package komm.ui.chat;

import atlantafx.base.theme.Styles;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import komm.ui.emojis.EmojiPickerPopup;
import komm.ui.emojis.EmojiTextArea;
import komm.ui.mention.MentionSuggestBar;
import komm.ui.mention.Mentions;
import lombok.Setter;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Inline message-editing widget. Drop it into a message's content column in
 * place of the bubble, wire {@link #setOnSave} and {@link #setOnDismiss}, then
 * call {@link #activate()} to focus and position the caret at the end.
 */
public class MessageEditBox extends VBox {

    private final EmojiTextArea editArea;
    private final EmojiPickerPopup emojiPicker = new EmojiPickerPopup();
    private HBox editRow;
    private MentionSuggestBar mentionBar;
    private Supplier<Collection<UUID>> mentionCandidates;

    @Setter
    private Consumer<String> onSave;
    @Setter
    private Runnable onDismiss;

    public MessageEditBox(String initialText, boolean hasAttachment) {
        editArea = new EmojiTextArea(false);
        editArea.setMaxLength(2000);
        editArea.setText(initialText);

        emojiPicker.setOnEmojiSelected(editArea::insertEmoji);

        Button emojiBtn = new Button(null, new FontIcon(MaterialDesignE.EMOTICON_OUTLINE));
        emojiBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        emojiBtn.setFocusTraversable(false);
        emojiBtn.setOnAction(e -> {
            if (emojiPicker.isShowing()) {
                emojiPicker.hide();
                return;
            }
            Bounds b = emojiBtn.localToScreen(emojiBtn.getBoundsInLocal());
            if (b != null) {
                double x = b.getMinX();
                double y = b.getMinY() - 460 - 4;
                if (y < 0) y = b.getMaxY() + 4;
                emojiPicker.show(emojiBtn.getScene().getWindow(), x, y);
            }
        });

        Button cancelBtn = new Button(null, new FontIcon(Feather.X));
        cancelBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        cancelBtn.setFocusTraversable(false);
        cancelBtn.setOnAction(e -> triggerDismiss());

        editRow = new HBox(4, editArea, emojiBtn, cancelBtn);
        editRow.setAlignment(Pos.BOTTOM_LEFT);
        editRow.setPadding(new Insets(4, 6, 4, 6));
        HBox.setHgrow(editArea, Priority.ALWAYS);

        Label hint = new Label("Enter to save  •  Esc to cancel");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-subtle;");
        HBox hintRow = new HBox(hint);
        hintRow.setPadding(new Insets(0, 6, 4, 8));

        setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                "-fx-background-radius: 2px;" +
                "-fx-border-color: -color-border-muted;" +
                "-fx-border-width: 1px;" +
                "-fx-border-radius: 2px;"
        );
        getChildren().addAll(editRow, hintRow);

        editArea.setOnSubmit(text -> {
            String trimmed = text == null ? "" : text.trim();
            if ((!trimmed.isEmpty() || hasAttachment) && onSave != null) {
                onSave.accept(resolveTypedMentions(trimmed));
            }
            triggerDismiss();
            return true;
        });

        editArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            // An open suggestion list takes Esc first (the text area closes it).
            if (e.getCode() == KeyCode.ESCAPE && (mentionBar == null || !mentionBar.isOpen())) {
                triggerDismiss();
                e.consume();
            }
        });
    }

    /** Turns on @mention suggestions while editing (channel messages only). */
    public void enableMentions(Supplier<Collection<UUID>> candidates, ToIntFunction<UUID> recency) {
        if (mentionBar != null) return;
        mentionCandidates = candidates;
        mentionBar = new MentionSuggestBar(editArea, candidates, recency);
        mentionBar.setTopRounded(false);
        getChildren().add(0, mentionBar); // docked on top of the edit row
        editArea.setPasteTransformer(s -> Mentions.fromPlain(s, candidates.get()));
    }

    private String resolveTypedMentions(String text) {
        if (mentionCandidates == null) return text;
        String converted = Mentions.fromPlain(text, mentionCandidates.get());
        return converted.length() <= 2000 ? converted : text;
    }

    /** Positions the caret at the end and requests focus once layout is ready. */
    public void activate() {
        editArea.moveCursorToEnd();
        editArea.runAfterLayout(editArea::requestFocus);
    }

    private void triggerDismiss() {
        emojiPicker.hide();
        if (mentionBar != null) mentionBar.close();
        if (onDismiss != null) onDismiss.run();
    }
}
