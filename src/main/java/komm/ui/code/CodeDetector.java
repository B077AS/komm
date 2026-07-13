package komm.ui.code;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Heuristics for deciding whether pasted text is source code (so the input box
 * can divert it to the code editor) and for guessing which language it is.
 *
 * <p>The detector is intentionally conservative: it only fires on multi-line
 * text with several independent code signals, so ordinary prose / pasted URLs
 * are not mistaken for code.
 */
public final class CodeDetector {

    private CodeDetector() {
    }

    private static final Pattern JAVA_SIGNAL = Pattern.compile(
            "\\b(public|private|protected|class|interface|void)\\b"
                    + "|\\bimport\\s+[\\w.]+;|\\bnew\\s+\\w+\\s*\\(|System\\.out\\.");

    private static final Pattern PYTHON_SIGNAL = Pattern.compile(
            "(^|\\n)\\s*(def\\s+\\w+\\s*\\(|class\\s+\\w+\\s*[:(]|import\\s+\\w+|from\\s+\\w+\\s+import|print\\s*\\()");

    /**
     * Returns {@code true} if {@code text} is reasonably likely to be code.
     */
    public static boolean looksLikeCode(String text) {
        if (text == null) return false;
        String t = text.strip();
        if (t.length() < 24) return false;

        String[] lines = t.split("\\R", -1);
        if (lines.length < 2) return false;

        int score = 0;
        if (JAVA_SIGNAL.matcher(t).find()) score += 2;
        if (PYTHON_SIGNAL.matcher(t).find()) score += 2;

        long braces = t.chars().filter(c -> c == '{' || c == '}').count();
        long semis = t.chars().filter(c -> c == ';').count();
        long indented = Arrays.stream(lines)
                .filter(l -> l.startsWith("    ") || l.startsWith("\t"))
                .count();

        if (braces >= 2) score += 1;
        if (semis >= 2) score += 1;
        if (indented >= 2) score += 1;
        if (t.contains("=>") || t.contains("->") || t.contains("::")) score += 1;

        return score >= 3;
    }

    /**
     * Best-effort language guess for the code editor's initial selection.
     */
    public static CodeLanguage guessLanguage(String text) {
        if (text == null) return CodeLanguage.PLAIN_TEXT;
        boolean python = PYTHON_SIGNAL.matcher(text).find();
        boolean java = JAVA_SIGNAL.matcher(text).find();

        // Braces/semicolons strongly imply a C-family (Java here) snippet.
        if (java || text.contains("{") || text.contains(";")) {
            if (python && !text.contains("{") && !text.contains(";")) return CodeLanguage.PYTHON;
            return CodeLanguage.JAVA;
        }
        if (python) return CodeLanguage.PYTHON;
        return CodeLanguage.PLAIN_TEXT;
    }
}
