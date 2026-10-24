package komm.ui.modals.installationsettings;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.summary.InstallationAccessTokenSummary;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.ConfirmationModal;
import komm.ui.utils.IconColorUtil;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignK;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import javafx.animation.FadeTransition;
import javafx.util.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AccessTokensTab implements InstallationSettingsTab {

    private static final double TICKET_GAP = 12;
    private static final double TICKET_HEIGHT = 104;
    private static final double TICKET_TOP_HEIGHT = 62;

    private final InstallationSettingsContext ctx;
    private final List<InstallationAccessTokenSummary> tokens = new ArrayList<>();
    private final GridPane grid = new GridPane();
    private final java.util.Map<UUID, Node> ticketNodes = new java.util.HashMap<>();
    private final Button generateTile = buildGenerateTile();
    private final VBox root;
    private boolean loaded = false;
    private boolean loading = false;
    private boolean generating = false;

    private final Service<List<InstallationAccessTokenSummary>> loadService = new Service<>() {
        @Override
        protected Task<List<InstallationAccessTokenSummary>> createTask() {
            return new Task<>() {
                @Override
                protected List<InstallationAccessTokenSummary> call() throws Exception {
                    return App.getServices().hub().getInstallationService()
                            .getAccessTokens(ctx.installation().getInstallationId());
                }
            };
        }
    };

    private final Service<InstallationAccessTokenSummary> generateService = new Service<>() {
        @Override
        protected Task<InstallationAccessTokenSummary> createTask() {
            return new Task<>() {
                @Override
                protected InstallationAccessTokenSummary call() throws Exception {
                    return App.getServices().hub().getInstallationService()
                            .generateAccessToken(ctx.installation().getInstallationId());
                }
            };
        }
    };

    public AccessTokensTab(InstallationSettingsContext ctx) {
        this.ctx = ctx;
        this.root = buildRoot();
    }

    @Override public String   name()    { return "Access Tokens"; }
    @Override public String   description() { return "One-time codes that give another user read-only access"; }
    @Override public FontIcon icon()    { return new FontIcon(MaterialDesignK.KEY_OUTLINE); }
    @Override public Node     getPane() { return root; }

    @Override
    public void onShown() {
        if (loaded) return;
        loaded = true;
        loadTokens();
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private VBox buildRoot() {
        grid.setHgap(TICKET_GAP);
        grid.setVgap(TICKET_GAP);
        ColumnConstraints col = new ColumnConstraints();
        col.setPercentWidth(50);
        col.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(col, col);

        VBox content = new VBox(grid);
        content.setPadding(new Insets(18, 24, 18, 24));
        content.setFillWidth(true);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox vbox = new VBox(scroll);
        vbox.setFillWidth(true);
        render();
        return vbox;
    }

    private void render() {
        grid.getChildren().clear();
        List<Node> cells = new ArrayList<>();
        cells.add(generateTile);
        if (loading) {
            cells.add(buildLoadingTile());
        } else {
            for (var token : tokens) cells.add(ticketNodes.computeIfAbsent(token.getTokenId(), id -> buildTicket(token)));
        }
        for (int i = 0; i < cells.size(); i++) {
            grid.add(cells.get(i), i % 2, i / 2);
        }
        if (!loading && tokens.isEmpty()) {
            Label empty = new Label("No active tokens yet. Generate one to share access.");
            empty.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
            empty.setPadding(new Insets(6, 0, 0, 0));
            grid.add(empty, 0, 1, 2, 1);
        }
    }

    private Button buildGenerateTile() {
        Label title = new Label("Generate token");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        Label hint = new Label("Copied automatically");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        VBox box = new VBox(4, IconColorUtil.colored(MaterialDesignP.PLUS, "-color-accent-fg", 20), title, hint);
        box.setAlignment(Pos.CENTER);

        Button tile = new Button(null, box);
        tile.setFocusTraversable(false);
        tile.setMaxWidth(Double.MAX_VALUE);
        tile.setMinHeight(TICKET_HEIGHT);
        tile.setPrefHeight(TICKET_HEIGHT);
        String base = "-fx-background-radius: 4px; -fx-border-radius: 4px; -fx-border-width: 1px;"
                + "-fx-border-style: segments(6, 4); -fx-cursor: hand;";
        String idle = "-fx-background-color: transparent; -fx-border-color: -color-fg-subtle;" + base;
        String hover = "-fx-background-color: -color-accent-subtle; -fx-border-color: -color-accent-emphasis;" + base;
        tile.setStyle(idle);
        tile.hoverProperty().addListener((obs, was, is) -> tile.setStyle(is ? hover : idle));
        tile.setOnAction(e -> handleGenerate());
        return tile;
    }

    private Node buildLoadingTile() {
        ProgressIndicator pi = new ProgressIndicator();
        pi.setMaxSize(22, 22);
        StackPane box = new StackPane(pi);
        box.setMinHeight(TICKET_HEIGHT);
        return box;
    }

    /** Ticket-shaped card: rounded body with a semicircular notch cut into each side at the perforation. */
    private static final class TicketPane extends StackPane {
        private static final double NOTCH = 7;
        private static final double CORNER = 4;

        private final Path body = new Path();
        private final Line perforation = new Line();
        private final Node top;

        TicketPane(Node top, Node foot) {
            this.top = top;
            VBox content = new VBox(top, foot);
            body.setStyle("-fx-fill: -color-bg-subtle; -fx-stroke: -color-border-default; -fx-stroke-width: 1;");
            body.setManaged(false);
            body.setMouseTransparent(true);
            perforation.setStyle("-fx-stroke: -color-border-default; -fx-stroke-width: 1;");
            perforation.getStrokeDashArray().setAll(4.0, 4.0);
            perforation.setManaged(false);
            perforation.setMouseTransparent(true);
            getChildren().addAll(body, perforation, content);
            setAlignment(Pos.TOP_LEFT);
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            // Inset by half the stroke so the outline is not clipped (it read as a dashed edge).
            double w = getWidth() - 0.5, h = getHeight() - 0.5;
            double y = top.getBoundsInParent().getMaxY();
            double c = CORNER, r = NOTCH;
            body.getElements().setAll(
                    new MoveTo(c, 0.5),
                    new HLineTo(w - c),
                    new ArcTo(c, c, 0, w, c, false, true),
                    new VLineTo(y - r),
                    new ArcTo(r, r, 0, w, y + r, false, false),
                    new VLineTo(h - c),
                    new ArcTo(c, c, 0, w - c, h, false, true),
                    new HLineTo(c),
                    new ArcTo(c, c, 0, 0.5, h - c, false, true),
                    new VLineTo(y + r),
                    new ArcTo(r, r, 0, 0.5, y - r, false, false),
                    new VLineTo(c),
                    new ArcTo(c, c, 0, c, 0.5, false, true),
                    new ClosePath());
            perforation.setStartX(r + 6);
            perforation.setEndX(w - r - 6);
            perforation.setStartY(y);
            perforation.setEndY(y);
        }
    }

    private Node buildTicket(InstallationAccessTokenSummary token) {
        Label code = new Label(token.getCode());
        code.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 20px; -fx-font-weight: bold;"
                + "-fx-text-fill: -color-accent-fg; -fx-cursor: hand;");
        Tooltip.install(code, new Tooltip("Click to copy"));
        code.setOnMouseClicked(e -> copyCode(token));
        HBox top = new HBox(code);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 16, 0, 18));
        top.setMinHeight(TICKET_TOP_HEIGHT);
        top.setPrefHeight(TICKET_TOP_HEIGHT);

        Label created = new Label("Created " + formatAge(token.getCreatedAt()));
        created.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button copyBtn = new Button(null, IconColorUtil.colored(MaterialDesignC.CONTENT_COPY, "-color-fg-muted", 15));
        copyBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
        copyBtn.setFocusTraversable(false);
        Tooltip.install(copyBtn, new Tooltip("Copy code"));
        copyBtn.setOnAction(e -> copyCode(token));

        Button deleteBtn = new Button(null, IconColorUtil.colored(MaterialDesignD.DELETE_OUTLINE, "-color-danger-fg", 16));
        deleteBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
        deleteBtn.setFocusTraversable(false);
        Tooltip.install(deleteBtn, new Tooltip("Revoke token"));
        deleteBtn.setOnAction(e -> confirmDelete(token));

        HBox foot = new HBox(2, created, spacer, copyBtn, deleteBtn);
        foot.setAlignment(Pos.CENTER_LEFT);
        foot.setPadding(new Insets(0, 10, 0, 18));
        foot.setMinHeight(TICKET_HEIGHT - TICKET_TOP_HEIGHT);
        foot.setPrefHeight(TICKET_HEIGHT - TICKET_TOP_HEIGHT);

        TicketPane ticket = new TicketPane(top, foot);
        ticket.setMinHeight(TICKET_HEIGHT);
        ticket.setPrefHeight(TICKET_HEIGHT);
        ticket.setMaxWidth(Double.MAX_VALUE);
        return ticket;
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void loadTokens() {
        loading = true;
        render();
        loadService.setOnSucceeded(e -> {
            loading = false;
            tokens.clear();
            tokens.addAll(loadService.getValue());
            // Oldest first: a new token lands at the end, so nothing already on screen moves.
            tokens.sort(java.util.Comparator.comparing(InstallationAccessTokenSummary::getCreatedAt,
                    java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
            render();
        });
        loadService.setOnFailed(e -> {
            loading = false;
            loaded = false;
            tokens.clear();
            render();
            new CustomNotification("Access Tokens",
                    HttpStatusException.extractMessage(loadService.getException()),
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
        });
        loadService.restart();
    }

    // ── Generate ──────────────────────────────────────────────────────────────

    private void handleGenerate() {
        if (generating) return;
        generating = true;
        generateTile.setDisable(true);
        generateService.setOnSucceeded(e -> {
            generating = false;
            generateTile.setDisable(false);
            InstallationAccessTokenSummary token = generateService.getValue();
            tokens.add(token);
            render();
            Node ticket = ticketNodes.get(token.getTokenId());
            if (ticket != null) {
                ticket.setOpacity(0);
                FadeTransition fade = new FadeTransition(Duration.millis(220), ticket);
                fade.setToValue(1);
                fade.play();
            }
            copyToClipboard(token.getCode());
            new CustomNotification("Token Generated",
                    "Code \"" + token.getCode() + "\" copied to clipboard.",
                    new FontIcon(MaterialDesignK.KEY_OUTLINE)).showNotification();
        });
        generateService.setOnFailed(e -> {
            generating = false;
            generateTile.setDisable(false);
            new CustomNotification("Generate Token",
                    HttpStatusException.extractMessage(generateService.getException()),
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
        });
        generateService.restart();
    }

    private void copyCode(InstallationAccessTokenSummary token) {
        copyToClipboard(token.getCode());
        new CustomNotification("Copied", "Token code copied to clipboard.",
                new FontIcon(MaterialDesignC.CONTENT_COPY)).showNotification();
    }

    private void copyToClipboard(String text) {
        javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text);
        cb.setContent(cc);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void confirmDelete(InstallationAccessTokenSummary token) {
        App.showModal(new ConfirmationModal(
                "Revoke Token",
                "Are you sure you want to revoke \"" + token.getCode() + "\"? "
                        + "Anyone who has not used this code yet will no longer be able to.",
                new FontIcon(MaterialDesignD.DELETE_OUTLINE),
                () -> deleteToken(token)));
    }

    private void deleteToken(InstallationAccessTokenSummary token) {
        UUID tokenId = token.getTokenId();
        Service<Void> svc = new Service<>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        App.getServices().hub().getInstallationService()
                                .deleteAccessToken(ctx.installation().getInstallationId(), tokenId);
                        return null;
                    }
                };
            }
        };

        svc.setOnSucceeded(e -> {
            tokens.remove(token);
            ticketNodes.remove(tokenId);
            render();
            new CustomNotification("Token Revoked", "The access token has been deleted.",
                    new FontIcon(MaterialDesignD.DELETE)).showNotification();
        });

        svc.setOnFailed(e -> {
            new CustomNotification("Revoke Token",
                    HttpStatusException.extractMessage(svc.getException()),
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
        });

        svc.start();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatAge(LocalDateTime createdAt) {
        if (createdAt == null) return "recently";
        long seconds = java.time.Duration.between(createdAt, LocalDateTime.now(ZoneOffset.UTC)).getSeconds();
        if (seconds < 60) return "just now";
        if (seconds < 3600) return (seconds / 60) + " min ago";
        if (seconds < 86400) return (seconds / 3600) + " h ago";
        return (seconds / 86400) + " d ago";
    }
}
