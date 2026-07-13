package komm.ui.cards;

import atlantafx.base.controls.RingProgressIndicator;
import atlantafx.base.theme.Styles;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import javafx.util.StringConverter;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.summary.InstallationSummary;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.ConfirmationModal;
import komm.ui.modals.InstallationSettingsModal;
import komm.ui.pages.HomePage;
import komm.ui.utils.FileChooserUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.io.File;
import java.nio.file.Files;

@Slf4j
public class InstallationCard extends HBox {

    @Getter
    private final InstallationSummary installation;

    private static final int ROW_HEIGHT = 90;

    private MenuButton optionsBtn;
    private HBox statsArea;
    private RingProgressIndicator downloadSpinner;

    public InstallationCard(InstallationSummary installation) {
        this.installation = installation;
        setMaxWidth(Double.MAX_VALUE);
        setMinHeight(ROW_HEIGHT);
        setMaxHeight(ROW_HEIGHT);
        setPrefHeight(ROW_HEIGHT);
        getStyleClass().add("server-row");
        initialize();
    }

    private void initialize() {
        setAlignment(Pos.CENTER_LEFT);
        setFillHeight(false);
        setPadding(new Insets(0, 10, 0, 16));
        setSpacing(0);

        // ── Icon avatar ───────────────────────────────────────────────────────
        StackPane iconAvatar = buildIconAvatar();
        HBox.setMargin(iconAvatar, new Insets(0, 16, 0, 0));

        // ── Identity column (name + ip chip + status) ─────────────────────────
        VBox identity = new VBox(6);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setMinWidth(180);
        HBox.setHgrow(identity, Priority.ALWAYS);

        Label nameLabel = new Label(installation.getInstallationName());
        nameLabel.setStyle(
                "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;"
        );
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        HBox metaRow = buildMetaRow();
        identity.getChildren().addAll(nameLabel, metaRow);

        // ── Stats area (HOSTED + PORT) ─────────────────────────────────────────
        statsArea = new HBox(0);
        statsArea.setAlignment(Pos.CENTER);

        VBox hostedCell = buildStatCell("SERVERS", String.valueOf(installation.getHostedServersCount()));

        Separator statDivider = vDivider();

        String portText = installation.getInstallationPort() != 0
                ? String.valueOf(installation.getInstallationPort())
                : "—";
        VBox portCell = buildStatCell("PORT", portText);

        statsArea.getChildren().addAll(hostedCell, statDivider, portCell);

        // ── Options menu ──────────────────────────────────────────────────────
        optionsBtn = createOptionsMenu();
        HBox.setMargin(optionsBtn, new Insets(0, 0, 0, 4));

        getChildren().addAll(iconAvatar, identity, statsArea, optionsBtn);
        setupInteractions();
    }

    // ── Icon avatar ───────────────────────────────────────────────────────────

    private StackPane buildIconAvatar() {
        StackPane pane = new StackPane();
        pane.setMinSize(62, 62);
        pane.setMaxSize(62, 62);
        pane.setPrefSize(62, 62);
        pane.setStyle(
                "-fx-background-color: -color-accent-subtle;" +
                "-fx-background-radius: 14px;" +
                "-fx-border-color: -color-accent-muted;" +
                "-fx-border-radius: 14px;" +
                "-fx-border-width: 1px;"
        );
        FontIcon icon = new FontIcon(MaterialDesignS.SERVER);
        pane.getChildren().add(icon);
        return pane;
    }

    // ── Meta row (status badge + IP chip) ────────────────────────────────────

    private HBox buildMetaRow() {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(buildStatusBadge(), buildIpChip());
        return row;
    }

    private HBox buildStatusBadge() {
        String rawStatus = installation.getStatus() != null ? installation.getStatus().toString() : "";
        String dotCssClass, labelText, bgColor;
        switch (rawStatus) {
            case "ONLINE"       -> { dotCssClass = "server-status-online";        labelText = "Online";       bgColor = "-color-success-subtle"; }
            case "OFFLINE"      -> { dotCssClass = "server-status-offline";       labelText = "Offline";      bgColor = "-color-danger-subtle";  }
            case "NOT_VERIFIED" -> { dotCssClass = "server-status-not-verified";  labelText = "Not Verified"; bgColor = "-color-warning-subtle"; }
            default             -> { dotCssClass = "server-status-unknown";       labelText = "Unknown";      bgColor = "-color-neutral-subtle"; }
        }
        Circle dot = new Circle(3);
        dot.getStyleClass().add(dotCssClass);
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        HBox badge = new HBox(5, dot, lbl);
        badge.setAlignment(Pos.CENTER);
        badge.setPadding(new Insets(2, 9, 2, 9));
        badge.setMaxWidth(Region.USE_PREF_SIZE);
        badge.setStyle("-fx-background-color: " + bgColor + "; -fx-background-radius: 4px;");
        return badge;
    }

    private HBox buildIpChip() {
        String ipText = installation.getIpAddress() != null && !installation.getIpAddress().isBlank()
                ? installation.getIpAddress()
                : "no address";

        Label ipLabel = new Label(ipText);
        ipLabel.setStyle(
                "-fx-font-size: 12px;" +
                "-fx-font-family: 'Monospaced';" +
                "-fx-font-weight: bold;" +
                "-fx-text-fill: -color-accent-fg;"
        );

        HBox chip = new HBox(6, ipLabel);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setPadding(new Insets(3, 10, 3, 8));
        chip.setMaxWidth(Region.USE_PREF_SIZE);
        chip.setStyle("-fx-background-color: -color-accent-subtle; -fx-background-radius: 5px;");
        return chip;
    }

    // ── Stat cell ─────────────────────────────────────────────────────────────

    private VBox buildStatCell(String labelText, String valueText) {
        Label value = new Label(valueText);
        value.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 8.5px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");

        VBox cell = new VBox(2, value, label);
        cell.setAlignment(Pos.CENTER);
        cell.setMinWidth(76);
        cell.setPadding(new Insets(0, 12, 0, 12));
        return cell;
    }

    // ── Divider ───────────────────────────────────────────────────────────────

    private Separator vDivider() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.setMaxHeight(34);
        s.setOpacity(0.7);
        return s;
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    private MenuButton createOptionsMenu() {
        MenuButton btn = new MenuButton();
        btn.setGraphic(new FontIcon(MaterialDesignD.DOTS_VERTICAL));
        btn.setFocusTraversable(false);
        btn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_ICON);

        MenuItem settingsItem = new MenuItem("View Info");
        settingsItem.setGraphic(new FontIcon(MaterialDesignC.COG));
        settingsItem.setOnAction(e -> App.showModal(new InstallationSettingsModal(installation)));

        if (installation.isOwner()) {
            settingsItem.setText("Settings");

            MenuItem downloadJarItem = new MenuItem("Download JAR");
            downloadJarItem.setGraphic(new FontIcon(MaterialDesignD.DOWNLOAD));
            downloadJarItem.setOnAction(e -> handleDownloadJar());

            MenuItem deleteItem = new MenuItem("Delete Installation");
            deleteItem.setGraphic(new FontIcon(MaterialDesignD.DELETE_OUTLINE));
            deleteItem.setOnAction(e -> {
                String message = "Are you sure you want to delete \"" + installation.getInstallationName() + "\"? "
                        + "All servers hosted on it will be permanently removed.";
                App.showModal(new ConfirmationModal(
                        "Delete Installation",
                        message,
                        new FontIcon(MaterialDesignD.DELETE_OUTLINE),
                        this::executeDelete
                ));
            });

            btn.getItems().addAll(settingsItem, downloadJarItem, new SeparatorMenuItem(), deleteItem);
        } else {
            btn.getItems().add(settingsItem);
        }

        return btn;
    }

    // ── JAR download ──────────────────────────────────────────────────────────

    private void handleDownloadJar() {
        if (downloadSpinner != null) return;

        downloadSpinner = new RingProgressIndicator(0, false);
        downloadSpinner.setMinSize(22, 22);
        downloadSpinner.setMaxSize(22, 22);
        downloadSpinner.setStringConverter(new StringConverter<>() {
            @Override public String toString(Double d) { return ""; }
            @Override public Double fromString(String s) { return 0d; }
        });
        HBox.setMargin(downloadSpinner, new Insets(0, 12, 0, 12));
        int idx = getChildren().indexOf(statsArea);
        if (idx >= 0) getChildren().add(idx, downloadSpinner);

        Service<byte[]> svc = new Service<>() {
            @Override
            protected Task<byte[]> createTask() {
                return new Task<>() {
                    @Override
                    protected byte[] call() throws Exception {
                        return App.getServices().hub().getInstallationService()
                                .downloadInstallationJar(installation.getInstallationId(),
                                        progress -> Platform.runLater(() -> downloadSpinner.setProgress(progress)));
                    }
                };
            }
        };

        svc.setOnSucceeded(e -> {
            byte[] bytes = svc.getValue();
            clearDownloadSpinner();
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName("komm-installation.jar");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR Files", "*.jar"));
            File dest = FileChooserUtil.showSaveDialog(chooser, getScene().getWindow());
            if (dest != null) {
                Thread.ofVirtual().start(() -> {
                    try {
                        Files.write(dest.toPath(), bytes);
                    } catch (Exception ex) {
                        log.error("Failed to save installation JAR", ex);
                    }
                });
            }
        });

        svc.setOnFailed(e -> {
            Throwable ex = svc.getException();
            log.error("Failed to download installation JAR", ex);
            clearDownloadSpinner();
            String msg = HttpStatusException.extractMessage(ex);
            new CustomNotification("Download JAR", msg, new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE))
                    .showNotification();
        });
        svc.start();
    }

    private void clearDownloadSpinner() {
        if (downloadSpinner == null) return;
        getChildren().remove(downloadSpinner);
        downloadSpinner = null;
    }

    // ── Interactions ──────────────────────────────────────────────────────────

    private void setupInteractions() {
        setOnMouseEntered(e -> animateCard(1.03));
        setOnMouseExited(e -> {
            if (optionsBtn.isShowing()) return;
            animateCard(1.0);
        });
        optionsBtn.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                getStyleClass().add("menu-open");
            } else {
                getStyleClass().remove("menu-open");
                if (!isHover()) animateCard(1.0);
            }
        });
    }

    private void animateCard(double scale) {
        ScaleTransition st = new ScaleTransition(Duration.millis(150), this);
        st.setToX(scale);
        st.setToY(scale);
        st.play();
    }

    private void executeDelete() {
        Service<Void> svc = new Service<>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        App.getServices().hub().getInstallationService()
                                .deleteInstallation(installation.getInstallationId());
                        return null;
                    }
                };
            }
        };

        svc.setOnSucceeded(e -> {
            HomePage homePage = App.getCachedHomePage();
            if (homePage != null) homePage.removeInstallation(installation.getInstallationId());
            new CustomNotification("Installation Deleted",
                    "\"" + installation.getInstallationName() + "\" has been permanently deleted.",
                    new FontIcon(MaterialDesignD.DELETE)).showNotification();
        });

        svc.setOnFailed(e -> {
            String msg = HttpStatusException.extractMessage(svc.getException());
            new CustomNotification("Delete Installation", msg,
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
            log.error("Failed to delete installation {}", installation.getInstallationId(), svc.getException());
        });

        svc.start();
    }
}
