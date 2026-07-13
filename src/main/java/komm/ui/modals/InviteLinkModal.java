package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import komm.App;
import komm.model.dto.response.InviteLinkResponse;
import komm.model.dto.summary.ServerSummary;
import komm.ui.customnodes.CustomNotification;
import komm.utils.AppConfig;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.time.format.DateTimeFormatter;

public class InviteLinkModal extends VBox {

    private final String linkUrl;

    public InviteLinkModal(ServerSummary server, InviteLinkResponse invite) {
        this.linkUrl = AppConfig.getInstance().getApiUrl() + "/invite/" + invite.getCode();
        buildUI(server, invite);
    }

    private void buildUI(ServerSummary server, InviteLinkResponse invite) {
        getStyleClass().add("custom-modal");
        setMinSize(420, 250);
        setMaxSize(420, 250);
        setPrefSize(420, 250);
        setSpacing(0);

        getChildren().addAll(buildHeader(server), buildBody(invite), buildFooter());
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private HBox buildHeader(ServerSummary server) {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 16, 12, 20));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");

        Label title = new Label("Invite Link");
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

    // ── Body ──────────────────────────────────────────────────────────────────

    private VBox buildBody(InviteLinkResponse invite) {
        FontIcon linkIcon = new FontIcon(MaterialDesignL.LINK_VARIANT);
        linkIcon.setIconSize(18);

        TextField linkField = new TextField(linkUrl);
        linkField.setEditable(false);
        linkField.setMaxWidth(Double.MAX_VALUE);
        linkField.setStyle("-fx-background-color: transparent; -fx-border-width: 0; -fx-padding: 0 0 0 2;");
        HBox.setHgrow(linkField, Priority.ALWAYS);

        Button copyBtn = new Button(null, new FontIcon(MaterialDesignC.CONTENT_COPY));
        copyBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_ICON, Styles.SMALL);
        copyBtn.setTooltip(new Tooltip("Copy link"));
        copyBtn.setOnAction(e -> copyAndNotify());

        HBox linkCard = new HBox(10, linkIcon, linkField, copyBtn);
        linkCard.setAlignment(Pos.CENTER_LEFT);
        linkCard.setPadding(new Insets(12, 10, 12, 14));
        linkCard.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: -color-border-default;" +
                "-fx-border-radius: 8;" +
                "-fx-border-width: 1;");

        FontIcon clockIcon = new FontIcon(MaterialDesignC.CLOCK_OUTLINE);
        clockIcon.setIconSize(13);

        String expiryText = invite.getExpiresAt() != null
                ? "Expires " + invite.getExpiresAt().format(DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm"))
                : "This link never expires";
        Label expiryLabel = new Label(expiryText);
        expiryLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        HBox expiryRow = new HBox(5, clockIcon, expiryLabel);
        expiryRow.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(12, linkCard, expiryRow);
        body.setPadding(new Insets(18, 20, 18, 20));
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private HBox buildFooter() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button doneBtn = new Button("Done");
        doneBtn.getStyleClass().add(Styles.SMALL);
        doneBtn.setOnAction(e -> App.closeModal());

        Button copyCloseBtn = new Button("Copy & Close");
        copyCloseBtn.setDefaultButton(true);
        copyCloseBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        copyCloseBtn.setOnAction(e -> {
            copyToClipboard();
            App.closeModal();
            new CustomNotification("Invite Link Copied", "The link has been copied to your clipboard.", new FontIcon(MaterialDesignL.LINK_VARIANT)).showNotification();
        });

        HBox footer = new HBox(8, spacer, doneBtn, copyCloseBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 20, 14, 20));
        footer.setStyle("-fx-border-color: -color-border-default transparent transparent transparent; -fx-border-width: 1 0 0 0;");
        return footer;
    }

    // ── Logic ─────────────────────────────────────────────────────────────────

    private void copyToClipboard() {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(linkUrl);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private void copyAndNotify() {
        copyToClipboard();
        new CustomNotification("Invite Link Copied", "The link has been copied to your clipboard.", new FontIcon(MaterialDesignL.LINK_VARIANT)).showNotification();
    }
}
