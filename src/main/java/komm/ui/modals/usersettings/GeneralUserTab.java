package komm.ui.modals.usersettings;

import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.AppState;
import komm.api.HttpStatusException;
import komm.model.dto.request.UserUpdateRequest;
import komm.model.dto.summary.BadgeSummary;
import komm.model.dto.summary.MainUserSummary;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import atlantafx.base.theme.Styles;
import komm.ui.customnodes.BadgeUi;
import komm.ui.customnodes.CustomNotification;
import komm.ui.customnodes.StatusComboBox;
import komm.ui.customnodes.StatusMessageEditor;
import komm.ui.emojis.EmojiFilterTextField;
import komm.ui.modals.RedeemBadgeModal;
import komm.ui.pages.HomePage;
import komm.ui.sections.ProfileSection;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static komm.ui.modals.usersettings.UserSettingsUi.*;

/** "General" tab: username, email, status message, status, and avatar. */
public class GeneralUserTab implements UserSettingsTab {

    private final UserSettingsContext ctx;

    private final EmojiFilterTextField usernameField = EmojiFilterTextField.username(32);
    private final TextField emailField = new TextField();
    private final StatusMessageEditor statusEditor = new StatusMessageEditor();
    private final StatusComboBox statusCombo;

    private final VBox badgesBox = new VBox();

    private String origUsername;
    private String origStatusMessage;
    private String origStatusEmoji;
    private UserStatus origStatus;

    // Captured on FX thread before service starts
    private String pendingAvatarBase64;
    private String pendingAvatarFmt;

    private final ScrollPane pane;

    private final Service<MainUserSummary> saveService = new Service<>() {
        @Override
        protected Task<MainUserSummary> createTask() {
            final String username   = usernameField.getText().trim();
            final String statusMsg  = statusEditor.getStatusText() != null ? statusEditor.getStatusText().trim() : "";
            final String statusEmoji = statusEditor.getStatusEmojiUnified();
            final UserStatus status = statusCombo.getSelectedStatus();
            final String avatarB64  = pendingAvatarBase64;
            final String avatarFmt  = pendingAvatarFmt;

            UserUpdateRequest req = UserUpdateRequest.builder()
                    .username(username).statusMessage(statusMsg)
                    .statusEmoji(statusEmoji == null ? "" : statusEmoji)
                    .status(status).avatar(avatarB64).avatarImageFormat(avatarFmt)
                    .build();

            return new Task<>() {
                @Override
                protected MainUserSummary call() throws Exception {
                    return App.getServices().hub().getUserService().updateUser(req);
                }
            };
        }
    };

    public GeneralUserTab(UserSettingsContext ctx) {
        this.ctx = ctx;
        MainUserSummary user = ctx.user();
        UserStatus currentStatus = (user != null && user.getStatus() != null)
                ? user.getStatus() : UserStatus.ONLINE;
        statusCombo = new StatusComboBox(currentStatus, false);

        this.pane = buildPane();
        populateFields();

        saveService.runningProperty().addListener((obs, was, now) -> {
            ctx.setSaving(now);
            ctx.refreshSaveButton();
        });
    }

    @Override public String name() { return "General"; }
    @Override public String description() { return "Manage your account details and profile"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignA.ACCOUNT_OUTLINE); }
    @Override public Node getPane() { return pane; }
    @Override public boolean participatesInSave() { return true; }
    @Override public String saveButtonText() { return "Save General"; }
    @Override public boolean isBusy() { return saveService.isRunning(); }

    @Override
    public boolean isDirty() {
        if (origUsername == null) return false;
        if (!usernameField.getText().trim().equals(origUsername)) return true;
        if (!statusEditor.getStatusText().trim().equals(origStatusMessage)) return true;
        if (!java.util.Objects.equals(statusEditor.getStatusEmojiUnified(), origStatusEmoji)) return true;
        if (statusCombo.getSelectedStatus() != origStatus) return true;
        return ctx.avatarWidget().hasNewUpload();
    }

    @Override
    public void save() {
        String username = usernameField.getText().trim();
        if (username.isEmpty()) {
            new CustomNotification("Validation Error", "Username is required.", new FontIcon(MaterialDesignA.ALERT_OUTLINE))
                    .showNotification();
            usernameField.requestFocus();
            return;
        }
        if (username.length() > 32) {
            new CustomNotification("Validation Error", "Username must be 32 characters or fewer.", new FontIcon(MaterialDesignA.ALERT_OUTLINE))
                    .showNotification();
            usernameField.requestFocus();
            return;
        }
        if (!username.matches("[a-zA-Z0-9_-]+")) {
            new CustomNotification("Validation Error", "Username may only contain letters, numbers, _ and -.", new FontIcon(MaterialDesignA.ALERT_OUTLINE))
                    .showNotification();
            usernameField.requestFocus();
            return;
        }

        pendingAvatarBase64 = null;
        pendingAvatarFmt = null;
        byte[] uploadedBytes = null;
        if (ctx.avatarWidget().hasNewUpload()) {
            try {
                uploadedBytes = ctx.avatarWidget().getFinalImageBytes();
                pendingAvatarBase64 = Base64.getEncoder().encodeToString(uploadedBytes);
                pendingAvatarFmt = ctx.avatarWidget().getImageFormat();
            } catch (Exception e) {
                new CustomNotification("Image Error", e.getMessage(),
                        new FontIcon(MaterialDesignA.ALERT_OUTLINE)).showNotification();
                return;
            }
        }

        // The exact bytes we just uploaded — used to refresh local UI directly, so we
        // don't depend on the server echoing the avatar back in the response.
        final byte[] newAvatarBytes = uploadedBytes;
        saveService.setOnSucceeded(e -> {
            MainUserSummary updated = saveService.getValue();
            App.setUser(updated);
            if (newAvatarBytes != null) {
                // Make sure the user model and cache hold the new bytes regardless of
                // what the response contained, then notify the live self-avatar
                // components (toolbar, own connected-user card).
                if (App.getUser() != null) App.getUser().setAvatar(newAvatarBytes);
                App.getAvatarCache().put(updated.getUserId(), updated.getUsername(), newAvatarBytes);
                AppState.notifySelfAvatarChanged();
            }
            AppState.syncStatusFromUser(updated);
            refreshProfileSection();
            App.closeModal();
            new CustomNotification("Settings Updated", "Your profile settings have been saved.", new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE))
                    .showNotification();
        });
        saveService.setOnFailed(e -> new CustomNotification(
                "Save Failed",
                HttpStatusException.extractMessage(saveService.getException()),
                new FontIcon(MaterialDesignA.ALERT_OUTLINE)).showNotification());
        saveService.restart();
    }

    private ScrollPane buildPane() {
        usernameField.setPromptText("e.g. CoolUser123");
        usernameField.setMaxWidth(Double.MAX_VALUE);
        emailField.setMaxWidth(Double.MAX_VALUE);
        emailField.setDisable(true);

        VBox pane = new VBox(20);
        pane.setPadding(new Insets(20, 28, 20, 28));
        pane.setAlignment(Pos.TOP_LEFT);
        pane.getChildren().addAll(
                new VBox(6, sectionLabel("Username"), usernameField),
                new VBox(6, sectionLabel("Email"), emailField),
                new VBox(6, sectionLabel("Status Message"), statusEditor),
                new VBox(6, sectionLabel("Status"), statusCombo),
                buildBadgesSection()
        );
        return wrapScroll(pane);
    }

    private VBox buildBadgesSection() {
        renderBadges();
        return new VBox(6, sectionLabel("Badges"), badgesBox);
    }

    private void renderBadges() {
        MainUserSummary user = App.getUser() != null ? App.getUser() : ctx.user();
        List<BadgeSummary> badges = user != null ? user.getBadges() : null;

        FlowPane flow = new FlowPane(6, 6);
        if (badges != null) {
            for (BadgeSummary badge : badges) {
                flow.getChildren().add(BadgeUi.pill(badge));
            }
        }
        flow.getChildren().add(buildAddBadgePill());
        badgesBox.getChildren().setAll(flow);
    }

    /** Small icon-only "add" affordance, styled like the refresh button on the home page. */
    private Node buildAddBadgePill() {
        Button addButton = new Button(null, new FontIcon(MaterialDesignP.PLUS));
        ((FontIcon) addButton.getGraphic()).setIconSize(13);
        addButton.setFocusTraversable(false);
        addButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        addButton.setTooltip(new Tooltip("Add Badge"));
        addButton.setStyle("-fx-padding: 4px;");
        addButton.setOnAction(e -> App.showModal(new RedeemBadgeModal(awarded -> {
            MainUserSummary user = App.getUser();
            if (user != null) {
                List<BadgeSummary> badges = user.getBadges() != null
                        ? new ArrayList<>(user.getBadges())
                        : new ArrayList<>();
                badges.add(awarded);
                user.setBadges(badges);
            }
            renderBadges();
        })));
        return addButton;
    }

    private void populateFields() {
        MainUserSummary user = ctx.user();
        if (user == null) return;

        origUsername      = user.getUsername() != null ? user.getUsername() : "";
        origStatusMessage = user.getStatusMessage() != null ? user.getStatusMessage() : "";
        origStatusEmoji   = user.getStatusEmoji();
        origStatus        = user.getStatus() != null ? user.getStatus() : UserStatus.ONLINE;

        usernameField.setText(origUsername);
        emailField.setText(user.getEmail() != null ? user.getEmail() : "");
        statusEditor.setStatusText(origStatusMessage);
        statusEditor.setStatusEmojiUnified(origStatusEmoji);

        usernameField.textProperty().addListener((obs, o, n) -> ctx.refreshSaveButton());
        statusEditor.textProperty().addListener((obs, o, n) -> ctx.refreshSaveButton());
        statusEditor.statusEmojiUnifiedProperty().addListener((obs, o, n) -> ctx.refreshSaveButton());
        statusCombo.valueProperty().addListener((obs, o, n) -> ctx.refreshSaveButton());
        ctx.avatarWidget().setOnUpload(ctx::refreshSaveButton);
    }

    private void refreshProfileSection() {
        HomePage hp = App.getCachedHomePage();
        if (hp != null) {
            ProfileSection ps = hp.getProfileSection();
            if (ps != null) ps.refresh();
        }
    }
}
