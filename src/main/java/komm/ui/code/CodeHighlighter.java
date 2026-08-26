package komm.ui.code;

import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rsyntaxtextarea.modes.CSSTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.GoTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.HTMLTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.JavaScriptTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.JavaTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.PythonTokenMaker;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import javax.swing.text.Segment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Tokenizes code with RSyntaxTextArea's {@link TokenMaker} parsers, used
 * purely headlessly here — {@code getTokenList} is a plain string scanner,
 * no Swing UI is ever created — and maps each token to one of the fixed
 * {@code cm-*} CSS classes that {@code style.css} colors, for RichTextFX
 * to render in the app's own {@code CodeArea}.
 *
 * <p>Two things about the underlying tokenizer are not obvious from its public
 * API and matter a lot here (confirmed against RSTA's own JFlex grammar
 * sources, e.g. {@code JavaTokenMaker.flex}):
 * <ul>
 *   <li>{@code . , ;} are lexed as {@link TokenTypes#IDENTIFIER}, not a
 *       separator type — {@link #isIdentifierShaped} filters those out before
 *       any identifier-only heuristic runs.</li>
 *   <li>{@link TokenTypes#FUNCTION} is <em>not</em> "this is a method call" —
 *       for Java it is a ~100-entry hardcoded whitelist of well-known JDK
 *       class/exception names (Timer, TreeMap, UUID, ...), i.e. types. Real
 *       method-call detection is done ourselves via {@link #isFollowedByOpenParen}.</li>
 * </ul>
 */
public class CodeHighlighter {

    /** A contiguous slice of the source. {@code styleClass} is {@code null} for unstyled text. */
    public record Token(int start, int end, String styleClass) {}

    private static TokenMaker tokenMakerFor(CodeLanguage language) {
        return switch (language) {
            case JAVA       -> new JavaTokenMaker();
            case PYTHON     -> new PythonTokenMaker();
            case JAVASCRIPT -> new JavaScriptTokenMaker();
            case GO         -> new GoTokenMaker();
            case CSS        -> new CSSTokenMaker();
            case HTML       -> new HTMLTokenMaker();
            default         -> null;
        };
    }

    public static List<Token> tokens(String text, CodeLanguage language) {
        if (text == null || text.isEmpty()) return List.of();
        TokenMaker tokenMaker = tokenMakerFor(language);
        if (tokenMaker == null) return List.of(new Token(0, text.length(), null));

        List<Token> result = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int cursor = 0;
        int offset = 0;
        int lineStartTokenType = TokenTypes.NULL;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Segment segment = new Segment(line.toCharArray(), 0, line.length());
            int lineCharEnd = offset + line.length();

            org.fife.ui.rsyntaxtextarea.Token first = tokenMaker.getTokenList(segment, lineStartTokenType, offset);
            boolean importLine = isImportOrPackageLine(first, language);
            boolean prevWasNew = false;

            for (org.fife.ui.rsyntaxtextarea.Token t = first; t != null; t = t.getNextToken()) {
                if (t.getType() == TokenTypes.NULL || t.length() <= 0) continue;
                int rawStart = t.getOffset();
                // A token still "open" at end-of-line (an unterminated block comment or
                // template literal continuing onto the next line) can report a length that
                // runs past what this line's own char array actually holds — a real quirk
                // in RSTA's generated tokenizers, not just a theoretical edge case (verified
                // against JavaScriptTokenMaker). Any content-inspecting helper below (charAt,
                // is(), isSingleChar()) would throw ArrayIndexOutOfBoundsException on it, so
                // such tokens are only ever classified by type, never by content.
                boolean fitsInLine = rawStart + t.length() <= lineCharEnd;
                int end = Math.min(rawStart + t.length(), lineCharEnd);
                if (end <= cursor) continue;
                int start = Math.max(rawStart, cursor);
                if (start >= end) continue;
                if (start > cursor) result.add(new Token(cursor, start, null));

                boolean identifierShaped = fitsInLine && isIdentifierShaped(t);
                String styleClass;
                if (importLine && identifierShaped && t.getType() != TokenTypes.RESERVED_WORD) {
                    // Whole dotted path of an import/package statement, not just the class name.
                    styleClass = "cm-type";
                } else if (identifierShaped && isCallable(t.getType()) && isFollowedByOpenParen(t)) {
                    // `new Foo(...)` is a type/constructor reference; anything else immediately
                    // followed by `(` is an actual call — RSTA has no such lookahead itself.
                    styleClass = prevWasNew ? "cm-type" : "cm-method";
                } else if (fitsInLine) {
                    styleClass = styleFor(t, language);
                } else {
                    styleClass = safeStyleFor(t.getType());
                }
                result.add(new Token(start, end, styleClass));
                cursor = end;

                if (!t.isWhitespace()) prevWasNew = fitsInLine && t.is(TokenTypes.RESERVED_WORD, "new");
            }

            int lineEnd = offset + line.length();
            if (lineEnd > cursor) {
                result.add(new Token(cursor, lineEnd, null));
                cursor = lineEnd;
            }

            lineStartTokenType = tokenMaker.getLastTokenTypeOnLine(segment, lineStartTokenType);
            offset = lineEnd + 1; // '\n' consumed between this line and the next

            if (i < lines.length - 1 && offset <= text.length()) {
                result.add(new Token(cursor, offset, null)); // the '\n' itself
                cursor = offset;
            }
        }

        if (cursor < text.length()) result.add(new Token(cursor, text.length(), null));
        return result;
    }

    // `. , ;` come back as IDENTIFIER from RSTA's own grammar (see class javadoc) —
    // this is what actually distinguishes a real name from stray punctuation.
    private static boolean isIdentifierShaped(org.fife.ui.rsyntaxtextarea.Token t) {
        return t.length() > 0 && Character.isJavaIdentifierStart(t.charAt(0));
    }

    // Token types that represent some kind of name — the only ones worth checking
    // for a following '(' to decide "this is being called".
    private static boolean isCallable(int type) {
        return type == TokenTypes.IDENTIFIER || type == TokenTypes.VARIABLE || type == TokenTypes.FUNCTION;
    }

    private static boolean isFollowedByOpenParen(org.fife.ui.rsyntaxtextarea.Token t) {
        for (org.fife.ui.rsyntaxtextarea.Token n = t.getNextToken(); n != null; n = n.getNextToken()) {
            if (n.getType() == TokenTypes.NULL || n.isWhitespace()) continue;
            // Type check first: '(' is always SEPARATOR, so this never reaches isSingleChar
            // (which touches the token's characters) on some other, possibly malformed token.
            return n.getType() == TokenTypes.SEPARATOR && n.isSingleChar('(');
        }
        return false;
    }

    // Languages where an identifier starting with an uppercase letter is treated
    // as a type reference (class/interface usage) rather than a plain variable —
    // RSTA's DATA_TYPE token only fires for built-in primitive keywords, so
    // without this every class name (extremely common) would fall through to
    // the plain-identifier color and the whole snippet would read flat. Also
    // gates the ALL_CAPS-means-constant convention these languages share.
    private static boolean usesCapitalizedTypeHeuristic(CodeLanguage language) {
        return language == CodeLanguage.JAVA || language == CodeLanguage.JAVASCRIPT || language == CodeLanguage.GO;
    }

    // True if `first` (the first token of a line) opens a Java import/package
    // statement — the whole dotted path gets colored as a type reference, since
    // that is how the real IntelliJ Java editor renders it, not just the leaf
    // class name at the end.
    private static boolean isImportOrPackageLine(org.fife.ui.rsyntaxtextarea.Token first, CodeLanguage language) {
        if (language != CodeLanguage.JAVA) return false;
        for (org.fife.ui.rsyntaxtextarea.Token t = first; t != null; t = t.getNextToken()) {
            if (t.getType() == TokenTypes.NULL || t.isWhitespace()) continue;
            return t.is(TokenTypes.RESERVED_WORD, "import") || t.is(TokenTypes.RESERVED_WORD, "package");
        }
        return false;
    }

    // ALL_CAPS identifiers (MAX_RETRIES, FOO_BAR) read as constants/`final` fields
    // by convention in these languages — our tokenizer has no semantic analysis to
    // confirm `final`/`static final`, so this textual convention is the best proxy.
    private static boolean looksLikeConstant(org.fife.ui.rsyntaxtextarea.Token t) {
        // Single uppercase letters are overwhelmingly generic type parameters
        // (T, E, K, V...), not constants — require at least two characters.
        if (t.length() < 2) return false;
        boolean hasLetter = false;
        for (int i = 0, len = t.length(); i < len; i++) {
            char c = t.charAt(i);
            if (Character.isUpperCase(c)) hasLetter = true;
            else if (c != '_' && !Character.isDigit(c)) return false;
        }
        return hasLetter;
    }

    // Maps RSTA's token-type constants onto the app's fixed style-class palette.
    // Only reached for tokens the call-site didn't already resolve via the
    // import-path or method-call checks above.
    private static String styleFor(org.fife.ui.rsyntaxtextarea.Token t, CodeLanguage language) {
        int type = t.getType();
        if (type == TokenTypes.IDENTIFIER) {
            // Stray '.', ',', ';' come back as IDENTIFIER too (see class javadoc) — leave unstyled.
            if (!isIdentifierShaped(t)) return null;
            if (usesCapitalizedTypeHeuristic(language)) {
                if (looksLikeConstant(t)) return "cm-final";
                if (Character.isUpperCase(t.charAt(0))) return "cm-type";
            }
            return "cm-variable";
        }
        return styleForType(type);
    }

    // Classifies by RSTA type alone — never touches the token's characters, so it
    // is the only classifier safe to call on a token whose reported length runs
    // past this line's actual bounds (see the fitsInLine check in tokens()).
    private static String safeStyleFor(int type) {
        return type == TokenTypes.IDENTIFIER ? null : styleForType(type);
    }

    private static String styleForType(int type) {
        return switch (type) {
            case TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2,
                 TokenTypes.LITERAL_BOOLEAN, TokenTypes.MARKUP_TAG_DELIMITER,
                 TokenTypes.PREPROCESSOR                                       -> "cm-keyword";
            case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR,
                 TokenTypes.LITERAL_BACKQUOTE, TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE,
                 TokenTypes.MARKUP_CDATA                                       -> "cm-string";
            case TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE,
                 TokenTypes.COMMENT_DOCUMENTATION, TokenTypes.COMMENT_KEYWORD,
                 TokenTypes.COMMENT_MARKUP, TokenTypes.MARKUP_COMMENT          -> "cm-comment";
            case TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT,
                 TokenTypes.LITERAL_NUMBER_HEXADECIMAL                         -> "cm-number";
            case TokenTypes.ANNOTATION                                        -> "cm-annotation";
            // FUNCTION is RSTA's hardcoded well-known-JDK-type whitelist (Timer, UUID, ...) —
            // these are class names, not calls, so they belong with DATA_TYPE, not cm-method.
            case TokenTypes.DATA_TYPE, TokenTypes.FUNCTION,
                 TokenTypes.MARKUP_TAG_NAME, TokenTypes.MARKUP_DTD             -> "cm-type";
            case TokenTypes.VARIABLE, TokenTypes.MARKUP_TAG_ATTRIBUTE          -> "cm-variable";
            default                                                           -> null;
        };
    }

    public static StyleSpans<Collection<String>> computeHighlighting(String text, CodeLanguage language) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        List<Token> toks = tokens(text, language);
        if (toks.isEmpty()) {
            spansBuilder.add(Collections.emptyList(), text == null ? 0 : text.length());
            return spansBuilder.create();
        }
        for (Token t : toks) {
            Collection<String> style = t.styleClass() == null
                    ? Collections.emptyList() : Collections.singleton(t.styleClass());
            spansBuilder.add(style, t.end() - t.start());
        }
        return spansBuilder.create();
    }
}
