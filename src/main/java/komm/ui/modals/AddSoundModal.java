package komm.ui.modals;

import atlantafx.base.theme.Styles;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.summary.SoundboardSummary;
import komm.service.PersonalSoundboardEntry;
import komm.service.PersonalSoundboardStore;
import komm.service.SoundboardCache;
import komm.service.SoundboardService;
import komm.ui.customnodes.CustomNotification;
import komm.ui.emojis.EmojiFilterTextField;
import komm.ui.emojis.EmojiPickerPopup;
import komm.ui.utils.FileChooserUtil;
import komm.ui.utils.IconColorUtil;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * Modal for adding or editing a single soundboard sound (server-wide or
 * personal). In edit mode the name, emoji and file are pre-filled and the audio
 * file is only replaced if a new one is picked. Lives in
 * the app's {@code ModalPane} — unlike the {@code SoundboardPopup} (a JavaFX
 * {@code Popup}, which cannot hold OS keyboard focus on Windows), so its text
 * field is properly editable. Persistence completes before the modal closes, so
 * whoever reopens the soundboard grid sees the new sound immediately.
 */
public class AddSoundModal extends VBox {

    private static final int NAME_MAX = 50;
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final double WIDTH = 460;

    private final boolean server;
    private final int slot;
    /** Non-null when editing an existing server sound instead of adding one. */
    private final SoundboardSummary editServerTarget;
    /** Non-null when editing an existing personal sound instead of adding one. */
    private final PersonalSoundboardEntry editPersonalTarget;
    private final boolean editMode;

    private EmojiFilterTextField nameField;
    private final EmojiPickerPopup emojiPicker = new EmojiPickerPopup();
    private String selectedEmoji;
    private StackPane emojiTile;
    private Label clearEmojiLabel;

    private Label nameCounter;
    private StackPane fileSlot;
    private File chosen;

    private ProgressIndicator spinner;
    private Button cancelButton;
    private Button saveButton;
    private Button closeButton;

    public AddSoundModal(boolean server, int slot) {
        this(server, slot, null, null);
    }

    /** Opens the modal in edit mode for an existing server sound. */
    public static AddSoundModal editServer(SoundboardSummary target) {
        return new AddSoundModal(true, target.getSlotIndex(), target, null);
    }

    /** Opens the modal in edit mode for an existing personal sound. */
    public static AddSoundModal editPersonal(PersonalSoundboardEntry target) {
        return new AddSoundModal(false, target.getSlotIndex(), null, target);
    }

    private AddSoundModal(boolean server, int slot,
                          SoundboardSummary editServerTarget, PersonalSoundboardEntry editPersonalTarget) {
        this.server = server;
        this.slot = slot;
        this.editServerTarget = editServerTarget;
        this.editPersonalTarget = editPersonalTarget;
        this.editMode = editServerTarget != null || editPersonalTarget != null;
        if (editMode) {
            this.selectedEmoji = server ? editServerTarget.getEmoji() : editPersonalTarget.getEmoji();
        }

        getStyleClass().add("custom-modal");
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);
        setMaxHeight(Region.USE_PREF_SIZE);

        getChildren().addAll(buildHeader(), buildBody(), buildFooter());
        if (editMode) {
            nameField.setText(server ? editServerTarget.getName() : editPersonalTarget.getName());
            showExistingFileCard();
        } else {
            showDropZone();
        }
        updateSaveEnabled();
    }

    // ── Header ──────────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        FontIcon icon = new FontIcon(MaterialDesignM.MUSIC_NOTE);
        IconColorUtil.apply(icon, "-color-accent-fg", 20);
        StackPane iconWrap = new StackPane(icon);
        iconWrap.setMinSize(40, 40);
        iconWrap.setMaxSize(40, 40);
        iconWrap.setStyle("-fx-background-color: -color-accent-subtle; -fx-background-radius: 10;");

        Label title = new Label(editMode
                ? (server ? "Edit server sound" : "Edit personal sound")
                : (server ? "Add server sound" : "Add personal sound"));
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label subtitle = new Label((server
                ? "Shared with everyone on this server"
                : "Saved on this device") + " · slot " + (slot + 1));
        subtitle.setStyle("-fx-font-size: 11.5px; -fx-text-fill: -color-fg-muted;");
        VBox titleBox = new VBox(1, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        closeButton = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeButton.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeButton.setFocusTraversable(false);
        closeButton.setOnAction(e -> App.closeModal());

        HBox header = new HBox(14, iconWrap, titleBox, spacer, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 16, 16, 20));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent;"
                + " -fx-border-width: 0 0 1 0;");
        return header;
    }

    // ── Body ────────────────────────────────────────────────────────────────────

    private VBox buildBody() {
        // ── Name ──
        nameField = new EmojiFilterTextField();
        nameField.setPromptText("e.g. Airhorn, Drumroll…");
        nameField.setMaxWidth(Double.MAX_VALUE);

        nameCounter = new Label("0/" + NAME_MAX);
        nameCounter.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        nameField.textProperty().addListener((o, a, b) -> {
            if (b.length() > NAME_MAX) { nameField.setText(b.substring(0, NAME_MAX)); return; }
            int len = b.length();
            nameCounter.setText(len + "/" + NAME_MAX);
            nameCounter.setStyle("-fx-font-size: 11px; -fx-text-fill: "
                    + (len >= NAME_MAX ? "-color-danger-fg" : "-color-fg-subtle") + ";");
        });

        Region nameSpacer = new Region();
        HBox.setHgrow(nameSpacer, Priority.ALWAYS);
        HBox nameLabelRow = new HBox(sectionLabel("NAME"), nameSpacer, nameCounter);
        nameLabelRow.setAlignment(Pos.CENTER_LEFT);

        VBox nameSection = new VBox(7, nameLabelRow, nameField);

        // ── Emoji ──
        emojiTile = new StackPane();
        emojiTile.setMinSize(38, 38);
        emojiTile.setMaxSize(38, 38);
        emojiTile.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: -color-border-default;" +
                "-fx-border-radius: 8;" +
                "-fx-border-width: 1;" +
                "-fx-cursor: hand;");
        Tooltip.install(emojiTile, new Tooltip("Pick an emoji icon for this sound"));
        emojiTile.setOnMouseClicked(e -> toggleEmojiPicker());

        clearEmojiLabel = new Label("Remove");
        clearEmojiLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted; -fx-cursor: hand;");
        clearEmojiLabel.setVisible(false);
        clearEmojiLabel.setManaged(false);
        clearEmojiLabel.setOnMouseClicked(e -> {
            selectedEmoji = null;
            refreshEmojiDisplay();
        });

        emojiPicker.setOnEmojiSelected(emoji -> {
            selectedEmoji = emoji.character();
            emojiPicker.hide();
            refreshEmojiDisplay();
        });

        refreshEmojiDisplay();

        HBox emojiRow = new HBox(10, emojiTile, clearEmojiLabel);
        emojiRow.setAlignment(Pos.CENTER_LEFT);

        Label emojiHint = new Label("Optional — shown as an icon before the sound name in the slot");
        emojiHint.setStyle("-fx-font-size: 10.5px; -fx-text-fill: -color-fg-subtle;");

        VBox emojiSection = new VBox(7, sectionLabel("EMOJI (OPTIONAL)"), emojiRow, emojiHint);

        // ── File ──
        fileSlot = new StackPane();
        fileSlot.setAlignment(Pos.CENTER);

        VBox body = new VBox(18,
                nameSection,
                emojiSection,
                new VBox(7, sectionLabel("SOUND FILE"), fileSlot));
        body.setPadding(new Insets(20, 20, 18, 20));
        return body;
    }

    private void toggleEmojiPicker() {
        if (emojiPicker.isShowing()) {
            emojiPicker.hide();
        } else {
            javafx.geometry.Bounds b = emojiTile.localToScreen(emojiTile.getBoundsInLocal());
            if (b != null) {
                double x = b.getMinX();
                double y = b.getMinY() - 444;
                if (y < 0) y = b.getMaxY() + 4;
                emojiPicker.show(emojiTile.getScene().getWindow(), x, y);
            }
        }
    }

    private void refreshEmojiDisplay() {
        emojiTile.getChildren().clear();
        if (selectedEmoji == null || selectedEmoji.isBlank()) {
            FontIcon placeholder = new FontIcon(MaterialDesignE.EMOTICON_OUTLINE);
            IconColorUtil.apply(placeholder, "-color-fg-subtle", 20);
            emojiTile.getChildren().add(placeholder);
            clearEmojiLabel.setVisible(false);
            clearEmojiLabel.setManaged(false);
            Tooltip.install(emojiTile, new Tooltip("Pick an emoji icon for this sound"));
        } else {
            List<Node> nodes = TextUtils.convertToTextAndImageNodes(selectedEmoji, 22);
            HBox center = new HBox();
            center.setAlignment(Pos.CENTER);
            for (Node n : nodes) {
                if (n instanceof javafx.scene.image.ImageView iv) {
                    iv.setFitWidth(22);
                    iv.setFitHeight(22);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                } else if (n instanceof javafx.scene.text.Text t) {
                    t.setStyle("-fx-font-size: 22px;");
                }
                center.getChildren().add(n);
            }
            emojiTile.getChildren().add(center);
            clearEmojiLabel.setVisible(true);
            clearEmojiLabel.setManaged(true);
            Tooltip.install(emojiTile, new Tooltip("Change emoji"));
        }
    }

    /** Empty state: a dashed, clickable drop-zone style card prompting a file pick. */
    private void showDropZone() {
        FontIcon up = new FontIcon(MaterialDesignF.FILE_UPLOAD);
        IconColorUtil.apply(up, "-color-fg-muted", 26);

        Label main = new Label("Choose an audio file");
        main.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        Label sub = new Label("MP3 or WAV · up to 20 MB");
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        VBox content = new VBox(6, up, main, sub);
        content.setAlignment(Pos.CENTER);

        StackPane zone = new StackPane(content);
        zone.setMinHeight(126);
        zone.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 10;"
                + " -fx-border-color: -color-border-muted; -fx-border-radius: 10;"
                + " -fx-border-width: 1.5; -fx-border-style: segments(6, 5) line-cap round;"
                + " -fx-cursor: hand;");
        zone.setOnMouseClicked(e -> browse());
        fileSlot.getChildren().setAll(zone);
    }

    /** Filled state: a card showing the chosen file with format + size chips. */
    private void showFileCard() {
        renderFileCard(chosen.getName(), chosen.length(), false);
    }

    /** Edit mode's initial state: the sound's current file, kept unless changed. */
    private void showExistingFileCard() {
        renderFileCard(server ? editServerTarget.getFileName() : editPersonalTarget.getFileName(),
                server ? editServerTarget.getFileSize() : editPersonalTarget.getFileSize(),
                true);
    }

    private void renderFileCard(String fn, long size, boolean current) {
        String ext = fn != null && fn.contains(".") ? fn.substring(fn.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) : "FILE";
        boolean tooBig = !current && server && size > MAX_BYTES;

        FontIcon fileIcon = new FontIcon(MaterialDesignF.FILE_MUSIC);
        IconColorUtil.apply(fileIcon, "-color-accent-fg", 22);
        StackPane iconWrap = new StackPane(fileIcon);
        iconWrap.setMinSize(44, 44);
        iconWrap.setMaxSize(44, 44);
        iconWrap.setStyle("-fx-background-color: -color-accent-subtle; -fx-background-radius: 10;");

        Label name = new Label(fn);
        name.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        name.setMaxWidth(230);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);

        HBox chips = new HBox(6, chip(ext, false), chip(humanSize(size), false));
        if (current) chips.getChildren().add(chip("Current file", false));
        if (tooBig) chips.getChildren().add(chip("Exceeds 20 MB", true));
        chips.setAlignment(Pos.CENTER_LEFT);

        VBox info = new VBox(6, name, chips);
        info.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(info, Priority.ALWAYS);

        Button change = new Button("Change");
        change.getStyleClass().addAll(Styles.FLAT, Styles.SMALL);
        change.setFocusTraversable(false);
        change.setOnAction(e -> browse());

        HBox card = new HBox(12, iconWrap, info, change);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(12));
        card.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 10;"
                + " -fx-border-color: " + (tooBig ? "-color-danger-emphasis" : "-color-border-default") + ";"
                + " -fx-border-radius: 10; -fx-border-width: 1;");
        fileSlot.getChildren().setAll(card);
    }

    private Label chip(String text, boolean danger) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 10.5px; -fx-padding: 2 9 2 9; -fx-background-radius: 20;"
                + " -fx-background-color: " + (danger ? "-color-danger-subtle" : "-color-neutral-muted") + ";"
                + " -fx-text-fill: " + (danger ? "-color-danger-fg" : "-color-fg-default") + ";");
        return l;
    }

    // ── Footer ──────────────────────────────────────────────────────────────────

    private HBox buildFooter() {
        spinner = new ProgressIndicator();
        spinner.setMaxSize(16, 16);
        spinner.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().add(Styles.SMALL);
        cancelButton.setFocusTraversable(false);
        cancelButton.setOnAction(e -> App.closeModal());

        saveButton = new Button(editMode ? "Save changes" : "Add sound");
        saveButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        saveButton.setFocusTraversable(false);
        saveButton.setOnAction(e -> save());

        HBox footer = new HBox(10, spacer, spinner, cancelButton, saveButton);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(14, 20, 16, 20));
        footer.setStyle("-fx-border-color: -color-border-default transparent transparent transparent;"
                + " -fx-border-width: 1 0 0 0;");
        return footer;
    }

    // ── Behaviour ─────────────────────────────────────────────────────────────────

    private void browse() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose a sound (MP3 or WAV)");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio (MP3, WAV)", "*.mp3", "*.wav"));
        File f = FileChooserUtil.showOpenDialog(fc, getScene() != null ? getScene().getWindow() : null);
        if (f == null) return;
        chosen = f;
        if (nameField.getText().isBlank()) {
            String n = f.getName();
            String base = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
            nameField.setText(base.length() > NAME_MAX ? base.substring(0, NAME_MAX) : base);
        }
        showFileCard();
        updateSaveEnabled();
    }

    private void updateSaveEnabled() {
        // In edit mode no new file is required — the current one is kept.
        boolean ok = (editMode || chosen != null) && !(server && chosen != null && chosen.length() > MAX_BYTES);
        saveButton.setDisable(!ok);
    }

    private void save() {
        if (chosen == null && !editMode) return;
        final File file = chosen;
        final String name = nameField.getText().trim();
        final String emoji = selectedEmoji;
        setBusy(true);
        App.getServices().getExecutor().submit(() -> {
            try {
                byte[] bytes = file != null ? Files.readAllBytes(file.toPath()) : null;
                if (server) {
                    if (bytes != null && bytes.length > MAX_BYTES) { fail("File exceeds the 20 MB limit"); return; }
                    SoundboardService svc;
                    try { svc = App.getServices().installation().getSoundboardService(); }
                    catch (Exception ex) { fail("Not connected to a server"); return; }
                    if (editMode) {
                        svc.edit(editServerTarget.getSoundboardId(), name, emoji,
                                file != null ? file.getName() : null,
                                file != null ? mimeFor(file.getName()) : null, bytes);
                    } else {
                        svc.upload(slot, name, emoji, file.getName(), mimeFor(file.getName()), bytes);
                    }
                    List<SoundboardSummary> list = svc.list();
                    Platform.runLater(() -> {
                        SoundboardCache.setServer(list);
                        finishSuccess();
                    });
                } else {
                    if (editMode) {
                        PersonalSoundboardStore.getInstance().update(editPersonalTarget.getId(),
                                name, emoji,
                                file != null ? file.getName() : null,
                                file != null ? mimeFor(file.getName()) : null, bytes);
                    } else {
                        PersonalSoundboardStore.getInstance().add(slot,
                                name.isBlank() ? file.getName() : name,
                                emoji, file.getName(), mimeFor(file.getName()), bytes);
                    }
                    Platform.runLater(this::finishSuccess);
                }
            } catch (Exception ex) {
                fail(server
                        ? (editMode ? "Edit failed: " : "Upload failed: ") + HttpStatusException.extractMessage(ex)
                        : "Save failed: " + ex.getMessage());
            }
        });
    }

    private void finishSuccess() {
        App.getModalPane().setPersistent(false);
        App.closeModal();
    }

    private void setBusy(boolean busy) {
        spinner.setVisible(busy);
        saveButton.setDisable(busy || chosen == null);
        cancelButton.setDisable(busy);
        closeButton.setDisable(busy);
        App.getModalPane().setPersistent(busy);
    }

    private void fail(String message) {
        Platform.runLater(() -> {
            new CustomNotification("Upload Error", message, new FontIcon(MaterialDesignC.CLOSE_CIRCLE_OUTLINE)).showNotification();
            setBusy(false);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private Label sectionLabel(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        return l;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f KB", kb);
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    private static String mimeFor(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }
}
