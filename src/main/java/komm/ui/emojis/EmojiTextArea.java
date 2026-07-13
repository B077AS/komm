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
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A multi-line emoji-aware text area that grows from 1 line up to {@value #MAX_LINES}
 * lines before showing a scrollbar.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   EmojiTextArea  (HBox)
 *   ├── inputWrapper (VBox, grows horizontally)
 *   │   └── scrollPane (ScrollPane — viewport clamped to MAX_LINES; content grows freely)
 *   │       └── contentBox (VBox, fills scroll viewport width)
 *   │           └── layerStack (StackPane, focusable, receives all input)
 *   │               ├── selectionPane  (Pane — per-line highlight rects, behind text)
 *   │               ├── textFlow       (TextFlow — emoji-aware rendering, mouse-transparent)
 *   │               ├── caretRect      (Rectangle — blinking caret, mouse-transparent)
 *   │               └── promptNode     (Text — placeholder, mouse-transparent)
 *   └── pickerButton (Button, optional, bottom-aligned)
 * </pre>
 *
 * <p>Links: URLs embedded in the text are rendered with an underline style.
 * Ctrl+click (or ⌘+click on macOS) opens the URL in the system browser.
 */
public class EmojiTextArea extends HBox {

    // ── constants ─────────────────────────────────────────────────────────────

    static final double EMOJI_SIZE = 20.0;
    private static final int MAX_LINES = 15;
    private static final double MIN_WIDTH = 80.0;
    private static final double H_PAD = 8.0;
    private static final double V_PAD = 6.0;
    private static final double SEL_VPAD = 4.0;

    private static final PseudoClass FOCUSED_PC = PseudoClass.getPseudoClass("focused");

    /**
     * Matches http(s):// and www. URLs.  The pattern intentionally stops at
     * common trailing punctuation so "see https://example.com." doesn't grab
     * the period.
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:https?://|www\\.)[^\\s<>\"'\\[\\]{}|\\\\^`]+"
    );

    // ── model ─────────────────────────────────────────────────────────────────

    private int maxLength = Integer.MAX_VALUE;
    private int minLines = 1;
    private int maxLines = MAX_LINES;

    private final StringBuilder buf = new StringBuilder();

    private record Snapshot(String text, int caret) {
    }

    private final java.util.Deque<Snapshot> undoStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<Snapshot> redoStack = new java.util.ArrayDeque<>();
    private static final int MAX_HISTORY = 200;
    private boolean undoInProgress = false;

    /**
     * charStarts[i] = raw buf offset of the START of textFlow child i.
     * charStarts[n] (n = number of children) = buf.length().
     */
    private int[] charStarts = {0};

    /**
     * For each textFlow child index, the URL string if that child is a link
     * node, or {@code null} otherwise.
     */
    private final List<String> nodeUrls = new ArrayList<>();

    private int caretPos = 0;
    private int anchorPos = 0;

    // ── nodes ─────────────────────────────────────────────────────────────────

    private final TextFlow textFlow = new TextFlow();
    private final Pane selectionPane = new Pane();
    private final Rectangle caretRect = new Rectangle(1.5, 0);
    private Text promptNode;

    private final StackPane layerStack = new StackPane();
    private final VBox contentBox = new VBox();
    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox inputWrapper = new VBox();

    private Button pickerButton;
    private final boolean showPickerButton;

    private SequentialTransition blinkAnim;

    private Runnable onLayoutReady;

    // ── properties ────────────────────────────────────────────────────────────

    private final StringProperty textProp = new SimpleStringProperty(this, "text", "");
    private final StringProperty promptProp = new SimpleStringProperty(this, "promptText", "");
    private final BooleanProperty editableProp = new SimpleBooleanProperty(this, "editable", true);

    private int dragAnchorRaw = 0;
    private Function<String, Boolean> submitHandler;

    /**
     * Optional interceptor consulted before inserting pasted text. If it returns
     * {@code true} the paste is considered handled and nothing is inserted (used
     * to divert pasted source code to the code-message editor).
     */
    private Function<String, Boolean> pasteInterceptor;

    // ── context menu ──────────────────────────────────────────────────────────
    private ContextMenu contextMenu;

    // ── constructor ───────────────────────────────────────────────────────────

    public EmojiTextArea() {
        this(false);
    }

    public EmojiTextArea(boolean showPicker) {
        this.showPickerButton = showPicker;
        setAlignment(Pos.BOTTOM_LEFT);
        setSpacing(4);
        buildUI();
        wireListeners();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────────────────────────────────

    private void buildUI() {

        promptNode = new Text();
        promptNode.setFill(Color.gray(0.55));
        promptNode.setMouseTransparent(true);
        promptNode.textProperty().bind(promptProp);

        textFlow.setPadding(new javafx.geometry.Insets(V_PAD, H_PAD, V_PAD, H_PAD));
        textFlow.setMouseTransparent(true);
        textFlow.setLineSpacing(0);

        caretRect.setStyle("-fx-fill: -color-fg-muted;");
        caretRect.setMouseTransparent(true);
        caretRect.setManaged(false);
        caretRect.setVisible(false);

        selectionPane.setMouseTransparent(true);
        selectionPane.setBackground(Background.EMPTY);

        layerStack.setAlignment(Pos.TOP_LEFT);
        layerStack.getChildren().addAll(selectionPane, textFlow, caretRect, promptNode);
        layerStack.setFocusTraversable(true);
        layerStack.setCursor(javafx.scene.Cursor.TEXT);
        layerStack.setBackground(Background.EMPTY);
        layerStack.setMinHeight(Region.USE_PREF_SIZE);

        contentBox.setAlignment(Pos.TOP_LEFT);
        contentBox.getChildren().add(layerStack);
        contentBox.setBackground(Background.EMPTY);

        scrollPane.setContent(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setBackground(Background.EMPTY);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scrollPane.setFocusTraversable(false);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        inputWrapper.setAlignment(Pos.TOP_LEFT);
        inputWrapper.getChildren().add(scrollPane);
        inputWrapper.setMinWidth(MIN_WIDTH);
        HBox.setHgrow(inputWrapper, Priority.ALWAYS);
        getChildren().add(inputWrapper);

        if (showPickerButton) {
            pickerButton = buildPickerButton();
            getChildren().add(pickerButton);
        }

        PauseTransition on = new PauseTransition(Duration.millis(530));
        PauseTransition off = new PauseTransition(Duration.millis(470));
        on.setOnFinished(e -> caretRect.setVisible(false));
        off.setOnFinished(e -> caretRect.setVisible(true));
        blinkAnim = new SequentialTransition(on, off);
        blinkAnim.setCycleCount(Animation.INDEFINITE);

        layerStack.focusedProperty().addListener((obs, was, now) -> {
            pseudoClassStateChanged(FOCUSED_PC, now);
            if (now) {
                if (editableProp.get()) {
                    caretRect.setVisible(true);
                    blinkAnim.playFromStart();
                }
                refreshOverlay();
            } else {
                blinkAnim.stop();
                caretRect.setVisible(false);
                clearSelRects();
                if (contextMenu != null) contextMenu.hide();
            }
        });

        contextMenu = buildContextMenu();

        layerStack.widthProperty().addListener((obs, o, n) -> positionPromptNode());
        layerStack.heightProperty().addListener((obs, o, n) -> positionPromptNode());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

    private void wireListeners() {

        layerStack.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
        layerStack.addEventFilter(KeyEvent.KEY_TYPED, this::handleKeyTyped);

        layerStack.setOnMousePressed(e -> {
            layerStack.requestFocus();
            if (e.getButton() == MouseButton.SECONDARY) {
                if (contextMenu != null) {
                    contextMenu.show(layerStack, e.getScreenX(), e.getScreenY());
                }
                e.consume();
                return;
            }
            if (contextMenu != null) contextMenu.hide();

            // ── Ctrl/Cmd + click → open link ──────────────────────────────────
            if (e.isControlDown() || e.isMetaDown()) {
                String url = urlAt(e.getX(), e.getY());
                if (url != null) {
                    openUrl(url);
                    e.consume();
                    return;
                }
            }

            if (e.getClickCount() == 2) {
                setCaretAndAnchor(0, buf.length());
                refreshOverlay();
                e.consume();
                return;
            }
            int idx = hitTest(e.getX(), e.getY());
            dragAnchorRaw = idx;
            setCaretAndAnchor(idx, idx);
            refreshOverlay();
            e.consume();
        });

        // Update cursor shape depending on whether the pointer is over a link
        // and the modifier key is held.
        layerStack.setOnMouseMoved(e -> updateCursorForPosition(e.getX(), e.getY(),
                e.isControlDown() || e.isMetaDown()));

        layerStack.setOnMouseDragged(e -> {
            int idx = hitTest(e.getX(), e.getY());
            anchorPos = dragAnchorRaw;
            caretPos = idx;
            refreshOverlay();
            e.consume();
        });
        layerStack.setOnMouseReleased(e -> e.consume());

        scrollPane.addEventFilter(ScrollEvent.SCROLL, e -> {
            double vpH = scrollPane.getViewportBounds().getHeight();
            double contentH = contentBox.getHeight();
            if (contentH <= vpH) return;
            double range = contentH - vpH;
            double newV = scrollPane.getVvalue() + (-e.getDeltaY() / range);
            scrollPane.setVvalue(Math.max(0, Math.min(1, newV)));
            e.consume();
        });

        scrollPane.viewportBoundsProperty().addListener((obs, o, n) -> {
            double w = n.getWidth();
            if (w > 0) {
                applyWidth(w);
                Platform.runLater(this::updateHeight);
                Platform.runLater(this::refreshOverlay);
                if (onLayoutReady != null) {
                    Runnable cb = onLayoutReady;
                    onLayoutReady = null;
                    Platform.runLater(cb);
                }
            }
        });

        textProp.addListener((obs, o, n) -> {
            String incoming = n == null ? "" : n;
            if (!Objects.equals(incoming, buf.toString())) {
                buf.setLength(0);
                buf.append(incoming);
                caretPos = clamp(caretPos, 0, buf.length());
                anchorPos = clamp(anchorPos, 0, buf.length());
                rebuild();
            }
        });

        promptProp.addListener((obs, o, n) -> refreshPromptVisibility());

        contentBox.heightProperty().addListener((obs, o, n) -> {
            if (caretAtBottom) {
                Platform.runLater(() -> scrollPane.setVvalue(1.0));
            }
        });

        applyFontToNodes();
        updateHeight();
        refreshOverlay();
    }

    private void applyWidth(double w) {
        textFlow.setPrefWidth(w);
        textFlow.setMaxWidth(w);
        layerStack.setPrefWidth(w);
        selectionPane.setPrefWidth(w);
        selectionPane.setMinHeight(layerStack.getPrefHeight());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keyboard
    // ─────────────────────────────────────────────────────────────────────────

    private void handleKeyPressed(KeyEvent e) {
        if (!editableProp.get()) {
            switch (e.getCode()) {
                case LEFT, RIGHT, UP, DOWN, HOME, END, A, C -> {} // allow navigation and copy
                default -> { e.consume(); return; }
            }
        }
        boolean shift = e.isShiftDown();
        boolean ctrl = e.isControlDown() || e.isMetaDown();

        switch (e.getCode()) {
            case ENTER -> {
                if (submitHandler != null && !shift && !ctrl) {
                    if (Boolean.TRUE.equals(submitHandler.apply(buf.toString()))) {
                        clearBuf();
                        e.consume();
                        return;
                    }
                }
                replaceSelection("\n");
                e.consume();
            }
            case BACK_SPACE -> {
                if (hasSelection()) replaceSelection("");
                else if (caretPos > 0) {
                    int len = emojiLenBefore(caretPos);
                    int from = Math.max(0, caretPos - len);
                    buf.delete(from, caretPos);
                    caretPos = anchorPos = from;
                    commitText();
                    rebuild();
                }
                e.consume();
            }
            case DELETE -> {
                if (hasSelection()) replaceSelection("");
                else if (caretPos < buf.length()) {
                    buf.delete(caretPos, caretPos + emojiLenAt(caretPos));
                    commitText();
                    rebuild();
                }
                e.consume();
            }
            case LEFT -> {
                if (shift) {
                    caretPos = caretPos > 0 ? caretPos - emojiLenBefore(caretPos) : 0;
                } else {
                    int to = hasSelection() ? Math.min(caretPos, anchorPos)
                            : (caretPos > 0 ? caretPos - emojiLenBefore(caretPos) : 0);
                    setCaretAndAnchor(to, -1);
                }
                refreshOverlay();
                ensureCaretVisible();
                e.consume();
            }
            case RIGHT -> {
                if (shift) {
                    caretPos = caretPos < buf.length() ? caretPos + emojiLenAt(caretPos) : buf.length();
                } else {
                    int to = hasSelection() ? Math.max(caretPos, anchorPos)
                            : (caretPos < buf.length() ? caretPos + emojiLenAt(caretPos) : buf.length());
                    setCaretAndAnchor(to, -1);
                }
                refreshOverlay();
                ensureCaretVisible();
                e.consume();
            }
            case UP -> {
                moveLine(-1, shift);
                e.consume();
            }
            case DOWN -> {
                moveLine(+1, shift);
                e.consume();
            }
            case HOME -> {
                int sol = startOfVisualLine(caretPos);
                if (shift) caretPos = sol;
                else setCaretAndAnchor(sol, -1);
                refreshOverlay();
                ensureCaretVisible();
                e.consume();
            }
            case END -> {
                int eol = endOfVisualLine(caretPos);
                if (shift) caretPos = eol;
                else setCaretAndAnchor(eol, -1);
                refreshOverlay();
                ensureCaretVisible();
                e.consume();
            }
            case A -> {
                if (ctrl) {
                    setCaretAndAnchor(0, buf.length());
                    refreshOverlay();
                    e.consume();
                }
            }
            case Z -> {
                if (ctrl) {
                    if (shift) redo();
                    else undo();
                    e.consume();
                }
            }
            case Y -> {
                if (ctrl) {
                    redo();
                    e.consume();
                }
            }
            case C -> {
                if (ctrl && hasSelection()) {
                    toClipboard(selectedText());
                    e.consume();
                }
            }
            case X -> {
                if (ctrl && hasSelection()) {
                    toClipboard(selectedText());
                    replaceSelection("");
                    e.consume();
                }
            }
            case V -> {
                if (ctrl) {
                    String s = javafx.scene.input.Clipboard.getSystemClipboard().getString();
                    if (s != null && !s.isEmpty()) {
                        if (pasteInterceptor != null && Boolean.TRUE.equals(pasteInterceptor.apply(s))) {
                            e.consume();
                            return;
                        }
                        int available = maxLength - (buf.length() - (selEnd() - selStart()));
                        if (available > 0) {
                            if (s.length() > available) s = s.substring(0, available);
                            replaceSelection(s);
                        }
                    }
                    e.consume();
                }
            }
            default -> {
            }
        }
    }

    private void handleKeyTyped(KeyEvent e) {
        if (!editableProp.get()) return;
        String ch = e.getCharacter();
        if (ch == null || ch.isEmpty() || ch.charAt(0) < 32 || ch.charAt(0) == 127) return;
        if (buf.length() - (selEnd() - selStart()) + ch.length() > maxLength) return;
        replaceSelection(ch);
        e.consume();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text mutation
    // ─────────────────────────────────────────────────────────────────────────

    private void replaceSelection(String ins) {
        pushUndo();
        int s = selStart(), e = selEnd();
        buf.replace(s, e, ins);
        caretPos = anchorPos = s + ins.length();
        commitText();
        rebuild();
    }

    private void clearBuf() {
        pushUndo();
        buf.setLength(0);
        caretPos = anchorPos = 0;
        commitText();
        rebuild();
    }

    private void commitText() {
        String v = buf.toString();
        if (!Objects.equals(textProp.get(), v)) textProp.set(v);
    }

    private void pushUndo() {
        if (undoInProgress) return;
        redoStack.clear();
        if (undoStack.size() >= MAX_HISTORY) undoStack.pollFirst();
        undoStack.push(new Snapshot(buf.toString(), caretPos));
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        undoInProgress = true;
        redoStack.push(new Snapshot(buf.toString(), caretPos));
        Snapshot s = undoStack.pop();
        buf.setLength(0);
        buf.append(s.text());
        caretPos = anchorPos = clamp(s.caret(), 0, buf.length());
        commitText();
        rebuild();
        undoInProgress = false;
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoInProgress = true;
        undoStack.push(new Snapshot(buf.toString(), caretPos));
        Snapshot s = redoStack.pop();
        buf.setLength(0);
        buf.append(s.text());
        caretPos = anchorPos = clamp(s.caret(), 0, buf.length());
        commitText();
        rebuild();
        undoInProgress = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Selection helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean hasSelection() {
        return caretPos != anchorPos;
    }

    private int selStart() {
        return Math.min(caretPos, anchorPos);
    }

    private int selEnd() {
        return Math.max(caretPos, anchorPos);
    }

    private String selectedText() {
        return hasSelection() ? buf.substring(selStart(), selEnd()) : "";
    }

    private void setCaretAndAnchor(int caret, int anchor) {
        caretPos = clamp(caret, 0, buf.length());
        anchorPos = anchor < 0 ? caretPos : clamp(anchor, 0, buf.length());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Emoji grapheme cluster length
    // ─────────────────────────────────────────────────────────────────────────

    private int emojiLenAt(int idx) {
        if (idx >= buf.length()) return 0;
        int len = Character.charCount(buf.codePointAt(idx));
        while (idx + len < buf.length()) {
            int cp = buf.codePointAt(idx + len);
            if (isEmojiCont(cp)) {
                len += Character.charCount(cp);
                // ZWJ sequences: after the ZWJ, the following codepoint is the joined
                // component (e.g. 🌫 in 😶‍🌫, ♀ in 👮‍♀) and is NOT itself in isEmojiCont,
                // so we must consume it unconditionally or the rebuild loop re-processes
                // it as a second standalone emoji.
                if (cp == 0x200D && idx + len < buf.length()) {
                    len += Character.charCount(buf.codePointAt(idx + len));
                }
            } else {
                break;
            }
        }
        return len;
    }

    private int emojiLenBefore(int idx) {
        if (idx <= 0) return 0;
        int start = 0;
        while (start < idx) {
            int len = emojiLenAt(start);
            if (start + len >= idx) return len;
            start += len;
        }
        return Character.charCount(buf.codePointBefore(idx));
    }

    private static boolean isEmojiCont(int cp) {
        return cp == 0x200D || cp == 0xFE0F || cp == 0x20E3
                || (cp >= 0x1F3FB && cp <= 0x1F3FF)
                || (cp >= 0xE0020 && cp <= 0xE007F);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Link helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the URL string for the textFlow child node at the given raw
     * buffer index, or {@code null} if that position is not a link.
     */
    private String urlAtRawIndex(int rawIdx) {
        rawIdx = clamp(rawIdx, 0, buf.length());
        for (int i = 0; i < charStarts.length - 1; i++) {
            if (rawIdx >= charStarts[i] && rawIdx < charStarts[i + 1]) {
                return i < nodeUrls.size() ? nodeUrls.get(i) : null;
            }
        }
        return null;
    }

    /**
     * Returns the URL under the given layerStack-local pointer coordinates,
     * or {@code null} if the pointer is not over a link.
     */
    private String urlAt(double x, double y) {
        int raw = hitTest(x, y);
        return urlAtRawIndex(raw);
    }

    /**
     * Updates the cursor to a hand when hovering over a link with Ctrl/Cmd.
     */
    private void updateCursorForPosition(double x, double y, boolean modifierDown) {
        if (modifierDown && urlAt(x, y) != null) {
            layerStack.setCursor(javafx.scene.Cursor.HAND);
        } else {
            layerStack.setCursor(javafx.scene.Cursor.TEXT);
        }
    }

    /**
     * Opens the given URL in the system browser. Prepends "https://" for
     * www. URLs that lack a scheme.
     */
    private static void openUrl(String url) {
        try {
            String normalized = url.startsWith("www.") ? "https://" + url : url;
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(normalized));
            }
        } catch (Exception ex) {
            // Silently ignore — there is no meaningful recovery in a text area
        }
    }

    /**
     * Creates a styled {@link Text} node representing a hyperlink span.
     * The node is mouse-transparent (the layerStack handles all events).
     */
    private Text makeLinkNode(String text, Font font) {
        Text t = new Text(text);
        t.setFont(font);
        t.setStyle("-fx-fill: -color-accent-4;");
        t.setUnderline(true);
        t.setMouseTransparent(true);
        return t;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rebuild TextFlow from buf
    // ─────────────────────────────────────────────────────────────────────────

    private void rebuild() {
        textFlow.getChildren().clear();
        nodeUrls.clear();
        clearSelRects();
        refreshPromptVisibility();

        String raw = buf.toString();
        if (raw.isEmpty()) {
            charStarts = new int[]{0};
            Platform.runLater(() -> {
                updateHeight();
                refreshOverlay();
            });
            return;
        }

        List<Integer> starts = new ArrayList<>();
        Font font = getFont();
        int rawCursor = 0;

        // ── segment the buffer: plain text vs URL spans vs emoji ──────────────
        // We walk through the raw string. Whenever a URL_PATTERN match begins
        // at rawCursor or after, we flush any plain-text segment before it,
        // emit the URL span, then continue. Within plain-text segments we
        // hand off to TextUtils for emoji decomposition as before.

        Matcher urlMatcher = URL_PATTERN.matcher(raw);
        // Collect all URL matches upfront so we can check them while walking.
        List<int[]> urlRanges = new ArrayList<>(); // {start, end}
        while (urlMatcher.find()) {
            urlRanges.add(new int[]{urlMatcher.start(), urlMatcher.end()});
        }

        int urlIdx = 0; // index into urlRanges for the next unprocessed URL

        while (rawCursor < raw.length()) {

            // Is the next URL range starting at rawCursor or later?
            // Skip any URL ranges that end at or before rawCursor (shouldn't
            // happen in practice, but defensive).
            while (urlIdx < urlRanges.size() && urlRanges.get(urlIdx)[1] <= rawCursor) {
                urlIdx++;
            }

            int urlStart = urlIdx < urlRanges.size() ? urlRanges.get(urlIdx)[0] : raw.length();

            if (urlStart > rawCursor) {
                // ── flush plain-text (possibly with emoji) up to urlStart ──────
                String segment = raw.substring(rawCursor, urlStart);
                List<Node> nodes = TextUtils.convertToTextAndImageNodes(segment, EMOJI_SIZE);
                int segCursor = rawCursor;

                for (Node node : nodes) {
                    starts.add(segCursor);
                    nodeUrls.add(null); // not a URL node
                    if (node instanceof Text t) {
                        t.setFont(font);
                        t.setStyle("-fx-fill: -color-fg-muted;");
                        t.setMouseTransparent(true);
                        int len = t.getText() == null ? 0 : t.getText().length();
                        segCursor += len;
                        textFlow.getChildren().add(t);
                    } else {
                        Node toAdd;
                        if (node instanceof ImageView iv) {
                            iv.setFitWidth(EMOJI_SIZE);
                            iv.setFitHeight(EMOJI_SIZE);
                            iv.setPreserveRatio(true);
                            iv.setMouseTransparent(true);
                            Text probe = new Text("A");
                            probe.setFont(font);
                            double bl = probe.getBaselineOffset();
                            javafx.scene.Group g = new javafx.scene.Group(iv) {
                                @Override
                                public double getBaselineOffset() {
                                    return bl;
                                }
                            };
                            g.setMouseTransparent(true);
                            toAdd = g;
                        } else {
                            node.setMouseTransparent(true);
                            toAdd = node;
                        }
                        int emojiLen = emojiLenAt(segCursor);
                        segCursor += Math.max(1, emojiLen);
                        textFlow.getChildren().add(toAdd);
                    }
                }
                rawCursor = segCursor;

            } else {
                // ── emit URL span ─────────────────────────────────────────────
                int urlEnd = urlRanges.get(urlIdx)[1];
                String urlText = raw.substring(urlStart, urlEnd);
                starts.add(rawCursor);
                nodeUrls.add(urlText);
                textFlow.getChildren().add(makeLinkNode(urlText, font));
                rawCursor = urlEnd;
                urlIdx++;
            }
        }

        starts.add(rawCursor);
        charStarts = starts.stream().mapToInt(Integer::intValue).toArray();

        if (charStarts[charStarts.length - 1] != buf.length()) {
            charStarts[charStarts.length - 1] = buf.length();
        }

        Platform.runLater(() -> {
            updateHeight();
            refreshOverlay();
            ensureCaretVisible();
        });
    }

    private void updateHeight() {
        double vpW = scrollPane.getViewportBounds().getWidth();
        if (vpW <= 0) return;

        applyWidth(vpW);

        double tfH = textFlow.prefHeight(vpW);
        double lineH = lineHeight();
        if (lineH <= 0) return;

        double minH = lineH + SEL_VPAD + 2 * V_PAD;
        double fullH = Math.max(tfH, minH);

        layerStack.setPrefHeight(fullH);
        layerStack.setMinHeight(minH);
        selectionPane.setPrefHeight(fullH);
        selectionPane.setMinHeight(minH);

        int lines = Math.max(1, (int) Math.round((tfH - 2 * V_PAD) / lineH));
        int clamped = Math.max(Math.min(lines, maxLines), minLines);
        boolean needsScroll = lines > maxLines;
        double vpH = clamped * lineH + 2 * V_PAD;

        scrollPane.setPrefHeight(vpH);
        scrollPane.setMinHeight(minH);
        scrollPane.setMaxHeight(vpH);

        scrollPane.setVbarPolicy(needsScroll
                ? ScrollPane.ScrollBarPolicy.AS_NEEDED
                : ScrollPane.ScrollBarPolicy.NEVER);
    }

    private double lineHeight() {
        Font f = getFont();
        Text p = new Text("Ag");
        p.setFont(f);
        return Math.max(p.getBoundsInLocal().getHeight(), EMOJI_SIZE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Font (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private Font getFont() {
        if (getScene() != null) {
            try {
                if (promptNode.getScene() != null) {
                    Font f = promptNode.getFont();
                    if (f.getSize() > 10) return f;
                }
            } catch (Exception ignored) {
            }
        }
        return Font.font(14);
    }

    private void applyFontToNodes() {
        Font f = getFont();
        promptNode.setFont(f);
        for (int i = 0; i < textFlow.getChildren().size(); i++) {
            Node n = textFlow.getChildren().get(i);
            if (n instanceof Text t) {
                t.setFont(f);
                // Re-apply link style in case font changed
                boolean isLink = i < nodeUrls.size() && nodeUrls.get(i) != null;
                if (isLink) {
                    t.setStyle("-fx-fill: -color-accent-4;");
                    t.setUnderline(true);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Overlay: caret + selection (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshOverlay() {
        positionCaret();
        if (layerStack.isFocused()) updateSelection();
        else clearSelRects();
    }

    private void positionCaret() {
        int tfIdx = rawToTF(caretPos);
        PathElement[] shape = textFlow.caretShape(tfIdx, true);

        if (shape == null || shape.length == 0) {
            caretRect.setLayoutX(H_PAD - caretRect.getWidth() / 2.0);
            caretRect.setLayoutY(V_PAD);
            caretRect.setHeight(lineHeight());
            return;
        }

        double x = 0, yTop = 0, yBot = lineHeight();
        for (PathElement pe : shape) {
            if (pe instanceof MoveTo m) {
                x = m.getX();
                yTop = m.getY();
            } else if (pe instanceof LineTo l) {
                yBot = l.getY();
            }
        }
        caretRect.setLayoutX(x + H_PAD - caretRect.getWidth() / 2.0);
        caretRect.setLayoutY(yTop + V_PAD);
        caretRect.setHeight(Math.max(yBot - yTop, lineHeight()));
    }

    private void updateSelection() {
        clearSelRects();
        if (!hasSelection()) return;
        int s = rawToTF(selStart());
        int e = rawToTF(selEnd());
        PathElement[] shape = textFlow.rangeShape(s, e);
        if (shape == null || shape.length == 0) return;
        parseSelShape(shape);
    }

    private void parseSelShape(PathElement[] elems) {
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        for (PathElement pe : elems) {
            if (pe instanceof MoveTo m) {
                if (!xs.isEmpty()) flushSelRect(xs, ys);
                xs.clear();
                ys.clear();
                xs.add(m.getX());
                ys.add(m.getY());
            } else if (pe instanceof LineTo l) {
                xs.add(l.getX());
                ys.add(l.getY());
            } else if (pe instanceof ClosePath) {
                if (!xs.isEmpty()) {
                    flushSelRect(xs, ys);
                    xs.clear();
                    ys.clear();
                }
            }
        }
        if (!xs.isEmpty()) flushSelRect(xs, ys);
    }

    private void flushSelRect(List<Double> xs, List<Double> ys) {
        double x1 = xs.stream().mapToDouble(d -> d).min().orElse(0);
        double x2 = xs.stream().mapToDouble(d -> d).max().orElse(0);
        double y1 = ys.stream().mapToDouble(d -> d).min().orElse(0);
        double y2 = ys.stream().mapToDouble(d -> d).max().orElse(0);
        if (x2 <= x1 || y2 <= y1) return;
        Rectangle r = new Rectangle(x1 + H_PAD, y1 + V_PAD - SEL_VPAD / 2.0,
                x2 - x1, Math.max(y2 - y1, lineHeight() + SEL_VPAD));
        r.setStyle("-fx-fill: -color-highlight");
        r.setMouseTransparent(true);
        r.setManaged(false);
        selectionPane.getChildren().add(r);
    }

    private void clearSelRects() {
        selectionPane.getChildren().clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prompt node (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private void positionPromptNode() {
        promptNode.setFont(getFont());
        Text probe = new Text("A");
        probe.setFont(getFont());
        promptNode.setManaged(false);
        promptNode.setLayoutX(H_PAD);
        promptNode.setLayoutY(V_PAD + probe.getBaselineOffset());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Index mapping: raw buf ↔ TextFlow index space (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private int rawToTF(int rawIdx) {
        rawIdx = clamp(rawIdx, 0, buf.length());
        int tf = 0;
        List<Node> ch = textFlow.getChildren();
        for (int i = 0; i < charStarts.length - 1; i++) {
            int ns = charStarts[i];
            int ne = charStarts[i + 1];
            if (rawIdx <= ns) break;
            Node node = i < ch.size() ? ch.get(i) : null;
            if (node == null) break;
            if (node instanceof Text t) {
                int nodeLen = t.getText() == null ? 0 : t.getText().length();
                if (rawIdx < ne) {
                    tf += rawIdx - ns;
                    return tf;
                }
                tf += nodeLen;
            } else {
                if (rawIdx < ne) return tf;
                tf += 1;
            }
        }
        return tf;
    }

    private int tfToRaw(int tfIdx) {
        List<Node> ch = textFlow.getChildren();
        int tf = 0;
        for (int i = 0; i < charStarts.length - 1; i++) {
            Node node = i < ch.size() ? ch.get(i) : null;
            if (node == null) break;
            int ns = charStarts[i];
            int nodeLen;
            if (node instanceof Text t) {
                nodeLen = t.getText() == null ? 0 : t.getText().length();
                if (tfIdx <= tf + nodeLen) return ns + (tfIdx - tf);
            } else {
                nodeLen = 1;
                if (tfIdx <= tf + nodeLen)
                    return (tfIdx <= tf) ? charStarts[i] : charStarts[i + 1];
            }
            tf += nodeLen;
        }
        return buf.length();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hit testing (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private int hitTest(double x, double y) {
        var hit = textFlow.hitTest(new Point2D(x - H_PAD, y - V_PAD));
        int tfIdx = hit.isLeading() ? hit.getCharIndex() : hit.getCharIndex() + 1;
        return snapToCluster(clamp(tfToRaw(tfIdx), 0, buf.length()));
    }

    /**
     * Snaps {@code rawIdx} to the nearest valid grapheme-cluster boundary so the
     * caret never lands inside a multi-codepoint emoji (e.g. mid-ZWJ-sequence or
     * mid-surrogate-pair).  If {@code rawIdx} falls inside a cluster, it is moved
     * to the end of that cluster.
     */
    private int snapToCluster(int rawIdx) {
        if (rawIdx <= 0 || rawIdx >= buf.length()) return rawIdx;
        int pos = 0;
        while (pos < buf.length()) {
            int len = emojiLenAt(pos);
            if (len == 0) return rawIdx;
            int next = pos + len;
            if (rawIdx <= pos) return pos;
            if (rawIdx < next) return next;
            pos = next;
        }
        return rawIdx;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Caret movement helpers (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private void moveLine(int dir, boolean shift) {
        PathElement[] shape = textFlow.caretShape(rawToTF(caretPos), true);
        if (shape == null || shape.length == 0) return;
        double cx = 0, cy = 0;
        for (PathElement pe : shape)
            if (pe instanceof MoveTo m) {
                cx = m.getX();
                cy = m.getY();
            }
        double targetY = cy + dir * lineHeight() + lineHeight() / 2.0;
        int newRaw = clamp(
                tfToRaw(textFlow.hitTest(new Point2D(cx, Math.max(0, targetY))).getCharIndex()),
                0, buf.length());
        if (shift) caretPos = newRaw;
        else setCaretAndAnchor(newRaw, -1);
        refreshOverlay();
        ensureCaretVisible();
    }

    private int startOfVisualLine(int rawIdx) {
        PathElement[] shape = textFlow.caretShape(rawToTF(rawIdx), true);
        if (shape == null || shape.length == 0) return rawIdx;
        double cy = 0;
        for (PathElement pe : shape) if (pe instanceof MoveTo m) cy = m.getY();
        return clamp(tfToRaw(
                        textFlow.hitTest(new Point2D(0, cy + lineHeight() / 2.0)).getCharIndex()),
                0, buf.length());
    }

    private int endOfVisualLine(int rawIdx) {
        PathElement[] shape = textFlow.caretShape(rawToTF(rawIdx), true);
        if (shape == null || shape.length == 0) return rawIdx;
        double cy = 0;
        for (PathElement pe : shape) if (pe instanceof MoveTo m) cy = m.getY();
        return clamp(tfToRaw(
                        textFlow.hitTest(new Point2D(
                                textFlow.getWidth() - 2 * H_PAD - 1,
                                cy + lineHeight() / 2.0)).getCharIndex()),
                0, buf.length());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scroll (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private boolean caretAtBottom = false;

    private void ensureCaretVisible() {
        Platform.runLater(() -> {
            double vpH = scrollPane.getViewportBounds().getHeight();
            double contentH = contentBox.getHeight();
            if (vpH <= 0 || contentH <= vpH + 1) {
                scrollPane.setVvalue(0);
                caretAtBottom = false;
                return;
            }
            double range = contentH - vpH;
            double caretY = caretRect.getLayoutY();
            double caretBot = caretY + caretRect.getHeight();
            double scrollTop = scrollPane.getVvalue() * range;
            double scrollBot = scrollTop + vpH;

            if (caretY < scrollTop) {
                caretAtBottom = false;
                scrollPane.setVvalue(Math.max(0, (caretY - V_PAD) / range));
            } else if (caretBot > scrollBot - V_PAD) {
                caretAtBottom = true;
                scrollPane.setVvalue(1.0);
            } else {
                caretAtBottom = false;
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prompt (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshPromptVisibility() {
        boolean show = buf.length() == 0
                && promptProp.get() != null && !promptProp.get().isEmpty();
        promptNode.setVisible(show);
        promptNode.setManaged(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Clipboard / util (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private static void toClipboard(String text) {
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Picker (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private Button buildPickerButton() {
        Button btn = new Button();
        btn.setGraphic(new FontIcon(MaterialDesignE.EMOTICON_OUTLINE));
        btn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        btn.setFocusTraversable(false);
        btn.setTooltip(new Tooltip("Insert emoji"));
        return btn;
    }

    public Button getPickerButton() {
        return pickerButton;
    }

    public void insertEmoji(Emoji emoji) {
        replaceSelection(emoji.character());
        layerStack.requestFocus();
    }

    public void attachPickerPopup(EmojiPickerPopup popup) {
        Button btn = getPickerButton();
        if (btn == null) return;
        btn.setOnAction(e -> {
            if (popup.isShowing()) {
                popup.hide();
                return;
            }
            popup.setOnEmojiSelected(this::insertEmoji);
            javafx.geometry.Bounds b = btn.localToScreen(btn.getBoundsInLocal());
            if (b != null) {
                double x = b.getMinX() - 90;
                double y = b.getMinY() - 460 - 4;
                if (y < 0) y = b.getMaxY() + 4;
                popup.show(btn.getScene().getWindow(), x, y);
            }
        });
    }

    private ContextMenu buildContextMenu() {
        MenuItem miUndo = new MenuItem("Undo");
        MenuItem miRedo = new MenuItem("Redo");
        MenuItem miCut = new MenuItem("Cut");
        MenuItem miCopy = new MenuItem("Copy");
        MenuItem miPaste = new MenuItem("Paste");
        MenuItem miDelete = new MenuItem("Delete");
        MenuItem miSelectAll = new MenuItem("Select All");

        miUndo.setOnAction(e -> {
            undo();
            layerStack.requestFocus();
        });
        miRedo.setOnAction(e -> {
            redo();
            layerStack.requestFocus();
        });
        miCut.setOnAction(e -> {
            if (hasSelection()) {
                toClipboard(selectedText());
                replaceSelection("");
            }
        });
        miCopy.setOnAction(e -> {
            if (hasSelection()) toClipboard(selectedText());
        });
        miPaste.setOnAction(e -> {
            String s = Clipboard.getSystemClipboard().getString();
            if (s != null && !s.isEmpty()) {
                if (pasteInterceptor != null && Boolean.TRUE.equals(pasteInterceptor.apply(s))) {
                    layerStack.requestFocus();
                    return;
                }
                replaceSelection(s);
            }
            layerStack.requestFocus();
        });
        miDelete.setOnAction(e -> {
            if (hasSelection()) replaceSelection("");
            layerStack.requestFocus();
        });
        miSelectAll.setOnAction(e -> {
            setCaretAndAnchor(0, buf.length());
            refreshOverlay();
            layerStack.requestFocus();
        });

        SeparatorMenuItem separator = new SeparatorMenuItem();
        ContextMenu menu = new ContextMenu(miUndo, miRedo,
                miCut, miCopy, miPaste, miDelete,
                separator, miSelectAll);

        menu.setOnShowing(e -> {
            boolean hasSel = hasSelection();
            boolean editable = editableProp.get();
            miUndo.setVisible(editable);
            miRedo.setVisible(editable);
            miCut.setVisible(editable);
            miPaste.setVisible(editable);
            miDelete.setVisible(editable);
            separator.setVisible(editable);
            miUndo.setDisable(undoStack.isEmpty());
            miRedo.setDisable(redoStack.isEmpty());
            miCut.setDisable(!hasSel);
            miCopy.setDisable(!hasSel);
            miPaste.setDisable(false);
            miDelete.setDisable(!hasSel);
            miSelectAll.setDisable(false);
        });
        return menu;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public String getText() {
        return textProp.get();
    }

    public void setText(String v) {
        textProp.set(v == null ? "" : v);
    }

    public StringProperty textProperty() {
        return textProp;
    }

    public String getPromptText() {
        return promptProp.get();
    }

    public void setPromptText(String v) {
        promptProp.set(v == null ? "" : v);
    }

    public StringProperty promptTextProperty() {
        return promptProp;
    }

    public boolean isEditable() {
        return editableProp.get();
    }

    public void setEditable(boolean v) {
        editableProp.set(v);
    }

    public BooleanProperty editableProperty() {
        return editableProp;
    }

    @Override
    public void requestFocus() {
        layerStack.requestFocus();
    }

    public boolean isFieldFocused() {
        return layerStack.isFocused();
    }

    public void selectAll() {
        setCaretAndAnchor(0, buf.length());
        refreshOverlay();
    }

    public void clear() {
        clearBuf();
    }

    public void setOnTextChanged(Consumer<String> listener) {
        textProp.addListener((obs, o, n) -> listener.accept(n));
    }

    /**
     * Enter (no modifier) calls this with the current text.
     * Return {@code true} to consume the event and auto-clear.
     * Shift+Enter always inserts a newline.
     */
    public void setOnSubmit(Function<String, Boolean> handler) {
        this.submitHandler = handler;
    }

    /**
     * Sets an interceptor consulted before inserting pasted text. Return
     * {@code true} to consume the paste (nothing is inserted).
     */
    public void setPasteInterceptor(Function<String, Boolean> interceptor) {
        this.pasteInterceptor = interceptor;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public void setMinLines(int minLines) {
        this.minLines = Math.max(1, minLines);
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = Math.max(1, maxLines);
    }

    public void moveCursorToEnd() {
        setCaretAndAnchor(buf.length(), buf.length());
        refreshOverlay();
    }

    /**
     * Registers a one-shot callback that fires (via Platform.runLater) once the
     * internal scroll viewport has a valid width — i.e., after the first layout
     * pass. If the viewport is already valid, the callback is queued immediately.
     * Use this to request focus after the textarea has been newly added to the scene.
     */
    public void runAfterLayout(Runnable callback) {
        if (scrollPane.getViewportBounds().getWidth() > 0) {
            Platform.runLater(callback);
        } else {
            this.onLayoutReady = callback;
        }
    }
}