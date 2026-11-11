package komm.update;

import com.sun.jna.Platform;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Keeps Windows' "Apps & Features" / Control Panel "Programs and Features" entry
 * in sync with the version that is actually installed.
 *
 * <p>The Inno Setup installer ({@code launcher/packaging/windows/komm.iss}) only writes
 * {@code DisplayVersion} once, at install time. Updates are swapped in by the jpack stub and
 * never run the installer again, so that registry value would otherwise go stale forever. The
 * client runs the new version after every swap, so it nudges just that one value at start -
 * nothing else Inno Setup owns (the uninstaller, the file list, {@code DisplayName}, ...) is touched.
 *
 * <p>Best-effort and silent. Only an installation started by a jpack stub (the stub sets
 * {@code JPACK_VERSION}) takes part: an IDE run or a hand-started jar would otherwise write its
 * own version over the installed one. A machine without the Inno Setup key (portable copy, AppImage)
 * is skipped too - this never fabricates a fake "installed program" entry.
 */
@Slf4j
public final class AppsFeaturesVersionUpdater {

    // Must match launcher/packaging/windows/komm.iss's AppId - Inno Setup names the
    // uninstall registry key "<AppId>_is1". komm.iss uses PrivilegesRequired=lowest
    // (per-user install), so this always lands under HKCU, never HKLM.
    private static final String UNINSTALL_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{5EE5B212-EE3B-43E1-8657-5C183E3FDE55}_is1";

    private AppsFeaturesVersionUpdater() {
    }

    /** Best-effort: never throws; failures only log at debug. */
    public static void updateIfInstalled() {
        String version = System.getenv("JPACK_VERSION");
        if (!Platform.isWindows() || version == null || version.isBlank()) return;
        try {
            if (run("query", UNINSTALL_KEY, "/v", "DisplayVersion") != 0) {
                log.debug("No Inno Setup uninstall entry found; not an installed copy, skipping DisplayVersion update");
                return;
            }
            run("add", UNINSTALL_KEY, "/v", "DisplayVersion", "/t", "REG_SZ", "/d", version, "/f");
            log.info("Updated Apps & Features version to {}", version);
        } catch (Exception e) {
            log.debug("Could not update Apps & Features version (non-fatal): {}", e.toString());
        }
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
