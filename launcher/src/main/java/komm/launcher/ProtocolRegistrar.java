package komm.launcher;

import com.sun.jna.Platform;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Registers the {@code komm://} URI scheme with the OS so that links on the hub's
 * web pages (e.g. the "Open in Komm app" link on an invite page, which points at
 * {@code komm://invite/CODE}) can open Komm straight from the browser.
 *
 * <p>The handler is always the file jpack names in {@code JPACK_EXE}: the stub
 * ({@code Komm.exe}) of an installation, or the AppImage file itself. Never the Java
 * process we happen to run in, and never the stub inside an AppImage's temporary
 * mount. Clicking a link therefore starts the stub, which hands the URL to the launcher
 * (it runs its normal update flow) and to the client, which parses the invite code.
 *
 * <ul>
 *   <li><b>Windows</b>: per-user registry keys under {@code HKCU\Software\Classes\komm}, no admin
 *       rights required; the browser shows its "Open Komm?" confirmation first.</li>
 *   <li><b>Linux</b>: a per-user {@code komm-url-handler.desktop} entry with
 *       {@code MimeType=x-scheme-handler/komm} plus {@code xdg-mime default}. An AppImage has no
 *       installer to do this, and jpack's generated {@code .desktop} file has no MimeType.</li>
 * </ul>
 *
 * <p>Registration only happens when started by a stub ({@code JPACK_EXE} is set): a
 * {@code mvn javafx:run} dev session has nothing worth registering. It re-registers on every
 * start, so the registration stays correct if the install (or the AppImage file) moves.
 */
@Slf4j
public final class ProtocolRegistrar {

    public static final String SCHEME = "komm";
    private static final String DESKTOP_FILE = "komm-url-handler.desktop";

    private ProtocolRegistrar() {
    }

    /** Best-effort: never throws; failures only log a warning. */
    public static void registerIfPossible() {
        Optional<String> exe = packagedExecutable();
        if (exe.isEmpty()) {
            log.debug("Not started by a jpack stub; skipping {}:// registration", SCHEME);
            return;
        }
        if (Platform.isWindows()) {
            registerWindows(exe.get());
        } else if (Platform.isLinux()) {
            registerLinux(exe.get());
        }
    }

    /** The file that starts Komm again (jpack's {@code JPACK_EXE}), or empty in a dev run. */
    private static Optional<String> packagedExecutable() {
        String exe = System.getenv("JPACK_EXE");
        return exe == null || exe.isBlank() ? Optional.empty() : Optional.of(exe);
    }

    // -- Windows ----------------------------------------------------------------------------

    private static void registerWindows(String exePath) {
        try {
            // A .reg file + `reg import` sidesteps the quoting pitfalls of passing
            // values with embedded quotes/spaces through ProcessBuilder args.
            Path regFile = Files.createTempFile("komm-protocol", ".reg");
            // reg.exe expects version-5.00 files to be UTF-16LE with a BOM.
            Files.writeString(regFile, "﻿" + buildRegScript(exePath), StandardCharsets.UTF_16LE);
            Process proc = new ProcessBuilder("reg", "import", regFile.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
            if (!finished) proc.destroyForcibly();
            Files.deleteIfExists(regFile);
            if (!finished || proc.exitValue() != 0) {
                throw new IllegalStateException("reg import exited with "
                        + (finished ? proc.exitValue() : "timeout"));
            }
            log.info("Registered {}:// protocol handler -> {}", SCHEME, exePath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Could not register {}:// protocol handler: {}", SCHEME, e.toString());
        }
    }

    private static String buildRegScript(String exePath) {
        String exe = escapeRegString(exePath);
        return """
                Windows Registry Editor Version 5.00

                [HKEY_CURRENT_USER\\Software\\Classes\\%1$s]
                @="URL:Komm Protocol"
                "URL Protocol"=""

                [HKEY_CURRENT_USER\\Software\\Classes\\%1$s\\DefaultIcon]
                @="\\"%2$s\\",0"

                [HKEY_CURRENT_USER\\Software\\Classes\\%1$s\\shell\\open\\command]
                @="\\"%2$s\\" \\"%%1\\""
                """.formatted(SCHEME, exe);
    }

    /** .reg string values escape backslashes and double quotes. */
    private static String escapeRegString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // -- Linux ------------------------------------------------------------------------------

    private static void registerLinux(String exePath) {
        try {
            String home = System.getProperty("user.home");
            String dataHome = System.getenv("XDG_DATA_HOME");
            Path applications = (dataHome == null || dataHome.isBlank() ? Path.of(home, ".local", "share") : Path.of(dataHome))
                    .resolve("applications");
            Path desktop = applications.resolve(DESKTOP_FILE);
            String content = buildDesktopEntry(exePath);

            // Nothing to do (and no xdg-mime process to spawn) when it is already registered to this file.
            if (Files.isRegularFile(desktop) && content.equals(Files.readString(desktop, StandardCharsets.UTF_8))) {
                return;
            }
            Files.createDirectories(applications);
            Files.writeString(desktop, content, StandardCharsets.UTF_8);

            Process proc = new ProcessBuilder("xdg-mime", "default", DESKTOP_FILE, "x-scheme-handler/" + SCHEME)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
            if (!finished) proc.destroyForcibly();
            if (!finished || proc.exitValue() != 0) {
                throw new IllegalStateException("xdg-mime exited with " + (finished ? proc.exitValue() : "timeout"));
            }
            log.info("Registered {}:// protocol handler -> {}", SCHEME, exePath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Could not register {}:// protocol handler: {}", SCHEME, e.toString());
        }
    }

    private static String buildDesktopEntry(String exePath) {
        // Desktop Entry spec: an Exec argument containing spaces or reserved characters is double-quoted,
        // with backslash escapes for the characters that stay special inside the quotes.
        String quoted = "\"" + exePath.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
                .replace("%", "%%") + "\"";
        return """
                [Desktop Entry]
                Type=Application
                Name=Komm
                Comment=Komm voice and text chat
                Exec=%s %%u
                Terminal=false
                NoDisplay=true
                MimeType=x-scheme-handler/%s;
                """.formatted(quoted, SCHEME);
    }
}
