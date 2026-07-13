package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.response.InviteLinkResponse;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.io.ByteArrayInputStream;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JoinViaInviteModal extends VBox {

    private static final Pattern INVITE_URL_PATTERN =
            Pattern.compile("/invite/([A-Za-z0-9]{6,16})(?:[/?].*)?$");

    private TextField codeField;
    private VBox previewBox;
    private Button joinBtn;
    private InviteLinkResponse previewInfo;
    private String pendingCode;

    private final PauseTransition debounce = new PauseTransition(Duration.millis(600));

    private final Service<InviteLinkResponse> lookupService = new Service<>() {
        @Override
        protected Task<InviteLinkResponse> createTask() {
            return new Task<>() {
                @Override
                protected InviteLinkResponse call() throws Exception {
                    return App.getServices().hub().getInviteService().getInviteInfo(pendingCode);
                }
            };
        }
    };

    private final Service<Void> joinService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().hub().getInviteService().joinViaInvite(pendingCode);
                    return null;
                }
            };
        }
    };

    public JoinViaInviteModal() {
        buildUI();
    }

    public JoinViaInviteModal(String prefillCode) {
        buildUI();
        codeField.setText(prefillCode);
    }

    private void buildUI() {
        getStyleClass().add("custom-modal");
        setMinSize(440, 330);
        setMaxSize(440, 330);
        setPrefSize(440, 330);
        setSpacing(0);

        getChildren().addAll(buildHeader(), buildBody(), buildFooter());

        debounce.setOnFinished(e -> triggerLookup());

        lookupService.setOnSucceeded(e -> {
            previewInfo = lookupService.getValue();
            showPreview();
        });
        lookupService.setOnFailed(e -> {
            clearPreview();
            new CustomNotification("Invalid Invite", HttpStatusException.extractMessage(lookupService.getException()),
                    new FontIcon(MaterialDesignC.CLOSE)).showNotification();
        });

        joinService.setOnSucceeded(e -> {
            App.closeModal();
            String name = previewInfo != null ? previewInfo.getServerName() : "server";
            new CustomNotification("Server Joined", "Welcome to " + name + "!",
                    new FontIcon(MaterialDesignA.ACCOUNT_MULTIPLE_PLUS)).showNotification();
            Platform.runLater(() -> {
                if (App.getCachedHomePage() != null) App.getCachedHomePage().refreshCurrentView();
            });
        });
        joinService.setOnFailed(e -> {
            joinBtn.setDisable(false);
            new CustomNotification("Failed to Join", HttpStatusException.extractMessage(joinService.getException()),
                    new FontIcon(MaterialDesignC.CLOSE)).showNotification();
        });
    }

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 16, 12, 20));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");

        Label title = new Label("Join a Server");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label subtitle = new Label("enter an invite link or code");
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
        codeField = new TextField();
        codeField.setPromptText("Paste a link or code…");
        codeField.setMaxWidth(Double.MAX_VALUE);
        codeField.textProperty().addListener((obs, old, val) -> {
            clearPreview();
            joinBtn.setDisable(true);
            String trimmed = val == null ? "" : val.trim();
            if (trimmed.length() >= 6) {
                debounce.playFromStart();
            } else {
                debounce.stop();
            }
        });
        codeField.setOnAction(e -> {
            debounce.stop();
            triggerLookup();
        });

        previewBox = new VBox();
        previewBox.setAlignment(Pos.CENTER);
        previewBox.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(previewBox, Priority.ALWAYS);
        previewBox.setStyle(
                "-fx-border-color: -color-border-default;" +
                "-fx-border-width: 1;" +
                "-fx-border-radius: 8;" +
                "-fx-background-color: -color-bg-subtle;" +
                "-fx-background-radius: 8;");
        showPlaceholder();

        VBox body = new VBox(12, codeField, previewBox);
        body.setPadding(new Insets(18, 20, 18, 20));
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    private void showPlaceholder() {
        previewBox.setAlignment(Pos.CENTER);
        previewBox.setPadding(Insets.EMPTY);

        FontIcon icon = new FontIcon(MaterialDesignL.LINK_VARIANT);

        Label hint = new Label("Paste an invite link or code above");
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-subtle;");

        VBox placeholder = new VBox(8, icon, hint);
        placeholder.setAlignment(Pos.CENTER);
        previewBox.getChildren().setAll(placeholder);
    }

    private HBox buildFooter() {
        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 20, 14, 20));
        footer.setStyle("-fx-border-color: -color-border-default transparent transparent transparent; -fx-border-width: 1 0 0 0;");

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add(Styles.SMALL);
        cancelBtn.setOnAction(e -> App.closeModal());

        joinBtn = new Button("Join Server");
        joinBtn.setDefaultButton(true);
        joinBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        joinBtn.setDisable(true);
        joinBtn.setOnAction(e -> {
            joinBtn.setDisable(true);
            joinService.restart();
        });

        footer.getChildren().addAll(cancelBtn, joinBtn);
        return footer;
    }

    // ── Logic ─────────────────────────────────────────────────────────────────

    private void triggerLookup() {
        String raw = codeField.getText() == null ? "" : codeField.getText().trim();
        if (raw.isEmpty()) return;

        pendingCode = extractCode(raw);
        if (pendingCode == null) {
            new CustomNotification("Invalid Link", "Could not extract a valid invite code.",
                    new FontIcon(MaterialDesignC.CLOSE)).showNotification();
            return;
        }
        lookupService.restart();
    }

    private String extractCode(String input) {
        Matcher m = INVITE_URL_PATTERN.matcher(input);
        if (m.find()) return m.group(1);
        if (input.matches("[A-Za-z0-9]{6,16}")) return input;
        return null;
    }

    private void showPreview() {
        if (previewInfo == null) return;
        previewBox.getChildren().clear();

        // Avatar
        javafx.scene.Node avatar;
        if (previewInfo.getServerAvatarBase64() != null) {
            try {
                byte[] bytes = Base64.getDecoder().decode(previewInfo.getServerAvatarBase64());
                Image img = new Image(new ByteArrayInputStream(bytes));
                ImageView iv = new ImageView(img);
                iv.setFitWidth(44);
                iv.setFitHeight(44);
                iv.setPreserveRatio(false);
                Rectangle clip = new Rectangle(44, 44);
                clip.setArcWidth(10);
                clip.setArcHeight(10);
                iv.setClip(clip);
                avatar = iv;
            } catch (Exception ignored) {
                avatar = avatarInitial(previewInfo.getServerName());
            }
        } else {
            avatar = avatarInitial(previewInfo.getServerName());
        }

        // Info
        Label nameLabel = new Label(previewInfo.getServerName());
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Label creatorLabel = new Label("Invited by " + previewInfo.getCreatorUsername());
        creatorLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");

        String expiryText = previewInfo.getExpiresAt() != null
                ? "Expires " + previewInfo.getExpiresAt().format(DateTimeFormatter.ofPattern("MMM d 'at' HH:mm"))
                : "No expiry";
        Label expiryLabel = new Label(expiryText);
        expiryLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        VBox info = new VBox(3, nameLabel, creatorLabel, expiryLabel);
        info.setAlignment(Pos.CENTER_LEFT);

        HBox card = new HBox(14, avatar, info);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(12, 14, 12, 14));
        card.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: -color-border-default;" +
                "-fx-border-radius: 8;" +
                "-fx-border-width: 1;");

        previewBox.setAlignment(Pos.TOP_LEFT);
        previewBox.setPadding(new Insets(14));
        previewBox.getChildren().setAll(card);
        joinBtn.setDisable(false);
    }

    private void clearPreview() {
        previewInfo = null;
        showPlaceholder();
    }

    private Label avatarInitial(String serverName) {
        String letter = (serverName == null || serverName.isEmpty())
                ? "?" : String.valueOf(serverName.charAt(0)).toUpperCase();
        Label lbl = new Label(letter);
        lbl.setMinSize(44, 44);
        lbl.setPrefSize(44, 44);
        lbl.setMaxSize(44, 44);
        lbl.setAlignment(Pos.CENTER);
        lbl.setStyle(
                "-fx-background-color: -color-accent-muted;" +
                "-fx-background-radius: 8;" +
                "-fx-font-weight: bold;" +
                "-fx-font-size: 18px;");
        return lbl;
    }
}
