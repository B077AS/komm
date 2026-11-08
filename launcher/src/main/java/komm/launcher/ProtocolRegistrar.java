package komm.launcher;

import com.sun.jna.Platform;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Registers the {@code komm://} URI scheme with the OS so that links on the hub's
 * web pages (e.g. the "Open in Komm app" link on an invite page, which points at
 * {@code komm://invite/CODE}) can open the launcher straight from the browser.
 *
 * <p>On Windows this writes the per-user registry keys under
 * {@code HKCU\Software\Classes\komm} — no admin rights required. Once registered,
 * clicking such a link makes the browser show its "Open Komm?" confirmation and
 * then start the launcher with the full URL as its first argument, which
 * {@code UpdateManager} already forwards to the client jar.
 *
 * <p>Registration only happens when running from a packaged native executable
 * (e.g. a jpackage build): registering the transient {@code java.exe} command line
 * of a {@code mvn javafx:run} dev session would be useless. It re-registers on
 * every start, so the registry stays correct if the install location moves.
 */
@Slf4j
public final class ProtocolRegistrar {

    public static final String SCHEME = "komm";

    private ProtocolRegistrar() {
    }

    /** Best-effort: never throws; failures only log a warning. */
    public static void registerIfPossible() {
        if (!Platform.isWindows()) {
            // On Linux this is a packaging concern instead: a .desktop file with
            // MimeType=x-scheme-handler/komm; plus `xdg-mime default`.
            return;
        }
        packagedExecutable().ifPresentOrElse(
                ProtocolRegistrar::registerWindows,
                () -> log.debug("Not running from a packaged exe; skipping {}:// registration", SCHEME));
    }

    /** The current process's executable, unless it's a plain JVM (dev run). */
    private static Optional<String> packagedExecutable() {
        Optional<String> command = ProcessHandle.current().info().command();
        if (command.isEmpty()) return Optional.empty();
        String name = Path.of(command.get()).getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("java.exe") || name.equals("javaw.exe") || name.equals("java")) {
            return Optional.empty();
        }
        return command;
    }

    private static void registerWindows(String exePath) {
        try {
            // A .reg file + `reg import` sidesteps the quoting pitfalls of passing
            // values with embedded quotes/spaces through ProcessBuilder args.
            Path regFile = Files.createTempFile("komm-protocol", ".reg");
            // reg.exe expects version-5.00 files to be UTF-16LE with a BOM.
            Files.writeString(regFile, "\uFEFF" + buildRegScript(exePath), StandardCharsets.UTF_16LE);
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
}
