package komm.ui.code;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Vocabulary;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.*;

public final class CodeHighlighter {

    private CodeHighlighter() {}

    /** A contiguous slice of the source. {@code styleClass} is {@code null} for unstyled text. */
    public record Token(int start, int end, String styleClass) {}

    // Keyword token-type sets auto-derived from each grammar's vocabulary (no hardcoded lists).
    // CSS/HTML entries are empty — their keywords are handled by name-based classification below.
    private static final Map<CodeLanguage, Set<Integer>> KEYWORD_TYPES;

    static {
        Map<CodeLanguage, Set<Integer>> map = new EnumMap<>(CodeLanguage.class);
        map.put(CodeLanguage.JAVA,       kwTypes(new JavaLexer(cs("")),        Set.of("BOOL_LITERAL", "NULL_LITERAL")));
        map.put(CodeLanguage.PYTHON,     kwTypes(new Python3Lexer(cs("")),      Set.of()));
        map.put(CodeLanguage.JAVASCRIPT, kwTypes(new JavaScriptLexer(cs("")),   Set.of("BooleanLiteral")));
        map.put(CodeLanguage.GO,         kwTypes(new GoLexer(cs("")),           Set.of()));
        KEYWORD_TYPES = Collections.unmodifiableMap(map);
    }

    private static org.antlr.v4.runtime.CharStream cs(String s) {
        return CharStreams.fromString(s);
    }

    private static Set<Integer> kwTypes(Lexer lexer, Set<String> extra) {
        Vocabulary vocab = lexer.getVocabulary();
        Set<Integer> types = new HashSet<>();
        for (int t = 1; t <= vocab.getMaxTokenType(); t++) {
            String literal = vocab.getLiteralName(t);
            if (literal != null) {
                String word = literal.substring(1, literal.length() - 1);
                if (word.matches("[a-zA-Z][a-zA-Z0-9_-]*")) types.add(t);
            }
            if (extra.contains(vocab.getSymbolicName(t))) types.add(t);
        }
        return Collections.unmodifiableSet(types);
    }

    // Per-language: identifier/paren token names and whether uppercase → cm-type.
    // CSS and HTML fall to default (null) because they use dedicated classifiers instead.
    private record Profile(String identSym, String parenSym, boolean typeHeuristic) {}

    private static Profile profileOf(CodeLanguage lang) {
        return switch (lang) {
            case JAVA       -> new Profile("IDENTIFIER", "LPAREN",     true);
            case PYTHON     -> new Profile("NAME",       "OPEN_PAREN", false);
            case JAVASCRIPT -> new Profile("Identifier", "OpenParen",  true);
            case GO         -> new Profile("IDENTIFIER", "L_PAREN",    true);
            default         -> new Profile(null,          null,         false);
        };
    }

    public static List<Token> tokens(String text, CodeLanguage language) {
        if (text == null || text.isEmpty()) return List.of();
        if (language == CodeLanguage.PLAIN_TEXT)
            return List.of(new Token(0, text.length(), null));

        Lexer lexer = createLexer(text, language);
        if (lexer == null) return List.of(new Token(0, text.length(), null));
        lexer.removeErrorListeners();

        Vocabulary vocab = lexer.getVocabulary();
        List<? extends org.antlr.v4.runtime.Token> raw = lexer.getAllTokens();

        Set<Integer> kwTypes = KEYWORD_TYPES.getOrDefault(language, Set.of());
        Profile prof = profileOf(language);
        String identSym = prof.identSym();
        String parenSym = prof.parenSym();

        String[] styles = new String[raw.size()];

        // Pass 1 — classify each token; identifiers left null for later passes
        for (int i = 0; i < raw.size(); i++) {
            org.antlr.v4.runtime.Token t = raw.get(i);
            String sym   = symName(t, vocab);
            String ttext = t.getText();
            styles[i] = switch (language) {
                case CSS  -> cssTokStyle(sym);
                case HTML -> htmlTokStyle(sym);
                default   -> genericTokStyle(sym, ttext, kwTypes, t, prof, identSym);
            };
        }

        if (identSym == null) {
            // CSS: Ident immediately after '.' → class selector → cm-type
            if (language == CodeLanguage.CSS) {
                for (int i = 0; i + 1 < raw.size(); i++) {
                    if ("Dot".equals(symName(raw.get(i), vocab))
                            && "Ident".equals(symName(raw.get(i + 1), vocab))) {
                        styles[i + 1] = "cm-type";
                    }
                }
            }
            return buildTokenList(text, raw, styles);
        }

        // Pass 2 — extend cm-annotation from @ to the identifier that follows it
        for (int i = 0; i < raw.size(); i++) {
            if (!"cm-annotation".equals(styles[i])) continue;
            for (int j = i + 1; j < raw.size(); j++) {
                String n = symName(raw.get(j), vocab);
                if (isWs(n)) continue;
                if (identSym.equals(n)) styles[j] = "cm-annotation";
                break;
            }
        }

        // Pass 3 — identifier immediately before '(' → cm-method
        for (int i = 0; i < raw.size(); i++) {
            if (styles[i] != null) continue;
            if (!identSym.equals(symName(raw.get(i), vocab))) continue;
            for (int j = i + 1; j < raw.size(); j++) {
                String n = symName(raw.get(j), vocab);
                if (isWs(n)) continue;
                if (parenSym != null && parenSym.equals(n)) styles[i] = "cm-method";
                break;
            }
        }

        // Pass 4 — remaining null-styled identifiers → cm-variable
        for (int i = 0; i < raw.size(); i++) {
            if (styles[i] != null) continue;
            if (identSym.equals(symName(raw.get(i), vocab))) styles[i] = "cm-variable";
        }

        return buildTokenList(text, raw, styles);
    }

    // ── Language-specific classifiers ────────────────────────────────────────

    private static String genericTokStyle(String sym, String ttext,
            Set<Integer> kwTypes, org.antlr.v4.runtime.Token t,
            Profile prof, String identSym) {
        if (kwTypes.contains(t.getType()))   return "cm-keyword";
        if (isComment(sym))                  return "cm-comment";
        if (isString(sym))                   return "cm-string";
        if (isNumber(sym))                   return "cm-number";
        if ("AT".equals(sym))                return "cm-annotation";
        if (identSym != null && identSym.equals(sym)
                && ttext != null && !ttext.isEmpty()
                && prof.typeHeuristic() && Character.isUpperCase(ttext.charAt(0)))
            return "cm-type";
        return null;
    }

    // CSS grammar: keywords are case-insensitive char-by-char patterns, not single literals,
    // so vocabulary scanning finds nothing. We match by symbolic token name instead.
    private static String cssTokStyle(String sym) {
        return switch (sym) {
            case "Comment"                                   -> "cm-comment";
            case "String_"                                   -> "cm-string";
            case "Number", "Dimension", "UnknownDimension",
                 "Percentage", "UnicodeRange"               -> "cm-number";
            case "Hash"                                      -> "cm-number";   // hex color or #id
            // CSS functions — grammar defines these as Ident+'(' combined into one token
            case "Function_", "Calc", "Var", "Url",
                 "Url_", "PseudoNot", "DxImageTransform"   -> "cm-method";
            case "Variable"                                  -> "cm-variable"; // --custom-prop
            case "Important"                                 -> "cm-keyword";
            // At-rules
            case "Import", "Page", "Media", "Namespace",
                 "Charset", "FontFace", "Supports",
                 "Keyframes", "Viewport", "CounterStyle",
                 "FontFeatureValues", "AtKeyword"           -> "cm-keyword";
            // Case-insensitive keywords (and, or, not, only, from, to)
            case "And", "Or", "Not", "MediaOnly",
                 "From", "To"                               -> "cm-keyword";
            default                                          -> null;
        };
    }

    // HTML grammar uses lexer modes. Token names are explicit and don't follow
    // the UPPER_CASE or MixedCase conventions of programming language grammars.
    private static String htmlTokStyle(String sym) {
        return switch (sym) {
            case "HTML_COMMENT",
                 "HTML_CONDITIONAL_COMMENT"                 -> "cm-comment";
            case "TAG_NAME"                                  -> "cm-type";
            case "ATTVALUE_VALUE"                            -> "cm-string";
            case "TAG_OPEN", "TAG_CLOSE",
                 "TAG_SLASH_CLOSE", "TAG_SLASH"             -> "cm-keyword";
            case "SCRIPT_OPEN", "STYLE_OPEN"                -> "cm-keyword";
            case "DTD"                                       -> "cm-type";
            default                                          -> null;
        };
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private static List<Token> buildTokenList(String text,
            List<? extends org.antlr.v4.runtime.Token> raw, String[] styles) {
        List<Token> result = new ArrayList<>(raw.size() + 4);
        int cursor = 0;
        for (int i = 0; i < raw.size(); i++) {
            org.antlr.v4.runtime.Token t = raw.get(i);
            if (t.getType() == org.antlr.v4.runtime.Token.EOF) break;
            int start = t.getStartIndex();
            int stop  = t.getStopIndex() + 1;
            if (start < 0 || start >= text.length()) continue;
            stop = Math.min(stop, text.length());
            if (stop <= start) continue;
            if (start < cursor) { cursor = Math.max(cursor, stop); continue; }
            if (start > cursor) result.add(new Token(cursor, start, null));
            result.add(new Token(start, stop, styles[i]));
            cursor = stop;
        }
        if (cursor < text.length()) result.add(new Token(cursor, text.length(), null));
        return result;
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

    private static Lexer createLexer(String text, CodeLanguage language) {
        var c = CharStreams.fromString(text);
        return switch (language) {
            case JAVA       -> new JavaLexer(c);
            case PYTHON     -> new Python3Lexer(c);
            case JAVASCRIPT -> new JavaScriptLexer(c);
            case GO         -> new GoLexer(c);
            case CSS        -> new css3Lexer(c);
            case HTML       -> new HTMLLexer(c);
            default         -> null;
        };
    }

    private static boolean isWs(String sym) {
        String u = sym.toUpperCase();
        return u.equals("WS") || u.equals("WHITESPACE") || u.contains("LINE_TERMINATOR");
    }

    private static boolean isComment(String sym) {
        return sym.toUpperCase().contains("COMMENT");
    }

    private static boolean isString(String sym) {
        String u = sym.toUpperCase();
        return u.contains("STRING") || u.equals("CHAR_LITERAL") || u.equals("TEXT_BLOCK")
                || u.equals("BYTES_LITERAL") || u.contains("REGULAREXPRESSION")
                || u.equals("BACKTICK") || u.contains("TEMPLATESTRING");
    }

    private static boolean isNumber(String sym) {
        String u = sym.toUpperCase();
        if (u.equals("NUMBER") || u.contains("IMAG")) return true;
        if (u.contains("INTEGER") || u.contains("FLOAT") || u.contains("DECIMAL")) return true;
        if (u.equals("DIMENSION") || u.equals("PERCENTAGE")) return true;
        // *_LIT / *LITERAL not involving strings/booleans/null → numeric (covers Go, Java)
        boolean endsLit = u.endsWith("_LIT") || u.endsWith("LITERAL");
        return endsLit
                && !u.contains("STRING") && !u.contains("CHAR")  && !u.contains("TEXT")
                && !u.contains("BOOL")   && !u.contains("NULL")  && !u.contains("NIL")
                && !u.contains("REGULAR") && !u.contains("TEMPLATE");
    }

    private static String symName(org.antlr.v4.runtime.Token t, Vocabulary vocab) {
        String n = vocab.getSymbolicName(t.getType());
        return n != null ? n : "";
    }
}
