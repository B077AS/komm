package komm.ui.customnodes;

import io.github.b077as.emojifx.EmojiData;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import komm.ui.emojis.EmojiFilterTextField;
import komm.ui.emojis.EmojiPickerPopup;
import komm.ui.utils.IconColorUtil;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;

import java.util.List;

/**
 * A single "status" control combining a one-emoji status icon and a plain
 * (emoji-free) status message field into one pill, the way Discord's custom
 * status editor does. The icon and text act as one visual unit: a shared
 * border/background reacts to the text field's focus, while the icon tile is
 * its own click target for the emoji picker.
 *
 * <p>The emoji is tracked as its codepoint "unified" string (e.g. {@code "1F600"},
 * matching {@link io.github.b077as.emojifx.Emoji#getUnified()}) rather than the raw
 * character, mirroring how reactions are persisted elsewhere in the app.
 */
public class StatusMessageEditor extends HBox {

    public static final int MAX_LENGTH = 60;
    private static final double TILE_SIZE = 30;
    private static final double EMOJI_RENDER_SIZE = 18;

    private final EmojiPickerPopup emojiPicker = new EmojiPickerPopup();
    private final StackPane emojiTile = new StackPane();
    private final ContextMenu emojiTileContextMenu = new ContextMenu();
    private final EmojiFilterTextField textField = EmojiFilterTextField.maxLength(MAX_LENGTH);
    private final StringProperty statusEmojiUnified = new SimpleStringProperty(this, "statusEmojiUnified", null);

    public StatusMessageEditor() {
        setSpacing(0);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(3));
        setMaxWidth(Double.MAX_VALUE);
        applyContainerStyle(false);

        emojiTile.setMinSize(TILE_SIZE, TILE_SIZE);
        emojiTile.setMaxSize(TILE_SIZE, TILE_SIZE);
        emojiTile.setStyle(tileStyle(false));
        emojiTile.setOnMouseEntered(e -> emojiTile.setStyle(tileStyle(true)));
        emojiTile.setOnMouseExited(e -> emojiTile.setStyle(tileStyle(false)));
        emojiTile.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                toggleEmojiPicker();
            } else if (e.getButton() == MouseButton.SECONDARY) {
                emojiTileContextMenu.show(emojiTile, e.getScreenX(), e.getScreenY());
            }
        });

        MenuItem clearItem = new MenuItem("Clear status icon");
        clearItem.setOnAction(e -> setStatusEmojiUnified(null));
        emojiTileContextMenu.getItems().add(clearItem);

        // A plain 1px Region rather than a Separator control: Separator carries
        // its own theme padding/insets, so coloring its background paints a
        // solid block instead of a hairline.
        Region divider = new Region();
        divider.setMinWidth(1);
        divider.setPrefWidth(1);
        divider.setMaxWidth(1);
        divider.setPrefHeight(18);
        divider.setStyle("-fx-background-color: -color-border-muted;");
        HBox dividerWrap = new HBox(divider);
        dividerWrap.setAlignment(Pos.CENTER);
        dividerWrap.setPadding(new Insets(0, 8, 0, 8));

        textField.setPromptText("Set a status message…");
        textField.setMaxWidth(Double.MAX_VALUE);
        textField.setStyle(fieldStyle());
        HBox.setHgrow(textField, Priority.ALWAYS);
        textField.focusedProperty().addListener((obs, was, is) -> applyContainerStyle(is));
        textField.setOnAction(e -> textField.getParent().requestFocus());

        emojiPicker.setOnEmojiSelected(emoji -> {
            setStatusEmojiUnified(emoji.getUnified());
            emojiPicker.hide();
        });

        getChildren().addAll(emojiTile, dividerWrap, textField);
        refreshEmojiTile();
    }

    // ── emoji tile ───────────────────────────────────────────────────────────

    private void toggleEmojiPicker() {
        if (emojiPicker.isShowing()) {
            emojiPicker.hide();
            return;
        }
        Bounds b = emojiTile.localToScreen(emojiTile.getBoundsInLocal());
        if (b == null || emojiTile.getScene() == null) return;
        double x = b.getMinX();
        double y = b.getMinY() - 444;
        if (y < 0) y = b.getMaxY() + 4;
        emojiPicker.show(emojiTile.getScene().getWindow(), x, y);
    }

    private void refreshEmojiTile() {
        emojiTile.getChildren().clear();
        String unified = statusEmojiUnified.get();

        if (unified == null || unified.isBlank()) {
            FontIcon placeholder = new FontIcon(MaterialDesignE.EMOTICON_OUTLINE);
            IconColorUtil.apply(placeholder, "-color-fg-subtle", 16);
            emojiTile.getChildren().add(placeholder);
            Tooltip.install(emojiTile, new Tooltip("Set a status icon"));
        } else {
            var emojiOpt = EmojiData.emojiFromCodepoints(unified);
            if (emojiOpt.isPresent()) {
                List<Node> nodes = TextUtils.convertToTextAndImageNodes(emojiOpt.get().character(), EMOJI_RENDER_SIZE);
                if (!nodes.isEmpty()) {
                    Node n = nodes.get(0);
                    if (n instanceof ImageView iv) {
                        iv.setFitWidth(EMOJI_RENDER_SIZE);
                        iv.setFitHeight(EMOJI_RENDER_SIZE);
                        iv.setPreserveRatio(true);
                        iv.setSmooth(true);
                        emojiTile.getChildren().add(iv);
                    } else if (n instanceof Text t) {
                        t.setStyle("-fx-font-size: " + EMOJI_RENDER_SIZE + "px;");
                        emojiTile.getChildren().add(t);
                    }
                }
                Tooltip.install(emojiTile, new Tooltip("Change status icon"));
            } else {
                FontIcon placeholder = new FontIcon(MaterialDesignE.EMOTICON_OUTLINE);
                IconColorUtil.apply(placeholder, "-color-fg-subtle", 16);
                emojiTile.getChildren().add(placeholder);
            }
        }
        emojiTileContextMenu.getItems().get(0).setDisable(unified == null || unified.isBlank());
    }

    // ── styling ──────────────────────────────────────────────────────────────

    private void applyContainerStyle(boolean focused) {
        String border = focused ? "-color-accent-7" : "-color-border-default";
        String bg = focused ? "-color-bg-default" : "transparent";
        setStyle(
                "-fx-background-color: " + bg + ";" +
                        "-fx-background-radius: 8px;" +
                        "-fx-border-color: " + border + ";" +
                        "-fx-border-radius: 8px;" +
                        "-fx-border-width: 1.5px;"
        );
    }

    private String tileStyle(boolean hovered) {
        return "-fx-background-color: " + (hovered ? "-color-neutral-subtle" : "transparent") + ";" +
                "-fx-background-radius: 6px;" +
                "-fx-cursor: hand;";
    }

    private String fieldStyle() {
        return "-fx-background-color: transparent;" +
                "-fx-border-color: transparent;" +
                "-fx-text-fill: -color-fg-muted;" +
                "-fx-font-size: 12px;" +
                "-fx-padding: 4 8 4 0;";
    }

    // ── public API ───────────────────────────────────────────────────────────

    public String getStatusText() {
        return textField.getText();
    }

    public void setStatusText(String text) {
        textField.setText(text == null ? "" : text);
    }

    public StringProperty textProperty() {
        return textField.textProperty();
    }

    public String getStatusEmojiUnified() {
        return statusEmojiUnified.get();
    }

    public void setStatusEmojiUnified(String unified) {
        statusEmojiUnified.set((unified == null || unified.isBlank()) ? null : unified);
        refreshEmojiTile();
    }

    public StringProperty statusEmojiUnifiedProperty() {
        return statusEmojiUnified;
    }

    public ReadOnlyBooleanProperty textFieldFocusedProperty() {
        return textField.focusedProperty();
    }

    public void requestTextFocus() {
        textField.requestFocus();
    }
}
