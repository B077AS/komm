package komm.ui.modals.usersettings;

import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import komm.App;
import komm.AppState;
import komm.api.HttpStatusException;
import komm.model.dto.request.UserUpdateRequest;
import komm.model.dto.summary.MainUserSummary;
import komm.model.dto.summary.MainUserSummary.DmPrivacy;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import static komm.ui.modals.usersettings.UserSettingsUi.*;

/** "Privacy" tab: controls who is allowed to send the user direct messages. */
public class PrivacyUserTab implements UserSettingsTab {

    private final UserSettingsContext ctx;
    private final ComboBox<DmPrivacy> dmPrivacyCombo = new ComboBox<>();
    private DmPrivacy origPrivacy;
    private final ScrollPane pane;

    private final Service<MainUserSummary> saveService = new Service<>() {
        @Override
        protected Task<MainUserSummary> createTask() {
            final DmPrivacy selected = dmPrivacyCombo.getValue();
            UserUpdateRequest req = UserUpdateRequest.builder()
                    .dmPrivacy(selected)
                    .build();
            return new Task<>() {
                @Override
                protected MainUserSummary call() throws Exception {
                    return App.getServices().hub().getUserService().updateUser(req);
                }
            };
        }
    };

    public PrivacyUserTab(UserSettingsContext ctx) {
        this.ctx = ctx;
        MainUserSummary user = ctx.user();
        this.origPrivacy = (user != null && user.getDmPrivacy() != null)
                ? user.getDmPrivacy() : DmPrivacy.EVERYONE;

        dmPrivacyCombo.getItems().setAll(DmPrivacy.values());
        dmPrivacyCombo.setValue(origPrivacy);
        dmPrivacyCombo.setMaxWidth(Double.MAX_VALUE);
        dmPrivacyCombo.setConverter(new StringConverter<>() {
            @Override public String toString(DmPrivacy p) { return p == null ? "" : p.getLabel(); }
            @Override public DmPrivacy fromString(String s) { return null; }
        });
        dmPrivacyCombo.valueProperty().addListener((obs, o, n) -> ctx.refreshSaveButton());

        this.pane = buildPane();

        saveService.runningProperty().addListener((obs, was, now) -> {
            ctx.setSaving(now);
            ctx.refreshSaveButton();
        });
    }

    @Override public String name() { return "Privacy"; }
    @Override public String description() { return "Control who can reach you and see your activity"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignS.SHIELD_LOCK_OUTLINE); }
    @Override public Node getPane() { return pane; }
    @Override public boolean participatesInSave() { return true; }
    @Override public String saveButtonText() { return "Save Privacy"; }
    @Override public boolean isBusy() { return saveService.isRunning(); }
    @Override public boolean isDirty() { return dmPrivacyCombo.getValue() != origPrivacy; }

    @Override
    public void save() {
        DmPrivacy selected = dmPrivacyCombo.getValue();
        if (selected == null) return;

        saveService.setOnSucceeded(e -> {
            MainUserSummary updated = saveService.getValue();
            App.setUser(updated);
            AppState.syncStatusFromUser(updated);
            origPrivacy = updated.getDmPrivacy() != null ? updated.getDmPrivacy() : selected;
            ctx.refreshSaveButton();
            new CustomNotification("Settings Saved", "Your privacy preferences have been updated.",
                    new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE)).showNotification();
        });
        saveService.setOnFailed(e -> new CustomNotification(
                "Save Failed",
                HttpStatusException.extractMessage(saveService.getException()),
                new FontIcon(MaterialDesignA.ALERT_OUTLINE)).showNotification());
        saveService.restart();
    }

    private ScrollPane buildPane() {
        Label desc = new Label(
                "Choose who is allowed to send you direct messages. This is checked when a "
                        + "message is sent, so changing it takes effect immediately.");
        desc.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        desc.setWrapText(true);

        VBox pane = new VBox(16);
        pane.setPadding(new Insets(20, 28, 20, 28));
        pane.setAlignment(Pos.TOP_LEFT);
        pane.getChildren().addAll(
                sectionLabel("Direct Messages"),
                new VBox(6, new Label("Allow messages from"), dmPrivacyCombo, desc)
        );
        return wrapScroll(pane);
    }
}
