package komm.ui.modals;

import java.util.UUID;

import komm.ui.emojis.EmojiFilterTextField;
import komm.utils.CsrGenerator;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import atlantafx.base.theme.Styles;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.request.CreateInstallationRequest;
import komm.ui.pages.HomePage;
import komm.ui.customnodes.CustomNotification;

public class CreateInstallationModal extends VBox {

    private TextField installationNameField;
    private TextField apiPortField;
    private TextField signalPortField;
    private TextField tcpPortField;
    private TextField mediaPortField;
    private ProgressIndicator progressIndicator;
    private Button cancelButton;
    private Button createButton;
    private Button closeButton;

    private CreateInstallationRequest currentRequest;
    private final Service<UUID> createInstallationService = new Service<>() {
        @Override
        protected Task<UUID> createTask() {
            return new Task<>() {
                @Override
                protected UUID call() throws Exception {
                    return App.getServices().hub().getInstallationService().createInstallation(currentRequest);
                }
            };
        }
    };

    public CreateInstallationModal() {
        setAlignment(Pos.TOP_CENTER);
        getStyleClass().add("custom-modal");
        setMaxSize(580, 350);
        setMinSize(580, 350);
        setPrefSize(580, 350);

        getChildren().addAll(
                createHeader(),
                createTitle(),
                createFormFields(),
                createButtonBox()
        );

        createInstallationService.runningProperty().addListener((obs, wasRunning, isRunning) -> {
            App.getModalPane().setPersistent(isRunning);
            cancelButton.setDisable(isRunning);
            createButton.setDisable(isRunning);
            closeButton.setDisable(isRunning);
            installationNameField.setDisable(isRunning);
            apiPortField.setDisable(isRunning);
            signalPortField.setDisable(isRunning);
            tcpPortField.setDisable(isRunning);
            mediaPortField.setDisable(isRunning);
            progressIndicator.setVisible(isRunning);
        });
    }

    private HBox createHeader() {
        HBox headerBox = new HBox();
        headerBox.setAlignment(Pos.CENTER_RIGHT);
        headerBox.setPadding(new Insets(10, 10, 0, 0));

        Region filler = new Region();
        HBox.setHgrow(filler, Priority.ALWAYS);

        closeButton = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeButton.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeButton.setOnAction(event -> App.closeModal());
        closeButton.setFocusTraversable(false);

        headerBox.getChildren().addAll(filler, closeButton);
        return headerBox;
    }

    private VBox createTitle() {
        VBox titleBox = new VBox(4);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        titleBox.setPadding(new Insets(0, 30, 16, 30));

        Label titleLabel = new Label("New Installation");
        titleLabel.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");

        Label subtitleLabel = new Label("Configure a name and the ports users will connect to");
        subtitleLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");

        titleBox.getChildren().addAll(titleLabel, subtitleLabel);
        return titleBox;
    }

    private VBox createFormFields() {
        VBox formContainer = new VBox(10);
        formContainer.setPadding(new Insets(0, 30, 0, 30));
        VBox.setVgrow(formContainer, Priority.ALWAYS);

        VBox nameBox = new VBox(5);
        Label nameLabel = new Label("Installation Name");
        nameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        installationNameField = new EmojiFilterTextField();
        installationNameField.setPromptText("My Installation");
        installationNameField.setMaxWidth(Double.MAX_VALUE);
        nameBox.getChildren().addAll(nameLabel, installationNameField);

        apiPortField = new TextField();
        signalPortField = new TextField();
        tcpPortField = new TextField();
        mediaPortField = new TextField();

        HBox portsRow = new HBox(10);
        portsRow.setAlignment(Pos.BOTTOM_CENTER);
        portsRow.getChildren().addAll(
                buildPortBox("API Port", "TCP", "8080", apiPortField),
                buildPortBox("Signaling Port", "TCP", "7880", signalPortField),
                buildPortBox("TCP Port", "TCP", "7881", tcpPortField),
                buildPortBox("Media Port", "UDP", "7882", mediaPortField)
        );

        formContainer.getChildren().addAll(nameBox, portsRow);
        return formContainer;
    }

    private VBox buildPortBox(String portName, String protocol, String defaultValue, TextField field) {
        VBox box = new VBox(5);
        HBox.setHgrow(box, Priority.ALWAYS);

        Label nameLabel = new Label(portName);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

        HBox header = new HBox(6, nameLabel, buildProtocolBadge(protocol));
        header.setAlignment(Pos.CENTER_LEFT);

        field.setText(defaultValue);
        field.setMaxWidth(Double.MAX_VALUE);
        field.setTextFormatter(createIntegerTextFormatter());

        box.getChildren().addAll(header, field);
        return box;
    }

    private HBox buildProtocolBadge(String protocol) {
        boolean isTcp = "TCP".equalsIgnoreCase(protocol);
        String bgColor  = isTcp ? "-color-accent-subtle"  : "-color-warning-subtle";
        String fgColor  = isTcp ? "-color-accent-fg"      : "-color-warning-fg";

        Label lbl = new Label(protocol.toUpperCase());
        lbl.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: " + fgColor + ";");

        HBox badge = new HBox(lbl);
        badge.setAlignment(Pos.CENTER);
        badge.setPadding(new Insets(2, 6, 2, 6));
        badge.setMaxWidth(Region.USE_PREF_SIZE);
        badge.setStyle("-fx-background-color: " + bgColor + "; -fx-background-radius: 4px;");
        return badge;
    }

    private TextFormatter<Integer> createIntegerTextFormatter() {
        return new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.isEmpty()) return change;
            if (newText.matches("\\d{0,5}")) return change;
            return null;
        });
    }

    private HBox createButtonBox() {
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.setPadding(new Insets(20, 30, 24, 30));

        progressIndicator = new ProgressIndicator();
        progressIndicator.setMaxSize(16, 16);
        progressIndicator.setVisible(false);

        Region filler = new Region();
        HBox.setHgrow(filler, Priority.ALWAYS);

        cancelButton = new Button("Cancel");
        cancelButton.setFocusTraversable(false);
        cancelButton.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SMALL);
        cancelButton.setOnAction(e -> App.closeModal());

        createButton = new Button("Create Installation");
        createButton.setFocusTraversable(false);
        createButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        createButton.setOnAction(e -> handleCreateInstallation());

        buttonBox.getChildren().addAll(filler, progressIndicator, cancelButton, createButton);
        return buttonBox;
    }

    private void handleCreateInstallation() {
        String installationName = installationNameField.getText().trim();
        String apiPortText = apiPortField.getText().trim();
        String signalPortText = signalPortField.getText().trim();
        String tcpPortText = tcpPortField.getText().trim();
        String mediaPortText = mediaPortField.getText().trim();

        if (installationName.isEmpty()) {
            showNotification("Installation name is required");
            return;
        }
        if (apiPortText.isEmpty()) {
            showNotification("API port is required");
            return;
        }
        if (signalPortText.isEmpty()) {
            showNotification("Signaling port is required");
            return;
        }
        if (tcpPortText.isEmpty()) {
            showNotification("TCP port is required");
            return;
        }
        if (mediaPortText.isEmpty()) {
            showNotification("Media port is required");
            return;
        }

        int apiPort, signalPort, tcpPort, mediaPort;
        try {
            apiPort = parsePort(apiPortText);
            signalPort = parsePort(signalPortText);
            tcpPort = parsePort(tcpPortText);
            mediaPort = parsePort(mediaPortText);
        } catch (IllegalArgumentException ex) {
            showNotification(ex.getMessage());
            return;
        }

        String csr;
        try {
            csr = CsrGenerator.generate(App.getUser().getUserId(), installationName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        currentRequest = CreateInstallationRequest.builder()
                .installationName(installationName)
                .installationPort(apiPort)
                .signalPort(signalPort)
                .tcpPort(tcpPort)
                .mediaPort(mediaPort)
                .installationCsr(csr)
                .build();

        createInstallationService.setOnSucceeded(e -> {
            UUID installationId = createInstallationService.getValue();
            if (installationId != null) {
                App.closeModal();
                ((HomePage) App.getCurrentPage()).refreshInstallationsView();
                new CustomNotification("Installation Created",
                        "\"" + installationName + "\" is ready. Download the JAR to get started.",
                        new FontIcon(MaterialDesignS.SERVER)).showNotification();
            } else {
                showNotification("Failed to create installation");
            }
        });

        createInstallationService.setOnFailed(e -> {
            Throwable ex = createInstallationService.getException();
            ex.printStackTrace();
            showNotification("Error: " + HttpStatusException.extractMessage(ex));
        });

        createInstallationService.restart();
    }

    private int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port < 1 || port > 65535)
                throw new IllegalArgumentException("Port " + port + " must be between 1 and 65535");
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port number: " + text);
        }
    }

    private void showNotification(String message) {
        new CustomNotification("Installation Error", message, new FontIcon(MaterialDesignS.SERVER_OFF))
                .showNotification();
    }
}