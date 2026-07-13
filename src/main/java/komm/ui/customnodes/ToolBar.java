package komm.ui.customnodes;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.geometry.Bounds;
import javafx.stage.Popup;
import komm.App;
import komm.AppState;
import komm.model.permissions.Permission;
import komm.utils.PingHistory;
import komm.model.dto.summary.MainUserSummary;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import komm.ui.avatar.AvatarColor;
import komm.ui.modals.EditUserModal;
import komm.ui.modals.ScreenShareModal;
import komm.webrtc.WebrtcRoomClient;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;
import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import komm.ui.customnodes.AudioDeviceSelector.AudioDeviceType;

public class ToolBar extends HBox {

    private static final double SIDE_SECTION_WIDTH = 220;
    private static final double BUTTON_SIZE = 32;

    private final HBox userPanel;
    private final HBox centerSection;
    private final HBox rightPanel;

    private Circle statusCircle;
    private Label currentStatusLabel;
    private Popup statusPopup;

    private StackPane avatarStack;
    private Circle avatarBgCircle;
    private Label avatarInitials;

    private AudioDeviceSelector microphoneSelector;
    private AudioDeviceSelector speakerSelector;

    private Button screenShareButton;
    private Button cameraButton;
    private Button musicBotButton;
    private Button soundboardButton;
    private Button settingsButton;
    private SoundboardPopup soundboardPopup;

    private SignalBars signalBars;
    private Label pingLabel;
    private PingGraphPopup pingGraphPopup;

    public ToolBar() {
        userPanel = createUserPanel();
        centerSection = createCenterSection();
        rightPanel = createRightPanel();

        App.getWebrtcRoomClient().setOnConnectionStateChanged(() ->
                Platform.runLater(() -> {
                    syncScreenShareButton();
                    syncChannelFeatures();
                })
        );
        this.setMinHeight(60);
        this.setMaxHeight(60);
        this.setPadding(new Insets(5, 12, 5, 12));
        this.setAlignment(Pos.CENTER);
        this.setSpacing(0);
        this.getStyleClass().add("komm-toolbar");

        this.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                microphoneSelector.refresh();
                speakerSelector.refresh();
            }
        });

        Region leftSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        this.getChildren().addAll(
                userPanel,
                leftSpacer,
                centerSection,
                rightSpacer,
                rightPanel
        );

        PingHistory.addListener(this::setPing);
    }

    // ---------------------------------------------------------------------
    // CENTER — mic + speaker always in the absolute middle, flanked
    // symmetrically by channel-features (left) and video-share (right).
    // Groups separated by thin vertical dividers, no nested borders.
    // ---------------------------------------------------------------------
    private HBox createCenterSection() {
        HBox center = new HBox(0);
        center.setAlignment(Pos.CENTER);
        center.getStyleClass().addAll("toolbar-center", "toolbar-center-island");

        HBox channelPod = createChannelFeaturesPod();
        HBox audioPod = createAudioPod();
        HBox videoPod = createVideoSharePod();

        Separator sep1 = new Separator(Orientation.VERTICAL);
        sep1.setPrefHeight(25);
        sep1.setMaxHeight(25);

        Separator sep2 = new Separator(Orientation.VERTICAL);
        sep2.setPrefHeight(25);
        sep2.setMaxHeight(25);

        center.getChildren().addAll(
                channelPod,
                sep1,
                audioPod,
                sep2,
                videoPod
        );
        return center;
    }

    private HBox createAudioPod() {
        HBox pod = new HBox(6);
        pod.setAlignment(Pos.CENTER);
        pod.setPadding(new Insets(0, 10, 0, 10));

        microphoneSelector = new AudioDeviceSelector(
                MaterialDesignM.MICROPHONE, MaterialDesignM.MICROPHONE_OFF, true, AudioDeviceType.INPUT);
        microphoneSelector.setMinHeight(BUTTON_SIZE);
        microphoneSelector.setMaxHeight(BUTTON_SIZE);

        speakerSelector = new AudioDeviceSelector(
                MaterialDesignH.HEADPHONES, MaterialDesignH.HEADPHONES_OFF, true, AudioDeviceType.OUTPUT);
        speakerSelector.setMinHeight(BUTTON_SIZE);
        speakerSelector.setMaxHeight(BUTTON_SIZE);

        pod.getChildren().addAll(microphoneSelector, speakerSelector);
        return pod;
    }

    private HBox createVideoSharePod() {
        HBox pod = new HBox(6);
        pod.setAlignment(Pos.CENTER);
        pod.setPadding(new Insets(0, 10, 0, 10));

        screenShareButton = buildIconButton(MaterialDesignM.MONITOR_SHARE, "Share screen");
        screenShareButton.setOnAction(e -> onScreenShareToggle());

        // Camera is temporarily removed — keep the button visible but permanently
        // disabled with a "Coming soon" tooltip and no action wired up.
        cameraButton = buildIconButton(MaterialDesignV.VIDEO_OUTLINE, "Coming soon");
        cameraButton.setDisable(true);

        pod.getChildren().addAll(screenShareButton, cameraButton);

        syncScreenShareButton();
        return pod;
    }

    private HBox createChannelFeaturesPod() {
        HBox pod = new HBox(6);
        pod.setAlignment(Pos.CENTER);
        pod.setPadding(new Insets(0, 10, 0, 10));

        // Music bot is temporarily removed — keep the button visible but permanently
        // disabled with a "Coming soon" tooltip and no action wired up.
        musicBotButton = buildIconButton(MaterialDesignM.MUSIC, "Coming soon");
        musicBotButton.setDisable(true);

        soundboardButton = buildIconButton(MaterialDesignM.MUSIC_BOX_MULTIPLE_OUTLINE, "Soundboard");
        soundboardButton.setOnAction(e -> onSoundboardClick());

        pod.getChildren().addAll(musicBotButton, soundboardButton);

        syncChannelFeatures();
        return pod;
    }

    private Button buildIconButton(org.kordamp.ikonli.Ikon icon, String tooltipText) {
        Button b = new Button(null, new FontIcon(icon));
        b.setFocusTraversable(false);
        b.getStyleClass().addAll(Styles.BUTTON_ICON, "toolbar-chip-ghost");
        b.setMinSize(BUTTON_SIZE, BUTTON_SIZE);
        b.setMaxSize(BUTTON_SIZE, BUTTON_SIZE);
        b.setPrefSize(BUTTON_SIZE, BUTTON_SIZE);
        if (tooltipText != null) {
            Tooltip tip = new Tooltip(tooltipText);
            tip.setShowDelay(javafx.util.Duration.millis(400));
            b.setTooltip(tip);
        }
        return b;
    }

    // ---------------------------------------------------------------------
    // RIGHT — ping + settings, right-aligned
    // ---------------------------------------------------------------------
    private HBox createRightPanel() {
        HBox right = new HBox(6);
        right.setMinWidth(SIDE_SECTION_WIDTH);
        right.setMaxWidth(SIDE_SECTION_WIDTH);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.getStyleClass().add("toolbar-side");

        signalBars = new SignalBars();
        signalBars.setCursor(Cursor.HAND);
        signalBars.setOnMouseClicked(e -> togglePingPopup());

        pingLabel = new Label("-- ms");
        pingLabel.getStyleClass().add("toolbar-ping-label");

        settingsButton = buildIconButton(MaterialDesignC.COG, "Settings");
        settingsButton.setOnAction(e -> onSettingsClick());
        settingsButton.getStyleClass().remove("custom-toolbar-button");
        settingsButton.getStyleClass().add("toolbar-button-ghost");

        right.getChildren().addAll(signalBars, pingLabel, settingsButton);
        return right;
    }

    // ---------------------------------------------------------------------
    // LEFT — user panel, mirrors right panel's fixed width
    // ---------------------------------------------------------------------
    private HBox createUserPanel() {
        HBox userContainer = new HBox(10);
        userContainer.setMinWidth(SIDE_SECTION_WIDTH);
        userContainer.setMaxWidth(SIDE_SECTION_WIDTH);
        userContainer.setAlignment(Pos.CENTER_LEFT);
        userContainer.getStyleClass().add("toolbar-side");

        StackPane avatar = createAvatar();

        VBox userInfo = new VBox(1);
        userInfo.setAlignment(Pos.CENTER_LEFT);

        MainUserSummary user = App.getUser();
        String username = user != null ? user.getUsername() : "Unknown";
        Label nameLabel = new Label(username);
        nameLabel.getStyleClass().add("toolbar-username");

        HBox statusRow = createStatusRow();

        // Sync initial status
        syncStatusDisplay(AppState.userStatusProperty().get());

        // Keep toolbar status label/dot in sync with App-level status changes
        AppState.userStatusProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) Platform.runLater(() -> syncStatusDisplay(newVal));
        });

        // Refresh the avatar when the user changes their own avatar
        AppState.selfAvatarRevisionProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(this::applyAvatarContent));

        userInfo.getChildren().addAll(nameLabel, statusRow);
        userContainer.getChildren().addAll(avatar, userInfo);

        avatar.setCursor(Cursor.HAND);
        avatar.setOnMouseClicked(e -> openSelfProfile());
        nameLabel.setCursor(Cursor.HAND);
        nameLabel.setOnMouseClicked(e -> openSelfProfile());

        return userContainer;
    }

    private void openSelfProfile() {
        MainUserSummary user = App.getUser();
        if (user == null) return;
        App.showModal(new komm.ui.modals.UserProfileModal(user.getUserId(), true));
    }


    private StackPane createAvatar() {
        avatarStack = new StackPane();
        avatarStack.setMinSize(42, 42);
        avatarStack.setMaxSize(42, 42);
        avatarStack.setAlignment(Pos.CENTER);

        avatarBgCircle = new Circle(19);

        statusCircle = new Circle(5.5);
        statusCircle.getStyleClass().addAll("status-dot", UserStatus.ONLINE.getCssClass());
        statusCircle.setStyle("-fx-stroke: -color-bg-middle; -fx-stroke-width: 2;");
        StackPane.setAlignment(statusCircle, Pos.BOTTOM_RIGHT);

        // [bgCircle, (initials), statusCircle] — initials inserted between by applyAvatarContent
        avatarStack.getChildren().addAll(avatarBgCircle, statusCircle);
        applyAvatarContent();

        return avatarStack;
    }

    /**
     * Fills the avatar circle from the logged-in user's current avatar bytes, falling
     * back to the initial-letter circle. Re-runnable, so it doubles as the refresh path
     * when the user changes their own avatar.
     */
    private void applyAvatarContent() {
        MainUserSummary user = App.getUser();
        if (user != null) {
            App.getAvatarCache().populate(user.getUserId(), user.getUsername(), user.getAvatar());
        }
        boolean hasImage = user != null && user.getAvatar() != null && user.getAvatar().length > 0;

        if (hasImage) {
            try {
                Image image = new Image(
                        new java.io.ByteArrayInputStream(user.getAvatar()),
                        38, 38, true, true
                );
                if (!image.isError()) {
                    avatarBgCircle.setFill(new javafx.scene.paint.ImagePattern(image));
                } else {
                    hasImage = false;
                }
            } catch (Exception e) {
                hasImage = false;
            }
        }

        if (avatarInitials != null) {
            avatarStack.getChildren().remove(avatarInitials);
            avatarInitials = null;
        }

        if (!hasImage) {
            String username = user != null ? user.getUsername() : null;
            String initial = (username != null && !username.isEmpty())
                    ? String.valueOf(username.charAt(0)).toUpperCase() : "?";
            avatarInitials = new Label(initial);
            avatarInitials.getStyleClass().add("toolbar-avatar-initials");
            avatarInitials.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: white;");
            avatarBgCircle.setFill(AvatarColor.forNameJfx(username));
            // Keep the status dot on top by inserting initials just below it
            int statusIdx = avatarStack.getChildren().indexOf(statusCircle);
            avatarStack.getChildren().add(statusIdx < 0 ? avatarStack.getChildren().size() : statusIdx, avatarInitials);
        }
    }

    private HBox createStatusRow() {
        HBox statusBox = new HBox(4);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        statusBox.getStyleClass().add("toolbar-status-row");

        currentStatusLabel = new Label(UserStatus.ONLINE.getValue());
        currentStatusLabel.getStyleClass().add("toolbar-status-label");

        FontIcon chevronIcon = new FontIcon(Feather.CHEVRON_DOWN);
        chevronIcon.setIconSize(10);
        chevronIcon.getStyleClass().add("toolbar-status-chevron");

        statusBox.getChildren().addAll(currentStatusLabel, chevronIcon);
        statusBox.setCursor(Cursor.HAND);
        statusBox.setOnMouseClicked(e -> toggleStatusPopup(statusBox));

        return statusBox;
    }

    private void toggleStatusPopup(HBox anchor) {
        if (statusPopup != null && statusPopup.isShowing()) {
            statusPopup.hide();
            return;
        }

        statusPopup = new Popup();
        statusPopup.setAutoHide(true);
        statusPopup.setHideOnEscape(true);

        VBox content = new VBox(2);
        content.getStyleClass().add("context-menu");
        content.setPadding(new Insets(4));

        for (StatusComboBox.StatusOption entry : StatusComboBox.STATUS_OPTIONS) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(5, 10, 5, 10));
            row.setCursor(Cursor.HAND);
            row.getStyleClass().add("toolbar-status-item");

            FontIcon dot = new FontIcon(entry.icon());
            dot.getStyleClass().add(entry.cssClass());

            Label lbl = new Label(entry.label());
            lbl.setStyle("-fx-font-size: 12px;");

            row.getChildren().addAll(dot, lbl);
            row.setOnMouseClicked(e -> {
                AppState.requestStatusChange(entry.status());
                statusPopup.hide();
            });

            content.getChildren().add(row);
        }

        statusPopup.getContent().add(content);

        javafx.geometry.Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        statusPopup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 4);
    }

    private void syncStatusDisplay(UserStatus status) {
        for (UserStatus s : UserStatus.values()) statusCircle.getStyleClass().remove(s.getCssClass());
        statusCircle.getStyleClass().add(status.getCssClass());
        for (StatusComboBox.StatusOption opt : StatusComboBox.STATUS_OPTIONS) {
            if (opt.status() == status) {
                currentStatusLabel.setText(opt.label());
                return;
            }
        }
        currentStatusLabel.setText(status.getValue());
    }

    // ---------------------------------------------------------------------
    // Screen share / camera / channel features
    // ---------------------------------------------------------------------
    private void syncScreenShareButton() {
        WebrtcRoomClient client = App.getWebrtcRoomClient();
        if (client == null) {
            screenShareButton.setDisable(true);
            return;
        }
        boolean inChannel = client.isInChannel();
        screenShareButton.setDisable(!inChannel);
        // Leaving/switching channels stops any active share. The webrtc client may have
        // already torn down screenShareClient (so isScreenSharing() is false), so always
        // reset the button to its default state when out of a channel.
        if (!inChannel) {
            setScreenShareActive(false);
        }
    }

    private void syncChannelFeatures() {
        WebrtcRoomClient client = App.getWebrtcRoomClient();
        boolean inVoice = client != null && client.isInChannel();

        if (soundboardButton != null) {
            boolean canSoundboard = inVoice && client.getCurrentChannelId() != null
                    && (App.getPermissionManager().hasInChannel(client.getCurrentChannelId(), Permission.USE_SOUNDBOARD)
                        || App.getPermissionManager().hasInChannel(client.getCurrentChannelId(), Permission.MANAGE_SERVER_SOUNDBOARD));
            soundboardButton.setDisable(!canSoundboard);
            if (!canSoundboard && soundboardPopup != null && soundboardPopup.isShowing()) {
                soundboardPopup.hide();
                soundboardPopup = null;
            }
        }
    }

    private void onScreenShareToggle() {
        if (App.getWebrtcRoomClient().isScreenSharing()) {
            if (App.getWebrtcRoomClient() != null) {
                App.getWebrtcRoomClient().stopScreenShare();
            }
            setScreenShareActive(false);
        } else {
            ScreenShareModal modal = new ScreenShareModal(selection -> {
                if (App.getWebrtcRoomClient() != null) {
                    App.getWebrtcRoomClient().startScreenShare(selection);
                    setScreenShareActive(true);
                }
            });
            App.showModal(modal);
        }
    }

    private void setScreenShareActive(boolean active) {
        FontIcon icon = (FontIcon) screenShareButton.getGraphic();
        icon.setIconCode(active ? MaterialDesignM.MONITOR_OFF : MaterialDesignM.MONITOR_SHARE);
        setButtonActive(screenShareButton, active);
    }

    private void setButtonActive(Button button, boolean active) {
        button.getStyleClass().removeAll("toolbar-chip-ghost", "custom-toolbar-button-active");
        if (active) {
            button.getStyleClass().addAll(Styles.BUTTON_ICON, "custom-toolbar-button-active");
        } else {
            button.getStyleClass().addAll(Styles.BUTTON_ICON, "toolbar-chip-ghost");
        }
    }

    private void onSoundboardClick() {
        if (soundboardPopup != null && soundboardPopup.isShowing()) {
            soundboardPopup.hide();
            soundboardPopup = null;
            return;
        }
        WebrtcRoomClient client = App.getWebrtcRoomClient();
        if (client == null || !client.isInChannel()) return;
        java.util.UUID channelId = client.getCurrentChannelId();
        if (channelId == null) return;
        if (!App.getPermissionManager().hasInChannel(channelId, Permission.USE_SOUNDBOARD)) return;

        soundboardPopup = new SoundboardPopup(channelId);
        Bounds b = soundboardButton.localToScreen(soundboardButton.getBoundsInLocal());
        soundboardPopup.show(soundboardButton.getScene().getWindow());
        soundboardPopup.setX(b.getMinX() + b.getWidth() / 2 - 280);
        soundboardPopup.setY(b.getMinY() - 432);
    }

    private void onSettingsClick() {
        App.showModal(new EditUserModal());
    }

    private void togglePingPopup() {
        if (pingGraphPopup != null && pingGraphPopup.isShowing()) {
            pingGraphPopup.hide();
            return;
        }
        pingGraphPopup = new PingGraphPopup();
        Bounds b = signalBars.localToScreen(signalBars.getBoundsInLocal());
        pingGraphPopup.show(signalBars.getScene().getWindow());
        pingGraphPopup.setX(b.getMinX() + b.getWidth() / 2 - PingGraphPopup.WIDTH / 2.0);
        pingGraphPopup.setY(b.getMinY() - pingGraphPopup.getHeight() - 8);
    }

    public void setPing(int ms) {
        if (signalBars != null) signalBars.setPing(ms);
        if (pingLabel != null) {
            pingLabel.setText(ms < 0 ? "-- ms" : ms + " ms");
            pingLabel.getStyleClass().removeAll("ping-good", "ping-ok", "ping-warn", "ping-bad");
            pingLabel.getStyleClass().add(SignalBars.classForPing(ms));
        }
    }

    // =====================================================================
    // SignalBars — ascending bars inside a fixed-height StackPane so the
    // whole widget's vertical center aligns with the sibling Label's center.
    // =====================================================================
    private static final class SignalBars extends StackPane {
        private static final int BAR_COUNT = 4;
        private static final double MAX_BAR_HEIGHT = 20;
        private final Rectangle[] bars = new Rectangle[BAR_COUNT];

        SignalBars() {
            HBox row = new HBox(2);
            row.setAlignment(Pos.BOTTOM_CENTER);
            row.getStyleClass().add("signal-bars");

            double[] heights = {8, 12, 16, MAX_BAR_HEIGHT};
            for (int i = 0; i < BAR_COUNT; i++) {
                Rectangle r = new Rectangle(3, heights[i]);
                r.setArcWidth(3);
                r.setArcHeight(3);
                r.getStyleClass().add("signal-bar");
                bars[i] = r;
                row.getChildren().add(r);
            }

            setMinHeight(MAX_BAR_HEIGHT);
            setMaxHeight(MAX_BAR_HEIGHT);
            setPrefHeight(MAX_BAR_HEIGHT);
            setAlignment(Pos.CENTER);
            getChildren().add(row);

            setPing(-1);
        }

        void setPing(int ms) {
            int filled = barsForPing(ms);
            String severityClass = classForPing(ms);

            for (int i = 0; i < BAR_COUNT; i++) {
                Rectangle r = bars[i];
                r.getStyleClass().removeAll(
                        "signal-bar-filled", "signal-bar-empty",
                        "ping-good", "ping-ok", "ping-warn", "ping-bad"
                );
                if (i < filled) {
                    r.getStyleClass().addAll("signal-bar-filled", severityClass);
                } else {
                    r.getStyleClass().add("signal-bar-empty");
                }
            }
        }

        static int barsForPing(int ms) {
            if (ms < 0) return 0;
            if (ms <= 60) return 4;
            if (ms <= 120) return 3;
            if (ms <= 200) return 2;
            return 1;
        }

        static String classForPing(int ms) {
            if (ms < 0) return "ping-bad";
            if (ms <= 60) return "ping-good";
            if (ms <= 120) return "ping-ok";
            if (ms <= 200) return "ping-warn";
            return "ping-bad";
        }
    }
}