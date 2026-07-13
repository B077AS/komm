package komm.ui.modals.usersettings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import komm.ui.customnodes.CustomNotification;
import komm.ui.customnodes.KeyBindingField;
import komm.utils.GlobalHotkeyManager;
import komm.utils.UserSettings;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignK;

import java.util.Arrays;

import static komm.ui.modals.usersettings.UserSettingsUi.*;

/** "Key Bindings" tab: global hotkeys for mute/unmute and deafen/undeafen. */
public class KeybindingsUserTab implements UserSettingsTab {

    private final UserSettingsContext ctx;
    private final KeyBindingField muteField = new KeyBindingField();
    private final KeyBindingField deafenField = new KeyBindingField();
    private int[] origMuteKeys;
    private int[] origDeafenKeys;
    private final ScrollPane pane;

    public KeybindingsUserTab(UserSettingsContext ctx) {
        this.ctx = ctx;
        UserSettings us = UserSettings.getInstance();
        origMuteKeys   = us.getKeybindingMuteKeys();
        origDeafenKeys = us.getKeybindingDeafenKeys();
        muteField.setKeys(origMuteKeys);
        deafenField.setKeys(origDeafenKeys);
        this.pane = buildPane();
    }

    @Override public String name() { return "Key Bindings"; }
    @Override public String description() { return "Customize keyboard shortcuts"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignK.KEYBOARD_OUTLINE); }
    @Override public Node getPane() { return pane; }
    @Override public boolean participatesInSave() { return true; }
    @Override public String saveButtonText() { return "Save Key Bindings"; }

    @Override
    public boolean isDirty() {
        return !Arrays.equals(muteField.getKeys(), origMuteKeys)
                || !Arrays.equals(deafenField.getKeys(), origDeafenKeys);
    }

    @Override
    public void save() {
        int[] muteKeys   = muteField.getKeys();
        int[] deafenKeys = deafenField.getKeys();
        UserSettings us = UserSettings.getInstance();
        us.setKeybindingMuteKeys(muteKeys);
        us.setKeybindingDeafenKeys(deafenKeys);
        GlobalHotkeyManager.getInstance().loadBindings();
        origMuteKeys   = Arrays.copyOf(muteKeys, muteKeys.length);
        origDeafenKeys = Arrays.copyOf(deafenKeys, deafenKeys.length);
        ctx.refreshSaveButton();
        new CustomNotification("Key Bindings Saved", "Your key bindings have been applied.",
                new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE)).showNotification();
    }

    @Override
    public void dispose() {
        muteField.cancelRecording();
        deafenField.cancelRecording();
    }

    private ScrollPane buildPane() {
        boolean available = GlobalHotkeyManager.getInstance().isStarted();

        muteField.setOnChange(ctx::refreshSaveButton);
        deafenField.setOnChange(ctx::refreshSaveButton);

        VBox root = new VBox(10);
        root.setPadding(new Insets(20, 28, 20, 28));

        root.getChildren().add(sectionLabel("Global Hotkeys"));

        if (!available) {
            Label unavailableLabel = new Label(
                    "⚠ Global hotkeys are unavailable on this system. " +
                    "On Linux, make sure the app has permission to access input devices.");
            unavailableLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-warning-fg;");
            unavailableLabel.setWrapText(true);
            muteField.setDisable(true);
            deafenField.setDisable(true);
            root.getChildren().add(unavailableLabel);
        }

        root.getChildren().addAll(
                buildRow("Mute / Unmute", "Toggle your microphone on or off.", muteField),
                buildRow("Deafen / Undeafen", "Toggle headphones on or off. Also mutes your mic.", deafenField)
        );

        Label hint = new Label(
                "Click a binding to record. Hold the first key while pressing the second for combos. " +
                "Press Escape to cancel.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        hint.setWrapText(true);
        root.getChildren().add(hint);

        return wrapScroll(root);
    }

    private HBox buildRow(String title, String description, KeyBindingField field) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        descLabel.setWrapText(true);

        VBox left = new VBox(3, titleLabel, descLabel);
        HBox.setHgrow(left, Priority.ALWAYS);

        Region spacer = new Region();
        spacer.setMinWidth(24);

        HBox row = new HBox(0, left, spacer, field);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(14, 16, 14, 16));
        row.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                " -fx-background-radius: 8px;");
        return row;
    }
}
