package komm.ui.chat;

import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.ImagePattern;
import javafx.scene.shape.Circle;
import komm.App;
import komm.model.dto.response.UserStatusDto;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.modals.UserProfileModal;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

public class DmChatHeader extends HBox {

    // ── Avatar layers ─────────────────────────────────────────────────────────
    private final StackPane avatarBg;
    private final Label initialsLabel;
    private final Circle imageCircle;
    private final Circle statusDot;

    // ── Text ──────────────────────────────────────────────────────────────────
    private final Label nameLabel;
    private final Label statusLabel;

    // ── Status fetch service ──────────────────────────────────────────────────
    private UUID pendingStatusId;
    private UUID currentPartnerId;
    private final Service<UserStatus> statusService = new Service<>() {
        @Override
        protected Task<UserStatus> createTask() {
            final UUID id = pendingStatusId;
            return new Task<>() {
                @Override
                protected UserStatus call() throws Exception {
                    List<UserStatusDto> result = App.getServices().hub()
                            .getUserService()
                            .getUsersBatch(List.of(id));
                    return result.isEmpty() ? null : result.get(0).getStatus();
                }
            };
        }
    };

    public DmChatHeader() {
        setMinHeight(48);
        setMaxHeight(48);
        setPrefHeight(48);
        setAlignment(Pos.CENTER_LEFT);
        setStyle("""
                -fx-background-color: -color-bg-subtle;
                -fx-border-color: transparent transparent -color-border-muted transparent;
                -fx-border-width: 0 0 1px 0;
                """);

        // ── Avatar background (CSS-only color, never set via Java paint API) ──
        avatarBg = new StackPane();
        avatarBg.setPrefSize(32, 32);
        avatarBg.setMinSize(32, 32);
        avatarBg.setMaxSize(32, 32);
        avatarBg.setStyle("-fx-background-color: " + AvatarColor.forName(null) + "; -fx-background-radius: 16px;");

        initialsLabel = new Label("?");
        initialsLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: white;");
        initialsLabel.setMouseTransparent(true);

        // Image circle — only visible when an image is loaded
        imageCircle = new Circle(16);
        imageCircle.setVisible(false);

        avatarBg.getChildren().addAll(initialsLabel, imageCircle);
        avatarBg.setAlignment(Pos.CENTER);

        // ── Status dot — stroke matches header background via CSS ─────────────
        statusDot = new Circle(4.5);
        statusDot.getStyleClass().addAll("status-dot", UserStatus.OFFLINE.getCssClass());
        statusDot.setStyle("-fx-stroke: -color-bg-subtle; -fx-stroke-width: 2;");

        // Stack is 2px larger than avatarBg on each side so the dot overlaps the edge
        StackPane avatarStack = new StackPane(avatarBg, statusDot);
        avatarStack.setMinSize(36, 36);
        avatarStack.setMaxSize(36, 36);
        avatarStack.setAlignment(Pos.CENTER);
        StackPane.setAlignment(statusDot, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(statusDot, new Insets(0, 0, 1, 0));

        // ── Name + status text ────────────────────────────────────────────────
        nameLabel = new Label();
        nameLabel.setId("chatChannelName");
        nameLabel.setStyle("""
                -fx-font-weight: bold;
                -fx-font-size: 14px;
                -fx-text-fill: -color-fg-default;
                """);

        statusLabel = new Label(UserStatus.OFFLINE.getValue());
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        VBox textBox = new VBox(1, nameLabel, statusLabel);
        textBox.setAlignment(Pos.CENTER_LEFT);

        HBox content = new HBox(10, avatarStack, textBox);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(0, 0, 0, 16));
        HBox.setHgrow(content, Priority.ALWAYS);

        avatarStack.setCursor(Cursor.HAND);
        avatarStack.setOnMouseClicked(e -> openPartnerProfile());
        textBox.setCursor(Cursor.HAND);
        textBox.setOnMouseClicked(e -> openPartnerProfile());

        getChildren().add(content);
    }

    private void openPartnerProfile() {
        if (currentPartnerId != null) App.showModal(new UserProfileModal(currentPartnerId, false));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void refreshStatus() {
        if (pendingStatusId != null) statusService.restart();
    }

    public void setPartner(UUID partnerId, String initialUsername) {
        currentPartnerId = partnerId;
        // Reset to placeholder state
        nameLabel.setText(initialUsername != null ? initialUsername : "…");
        statusLabel.setText("…");
        initialsLabel.setText(initial(initialUsername));
        initialsLabel.setVisible(true);
        imageCircle.setVisible(false);
        applyStatusDot(UserStatus.OFFLINE);
        applyAvatarBgColor(initialUsername);

        // Apply cached avatar immediately if present
        AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(partnerId);
        if (cached != null) applyAvatar(cached);

        // Resolve avatar async
        App.getAvatarCache().resolve(partnerId).thenAccept(cu -> {
            if (cu == null) return;
            Platform.runLater(() -> {
                nameLabel.setText(cu.username());
                applyAvatar(cu);
            });
        });

        // Fetch partner's current status
        pendingStatusId = partnerId;
        statusService.setOnSucceeded(e -> {
            UserStatus s = statusService.getValue();
            if (s != null) applyStatusDot(s);
        });
        statusService.restart();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyAvatar(AvatarCache.CachedUser cu) {
        if (cu.avatar() != null && cu.avatar().length > 0) {
            try {
                Image img = new Image(new ByteArrayInputStream(cu.avatar()), 32, 32, true, true);
                if (!img.isError()) {
                    imageCircle.setFill(new ImagePattern(img));
                    imageCircle.setVisible(true);
                    initialsLabel.setVisible(false);
                    avatarBg.setStyle("-fx-background-color: transparent; -fx-background-radius: 16px;");
                    return;
                }
            } catch (Exception ignored) {}
        }
        // Fallback: show initial letter over name-derived background
        initialsLabel.setText(initial(cu.username()));
        initialsLabel.setVisible(true);
        imageCircle.setVisible(false);
        applyAvatarBgColor(cu.username());
    }

    private void applyStatusDot(UserStatus status) {
        if (status == null) status = UserStatus.OFFLINE;
        for (UserStatus s : UserStatus.values()) statusDot.getStyleClass().remove(s.getCssClass());
        statusDot.getStyleClass().add(status.getCssClass());
        statusDot.setStyle("-fx-stroke: -color-bg-subtle; -fx-stroke-width: 2;");
        statusLabel.setText(status.getValue());
    }

    private void applyAvatarBgColor(String name) {
        avatarBg.setStyle(
            "-fx-background-color: " + AvatarColor.forName(name) + ";" +
            "-fx-background-radius: 16px;"
        );
    }

    private static String initial(String name) {
        return (name != null && !name.isEmpty())
                ? String.valueOf(name.charAt(0)).toUpperCase() : "?";
    }
}
