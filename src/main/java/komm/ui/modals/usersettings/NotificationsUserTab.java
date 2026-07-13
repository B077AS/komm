package komm.ui.modals.usersettings;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import komm.ui.customnodes.CustomNotification;
import komm.utils.UserSettings;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import static komm.ui.modals.usersettings.UserSettingsUi.*;

/** "Notifications" tab: DM notification toggle. */
public class NotificationsUserTab implements UserSettingsTab {

    private final UserSettingsContext ctx;
    private final CheckBox dmNotificationsToggle = new CheckBox("Enable DM notifications");
    private boolean origDmNotif;
    private final ScrollPane pane;

    public NotificationsUserTab(UserSettingsContext ctx) {
        this.ctx = ctx;
        this.origDmNotif = UserSettings.getInstance().isDmNotificationsEnabled();
        dmNotificationsToggle.setSelected(origDmNotif);
        dmNotificationsToggle.setFocusTraversable(false);
        dmNotificationsToggle.selectedProperty().addListener((obs, o, n) -> ctx.refreshSaveButton());
        this.pane = buildPane();
    }

    @Override public String name() { return "Notifications"; }
    @Override public String description() { return "Choose what you get notified about"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignB.BELL_OUTLINE); }
    @Override public Node getPane() { return pane; }
    @Override public boolean participatesInSave() { return true; }
    @Override public String saveButtonText() { return "Save Notifications"; }
    @Override public boolean isDirty() { return dmNotificationsToggle.isSelected() != origDmNotif; }

    @Override
    public void save() {
        boolean dmOn = dmNotificationsToggle.isSelected();
        UserSettings.getInstance().setDmNotificationsEnabled(dmOn);
        origDmNotif = dmOn;
        ctx.refreshSaveButton();
        new CustomNotification("Settings Saved", "Your notification preferences have been updated.",
                new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE)).showNotification();
    }

    private ScrollPane buildPane() {
        Label dmDesc = new Label(
                "Show a pop-up notification when you receive a direct message while the conversation is not in focus.");
        dmDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        dmDesc.setWrapText(true);

        VBox pane = new VBox(16);
        pane.setPadding(new Insets(20, 28, 20, 28));
        pane.getChildren().addAll(
                sectionLabel("Direct Messages"),
                new VBox(6, dmNotificationsToggle, dmDesc)
        );
        return wrapScroll(pane);
    }
}
