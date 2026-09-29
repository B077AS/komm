package komm.ui.modals;

import atlantafx.base.theme.Styles;
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
import komm.api.HttpStatusException;
import komm.model.dto.summary.BadgeSummary;
import komm.ui.customnodes.BadgeUi;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.function.Consumer;

/**
 * Small modal (stacked on top of the user settings modal) where the user
 * pastes a badge token to add the badge to their profile. On success the
 * awarded badge is handed to {@code onRedeemed} and the modal closes.
 */
public class RedeemBadgeModal extends VBox {

    private final TextField tokenField = new TextField();
    private final Button redeemButton = new Button("Redeem");

    private final Consumer<BadgeSummary> onRedeemed;

    private final Service<BadgeSummary> redeemService = new Service<>() {
        @Override
        protected Task<BadgeSummary> createTask() {
            final String token = tokenField.getText().trim();
            return new Task<>() {
                @Override
                protected BadgeSummary call() throws Exception {
                    return App.getServices().hub().getUserService().redeemBadgeToken(token);
                }
            };
        }
    };

    public RedeemBadgeModal(Consumer<BadgeSummary> onRedeemed) {
        this.onRedeemed = onRedeemed;

        getStyleClass().add("custom-modal");
        setMaxSize(430, 230);
        setMinSize(430, 230);
        setPrefSize(430, 230);

        // ── Header ──────────────────────────────────────────────────────────
        Region headerFiller = new Region();
        HBox.setHgrow(headerFiller, Priority.ALWAYS);

        Button closeButton = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeButton.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeButton.setOnAction(e -> App.closeModal());

        HBox headerBox = new HBox(headerFiller, closeButton);
        headerBox.setAlignment(Pos.CENTER_RIGHT);
        headerBox.setPadding(new Insets(5, 5, 0, 0));

        // ── Body ────────────────────────────────────────────────────────────
        FontIcon icon = new FontIcon(MaterialDesignS.SEAL);
        icon.getStyleClass().add("custom-alert-icon");

        Label titleLabel = new Label("Add Badge");
        titleLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        Label textLabel = new Label("Enter a badge token to add the badge to your profile.");
        textLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-muted;");
        textLabel.setWrapText(true);

        tokenField.setPromptText("BADGE-XXXX-XXXX-XXXX");
        tokenField.setMaxWidth(Double.MAX_VALUE);
        tokenField.textProperty().addListener((obs, o, n) -> redeemButton.setDisable(n.trim().isEmpty()));
        tokenField.setOnAction(e -> redeem());

        // Field sits in the text column, under title + description — the badge
        // icon on the left keeps its own column
        VBox textBox = new VBox(6, titleLabel, textLabel, tokenField);
        textBox.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(tokenField, new Insets(8, 0, 0, 0));
        HBox.setHgrow(textBox, Priority.ALWAYS);

        HBox upperBox = new HBox(20, icon, textBox);
        upperBox.setPadding(new Insets(0, 10, 0, 10));
        upperBox.setAlignment(Pos.CENTER_LEFT);

        // ── Buttons ─────────────────────────────────────────────────────────
        redeemButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        redeemButton.setDefaultButton(true);
        redeemButton.setDisable(true);
        redeemButton.setOnAction(e -> redeem());

        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().add(Styles.SMALL);
        cancelButton.setOnAction(e -> App.closeModal());

        HBox buttonsBox = new HBox(10, redeemButton, cancelButton);
        buttonsBox.setAlignment(Pos.BOTTOM_RIGHT);
        buttonsBox.setPadding(new Insets(0, 10, 10, 0));
        VBox.setVgrow(buttonsBox, Priority.ALWAYS);

        getChildren().addAll(headerBox, upperBox, buttonsBox);

        redeemService.setOnSucceeded(e -> {
            BadgeSummary awarded = redeemService.getValue();
            App.closeModal();
            new CustomNotification("Badge Added",
                    "\"" + awarded.getName() + "\" is now on your profile.",
                    BadgeUi.resolveIcon(awarded.getIcon()))
                    .showNotification();
            if (this.onRedeemed != null) this.onRedeemed.accept(awarded);
        });
        redeemService.setOnFailed(e -> {
            redeemButton.setDisable(false);
            tokenField.setDisable(false);
            new CustomNotification("Redeem Failed",
                    HttpStatusException.extractMessage(redeemService.getException()),
                    new FontIcon(MaterialDesignC.CLOSE)).showNotification();
        });
    }

    private void redeem() {
        if (tokenField.getText().trim().isEmpty() || redeemService.isRunning()) return;
        redeemButton.setDisable(true);
        tokenField.setDisable(true);
        redeemService.restart();
    }
}
