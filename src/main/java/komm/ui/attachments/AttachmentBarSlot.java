package komm.ui.attachments;

import atlantafx.base.theme.Styles;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.materialdesign2.*;

import java.io.File;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.util.*;
import java.util.function.Consumer;

/**
 * Animated attachment bar that slides in/out above the input box, below the reply bar.
 * Mirrors ChatSection's reply bar animation approach exactly.
 *
 * <p>Layout inside ChatSection (bottom-up):
 * <pre>
 *   messageInputBox
 *   attachmentBarSlot   ← this component
 *   replyBarSlot
 *   typingRow
 *   chatScrollPane
 * </pre>
 *
 * <p>Supports up to {@value #MAX_ATTACHMENTS} files, 50 MB each.
 */
@Slf4j
public class AttachmentBarSlot extends VBox {

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final int MAX_ATTACHMENTS = 10;
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024; // 50 MB

    private long maxFileSizeBytes = MAX_FILE_BYTES;

    private static final double BAR_HEIGHT = 72.0;
    private static final double ANIM_MS = 180.0;
    private static final double H_PAD = 16.0;

    private static final String STYLE_ROUNDED =
            "-fx-background-color: -color-bg-subtle;" +
                    "-fx-background-radius: 8px 8px 0 0;" +
                    "-fx-border-color: transparent transparent -color-border-muted transparent;" +
                    "-fx-border-width: 0 0 1px 0;";

    private static final String STYLE_FLAT =
            "-fx-background-color: -color-bg-subtle;" +
                    "-fx-background-radius: 0;" +
                    "-fx-border-color: transparent transparent -color-border-muted transparent;" +
                    "-fx-border-width: 0 0 1px 0;";

    // ── State ─────────────────────────────────────────────────────────────────

    @Getter
    private final List<PendingAttachment> attachments = new ArrayList<>();

    private final Consumer<String> onError;
   @Setter
    private Runnable onChanged;

    private Timeline timeline;
    private final Rectangle clip;

    // Inner UI
    private HBox chipRow;
    private ScrollPane chipScroll;

    // ── Inner model ───────────────────────────────────────────────────────────

    public record PendingAttachment(File file, String mimeType) {
        public String fileName() {
            return file.getName();
        }

        public long fileSize() {
            return file.length();
        }
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public AttachmentBarSlot(Consumer<String> onError) {
        this.onError = onError;

        setMinHeight(0);
        setPrefHeight(0);
        setMaxHeight(0);
        setPadding(new Insets(0, H_PAD, 0, H_PAD));

        clip = new Rectangle(0, 0);
        setClip(clip);
        widthProperty().addListener((o, ov, w) -> clip.setWidth(w.doubleValue()));
        prefHeightProperty().addListener((o, ov, h) -> clip.setHeight(h.doubleValue()));

        buildInnerUI();

        // Start hidden
        setVisible(false);
        setManaged(false);
    }

    public void addFile(File file) {
        if (attachments.size() >= MAX_ATTACHMENTS) {
            onError.accept("You can attach at most " + MAX_ATTACHMENTS + " files per send.");
            return;
        }
        if (file.length() > maxFileSizeBytes) {
            onError.accept("\"" + file.getName() + "\" exceeds the " + formatSize(maxFileSizeBytes) + " limit.");
            return;
        }

        String mime = resolveMime(file);
        PendingAttachment att = new PendingAttachment(file, mime);
        attachments.add(att);
        addChip(att);
        if (attachments.size() == 1) expand();
        if (onChanged != null) onChanged.run();
    }

    /**
     * Remove all attachments and collapse.
     */
    public void clear() {
        attachments.clear();
        chipRow.getChildren().clear();
        collapseInstant();
        if (onChanged != null) onChanged.run();
    }

    public boolean isEmpty() {
        return attachments.isEmpty();
    }

    // ── Inner UI ──────────────────────────────────────────────────────────────

    private void buildInnerUI() {
        chipRow = new HBox(8);
        chipRow.setAlignment(Pos.CENTER_LEFT);
        chipRow.setPadding(new Insets(10, 10, 10, 10));

        chipScroll = new ScrollPane(chipRow);
        chipScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        chipScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        chipScroll.setFitToHeight(true);
        chipScroll.setMaxHeight(BAR_HEIGHT);
        chipScroll.setPrefHeight(BAR_HEIGHT);
        chipScroll.setMinHeight(BAR_HEIGHT);
        chipScroll.setStyle(STYLE_ROUNDED);

        // Make the inner viewport transparent
        chipScroll.skinProperty().addListener((o, ov, nv) -> {
            chipScroll.lookup(".viewport").setStyle("-fx-background-color: transparent;");
        });

        getChildren().add(chipScroll);
    }

    private void addChip(PendingAttachment att) {
        HBox chip = buildChip(att);
        chipRow.getChildren().add(chip);
        // Scroll to end so newly added chip is visible
        Platform.runLater(() -> chipScroll.setHvalue(chipScroll.getHmax()));
    }

    private HBox buildChip(PendingAttachment att) {
        HBox chip = new HBox(8);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setPadding(new Insets(4, 12, 4, 12));
        chip.setStyle(
                "-fx-background-color: -color-bg-default;" +
                        "-fx-background-radius: 6px;" +
                        "-fx-border-color: -color-border-muted;" +
                        "-fx-border-radius: 6px;" +
                        "-fx-border-width: 1px;"
        );
        chip.setMaxHeight(Double.MAX_VALUE);
        chip.setMinWidth(160.0);

        // File type icon
        FontIcon icon = new FontIcon(resolveIcon(att.mimeType(), att.fileName()));
        icon.getStyleClass().add("custom-icon-24-emphasis");

        // Name + size
        VBox info = new VBox(1);
        info.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(truncateFileName(att.fileName(), 28));
        nameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        nameLabel.setMinWidth(Region.USE_PREF_SIZE);

        Label sizeLabel = new Label(formatSize(att.fileSize()));
        sizeLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-muted;");

        info.getChildren().addAll(nameLabel, sizeLabel);
        HBox.setHgrow(info, Priority.ALWAYS);

        // Delete button
        Button del = new Button();
        del.setGraphic(new FontIcon(Feather.X));
        del.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        del.setFocusTraversable(false);
        del.setOnAction(e -> {
            removeAttachment(att, chip);
        });

        Region spacer = new Region();
        spacer.setPrefWidth(10);

        chip.getChildren().addAll(icon, info, spacer, del);
        return chip;
    }

    private void removeAttachment(PendingAttachment att, HBox chip) {
        attachments.remove(att);
        chipRow.getChildren().remove(chip);
        if (attachments.isEmpty()) collapse();
        if (onChanged != null) onChanged.run();
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    private void expand() {
        setVisible(true);
        setManaged(true);

        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }

        double fromH = getPrefHeight();
        chipScroll.setOpacity(0);

        timeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(prefHeightProperty(), fromH),
                        new KeyValue(maxHeightProperty(), fromH),
                        new KeyValue(minHeightProperty(), fromH),
                        new KeyValue(chipScroll.opacityProperty(), 0.0)),
                new KeyFrame(Duration.millis(ANIM_MS),
                        new KeyValue(prefHeightProperty(), BAR_HEIGHT, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(maxHeightProperty(), BAR_HEIGHT, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(minHeightProperty(), BAR_HEIGHT, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(chipScroll.opacityProperty(), 1.0, Interpolator.EASE_OUT)));
        timeline.setOnFinished(e -> timeline = null);
        timeline.play();
    }

    private void collapse() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        if (getPrefHeight() <= 0) {
            setVisible(false);
            setManaged(false);
            return;
        }

        double fromH = getPrefHeight();
        timeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(prefHeightProperty(), fromH),
                        new KeyValue(maxHeightProperty(), fromH),
                        new KeyValue(minHeightProperty(), fromH),
                        new KeyValue(chipScroll.opacityProperty(), chipScroll.getOpacity())),
                new KeyFrame(Duration.millis(ANIM_MS),
                        new KeyValue(prefHeightProperty(), 0.0, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(maxHeightProperty(), 0.0, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(minHeightProperty(), 0.0, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(chipScroll.opacityProperty(), 0.0, Interpolator.EASE_IN)));
        timeline.setOnFinished(ev -> {
            setVisible(false);
            setManaged(false);
            timeline = null;
        });
        timeline.play();
    }

    private void collapseInstant() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        chipScroll.setOpacity(1);
        setPrefHeight(0);
        setMaxHeight(0);
        setMinHeight(0);
        setVisible(false);
        setManaged(false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String resolveMime(File file) {
        try {
            String mime = Files.probeContentType(file.toPath());
            return mime != null ? mime : "application/octet-stream";
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    /**
      */
    public static Ikon resolveIcon(String mime, String fileName) {
        if (mime == null) mime = "";
        String ext = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : "";

        if (mime.startsWith("image/")) return MaterialDesignI.IMAGE;
        if (mime.startsWith("video/")) return MaterialDesignV.VIDEO;
        if (mime.startsWith("audio/")) return MaterialDesignM.MUSIC_NOTE;
        if (mime.equals("application/pdf")) return MaterialDesignF.FILE_PDF_BOX;
        if (mime.contains("zip") || mime.contains("compressed")
                || ext.equals("zip") || ext.equals("rar")
                || ext.equals("7z") || ext.equals("tar")) return MaterialDesignZ.ZIP_BOX;
        if (mime.contains("word") || ext.equals("doc")
                || ext.equals("docx")) return MaterialDesignF.FILE_WORD_BOX;
        if (mime.contains("excel") || mime.contains("spreadsheet")
                || ext.equals("xls") || ext.equals("xlsx")) return MaterialDesignF.FILE_EXCEL_BOX;
        if (mime.contains("presentation") || ext.equals("ppt")
                || ext.equals("pptx")) return MaterialDesignF.FILE_POWERPOINT_BOX;
        if (ext.equals("txt") || ext.equals("md")) return MaterialDesignF.FILE_DOCUMENT_OUTLINE;
        if (mime.contains("json") || ext.equals("json")
                || ext.equals("yaml") || ext.equals("yml")) return MaterialDesignC.CODE_BRACES;
        if (mime.equals("text/xml") || mime.contains("xml") || ext.equals("xml")) return MaterialDesignF.FILE_XML_BOX;
        if (ext.equals("java") || ext.equals("kt") || ext.equals("py")
                || ext.equals("js") || ext.equals("ts")
                || ext.equals("cpp") || ext.equals("c")) return MaterialDesignC.CODE_TAGS;

        return MaterialDesignF.FILE_OUTLINE;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return new DecimalFormat("0.#").format(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024 * 1024) return new DecimalFormat("0.#").format(bytes / (1024.0 * 1024)) + " MB";
        return new DecimalFormat("0.#").format(bytes / (1024.0 * 1024 * 1024)) + " GB";
    }

    private static String truncateFileName(String name, int max) {
        if (name.length() <= max) return name;
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot) : "";
        int keep = max - 3 - ext.length();
        if (keep <= 0) return name.substring(0, max - 3) + "...";
        return name.substring(0, keep) + "..." + ext;
    }

    public void setMaxFileBytes(long bytes) {
        this.maxFileSizeBytes = bytes;
    }

    public void setTopCornersRounded(boolean rounded) {
        chipScroll.setStyle(rounded ? STYLE_ROUNDED : STYLE_FLAT);
    }
}