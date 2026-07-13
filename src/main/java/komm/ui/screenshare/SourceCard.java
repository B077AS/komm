package komm.ui.screenshare;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import lombok.Getter;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.util.function.Consumer;

public class SourceCard extends VBox {

    @Getter
    private final long sourceId;
    @Getter
    private final String sourceName;
    @Getter
    private final boolean isWindow;

    private boolean isSelected = false;
    private Consumer<SourceCard> onSelected;

    private final StackPane previewPane;
    private final Label nameLabel;
    private final ImageView previewView;
    private final FontIcon placeholderIcon;
    private ImageView iconOverlay;

    public SourceCard(long sourceId, String sourceName, boolean isWindow) {
        this.sourceId = sourceId;
        this.sourceName = sourceName;
        this.isWindow = isWindow;

        setAlignment(Pos.TOP_CENTER);
        setSpacing(6);
        setPadding(new Insets(8, 8, 8, 8));
        getStyleClass().add("source-card");
        setMaxWidth(172);
        setMinWidth(172);
        setPrefWidth(172);

        // ── Preview pane ──────────────────────────────────────────────────
        previewPane = new StackPane();
        previewPane.setPrefSize(156, 88);
        previewPane.setMinSize(156, 88);
        previewPane.setMaxSize(156, 88);
        previewPane.setStyle("-fx-background-color: -color-bg-elevated; -fx-background-radius: 6px;");

        Rectangle clip = new Rectangle(156, 88);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        previewPane.setClip(clip);

        previewView = new ImageView();
        previewView.setFitWidth(156);
        previewView.setFitHeight(88);
        previewView.setPreserveRatio(true);
        previewView.setSmooth(true);

        placeholderIcon = isWindow
                ? new FontIcon(MaterialDesignA.APPLICATION_OUTLINE)
                : new FontIcon(MaterialDesignM.MONITOR);
        placeholderIcon.setIconSize(28);
        placeholderIcon.setIconColor(Color.web("#555a65"));

        previewPane.getChildren().addAll(previewView, placeholderIcon);

        // ── Name label ────────────────────────────────────────────────────
        String displayName = sourceName.length() > 22
                ? sourceName.substring(0, 19) + "…"
                : sourceName;
        nameLabel = new Label(displayName);
        nameLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-default;");
        nameLabel.setTooltip(new Tooltip(sourceName));

        setOnMouseClicked(e -> { if (onSelected != null) onSelected.accept(this); });

        getChildren().addAll(previewPane, nameLabel);
    }

    public void setOnSelected(Consumer<SourceCard> cb) {
        this.onSelected = cb;
    }

    public void setSelected(boolean selected) {
        this.isSelected = selected;
        if (selected) {
            if (!getStyleClass().contains("source-card-selected"))
                getStyleClass().add("source-card-selected");
        } else {
            getStyleClass().remove("source-card-selected");
        }
    }

    public void setThumbnail(Image thumbnail) {
        previewView.setImage(thumbnail);
        placeholderIcon.setVisible(false);
    }

    public void setIcon(Image icon) {
        if (icon == null) return;

        if (previewView.getImage() != null) {
            if (iconOverlay == null) {
                iconOverlay = new ImageView(icon);
                iconOverlay.setFitWidth(24);
                iconOverlay.setFitHeight(24);
                iconOverlay.setPreserveRatio(true);
                iconOverlay.setSmooth(true);
                StackPane.setAlignment(iconOverlay, Pos.BOTTOM_RIGHT);
                StackPane.setMargin(iconOverlay, new Insets(0, 6, 6, 0));
                previewPane.getChildren().add(iconOverlay);
            } else {
                iconOverlay.setImage(icon);
            }
        } else {
            placeholderIcon.setVisible(false);
            if (iconOverlay == null) {
                iconOverlay = new ImageView(icon);
                iconOverlay.setFitWidth(40);
                iconOverlay.setFitHeight(40);
                iconOverlay.setPreserveRatio(true);
                iconOverlay.setSmooth(true);
                previewPane.getChildren().add(iconOverlay);
            } else {
                iconOverlay.setImage(icon);
            }
        }
    }
}