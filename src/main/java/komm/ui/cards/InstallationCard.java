package komm.ui.cards;

import atlantafx.base.theme.Styles;
import javafx.animation.ScaleTransition;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.summary.InstallationDetailSummary;
import komm.model.dto.summary.InstallationStatusEventSummary;
import komm.model.dto.summary.InstallationSummary;
import komm.model.dto.summary.InstallationUptimeDayPoint;
import komm.model.dto.summary.InstallationUptimeSummary;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.ConfirmationModal;
import komm.ui.modals.InstallationSettingsModal;
import komm.ui.modals.VerificationCodeModal;
import komm.ui.pages.HomePage;
import komm.ui.utils.IconColorUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A single installation rendered as a compact status-page widget: a live status ring, a
 * headline uptime percentage, and a 90-day uptime bar strip (same idea as a big-tech status
 * page) fed by {@code GET /api/installations/{id}/uptime}. The bars/KPI load asynchronously
 * on top of the synchronously-available {@link InstallationSummary}, so the card never blocks
 * on the network to render.
 */
@Slf4j
public class InstallationCard extends VBox {

    @Getter
    private final InstallationSummary installation;

    private static final int RANGE_DAYS = 90;
    private static final int RING_SIZE = 56;
    private static final double BAR_HEIGHT = 28;

    private MenuButton optionsBtn;
    private HBox header;
    private Label uptimePercentLabel;
    private Label uptimeSubLabel;
    private HBox barStrip;
    private final List<Region> dayBars = new ArrayList<>();
    private Label lastSeenValue;
    private Node offlineBanner;
    private final Consumer<Double> onUptimeResolved;

    public InstallationCard(InstallationSummary installation) {
        this(installation, null);
    }

    /**
     * @param onUptimeResolved called once this card's own async uptime fetch resolves — with the
     *                         loaded percentage, or {@code null} on failure — so a fleet-wide
     *                         summary (e.g. {@link InstallationFleetSummary}) can aggregate across
     *                         cards without making its own redundant requests. May be {@code null}.
     */
    public InstallationCard(InstallationSummary installation, Consumer<Double> onUptimeResolved) {
        this.installation = installation;
        this.onUptimeResolved = onUptimeResolved;
        getStyleClass().add("installation-status-card");
        setMaxWidth(Double.MAX_VALUE);
        setSpacing(14);
        setPadding(new Insets(16, 18, 16, 18));
        initialize();
        loadUptime();
    }

    private void initialize() {
        header = buildHeader();
        HBox statsFooter = buildStatsFooter();
        barStrip = buildBarSkeleton();
        HBox rangeLabels = buildRangeLabels();

        VBox barBlock = new VBox(6, barStrip, rangeLabels);

        getChildren().addAll(header, statsFooter, barBlock);
        setupInteractions();

        if (installation.getStatus() == InstallationSummary.InstallationStatus.OFFLINE) {
            offlineBanner = buildOfflineBanner();
            getChildren().add(1, offlineBanner);
        }
    }

    // ── Header: status ring + identity + uptime KPI + options ─────────────────

    private HBox buildHeader() {
        StackPane ring = buildStatusRing();

        VBox identity = new VBox(6);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setMinWidth(140);
        HBox.setHgrow(identity, Priority.ALWAYS);

        Label nameLabel = new Label(installation.getInstallationName());
        nameLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        HBox metaRow = new HBox(8, buildStatusBadge(), buildChip(ipText(), "-color-accent-subtle", "-color-accent-fg"));
        metaRow.setAlignment(Pos.CENTER_LEFT);

        identity.getChildren().addAll(nameLabel, metaRow);

        VBox kpi = new VBox(2);
        kpi.setAlignment(Pos.CENTER_RIGHT);
        uptimePercentLabel = new Label("—");
        uptimePercentLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        uptimeSubLabel = new Label("loading…");
        uptimeSubLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        kpi.getChildren().addAll(uptimePercentLabel, uptimeSubLabel);

        optionsBtn = createOptionsMenu();

        HBox row = new HBox(14, ring, identity, kpi, optionsBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private StackPane buildStatusRing() {
        String color = statusColor(installation.getStatus());

        StackPane ring = new StackPane();
        ring.setMinSize(RING_SIZE, RING_SIZE);
        ring.setMaxSize(RING_SIZE, RING_SIZE);
        ring.setPrefSize(RING_SIZE, RING_SIZE);
        ring.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                        "-fx-background-radius: 4px;" +
                        "-fx-border-color: " + color + ";" +
                        "-fx-border-width: 2px;" +
                        "-fx-border-radius: 4px;"
        );

        FontIcon icon = IconColorUtil.colored(MaterialDesignS.SERVER, color, 22);
        ring.getChildren().add(icon);

        return ring;
    }

    private HBox buildStatusBadge() {
        InstallationSummary.InstallationStatus status = installation.getStatus();
        String labelText = switch (status != null ? status : InstallationSummary.InstallationStatus.UNKNOWN) {
            case ONLINE -> "Online";
            case OFFLINE -> "Offline";
            case NOT_VERIFIED -> "Not Verified";
            default -> "Unknown";
        };
        Circle dot = new Circle(3);
        dot.setStyle("-fx-fill: " + statusColor(status) + ";");
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");
        HBox badge = new HBox(5, dot, lbl);
        badge.setAlignment(Pos.CENTER_LEFT);
        return badge;
    }

    private HBox buildChip(String text, String bg, String fg) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + fg + ";");
        HBox chip = new HBox(lbl);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setPadding(new Insets(2, 8, 2, 8));
        chip.setMaxWidth(Region.USE_PREF_SIZE);
        chip.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 3px;");
        return chip;
    }

    private String ipText() {
        return installation.getIpAddress() != null && !installation.getIpAddress().isBlank()
                ? installation.getIpAddress() : "no address";
    }

    private String statusColor(InstallationSummary.InstallationStatus status) {
        if (status == null) return "-color-neutral-emphasis";
        return switch (status) {
            case ONLINE -> "-color-accent-emphasis";
            case OFFLINE -> "-color-danger-emphasis";
            case NOT_VERIFIED -> "-color-warning-emphasis";
            default -> "-color-neutral-emphasis";
        };
    }

    // ── Offline banner ─────────────────────────────────────────────────────────

    private Node buildOfflineBanner() {
        FontIcon icon = IconColorUtil.colored(MaterialDesignA.ALERT_CIRCLE_OUTLINE, "-color-danger-fg", 14);
        Label lbl = new Label("This installation is currently offline.");
        lbl.setStyle("-fx-font-size: 11.5px; -fx-font-weight: bold; -fx-text-fill: -color-danger-fg;");
        HBox banner = new HBox(8, icon, lbl);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setPadding(new Insets(6, 10, 6, 10));
        banner.setStyle("-fx-background-color: -color-danger-subtle; -fx-background-radius: 4px;");
        return banner;
    }

    // ── Uptime bar strip ──────────────────────────────────────────────────────

    private HBox buildBarSkeleton() {
        HBox strip = new HBox(2);
        strip.setAlignment(Pos.CENTER);
        strip.setMaxWidth(Double.MAX_VALUE);
        for (int i = 0; i < RANGE_DAYS; i++) {
            Region bar = buildBar("-color-neutral-emphasis", 0.18, null);
            dayBars.add(bar);
            strip.getChildren().add(bar);
        }
        return strip;
    }

    private Region buildBar(String color, double opacity, Tooltip tooltip) {
        Region bar = new Region();
        bar.setMinWidth(3);
        bar.setPrefWidth(6);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setMinHeight(BAR_HEIGHT);
        bar.setMaxHeight(BAR_HEIGHT);
        bar.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 2px; -fx-opacity: " + opacity + ";");
        HBox.setHgrow(bar, Priority.ALWAYS);
        if (tooltip != null) Tooltip.install(bar, tooltip);
        return bar;
    }

    private HBox buildRangeLabels() {
        Label from = new Label(RANGE_DAYS + " days ago");
        from.setStyle("-fx-font-size: 9.5px; -fx-text-fill: -color-fg-subtle;");
        Label to = new Label("Today");
        to.setStyle("-fx-font-size: 9.5px; -fx-text-fill: -color-fg-subtle;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(from, spacer, to);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void applyUptime(InstallationUptimeSummary summary) {
        double pct = summary.getUptimePercentage();
        uptimePercentLabel.setText(formatPercent(pct));
        uptimePercentLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        uptimeSubLabel.setText("uptime · " + summary.getRangeDays() + "d");

        List<InstallationUptimeDayPoint> days = summary.getDays() != null ? summary.getDays() : List.of();
        int padCount = Math.max(0, RANGE_DAYS - days.size());

        for (int i = 0; i < dayBars.size(); i++) {
            Region bar = dayBars.get(i);
            int dayIdx = i - padCount;
            if (dayIdx < 0) {
                restyleBar(bar, "-color-neutral-emphasis", 0.18, null);
            } else {
                InstallationUptimeDayPoint point = days.get(dayIdx);
                restyleBar(bar, barColor(point), barOpacity(point), buildBarTooltip(point));
            }
        }

        if (lastSeenValue != null) {
            lastSeenValue.setText(formatLastSeen(summary.getLastSeenAt(), summary.getCurrentStatus()));
        }
    }

    private void restyleBar(Region bar, String color, double opacity, Tooltip tooltip) {
        bar.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 2px; -fx-opacity: " + opacity + ";");
        if (tooltip != null) Tooltip.install(bar, tooltip);
    }

    private String barColor(InstallationUptimeDayPoint p) {
        long known = p.getUptimeSeconds() + p.getDowntimeSeconds();
        if (known <= 0) return "-color-neutral-emphasis";
        if (p.getUptimePercentage() >= 99.9) return "-color-success-emphasis";
        if (p.getUptimePercentage() >= 90.0) return "-color-warning-emphasis";
        return "-color-danger-emphasis";
    }

    private double barOpacity(InstallationUptimeDayPoint p) {
        long known = p.getUptimeSeconds() + p.getDowntimeSeconds();
        if (known <= 0) return 0.18;
        return p.isPartial() ? 0.55 : 1.0;
    }

    private Tooltip buildBarTooltip(InstallationUptimeDayPoint p) {
        long known = p.getUptimeSeconds() + p.getDowntimeSeconds();
        String dateStr = p.getDay().format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
        String text;
        if (known <= 0) {
            text = dateStr + "\nNo data";
        } else {
            StringBuilder sb = new StringBuilder(dateStr)
                    .append(p.isPartial() ? " (so far today)" : "")
                    .append("\n").append(formatPercent(p.getUptimePercentage())).append(" uptime");
            if (p.getOutageCount() > 0) {
                sb.append("\n").append(p.getOutageCount()).append(p.getOutageCount() == 1 ? " outage, longest " : " outages, longest ")
                        .append(formatDuration(p.getLongestOutageSeconds()));
            }
            text = sb.toString();
        }
        Tooltip tooltip = new Tooltip(text);
        tooltip.getStyleClass().add(Styles.SMALL);
        return tooltip;
    }

    // ── Footer stats (servers / last seen) ──────────────────────────────────────

    private HBox buildStatsFooter() {
        VBox serversCell = buildStatCell("SERVERS", String.valueOf(installation.getHostedServersCount()));
        VBox lastSeenCell = buildStatCell("LAST SEEN", "—");
        lastSeenValue = (Label) lastSeenCell.getChildren().get(0);

        HBox row = new HBox(16, serversCell, vDivider(), lastSeenCell);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox buildStatCell(String labelText, String valueText) {
        Label value = new Label(valueText);
        value.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 8.5px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        VBox cell = new VBox(2, value, label);
        cell.setAlignment(Pos.CENTER_LEFT);
        return cell;
    }

    private Separator vDivider() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.setMaxHeight(24);
        s.setOpacity(0.6);
        return s;
    }

    // ── Formatting helpers ─────────────────────────────────────────────────────

    private String formatPercent(double pct) {
        return String.format("%.2f%%", pct);
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return "<1m";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours == 0) return minutes + "m";
        return hours + "h " + minutes + "m";
    }

    private String formatLastSeen(LocalDateTime lastSeenAt, InstallationSummary.InstallationStatus status) {
        if (status == InstallationSummary.InstallationStatus.ONLINE) return "Online now";
        if (lastSeenAt == null) return "Never";
        long seconds = java.time.Duration.between(lastSeenAt, LocalDateTime.now(ZoneOffset.UTC)).getSeconds();
        if (seconds < 60) return "Just now";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }

    // ── Data loading ───────────────────────────────────────────────────────────

    private void loadUptime() {
        Service<InstallationUptimeSummary> svc = new Service<>() {
            @Override
            protected Task<InstallationUptimeSummary> createTask() {
                return new Task<>() {
                    @Override
                    protected InstallationUptimeSummary call() throws Exception {
                        return App.getServices().hub().getInstallationService()
                                .getInstallationUptime(installation.getInstallationId(), RANGE_DAYS);
                    }
                };
            }
        };
        svc.setOnSucceeded(e -> {
            InstallationUptimeSummary summary = svc.getValue();
            applyUptime(summary);
            if (onUptimeResolved != null) onUptimeResolved.accept(summary.getUptimePercentage());
        });
        svc.setOnFailed(e -> {
            log.warn("Failed to load uptime for installation {}: {}",
                    installation.getInstallationId(), svc.getException().getMessage());
            uptimePercentLabel.setText("—");
            uptimeSubLabel.setText("no data");
            if (onUptimeResolved != null) onUptimeResolved.accept(null);
        });
        svc.start();
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

            if (installation.getStatus() == InstallationSummary.InstallationStatus.NOT_VERIFIED) {
                MenuItem verificationCodeItem = new MenuItem("Get Verification Code");
                verificationCodeItem.setGraphic(new FontIcon(MaterialDesignK.KEY_OUTLINE));
                verificationCodeItem.setOnAction(e -> handleShowVerificationCode());
                btn.getItems().addAll(settingsItem, verificationCodeItem, new SeparatorMenuItem(), deleteItem);
            } else {
                btn.getItems().addAll(settingsItem, new SeparatorMenuItem(), deleteItem);
            }
        } else {
            btn.getItems().add(settingsItem);
        }

        return btn;
    }

    private void handleShowVerificationCode() {
        Service<InstallationDetailSummary> svc = new Service<>() {
            @Override
            protected Task<InstallationDetailSummary> createTask() {
                return new Task<>() {
                    @Override
                    protected InstallationDetailSummary call() throws Exception {
                        return App.getServices().hub().getInstallationService()
                                .getInstallationDetails(installation.getInstallationId());
                    }
                };
            }
        };

        svc.setOnSucceeded(e -> {
            String code = svc.getValue().getVerificationCode();
            if (code == null || code.isBlank()) {
                new CustomNotification("Verification Code", "This installation has already been activated.",
                        new FontIcon(MaterialDesignK.KEY_OUTLINE)).showNotification();
                return;
            }
            App.showModal(new VerificationCodeModal(installation, code));
        });

        svc.setOnFailed(e -> {
            Throwable ex = svc.getException();
            log.error("Failed to load verification code for installation {}", installation.getInstallationId(), ex);
            String msg = HttpStatusException.extractMessage(ex);
            new CustomNotification("Verification Code", msg, new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE))
                    .showNotification();
        });
        svc.start();
    }

    // ── Interactions ──────────────────────────────────────────────────────────

    private void setupInteractions() {
        setOnMouseEntered(e -> animateCard(1.015));
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
