package komm.ui.code;

import javafx.beans.InvalidationListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.reactfx.collection.LiveList;
import org.reactfx.value.Val;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

    /** Above this many restyled lines one whole-document update is cheaper than line-by-line ones. */
    private static final int BULK_RESTYLE_THRESHOLD = 150;

    private static final Color STRIPE = Color.rgb(127, 127, 127, 0.06);
    private static final List<Stop> STRIPE_STOPS = List.of(
            new Stop(0, Color.TRANSPARENT), new Stop(0.5, Color.TRANSPARENT),
            new Stop(0.5, STRIPE), new Stop(1, STRIPE));

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
     * A zebra-stripe layer for {@code area}: put the returned region directly behind the
     * area (same position and size, e.g. both in one {@code StackPane}).
     *
     * <p>The stripes are one repeating gradient that follows the scroll offset, so they
     * cost the same for 10 lines or 10 000. (They used to be a style class set on every
     * paragraph, i.e. one document mutation per line after every edit — the main reason
     * the editor froze on long snippets.) Rows are all the same height because code
     * areas never wrap.
     */
    public static Region rowStripes(CodeArea area) {
        Region layer = new Region();
        layer.setMouseTransparent(true);
        InvalidationListener update = o -> {
            // Both values are read on every call: these are lazy observables that only
            // notify again after they have been read.
            Double total = area.totalHeightEstimateProperty().getValue();
            Double scroll = area.estimatedScrollYProperty().getValue();
            int rows = area.getParagraphs().size();
            // all rows are equally tall, so the average is the row height
            double h = (total == null || rows == 0) ? 0 : total / rows;
            if (h < 4 || h > 200) return;
            // the fill is inset to the text viewport, so the padding stays unstriped
            javafx.geometry.Insets pad = area.getPadding();
            double y0 = pad.getTop() - ((scroll == null ? 0 : scroll) % (2 * h));
            layer.setBackground(new Background(new BackgroundFill(
                    new LinearGradient(0, y0, 0, y0 + 2 * h, false, CycleMethod.REPEAT, STRIPE_STOPS),
                    null, new javafx.geometry.Insets(pad.getTop(), 0, pad.getBottom(), 0))));
        };
        area.estimatedScrollYProperty().addListener(update);
        area.totalHeightEstimateProperty().addListener(update);
        area.heightProperty().addListener(update);
        return layer;
    }

    /**
     * Applies freshly computed highlighting, touching only the lines whose styling
     * actually changed. After ordinary typing that is one or two lines, where restyling
     * the whole document rebuilds every paragraph and stalls the UI on long snippets.
     */
    public static void applyHighlighting(CodeArea area, CodeHighlighter.Highlighting hl) {
        List<StyleSpans<Collection<String>>> perLine = hl.perLine();
        int n = area.getParagraphs().size();
        if (perLine.size() != n) {
            if (hl.whole().length() == area.getLength()) area.setStyleSpans(0, hl.whole());
            return;
        }
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!sameStyling(area.getParagraph(i).getStyleSpans(), perLine.get(i))) {
                changed.add(i);
                if (changed.size() > BULK_RESTYLE_THRESHOLD) {
                    area.setStyleSpans(0, hl.whole());
                    return;
                }
            }
        }
        for (int i : changed) area.setStyleSpans(i, 0, perLine.get(i));
    }

    /** Same style at every position, regardless of how the runs are split up. */
    private static boolean sameStyling(StyleSpans<Collection<String>> a, StyleSpans<Collection<String>> b) {
        if (a.length() != b.length()) return false;
        int ia = 0, ib = 0, na = a.getSpanCount(), nb = b.getSpanCount();
        int leftA = 0, leftB = 0;
        Collection<String> sa = null, sb = null;
        while (true) {
            while (leftA == 0 && ia < na) {
                StyleSpan<Collection<String>> s = a.getStyleSpan(ia++);
                leftA = s.getLength();
                sa = s.getStyle();
            }
            while (leftB == 0 && ib < nb) {
                StyleSpan<Collection<String>> s = b.getStyleSpan(ib++);
                leftB = s.getLength();
                sb = s.getStyle();
            }
            if (leftA == 0 || leftB == 0) return leftA == leftB;
            if (!sameStyle(sa, sb)) return false;
            int step = Math.min(leftA, leftB);
            leftA -= step;
            leftB -= step;
        }
    }

    private static boolean sameStyle(Collection<String> a, Collection<String> b) {
        if (a.size() != b.size()) return false;
        return a.isEmpty() || a.containsAll(b);
    }
}
