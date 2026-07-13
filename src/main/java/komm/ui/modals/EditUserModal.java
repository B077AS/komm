package komm.ui.modals;

import atlantafx.base.controls.ModalPane;
import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.model.dto.summary.MainUserSummary;
import komm.ui.avatar.AvatarPreviewWidget;
import komm.ui.modals.usersettings.*;
import komm.ui.utils.IconColorUtil;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User settings shell. Owns the avatar widget, nav, header and footer, and delegates each section
 * to its own {@link UserSettingsTab} (see {@code komm.ui.modals.usersettings}).
 */
public class EditUserModal extends HBox {

    private final UserSettingsContext ctx;

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private final GeneralUserTab generalTab;
    private final AudioUserTab audioTab;
    private final NotificationsUserTab notificationsTab;
    private final PrivacyUserTab privacyTab;
    private final KeybindingsUserTab keybindingsTab;
    private final AppearanceUserTab appearanceTab;
    private final InfoUserTab infoTab;

    // ── Nav / content ─────────────────────────────────────────────────────────
    private VBox tabNav;
    private StackPane tabContent;
    private final Map<UserSettingsTab, VBox> navItems = new LinkedHashMap<>();
    private VBox activeNavItem;
    private UserSettingsTab activeTab;

    // ── Header ────────────────────────────────────────────────────────────────
    private Label titleLabel;
    private Label subtitleLabel;

    // ── Footer ────────────────────────────────────────────────────────────────
    private Button cancelButton;
    private Button saveButton;
    private Button closeButton;
    private ProgressIndicator progressIndicator;
    private HBox footer;

    private boolean disposed = false;

    public EditUserModal() {
        MainUserSummary user = App.getUser();

        byte[] imageBytes = (user != null) ? user.getAvatar() : null;
        String username = (user != null) ? user.getUsername() : null;
        String fmt = (imageBytes != null && imageBytes.length > 0)
                ? (user.getAvatarImageFormat() != null ? user.getAvatarImageFormat() : "png") : null;

        AvatarPreviewWidget avatarWidget = AvatarPreviewWidget.configure()
                .previewSize(160)
                .allowUpload(true)
                .initialBytes(imageBytes)
                .initialFormat(fmt)
                .letterFallbackName(fmt == null ? username : null)
                .build();

        this.ctx = new UserSettingsContext(user, avatarWidget);
        ctx.setSaveButtonRefresher(this::updateSaveButton);
        ctx.setSavingStateHandler(this::setSaving);

        generalTab = new GeneralUserTab(ctx);
        audioTab = new AudioUserTab(ctx);
        notificationsTab = new NotificationsUserTab(ctx);
        privacyTab = new PrivacyUserTab(ctx);
        keybindingsTab = new KeybindingsUserTab(ctx);
        appearanceTab = new AppearanceUserTab();
        infoTab = new InfoUserTab();

        setAlignment(Pos.TOP_LEFT);
        getStyleClass().add("custom-modal");
        setMaxSize(800, 620);
        setMinSize(800, 620);
        setPrefSize(800, 620);
        setSpacing(0);

        tabContent = new StackPane();
        tabContent.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(tabContent, Priority.ALWAYS);

        Separator vDivider = new Separator(Orientation.VERTICAL);
        vDivider.setPadding(new Insets(0));

        VBox rightColumn = createRightColumn();
        HBox.setHgrow(rightColumn, Priority.ALWAYS);

        getChildren().addAll(createLeftPanel(avatarWidget), vDivider, rightColumn);

        progressIndicator.setVisible(false);
        switchTo(generalTab);

        // AtlantaFX's own ESC / click-outside-to-close handling calls the ModalPane's internal
        // hide() WITHOUT clearing its content (only App.closeModal()'s hide(true) does that),
        // so sceneProperty() alone never goes null when the modal is dismissed that way. The
        // one signal that fires on every dismissal path is the hosting ModalPane's displayProperty
        // flipping to false, so walk up to find it as soon as we're attached to the scene.
        sceneProperty().addListener((obs, o, n) -> {
            if (n == null) {
                disposeOnce();
                return;
            }
            Parent p = getParent();
            while (p != null && !(p instanceof ModalPane)) p = p.getParent();
            if (p instanceof ModalPane hostPane) {
                hostPane.displayProperty().addListener((obs2, was, isShowing) -> {
                    if (!isShowing) disposeOnce();
                });
            }
        });
    }

    private void disposeOnce() {
        if (disposed) return;
        disposed = true;
        dispose();
    }

    // ── Left panel + nav ──────────────────────────────────────────────────────

    private VBox createLeftPanel(AvatarPreviewWidget avatarWidget) {
        VBox pane = new VBox(0);
        pane.setPrefWidth(240);
        pane.setMinWidth(240);
        pane.setMaxWidth(240);
        pane.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 12px 0 0 12px;");

        VBox avatarSection = new VBox(0);
        avatarSection.setPadding(new Insets(24, 20, 18, 20));
        avatarSection.setAlignment(Pos.CENTER);
        avatarSection.getChildren().add(avatarWidget);

        Label navLabel = new Label("SETTINGS");
        navLabel.setStyle(
                "-fx-font-size: 10px; -fx-font-weight: bold;" +
                        "-fx-text-fill: -color-fg-subtle; -fx-padding: 12px 0 4px 18px;");

        tabNav = createTabNav();

        ScrollPane navScroll = new ScrollPane(tabNav);
        navScroll.setFitToWidth(true);
        navScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(navScroll, Priority.ALWAYS);

        pane.getChildren().addAll(avatarSection, new Separator(Orientation.HORIZONTAL), navLabel, navScroll);
        return pane;
    }

    private VBox addLogoutNavItem() {
        FontIcon icon = IconColorUtil.colored(MaterialDesignL.LOGOUT, "-color-danger-fg", 15);
        Label lbl = new Label("Log Out");
        lbl.getStyleClass().add("nav-label");
        lbl.setStyle("-fx-text-fill: -color-danger-fg;");

        HBox row = new HBox(10, icon, lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox item = new VBox(row);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(9, 12, 9, 12));
        item.getStyleClass().add("nav-item");
        item.setOnMouseClicked(e -> confirmLogout());
        tabNav.getChildren().add(item);
        return item;
    }

    private void confirmLogout() {
        App.showModal(new ConfirmationModal(
                "Log Out",
                "Are you sure you want to log out?",
                new FontIcon(MaterialDesignL.LOGOUT),
                () -> {
                    App.logout();
                    App.closeModal();
                }));
    }

    private VBox createTabNav() {
        VBox nav = new VBox(2);
        nav.setPadding(new Insets(0, 8, 12, 8));
        this.tabNav = nav;

        addNavItem(generalTab);
        addNavItem(audioTab);
        addNavItem(notificationsTab);
        addNavItem(privacyTab);
        addNavItem(keybindingsTab);
        addNavItem(appearanceTab);
        addNavItem(infoTab);
        addLogoutNavItem();
        return nav;
    }

    private VBox addNavItem(UserSettingsTab tab) {
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

    private void switchTo(UserSettingsTab tab) {
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
        if (footer != null) {
            boolean show = tab.participatesInSave();
            footer.setVisible(show);
            footer.setManaged(show);
        }
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

    // ── Right column ──────────────────────────────────────────────────────────

    private VBox createRightColumn() {
        VBox column = new VBox(0);
        column.setAlignment(Pos.TOP_LEFT);
        footer = createFooter();
        column.getChildren().addAll(createHeader(), tabContent, footer);
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

        saveButton = new Button("Save General");
        saveButton.setFocusTraversable(false);
        saveButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        saveButton.setDisable(true);
        saveButton.setOnAction(e -> {
            if (activeTab != null && activeTab.participatesInSave()) activeTab.save();
        });

        footer.getChildren().addAll(progressIndicator, spacer, cancelButton, saveButton);
        return footer;
    }

    // ── Shell callbacks ───────────────────────────────────────────────────────

    private void updateSaveButton() {
        if (saveButton == null) return;
        if (activeTab != null && activeTab.participatesInSave()) {
            saveButton.setText(activeTab.saveButtonText());
            saveButton.setDisable(!activeTab.isDirty() || activeTab.isBusy());
        } else {
            saveButton.setText("Save");
            saveButton.setDisable(true);
        }
        if (progressIndicator != null)
            progressIndicator.setVisible(activeTab != null && activeTab.isBusy());
    }

    private void setSaving(boolean saving) {
        if (cancelButton != null) cancelButton.setDisable(saving);
        if (closeButton != null) closeButton.setDisable(saving);
        updateSaveButton();
    }

    private void dispose() {
        generalTab.dispose();
        audioTab.dispose();
        notificationsTab.dispose();
        keybindingsTab.dispose();
    }
}
