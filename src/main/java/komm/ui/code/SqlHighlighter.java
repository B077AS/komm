package komm.ui.code;

import com.github.vertical_blank.sqlformatter.core.FormatConfig;
import com.github.vertical_blank.sqlformatter.core.Token;
import com.github.vertical_blank.sqlformatter.core.TokenTypes;
import com.github.vertical_blank.sqlformatter.core.Tokenizer;
import com.github.vertical_blank.sqlformatter.languages.PostgreSqlFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL highlighting via the tokenizer of {@code sql-formatter} (vertical-blank). RSyntaxTextArea's
 * own SQL mode misclassifies too much ({@code .} as a number, no {@code BIGINT}/{@code LIMIT}).
 */
final class SqlHighlighter {

    private static final Tokenizer TOKENIZER =
            new Tokenizer(new PostgreSqlFormatter(FormatConfig.builder().build()).dialectConfig());

    private SqlHighlighter() {
    }

    /** Tokens covering {@code text} completely; {@code styleClass} null = unstyled. */
    static List<CodeHighlighter.Token> tokens(String text) {
        List<Token> toks = TOKENIZER.tokenize(text).toList();
        List<CodeHighlighter.Token> out = new ArrayList<>();
        int pos = 0;
        for (int i = 0; i < toks.size(); i++) {
            Token t = toks.get(i);
            int start = text.indexOf(t.value, pos);
            if (start < 0) break; // tokenizer normalised something: leave the rest unstyled
            int end = start + t.value.length();
            if (start > pos) out.add(new CodeHighlighter.Token(pos, start, null));
            out.add(new CodeHighlighter.Token(start, end, styleFor(t, i + 1 < toks.size() ? toks.get(i + 1) : null)));
            pos = end;
        }
        if (pos < text.length()) out.add(new CodeHighlighter.Token(pos, text.length(), null));
        return out;
    }

    private static String styleFor(Token t, Token next) {
        return switch (t.type) {
            case RESERVED, RESERVED_TOP_LEVEL, RESERVED_TOP_LEVEL_NO_INDENT, RESERVED_NEWLINE -> "cm-keyword";
            case STRING -> "cm-string";
            case NUMBER -> "cm-number";
            case LINE_COMMENT, BLOCK_COMMENT -> "cm-comment";
            case PLACEHOLDER -> "cm-variable";
            case WORD -> next != null && next.type == TokenTypes.OPEN_PAREN && next.whitespaceBefore.isEmpty()
                    ? "cm-method" : null;
            default -> null; // operators, parentheses
        };
    }
}
