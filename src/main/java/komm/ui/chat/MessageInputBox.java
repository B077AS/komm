package komm.ui.chat;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import komm.App;
import komm.ui.attachments.AttachmentBarSlot;
import komm.ui.emojis.EmojiPickerPopup;
import komm.ui.emojis.EmojiTextArea;
import komm.ui.code.CodeDetector;
import komm.ui.gifs.GifPickerPopup;
import komm.ui.utils.FileChooserUtil;
import komm.ui.utils.IconColorUtil;
import komm.websocket.messages.payloads.MessageReceivedPayload;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import atlantafx.base.theme.Styles;

import java.io.File;
import java.util.*;

/**
 * The message input bar. Owns no reply bar or attachment bar — those live in
 * ChatSection as separate {@code VBox} slots with their own animations.
 *
 * <p>The "image" button has been replaced with a generic attachment button that
 * opens a file-chooser. Drag-and-drop onto the whole ChatSection is handled by
 * ChatSection itself using {@link #addFilesToAttachmentBar(List)}.
 */
@Slf4j
public class MessageInputBox extends VBox {

    public interface MessageInputListener {
        void onMessageSubmit(String text, MessageReceivedPayload replyTarget);

        void onTyping();

        /**
         * Requested to compose a code message. {@code prefill} is the text to
         * pre-populate the editor with (e.g. pasted code), or {@code null} when
         * opened via the code button with an empty editor.
         */
        void onCodeRequested(String prefill);
    }

    private static final long TYPING_DEBOUNCE_MS = 1500;
    private static final long TYPING_TIMEOUT_MS = 4000;

    @Getter
    private final EmojiPickerPopup emojiPicker = new EmojiPickerPopup();
    @Getter
    private final GifPickerPopup gifPicker = new GifPickerPopup();

    private final MessageInputListener listener;

    // Owned by ChatSection — injected after construction
    @Getter
    @Setter
    private AttachmentBarSlot attachmentBarSlot;

    // Typing row is placed by ChatSection above the reply bar slot
    @Getter
    private HBox typingIndicatorRow;
    private Label typingTextLabel;
    private Timeline dotsAnimation;

    private final Map<UUID, String> typingUsers = new LinkedHashMap<>();
    private final Map<UUID, Timeline> typingTimers = new HashMap<>();

    private HBox mainInputRow;
    private EmojiTextArea emojiArea;

    /**
     * Kept here only to pass on submit; actual UI lives in ChatSection.
     */
    MessageReceivedPayload replyTarget;
    private long lastTypingSentAt = 0;

    public MessageInputBox(MessageInputListener listener) {
        this.listener = listener;
        setPadding(new Insets(0, 16, 16, 16));
        buildUI();
    }

    /**
     * Called by ChatSection when the reply bar opens.
     */
    public void setReplyTarget(MessageReceivedPayload target) {
        this.replyTarget = target;
        refreshInputRowRadius();
        if (target != null) focusInput();
    }

    public void focusInput() {
        if (emojiArea != null) emojiArea.requestFocus();
    }

    /**
     * Called by ChatSection when channel changes or reply is dismissed.
     */
    public void clearReply() {
        replyTarget = null;
        refreshInputRowRadius();
    }

    /**
     * Forward files to the attachment bar (called from drag-drop in ChatSection).
     */
    public void addFilesToAttachmentBar(List<File> files) {
        if (attachmentBarSlot == null) return;
        for (File f : files) attachmentBarSlot.addFile(f);
    }

    public void registerTyping(UUID userId, String username) {
        if (userId.equals(App.getUser().getUserId())) return;
        Platform.runLater(() -> {
            typingUsers.put(userId, username != null ? username : "Someone");
            refreshTypingLabel();
            Timeline old = typingTimers.remove(userId);
            if (old != null) old.stop();
            Timeline t = new Timeline(new KeyFrame(Duration.millis(TYPING_TIMEOUT_MS), e -> {
                typingUsers.remove(userId);
                typingTimers.remove(userId);
                Platform.runLater(this::refreshTypingLabel);
            }));
            t.play();
            typingTimers.put(userId, t);
        });
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        // Typing row — managed by ChatSection, not added here
        typingTextLabel = new Label("");
        typingTextLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        Circle d1 = makeDot(), d2 = makeDot(), d3 = makeDot();
        HBox dots = new HBox(3, d1, d2, d3);
        dots.setAlignment(Pos.CENTER);

        dotsAnimation = new Timeline(
                kf(0, d1.translateYProperty(), 0),
                kf(300, d1.translateYProperty(), -3),
                kf(600, d1.translateYProperty(), 0),
                kf(200, d2.translateYProperty(), 0),
                kf(500, d2.translateYProperty(), -3),
                kf(800, d2.translateYProperty(), 0),
                kf(400, d3.translateYProperty(), 0),
                kf(700, d3.translateYProperty(), -3),
                kf(1000, d3.translateYProperty(), 0));
        dotsAnimation.setCycleCount(Animation.INDEFINITE);

        typingIndicatorRow = new HBox(5, typingTextLabel, dots);
        typingIndicatorRow.setAlignment(Pos.CENTER_LEFT);
        typingIndicatorRow.setMinHeight(16);
        typingIndicatorRow.setPrefHeight(16);
        typingIndicatorRow.setMaxHeight(16);
        typingIndicatorRow.setVisible(false);

        mainInputRow = buildInputRow();
        getChildren().add(mainInputRow);
    }

    private Circle makeDot() {
        Circle c = new Circle(1.5);
        c.setStyle("-fx-fill: -color-fg-muted;");
        c.setOpacity(0.7);
        return c;
    }

    private static KeyFrame kf(double ms,
                               javafx.beans.value.WritableValue<Number> p,
                               double v) {
        return new KeyFrame(Duration.millis(ms),
                new KeyValue(p, v, Interpolator.SPLINE(0.4, 0, 0.2, 1)));
    }

    public void setPromptText(String text) {
        if (emojiArea != null) emojiArea.setPromptText(text);
    }

    private HBox buildInputRow() {
        emojiArea = new EmojiTextArea(true);
        emojiArea.setMaxLength(2000);
        emojiArea.setPromptText("Message");
        HBox.setHgrow(emojiArea, Priority.ALWAYS);
        emojiArea.attachPickerPopup(emojiPicker);

        emojiArea.setOnSubmit(text -> { submitMessage(text); return true; });

        // Divert pasted source code to the code-message editor instead of the box.
        emojiArea.setPasteInterceptor(pasted -> {
            if (CodeDetector.looksLikeCode(pasted)) {
                listener.onCodeRequested(pasted);
                return true;
            }
            return false;
        });

        emojiArea.textProperty().addListener((obs, ov, nv) -> {
            long now = System.currentTimeMillis();
            if (now - lastTypingSentAt >= TYPING_DEBOUNCE_MS) {
                lastTypingSentAt = now;
                listener.onTyping();
            }
        });

        // ── Send button ───────────────────────────────────────────────────────
        Button sendButton = new Button(null, accentIcon(MaterialDesignS.SEND_VARIANT_OUTLINE, 18));
        sendButton.setFocusTraversable(false);
        sendButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        sendButton.setOnAction(e -> {
            String text = emojiArea.getText();
            boolean hasAttachment = attachmentBarSlot != null && !attachmentBarSlot.isEmpty();
            if ((text != null && !text.isBlank()) || hasAttachment) {
                submitMessage(text);
                emojiArea.clear();
            }
            emojiArea.requestFocus();
        });

        // ── GIF button ────────────────────────────────────────────────────────
        Button gifButton = new Button(null, new FontIcon(MaterialDesignF.FILE_GIF_BOX));
        gifButton.setFocusTraversable(false);
        gifButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        gifButton.setOnAction(e -> {
            if (gifPicker.isShowing()) {
                gifPicker.hide();
            } else {
                javafx.geometry.Bounds b =
                        gifButton.localToScreen(gifButton.getBoundsInLocal());
                if (b != null) {
                    double x = b.getMinX() - 120;
                    double y = b.getMinY() - 480 - 4;
                    if (y < 0) y = b.getMaxY() + 4;
                    gifPicker.show(gifButton.getScene().getWindow(), x, y);
                }
            }
        });

        // ── Attachment button (replaces old image button) ─────────────────────
        Button attachButton = new Button(null, new FontIcon(MaterialDesignP.PAPERCLIP));
        attachButton.setFocusTraversable(false);
        attachButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        attachButton.setOnAction(e -> openFileChooser(attachButton));

        // ── Code button ───────────────────────────────────────────────────────
        Button codeButton = new Button(null, new FontIcon(MaterialDesignC.CODE_BRACES));
        codeButton.setFocusTraversable(false);
        codeButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        codeButton.setTooltip(new Tooltip("Send code"));
        codeButton.setOnAction(e -> listener.onCodeRequested(null));

        HBox row = new HBox(8);
        row.setAlignment(Pos.BOTTOM_LEFT);
        row.setPadding(new Insets(8, 8, 8, 16));
        row.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 8px;");
        row.getChildren().addAll(emojiArea, gifButton, attachButton, codeButton, sendButton);
        return row;
    }

    private static FontIcon accentIcon(org.kordamp.ikonli.Ikon ikon, int size) {
        return IconColorUtil.colored(ikon, "-color-accent-emphasis", size);
    }

    private void submitMessage(String text) {
        listener.onMessageSubmit(text, replyTarget);
        if (replyTarget != null) {
            replyTarget = null;
            refreshInputRowRadius();
        }
    }

    private void openFileChooser(Button anchor) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Attach files");
        // No extension filter — accept everything
        List<File> files = FileChooserUtil.showOpenMultipleDialog(fc, anchor.getScene().getWindow());
        if (files != null && !files.isEmpty()) {
            addFilesToAttachmentBar(files);
        }
    }

    // ── Radius management ─────────────────────────────────────────────────────

    /**
     * Recalculate corner radii based on whether either the reply bar or the
     * attachment bar is open above this input row.
     */
    void refreshInputRowRadius() {
        if (mainInputRow == null) return;
        boolean flatTop = (replyTarget != null)
                || (attachmentBarSlot != null && !attachmentBarSlot.isEmpty());
        mainInputRow.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                        "-fx-background-radius: " + (flatTop ? "0 0 8px 8px" : "8px") + ";"
        );
    }

    // Keep the old name so ChatSection compiles without changes
    void setInputRowRadius(boolean flatTop) {
        if (mainInputRow == null) return;
        mainInputRow.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                        "-fx-background-radius: " + (flatTop ? "0 0 8px 8px" : "8px") + ";"
        );
    }

    // ── Typing label ──────────────────────────────────────────────────────────

    private void refreshTypingLabel() {
        if (typingIndicatorRow == null) return;
        if (typingUsers.isEmpty()) {
            typingIndicatorRow.setVisible(false);
            dotsAnimation.stop();
            typingTextLabel.setText("");
            return;
        }
        List<String> names = new ArrayList<>(typingUsers.values());
        String text = switch (names.size()) {
            case 1 -> names.get(0) + " is typing";
            case 2 -> names.get(0) + " and " + names.get(1) + " are typing";
            default -> "Several people are typing";
        };
        typingTextLabel.setText(text);
        typingIndicatorRow.setVisible(true);
        if (dotsAnimation.getStatus() != Animation.Status.RUNNING)
            dotsAnimation.playFromStart();
    }
}