package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import komm.App;
import komm.model.dto.summary.InstallationSummary;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

public class VerificationCodeModal extends VBox {

    private final String code;

    public VerificationCodeModal(InstallationSummary installation, String code) {
        this.code = code;
        buildUI(installation);
    }

    private void buildUI(InstallationSummary installation) {
        getStyleClass().add("custom-modal");
        setMinSize(420, 250);
        setMaxSize(420, 250);
        setPrefSize(420, 250);
        setSpacing(0);

        getChildren().addAll(buildHeader(installation), buildBody(), buildFooter());
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private HBox buildHeader(InstallationSummary installation) {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 16, 12, 20));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");

        Label title = new Label("Verification Code");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label subtitle = new Label("for " + installation.getInstallationName());
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

    private VBox buildBody() {
        FontIcon keyIcon = new FontIcon(MaterialDesignK.KEY_OUTLINE);
        keyIcon.setIconSize(18);

        TextField codeField = new TextField(code);
        codeField.setEditable(false);
        codeField.setMaxWidth(Double.MAX_VALUE);
        codeField.setStyle("-fx-background-color: transparent; -fx-border-width: 0; -fx-padding: 0 0 0 2; -fx-font-family: 'Monospaced';");
        HBox.setHgrow(codeField, Priority.ALWAYS);

        Button copyBtn = new Button(null, new FontIcon(MaterialDesignC.CONTENT_COPY));
        copyBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_ICON, Styles.SMALL);
        copyBtn.setTooltip(new Tooltip("Copy code"));
        copyBtn.setOnAction(e -> copyAndNotify());

        HBox codeCard = new HBox(10, keyIcon, codeField, copyBtn);
        codeCard.setAlignment(Pos.CENTER_LEFT);
        codeCard.setPadding(new Insets(12, 10, 12, 14));
        codeCard.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: -color-border-default;" +
                "-fx-border-radius: 8;" +
                "-fx-border-width: 1;");

        FontIcon infoIcon = new FontIcon(MaterialDesignI.INFORMATION_OUTLINE);
        infoIcon.setIconSize(13);

        Label infoLabel = new Label("Paste this into the server launcher when it asks for your verification code.");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        HBox infoRow = new HBox(5, infoIcon, infoLabel);
        infoRow.setAlignment(Pos.TOP_LEFT);

        VBox body = new VBox(12, codeCard, infoRow);
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
            new CustomNotification("Verification Code Copied", "The code has been copied to your clipboard.", new FontIcon(MaterialDesignK.KEY_OUTLINE)).showNotification();
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
        cc.putString(code);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private void copyAndNotify() {
        copyToClipboard();
        new CustomNotification("Verification Code Copied", "The code has been copied to your clipboard.", new FontIcon(MaterialDesignK.KEY_OUTLINE)).showNotification();
    }
}
