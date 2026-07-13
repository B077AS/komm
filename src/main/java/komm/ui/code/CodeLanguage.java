package komm.ui.code;

/**
 * Programming languages supported by code messages. The {@link #name()} of each
 * constant is what gets persisted in the {@code codeLanguage} column / payload
 * field; {@link #fromString(String)} parses it back tolerantly.
 */
public enum CodeLanguage {
    JAVA("Java"),
    PYTHON("Python"),
    JAVASCRIPT("JavaScript"),
    CSS("CSS"),
    HTML("HTML"),
    GO("Go"),
    PLAIN_TEXT("Plain text");

    private final String displayName;

    CodeLanguage(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * Parses a persisted language string back into an enum value. Falls back to
     * {@link #PLAIN_TEXT} for {@code null}, blank, or unknown values so old or
     * malformed data never breaks rendering.
     */
    public static CodeLanguage fromString(String s) {
        if (s == null || s.isBlank()) return PLAIN_TEXT;
        try {
            return CodeLanguage.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return switch (s.trim().toLowerCase()) {
                case "java" -> JAVA;
                case "python", "py" -> PYTHON;
                case "javascript", "js" -> JAVASCRIPT;
                case "css" -> CSS;
                case "html" -> HTML;
                case "go", "golang" -> GO;
                default -> PLAIN_TEXT;
            };
        }
    }
}
