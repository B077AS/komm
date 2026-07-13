package komm.ui.emojis;

import io.github.b077as.emojifx.Emoji;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.text.*;
import komm.App;
import komm.ui.code.CodeBlockView;
import komm.ui.code.CodeLanguage;
import komm.ui.gifs.GifMessageCell;
import komm.ui.modals.ConfirmationModal;
import komm.ui.utils.IconColorUtil;
import lombok.Getter;
import lombok.Setter;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A read-only, emoji-aware message bubble with Discord-style reaction support.
 *
 * <p>Layout:
 * <pre>
 *   EmojiMessageContent  (VBox, focusable)
 *   ├── bubbleStack      (StackPane)
 *   │   ├── selectionPane  (Pane — per-line highlight rects, behind text)
 *   │   └── textFlow       (TextFlow — emoji-aware rendering, mouse-transparent)
 *   └── reactionBar      (EmojiReactionBar — shown below bubble when reactions exist)
 * </pre>
 *
 * <p>The context menu "Add Reaction…" item and the public
 * {@link #showReactionPickerAt} method both open an {@link EmojiPickerPopup}
 * anchored near the bubble. All state is local — no server calls.
 */
public class EmojiMessageContent extends VBox {

    // ── Constants ─────────────────────────────────────────────────────────────

    static final double EMOJI_SIZE = 20.0;
    public static final double H_PAD = 8.0;
    public static final double V_PAD = 0.0;
    private static final double SEL_VPAD = 4.0;

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:https?://|www\\.)[^\\s<>\"'\\[\\]{}|\\\\^`]+"
    );

    // ── Model ─────────────────────────────────────────────────────────────────

    @Getter
    private final String text;
    private int[] charStarts = {0};
    private final List<String> nodeUrls = new ArrayList<>();

    private int caretPos = 0;
    private int anchorPos = 0;
    private int dragAnchorRaw = 0;
    private String pendingLinkUrl;

    // ── Nodes ─────────────────────────────────────────────────────────────────

    @Getter
    private final StackPane bubbleStack = new StackPane();
    private final TextFlow textFlow = new TextFlow();
    private final Pane selectionPane = new Pane();

    @Getter
    private final EmojiReactionBar reactionBar = new EmojiReactionBar();

    private EmojiPickerPopup reactionPicker;
    @Setter
    private Runnable onDelete;
    @Setter
    private Runnable onEdit;
    @Setter
    private BiConsumer<EmojiMessageContent, Emoji> onAddReaction;
    @Setter
    private Consumer<EmojiMessageContent> onReply;
    @Setter
    private Runnable onAllPopupsClosed;

    private double lastContextMenuScreenX;
    private double lastContextMenuScreenY;
    private CodeBlockView codeBlock = null;

    private final ContextMenu contextMenu;
    private BooleanSupplier deleteVisibleSupplier;

    // ── Constructor ───────────────────────────────────────────────────────────

    public EmojiMessageContent(String rawText) {
        super(0);
        this.text = rawText;

        setAlignment(Pos.TOP_LEFT);
        setFocusTraversable(true);
        //setCursor(javafx.scene.Cursor.TEXT);

        // ── Bubble stack (selection highlights + text) ────────────────────────
        selectionPane.setMouseTransparent(true);
        selectionPane.setBackground(Background.EMPTY);

        textFlow.setPadding(new javafx.geometry.Insets(V_PAD, H_PAD, V_PAD, H_PAD));
        textFlow.setMouseTransparent(true);
        textFlow.setLineSpacing(0);

        bubbleStack.setAlignment(Pos.TOP_LEFT);
        bubbleStack.getChildren().addAll(selectionPane, textFlow);
        bubbleStack.setFocusTraversable(false);
        bubbleStack.setCursor(javafx.scene.Cursor.TEXT);

        VBox.setMargin(reactionBar, new Insets(0, 0, 0, H_PAD));
        reactionBar.setOnPickerRequested(coords -> openReactionPickerAt(coords[0], coords[1]));

        getChildren().addAll(bubbleStack, reactionBar);

        contextMenu = buildContextMenu();
        wireListeners();
        buildTextFlow();
    }

    public static EmojiMessageContent of(String text) {
        return new EmojiMessageContent(text);
    }

    public void setDeleteVisible(boolean visible) {
        contextMenu.getItems().stream()
                .filter(mi -> "Delete".equals(mi.getText()))
                .findFirst()
                .ifPresent(mi -> mi.setVisible(visible));
        var items = contextMenu.getItems();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i) instanceof SeparatorMenuItem sep) {
                sep.setVisible(visible);
                break;
            }
        }
    }

    public void setEditVisible(boolean visible) {
        contextMenu.getItems().stream()
                .filter(mi -> "Edit".equals(mi.getText()))
                .findFirst()
                .ifPresent(mi -> mi.setVisible(visible));
    }

    /**
     * Registers a supplier re-evaluated every time the context menu is about to be
     * shown, so permission-based Delete visibility never goes stale (e.g. after the
     * user's role/permissions changed while this row wasn't being looked at).
     */
    public void setDeleteVisibleSupplier(BooleanSupplier supplier) {
        this.deleteVisibleSupplier = supplier;
    }

    /**
     * Shows the context menu at the given screen coordinates.
     * Called by {@link EmojiMessageItem} when the user right-clicks anywhere
     * on the message row, not just within the bubble itself.
     */
    public void showContextMenuAt(double screenX, double screenY) {
        lastContextMenuScreenX = screenX;
        lastContextMenuScreenY = screenY;
        updateContextMenuState();
        contextMenu.show(this, screenX, screenY);
    }

    /**
     * Opens the reaction emoji picker anchored near the bubble.
     * Can be called externally (e.g. from the hover "😊+" button in the item row).
     */
    public void showReactionPickerAt(double screenX, double screenY) {
        openReactionPickerAt(screenX, screenY);
    }

    // ── Reaction picker helpers ───────────────────────────────────────────────

    private void openReactionPickerNearBubble() {
        if (getScene() == null) return;
        javafx.geometry.Bounds b = localToScreen(getBoundsInLocal());
        if (b == null) return;
        // Try to show above the bubble; fall back to below if too high
        double x = b.getMinX();
        double y = b.getMinY() - 444 - 4; // 444 ≈ POPUP_HEIGHT + gap
        if (y < 0) y = b.getMaxY() + 4;
        openReactionPickerAt(x, y);
    }

    private void openReactionPickerAt(double screenX, double screenY) {
        if (reactionPicker == null) {
            reactionPicker = new EmojiPickerPopup();
            reactionPicker.setOnEmojiSelected(emoji -> {
                reactionPicker.hide();
                if (onAddReaction != null) onAddReaction.accept(this, emoji);
            });

            reactionPicker.setOnHidden(e -> {
                if (onAllPopupsClosed != null && !contextMenu.isShowing()) {
                    onAllPopupsClosed.run();
                }
            });
        }
        if (getScene() != null) {
            reactionPicker.show(getScene().getWindow(), screenX, screenY);
        }
    }

    private void wireListeners() {

        bubbleStack.setOnMousePressed(e -> {
            requestFocus();

            if (e.getButton() == MouseButton.SECONDARY) {
                showContextMenuAt(e.getScreenX(), e.getScreenY());
                e.consume();
                return;
            }
            if (contextMenu.isShowing()) contextMenu.hide();

            // Remember a link under the cursor; opened on release if no drag occurs
            // (so text selection over a link still works).
            pendingLinkUrl = e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1
                    ? urlAt(e.getX(), e.getY())
                    : null;

            if (e.getClickCount() == 2) {
                setCaretAndAnchor(0, text.length());
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

        bubbleStack.setOnMouseMoved(e ->
                updateCursorForPosition(e.getX(), e.getY(), e.isControlDown() || e.isMetaDown()));

        bubbleStack.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                e.consume();
                return;
            }
            int idx = hitTest(e.getX(), e.getY());
            anchorPos = dragAnchorRaw;
            caretPos = idx;
            if (idx != dragAnchorRaw) pendingLinkUrl = null; // dragging = selecting, not a link click
            refreshOverlay();
            e.consume();
        });

        bubbleStack.setOnMouseReleased(e -> {
            if (pendingLinkUrl != null && urlAt(e.getX(), e.getY()) != null) {
                confirmOpenUrl(pendingLinkUrl);
            }
            pendingLinkUrl = null;
            e.consume();
        });

        focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                clearSelRects();
                if (contextMenu.isShowing()) contextMenu.hide();
            }
        });

        setOnKeyPressed(e -> {
            boolean ctrl = e.isControlDown() || e.isMetaDown();
            switch (e.getCode()) {
                case A -> {
                    if (ctrl) {
                        setCaretAndAnchor(0, text.length());
                        refreshOverlay();
                        e.consume();
                    }
                }
                case C -> {
                    if (ctrl && hasSelection()) {
                        toClipboard(selectedText());
                        e.consume();
                    }
                }
                default -> {
                }
            }
        });

        refreshOverlay();

        //widthProperty().addListener((obs, o, n) -> Platform.runLater(this::refreshOverlay));

        contextMenu.setOnHidden(e -> {
            if (onAllPopupsClosed != null
                    && (reactionPicker == null || !reactionPicker.isShowing())) {
                onAllPopupsClosed.run();
            }
        });

        // Dismiss context menu on any left-click anywhere on this VBox
        // (covers GIF cells and other children that live outside bubbleStack)
        setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY && contextMenu.isShowing()) {
                contextMenu.hide();
                e.consume();
            }
        });
    }

    // ── Build TextFlow ────────────────────────────────────────────────────────

    private void buildTextFlow() {
        textFlow.getChildren().clear();
        nodeUrls.clear();

        if (text.isEmpty()) {
            charStarts = new int[]{0};
            return;
        }

        List<Integer> starts = new ArrayList<>();
        Font font = Font.font(14);

        Matcher urlMatcher = URL_PATTERN.matcher(text);
        List<int[]> urlRanges = new ArrayList<>();
        while (urlMatcher.find()) urlRanges.add(new int[]{urlMatcher.start(), urlMatcher.end()});

        int rawCursor = 0;
        int urlIdx = 0;

        while (rawCursor < text.length()) {
            while (urlIdx < urlRanges.size() && urlRanges.get(urlIdx)[1] <= rawCursor) urlIdx++;

            int urlStart = urlIdx < urlRanges.size() ? urlRanges.get(urlIdx)[0] : text.length();

            if (urlStart > rawCursor) {
                String segment = text.substring(rawCursor, urlStart);
                List<Node> nodes = TextUtils.convertToTextAndImageNodes(segment, EMOJI_SIZE);
                int segCursor = rawCursor;

                for (Node node : nodes) {
                    starts.add(segCursor);
                    nodeUrls.add(null);
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
                int urlEnd = urlRanges.get(urlIdx)[1];
                String urlTxt = text.substring(urlStart, urlEnd);
                starts.add(rawCursor);
                nodeUrls.add(urlTxt);
                textFlow.getChildren().add(makeLinkNode(urlTxt, font));
                rawCursor = urlEnd;
                urlIdx++;
            }
        }

        starts.add(rawCursor);
        charStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        if (charStarts[charStarts.length - 1] != text.length())
            charStarts[charStarts.length - 1] = text.length();
    }

    // ── Overlay: selection rectangles ─────────────────────────────────────────

    private void refreshOverlay() {
        if (isFocused()) updateSelection();
        else clearSelRects();
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
        javafx.scene.shape.Rectangle r = new javafx.scene.shape.Rectangle(
                x1 + H_PAD,
                y1 + V_PAD - SEL_VPAD / 2.0,
                x2 - x1,
                Math.max(y2 - y1, lineHeight() + SEL_VPAD));
        r.setStyle("-fx-fill: -color-highlight");
        r.setMouseTransparent(true);
        r.setManaged(false);
        selectionPane.getChildren().add(r);
    }

    private void clearSelRects() {
        selectionPane.getChildren().clear();
    }

    // ── Context menu ──────────────────────────────────────────────────────────

    private ContextMenu buildContextMenu() {
        MenuItem miReply = new MenuItem("Reply", new FontIcon(MaterialDesignR.REPLY));
        MenuItem miEdit = new MenuItem("Edit", new FontIcon(MaterialDesignP.PENCIL_OUTLINE));
        MenuItem miCopy = new MenuItem("Copy", new FontIcon(MaterialDesignC.CONTENT_COPY));
        MenuItem miCopyAll = new MenuItem("Copy All", new FontIcon(MaterialDesignC.CONTENT_DUPLICATE));
        MenuItem miAddReaction = new MenuItem("Reaction", new FontIcon(MaterialDesignE.EMOTICON_OUTLINE));
        MenuItem miDelete = new MenuItem("Delete", IconColorUtil.colored(MaterialDesignD.DELETE_OUTLINE, "-color-danger-fg", 18));

        miReply.setOnAction(e -> {
            if (onReply != null) onReply.accept(this);
        });
        miEdit.setOnAction(e -> {
            if (onEdit != null) onEdit.run();
        });
        miCopy.setOnAction(e -> {
            if (hasSelection()) toClipboard(selectedText());
        });
        miCopyAll.setOnAction(e -> toClipboard(text));
        miAddReaction.setOnAction(e -> openReactionPickerAt(lastContextMenuScreenX, lastContextMenuScreenY));
        miDelete.setOnAction(e -> {
            if (onDelete != null) onDelete.run();
        });

        // Edit is hidden by default — shown only for own messages via setEditVisible()
        miEdit.setVisible(false);

        return new ContextMenu(
                miReply,
                miEdit,
                new SeparatorMenuItem(),
                miCopy, miCopyAll,
                new SeparatorMenuItem(),
                miAddReaction,
                new SeparatorMenuItem(),
                miDelete
        );
    }

    private void updateContextMenuState() {
        if (deleteVisibleSupplier != null) {
            setDeleteVisible(deleteVisibleSupplier.getAsBoolean());
        }
        boolean hasSel = codeBlock != null
                ? !codeBlock.getSelectedText().isEmpty()
                : hasSelection();
        contextMenu.getItems().stream()
                .filter(mi -> "Copy".equals(mi.getText()))
                .findFirst()
                .ifPresent(mi -> mi.setDisable(!hasSel));
    }

    // ── Selection helpers ─────────────────────────────────────────────────────

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
        return hasSelection() ? text.substring(selStart(), selEnd()) : "";
    }

    private void setCaretAndAnchor(int caret, int anchor) {
        caretPos = clamp(caret, 0, text.length());
        anchorPos = anchor < 0 ? caretPos : clamp(anchor, 0, text.length());
    }

    // ── Hit test ──────────────────────────────────────────────────────────────

    private int hitTest(double x, double y) {
        var hit = textFlow.hitTest(new Point2D(x - H_PAD, y - V_PAD));
        int tfIdx = hit.isLeading() ? hit.getCharIndex() : hit.getCharIndex() + 1;
        return clamp(tfToRaw(tfIdx), 0, text.length());
    }

    // ── Index mapping: raw ↔ TextFlow ─────────────────────────────────────────

    private int rawToTF(int rawIdx) {
        rawIdx = clamp(rawIdx, 0, text.length());
        int tf = 0;
        List<Node> ch = textFlow.getChildren();
        for (int i = 0; i < charStarts.length - 1; i++) {
            int ns = charStarts[i], ne = charStarts[i + 1];
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
        return text.length();
    }

    // In EmojiMessageContent — three thin delegator methods:

    public void fireEdit() {
        if (onEdit != null) onEdit.run();
    }

    public void fireReply() {
        if (onReply != null) onReply.accept(this);
    }

    public void fireAddReaction() {
        if (onAddReaction != null) onAddReaction.accept(this, null); // null = open picker
    }

    public void fireQuickReact(Emoji emoji) {
        if (onAddReaction != null) onAddReaction.accept(this, emoji);
    }

    // ── Emoji grapheme helpers ────────────────────────────────────────────────

    private int emojiLenAt(int idx) {
        if (idx >= text.length()) return 0;
        int len = Character.charCount(text.codePointAt(idx));
        while (idx + len < text.length()) {
            int cp = text.codePointAt(idx + len);
            if (isEmojiCont(cp)) len += Character.charCount(cp);
            else break;
        }
        return len;
    }

    private static boolean isEmojiCont(int cp) {
        return cp == 0x200D || cp == 0xFE0F || cp == 0x20E3
                || (cp >= 0x1F3FB && cp <= 0x1F3FF)
                || (cp >= 0xE0020 && cp <= 0xE007F);
    }

    // ── Link helpers ──────────────────────────────────────────────────────────

    private String urlAt(double x, double y) {
        int raw = clamp(hitTest(x, y), 0, text.length());
        for (int i = 0; i < charStarts.length - 1; i++) {
            if (raw >= charStarts[i] && raw < charStarts[i + 1])
                return i < nodeUrls.size() ? nodeUrls.get(i) : null;
        }
        return null;
    }

    private void updateCursorForPosition(double x, double y, boolean modDown) {
        // Set the cursor on bubbleStack, not on `this`: bubbleStack has its own
        // cursor (TEXT) which takes precedence over any ancestor cursor.
        bubbleStack.setCursor(urlAt(x, y) != null
                ? javafx.scene.Cursor.HAND
                : javafx.scene.Cursor.TEXT);
    }

    private static void confirmOpenUrl(String url) {
        App.showModal(new ConfirmationModal(
                "Open link",
                "Are you sure you want to open this link in your browser? Only open links you trust.",
                new FontIcon(MaterialDesignA.ALERT_OUTLINE),
                () -> openUrl(url)));
    }

    private static void openUrl(String url) {
        String normalized = url.startsWith("www.") ? "https://" + url : url;
        // Run off the FX thread: Desktop.browse() / process launch can block.
        Thread.ofVirtual().start(() -> {
            try {
                URI uri = new URI(normalized);
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(uri);
                    return;
                }
            } catch (Exception ignored) {
                // fall through to the OS-specific launcher below
            }
            // Fallback for Linux (and any platform where the AWT BROWSE action
            // is unavailable). Desktop.browse() relies on libgnome on Linux and
            // usually fails there, so shell out to the native URL handler.
            // (macOS is not a supported target, so no "open" branch here.)
            try {
                ProcessBuilder pb = com.sun.jna.Platform.isWindows()
                        ? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", normalized)
                        : new ProcessBuilder("xdg-open", normalized);
                pb.start();
            } catch (Exception ignored) {
            }
        });
    }

    private Text makeLinkNode(String t, Font font) {
        Text n = new Text(t);
        n.setFont(font);
        n.setStyle("-fx-fill: -color-accent-4;");
        n.setUnderline(true);
        n.setMouseTransparent(true);
        return n;
    }

    private double lineHeight() {
        Text p = new Text("Ag");
        p.setFont(Font.font(14));
        return Math.max(p.getBoundsInLocal().getHeight(), EMOJI_SIZE);
    }

    private static void toClipboard(String text) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public boolean isAnyPopupOpen() {
        return contextMenu.isShowing()
                || (reactionPicker != null && reactionPicker.isShowing());
    }

    public static EmojiMessageContent ofGif(String gifUrl, int gifWidth, int gifHeight) {
        EmojiMessageContent content = new EmojiMessageContent(""); // ← pass gifUrl, not ""

        GifMessageCell cell = new GifMessageCell(gifUrl, gifWidth, gifHeight);
        VBox.setMargin(cell, new Insets(4, 0, 4, H_PAD));
        content.getChildren().add(0, cell);

        content.markAsGif(gifUrl);
        return content;
    }

    public EmojiReactionBar detachReactionBar() {
        getChildren().remove(reactionBar);
        return reactionBar;
    }

    /**
     * Builds a content bubble wrapping a syntax-highlighted {@link CodeBlockView}.
     * Mirrors {@link #ofGif} — the base text is empty and the code card is added
     * as the first child, so the existing reaction/reply/edit wiring keeps working.
     */
    public static EmojiMessageContent ofCode(String code, String language) {
        EmojiMessageContent content = new EmojiMessageContent("");
        CodeBlockView cell = new CodeBlockView(code, CodeLanguage.fromString(language));
        VBox.setMargin(cell, new Insets(4, 0, 4, H_PAD));
        content.getChildren().add(0, cell);
        content.markAsCode(code, cell);
        // Close the context menu when the user left-clicks inside the code block;
        // RichTextFX consumes mouse events so JavaFX won't auto-dismiss it.
        cell.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.PRIMARY) content.contextMenu.hide();
        });
        return content;
    }

    /** Adjusts the context menu for a code message: adds Copy (selection) + Copy Code, removes Copy All. */
    public void markAsCode(String code, CodeBlockView cell) {
        this.codeBlock = cell;

        contextMenu.getItems().removeIf(mi ->
                "Copy".equals(mi.getText()) || "Copy All".equals(mi.getText()));

        // Removing Copy/CopyAll leaves two consecutive separators — drop one.
        var items = contextMenu.getItems();
        for (int i = items.size() - 1; i > 0; i--) {
            if (items.get(i) instanceof SeparatorMenuItem && items.get(i - 1) instanceof SeparatorMenuItem) {
                items.remove(i);
                break;
            }
        }

        MenuItem miCopy = new MenuItem("Copy", new FontIcon(MaterialDesignC.CONTENT_COPY));
        miCopy.setOnAction(e -> {
            String sel = cell.getSelectedText();
            if (sel != null && !sel.isEmpty()) toClipboard(sel);
        });

        MenuItem miCopyCode = new MenuItem("Copy Code", new FontIcon(MaterialDesignC.CONTENT_DUPLICATE));
        miCopyCode.setOnAction(e -> toClipboard(code));

        // Insert: [Sep] Copy, CopyCode, [new Sep], AddReaction…
        items.add(3, miCopy);
        items.add(4, miCopyCode);
        items.add(5, new SeparatorMenuItem());
    }

    /**
     * Called once when this content wraps a GIF message. Adjusts the context menu accordingly.
     */
    public void markAsGif(String gifUrl) {
        contextMenu.getItems().removeIf(mi ->
                "Copy".equals(mi.getText()) || "Copy All".equals(mi.getText()));

        // Removing Copy/CopyAll leaves two consecutive separators — drop one.
        var items = contextMenu.getItems();
        for (int i = items.size() - 1; i > 0; i--) {
            if (items.get(i) instanceof SeparatorMenuItem && items.get(i - 1) instanceof SeparatorMenuItem) {
                items.remove(i);
                break;
            }
        }

        MenuItem miCopyGifUrl = new MenuItem("Copy GIF URL", new FontIcon(MaterialDesignC.CONTENT_COPY));
        miCopyGifUrl.setOnAction(e -> toClipboard(gifUrl));  // use the passed-in URL

        items.add(2, miCopyGifUrl);
    }
}