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
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignK;

public class JoinInstallationModal extends VBox {

    private TextField codeField;
    private Button joinBtn;

    private final Service<String> joinService = new Service<>() {
        @Override
        protected Task<String> createTask() {
            return new Task<>() {
                @Override
                protected String call() throws Exception {
                    return App.getServices().hub().getInstallationService()
                            .joinViaToken(codeField.getText().trim());
                }
            };
        }
    };

    public JoinInstallationModal() {
        getStyleClass().add("custom-modal");
        setMaxSize(420, 260);
        setMinSize(420, 260);
        setPrefSize(420, 260);
        setSpacing(0);
        buildContent();
    }

    private void buildContent() {
        // Spacer pushes footer to the bottom and absorbs any
        // height changes from the body so nothing else moves
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(buildHeader(), buildBody(), spacer, buildFooter());
        setupServiceCallbacks();
    }

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.TOP_LEFT);
        header.setPadding(new Insets(18, 16, 14, 20));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");

        Label title = new Label("Join an Installation");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label subtitle = new Label("Enter the access token code you received");
        subtitle.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button closeBtn = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.setFocusTraversable(false);
        closeBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeBtn.setOnAction(e -> App.closeModal());

        header.getChildren().addAll(new VBox(2, title, subtitle), spacer, closeBtn);
        return header;
    }

    private VBox buildBody() {
        VBox body = new VBox(14);
        body.setPadding(new Insets(20, 20, 0, 20));

        Label fieldLabel = new Label("Access Token Code");
        fieldLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");

        codeField = new TextField();
        codeField.setPromptText("e.g. Ab3Cd5Ef");
        codeField.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 14px;");
        codeField.setMaxWidth(Double.MAX_VALUE);
        codeField.textProperty().addListener((obs, old, val) ->
                joinBtn.setDisable(val == null || val.trim().isEmpty()));

        body.getChildren().addAll(fieldLabel, codeField);
        return body;
    }

    private HBox buildFooter() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 20, 14, 20));
        footer.setStyle("-fx-border-color: -color-border-default; -fx-border-width: 1px 0 0 0;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add(Styles.SMALL);
        cancelBtn.setFocusTraversable(false);
        cancelBtn.setOnAction(e -> App.closeModal());

        joinBtn = new Button("Join", new FontIcon(MaterialDesignK.KEY_OUTLINE));
        joinBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        joinBtn.setDisable(true);
        joinBtn.setFocusTraversable(false);
        joinBtn.setOnAction(e -> handleJoin());

        footer.getChildren().addAll(spacer, cancelBtn, joinBtn);
        return footer;
    }

    private void handleJoin() {
        joinBtn.setDisable(true);
        joinService.restart();
    }

    private void setupServiceCallbacks() {
        joinService.setOnSucceeded(e -> {
            String name = joinService.getValue();
            App.closeModal();
            if (App.getCachedHomePage() != null) App.getCachedHomePage().refreshCurrentView();
            new CustomNotification("Joined Installation",
                    "You now have access to \"" + (name != null ? name : "the installation") + "\".",
                    new FontIcon(MaterialDesignK.KEY_OUTLINE)).showNotification();
        });

        joinService.setOnFailed(e -> {
            joinBtn.setDisable(false);
            String msg = HttpStatusException.extractMessage(joinService.getException());
            new CustomNotification("Join Installation", msg,
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
        });
    }
}
