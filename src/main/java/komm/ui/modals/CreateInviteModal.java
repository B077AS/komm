package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.response.InviteLinkResponse;
import komm.model.dto.summary.ServerSummary;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

public class CreateInviteModal extends VBox {

    private record ExpiryOption(String label, Integer hours) {
        @Override public String toString() { return label; }
    }

    private static final ExpiryOption[] OPTIONS = {
        new ExpiryOption("Never expires",  null),
        new ExpiryOption("7 days",         168),
        new ExpiryOption("48 hours",       48),
        new ExpiryOption("24 hours",       24),
    };

    private Button generateBtn;
    private ProgressIndicator progressIndicator;
    private final ComboBox<ExpiryOption> expiryBox = new ComboBox<>();
    private final ServerSummary server;

    private final Service<InviteLinkResponse> createService = new Service<>() {
        @Override
        protected Task<InviteLinkResponse> createTask() {
            return new Task<>() {
                @Override
                protected InviteLinkResponse call() throws Exception {
                    Integer hours = expiryBox.getValue().hours();
                    return App.getServices().hub().getInviteService()
                            .createInvite(server.getServerId(), hours);
                }
            };
        }
    };

    public CreateInviteModal(ServerSummary server) {
        this.server = server;
        buildUI();
    }

    private void buildUI() {
        getStyleClass().add("custom-modal");
        setMinSize(420, 230);
        setMaxSize(420, 230);
        setPrefSize(420, 230);
        setSpacing(0);

        getChildren().addAll(buildHeader(), buildBody(), buildFooter());

        createService.setOnSucceeded(e -> {
            InviteLinkResponse result = createService.getValue();
            App.getModalPane().setContent(new InviteLinkModal(server, result));
        });
        createService.setOnFailed(e -> new CustomNotification(
                "Error",
                HttpStatusException.extractMessage(createService.getException()),
                new FontIcon(MaterialDesignC.CLOSE)).showNotification());
        createService.runningProperty().addListener((obs, was, running) -> {
            generateBtn.setDisable(running);
            progressIndicator.setVisible(running);
        });
    }

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 16, 12, 20));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");

        Label title = new Label("Create Invite");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label subtitle = new Label("for " + server.getServerName());
        subtitle.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeBtn.setOnAction(e -> App.closeModal());

        header.getChildren().addAll(new VBox(2, title, subtitle), spacer, closeBtn);
        return header;
    }

    private VBox buildBody() {
        Label lbl = new Label("Link expires after");
        lbl.setStyle("-fx-font-size: 13px;");
        lbl.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(lbl, Priority.NEVER);

        expiryBox.getItems().addAll(OPTIONS);
        expiryBox.setValue(OPTIONS[0]);
        expiryBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(expiryBox, Priority.ALWAYS);

        HBox row = new HBox(12, lbl, expiryBox);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(row);
        body.setPadding(new Insets(22, 20, 22, 20));
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    private HBox buildFooter() {
        progressIndicator = new ProgressIndicator();
        progressIndicator.setMaxSize(16, 16);
        progressIndicator.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add(Styles.SMALL);
        cancelBtn.setOnAction(e -> App.closeModal());

        generateBtn = new Button("Generate Link");
        generateBtn.setDefaultButton(true);
        generateBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        generateBtn.setOnAction(e -> createService.restart());

        HBox footer = new HBox(8, spacer, progressIndicator, cancelBtn, generateBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 20, 14, 20));
        footer.setStyle("-fx-border-color: -color-border-default transparent transparent transparent; -fx-border-width: 1 0 0 0;");
        return footer;
    }

    public InviteLinkResponse getResult() {
        return createService.getValue();
    }
}
