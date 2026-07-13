package komm.ui.customnodes;

import java.util.List;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import komm.App;
import komm.AppState;
import komm.model.dto.summary.MainUserSummary;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import atlantafx.base.theme.Styles;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import komm.utils.UserSettings;

public class AudioDeviceSelector extends HBox {
    private static final double TOGGLE_SIZE = 37;
    private static final double CHEVRON_WIDTH = 16;

    private final Button toggleButton;
    private final Button dropdownButton;
    private final Ikon enabledIcon;
    private final Ikon disabledIcon;
    private final AudioDeviceType deviceType;
    private boolean enabled;
    private boolean serverLocked; // true when server has muted/deafened this device
    private Popup activeMenu;

    private final BooleanProperty enabledProperty = new SimpleBooleanProperty(this, "enabled", true);

    public BooleanProperty enabledProperty() {
        return enabledProperty;
    }

    public AudioDeviceSelector(Ikon enabledIcon, Ikon disabledIcon, boolean initialState, AudioDeviceType deviceType) {
        this.enabledIcon = enabledIcon;
        this.disabledIcon = disabledIcon;
        this.deviceType = deviceType;

        this.enabled = deviceType == AudioDeviceType.INPUT
                ? AppState.micEnabledProperty().get()
                : AppState.speakerEnabledProperty().get();
        this.serverLocked = deviceType == AudioDeviceType.INPUT
                ? !AppState.serverMicEnabledProperty().get()
                : !AppState.serverSpeakerEnabledProperty().get();
        enabledProperty.set(this.enabled);

        this.setAlignment(Pos.CENTER);
        this.setSpacing(3);
        this.setPrefHeight(TOGGLE_SIZE);
        this.setMaxHeight(TOGGLE_SIZE);
        this.getStyleClass().add("audio-device-selector");

        FontIcon toggleIcon = new FontIcon(this.enabled ? enabledIcon : disabledIcon);
        toggleButton = new Button(null, toggleIcon);
        toggleButton.setFocusTraversable(false);
        toggleButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, "toolbar-chip");
        toggleButton.setMinSize(TOGGLE_SIZE, TOGGLE_SIZE);
        toggleButton.setMaxSize(TOGGLE_SIZE, TOGGLE_SIZE);
        toggleButton.setPrefSize(TOGGLE_SIZE, TOGGLE_SIZE);
        toggleButton.setOnAction(e -> onToggle());

        FontIcon chevronIcon = new FontIcon(Feather.CHEVRON_UP);
        chevronIcon.setIconSize(11);
        dropdownButton = new Button(null, chevronIcon);
        dropdownButton.setFocusTraversable(false);
        dropdownButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, "toolbar-chevron");
        dropdownButton.setMinSize(CHEVRON_WIDTH, TOGGLE_SIZE);
        dropdownButton.setMaxSize(CHEVRON_WIDTH, TOGGLE_SIZE);
        dropdownButton.setPrefSize(CHEVRON_WIDTH, TOGGLE_SIZE);
        dropdownButton.setOnAction(e -> onDropdownClick());

        this.getChildren().addAll(toggleButton, dropdownButton);

        updateStyle();

        // Mirror App-level audio state changes into this widget's UI
        if (deviceType == AudioDeviceType.INPUT) {
            AppState.micEnabledProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != enabled) {
                    enabled = newVal;
                    enabledProperty.set(enabled);
                    Platform.runLater(this::updateStyle);
                }
            });
            AppState.serverMicEnabledProperty().addListener((obs, oldVal, newVal) -> {
                serverLocked = !newVal;
                Platform.runLater(this::updateStyle);
            });
        } else {
            AppState.speakerEnabledProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != enabled) {
                    enabled = newVal;
                    enabledProperty.set(enabled);
                    Platform.runLater(this::updateStyle);
                }
            });
            AppState.serverSpeakerEnabledProperty().addListener((obs, oldVal, newVal) -> {
                serverLocked = !newVal;
                Platform.runLater(this::updateStyle);
            });
        }
    }

    private void onToggle() {
        if (deviceType == AudioDeviceType.INPUT) {
            AppState.applyMicEnabled(!enabled);
        } else {
            AppState.applySpeakerEnabled(!enabled);
        }
    }

    private void onDropdownClick() {
        if (activeMenu != null && activeMenu.isShowing()) {
            activeMenu.hide();
            return;
        }
        showDeviceMenu();
    }

    private void showDeviceMenu() {
        List<String> devices;
        String currentDevice;
        try {
            if (deviceType == AudioDeviceType.INPUT) {
                devices = App.getWebrtcRoomClient().getInputDevices();
                currentDevice = App.getWebrtcRoomClient().getCurrentInputDevice();
            } else {
                devices = App.getWebrtcRoomClient().getOutputDevices();
                currentDevice = App.getWebrtcRoomClient().getCurrentOutputDevice();
            }
            if (currentDevice == null) {
                currentDevice = deviceType == AudioDeviceType.INPUT
                        ? UserSettings.getInstance().getInputDevice()
                        : UserSettings.getInstance().getOutputDevice();
            }
        } catch (Exception e) {
            System.err.println("Error loading devices: " + e.getMessage());
            devices = null;
            currentDevice = null;
        }

        VBox content = new VBox(2);
        content.getStyleClass().addAll("custom-pop-up", "audio-device-menu");
        content.setPadding(new Insets(4));
        content.setMinWidth(160);

        if (devices == null || devices.isEmpty()) {
            Label empty = new Label(devices == null ? "Error loading devices" : "No devices available");
            empty.setPadding(new Insets(6, 10, 6, 10));
            empty.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
            content.getChildren().add(empty);
        } else {
            for (String device : devices) {
                content.getChildren().add(buildDeviceRow(device, device != null && device.equals(currentDevice)));
            }
        }

        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(content);
        popup.setOnHidden(e -> {
            dropdownButton.getStyleClass().remove("showing");
            if (activeMenu == popup) activeMenu = null;
        });
        activeMenu = popup;
        dropdownButton.getStyleClass().add("showing");

        // Show once off-position first so the popup measures its own size, then
        // reposition it above the widget — mirrors PingGraphPopup's positioning.
        Bounds b = dropdownButton.localToScreen(dropdownButton.getBoundsInLocal());
        popup.show(this.getScene().getWindow());
        popup.setX(b.getMinX());
        popup.setY(b.getMinY() - popup.getHeight() - 8);
    }

    private HBox buildDeviceRow(String device, boolean selected) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5, 10, 5, 10));
        row.setCursor(Cursor.HAND);
        row.getStyleClass().add("toolbar-status-item");
        if (selected) {
            row.getStyleClass().add("selected");
        }

        Label lbl = new Label(device);
        lbl.setStyle("-fx-font-size: 12px;");
        row.getChildren().add(lbl);

        row.setOnMouseClicked(e -> {
            onDeviceSelected(device);
            if (activeMenu != null) activeMenu.hide();
        });

        return row;
    }

    private void onDeviceSelected(String device) {
        if (device != null) {
            try {
                if (deviceType == AudioDeviceType.INPUT) {
                    App.getWebrtcRoomClient().changeInputDevice(device);
                } else {
                    App.getWebrtcRoomClient().changeOutputDevice(device);
                }
            } catch (Exception e) {
                System.err.println("Error changing device: " + e.getMessage());
            }
        }
    }

    private void updateStyle() {
        boolean showMuted = serverLocked || !enabled;
        FontIcon icon = new FontIcon(showMuted ? disabledIcon : enabledIcon);
        toggleButton.setGraphic(icon);
        toggleButton.setDisable(serverLocked);

        if (!showMuted) {
            this.getStyleClass().removeAll("muted");
            toggleButton.getStyleClass().remove("toolbar-chip-muted");
            if (!toggleButton.getStyleClass().contains("toolbar-chip"))
                toggleButton.getStyleClass().add("toolbar-chip");
            dropdownButton.getStyleClass().remove("toolbar-chevron-muted");
            if (!dropdownButton.getStyleClass().contains("toolbar-chevron"))
                dropdownButton.getStyleClass().add("toolbar-chevron");
        } else {
            this.getStyleClass().add("muted");
            toggleButton.getStyleClass().remove("toolbar-chip");
            if (!toggleButton.getStyleClass().contains("toolbar-chip-muted"))
                toggleButton.getStyleClass().add("toolbar-chip-muted");
            dropdownButton.getStyleClass().remove("toolbar-chevron");
            if (!dropdownButton.getStyleClass().contains("toolbar-chevron-muted"))
                dropdownButton.getStyleClass().add("toolbar-chevron-muted");
        }
    }

    public void refresh() {
        MainUserSummary user = App.getUser();
        if (user == null) return;
        boolean newState = deviceType == AudioDeviceType.INPUT
                ? user.isMicEnabled()
                : user.isSpeakerEnabled();
        if (newState != enabled) {
            enabled = newState;
            enabledProperty.set(enabled);
            updateStyle();
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        enabledProperty.set(enabled);
        updateStyle();
    }

    public enum AudioDeviceType {
        INPUT, OUTPUT
    }
}
