package komm.launcher.update;

import com.sun.jna.Platform;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Keeps Windows' "Apps & Features" / Control Panel "Programs and Features" entry
 * in sync with the client version the runtime jar-swap just installed.
 *
 * <p>The Inno Setup installer ({@code packaging/windows/komm.iss}) only writes
 * {@code DisplayVersion} once, at install time. The whole point of swapping the
 * jar in place is that the installer never runs again, so that registry value
 * would otherwise go stale forever even as the actual installed client keeps
 * moving. This nudges just that one value after every successful swap — nothing
 * else Inno Setup owns (the uninstaller, the file list, {@code DisplayName}, ...)
 * is touched.
 *
 * <p>Best-effort and silent: a portable/non-installer copy never created this
 * registry key, so {@link #keyExists()} confirms it first — a missing key means
 * "nothing to update", not a failure, and this never fabricates a fake
 * "installed program" entry.
 */
@Slf4j
final class AppsFeaturesVersionUpdater {

    // Must match packaging/windows/komm.iss's AppId - Inno Setup names the
    // uninstall registry key "<AppId>_is1". komm.iss uses PrivilegesRequired=lowest
    // (per-user install, matching the HKCU komm:// registration in
    // ProtocolRegistrar), so this always lands under HKCU, never HKLM.
    private static final String UNINSTALL_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{5EE5B212-EE3B-43E1-8657-5C183E3FDE55}_is1";

    private AppsFeaturesVersionUpdater() {
    }

    /** Best-effort: never throws; failures only log at debug. */
    static void updateIfInstalled(String version) {
        if (!Platform.isWindows() || version == null || version.isBlank()) return;
        try {
            if (!keyExists()) {
                log.debug("No Inno Setup uninstall entry found; not a real install, skipping DisplayVersion update");
                return;
            }
            run("add", UNINSTALL_KEY, "/v", "DisplayVersion", "/t", "REG_SZ", "/d", version, "/f");
            log.info("Updated Apps & Features version to {}", version);
        } catch (Exception e) {
            log.debug("Could not update Apps & Features version (non-fatal): {}", e.toString());
        }
    }

    private static boolean keyExists() throws Exception {
        return run("query", UNINSTALL_KEY, "/v", "DisplayVersion") == 0;
    }

    private static int run(String... regArgs) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("reg");
        command.addAll(List.of(regArgs));
        Process proc = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IllegalStateException("reg " + regArgs[0] + " timed out");
        }
        return proc.exitValue();
    }
}
