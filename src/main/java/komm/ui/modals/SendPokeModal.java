package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import komm.App;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.util.UUID;

public class SendPokeModal extends VBox {

    private static final int MAX_POKE_LENGTH = 100;

    private final UUID targetUserId;

    private TextField messageField;
    private Button sendButton;
    private Button cancelButton;
    private Button closeButton;

    private final Service<Void> pokeService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            String msg = messageField != null ? messageField.getText().trim() : "";
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().installation().getMemberService().pokeUser(targetUserId, msg);
                    return null;
                }
            };
        }
    };

    public SendPokeModal(UUID targetUserId, String targetUsername) {
        this.targetUserId = targetUserId;

        getStyleClass().add("custom-modal");
        setMaxSize(420, 215);
        setMinSize(420, 215);
        setPrefSize(420, 215);

        getChildren().addAll(
                createHeader(),
                createBody(targetUsername),
                createFooter()
        );

        pokeService.setOnSucceeded(e -> Platform.runLater(App::closeModal));
        pokeService.setOnFailed(e -> Platform.runLater(() -> {
            new CustomNotification("Poke Failed", "Failed to send poke.", new FontIcon(MaterialDesignC.CLOSE))
                    .showNotification();
            sendButton.setDisable(false);
            cancelButton.setDisable(false);
            closeButton.setDisable(false);
            if (messageField != null) messageField.setDisable(false);
            App.getModalPane().setPersistent(false);
        }));
        pokeService.runningProperty().addListener((obs, wasRunning, isRunning) -> {
            sendButton.setDisable(isRunning);
            cancelButton.setDisable(isRunning);
            closeButton.setDisable(isRunning);
            if (messageField != null) messageField.setDisable(isRunning);
            App.getModalPane().setPersistent(isRunning);
        });
    }

    private HBox createHeader() {
        HBox box = new HBox();
        box.setAlignment(Pos.CENTER_RIGHT);
        box.setPadding(new Insets(10, 10, 0, 0));

        Region filler = new Region();
        HBox.setHgrow(filler, Priority.ALWAYS);

        closeButton = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeButton.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeButton.setFocusTraversable(false);
        closeButton.setOnAction(e -> App.closeModal());

        box.getChildren().addAll(filler, closeButton);
        return box;
    }

    private VBox createBody(String targetUsername) {
        VBox body = new VBox(10);
        body.setPadding(new Insets(0, 28, 0, 28));
        VBox.setVgrow(body, Priority.ALWAYS);

        Label titleLabel = new Label("Poke " + targetUsername);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label subtitleLabel = new Label("Add an optional message");
        subtitleLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");

        Label counterLabel = new Label("0/" + MAX_POKE_LENGTH);
        counterLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");

        Region subtitleFiller = new Region();
        HBox.setHgrow(subtitleFiller, Priority.ALWAYS);
        HBox subtitleRow = new HBox(subtitleLabel, subtitleFiller, counterLabel);

        messageField = new TextField();
        messageField.setPromptText("Say something...");
        messageField.setMaxWidth(Double.MAX_VALUE);
        messageField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.length() > MAX_POKE_LENGTH) {
                messageField.setText(newVal.substring(0, MAX_POKE_LENGTH));
            } else {
                counterLabel.setText(newVal.length() + "/" + MAX_POKE_LENGTH);
                String color = newVal.length() == MAX_POKE_LENGTH ? "-color-danger-fg" : "-color-fg-muted";
                counterLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;");
            }
        });

        body.getChildren().addAll(titleLabel, subtitleRow, messageField);
        return body;
    }

    private HBox createFooter() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 24, 16, 24));

        cancelButton = new Button("Cancel");
        cancelButton.setFocusTraversable(false);
        cancelButton.getStyleClass().add(Styles.SMALL);
        cancelButton.setOnAction(e -> App.closeModal());

        sendButton = new Button("Send Poke");
        sendButton.setDefaultButton(true);
        sendButton.setFocusTraversable(false);
        sendButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        sendButton.setOnAction(e -> pokeService.restart());

        footer.getChildren().addAll(cancelButton, sendButton);
        return footer;
    }
}
