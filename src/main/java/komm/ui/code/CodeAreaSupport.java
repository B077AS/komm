package komm.ui.code;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import org.fxmisc.richtext.CodeArea;
import org.reactfx.collection.LiveList;
import org.reactfx.value.Val;

import java.util.Collection;
import java.util.Collections;
import java.util.function.IntFunction;

/**
 * Shared styling helpers for our {@link CodeArea} instances (bubble + modal).
 *
 * <p>RichTextFX's stock {@code LineNumberFactory} paints the gutter background
 * and text colour <em>programmatically</em>, which CSS cannot override (hence the
 * stubborn white square). This factory leaves all styling to {@code .lineno} in
 * the stylesheet, right-aligns the numbers, and pads them to the width of the
 * largest line number so the gap to the code never shifts as digits grow.
 */
public final class CodeAreaSupport {

    private CodeAreaSupport() {
    }

    public static IntFunction<Node> lineNumberFactory(CodeArea area) {
        Val<Integer> nParagraphs = LiveList.sizeOf(area.getParagraphs());
        return idx -> {
            Label lineNo = new Label();
            lineNo.getStyleClass().add("lineno");
            lineNo.setAlignment(Pos.CENTER_RIGHT);
            lineNo.setMaxHeight(Double.MAX_VALUE);
            Val<String> formatted = nParagraphs.map(n -> format(idx + 1, n));
            lineNo.textProperty().bind(formatted.conditionOnShowing(lineNo));
            return lineNo;
        };
    }

    private static String format(int line, int total) {
        int digits = Math.max(2, (int) Math.floor(Math.log10(Math.max(1, total))) + 1);
        return String.format("%" + digits + "d", line);
    }

    /**
     * Tags each paragraph with an alternating {@code code-row-even} /
     * {@code code-row-odd} style class so the stylesheet can give rows a subtle
     * zebra stripe. Safe to call after every edit.
     */
    public static void applyRowStripes(CodeArea area) {
        int n = area.getParagraphs().size();
        for (int i = 0; i < n; i++) {
            Collection<String> style = Collections.singletonList(
                    (i % 2 == 0) ? "code-row-even" : "code-row-odd");
            area.setParagraphStyle(i, style);
        }
    }
}
