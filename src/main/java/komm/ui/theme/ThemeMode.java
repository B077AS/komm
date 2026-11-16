package komm.ui.theme;

/** Light/dark preference. {@link #SYSTEM} follows the OS colour scheme (JavaFX Platform preferences). */
public enum ThemeMode {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System");

    private final String displayName;

    ThemeMode(String displayName) { this.displayName = displayName; }

    public String getDisplayName() { return displayName; }

    public static ThemeMode fromSetting(String s) {
        if (s != null) {
            try { return valueOf(s.trim()); } catch (IllegalArgumentException ignored) { }
        }
        return DARK;
    }
}
