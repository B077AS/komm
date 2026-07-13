package komm.ui.avatar;

import atlantafx.base.theme.Styles;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;

import komm.ui.customnodes.CustomNotification;
import komm.ui.utils.FileChooserUtil;
import komm.ui.avatar.AvatarColor;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

@Slf4j
public class AvatarPreviewWidget extends VBox {

    // ── Geometry ──────────────────────────────────────────────────────────────
    private static final double MIN_ZOOM = 0.5;
    private static final double MAX_ZOOM = 3.0;
    private static final double ZOOM_SENSITIVITY = 0.002;
    private static final double BORDER_WIDTH = 2.5;

    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    // ── State ─────────────────────────────────────────────────────────────────
    private BufferedImage originalImage;
    @Getter
    private String imageFormat;
    private boolean hasNewUpload = false;

    private double zoom = 1.0;
    private double xOff = 0.0;
    private double yOff = 0.0;
    private double dragStartX, dragStartY, dragStartOffX, dragStartOffY;

    // ── UI nodes ──────────────────────────────────────────────────────────────
    private final ImageView avatarPreview;
    private final StackPane placeholderSlot;
    private final StackPane previewWrapper;
    private final Label hintLabel;
    private final HBox buttonRow;
    private final Button recenterButton;

    private Runnable onUploadCallback;

    private final int previewSize;
    private final boolean uploadAllowed;

    @Builder(builderMethodName = "configure" )
    public static AvatarPreviewWidget create(
            int previewSize,
            byte[] initialBytes,
            String initialFormat,
            String letterFallbackName,
            boolean allowUpload
    ) {
        return new AvatarPreviewWidget(previewSize, initialBytes, initialFormat, letterFallbackName, allowUpload);
    }

    private AvatarPreviewWidget(
            int previewSize,
            byte[] initialBytes,
            String initialFormat,
            String letterFallbackName,
            boolean allowUpload
    ) {
        super(10);
        this.previewSize = previewSize;
        this.uploadAllowed = allowUpload;

        setAlignment(Pos.CENTER);

        // ── Section label ──
        Label sectionLabel = new Label("Server Avatar" );
        sectionLabel.setStyle(
                "-fx-font-size: 11px; -fx-font-weight: bold;" +
                        "-fx-text-fill: -color-fg-muted; -fx-letter-spacing: 0.06em;" );
        sectionLabel.setMaxWidth(Double.MAX_VALUE);
        sectionLabel.setAlignment(Pos.CENTER);

        // ── Preview wrapper ──
        previewWrapper = new StackPane();
        previewWrapper.setPrefSize(previewSize, previewSize);
        previewWrapper.setMinSize(previewSize, previewSize);
        previewWrapper.setMaxSize(previewSize, previewSize);
        previewWrapper.setStyle(
                "-fx-border-color: -color-accent-7;" +
                        "-fx-border-width: " + BORDER_WIDTH + "px;" +
                        "-fx-border-radius: " + (previewSize / 2) + "px;" +
                        "-fx-background-radius: " + (previewSize / 2) + "px;" +
                        "-fx-background-color: -color-bg-default;" );

        Circle clip = new Circle(previewSize / 2.0, previewSize / 2.0, previewSize / 2.0 - 2);

        avatarPreview = new ImageView();
        avatarPreview.setFitWidth(previewSize);
        avatarPreview.setFitHeight(previewSize);
        avatarPreview.setPreserveRatio(false);
        avatarPreview.setClip(clip);
        avatarPreview.setVisible(false);

        placeholderSlot = buildDefaultPlaceholder();
        previewWrapper.getChildren().addAll(placeholderSlot, avatarPreview);

        // ── Hint label ──
        hintLabel = new Label("Scroll to zoom · Drag to reposition" );
        hintLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-subtle;" );
        hintLabel.setVisible(false);
        hintLabel.setManaged(false);

        // ── Button row ──
        buttonRow = new HBox(8);
        buttonRow.setAlignment(Pos.CENTER);

        recenterButton = new Button("Reset", new FontIcon(MaterialDesignC.CROSSHAIRS_GPS));
        recenterButton.setFocusTraversable(false);
        recenterButton.getStyleClass().add(Styles.SMALL);
        recenterButton.setOnAction(e -> resetAdjustments());

        if (uploadAllowed) {
            Button uploadButton = new Button("Upload", new FontIcon(MaterialDesignI.IMAGE_PLUS));
            uploadButton.setFocusTraversable(false);
            uploadButton.getStyleClass().add(Styles.SMALL);
            uploadButton.setOnAction(e -> handleUpload());
            buttonRow.getChildren().add(uploadButton);

            setupMouseInteractions();
        }

        getChildren().addAll(sectionLabel, previewWrapper, hintLabel, buttonRow);

        applyInitialState(initialBytes, initialFormat, letterFallbackName);
    }

    private void applyInitialState(byte[] initialBytes, String initialFormat, String letterFallbackName) {
        boolean imageLoaded = false;

        if (initialBytes != null && initialBytes.length > 0) {
            try {
                originalImage = ImageIO.read(new ByteArrayInputStream(initialBytes));
                if (originalImage != null) {
                    imageFormat = (initialFormat != null) ? initialFormat : "png";
                    imageLoaded = true;
                    hasNewUpload = false;

                    placeholderSlot.setVisible(false);
                    placeholderSlot.setManaged(false);
                    avatarPreview.setVisible(true);
                    resetAdjustments();
                }
            } catch (IOException e) {
                log.warn("AvatarPreviewWidget: could not decode initial image bytes", e);
            }
        }

        if (!imageLoaded && letterFallbackName != null && !letterFallbackName.isEmpty()) {
            StackPane tile = buildLetterTile(letterFallbackName);
            int idx = previewWrapper.getChildren().indexOf(placeholderSlot);
            previewWrapper.getChildren().set(idx, tile);
            previewWrapper.setStyle(
                    "-fx-background-radius: " + (previewSize / 2) + "px;" +
                            "-fx-background-color: transparent;" );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public void setOnUpload(Runnable callback) {
        this.onUploadCallback = callback;
    }

    /**
     * True only when the user has uploaded a new image this session.
     */
    public boolean hasNewUpload() {
        return hasNewUpload;
    }

    /**
     * Returns the cropped, square-scaled image bytes at the current zoom/offset.
     *
     * @throws IllegalStateException if no image has been uploaded this session
     */
    public byte[] getFinalImageBytes() {
        if (originalImage == null) throw new IllegalStateException("No image loaded");
        try {
            BufferedImage cropped = AvatarImageUtils.cropAndScale(originalImage, previewSize, zoom, xOff, yOff);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(cropped, imageFormat != null ? imageFormat : "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode avatar image", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Placeholder / letter tile
    // ─────────────────────────────────────────────────────────────────────────

    private StackPane buildDefaultPlaceholder() {
        FontIcon icon = new FontIcon(MaterialDesignI.IMAGE_OUTLINE);
        Label lbl = new Label("No avatar" );
        lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-subtle;" );
        VBox inner = new VBox(4, icon, lbl);
        inner.setAlignment(Pos.CENTER);
        StackPane sp = new StackPane(inner);
        sp.setAlignment(Pos.CENTER);
        return sp;
    }

    private StackPane buildLetterTile(String name) {
        String letter = String.valueOf(name.charAt(0)).toUpperCase();

        StackPane tile = new StackPane();
        tile.setPrefSize(previewSize, previewSize);
        tile.setMinSize(previewSize, previewSize);
        tile.setMaxSize(previewSize, previewSize);
        tile.setStyle(
                "-fx-background-color: " + AvatarColor.forName(name) + ";" +
                        "-fx-background-radius: " + (previewSize / 2.0) + "px;" +
                        "-fx-border-color: -color-accent-7;" +
                        "-fx-border-width: " + BORDER_WIDTH + "px;" +
                        "-fx-border-radius: " + (previewSize / 2.0) + "px;" );

        Text text = new Text(letter);
        text.setFill(Color.WHITE);
        text.setFont(Font.font("System", FontWeight.BOLD, previewSize / 2.5));
        tile.getChildren().add(text);
        return tile;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mouse interactions
    // ─────────────────────────────────────────────────────────────────────────

    private void setupMouseInteractions() {
        previewWrapper.setOnMousePressed(e -> {
            if (isInteractable()) {
                dragStartX = e.getX();
                dragStartY = e.getY();
                dragStartOffX = xOff;
                dragStartOffY = yOff;
                previewWrapper.setCursor(Cursor.CLOSED_HAND);
                e.consume();
            }
        });

        previewWrapper.setOnMouseDragged(e -> {
            if (isInteractable()) {
                xOff = dragStartOffX - ((e.getX() - dragStartX) / previewSize) * 2;
                yOff = dragStartOffY - ((e.getY() - dragStartY) / previewSize) * 2;
                xOff = Math.max(-2.0, Math.min(2.0, xOff));
                yOff = Math.max(-2.0, Math.min(2.0, yOff));
                updatePreview();
                e.consume();
            }
        });

        previewWrapper.setOnMouseReleased(e -> {
            if (isInteractable()) previewWrapper.setCursor(Cursor.HAND);
        });
        previewWrapper.setOnMouseEntered(e -> {
            if (isInteractable()) previewWrapper.setCursor(Cursor.HAND);
        });
        previewWrapper.setOnMouseExited(e -> previewWrapper.setCursor(Cursor.DEFAULT));

        previewWrapper.setOnScroll(e -> {
            if (isInteractable()) {
                zoom += e.getDeltaY() * ZOOM_SENSITIVITY;
                zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
                updatePreview();
                e.consume();
            }
        });
    }

    private boolean isInteractable() {
        return hasNewUpload && originalImage != null && avatarPreview.isVisible();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Image handling
    // ─────────────────────────────────────────────────────────────────────────

    private void handleUpload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Server Avatar" );
        chooser.getExtensionFilters()
                .add(new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg" ));

        File file = FileChooserUtil.showOpenDialog(chooser, getScene().getWindow());
        if (file == null) return;

        if (file.length() > MAX_FILE_BYTES) {
            new CustomNotification("Upload Failed", "Image must be 5 MB or smaller.", new FontIcon(MaterialDesignC.CLOSE))
                    .showNotification();
            return;
        }

        try {
            imageFormat = file.getName().toLowerCase().endsWith(".png" ) ? "png" : "jpeg";
            originalImage = ImageIO.read(file);

            if (originalImage != null) {
                hasNewUpload = true;
                if (onUploadCallback != null) onUploadCallback.run();

                placeholderSlot.setVisible(false);
                placeholderSlot.setManaged(false);
                avatarPreview.setVisible(true);
                hintLabel.setVisible(true);
                hintLabel.setManaged(true);

                if (!buttonRow.getChildren().contains(recenterButton)) {
                    buttonRow.getChildren().add(recenterButton);
                }

                resetAdjustments();
                updatePreview();
            }
        } catch (IOException e) {
            log.error("AvatarPreviewWidget: failed to load image from {}", file.getAbsolutePath(), e);
        }
    }

    private void resetAdjustments() {
        zoom = 1.0;
        xOff = 0.0;
        yOff = 0.0;
        updatePreview();
    }

    private void updatePreview() {
        if (originalImage == null) return;
        try {
            BufferedImage cropped = AvatarImageUtils.cropAndScale(originalImage, previewSize, zoom, xOff, yOff);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(cropped, "png", baos);
            avatarPreview.setImage(new Image(new ByteArrayInputStream(baos.toByteArray())));
        } catch (IOException e) {
            log.error("AvatarPreviewWidget: failed to render preview", e);
        }
    }
}