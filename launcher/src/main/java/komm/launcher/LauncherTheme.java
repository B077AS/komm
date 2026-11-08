package komm.launcher;

import komm.ui.theme.AppTheme;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The user's accent colour for the launcher window. Accent choice is a per-user client setting,
 * but the launcher runs before anyone is logged in, so the client mirrors the active accent into
 * {@code config/accent} ({@code ThemeManager}); a missing/unknown value means the default purple.
 */
@Slf4j
final class LauncherTheme {

    private LauncherTheme() {}

    static AppTheme savedAccent() {
        try {
            Path file = Launcher.getAppDataDirectory().resolve("config").resolve("accent");
            if (Files.isRegularFile(file)) {
                return AppTheme.valueOf(Files.readString(file, StandardCharsets.UTF_8).trim());
            }
        } catch (Exception e) {
            log.debug("No usable saved accent: {}", e.toString());
        }
        return AppTheme.PURPLE;
    }

    /** A tiny scene stylesheet with the accent palette — the same override the client applies. */
    static String accentStylesheet(AppTheme theme) {
        try {
            Path file = Files.createTempFile("komm-launcher-accent-", ".css");
            file.toFile().deleteOnExit();
            Files.writeString(file, ".root{" + theme.getCssOverride() + "}", StandardCharsets.UTF_8);
            return file.toUri().toString();
        } catch (Exception e) {
            log.debug("Could not write accent stylesheet: {}", e.toString());
            return "data:text/css,";
        }
    }
}
