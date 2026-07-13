package komm.ui.modals.serversettings;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.ImagePattern;
import javafx.scene.shape.Circle;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.response.InviteSummary;
import komm.model.dto.summary.ServerSummary;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.ConfirmationModal;
import komm.ui.utils.IconColorUtil;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static komm.ui.modals.serversettings.ServerSettingsUi.sectionLabel;

/** "Invites" tab: read-only list of active invite links with per-invite deletion. */
@Slf4j
public class InvitesTab implements ServerSettingsTab {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm");

    private final ServerSummary serverDetails;
    private final VBox pane;
    private final VBox listBox = new VBox(8);

    private final List<InviteSummary> invites = new ArrayList<>();
    private boolean loaded = false;
    private boolean ascending = true; // soonest-expiring first
    private Button sortButton;

    private final Service<List<InviteSummary>> loadService = new Service<>() {
        @Override
        protected Task<List<InviteSummary>> createTask() {
            return new Task<>() {
                @Override
                protected List<InviteSummary> call() throws Exception {
                    List<InviteSummary> result = App.getServices().hub().getInviteService()
                            .getServerInvites(serverDetails.getServerId());
                    if (result != null && !result.isEmpty()) {
                        App.getAvatarCache().resolveAll(
                                result.stream().map(InviteSummary::getCreatorId).toList()).join();
                    }
                    return result;
                }
            };
        }
    };

    public InvitesTab(ServerSettingsContext ctx) {
        this.serverDetails = ctx.serverDetails();
        this.pane = buildPane();
    }

    // ── ServerSettingsTab ──────────────────────────────────────────────────────

    @Override public String name() { return "Invites"; }
    @Override public String description() { return "View and revoke active invite links"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignL.LINK_VARIANT); }
    @Override public Node getPane() { return pane; }

    @Override
    public void onShown() {
        if (loaded) return;
        loaded = true;
        loadInvites();
    }

    // ── Pane ───────────────────────────────────────────────────────────────────

    private VBox buildPane() {
        Label title = sectionLabel("ACTIVE INVITES");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        sortButton = new Button();
        sortButton.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
        sortButton.setFocusTraversable(false);
        sortButton.setOnAction(e -> {
            ascending = !ascending;
            updateSortButton();
            render();
        });
        updateSortButton();

        HBox header = new HBox(8, title, spacer, sortButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 4, 0));

        Label loadingLbl = new Label("Loading…");
        loadingLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted; -fx-padding: 4 0 4 4;");
        listBox.getChildren().add(loadingLbl);

        ScrollPane scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox root = new VBox(10, header, scroll);
        root.setPadding(new Insets(20, 24, 16, 24));
        VBox.setVgrow(root, Priority.ALWAYS);
        return root;
    }

    private void updateSortButton() {
        FontIcon icon = new FontIcon(ascending
                ? MaterialDesignC.CHEVRON_UP : MaterialDesignC.CHEVRON_DOWN);
        sortButton.setGraphic(icon);
        sortButton.setText(ascending ? "Expiry: soonest first" : "Expiry: latest first");
    }

    // ── Loading + rendering ────────────────────────────────────────────────────

    private void loadInvites() {
        loadService.setOnSucceeded(e -> {
            invites.clear();
            List<InviteSummary> value = loadService.getValue();
            if (value != null) invites.addAll(value);
            render();
        });
        loadService.setOnFailed(e -> {
            log.error("Failed to load invites: {}",
                    loadService.getException() != null ? loadService.getException().getMessage() : "unknown");
            listBox.getChildren().setAll(emptyLabel("Failed to load invites."));
        });
        loadService.start();
    }

    private void render() {
        listBox.getChildren().clear();
        if (invites.isEmpty()) {
            listBox.getChildren().add(emptyLabel("No active invites."));
            return;
        }

        // Always keep never-expiring invites at the end, regardless of sort direction.
        Comparator<InviteSummary> byExpiry = (a, b) -> {
            LocalDateTime ea = a.getExpiresAt();
            LocalDateTime eb = b.getExpiresAt();
            if (ea == null && eb == null) return 0;
            if (ea == null) return 1;
            if (eb == null) return -1;
            return ascending ? ea.compareTo(eb) : eb.compareTo(ea);
        };

        invites.stream().sorted(byExpiry).forEach(inv -> listBox.getChildren().add(buildInviteRow(inv)));
    }

    private HBox buildInviteRow(InviteSummary invite) {
        StackPane avatarBg = buildAvatarBg(34);
        AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(invite.getCreatorId());
        if (cached != null) fillAvatarBg(avatarBg, cached, 34);

        Label codeLbl = new Label(invite.getCode());
        codeLbl.setStyle(
                "-fx-font-family: 'Monospaced'; -fx-font-size: 14px;" +
                "-fx-font-weight: bold; -fx-text-fill: -color-accent-fg;"
        );

        Label byLbl = new Label("by " + (invite.getCreatorUsername() != null ? invite.getCreatorUsername()
                : (cached != null && cached.username() != null ? cached.username() : "Unknown")));
        byLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        HBox topLine = new HBox(8, codeLbl, byLbl);
        topLine.setAlignment(Pos.CENTER_LEFT);

        String expiry = invite.getExpiresAt() != null
                ? "Expires " + DATE_FMT.format(invite.getExpiresAt())
                : "Never expires";
        Label metaLbl = new Label(expiry + "  ·  " + invite.getUses() + " use" + (invite.getUses() == 1 ? "" : "s"));
        metaLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        VBox textBox = new VBox(2, topLine, metaLbl);
        textBox.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button copyBtn = new Button(null, new FontIcon(MaterialDesignC.CONTENT_COPY));
        copyBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
        copyBtn.setFocusTraversable(false);
        copyBtn.setMinWidth(Region.USE_PREF_SIZE);
        copyBtn.setTooltip(new Tooltip("Copy invite code"));
        copyBtn.setOnAction(e -> {
            copyToClipboard(invite.getCode());
            new CustomNotification("Copied", "Invite code copied to clipboard.",
                    new FontIcon(MaterialDesignC.CONTENT_COPY)).showNotification();
        });

        Button deleteBtn = new Button(null, IconColorUtil.colored(MaterialDesignD.DELETE_OUTLINE, "-color-danger-fg", 18));
        deleteBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
        deleteBtn.setFocusTraversable(false);
        deleteBtn.setMinWidth(Region.USE_PREF_SIZE);
        deleteBtn.setTooltip(new Tooltip("Delete invite"));
        deleteBtn.setOnAction(e -> App.showModal(new ConfirmationModal(
                "Delete Invite",
                "Delete invite \"" + invite.getCode() + "\"? People will no longer be able to join with it.",
                new FontIcon(MaterialDesignD.DELETE_ALERT),
                () -> executeDelete(invite))));

        HBox row = new HBox(10, avatarBg, textBox, spacer, copyBtn, deleteBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 14, 10, 14));
        row.setMaxWidth(Double.MAX_VALUE);
        row.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                "-fx-border-color: -color-border-default;" +
                "-fx-border-radius: 8; -fx-background-radius: 8;"
        );

        if (cached == null) {
            App.getAvatarCache().resolve(invite.getCreatorId()).thenAccept(cu -> {
                if (cu == null) return;
                Platform.runLater(() -> {
                    if (cu.username() != null) byLbl.setText("by " + (invite.getCreatorUsername() != null
                            ? invite.getCreatorUsername() : cu.username()));
                    fillAvatarBg(avatarBg, cu, 34);
                });
            });
        }

        return row;
    }

    private void copyToClipboard(String text) {
        javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text);
        cb.setContent(cc);
    }

    private void executeDelete(InviteSummary invite) {
        Service<Void> svc = new Service<>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        App.getServices().hub().getInviteService().deleteInvite(invite.getInviteLinkId());
                        return null;
                    }
                };
            }
        };
        svc.setOnSucceeded(e -> {
            invites.removeIf(i -> i.getInviteLinkId().equals(invite.getInviteLinkId()));
            render();
            new CustomNotification("Invite Deleted", "Invite code \"" + invite.getCode() + "\" has been removed.",
                    new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE)).showNotification();
        });
        svc.setOnFailed(e -> new CustomNotification(
                "Delete Failed",
                HttpStatusException.extractMessage(svc.getException()),
                new FontIcon(MaterialDesignC.CLOSE)).showNotification());
        svc.start();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Label emptyLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted; -fx-padding: 4 0 4 4;");
        return l;
    }

    private StackPane buildAvatarBg(double size) {
        StackPane bg = new StackPane();
        bg.setPrefSize(size, size);
        bg.setMinSize(size, size);
        bg.setMaxSize(size, size);
        bg.setStyle("-fx-background-color: " + AvatarColor.forName(null) + ";" +
                "-fx-background-radius: " + (size / 2) + "px;");
        Label letter = new Label("?");
        letter.setStyle("-fx-font-size: " + (int) (size * 0.38) + "px; -fx-font-weight: bold; -fx-text-fill: white;");
        letter.setMouseTransparent(true);
        letter.setUserData("letter");
        bg.getChildren().add(letter);
        bg.setAlignment(Pos.CENTER);
        return bg;
    }

    private void fillAvatarBg(StackPane bg, AvatarCache.CachedUser cu, double size) {
        Label letter = bg.getChildren().stream()
                .filter(n -> n instanceof Label && "letter".equals(n.getUserData()))
                .map(n -> (Label) n).findFirst().orElse(null);
        if (cu.avatar() != null && cu.avatar().length > 0) {
            try {
                Image img = new Image(new ByteArrayInputStream(cu.avatar()), size, size, true, true);
                if (!img.isError()) {
                    Circle imgCircle = new Circle(size / 2);
                    imgCircle.setFill(new ImagePattern(img));
                    if (letter != null) letter.setVisible(false);
                    bg.getChildren().removeIf(n -> n instanceof Circle);
                    bg.getChildren().add(imgCircle);
                    bg.setStyle("-fx-background-color: transparent; -fx-background-radius: " + (size / 2) + "px;");
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        bg.getChildren().removeIf(n -> n instanceof Circle);
        if (letter != null) {
            String l = (cu.username() != null && !cu.username().isEmpty())
                    ? String.valueOf(cu.username().charAt(0)).toUpperCase() : "?";
            letter.setText(l);
            letter.setVisible(true);
        }
        bg.setStyle("-fx-background-color: " + AvatarColor.forName(cu.username()) + ";" +
                "-fx-background-radius: " + (size / 2) + "px;");
    }
}
