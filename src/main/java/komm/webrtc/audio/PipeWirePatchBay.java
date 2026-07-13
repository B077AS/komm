package komm.webrtc.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * PipeWire "tap" patch-bay that lets {@link LinuxAudioLoopbackCapture} capture the
 * system audio mix <em>excluding Komm's own output</em> — the Linux equivalent of the
 * Windows process-loopback EXCLUDE mode.
 *
 * <h3>Why a patch-bay instead of {@code @DEFAULT_MONITOR@}</h3>
 * <p>Capturing the default sink's monitor grabs the <em>entire</em> mix, including the
 * remote-participant voices and soundboard that Komm itself is playing — re-publishing
 * that echoes everyone back to the room. PulseAudio has no per-process subtract, so we
 * borrow the technique used by the Linux Discord audio-share tools
 * (<a href="https://github.com/Vencord/venmic">venmic</a>): create a dedicated capture
 * sink and <em>tap</em> every other application's output into it via PipeWire links,
 * leaving Komm's own streams unlinked.</p>
 *
 * <h3>Mechanism</h3>
 * <ol>
 *   <li>{@code pactl load-module module-null-sink sink_name=komm_screen_audio} — a virtual
 *       sink whose {@code .monitor} source we capture. Records the module index for teardown.</li>
 *   <li>{@code pw-dump} enumerates the graph as JSON. For every {@code Stream/Output/Audio}
 *       node whose {@code application.process.id} differs from ours, a {@code pw-link} is added
 *       from each of its output ports into the null sink's input ports. The application keeps
 *       playing to its real device (its original links are untouched) — we merely duplicate the
 *       signal into our capture sink.</li>
 *   <li>A background thread re-runs the scan every ~1.5&nbsp;s so apps that start playing
 *       mid-share are picked up.</li>
 *   <li>{@link #tearDown()} unloads the null-sink module, which drops every tap link in one go.
 *       The user's real routing was never modified, so a crash leaves nothing worse than an
 *       orphaned (silent) virtual sink that disappears on the next PipeWire restart.</li>
 * </ol>
 *
 * <p>Requires PipeWire with its CLI tools ({@code pw-dump}, {@code pw-link}) plus {@code pactl}.
 * On classic PulseAudio-only systems {@link #isAvailable()} returns false and the caller falls
 * back to a plain {@code @DEFAULT_MONITOR@} full-mix capture.</p>
 */
@Slf4j
class PipeWirePatchBay {

    private static final String SINK_NAME = "komm_screen_audio";
    private static final String SINK_DESC = "Komm_Screen_Audio";
    /** PulseAudio/PipeWire monitor-source name to record once the sink exists. */
    static final String MONITOR_DEVICE = SINK_NAME + ".monitor";

    private static final long RESCAN_INTERVAL_MS = 1_500;

    private final long ownPid = ProcessHandle.current().pid();
    /** "srcPortId->dstPortId" pairs we've already issued a pw-link for (dedup across rescans). */
    private final Set<String> linkedPairs = ConcurrentHashMap.newKeySet();

    private volatile boolean active;
    private volatile String moduleIndex;   // pactl module id of the null sink
    private Thread rescanThread;

    // ── Availability ──────────────────────────────────────────────────────────

    /** True only when the PipeWire CLI tooling needed for the tap approach is present. */
    static boolean isAvailable() {
        boolean pwDump = commandExists("pw-dump");
        boolean pwLink = commandExists("pw-link");
        boolean pactl  = commandExists("pactl");
        if (!(pwDump && pwLink && pactl)) {
            log.warn("[ScreenAudio] PipeWire tap unavailable — missing CLI tools (pw-dump={}, "
                    + "pw-link={}, pactl={}). Install the PipeWire utils package (e.g. "
                    + "'pipewire-utils'/'pipewire-tools') to exclude Komm's own audio from the share.",
                    pwDump, pwLink, pactl);
            return false;
        }
        return true;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Creates the capture null sink, taps in all non-Komm application streams, and starts the
     * background rescan.
     *
     * @return the monitor-source device name to capture (e.g. {@code komm_screen_audio.monitor})
     * @throws IllegalStateException if the null sink cannot be created
     */
    synchronized String setUp() throws Exception {
        // Clean up any leftover sink from a previously crashed session before creating a new one.
        unloadStaleSinks();

        String out = exec(5, "pactl", "load-module", "module-null-sink",
                "sink_name=" + SINK_NAME,
                "sink_properties=device.description=" + SINK_DESC);
        String idx = out.trim();
        if (!idx.matches("\\d+")) {
            throw new IllegalStateException("module-null-sink load failed: " + idx);
        }
        moduleIndex = idx;
        active = true;

        linkOnce();   // tap whatever is already playing

        rescanThread = new Thread(this::rescanLoop, "screen-audio-pw-rescan");
        rescanThread.setDaemon(true);
        rescanThread.start();

        log.info("[ScreenAudio] PipeWire tap ready (sink={}, module={}) — Komm (pid={}) excluded",
                SINK_NAME, moduleIndex, ownPid);
        return MONITOR_DEVICE;
    }

    /** Unloads the null-sink module (dropping every tap link) and stops the rescan thread. */
    synchronized void tearDown() {
        active = false;
        Thread t = rescanThread;
        rescanThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        String idx = moduleIndex;
        moduleIndex = null;
        if (idx != null) {
            try { exec(5, "pactl", "unload-module", idx); }
            catch (Exception e) {
                log.warn("[ScreenAudio] Failed to unload capture sink module {}: {}", idx, e.getMessage());
            }
        }
        linkedPairs.clear();
        log.info("[ScreenAudio] PipeWire tap torn down");
    }

    // ── Linking ───────────────────────────────────────────────────────────────

    private void rescanLoop() {
        while (active) {
            try { Thread.sleep(RESCAN_INTERVAL_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            if (!active) break;
            try { linkOnce(); }
            catch (Throwable t) { log.debug("[ScreenAudio] rescan failed: {}", t.getMessage()); }
        }
    }

    /** Snapshot the graph and ensure every non-Komm application stream is tapped into our sink. */
    private void linkOnce() {
        String dump;
        try { dump = exec(5, "pw-dump"); }
        catch (Exception e) { log.debug("[ScreenAudio] pw-dump failed: {}", e.getMessage()); return; }

        JsonArray arr;
        try { arr = JsonParser.parseString(dump).getAsJsonArray(); }
        catch (Exception e) { log.debug("[ScreenAudio] pw-dump parse failed: {}", e.getMessage()); return; }

        Map<Integer, NodeInfo> nodes = new HashMap<>();
        List<PortInfo> ports = new ArrayList<>();
        // PipeWire often records application.process.id on the Client object rather than on every
        // stream Node, so we collect the set of clients belonging to our own process and exclude
        // any node owned by them. This is what makes Komm's own output reliably excluded.
        Set<Integer> ownClients = new java.util.HashSet<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String type = optString(o, "type");
            int id = optInt(o, "id", -1);
            JsonObject props = nestedProps(o);
            if ("PipeWire:Interface:Node".equals(type)) {
                NodeInfo n = new NodeInfo();
                n.id = id;
                n.mediaClass = optString(props, "media.class");
                n.pid = optLong(props, "application.process.id", -1);
                n.clientId = (int) optLong(props, "client.id", -1);
                n.name = optString(props, "node.name");
                n.appName = optString(props, "application.name");
                nodes.put(id, n);
            } else if ("PipeWire:Interface:Port".equals(type)) {
                PortInfo p = new PortInfo();
                p.id = id;
                p.nodeId = (int) optLong(props, "node.id", -1);
                p.direction = optString(props, "port.direction");
                p.channel = optString(props, "audio.channel");
                ports.add(p);
            } else if ("PipeWire:Interface:Client".equals(type)) {
                if (optLong(props, "application.process.id", -1) == ownPid) {
                    ownClients.add(id);
                }
            }
        }

        // Locate our capture sink and its input ports (FL/FR).
        NodeInfo sink = nodes.values().stream()
                .filter(n -> SINK_NAME.equals(n.name)).findFirst().orElse(null);
        if (sink == null) return;   // sink not yet visible in the graph

        Integer inFL = null, inFR = null;
        for (PortInfo p : ports) {
            if (p.nodeId == sink.id && "in".equals(p.direction)) {
                if ("FL".equals(p.channel)) inFL = p.id;
                else if ("FR".equals(p.channel)) inFR = p.id;
            }
        }
        if (inFL == null && inFR == null) return;

        // Tap every application playback stream except Komm's own output.
        for (NodeInfo n : nodes.values()) {
            if (!"Stream/Output/Audio".equals(n.mediaClass)) continue;  // app playback streams only
            if (n.id == sink.id) continue;
            // Exclude ourselves so we never re-publish Komm's own audio (no echo). A node is "ours"
            // if it carries our PID directly, or is owned by one of our PipeWire clients.
            if (n.pid == ownPid || (n.clientId >= 0 && ownClients.contains(n.clientId))) {
                log.debug("[ScreenAudio] Excluding own stream node {} (name={}, app={}, pid={}, client={})",
                        n.id, n.name, n.appName, n.pid, n.clientId);
                continue;
            }
            log.debug("[ScreenAudio] Tapping app stream node {} (name={}, app={}, pid={}, client={})",
                    n.id, n.name, n.appName, n.pid, n.clientId);

            List<PortInfo> outs = new ArrayList<>();
            for (PortInfo p : ports) {
                if (p.nodeId == n.id && "out".equals(p.direction)) outs.add(p);
            }
            if (outs.isEmpty()) continue;

            boolean mono = outs.size() == 1;
            for (PortInfo p : outs) {
                if (!mono && "FL".equals(p.channel)) {
                    tryLink(p.id, inFL);
                } else if (!mono && "FR".equals(p.channel)) {
                    tryLink(p.id, inFR);
                } else {
                    // Mono / unknown channel → feed both sink inputs so it isn't lost.
                    tryLink(p.id, inFL);
                    tryLink(p.id, inFR);
                }
            }
        }
    }

    private void tryLink(Integer src, Integer dst) {
        if (src == null || dst == null) return;
        String key = src + "->" + dst;
        if (!linkedPairs.add(key)) return;   // already attempted this session
        try { exec(3, "pw-link", String.valueOf(src), String.valueOf(dst)); }
        catch (Exception e) { log.debug("[ScreenAudio] pw-link {} failed: {}", key, e.getMessage()); }
    }

    private void unloadStaleSinks() {
        try {
            String mods = exec(5, "pactl", "list", "short", "modules");
            for (String line : mods.split("\n")) {
                if (line.contains("module-null-sink") && line.contains("sink_name=" + SINK_NAME)) {
                    String id = line.split("\\s+")[0];
                    try { exec(3, "pactl", "unload-module", id); } catch (Exception ignored) {}
                    log.debug("[ScreenAudio] Removed stale capture sink module {}", id);
                }
            }
        } catch (Exception ignored) {}
    }

    // ── Process / JSON helpers ────────────────────────────────────────────────

    private static boolean commandExists(String name) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v " + name).start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs {@code cmd}, returns stdout. Throws on timeout; a non-zero exit is left to the caller. */
    private static String exec(int timeoutSec, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        byte[] out = p.getInputStream().readAllBytes();   // drains stdout (process closes it on exit)
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("timed out: " + String.join(" ", cmd));
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    private static JsonObject nestedProps(JsonObject o) {
        if (o.has("info") && o.get("info").isJsonObject()) {
            JsonObject info = o.getAsJsonObject("info");
            if (info.has("props") && info.get("props").isJsonObject()) {
                return info.getAsJsonObject("props");
            }
        }
        return null;
    }

    private static String optString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try { return o.get(key).getAsString(); } catch (Exception e) { return null; }
    }

    private static int optInt(JsonObject o, String key, int def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsInt(); } catch (Exception e) { return def; }
    }

    private static long optLong(JsonObject o, String key, long def) {
        String s = optString(o, key);
        if (s == null) return def;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private static final class NodeInfo {
        int id;
        long pid;
        int clientId;
        String mediaClass;
        String name;
        String appName;
    }

    private static final class PortInfo {
        int id;
        int nodeId;
        String direction;
        String channel;
    }
}
