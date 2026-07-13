package komm.ui.emojis;

import atlantafx.base.theme.Styles;
import io.github.b077as.emojifx.Emoji;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A single-line text field that renders emoji characters as full-colour images.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   EmojiTextField (HBox)
 *   ├── stack (StackPane, fills available width)
 *   │   ├── sink   (TextField — provides ALL visual chrome: border, bg, focus ring)
 *   │   └── overlay (Pane — pixel-aligned on top of sink, clipped)
 *   │       ├── contentPane (Pane — scrolls horizontally)
 *   │       │   ├── selectionRect
 *   │       │   ├── textFlow  (text + emoji images)
 *   │       │   └── caretRect
 *   │       └── promptNode (Text)
 *   └── pickerButton (optional)
 * </pre>
 *
 * <h2>Key design</h2>
 * The sink TextField is left FULLY styled by the theme (we do not touch its
 * background, border or padding in Java). We only hide its text/caret/selection
 * via CSS. The overlay Pane is bound to exactly the same size as the sink and
 * is stacked on top. We measure the sink's insets at layout time to know where
 * the inner content area begins, then position textFlow and caret accordingly.
 */
public class EmojiTextField extends HBox {

    static final double EMOJI_SIZE = 20.0;
    private static final double SCROLL_MARGIN = 4.0;

    private static final PseudoClass FOCUSED_PC = PseudoClass.getPseudoClass("focused");

    // ── nodes ─────────────────────────────────────────────────────────────────

    private final javafx.scene.control.TextField sink = new javafx.scene.control.TextField();
    private final Pane overlay = new Pane();
    private final Pane contentPane = new Pane();
    private final TextFlow textFlow = new TextFlow();
    private final Rectangle selectionRect = new Rectangle();
    private final Rectangle caretRect = new Rectangle(1.5, 0);
    private Text promptNode;

    private Button pickerButton;
    private final boolean showPickerButton;
    private SequentialTransition blinkAnim;

    // ── properties ────────────────────────────────────────────────────────────

    private final StringProperty text = new SimpleStringProperty(this, "text", "");
    private final StringProperty promptText = new SimpleStringProperty(this, "promptText", "");
    private final BooleanProperty editable = new SimpleBooleanProperty(this, "editable", true);

    // ── state ─────────────────────────────────────────────────────────────────

    private int[] charStarts = new int[0];
    private double scrollLeft = 0;
    private Color fgColor = Color.web("#cccccc");
    private Color selColor = null;
    private int dragAnchor = 0;

    // ── constructor ───────────────────────────────────────────────────────────

    public EmojiTextField() {
        this(false);
    }

    public EmojiTextField(boolean showPicker) {
        this.showPickerButton = showPicker;
        getStyleClass().add("emoji-text-field");
        setAlignment(Pos.CENTER_LEFT);
        buildUI();
        wireListeners();
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void buildUI() {

        // ── sink: fully themed, text only made invisible ──────────────────────
        // We let the theme style .text-field completely (border, background,
        // padding, focus ring). We ONLY suppress the text/caret/selection colours.
        sink.getStyleClass().add("emoji-input-sink");
        sink.setPromptText("");   // we render our own prompt
        sink.setStyle(
                "-fx-text-fill:           transparent;" +
                        "-fx-highlight-fill:      transparent;" +
                        "-fx-highlight-text-fill: transparent;" +
                        "-fx-prompt-text-fill:    transparent;"
        );
        sink.setFocusTraversable(true);
        sink.setMaxWidth(Double.MAX_VALUE);
        sink.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(sink, Priority.ALWAYS);

        sink.caretPositionProperty().addListener((o, a, b) ->
                Platform.runLater(this::onCaretOrSelectionChanged));
        sink.anchorProperty().addListener((o, a, b) ->
                Platform.runLater(this::onCaretOrSelectionChanged));
        sink.editableProperty().bind(editable);

        sink.focusedProperty().addListener((o, was, now) ->
                pseudoClassStateChanged(FOCUSED_PC, now));

        // ── prompt ────────────────────────────────────────────────────────────
        promptNode = new Text();
        promptNode.setMouseTransparent(true);
        promptNode.setFill(Color.gray(0.55));
        promptNode.textProperty().bind(promptText);
        promptNode.setVisible(false);

        // ── selection ─────────────────────────────────────────────────────────
        selectionRect.setStyle("-fx-fill: -color-highlight;");
        selectionRect.setManaged(false);
        selectionRect.setMouseTransparent(true);
        selectionRect.setVisible(false);

        // ── caret ─────────────────────────────────────────────────────────────
        caretRect.setFill(fgColor);
        caretRect.setManaged(false);
        caretRect.setMouseTransparent(true);
        caretRect.setVisible(false);

        // ── textFlow ──────────────────────────────────────────────────────────
        textFlow.setManaged(false);
        textFlow.setMouseTransparent(true);
        textFlow.setLineSpacing(0);

        // ── contentPane (scrolls) ─────────────────────────────────────────────
        contentPane.setBackground(Background.EMPTY);
        contentPane.setManaged(false);
        contentPane.getChildren().addAll(selectionRect, textFlow, caretRect);

        // ── overlay (sits on top of sink, same size) ──────────────────────────
        overlay.setCursor(javafx.scene.Cursor.TEXT);
        overlay.setBackground(Background.EMPTY);
        overlay.setMouseTransparent(false);
        overlay.getChildren().addAll(contentPane, promptNode);

        Rectangle overlayClip = new Rectangle();
        overlay.layoutBoundsProperty().addListener((o, a, b) -> {
            overlayClip.setX(0);
            overlayClip.setY(-2);
            overlayClip.setWidth(b.getWidth());
            overlayClip.setHeight(b.getHeight() + 2);
        });
        overlay.setClip(overlayClip);

        // Bind overlay size to sink so they are always pixel-identical.
        // min* bindings are intentionally omitted — binding min to the actual
        // rendered width creates a layout feedback loop when the field is in a
        // constrained container (e.g. alongside a sibling button in an HBox).
        overlay.prefWidthProperty().bind(sink.widthProperty());
        overlay.prefHeightProperty().bind(sink.heightProperty());
        overlay.maxWidthProperty().bind(sink.widthProperty());
        overlay.maxHeightProperty().bind(sink.heightProperty());
        overlay.setMinWidth(0);
        overlay.setMinHeight(0);

        // ── stack ─────────────────────────────────────────────────────────────
        StackPane stack = new StackPane(sink, overlay);
        stack.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(stack, Priority.ALWAYS);
        getChildren().add(stack);

        if (showPickerButton) {
            pickerButton = buildPickerButton();
            getChildren().add(pickerButton);
        }

        // ── blink: hard on/off ────────────────────────────────────────────────
        PauseTransition onPhase = new PauseTransition(Duration.millis(530));
        PauseTransition offPhase = new PauseTransition(Duration.millis(470));
        onPhase.setOnFinished(e -> caretRect.setVisible(false));
        offPhase.setOnFinished(e -> caretRect.setVisible(true));
        blinkAnim = new SequentialTransition(onPhase, offPhase);
        blinkAnim.setCycleCount(Animation.INDEFINITE);

        sink.focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                caretRect.setVisible(true);
                blinkAnim.playFromStart();
            } else {
                blinkAnim.stop();
                caretRect.setVisible(false);
                selectionRect.setVisible(false);
            }
        });

        sceneProperty().addListener((obs, o, n) -> {
            if (n == null) blinkAnim.stop();
            if (n != null) Platform.runLater(this::refreshPromptColor);
        });

        // ── mouse ─────────────────────────────────────────────────────────────
        overlay.setOnMousePressed(e -> {
            // Right-click: just ensure focus without disturbing the selection,
            // so the context menu sees the current selection intact.
            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                sink.requestFocus();
                e.consume();
                return;
            }
            // Double-click: select all content
            if (e.getClickCount() == 2) {
                sink.requestFocus();
                sink.selectAll();
                e.consume();
                return;
            }
            dragAnchor = xToCharIndex(overlayXToContent(e.getX()));
            sink.positionCaret(dragAnchor);
            sink.requestFocus();
            e.consume();
        });

        overlay.setOnMouseDragged(e -> {
            double ox = e.getX(), ow = overlay.getWidth(), lx = padLeft();
            if (ox < lx) applyScroll(scrollLeft - Math.min((lx - ox) * 0.5, 20));
            else if (ox > ow - lx) applyScroll(scrollLeft + Math.min((ox - (ow - lx)) * 0.5, 20));
            sink.selectRange(dragAnchor, xToCharIndex(overlayXToContent(e.getX())));
            e.consume();
        });
        overlay.setOnMouseReleased(e -> e.consume());
        overlay.setOnContextMenuRequested(e -> sink.fireEvent(e.copyFor(sink, sink)));
        overlay.setOnScroll(e -> {
            if (e.getDeltaX() != 0) {
                applyScroll(scrollLeft - e.getDeltaX());
                e.consume();
            }
        });
    }

    // ── coordinate helpers ────────────────────────────────────────────────────

    private double padLeft() {
        return sink.getPadding().getLeft();
    }

    /**
     * overlay-local X → content-space X
     */
    private double overlayXToContent(double ox) {
        return ox - padLeft() + scrollLeft;
    }

    // ── layout ────────────────────────────────────────────────────────────────

    private void layoutContentPane() {
        double ow = overlay.getWidth();
        double oh = overlay.getHeight();
        if (ow <= 0 || oh <= 0) return;

        // Use the sink's own padding to find the inner content area.
        // getPadding() gives CSS padding only (no border), which is what we want.
        double padTop = sink.getPadding().getTop();
        double padBottom = sink.getPadding().getBottom();
        double padLeft = sink.getPadding().getLeft();

        double innerH = oh - padTop - padBottom;
        Text probe = new Text("A");
        probe.setFont(sink.getFont());
        double lineH = probe.getBoundsInLocal().getHeight();
        double baseH = Math.max(EMOJI_SIZE, lineH);
        // Extra vertical padding so tall emojis have breathing room top and bottom
        final double VPAD = 4.0;
        double tfH = baseH + VPAD;

        // Centre the content block within the inner area, clamped so it never
        // goes above the top edge (tfY >= 0).
        double tfY = Math.max(0, padTop + Math.floor((innerH - tfH) / 2.0));
        double leftX = padLeft;

        double totalW = getTotalContentWidth();
        double contentW = Math.max(ow, totalW + leftX + 10);

        contentPane.resize(contentW, oh);
        contentPane.setLayoutX(leftX - scrollLeft);
        contentPane.setLayoutY(0);

        // Offset by half VPAD so the content row is centred within the tfH block
        textFlow.relocate(0, tfY + VPAD / 2.0);
        textFlow.setPrefWidth(contentW);
        textFlow.setMaxWidth(contentW);

        // Prompt: baseline-aligned with the text row.
        // Text node layoutY positions the BASELINE in JavaFX.
        // The text baseline within our tfH block sits at tfY + probe.getBaselineOffset().
        promptNode.setFont(sink.getFont());
        promptNode.setLayoutX(leftX);
        promptNode.setLayoutY(tfY + VPAD / 2.0 + probe.getBaselineOffset());

        selectionRect.setY(tfY);
        selectionRect.setHeight(tfH);
        caretRect.setY(tfY + VPAD / 2.0);
        caretRect.setHeight(baseH);

        positionCaretAndSelection();
    }

    // ── scroll ────────────────────────────────────────────────────────────────

    private void applyScroll(double s) {
        double lx = padLeft();
        double maxScroll = Math.max(0, getTotalContentWidth() - (overlay.getWidth() - 2 * lx) + SCROLL_MARGIN);
        scrollLeft = Math.max(0, Math.min(s, maxScroll));
        contentPane.setLayoutX(lx - scrollLeft);
        positionCaretAndSelection();
    }

    private void ensureCaretVisible() {
        double lx = padLeft();
        double visW = overlay.getWidth() - 2 * lx;
        if (visW <= 0) return;
        double cx = charIndexToX(sink.getCaretPosition());
        double ns = scrollLeft;
        if (cx < scrollLeft + SCROLL_MARGIN) ns = cx - SCROLL_MARGIN;
        else if (cx > scrollLeft + visW - SCROLL_MARGIN) ns = cx - visW + SCROLL_MARGIN;
        applyScroll(ns);
    }

    // ── char ↔ X ──────────────────────────────────────────────────────────────

    private double charIndexToX(int rawIndex) {
        if (charStarts.length < 2) return 0;
        List<Node> ch = textFlow.getChildren();
        double x = 0;
        for (int i = 0; i < charStarts.length - 1; i++) {
            int ns = charStarts[i], ne = charStarts[i + 1];
            if (rawIndex <= ns) break;
            Node node = ch.get(i);
            if (rawIndex >= ne) x += nodeWidth(node);
            else {
                x += (node instanceof Text t) ? measureTextPrefix(t, rawIndex - ns) : nodeWidth(node);
                break;
            }
        }
        return x;
    }

    private int xToCharIndex(double contentX) {
        if (charStarts.length < 2 || textFlow.getChildren().isEmpty()) return 0;
        if (contentX <= 0) return 0;
        List<Node> ch = textFlow.getChildren();
        double cursor = 0;
        for (int i = 0; i < charStarts.length - 1; i++) {
            Node node = ch.get(i);
            double w = nodeWidth(node);
            if (contentX <= cursor + w)
                return (node instanceof Text t) ? charStarts[i] + textOffsetForX(t, contentX - cursor)
                        : (contentX - cursor < w / 2.0 ? charStarts[i] : charStarts[i + 1]);
            cursor += w;
        }
        String s = sink.getText();
        return s == null ? 0 : s.length();
    }

    // ── measurement ───────────────────────────────────────────────────────────

    private double getTotalContentWidth() {
        if (charStarts.length < 2) return 0;
        double t = 0;
        List<Node> ch = textFlow.getChildren();
        int n = charStarts.length - 1;
        for (int i = 0; i < n && i < ch.size(); i++) t += nodeWidth(ch.get(i));
        return t;
    }

    private double nodeWidth(Node node) {
        if (node instanceof javafx.scene.Group g && !g.getChildren().isEmpty()
                && g.getChildren().get(0) instanceof ImageView iv)
            return iv.getFitWidth() > 0 ? iv.getFitWidth() : EMOJI_SIZE;
        if (node instanceof ImageView iv) return iv.getFitWidth() > 0 ? iv.getFitWidth() : EMOJI_SIZE;
        if (node instanceof Text t) return t.getBoundsInLocal().getWidth();
        return node.getBoundsInLocal().getWidth();
    }

    private double measureTextPrefix(Text ref, int chars) {
        if (chars <= 0) return 0;
        String sub = ref.getText().substring(0, Math.min(chars, ref.getText().length()));
        Text p = new Text(sub);
        p.setFont(ref.getFont());
        return p.getBoundsInLocal().getWidth();
    }

    private int textOffsetForX(Text t, double lx) {
        String s = t.getText();
        if (s == null || s.isEmpty()) return 0;
        double best = Double.MAX_VALUE;
        int bi = 0;
        for (int i = 0; i <= s.length(); i++) {
            double d = Math.abs(measureTextPrefix(t, i) - lx);
            if (d < best) {
                best = d;
                bi = i;
            }
        }
        return bi;
    }

    // ── caret + selection ─────────────────────────────────────────────────────

    private void onCaretOrSelectionChanged() {
        ensureCaretVisible();
        positionCaretAndSelection();
        if (sink.isFocused()) {
            caretRect.setVisible(true);
            blinkAnim.playFromStart();
        }
    }

    private void positionCaretAndSelection() {
        caretRect.setLayoutX(charIndexToX(sink.getCaretPosition()) - caretRect.getWidth() / 2.0);
        int a = sink.getAnchor(), c = sink.getCaretPosition();
        int s = Math.min(a, c), e = Math.max(a, c);
        if (s == e || !sink.isFocused()) {
            selectionRect.setVisible(false);
            return;
        }
        double x1 = charIndexToX(s), x2 = charIndexToX(e);
        selectionRect.setX(x1);
        selectionRect.setWidth(Math.max(0, x2 - x1));
        selectionRect.setVisible(true);
    }

    // ── listeners ─────────────────────────────────────────────────────────────

    private void wireListeners() {
        sink.textProperty().addListener((obs, o, n) -> {
            if (!Objects.equals(text.get(), n)) text.set(n);
            refreshRender();
        });
        text.addListener((obs, o, n) -> {
            if (!Objects.equals(sink.getText(), n)) sink.setText(n);
        });
        sink.fontProperty().addListener((obs, o, n) -> {
            updateFontOnNodes(n);
            Platform.runLater(this::positionCaretAndSelection);
        });
        promptText.addListener((obs, o, n) -> updatePromptVisibility());
        sink.textProperty().addListener((obs, o, n) -> updatePromptVisibility());
        overlay.widthProperty().addListener((obs, o, n) -> layoutContentPane());
        overlay.heightProperty().addListener((obs, o, n) -> layoutContentPane());
        sceneProperty().addListener((obs, o, scene) -> {
            if (scene != null) Platform.runLater(this::refreshPromptColor);
        });
    }

    private void updateFontOnNodes(Font font) {
        for (Node n : textFlow.getChildren()) if (n instanceof Text t) t.setFont(font);
        if (promptNode != null) promptNode.setFont(font);
    }

    private void updatePromptVisibility() {
        promptNode.setVisible((sink.getText() == null || sink.getText().isEmpty())
                && promptText.get() != null && !promptText.get().isEmpty());
    }

    private void refreshPromptColor() {
        try {
            var skin = sink.getSkin();
            if (skin != null) {
                java.lang.reflect.Field f = null;
                Class<?> cls = skin.getClass();
                while (cls != null) {
                    try {
                        f = cls.getDeclaredField("promptNode");
                        break;
                    } catch (NoSuchFieldException ex) {
                        cls = cls.getSuperclass();
                    }
                }
                if (f != null) {
                    f.setAccessible(true);
                    Object pn = f.get(skin);
                    if (pn instanceof Text pt) {
                        Paint fill = pt.getFill();
                        if (fill instanceof Color c && c.getOpacity() > 0.01) {
                            promptNode.setFill(c);
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        promptNode.setFill(Color.gray(0.55));
    }

    // ── render ────────────────────────────────────────────────────────────────

    private void refreshRender() {
        final String raw = sink.getText();
        final Runnable update = () -> {
            textFlow.getChildren().clear();
            updatePromptVisibility();
            if (raw == null || raw.isEmpty()) {
                charStarts = new int[0];
                selectionRect.setVisible(false);
                caretRect.setLayoutX(0);
                return;
            }

            List<Node> nodes = TextUtils.convertToTextAndImageNodes(raw, EMOJI_SIZE);
            List<Integer> starts = new ArrayList<>();
            int rawCursor = 0;

            for (Node node : nodes) {
                starts.add(rawCursor);
                if (node instanceof Text t) {
                    t.setFont(sink.getFont());
                    t.setFill(fgColor);
                    t.setMouseTransparent(true);
                    rawCursor += t.getText() == null ? 0 : t.getText().length();
                    textFlow.getChildren().add(t);
                } else if (node instanceof ImageView iv) {
                    iv.setFitWidth(EMOJI_SIZE);
                    iv.setFitHeight(EMOJI_SIZE);
                    iv.setPreserveRatio(true);
                    iv.setMouseTransparent(true);
                    Text probe = new Text("A");
                    probe.setFont(sink.getFont());
                    double baseline = probe.getBaselineOffset();
                    javafx.scene.Group wrapper = new javafx.scene.Group(iv) {
                        @Override
                        public double getBaselineOffset() {
                            return baseline;
                        }
                    };
                    wrapper.setMouseTransparent(true);
                    rawCursor += emojiCharLengthAt(raw, rawCursor);
                    textFlow.getChildren().add(wrapper);
                }
            }
            starts.add(rawCursor);
            charStarts = starts.stream().mapToInt(Integer::intValue).toArray();
            Platform.runLater(() -> {
                layoutContentPane();
                ensureCaretVisible();
                positionCaretAndSelection();
            });
        };
        if (Platform.isFxApplicationThread()) update.run();
        else Platform.runLater(update);
    }

    private int emojiCharLengthAt(String raw, int idx) {
        if (idx >= raw.length()) return 0;
        int len = Character.charCount(raw.codePointAt(idx));
        while (idx + len < raw.length()) {
            int next = raw.codePointAt(idx + len);
            if (next == 0x200D || next == 0xFE0F || next == 0x20E3
                    || (next >= 0x1F3FB && next <= 0x1F3FF)
                    || (next >= 0xE0020 && next <= 0xE007F)) {
                len += Character.charCount(next);
                // ZWJ sequences: the codepoint after ZWJ (e.g. ♀ in 👮‍♀, 🌫 in 😶‍🌫)
                // is NOT itself a continuation marker, so consume it unconditionally.
                if (next == 0x200D && idx + len < raw.length()) {
                    len += Character.charCount(raw.codePointAt(idx + len));
                }
            } else break;
        }
        return len;
    }

    // ── colour ────────────────────────────────────────────────────────────────

    public void setTextColor(Color color) {
        fgColor = color;
        for (Node n : textFlow.getChildren()) if (n instanceof Text t) t.setFill(color);
        caretRect.setFill(color);
    }

    public void setSelectionColor(Color color) {
        selColor = color;
        selectionRect.setFill(color);
    }

    @Deprecated
    public void setCaretColor(Color color) {
        setTextColor(color);
    }

    // ── picker ────────────────────────────────────────────────────────────────

    private Button buildPickerButton() {
        Button btn = new Button();
        btn.setGraphic(new FontIcon(MaterialDesignE.EMOTICON_OUTLINE));
        btn.getStyleClass().addAll(Styles.BUTTON_ICON);
        btn.setFocusTraversable(false);
        btn.setTooltip(new Tooltip("Insert emoji"));
        return btn;
    }

    public Button getPickerButton() {
        return pickerButton;
    }

    public void setMaxLength(int maxLength) {
        sink.setTextFormatter(new TextFormatter<>(c ->
                c.getControlNewText().length() <= maxLength ? c : null));
    }

    public void insertEmoji(Emoji emoji) {
        String ch = emoji.character();
        String current = sink.getText() == null ? "" : sink.getText();
        int caret = sink.isFocused() ? sink.getCaretPosition() : current.length();
        String next = current.substring(0, caret) + ch + current.substring(caret);
        int nc = caret + ch.length();
        sink.setText(next);
        sink.requestFocus();
        sink.positionCaret(nc);
    }

    // ── public API ────────────────────────────────────────────────────────────

    public String getText() {
        return text.get();
    }

    public void setText(String v) {
        text.set(v);
    }

    public StringProperty textProperty() {
        return text;
    }

    public String getPromptText() {
        return promptText.get();
    }

    public void setPromptText(String v) {
        promptText.set(v);
    }

    public StringProperty promptTextProperty() {
        return promptText;
    }

    public boolean isEditable() {
        return editable.get();
    }

    public void setEditable(boolean v) {
        editable.set(v);
    }

    public BooleanProperty editableProperty() {
        return editable;
    }

    @Override
    public void requestFocus() {
        sink.requestFocus();
    }

    public boolean isFieldFocused() {
        return sink.isFocused();
    }

    public javafx.scene.control.TextField getInputField() {
        return sink;
    }

    public void selectAll() {
        sink.selectAll();
    }

    public void clear() {
        sink.clear();
    }

    public void setOnTextChanged(Consumer<String> listener) {
        text.addListener((obs, o, n) -> listener.accept(n));
    }
}