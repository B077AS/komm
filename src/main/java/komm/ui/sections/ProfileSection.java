package komm.ui.sections;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import komm.App;
import komm.AppState;
import komm.model.dto.request.UserUpdateRequest;
import komm.model.dto.summary.MainUserSummary;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import komm.ui.avatar.AvatarColor;
import komm.ui.customnodes.StatusComboBox;
import komm.ui.customnodes.StatusMessageEditor;
import komm.ui.modals.EditUserModal;
import komm.utils.AudioDeviceService;

import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.io.ByteArrayInputStream;
import java.util.Objects;

@Slf4j
public class ProfileSection extends VBox {

    private static final int AVATAR_SIZE = 96;
    private static final int BORDER_PAD = 3;
    private static final int WRAPPER_SIZE = AVATAR_SIZE + BORDER_PAD * 2;

    private Label usernameLabel;
    private StatusMessageEditor statusEditor;
    private boolean suppressStatusAutoSave = false;
    private String lastSavedStatusText = "";
    private String lastSavedStatusEmoji = null;
    private ComboBox<String> microphonesCombobox;
    private ComboBox<String> speakersCombobox;
    private Button micToggleButton;
    private Button speakerToggleButton;
    private AudioDeviceService audioService;

    // Held so refresh() can swap the avatar inner content
    private StackPane avatarInnerPane;
    private StackPane avatarRing;

    public ProfileSection() {
        audioService = new AudioDeviceService(App.getWebrtcRoomClient());

        this.setFillWidth(true);
        this.setStyle(
                "-fx-background-color: -color-bg-default; " +
                        "-fx-border-color: transparent transparent transparent -color-border-muted; " +
                        "-fx-border-width: 0 0 0 1px;"
        );

        VBox mainBox = new VBox(16);
        mainBox.setPadding(new Insets(20));
        mainBox.setFillWidth(true);

        Label header = new Label("Profile");
        header.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        mainBox.getChildren().addAll(header, createProfileCard(), createAudioControls());
        this.getChildren().add(mainBox);

        AppState.micEnabledProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(this::refreshMicButton));
        AppState.speakerEnabledProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(this::refreshSpeakerButton));
        AppState.serverMicEnabledProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(this::refreshMicButton));
        AppState.serverSpeakerEnabledProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(this::refreshSpeakerButton));

        this.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                syncInitialAudioState();
                audioService.bind(microphonesCombobox, speakersCombobox);
            }
            if (newScene == null) {
                dispose();
            }
        });
    }

    // ─── Public refresh — called after a confirmed save in EditUserModal ──────

    public void refreshDeviceSelections() {
        var us = komm.utils.UserSettings.getInstance();
        audioService.updateSelection(us.getInputDevice(), us.getOutputDevice());
    }

    public void refresh() {
        MainUserSummary user = App.getUser();
        if (user == null) return;

        usernameLabel.setText(user.getUsername() != null ? user.getUsername() : "Unknown");

        String msg = user.getStatusMessage() != null ? user.getStatusMessage() : "";
        String emoji = user.getStatusEmoji();
        suppressStatusAutoSave = true;
        try {
            statusEditor.setStatusText(msg);
            statusEditor.setStatusEmojiUnified(emoji);
        } finally {
            suppressStatusAutoSave = false;
        }
        lastSavedStatusText = msg;
        lastSavedStatusEmoji = emoji;

        if (avatarInnerPane != null) {
            avatarInnerPane.getChildren().setAll(buildAvatarInner(user));
        }
    }

    // ─── Audio sync ───────────────────────────────────────────────────────────

    private void syncInitialAudioState() {
        boolean mic = AppState.micEnabledProperty().get();
        boolean spk = AppState.speakerEnabledProperty().get();
        audioService.setMicrophoneMuted(!mic);
        audioService.setSpeakerMuted(!spk);
        refreshMicButton();
        refreshSpeakerButton();
    }

    private void refreshMicButton() {
        if (micToggleButton == null) return;
        boolean serverMicEnabled = AppState.serverMicEnabledProperty().get();
        boolean micEnabled = AppState.micEnabledProperty().get();
        if (!serverMicEnabled) {
            applyServerLockedButtonState(micToggleButton, MaterialDesignM.MICROPHONE_OFF);
        } else {
            micToggleButton.setDisable(false);
            updateToggleButtonState(micToggleButton, micEnabled,
                    MaterialDesignM.MICROPHONE, MaterialDesignM.MICROPHONE_OFF);
        }
    }

    private void refreshSpeakerButton() {
        if (speakerToggleButton == null) return;
        boolean serverSpeakerEnabled = AppState.serverSpeakerEnabledProperty().get();
        boolean speakerEnabled = AppState.speakerEnabledProperty().get();
        if (!serverSpeakerEnabled) {
            applyServerLockedButtonState(speakerToggleButton, MaterialDesignH.HEADPHONES_OFF);
        } else {
            speakerToggleButton.setDisable(false);
            updateToggleButtonState(speakerToggleButton, speakerEnabled,
                    MaterialDesignH.HEADPHONES, MaterialDesignH.HEADPHONES_OFF);
        }
    }

    private void applyServerLockedButtonState(Button button, Ikon offIcon) {
        FontIcon icon = new FontIcon(offIcon);
        icon.getStyleClass().add("custom-icon-20-muted");
        button.setGraphic(icon);
        button.getStyleClass().remove("custom-toolbar-button");
        if (!button.getStyleClass().contains("custom-toolbar-button-muted"))
            button.getStyleClass().add("custom-toolbar-button-muted");
        button.setDisable(true);
    }

    // ─── Profile card ─────────────────────────────────────────────────────────

    private VBox createProfileCard() {
        MainUserSummary user = App.getUser();

        UserStatus currentStatus = (user != null && user.getStatus() != null)
                ? user.getStatus() : UserStatus.ONLINE;
        String username = (user != null && user.getUsername() != null) ? user.getUsername() : "Unknown";
        String statusMsg = (user != null && user.getStatusMessage() != null) ? user.getStatusMessage() : "";
        String statusEmoji = user != null ? user.getStatusEmoji() : null;

        VBox card = new VBox(14);
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setPadding(new Insets(22, 16, 18, 16));
        card.setStyle(
                "-fx-background-color: -color-bg-subtle; " +
                        "-fx-background-radius: 10px; " +
                        "-fx-border-color: -color-border-default; " +
                        "-fx-border-radius: 10px;"
        );

        StackPane avatar = buildAvatar(user);
        usernameLabel = new Label(username);
        usernameLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Node statusMsgWidget = buildStatusMessageWidget(statusMsg, statusEmoji);
        Node statusComboWidget = new StatusComboBox(currentStatus);
        Button editBtn = buildEditProfileButton();

        card.getChildren().addAll(avatar, usernameLabel, statusMsgWidget, statusComboWidget, editBtn);
        return card;
    }

    // ─── Avatar ───────────────────────────────────────────────────────────────

    private StackPane buildAvatar(MainUserSummary user) {
        avatarRing = new StackPane();
        avatarRing.setPrefSize(WRAPPER_SIZE, WRAPPER_SIZE);
        avatarRing.setMinSize(WRAPPER_SIZE, WRAPPER_SIZE);
        avatarRing.setMaxSize(WRAPPER_SIZE, WRAPPER_SIZE);
        avatarRing.setStyle(
                "-fx-border-color: -color-accent-7;" +
                        "-fx-border-width: 3px;" +
                        "-fx-border-radius: " + (WRAPPER_SIZE / 2.0) + "px;" +
                        "-fx-background-color: transparent;" +
                        "-fx-background-radius: " + (WRAPPER_SIZE / 2.0) + "px;"
        );

        avatarInnerPane = new StackPane();
        avatarInnerPane.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        avatarInnerPane.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        avatarInnerPane.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
        avatarInnerPane.getChildren().add(buildAvatarInner(user));

        avatarRing.getChildren().add(avatarInnerPane);

        StackPane wrapper = new StackPane(avatarRing);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setMaxSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);
        return wrapper;
    }

    /** Returns either a clipped ImageView or a letter-tile for the given user. */
    private Node buildAvatarInner(MainUserSummary user) {
        boolean hasAvatar = user != null && user.getAvatar() != null && user.getAvatar().length > 0;
        if (hasAvatar) {
            ImageView iv = new ImageView();
            iv.setFitWidth(AVATAR_SIZE);
            iv.setFitHeight(AVATAR_SIZE);
            iv.setPreserveRatio(false);
            iv.setClip(new Circle(AVATAR_SIZE / 2.0, AVATAR_SIZE / 2.0, AVATAR_SIZE / 2.0));
            try {
                iv.setImage(new Image(new ByteArrayInputStream(user.getAvatar())));
                return iv;
            } catch (Exception ignored) {}
        }
        return buildLetterTile(user);
    }

    private StackPane buildLetterTile(MainUserSummary user) {
        String uname = (user != null && user.getUsername() != null && !user.getUsername().isEmpty())
                ? user.getUsername() : "?";
        String letter = String.valueOf(uname.charAt(0)).toUpperCase();

        StackPane tile = new StackPane();
        tile.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        tile.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        tile.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
        tile.setStyle(
                "-fx-background-color: " + AvatarColor.forName(uname) + "; " +
                        "-fx-background-radius: " + (AVATAR_SIZE / 2.0) + "px;"
        );

        Text text = new Text(letter);
        text.setFill(Color.WHITE);
        text.setFont(Font.font("System", FontWeight.BOLD, AVATAR_SIZE / 2.5));

        tile.getChildren().add(text);
        return tile;
    }

    // ─── Status message widget ────────────────────────────────────────────────

    private Node buildStatusMessageWidget(String initialText, String initialEmoji) {
        statusEditor = new StatusMessageEditor();
        statusEditor.setStatusText(initialText);
        statusEditor.setStatusEmojiUnified(initialEmoji);
        lastSavedStatusText = initialText != null ? initialText : "";
        lastSavedStatusEmoji = initialEmoji;

        // Commit on blur (matches the old field's Enter/click-away behaviour) and
        // immediately whenever the emoji changes, since picking one is a single
        // deliberate action rather than something you'd want to "undo" by tabbing away.
        statusEditor.textFieldFocusedProperty().addListener((obs, was, is) -> {
            if (!is) commitStatusIfChanged();
        });
        statusEditor.statusEmojiUnifiedProperty().addListener((obs, o, n) -> commitStatusIfChanged());

        return statusEditor;
    }

    private void commitStatusIfChanged() {
        if (suppressStatusAutoSave) return;
        String text = statusEditor.getStatusText() != null ? statusEditor.getStatusText().trim() : "";
        String emoji = statusEditor.getStatusEmojiUnified();
        if (text.equals(lastSavedStatusText) && Objects.equals(emoji, lastSavedStatusEmoji)) return;

        lastSavedStatusText = text;
        lastSavedStatusEmoji = emoji;

        Thread.ofVirtual().start(() -> {
            try {
                UserUpdateRequest req = UserUpdateRequest.builder()
                        .statusMessage(text)
                        .statusEmoji(emoji == null ? "" : emoji)
                        .build();
                MainUserSummary updated = App.getServices().hub().getUserService().updateUser(req);
                Platform.runLater(() -> {
                    MainUserSummary current = App.getUser();
                    if (current != null) {
                        current.setStatusMessage(updated.getStatusMessage());
                        current.setStatusEmoji(updated.getStatusEmoji());
                    }
                });
            } catch (Exception e) {
                log.error("Failed to save status", e);
            }
        });
    }

    // ─── Edit profile button ──────────────────────────────────────────────────

    private Button buildEditProfileButton() {
        FontIcon icon = new FontIcon(MaterialDesignC.COG_OUTLINE);
        icon.setIconSize(13);

        Button btn = new Button("User Settings", icon);
        btn.setFocusTraversable(false);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setPadding(new Insets(9, 0, 9, 0));
        btn.setStyle(editBtnStyle(false));
        btn.setOnMouseEntered(e -> btn.setStyle(editBtnStyle(true)));
        btn.setOnMouseExited(e -> btn.setStyle(editBtnStyle(false)));
        btn.setOnAction(e -> App.showModal(new EditUserModal()));
        return btn;
    }

    private String editBtnStyle(boolean hovered) {
        String bg = hovered ? "-color-bg-default" : "transparent";
        String border = hovered ? "-color-accent-7" : "-color-border-default";
        return
                "-fx-background-color: " + bg + "; " +
                        "-fx-background-radius: 8px; " +
                        "-fx-border-color: " + border + "; " +
                        "-fx-border-radius: 8px; " +
                        "-fx-border-width: 1.5px; " +
                        "-fx-font-size: 12px; " +
                        "-fx-cursor: hand;";
    }

    // ─── Audio controls ───────────────────────────────────────────────────────

    private VBox createAudioControls() {
        VBox box = new VBox(12);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setPadding(new Insets(15));
        box.setStyle(
                "-fx-background-color: -color-bg-subtle; " +
                        "-fx-background-radius: 10px; " +
                        "-fx-border-color: -color-border-default; " +
                        "-fx-border-radius: 10px;"
        );

        Label audioHeader = new Label("Audio Settings");
        audioHeader.setStyle("-fx-font-weight: bold;");
        audioHeader.setPadding(new Insets(0, 0, 5, 0));

        HBox micControl = createAudioControl("MICROPHONE", MaterialDesignM.MICROPHONE, this::onMicToggle);
        microphonesCombobox = (ComboBox<String>) ((VBox) micControl.getChildren().get(0)).getChildren().get(1);
        microphonesCombobox.setMaxHeight(32);
        microphonesCombobox.setMinHeight(32);
        micToggleButton = (Button) micControl.getChildren().get(1);

        HBox speakerControl = createAudioControl("HEADPHONES", MaterialDesignH.HEADPHONES, this::onSpeakerToggle);
        speakersCombobox = (ComboBox<String>) ((VBox) speakerControl.getChildren().get(0)).getChildren().get(1);
        speakersCombobox.setMaxHeight(32);
        speakersCombobox.setMinHeight(32);
        speakerToggleButton = (Button) speakerControl.getChildren().get(1);

        box.getChildren().addAll(audioHeader, micControl, speakerControl);

        refreshMicButton();
        refreshSpeakerButton();

        return box;
    }

    private HBox createAudioControl(String title, Ikon buttonIcon, Runnable onToggle) {
        HBox control = new HBox(10);
        control.setPadding(new Insets(5, 0, 5, 0));
        control.setAlignment(Pos.BOTTOM_LEFT);

        Label label = new Label(title);
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        ComboBox<String> comboBox = new ComboBox<>();
        comboBox.setFocusTraversable(false);
        HBox.setHgrow(comboBox, Priority.ALWAYS);
        comboBox.setMaxWidth(Double.MAX_VALUE);
        comboBox.getStyleClass().add("compact-combo");

        VBox labeledCombo = new VBox(4, label, comboBox);
        HBox.setHgrow(labeledCombo, Priority.ALWAYS);

        Button toggleButton = new Button(null, new FontIcon(buttonIcon));
        toggleButton.setFocusTraversable(false);
        toggleButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, "custom-toolbar-button", "audio-toggle-bordered");
        toggleButton.setMinSize(32, 32);
        toggleButton.setMaxSize(32, 32);
        toggleButton.setPrefSize(32, 32);
        toggleButton.setOnAction(e -> {
            if (onToggle != null) onToggle.run();
        });

        control.getChildren().addAll(labeledCombo, toggleButton);
        return control;
    }

    private void onMicToggle() {
        AppState.applyMicEnabled(!AppState.micEnabledProperty().get());
    }

    private void onSpeakerToggle() {
        AppState.applySpeakerEnabled(!AppState.speakerEnabledProperty().get());
    }

    private void updateToggleButtonState(Button button, boolean enabled, Ikon onIcon, Ikon offIcon) {
        if (button == null) return;
        FontIcon icon = new FontIcon(enabled ? onIcon : offIcon);
        icon.getStyleClass().add(enabled ? "custom-icon-20" : "custom-icon-20-accent");
        button.setGraphic(icon);
        button.getStyleClass().remove("toolbar-button-base");
        if (enabled) {
            button.getStyleClass().remove("custom-toolbar-button-muted");
            if (!button.getStyleClass().contains("custom-toolbar-button"))
                button.getStyleClass().add("custom-toolbar-button");
        } else {
            button.getStyleClass().remove("custom-toolbar-button");
            if (!button.getStyleClass().contains("custom-toolbar-button-muted"))
                button.getStyleClass().add("custom-toolbar-button-muted");
        }
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    public void dispose() {
        audioService.unbind(microphonesCombobox, speakersCombobox);
    }
}