package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.model.dto.summary.ServerPermissionsSummary;
import komm.model.dto.summary.ServerSummary;
import komm.model.permissions.Permission;
import komm.model.permissions.PermissionManager;
import komm.ui.avatar.AvatarPreviewWidget;
import komm.ui.modals.serversettings.*;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server settings shell. Owns the avatar, nav, header and footer, and delegates each section to its own
 * {@link ServerSettingsTab} (see {@code komm.ui.modals.serversettings}). The General and Permissions tabs are
 * available immediately; the Bans and Invites tabs are added once fresh permissions confirm access.
 */
@Slf4j
public class EditServerModal extends HBox {

    private final ServerSummary serverDetails;
    private final boolean allowEdit;
    private final boolean canViewPermsTab;

    private final AvatarPreviewWidget avatarWidget;
    private final PermissionManager localPermManager = new PermissionManager();
    private final ServerSettingsContext ctx;

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private GeneralTab generalTab;
    private PermissionsTab permissionsTab;
    private BansTab bansTab;
    private InvitesTab invitesTab;
    private DangerZoneTab dangerZoneTab;

    // ── Nav / content ─────────────────────────────────────────────────────────
    private VBox tabNav;
    private StackPane tabContent;
    private final Map<ServerSettingsTab, VBox> navItems = new LinkedHashMap<>();
    private VBox activeNavItem;
    private ServerSettingsTab activeTab;

    // ── Header ────────────────────────────────────────────────────────────────
    private Label titleLabel;
    private Label subtitleLabel;

    // ── Footer ────────────────────────────────────────────────────────────────
    private Button cancelButton;
    private Button saveButton;
    private Button closeButton;
    private ProgressIndicator progressIndicator;

    private final Service<ServerPermissionsSummary> loadPermsService = new Service<>() {
        @Override
        protected Task<ServerPermissionsSummary> createTask() {
            return new Task<>() {
                @Override
                protected ServerPermissionsSummary call() throws Exception {
                    return App.getServices().hub().getHubPermissionService()
                            .getServerPermissions(serverDetails.getServerId());
                }
            };
        }
    };

    public EditServerModal(ServerSummary serverDetails, boolean allowEdit) {
        this.serverDetails = serverDetails;
        java.util.List<String> ep = serverDetails.getEffectivePermissions() != null
                ? serverDetails.getEffectivePermissions() : java.util.List.of();
        boolean isOwner = serverDetails.getRole() == ServerSummary.Role.OWNER;
        this.allowEdit = isOwner || (allowEdit && ep.contains(Permission.EDIT_SERVER_INFO.name()));
        this.canViewPermsTab = isOwner || ep.contains(Permission.EDIT_SERVER_PERMS.name());

        setAlignment(Pos.TOP_LEFT);
        getStyleClass().add("custom-modal");
        setMaxSize(950, 680);
        setMinSize(950, 680);
        setPrefSize(950, 680);
        setSpacing(0);

        this.avatarWidget = buildAvatarWidget();
        this.ctx = new ServerSettingsContext(serverDetails, this.allowEdit, this.canViewPermsTab,
                avatarWidget, localPermManager);
        ctx.setSaveButtonRefresher(this::updateSaveButton);
        ctx.setSavingStateHandler(this::setSaving);
        ctx.setPermissionsReloader(() -> { loadPermsService.reset(); startPermsLoad(); });
        if (this.allowEdit) avatarWidget.setOnUpload(this::updateSaveButton);

        if (this.allowEdit) generalTab = new GeneralTab(ctx);
        if (this.canViewPermsTab) permissionsTab = new PermissionsTab(ctx);

        tabContent = new StackPane();
        tabContent.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(tabContent, Priority.ALWAYS);

        Separator vDivider = new Separator(Orientation.VERTICAL);
        vDivider.setPadding(new Insets(0));

        VBox rightColumn = createRightColumn();
        HBox.setHgrow(rightColumn, Priority.ALWAYS);

        getChildren().addAll(createLeftPanel(), vDivider, rightColumn);

        if (generalTab != null) progressIndicator.visibleProperty().bind(generalTab.busyBinding());
        else progressIndicator.setVisible(false);

        // Initial active tab
        if (generalTab != null) switchTo(generalTab);
        else if (permissionsTab != null) switchTo(permissionsTab);

        startPermsLoad();
    }

    // ── Permissions load ───────────────────────────────────────────────────────

    private void startPermsLoad() {
        loadPermsService.setOnSucceeded(e -> {
            ServerPermissionsSummary summary = loadPermsService.getValue();
            if (summary == null) return;
            localPermManager.load(summary, serverDetails.getRole());
            if (permissionsTab != null) permissionsTab.applyFreshPermissions(summary);
            addDynamicTabs();
        });
        loadPermsService.setOnFailed(e -> loadPermsService.getException().printStackTrace());
        loadPermsService.start();
    }

    /** Add the Bans / Invites tabs once fresh permissions confirm the caller may use them. */
    private void addDynamicTabs() {
        if (bansTab == null && localPermManager.has(Permission.BAN_USERS)) {
            bansTab = new BansTab(ctx);
            addNavItem(bansTab);
            if (activeTab == null) switchTo(bansTab);
        }
        if (invitesTab == null && localPermManager.has(Permission.DELETE_INVITES)) {
            invitesTab = new InvitesTab(ctx);
            addNavItem(invitesTab);
            if (activeTab == null) switchTo(invitesTab);
        }
        if (dangerZoneTab == null && localPermManager.has(Permission.DELETE_SERVER)) {
            dangerZoneTab = new DangerZoneTab(ctx);
            addNavItem(dangerZoneTab);
            if (activeTab == null) switchTo(dangerZoneTab);
        }
    }

    // ── Avatar ─────────────────────────────────────────────────────────────────

    private AvatarPreviewWidget buildAvatarWidget() {
        byte[] imageBytes = resolveAvatarBytes();
        String serverName = (serverDetails != null) ? serverDetails.getServerName() : null;
        String fmt = (imageBytes != null && imageBytes.length > 0)
                ? (serverDetails.getAvatarImageFormat() != null ? serverDetails.getAvatarImageFormat() : "png")
                : null;
        return AvatarPreviewWidget.configure()
                .previewSize(160)
                .allowUpload(allowEdit)
                .initialBytes(imageBytes)
                .initialFormat(fmt)
                .letterFallbackName(fmt == null ? serverName : null)
                .build();
    }

    private byte[] resolveAvatarBytes() {
        if (serverDetails == null) return null;
        byte[] bytes = serverDetails.getAvatarBytes();
        if ((bytes == null || bytes.length == 0) && serverDetails.getAvatar() != null) {
            try {
                bytes = Base64.getDecoder().decode(serverDetails.getAvatar());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return bytes;
    }

    // ── Left panel + nav ────────────────────────────────────────────────────────

    private VBox createLeftPanel() {
        VBox pane = new VBox(0);
        pane.setPrefWidth(240);
        pane.setMinWidth(240);
        pane.setMaxWidth(240);
        pane.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 12px 0 0 12px;");

        VBox avatarSection = new VBox(0);
        avatarSection.setPadding(new Insets(24, 20, 18, 20));
        avatarSection.setAlignment(Pos.CENTER);
        avatarSection.getChildren().add(avatarWidget);

        tabNav = createTabNav();
        VBox.setVgrow(tabNav, Priority.ALWAYS);

        pane.getChildren().addAll(avatarSection, new Separator(Orientation.HORIZONTAL), tabNav);
        return pane;
    }

    private VBox createTabNav() {
        VBox nav = new VBox(2);
        nav.setPadding(new Insets(12, 8, 12, 8));

        Label navLabel = new Label("SETTINGS");
        navLabel.setStyle(
                "-fx-font-size: 10px; -fx-font-weight: bold;" +
                        "-fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 4px 10px;");
        nav.getChildren().add(navLabel);
        this.tabNav = nav;

        if (generalTab != null) addNavItem(generalTab);
        if (permissionsTab != null) addNavItem(permissionsTab);
        return nav;
    }

    private VBox addNavItem(ServerSettingsTab tab) {
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

    private void switchTo(ServerSettingsTab tab) {
        VBox item = navItems.get(tab);
        if (item == null) return;
        if (activeNavItem != item) {
            if (activeNavItem != null) setNavInactive(activeNavItem);
            setNavActive(item);
            activeNavItem = item;
        }
        activeTab = tab;
        if (titleLabel != null) titleLabel.setText(tab.name());
        if (subtitleLabel != null) subtitleLabel.setText(tab.description());
        tabContent.getChildren().setAll(tab.getPane());
        tab.onShown();
        updateSaveButton();
    }

    private void setNavActive(VBox item) {
        item.getStyleClass().remove("nav-inactive");
        if (!item.getStyleClass().contains("nav-active")) item.getStyleClass().add("nav-active");
    }

    private void setNavInactive(VBox item) {
        item.getStyleClass().remove("nav-active");
        if (!item.getStyleClass().contains("nav-inactive")) item.getStyleClass().add("nav-inactive");
    }

    // ── Right column (header / content / footer) ─────────────────────────────────

    private VBox createRightColumn() {
        VBox column = new VBox(0);
        column.setAlignment(Pos.TOP_LEFT);
        column.getChildren().addAll(createHeader(), tabContent, createFooter());
        return column;
    }

    private HBox createHeader() {
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

    private HBox createFooter() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 24, 14, 24));
        footer.setStyle("-fx-border-color: -color-border-default; -fx-border-width: 1px 0 0 0;");

        progressIndicator = new ProgressIndicator();
        progressIndicator.setMaxSize(16, 16);
        progressIndicator.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        cancelButton = new Button("Cancel");
        cancelButton.setFocusTraversable(false);
        cancelButton.getStyleClass().add(Styles.SMALL);
        cancelButton.setOnAction(e -> App.closeModal());

        footer.getChildren().addAll(spacer, progressIndicator, cancelButton);

        if (allowEdit || canViewPermsTab) {
            saveButton = new Button("Save Changes");
            saveButton.setFocusTraversable(false);
            saveButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
            saveButton.setDisable(true);
            saveButton.setOnAction(e -> {
                if (activeTab != null && activeTab.participatesInSave()) activeTab.save();
            });
            footer.getChildren().add(saveButton);
        }
        return footer;
    }

    // ── Shell callbacks ──────────────────────────────────────────────────────────

    private void updateSaveButton() {
        if (saveButton == null) return;
        if (activeTab != null && activeTab.participatesInSave()) {
            saveButton.setText(activeTab.saveButtonText());
            saveButton.setDisable(!activeTab.isDirty() || activeTab.isBusy());
        } else {
            saveButton.setText("Save Changes");
            saveButton.setDisable(true);
        }
    }

    private void setSaving(boolean saving) {
        App.getModalPane().setPersistent(saving);
        if (cancelButton != null) cancelButton.setDisable(saving);
        if (closeButton != null) closeButton.setDisable(saving);
        updateSaveButton();
    }
}
