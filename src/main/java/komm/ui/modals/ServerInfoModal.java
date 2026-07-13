package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.AppState;
import komm.api.ServiceContainer;
import komm.model.dto.summary.ServerSummary;
import komm.ui.avatar.AvatarPreviewWidget;
import komm.ui.emojis.EmojiTextArea;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.Base64;

public class ServerInfoModal extends HBox {

    private AvatarPreviewWidget avatarWidget;
    private VBox tabNav;
    private StackPane tabContent;
    private VBox activeNavItem;
    private String activeTabName = "General";

    private ScrollPane generalPane;
    private ScrollPane notificationsPane;

    private HBox footer;
    private Button saveButton;
    private boolean notifDirty = false;
    private boolean pokesDirty = false;

    private CheckBox channelNotifToggle;
    private boolean origChannelNotif;

    private CheckBox pokesEnabledToggle;
    private boolean origPokesEnabled;
    private volatile boolean pendingPokesEnabled;

    private final Service<Void> savePokePrefService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().installation().getMemberService()
                            .updateMyPokePreference(pendingPokesEnabled);
                    return null;
                }
            };
        }
    };

    private final ServerSummary serverDetails;
    private final String initialTab;

    public ServerInfoModal(ServerSummary serverDetails) {
        this(serverDetails, "General");
    }

    public ServerInfoModal(ServerSummary serverDetails, String initialTab) {
        this.serverDetails = serverDetails;
        this.initialTab = initialTab;

        savePokePrefService.setOnSucceeded(e -> {
            origPokesEnabled = pendingPokesEnabled;
            pokesDirty = false;
            AppState.setPokesEnabled(pendingPokesEnabled);
            Platform.runLater(this::updateSaveButton);
        });
        savePokePrefService.setOnFailed(e -> Platform.runLater(this::updateSaveButton));

        setAlignment(Pos.TOP_LEFT);
        getStyleClass().add("custom-modal");
        setMaxSize(780, 520);
        setMinSize(780, 520);
        setPrefSize(780, 520);
        setSpacing(0);

        buildAvatarWidget();
        generalPane = buildGeneralPane();
        notificationsPane = buildNotificationsPane();

        Separator vDivider = new Separator(Orientation.VERTICAL);
        vDivider.setPadding(new Insets(0));

        VBox rightColumn = createRightColumn();
        HBox.setHgrow(rightColumn, Priority.ALWAYS);

        getChildren().addAll(createLeftPanel(), vDivider, rightColumn);
    }

    private void buildAvatarWidget() {
        byte[] imageBytes = resolveAvatarBytes();
        String serverName = (serverDetails != null) ? serverDetails.getServerName() : null;
        String fmt = (imageBytes != null && imageBytes.length > 0)
                ? (serverDetails.getAvatarImageFormat() != null ? serverDetails.getAvatarImageFormat() : "png")
                : null;
        avatarWidget = AvatarPreviewWidget.configure()
                .previewSize(160)
                .allowUpload(false)
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

        Label navLabel = new Label("INFO");
        navLabel.setStyle(
                "-fx-font-size: 10px; -fx-font-weight: bold;" +
                        "-fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 4px 10px;");

        VBox generalItem = buildNavItem(new FontIcon(MaterialDesignS.SERVER), "General", generalPane);
        VBox notifItem = buildNavItem(new FontIcon(MaterialDesignB.BELL_OUTLINE), "Notifications", notificationsPane);

        if ("Notifications".equals(initialTab)) {
            setNavActive(notifItem);
            activeNavItem = notifItem;
            activeTabName = "Notifications";
        } else {
            setNavActive(generalItem);
            activeNavItem = generalItem;
        }

        nav.getChildren().addAll(navLabel, generalItem, notifItem);
        return nav;
    }

    private VBox buildNavItem(FontIcon icon, String text, Node targetPane) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("nav-label");
        HBox row = new HBox(10, icon, lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox item = new VBox(row);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(9, 12, 9, 12));
        item.getStyleClass().add("nav-item");
        item.setOnMouseClicked(e -> {
            if (activeNavItem != item) {
                setNavInactive(activeNavItem);
                setNavActive(item);
                activeNavItem = item;
            }
            activeTabName = text;
            tabContent.getChildren().setAll(targetPane);
            updateSaveButton();
        });
        return item;
    }

    private void setNavActive(VBox item) {
        item.getStyleClass().remove("nav-inactive");
        if (!item.getStyleClass().contains("nav-active")) item.getStyleClass().add("nav-active");
    }

    private void setNavInactive(VBox item) {
        item.getStyleClass().remove("nav-active");
        if (!item.getStyleClass().contains("nav-inactive")) item.getStyleClass().add("nav-inactive");
    }

    private VBox createRightColumn() {
        VBox column = new VBox(0);
        column.setAlignment(Pos.TOP_LEFT);

        tabContent = new StackPane();
        tabContent.setAlignment(Pos.TOP_LEFT);
        tabContent.getChildren().add("Notifications".equals(initialTab) ? notificationsPane : generalPane);
        VBox.setVgrow(tabContent, Priority.ALWAYS);

        footer = createFooter();
        boolean startsOnNotifs = "Notifications".equals(initialTab);
        footer.setVisible(startsOnNotifs);
        footer.setManaged(startsOnNotifs);
        column.getChildren().addAll(createHeader(), tabContent, footer);
        return column;
    }

    private HBox createHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 16, 14, 24));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");

        Label title = new Label("Server Info");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        String displayName = (serverDetails != null && serverDetails.getServerName() != null)
                ? serverDetails.getServerName() : "Unnamed Server";
        Label subtitle = new Label("Viewing: " + displayName);
        subtitle.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeButton = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeButton.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeButton.setOnAction(e -> App.closeModal());

        header.getChildren().addAll(new VBox(2, title, subtitle), spacer, closeButton);
        return header;
    }

    private HBox createFooter() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 24, 14, 24));
        footer.setStyle("-fx-border-color: -color-border-default; -fx-border-width: 1px 0 0 0;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeButton = new Button("Close");
        closeButton.setFocusTraversable(false);
        closeButton.getStyleClass().add(Styles.SMALL);
        closeButton.setOnAction(e -> App.closeModal());

        saveButton = new Button("Save");
        saveButton.setFocusTraversable(false);
        saveButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        saveButton.setDisable(true);
        saveButton.setOnAction(e -> handleSave());

        footer.getChildren().addAll(spacer, closeButton, saveButton);
        return footer;
    }

    private void updateSaveButton() {
        if (saveButton == null) return;
        boolean isNotifs = "Notifications".equals(activeTabName);
        if (footer != null) {
            footer.setVisible(isNotifs);
            footer.setManaged(isNotifs);
        }
        saveButton.setDisable(!notifDirty && !pokesDirty);
    }

    private void handleSave() {
        if (!"Notifications".equals(activeTabName)) return;
        saveButton.setDisable(true);

        if (channelNotifToggle != null && notifDirty) {
            boolean enabled = channelNotifToggle.isSelected();
            Service<Void> svc = new Service<>() {
                @Override
                protected Task<Void> createTask() {
                    return new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            ServiceContainer.getInstance().hub().getServerService()
                                    .updateChannelNotifications(serverDetails.getServerId(), enabled);
                            return null;
                        }
                    };
                }
            };
            svc.setOnSucceeded(e -> {
                serverDetails.setChannelNotificationsEnabled(enabled);
                origChannelNotif = enabled;
                notifDirty = false;
            });
            svc.setOnFailed(e -> updateSaveButton());
            svc.start();
        }

        if (pokesEnabledToggle != null && pokesDirty) {
            pendingPokesEnabled = pokesEnabledToggle.isSelected();
            savePokePrefService.restart();
        }
    }

    private ScrollPane buildGeneralPane() {
        VBox pane = new VBox(20);
        pane.setPadding(new Insets(20, 28, 20, 28));
        pane.setAlignment(Pos.TOP_LEFT);

        TextField nameField = new TextField();
        nameField.setEditable(false);
        nameField.setFocusTraversable(false);
        nameField.setMaxWidth(Double.MAX_VALUE);
        nameField.setStyle("-fx-opacity: 0.75;");
        if (serverDetails != null && serverDetails.getServerName() != null) {
            nameField.setText(serverDetails.getServerName());
        }

        EmojiTextArea descField = new EmojiTextArea(false);
        descField.getStyleClass().add("text-input");
        descField.setStyle("-fx-padding: 0;");
        descField.setMaxLines(9);
        descField.setEditable(false);
        descField.setOpacity(0.75);
        if (serverDetails != null && serverDetails.getDescription() != null) {
            descField.setText(serverDetails.getDescription());
        }

        pane.getChildren().addAll(
                new VBox(6, sectionLabel("Server Name"), nameField),
                new VBox(6, sectionLabel("Description"), descField));

        return wrapScroll(pane);
    }

    private ScrollPane buildNotificationsPane() {
        origChannelNotif = serverDetails != null && serverDetails.isChannelNotificationsEnabled();

        VBox pane = new VBox(16);
        pane.setPadding(new Insets(20, 28, 20, 28));

        channelNotifToggle = new CheckBox("Enable channel message notifications");
        channelNotifToggle.setSelected(origChannelNotif);
        channelNotifToggle.setFocusTraversable(false);

        Label desc = new Label("Show a pop-up notification when a message is received in a text channel while the conversation is not in focus.");
        desc.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        desc.setWrapText(true);

        channelNotifToggle.selectedProperty().addListener((obs, o, n) -> {
            notifDirty = n != origChannelNotif;
            updateSaveButton();
        });

        // ── Pokes preference ──────────────────────────────────────────────────
        pokesEnabledToggle = new CheckBox("Allow incoming pokes");
        pokesEnabledToggle.setFocusTraversable(false);
        pokesEnabledToggle.setDisable(true);

        Label pokesDesc = new Label("When enabled, users with the Poke permission can send you poke notifications. This setting takes priority over the sender's permission.");
        pokesDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        pokesDesc.setWrapText(true);

        pokesEnabledToggle.selectedProperty().addListener((obs, o, n) -> {
            pokesDirty = n != origPokesEnabled;
            updateSaveButton();
        });

        Service<Boolean> loadPokePrefService = new Service<>() {
            @Override
            protected Task<Boolean> createTask() {
                return new Task<>() {
                    @Override
                    protected Boolean call() throws Exception {
                        return App.getServices().installation().getMemberService().getMyPokePreference();
                    }
                };
            }
        };
        loadPokePrefService.setOnSucceeded(e -> {
            boolean enabled = loadPokePrefService.getValue();
            origPokesEnabled = enabled;
            Platform.runLater(() -> {
                pokesEnabledToggle.setSelected(enabled);
                pokesEnabledToggle.setDisable(false);
                pokesDirty = false;
                updateSaveButton();
            });
        });
        loadPokePrefService.setOnFailed(e -> {
            origPokesEnabled = AppState.isPokesEnabled();
            Platform.runLater(() -> {
                pokesEnabledToggle.setSelected(origPokesEnabled);
                pokesEnabledToggle.setDisable(false);
                pokesDirty = false;
            });
        });
        loadPokePrefService.start();

        pane.getChildren().addAll(
                sectionLabel("Channel Messages"),
                new VBox(6, channelNotifToggle, desc),
                sectionLabel("Pokes"),
                new VBox(6, pokesEnabledToggle, pokesDesc)
        );
        return wrapScroll(pane);
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");
        return l;
    }

    private static ScrollPane wrapScroll(VBox content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }
}
