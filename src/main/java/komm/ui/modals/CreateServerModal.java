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
import komm.api.HttpStatusException;
import komm.model.dto.request.ServerCreateRequest;
import komm.model.dto.summary.InstallationSummary;
import komm.ui.pages.HomePage;
import komm.ui.avatar.AvatarPreviewWidget;
import komm.ui.emojis.EmojiFilterTextField;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

public class CreateServerModal extends HBox {

    private TextField serverNameField;
    private ComboBox<InstallationSummary> installationComboBox;
    private AvatarPreviewWidget avatarWidget;
    private Button cancelButton;
    private Button createButton;
    private Button closeButton;
    private ProgressIndicator progressIndicator;

    private final Service<List<InstallationSummary>> loadInstallationsService = new Service<>() {
        @Override
        protected Task<List<InstallationSummary>> createTask() {
            return new Task<>() {
                @Override
                protected List<InstallationSummary> call() throws Exception {
                    return App.getServices().hub().getInstallationService().getUserInstallations();
                }
            };
        }
    };

    private ServerCreateRequest currentRequest;
    private final Service<Void> createServerService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().hub().getServerService().createServer(currentRequest);
                    return null;
                }
            };
        }
    };


    public CreateServerModal() {
        setAlignment(Pos.TOP_LEFT);
        getStyleClass().add("custom-modal");
        setMaxSize(760, 460);
        setMinSize(760, 460);
        setPrefSize(760, 460);
        setSpacing(0);

        avatarWidget = AvatarPreviewWidget.configure()
                .previewSize(190)
                .allowUpload(true)
                .build();

        VBox avatarPane = createAvatarPane();

        Separator vDivider = new Separator(Orientation.VERTICAL);
        vDivider.setPadding(new Insets(0));

        VBox formColumn = createFormColumn();
        HBox.setHgrow(formColumn, Priority.ALWAYS);

        getChildren().addAll(avatarPane, vDivider, formColumn);

        createButton.disableProperty().bind(
                loadInstallationsService.runningProperty().or(createServerService.runningProperty())
        );
        progressIndicator.visibleProperty().bind(
                loadInstallationsService.runningProperty().or(createServerService.runningProperty())
        );

        createServerService.runningProperty().addListener((obs, wasRunning, isRunning) ->
                App.getModalPane().setPersistent(isRunning)
        );

        loadInstallations();
    }

    // ─── Left panel ───────────────────────────────────────────────────────────

    private VBox createAvatarPane() {
        VBox pane = new VBox(16);
        pane.setPadding(new Insets(32, 28, 32, 28));
        pane.setAlignment(Pos.CENTER);
        pane.setPrefWidth(290);
        pane.setMinWidth(290);
        pane.setMaxWidth(290);
        pane.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 12px 0 0 12px;" );
        pane.getChildren().add(avatarWidget);
        return pane;
    }

    // ─── Right column ─────────────────────────────────────────────────────────

    private VBox createFormColumn() {
        VBox column = new VBox(0);
        column.setAlignment(Pos.TOP_LEFT);
        column.getChildren().addAll(createHeader(), createFormBody(), createFooter());
        return column;
    }

    private HBox createHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.TOP_LEFT);
        header.setPadding(new Insets(18, 16, 0, 24));

        Label title = new Label("Create New Server" );
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;" );
        Label subtitle = new Label("Configure your server below — avatar is optional." );
        subtitle.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;" );

        VBox titleGroup = new VBox(2, title, subtitle);
        titleGroup.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        closeButton = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeButton.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeButton.setOnAction(e -> App.closeModal());

        header.getChildren().addAll(new HBox(10, titleGroup), spacer, closeButton);
        return header;
    }

    private VBox createFormBody() {
        VBox pane = new VBox(20);
        pane.setPadding(new Insets(0, 24, 24, 24));
        pane.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label("Server Name" );
        nameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;" );
        serverNameField = EmojiFilterTextField.maxLength(100);
        serverNameField.setPromptText("e.g. My Gaming Server" );
        serverNameField.setMaxWidth(Double.MAX_VALUE);
        VBox nameGroup = new VBox(6, nameLabel, serverNameField);

        Label installLabel = new Label("Installation" );
        installLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;" );
        installationComboBox = new ComboBox<>();
        installationComboBox.setPromptText("Select an installation" );
        installationComboBox.setMaxWidth(Double.MAX_VALUE);
        installationComboBox.setCellFactory(p -> new ListCell<>() {
            @Override
            protected void updateItem(InstallationSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.getInstallationName());
            }
        });
        installationComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(InstallationSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.getInstallationName());
            }
        });
        VBox installGroup = new VBox(6, installLabel, installationComboBox);

        pane.getChildren().addAll(nameGroup, installGroup);
        VBox.setVgrow(pane, Priority.ALWAYS);
        return pane;
    }

    private HBox createFooter() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(14, 24, 14, 24));

        progressIndicator = new ProgressIndicator();
        progressIndicator.setMaxSize(16, 16);
        progressIndicator.setVisible(false);

        cancelButton = new Button("Cancel" );
        cancelButton.setFocusTraversable(false);
        cancelButton.getStyleClass().add(Styles.SMALL);
        cancelButton.setOnAction(e -> App.closeModal());

        createButton = new Button("Create Server" );
        createButton.setFocusTraversable(false);
        createButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        createButton.setOnAction(e -> handleCreateServer());

        cancelButton.disableProperty().bind(createServerService.runningProperty());
        closeButton.disableProperty().bind(createServerService.runningProperty());

        footer.getChildren().addAll(progressIndicator, cancelButton, createButton);
        return footer;
    }

    // ─── Installations ────────────────────────────────────────────────────────

    private void loadInstallations() {
        loadInstallationsService.setOnSucceeded(e -> {
            List<InstallationSummary> list = loadInstallationsService.getValue();
            installationComboBox.getItems().addAll(list);
            if (!list.isEmpty()) installationComboBox.getSelectionModel().selectFirst();
        });

        loadInstallationsService.setOnFailed(e ->
                new CustomNotification("Load Error", "Could not load installations. Please try again.",
                        new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                        .showNotification()
        );

        loadInstallationsService.start();
    }

    // ─── Create ───────────────────────────────────────────────────────────────

    private void handleCreateServer() {
        String serverName = serverNameField.getText().trim();
        InstallationSummary selected = installationComboBox.getValue();

        if (serverName.isEmpty()) {
            new CustomNotification("Validation Error", "Server name is required.", new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                    .showNotification();
            serverNameField.requestFocus();
            return;
        }
        if (serverName.length() > 100) {
            new CustomNotification("Validation Error", "Server name must be 100 characters or fewer.", new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                    .showNotification();
            serverNameField.requestFocus();
            return;
        }
        if (selected == null) {
            new CustomNotification("Validation Error", "Please select an installation.", new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                    .showNotification();
            return;
        }

        String base64Image = null;
        String format = null;
        if (avatarWidget.hasNewUpload()) {
            try {
                base64Image = Base64.getEncoder().encodeToString(avatarWidget.getFinalImageBytes());
                format = avatarWidget.getImageFormat();
            } catch (Exception e) {
                new CustomNotification("Image Error", e.getMessage(),
                        new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                        .showNotification();
                return;
            }
        }

        currentRequest = ServerCreateRequest.builder()
                .serverName(serverName)
                .installationId(selected.getInstallationId())
                .avatarBase64(base64Image)
                .avatarContentType(format)
                .build();

        createServerService.setOnSucceeded(e -> {
            App.closeModal();
            ((HomePage) App.getCurrentPage()).refreshCurrentView();
            new CustomNotification("Server Created",
                    "Your server is ready. Invite members to get started.",
                    new FontIcon(MaterialDesignL.LAN_CHECK)).showNotification();
        });

        createServerService.setOnFailed(e -> {
            Throwable ex = createServerService.getException();
            ex.printStackTrace();
            new CustomNotification("Error", HttpStatusException.extractMessage(ex),
                    new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                    .showNotification();
        });

        createServerService.restart();

    }
}