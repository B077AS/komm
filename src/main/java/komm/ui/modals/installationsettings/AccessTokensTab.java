package komm.ui.modals.installationsettings;

import atlantafx.base.theme.Styles;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.summary.InstallationAccessTokenSummary;
import komm.ui.customnodes.CustomNotification;
import komm.ui.utils.IconColorUtil;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignK;

import java.util.List;
import java.util.UUID;

public class AccessTokensTab implements InstallationSettingsTab {

    private final InstallationSettingsContext ctx;
    private final VBox listContainer = new VBox(8);
    private boolean loaded = false;
    private Button generateBtn;
    private final VBox root;

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
    @Override public String   description() { return "Create and revoke API access tokens"; }
    @Override public FontIcon icon()    { return new FontIcon(MaterialDesignK.KEY_OUTLINE); }
    @Override public Node     getPane() { return root; }

    @Override
    public void onShown() {
        if (loaded) return;
        loaded = true;
        loadTokens();
    }

    // ── Root: sticky header + separator + scrollable list ────────────────────

    private VBox buildRoot() {
        listContainer.setFillWidth(true);
        listContainer.setMaxWidth(Double.MAX_VALUE);

        VBox vbox = new VBox(0);
        vbox.setFillWidth(true);
        vbox.getChildren().addAll(
                buildStickyHeader(),
                new Separator(Orientation.HORIZONTAL),
                buildListScrollPane()
        );
        return vbox;
    }

    // ── Sticky header ─────────────────────────────────────────────────────────

    private VBox buildStickyHeader() {
        // Icon
        FontIcon keyIcon = new FontIcon(MaterialDesignK.KEY_CHAIN_VARIANT);
        keyIcon.setIconSize(26);
        StackPane iconBox = new StackPane(keyIcon);
        iconBox.setMinSize(48, 48);
        iconBox.setMaxSize(48, 48);
        iconBox.setStyle(
                "-fx-background-color: -color-accent-subtle;" +
                "-fx-background-radius: 12px;" +
                "-fx-border-color: -color-accent-muted;" +
                "-fx-border-radius: 12px;" +
                "-fx-border-width: 1.5px;"
        );

        // Text
        Label titleLbl = new Label("One-time Access Tokens");
        titleLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        Label descLbl = new Label("Generate a single-use code to grant another user read-only access to this installation.");
        descLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted; -fx-wrap-text: true;");
        descLbl.setMaxWidth(Double.MAX_VALUE);
        VBox textBlock = new VBox(3, titleLbl, descLbl);
        textBlock.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textBlock, Priority.ALWAYS);

        // Button — pinned to pref width so it never gets compressed
        generateBtn = new Button("Generate Token", new FontIcon(MaterialDesignK.KEY_PLUS));
        generateBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        generateBtn.setFocusTraversable(false);
        generateBtn.setMinWidth(Region.USE_PREF_SIZE);
        generateBtn.setOnAction(e -> handleGenerate());

        HBox topRow = new HBox(14, iconBox, textBlock, generateBtn);
        topRow.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(topRow);
        header.setPadding(new Insets(20, 28, 20, 28));
        header.setMinHeight(Region.USE_PREF_SIZE);
        header.setMaxHeight(Region.USE_PREF_SIZE);
        return header;
    }

    // ── Scrollable list ───────────────────────────────────────────────────────

    private ScrollPane buildListScrollPane() {
        VBox wrapper = new VBox(listContainer);
        wrapper.setPadding(new Insets(14, 28, 14, 28));
        wrapper.setFillWidth(true);

        ScrollPane scroll = new ScrollPane(wrapper);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void loadTokens() {
        listContainer.getChildren().setAll(buildLoadingRow());
        loadService.setOnSucceeded(e -> populateList(loadService.getValue()));
        loadService.setOnFailed(e -> {
            listContainer.getChildren().setAll(emptyLabel("Failed to load tokens."));
            new CustomNotification("Access Tokens",
                    HttpStatusException.extractMessage(loadService.getException()),
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
        });
        loadService.restart();
    }

    private void populateList(List<InstallationAccessTokenSummary> tokens) {
        listContainer.getChildren().clear();
        if (tokens.isEmpty()) {
            listContainer.getChildren().add(emptyLabel("No active tokens. Generate one to share access."));
            return;
        }
        for (var token : tokens) {
            listContainer.getChildren().add(buildTokenRow(token));
        }
    }

    // ── Generate ──────────────────────────────────────────────────────────────

    private void handleGenerate() {
        generateBtn.setDisable(true);
        generateService.setOnSucceeded(e -> {
            generateBtn.setDisable(false);
            InstallationAccessTokenSummary token = generateService.getValue();
            if (listContainer.getChildren().size() == 1
                    && listContainer.getChildren().get(0) instanceof Label) {
                listContainer.getChildren().clear();
            }
            listContainer.getChildren().add(0, buildTokenRow(token));
            copyToClipboard(token.getCode());
            new CustomNotification("Token Generated",
                    "Code \"" + token.getCode() + "\" copied to clipboard.",
                    new FontIcon(MaterialDesignK.KEY_OUTLINE)).showNotification();
        });
        generateService.setOnFailed(e -> {
            generateBtn.setDisable(false);
            new CustomNotification("Generate Token",
                    HttpStatusException.extractMessage(generateService.getException()),
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
        });
        generateService.restart();
    }

    private void copyToClipboard(String text) {
        javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text);
        cb.setContent(cc);
    }

    // ── Token row ─────────────────────────────────────────────────────────────

    private HBox buildTokenRow(InstallationAccessTokenSummary token) {
        Label codeLabel = new Label(token.getCode());
        codeLabel.setStyle(
                "-fx-font-family: 'Monospaced'; -fx-font-size: 15px;" +
                "-fx-font-weight: bold; -fx-text-fill: -color-accent-fg;"
        );

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button copyBtn = new Button(null, new FontIcon(MaterialDesignC.CONTENT_COPY));
        copyBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
        copyBtn.setFocusTraversable(false);
        copyBtn.setMinWidth(Region.USE_PREF_SIZE);
        Tooltip.install(copyBtn, new Tooltip("Copy code"));
        copyBtn.setOnAction(e -> {
            copyToClipboard(token.getCode());
            new CustomNotification("Copied", "Token code copied to clipboard.",
                    new FontIcon(MaterialDesignC.CONTENT_COPY)).showNotification();
        });

        Button deleteBtn = new Button(null, IconColorUtil.colored(MaterialDesignD.DELETE_OUTLINE, "-color-danger-fg", 18));
        deleteBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
        deleteBtn.setFocusTraversable(false);
        deleteBtn.setMinWidth(Region.USE_PREF_SIZE);
        Tooltip.install(deleteBtn, new Tooltip("Revoke token"));

        HBox row = new HBox(10, codeLabel, spacer, copyBtn, deleteBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 14, 10, 14));
        row.setMaxWidth(Double.MAX_VALUE);
        row.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                "-fx-border-color: -color-border-default;" +
                "-fx-border-radius: 8; -fx-background-radius: 8;"
        );

        deleteBtn.setOnAction(e -> handleDelete(token.getTokenId(), row));
        return row;
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void handleDelete(UUID tokenId, HBox row) {
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
            listContainer.getChildren().remove(row);
            if (listContainer.getChildren().isEmpty()) {
                listContainer.getChildren().add(emptyLabel("No active tokens. Generate one to share access."));
            }
            new CustomNotification("Token Revoked", "The access token has been deleted.",
                    new FontIcon(MaterialDesignD.DELETE)).showNotification();
        });

        svc.setOnFailed(e -> new CustomNotification("Revoke Token",
                HttpStatusException.extractMessage(svc.getException()),
                new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification());

        svc.start();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Label emptyLabel(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");
        lbl.setPadding(new Insets(8, 0, 0, 0));
        return lbl;
    }

    private HBox buildLoadingRow() {
        ProgressIndicator pi = new ProgressIndicator();
        pi.setMaxSize(20, 20);
        HBox row = new HBox(pi);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 0, 0, 0));
        return row;
    }
}
