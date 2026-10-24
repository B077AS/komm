package komm.ui.modals.installationsettings;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import komm.App;
import komm.model.dto.summary.InstallationUptimeDayPoint;
import komm.model.dto.summary.InstallationUptimeSummary;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static komm.ui.modals.installationsettings.InstallationSettingsUi.wrapScroll;

/** Per-day uptime history (the 90-day bar strip that used to live on the installation card). */
@Slf4j
public class UptimeTab implements InstallationSettingsTab {

    private static final int RANGE_DAYS = 90;
    private static final double BAR_HEIGHT = 56;

    private final InstallationSettingsContext ctx;
    private final ScrollPane pane;
    private final List<Region> dayBars = new ArrayList<>();
    private final Label percentLabel = new Label("—");
    private final Label outagesLabel = new Label("—");
    private final Label longestLabel = new Label("—");
    private boolean loaded = false;

    public UptimeTab(InstallationSettingsContext ctx) {
        this.ctx = ctx;
        this.pane = buildPane();
    }

    @Override public String   name()        { return "Uptime"; }
    @Override public String   description() { return "Availability of this installation over the last " + RANGE_DAYS + " days"; }
    @Override public FontIcon icon()        { return new FontIcon(MaterialDesignC.CHART_BAR); }
    @Override public Node     getPane()     { return pane; }

    @Override
    public void onShown() {
        if (loaded) return;
        loaded = true;
        Thread.ofVirtual().start(() -> {
            try {
                InstallationUptimeSummary summary = App.getServices().hub().getInstallationService()
                        .getInstallationUptime(ctx.installation().getInstallationId(), RANGE_DAYS);
                Platform.runLater(() -> apply(summary));
            } catch (Exception e) {
                log.warn("Failed to load uptime for installation {}: {}", ctx.installation().getInstallationId(), e.getMessage());
                Platform.runLater(() -> {
                    percentLabel.setText("No data");
                    loaded = false;
                });
            }
        });
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    private ScrollPane buildPane() {
        Label caption = new Label("UPTIME, LAST " + RANGE_DAYS + " DAYS");
        caption.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        percentLabel.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        VBox headline = new VBox(2, caption, percentLabel);

        HBox stats = new HBox(24, statBlock("OUTAGES", outagesLabel), statBlock("LONGEST OUTAGE", longestLabel));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(16, headline, spacer, stats);
        top.setAlignment(Pos.BOTTOM_LEFT);

        HBox strip = new HBox(2);
        strip.setAlignment(Pos.CENTER);
        for (int i = 0; i < RANGE_DAYS; i++) {
            Region bar = new Region();
            bar.setMinWidth(3);
            bar.setPrefWidth(6);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setMinHeight(BAR_HEIGHT);
            bar.setMaxHeight(BAR_HEIGHT);
            HBox.setHgrow(bar, Priority.ALWAYS);
            style(bar, "-color-neutral-emphasis", 0.18);
            dayBars.add(bar);
            strip.getChildren().add(bar);
        }

        Label from = new Label(RANGE_DAYS + " days ago");
        Label to = new Label("Today");
        from.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-subtle;");
        to.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-subtle;");
        Region rangeSpacer = new Region();
        HBox.setHgrow(rangeSpacer, Priority.ALWAYS);
        HBox range = new HBox(from, rangeSpacer, to);

        HBox legend = new HBox(16,
                legendItem("-color-success-emphasis", "99.9% or more"),
                legendItem("-color-warning-emphasis", "90% to 99.9%"),
                legendItem("-color-danger-emphasis", "Below 90%"),
                legendItem("-color-neutral-emphasis", "No data"));
        legend.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(14, top, new VBox(6, strip, range));
        panel.setPadding(new Insets(16, 18, 16, 18));
        panel.setStyle("-fx-background-color: -color-bg-subtle; -fx-border-color: -color-border-default;"
                + "-fx-border-width: 1px; -fx-border-radius: 4px; -fx-background-radius: 4px;");

        Label hint = new Label("Hover a bar for that day's uptime and outages. Faded bars are days still in progress.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        VBox content = new VBox(14, panel, legend, hint);
        content.setPadding(new Insets(16, 20, 16, 20));
        content.setAlignment(Pos.TOP_LEFT);
        return wrapScroll(content);
    }

    private VBox statBlock(String title, Label value) {
        Label t = new Label(title);
        t.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        value.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        return new VBox(2, t, value);
    }

    private HBox legendItem(String color, String text) {
        Region swatch = new Region();
        swatch.setMinSize(10, 10);
        swatch.setMaxSize(10, 10);
        style(swatch, color, 1.0);
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        HBox box = new HBox(6, swatch, l);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // ── Apply data ────────────────────────────────────────────────────────────

    private void apply(InstallationUptimeSummary summary) {
        percentLabel.setText(String.format("%.2f%%", summary.getUptimePercentage()));

        List<InstallationUptimeDayPoint> days = summary.getDays() != null ? summary.getDays() : List.of();
        int pad = Math.max(0, RANGE_DAYS - days.size());
        int outages = 0;
        long longest = 0;
        for (int i = 0; i < dayBars.size(); i++) {
            Region bar = dayBars.get(i);
            int idx = i - pad;
            if (idx < 0) {
                style(bar, "-color-neutral-emphasis", 0.18);
                continue;
            }
            InstallationUptimeDayPoint p = days.get(idx);
            style(bar, barColor(p), barOpacity(p));
            Tooltip.install(bar, tooltip(p));
            outages += p.getOutageCount();
            longest = Math.max(longest, p.getLongestOutageSeconds());
        }
        outagesLabel.setText(String.valueOf(outages));
        longestLabel.setText(outages == 0 ? "None" : formatDuration(longest));
    }

    private void style(Region r, String color, double opacity) {
        r.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 2px; -fx-opacity: " + opacity + ";");
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

    private Tooltip tooltip(InstallationUptimeDayPoint p) {
        long known = p.getUptimeSeconds() + p.getDowntimeSeconds();
        String date = p.getDay().format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
        String text;
        if (known <= 0) {
            text = date + "\nNo data";
        } else {
            StringBuilder sb = new StringBuilder(date)
                    .append(p.isPartial() ? " (so far today)" : "")
                    .append("\n").append(String.format("%.2f%%", p.getUptimePercentage())).append(" uptime");
            if (p.getOutageCount() > 0) {
                sb.append("\n").append(p.getOutageCount()).append(p.getOutageCount() == 1 ? " outage, longest " : " outages, longest ")
                        .append(formatDuration(p.getLongestOutageSeconds()));
            }
            text = sb.toString();
        }
        Tooltip t = new Tooltip(text);
        t.getStyleClass().add(Styles.SMALL);
        return t;
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return "<1m";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours == 0) return minutes + "m";
        return hours + "h " + minutes + "m";
    }
}
