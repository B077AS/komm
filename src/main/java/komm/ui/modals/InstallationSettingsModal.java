package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.model.dto.summary.InstallationSummary;
import komm.ui.modals.installationsettings.*;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.LinkedHashMap;
import java.util.Map;

public class InstallationSettingsModal extends HBox {

    private final InstallationSettingsContext ctx;

    private final GeneralInstallationTab generalTab;
    private final HostedServersTab hostedServersTab;
    private final AccessTokensTab accessTokensTab;

    private VBox tabNav;
    private StackPane tabContent;
    private final Map<InstallationSettingsTab, VBox> navItems = new LinkedHashMap<>();
    private VBox activeNavItem;
    private InstallationSettingsTab activeTab;

    private Button closeButton;

    private Label titleLabel;
    private Label subtitleLabel;

    public InstallationSettingsModal(InstallationSummary installation) {
        this.ctx = new InstallationSettingsContext(installation);

        this.generalTab       = new GeneralInstallationTab(ctx);
        this.hostedServersTab  = new HostedServersTab(ctx);
        this.accessTokensTab   = installation.isOwner() ? new AccessTokensTab(ctx) : null;

        setAlignment(Pos.TOP_LEFT);
        getStyleClass().add("custom-modal");
        setMaxSize(860, 530);
        setMinSize(860, 530);
        setPrefSize(860, 530);
        setSpacing(0);

        tabContent = new StackPane();
        tabContent.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(tabContent, Priority.ALWAYS);

        Separator vDivider = new Separator(Orientation.VERTICAL);
        vDivider.setPadding(new Insets(0));

        VBox rightColumn = createRightColumn();
        HBox.setHgrow(rightColumn, Priority.ALWAYS);

        getChildren().addAll(createLeftPanel(), vDivider, rightColumn);

        switchTo(generalTab);
    }

    // ── Left panel ────────────────────────────────────────────────────────────

    private VBox createLeftPanel() {
        VBox pane = new VBox(0);
        pane.setPrefWidth(230);
        pane.setMinWidth(230);
        pane.setMaxWidth(230);
        pane.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 12px 0 0 12px;");

        VBox iconSection = new VBox(0);
        iconSection.setPadding(new Insets(24, 20, 18, 20));
        iconSection.setAlignment(Pos.CENTER);
        iconSection.getChildren().add(buildIconAvatar());

        tabNav = createTabNav();
        VBox.setVgrow(tabNav, Priority.ALWAYS);

        pane.getChildren().addAll(iconSection, new Separator(Orientation.HORIZONTAL), tabNav);
        return pane;
    }

    private StackPane buildIconAvatar() {
        StackPane pane = new StackPane();
        pane.setMinSize(80, 80);
        pane.setMaxSize(80, 80);
        pane.setPrefSize(80, 80);
        pane.setStyle(
                "-fx-background-color: -color-accent-subtle;" +
                "-fx-background-radius: 18px;" +
                "-fx-border-color: -color-accent-muted;" +
                "-fx-border-radius: 18px;" +
                "-fx-border-width: 1.5px;"
        );
        FontIcon icon = new FontIcon(MaterialDesignS.SERVER);
        icon.setIconSize(34);
        pane.getChildren().add(icon);
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

        addNavItem(generalTab);
        addNavItem(hostedServersTab);
        if (accessTokensTab != null) addNavItem(accessTokensTab);
        return nav;
    }

    private void addNavItem(InstallationSettingsTab tab) {
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
    }

    private void switchTo(InstallationSettingsTab tab) {
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

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancelButton = new Button("Close");
        cancelButton.setFocusTraversable(false);
        cancelButton.getStyleClass().add(Styles.SMALL);
        cancelButton.setOnAction(e -> App.closeModal());

        footer.getChildren().addAll(spacer, cancelButton);
        return footer;
    }
}
