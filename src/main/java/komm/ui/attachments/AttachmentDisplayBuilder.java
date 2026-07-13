package komm.ui.attachments;

import atlantafx.base.controls.RingProgressIndicator;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import komm.App;
import komm.websocket.messages.payloads.MessageReceivedPayload;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import atlantafx.base.theme.Styles;

import java.io.ByteArrayInputStream;
import java.util.Base64;

@Slf4j
public class AttachmentDisplayBuilder {

    private AttachmentDisplayBuilder() {
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Builds the inline display node for a message that carries an attachment.
     *
     * @return a {@link Node} ready to be inserted into the message's content
     * column, or {@code null} if the message has no recognisable attachment.
     */
    public static Node buildAttachmentDisplay(MessageReceivedPayload msg) {
        if (msg.getFileType() == null) return null;

        String mime = msg.getFileType();
        String fileName = msg.getFileName() != null ? msg.getFileName() : "attachment";

        if (mime.startsWith("image/") && msg.getFile64() != null && !msg.getFile64().isBlank()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(msg.getFile64());
                Image img = new Image(new ByteArrayInputStream(bytes),
                        320, 240, true, true);
                ImageView iv = new ImageView(img);
                iv.setPreserveRatio(true);
                iv.setFitWidth(320);
                iv.setFitHeight(240);
                iv.setStyle("-fx-cursor: hand;");
                iv.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY) {
                        Image fullImage = new Image(new ByteArrayInputStream(bytes));
                        ImageView modalImage = new ImageView(fullImage);
                        modalImage.setPreserveRatio(true);
                        modalImage.setFitWidth(800);
                        modalImage.setFitHeight(600);

                        javafx.scene.control.MenuItem miDownload = new javafx.scene.control.MenuItem("Download image…");
                        miDownload.setOnAction(ev -> saveBytes(bytes, fileName, modalImage));
                        javafx.scene.control.ContextMenu modalMenu = new javafx.scene.control.ContextMenu(miDownload);

                        modalImage.setOnContextMenuRequested(ev ->
                                modalMenu.show(modalImage, ev.getScreenX(), ev.getScreenY()));
                        modalImage.setOnMousePressed(ev -> {
                            if (ev.isPrimaryButtonDown() && modalMenu.isShowing()) modalMenu.hide();
                        });

                        App.getModalPane().show(modalImage);
                        modalImage.requestFocus();
                        e.consume();
                    }
                });

                VBox wrapper = new VBox(iv);
                wrapper.setAlignment(Pos.CENTER_LEFT);
                VBox.setMargin(wrapper, new Insets(4, 0, 0, 0));
                return wrapper;
            } catch (Exception e) {
                log.warn("Could not decode image attachment", e);
                // fall through to generic chip
            }
        }

        return buildFileChip(msg);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * A compact chip showing icon + name + size for non-image attachments.
     */
    private static HBox buildFileChip(MessageReceivedPayload msg) {
        String mime = msg.getFileType() != null ? msg.getFileType() : "application/octet-stream";
        String fileName = msg.getFileName() != null ? msg.getFileName() : "attachment";
        long fileSize = msg.getFileSize();

        HBox chip = new HBox(10);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setPadding(new Insets(8, 12, 8, 12));
        chip.setMaxWidth(320);
        chip.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                        "-fx-background-radius: 8px;" +
                        "-fx-border-color: -color-border-muted;" +
                        "-fx-border-radius: 8px;" +
                        "-fx-border-width: 1px;"
        );
        VBox.setMargin(chip, new Insets(4, 0, 0, 0));

        FontIcon icon = new FontIcon(AttachmentBarSlot.resolveIcon(mime, fileName));
        icon.getStyleClass().add("custom-icon-24-emphasis");

        VBox info = new VBox(2);
        Label nameLbl = new Label(fileName);
        nameLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        nameLbl.setWrapText(false);

        HBox meta = new HBox(6);
        Label typeLbl = new Label(shortMime(mime));
        typeLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-muted;");
        Label sizeLbl = new Label(formatSize(fileSize));
        sizeLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-muted;");
        Label dot = new Label("·");
        dot.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-muted;");
        meta.getChildren().addAll(typeLbl, dot, sizeLbl);

        info.getChildren().addAll(nameLbl, meta);
        HBox.setHgrow(info, Priority.ALWAYS);

        // Download button — handler to be wired once the endpoint is ready
        Button dlBtn = new Button(null, new FontIcon(Feather.DOWNLOAD));
        dlBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        dlBtn.setFocusTraversable(false);
        dlBtn.setOnAction(e -> handleDownload(msg, dlBtn, chip));

        chip.getChildren().addAll(icon, info, dlBtn);
        return chip;
    }

    private static void handleDownload(MessageReceivedPayload msg, Button dlBtn, HBox chip) {
        RingProgressIndicator ring = new RingProgressIndicator(0, false);
        ring.setMinSize(30, 30);
        ring.setMaxSize(30, 30);
        ring.setStringConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Double progress) {
                return "";
            }

            @Override
            public Double fromString(String s) {
                return 0d;
            }
        });

        int dlBtnIndex = chip.getChildren().indexOf(dlBtn);
        chip.getChildren().set(dlBtnIndex, ring);

        Service<byte[]> svc = new Service<>() {
            @Override
            protected Task<byte[]> createTask() {
                return new Task<>() {
                    @Override
                    protected byte[] call() throws Exception {
                        return msg.getServerId() == null
                                ? App.getServices().hub().getDirectMessageService()
                                        .downloadAttachment(msg.getMessageId(),
                                                progress -> Platform.runLater(() -> ring.setProgress(progress)))
                                : App.getServices().installation().getMessageService()
                                        .downloadAttachmentWithProgress(msg.getMessageId(),
                                                progress -> Platform.runLater(() -> ring.setProgress(progress)));
                    }
                };
            }
        };
        svc.setOnSucceeded(e -> {
            byte[] bytes = svc.getValue();
            chip.getChildren().set(dlBtnIndex, dlBtn);

            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setInitialFileName(msg.getFileName());
            java.io.File dest = komm.ui.utils.FileChooserUtil.showSaveDialog(chooser, chip.getScene().getWindow());
            if (dest != null) {
                Thread.ofVirtual().start(() -> {
                    try {
                        java.nio.file.Files.write(dest.toPath(), bytes);
                    } catch (Exception ex) {
                        log.error("Failed to save attachment", ex);
                    }
                });
            }
        });
        svc.setOnFailed(e -> {
            log.error("Failed to download attachment", svc.getException());
            chip.getChildren().set(dlBtnIndex, dlBtn);
        });
        svc.start();
    }

    static String shortMime(String mime) {
        if (mime == null) return "FILE";
        return switch (mime) {
            case "application/pdf" -> "PDF";
            case "application/zip", "application/x-zip-compressed", "application/x-zip" -> "ZIP";
            case "application/x-rar-compressed", "application/vnd.rar" -> "RAR";
            case "application/x-7z-compressed" -> "7Z";
            case "application/x-tar" -> "TAR";
            case "text/plain" -> "TXT";
            case "text/html" -> "HTML";
            case "text/csv" -> "CSV";
            case "text/xml", "application/xml" -> "XML";
            case "application/json" -> "JSON";
            case "application/msword" -> "DOC";
            case "application/vnd.ms-excel" -> "XLS";
            case "application/vnd.ms-powerpoint" -> "PPT";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "XLSX";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "PPTX";
            case "application/vnd.oasis.opendocument.text" -> "ODT";
            case "application/vnd.oasis.opendocument.spreadsheet" -> "ODS";
            default -> {
                int slash = mime.indexOf('/');
                yield slash >= 0
                        ? mime.substring(slash + 1).toUpperCase()
                        : mime.toUpperCase();
            }
        };
    }

    static String formatSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static void saveBytes(byte[] bytes, String fileName, javafx.scene.Node ownerNode) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Save image");
        chooser.setInitialFileName(fileName);
        java.io.File dest = komm.ui.utils.FileChooserUtil.showSaveDialog(chooser, ownerNode.getScene().getWindow());
        if (dest == null) return;

        Thread.ofVirtual().start(() -> {
            try {
                java.nio.file.Files.write(dest.toPath(), bytes);
                log.info("Image saved to {}", dest.getAbsolutePath());
            } catch (Exception ex) {
                log.error("Failed to save image", ex);
            }
        });
    }
}