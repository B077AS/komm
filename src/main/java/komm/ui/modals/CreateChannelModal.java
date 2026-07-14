package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.request.ChannelCreateRequest;
import komm.model.dto.summary.ChannelSummary;
import komm.model.dto.summary.ChannelSummary.ChannelType;
import komm.model.dto.summary.ServerSummary;
import komm.model.permissions.Permission;
import komm.ui.modals.createchannel.ChannelSettingsContext;
import komm.ui.modals.createchannel.ChannelSettingsTab;
import komm.ui.modals.createchannel.GeneralChannelTab;
import komm.ui.modals.createchannel.IconChannelTab;
import komm.ui.modals.createchannel.PermissionsChannelTab;
import komm.ui.modals.createchannel.UserExceptionsChannelTab;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Create/edit-channel shell. Owns the nav, header and footer, and delegates each section to its
 * own {@link ChannelSettingsTab} (see {@code komm.ui.modals.createchannel}).
 *
 * <p>Edit mode mirrors {@code EditUserModal}/{@code EditServerModal}: each tab saves
 * independently via the shared footer Save button. Create mode has no per-tab save — instead the
 * single "Create Channel" button pulls the current name/type/icon/permissions straight off the
 * tab instances and builds one {@link ChannelCreateRequest}.
 */
public class CreateChannelModal extends HBox {

    private static final double MODAL_WIDTH = 900;
    private static final double MODAL_HEIGHT = 580;
    private static final double NAV_WIDTH = 200;

    private final ServerSummary server;
    private final boolean isEditMode;
    private final ChannelSummary editChannel;

    private ChannelSettingsContext ctx;

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private GeneralChannelTab generalTab;
    private IconChannelTab iconTab;
    private PermissionsChannelTab permissionsTab;
    private UserExceptionsChannelTab userExceptionsTab;

    // ── Nav / content ─────────────────────────────────────────────────────────
    private VBox tabNav;
    private StackPane contentArea;
    private final Map<ChannelSettingsTab, VBox> navItems = new LinkedHashMap<>();
    private VBox activeNavItem;
    private ChannelSettingsTab activeTab;

    // ── Header ────────────────────────────────────────────────────────────────
    private Label titleLabel;
    private Label subtitleLabel;

    // ── Footer ────────────────────────────────────────────────────────────────
    private Button cancelButton;
    private Button createButton;
    private Button saveButton;
    private Button closeButton;
    private ProgressIndicator progressIndicator;

    // ── Create service ────────────────────────────────────────────────────────
    private ChannelCreateRequest currentRequest;

    private final Service<ChannelSummary> createChannelService = new Service<>() {
        @Override
        protected Task<ChannelSummary> createTask() {
            return new Task<>() {
                @Override
                protected ChannelSummary call() throws Exception {
                    return App.getServices().installation().getChannelService().createChannel(currentRequest);
                }
            };
        }
    };

    public CreateChannelModal(ServerSummary server) {
        this.server = server;
        this.isEditMode = false;
        this.editChannel = null;
        setup();
    }

    public CreateChannelModal(ServerSummary server, ChannelSummary channelToEdit) {
        this.server = server;
        this.isEditMode = true;
        this.editChannel = channelToEdit;
        setup();
    }

    private void setup() {
        setAlignment(Pos.TOP_LEFT);
        getStyleClass().add("custom-modal");
        setMinSize(MODAL_WIDTH, MODAL_HEIGHT);
        setMaxSize(MODAL_WIDTH, MODAL_HEIGHT);
        setPrefSize(MODAL_WIDTH, MODAL_HEIGHT);
        setSpacing(0);

        ctx = new ChannelSettingsContext(server, isEditMode, editChannel);
        ctx.setSaveButtonRefresher(this::updateSaveButton);
        ctx.setSavingStateHandler(this::setSaving);

        generalTab = new GeneralChannelTab(ctx);
        iconTab = new IconChannelTab(ctx);
        permissionsTab = new PermissionsChannelTab(ctx);
        userExceptionsTab = new UserExceptionsChannelTab(ctx);

        VBox navPanel = buildNavPanel();
        Separator vDivider = new Separator(Orientation.VERTICAL);
        vDivider.setPadding(new Insets(0));
        VBox rightColumn = buildRightColumn();
        HBox.setHgrow(rightColumn, Priority.ALWAYS);

        getChildren().addAll(navPanel, vDivider, rightColumn);

        if (!isEditMode) {
            createChannelService.runningProperty().addListener((obs, wasRunning, isRunning) -> {
                App.getModalPane().setPersistent(isRunning);
                cancelButton.setDisable(isRunning);
                closeButton.setDisable(isRunning);
            });
            createButton.disableProperty().bind(createChannelService.runningProperty());
            progressIndicator.visibleProperty().bind(createChannelService.runningProperty());
        }

        switchTo(generalTab);
    }

    // ── Left nav panel ────────────────────────────────────────────────────────

    private VBox buildNavPanel() {
        VBox panel = new VBox(0);
        panel.setPrefWidth(NAV_WIDTH);
        panel.setMinWidth(NAV_WIDTH);
        panel.setMaxWidth(NAV_WIDTH);
        panel.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 12px 0 0 12px;");

        VBox nav = new VBox(2);
        nav.setPadding(new Insets(12, 8, 12, 8));
        this.tabNav = nav;

        Label navLabel = new Label("SETTINGS");
        navLabel.setStyle(
                "-fx-font-size: 10px; -fx-font-weight: bold;" +
                        "-fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 4px 10px;");
        nav.getChildren().add(navLabel);

        addNavItem(generalTab);
        addNavItem(iconTab);
        VBox permissionsNavItem = addNavItem(permissionsTab);
        VBox userExceptionsNavItem = addNavItem(userExceptionsTab);

        boolean canEditChannelPerms = App.getPermissionManager().has(Permission.EDIT_CHANNEL_PERMS);
        if (!canEditChannelPerms) {
            disableNavItem(permissionsNavItem);
            disableNavItem(userExceptionsNavItem);
        }
        if (!isEditMode) {
            disableNavItem(userExceptionsNavItem);
        }

        panel.getChildren().add(nav);
        return panel;
    }

    private void disableNavItem(VBox item) {
        item.setDisable(true);
        item.setOpacity(0.45);
    }

    private VBox addNavItem(ChannelSettingsTab tab) {
        Label lbl = new Label(tab.name());
        lbl.getStyleClass().add("nav-label");
        HBox row = new HBox(10, tab.icon(), lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox item = new VBox(row);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(9, 12, 9, 12));
        item.getStyleClass().add("nav-item");
        item.setOnMouseClicked(e -> switchTo(tab));
        navItems.put(tab, item);
        tabNav.getChildren().add(item);
        return item;
    }

    private void switchTo(ChannelSettingsTab tab) {
        VBox item = navItems.get(tab);
        if (item == null) return;
        if (activeNavItem != item) {
            if (activeNavItem != null) setNavInactive(activeNavItem);
            setNavActive(item);
            activeNavItem = item;
        }
        activeTab = tab;
        updateHeaderText(tab);
        contentArea.getChildren().setAll(tab.getPane());
        tab.onShown();
        if (isEditMode) updateSaveButton();
    }

    private void setNavActive(VBox item) {
        item.getStyleClass().remove("nav-inactive");
        if (!item.getStyleClass().contains("nav-active")) item.getStyleClass().add("nav-active");
    }

    private void setNavInactive(VBox item) {
        item.getStyleClass().remove("nav-active");
        if (!item.getStyleClass().contains("nav-inactive")) item.getStyleClass().add("nav-inactive");
    }

    // ── Right column ──────────────────────────────────────────────────────────

    private VBox buildRightColumn() {
        contentArea = new StackPane();
        contentArea.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        VBox column = new VBox(0);
        column.setAlignment(Pos.TOP_LEFT);
        column.getChildren().addAll(buildHeader(), contentArea, buildFooter());
        return column;
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.TOP_LEFT);
        header.setPadding(new Insets(18, 16, 14, 24));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");

        titleLabel = new Label();
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        subtitleLabel = new Label();
        subtitleLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        VBox titleBox = new VBox(2, titleLabel, subtitleLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        closeButton = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeButton.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeButton.setOnAction(e -> App.closeModal());

        header.getChildren().addAll(titleBox, spacer, closeButton);
        return header;
    }

    private void updateHeaderText(ChannelSettingsTab tab) {
        titleLabel.setText(tab.name());
        String modeVerb = isEditMode ? "Editing" : "Creating";
        subtitleLabel.setText(tab.description() + " — " + modeVerb + " in " + server.getServerName());
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private HBox buildFooter() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 24, 14, 24));
        footer.setStyle("-fx-border-color: -color-border-default transparent transparent transparent; -fx-border-width: 1 0 0 0;");

        progressIndicator = new ProgressIndicator();
        progressIndicator.setMaxSize(16, 16);
        progressIndicator.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        cancelButton = new Button("Cancel");
        cancelButton.setFocusTraversable(false);
        cancelButton.getStyleClass().add(Styles.SMALL);
        cancelButton.setOnAction(e -> App.closeModal());

        if (isEditMode) {
            saveButton = new Button("Save General");
            saveButton.setFocusTraversable(false);
            saveButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
            saveButton.setDisable(true);
            saveButton.setOnAction(e -> {
                if (activeTab != null && activeTab.participatesInSave()) activeTab.save();
            });
            footer.getChildren().addAll(spacer, progressIndicator, cancelButton, saveButton);
        } else {
            createButton = new Button("Create Channel");
            createButton.setFocusTraversable(false);
            createButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
            createButton.setOnAction(e -> handleCreate());
            footer.getChildren().addAll(spacer, progressIndicator, cancelButton, createButton);
        }

        return footer;
    }

    // ── Shell callbacks ───────────────────────────────────────────────────────

    private void updateSaveButton() {
        if (saveButton == null) return;
        if (activeTab != null && activeTab.participatesInSave()) {
            saveButton.setText(activeTab.saveButtonText());
            saveButton.setDisable(!activeTab.isDirty() || activeTab.isBusy());
        } else {
            saveButton.setText("Save Changes");
            saveButton.setDisable(true);
        }
        if (progressIndicator != null) progressIndicator.setVisible(activeTab != null && activeTab.isBusy());
    }

    private void setSaving(boolean saving) {
        App.getModalPane().setPersistent(saving);
        if (cancelButton != null) cancelButton.setDisable(saving);
        if (closeButton != null) closeButton.setDisable(saving);
        updateSaveButton();
    }

    // ── Create ────────────────────────────────────────────────────────────────

    private void handleCreate() {
        String name = generalTab.getChannelName();
        if (name.isEmpty()) {
            new CustomNotification("Validation Error", "Channel name is required.", new FontIcon(MaterialDesignP.POUND))
                    .showNotification();
            generalTab.focusNameField();
            return;
        }
        if (name.length() > 100) {
            new CustomNotification("Validation Error", "Channel name must be 100 characters or fewer.", new FontIcon(MaterialDesignP.POUND))
                    .showNotification();
            generalTab.focusNameField();
            return;
        }

        currentRequest = ChannelCreateRequest.builder()
                .serverId(server.getServerId())
                .channelName(name)
                .channelType(ctx.isVoice() ? ChannelType.VOICE : ChannelType.TEXT)
                .icon(iconTab.getSelectedIconDescription())
                .build();

        createChannelService.setOnSucceeded(e -> {
            ChannelSummary created = createChannelService.getValue();
            if (created != null && !permissionsTab.getPendingOverrides().isEmpty()) {
                App.getServices().getExecutor().submit(() -> applyPendingOverrides(created.getChannelId()));
            }
            App.closeModal();
        });
        createChannelService.setOnFailed(e -> new CustomNotification(
                "Error",
                HttpStatusException.extractMessage(createChannelService.getException()),
                new FontIcon(MaterialDesignC.CLOSE))
                .showNotification());

        createChannelService.restart();
    }

    private void applyPendingOverrides(UUID channelId) {
        var svc = App.getServices().installation().getChannelPermissionService();
        permissionsTab.getPendingOverrides().forEach((role, overrideMap) -> {
            EnumSet<Permission> allow = EnumSet.noneOf(Permission.class);
            EnumSet<Permission> deny = EnumSet.noneOf(Permission.class);
            overrideMap.forEach((perm, state) -> {
                switch (state) {
                    case ALLOW -> allow.add(perm);
                    case DENY -> deny.add(perm);
                    case INHERIT -> {}
                }
            });
            if (allow.isEmpty() && deny.isEmpty()) return;
            try { svc.updateChannelRolePermission(channelId, role.name(), allow, deny); }
            catch (Exception ex) { ex.printStackTrace(); }
        });
        permissionsTab.getPendingCustomRoleOverrides().forEach((crId, overrideMap) -> {
            EnumSet<Permission> allow = EnumSet.noneOf(Permission.class);
            EnumSet<Permission> deny = EnumSet.noneOf(Permission.class);
            overrideMap.forEach((perm, state) -> {
                switch (state) {
                    case ALLOW -> allow.add(perm);
                    case DENY -> deny.add(perm);
                    case INHERIT -> {}
                }
            });
            if (allow.isEmpty() && deny.isEmpty()) return;
            try { svc.updateChannelCustomRolePermission(channelId, crId, allow, deny); }
            catch (Exception ex) { ex.printStackTrace(); }
        });
    }
}
