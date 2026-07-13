package komm.ui.customnodes;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;

/**
 * Shared rendering/stat logic for the "self" and "other user" latency popups.
 * Subclasses only wire up where the RTT samples come from and (optionally)
 * the subscribe/unsubscribe network calls for observing another user.
 */
abstract class PingGraphPopupBase extends Popup {

    public static final int WIDTH = 300;
    private static final int CANVAS_W = 272;
    private static final int CANVAS_H = 104;
    private static final int VISIBLE_POINTS = 20;

    private static final Color BG_OVERLAY = Color.color(0, 0, 0, 0.20);
    private static final Color GRID_COLOR = Color.color(1, 1, 1, 0.055);
    private static final Color LABEL_COLOR = Color.color(1, 1, 1, 0.30);
    private static final Color NO_DATA_CLR = Color.color(1, 1, 1, 0.22);

    private static final Color COLOR_GOOD = Color.web("#43b581");
    private static final Color COLOR_OK = Color.web("#9dcc5a");
    private static final Color COLOR_WARN = Color.web("#faa61a");
    private static final Color COLOR_BAD = Color.web("#f04747");

    private final Canvas canvas = new Canvas(CANVAS_W, CANVAS_H);
    private final Circle pulseRing = new Circle(4);
    private final Timeline pulseAnim;

    private final Label currentPingLabel;
    private Label avgValueLabel;
    private Label jitterValueLabel;
    private Label lossValueLabel;

    private final Consumer<Integer> pingListener;

    protected PingGraphPopupBase(String titleText) {
        setAutoHide(true);
        setHideOnEscape(true);

        VBox root = new VBox(8);
        root.getStyleClass().add("custom-pop-up");
        root.setPadding(new Insets(14, 14, 12, 14));
        root.setMinWidth(WIDTH);
        root.setMaxWidth(WIDTH);

        // ── Header ────────────────────────────────────────────────────────
        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(titleText);
        title.setStyle("-fx-text-fill: rgba(255,255,255,0.45); -fx-font-size: 10px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        currentPingLabel = new Label("-- ms");
        currentPingLabel.setStyle(pingValueStyle(COLOR_BAD));

        header.getChildren().addAll(title, spacer, currentPingLabel);

        // ── Canvas + live pulse ring ─────────────────────────────────────
        Pane graphPane = new Pane(canvas, pulseRing);
        graphPane.setClip(new Rectangle(CANVAS_W, CANVAS_H));
        pulseRing.setFill(Color.TRANSPARENT);
        pulseRing.setStrokeWidth(1.6);
        pulseRing.setManaged(false);
        pulseRing.setMouseTransparent(true);
        pulseRing.setVisible(false);

        pulseAnim = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(pulseRing.radiusProperty(), 4),
                        new KeyValue(pulseRing.opacityProperty(), 0.6)),
                new KeyFrame(Duration.seconds(1.4),
                        new KeyValue(pulseRing.radiusProperty(), 15),
                        new KeyValue(pulseRing.opacityProperty(), 0))
        );
        pulseAnim.setCycleCount(Animation.INDEFINITE);

        // ── Stats ─────────────────────────────────────────────────────────
        HBox statsRow = buildStatsRow();

        root.getChildren().addAll(header, graphPane, statsRow);
        getContent().add(root);

        // ── Listener lifecycle ────────────────────────────────────────────
        pingListener = ms -> redraw();

        showingProperty().addListener((obs, was, now) -> {
            if (now) {
                subscribe(pingListener);
                onShow();
                redraw();
                pulseAnim.playFromStart();
            } else {
                unsubscribe(pingListener);
                onHide();
                pulseAnim.stop();
            }
        });
    }

    // ── Subclass hooks ────────────────────────────────────────────────────

    protected abstract List<Integer> currentHistory();

    protected abstract void subscribe(Consumer<Integer> listener);

    protected abstract void unsubscribe(Consumer<Integer> listener);

    /** Missed-heartbeat rate (0-100) for the tracked connection, as last reported. */
    protected abstract int currentLossPercent();

    protected void onShow() {}

    protected void onHide() {}

    // ── Redraw ────────────────────────────────────────────────────────────

    private void redraw() {
        List<Integer> pings = currentHistory();

        if (pings.isEmpty()) {
            currentPingLabel.setText("-- ms");
            currentPingLabel.setStyle(pingValueStyle(COLOR_BAD));
            applyStatStyle(avgValueLabel, "--", LABEL_COLOR);
            applyStatStyle(jitterValueLabel, "--", LABEL_COLOR);
            applyStatStyle(lossValueLabel, "--", LABEL_COLOR);
            drawGraph(pings);
            return;
        }

        int latest = pings.get(pings.size() - 1);
        double avg = pings.stream().mapToInt(Integer::intValue).average().orElse(0);
        double jitter = computeJitter(pings);
        int lossPct = currentLossPercent();

        currentPingLabel.setText(latest + " ms");
        currentPingLabel.setStyle(pingValueStyle(colorForPing(latest)));

        applyStatStyle(avgValueLabel, Math.round(avg) + " ms", colorForPing((int) Math.round(avg)));
        applyStatStyle(jitterValueLabel, Math.round(jitter) + " ms", jitterColor(jitter));
        applyStatStyle(lossValueLabel, lossPct + "%", lossColor(lossPct));

        drawGraph(pings);
    }

    // ── Graph drawing ─────────────────────────────────────────────────────

    private void drawGraph(List<Integer> pings) {
        List<Integer> visible = pings.size() > VISIBLE_POINTS
                ? pings.subList(pings.size() - VISIBLE_POINTS, pings.size())
                : pings;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = CANVAS_W, h = CANVAS_H;
        gc.clearRect(0, 0, w, h);

        gc.setFill(BG_OVERLAY);
        gc.fillRoundRect(0, 0, w, h, 8, 8);

        if (visible.isEmpty()) {
            pulseRing.setVisible(false);
            gc.setFill(NO_DATA_CLR);
            gc.setFont(Font.font("System", 11));
            gc.fillText("Waiting for data…", w / 2 - 56, h / 2 + 4);
            return;
        }

        double padL = 32, padR = 6, padT = 12, padB = 6;
        double gw = w - padL - padR;
        double gh = h - padT - padB;

        int rawMax = visible.stream().mapToInt(Integer::intValue).max().orElse(50);
        int yMax = niceMax(rawMax);

        // Gridlines + Y labels
        gc.setFont(Font.font("System", 9));
        int[] steps = {0, yMax / 2, yMax};
        for (int val : steps) {
            double y = padT + gh * (1.0 - (double) val / yMax);
            gc.setStroke(GRID_COLOR);
            gc.setLineWidth(1);
            gc.strokeLine(padL, y, w - padR, y);
            gc.setFill(LABEL_COLOR);
            String lbl = val == 0 ? "0" : val + "";
            gc.fillText(lbl, 2, y + 3.5);
        }

        // Dashed average reference line
        double avgVisible = visible.stream().mapToInt(Integer::intValue).average().orElse(0);
        double avgY = padT + gh * (1.0 - Math.min(avgVisible, yMax) / yMax);
        gc.setStroke(LABEL_COLOR);
        gc.setLineWidth(1);
        gc.setLineDashes(4, 4);
        gc.strokeLine(padL, avgY, w - padR, avgY);
        gc.setLineDashes();

        // Build coordinates
        int n = visible.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = padL + (n == 1 ? gw / 2 : gw * i / (double) (n - 1));
            ys[i] = padT + gh * (1.0 - visible.get(i) / (double) yMax);
        }

        Color line = colorForPing(visible.get(n - 1));
        double bottomY = padT + gh;

        // Position the live pulse ring on the latest sample
        pulseRing.setVisible(true);
        pulseRing.setLayoutX(xs[n - 1]);
        pulseRing.setLayoutY(ys[n - 1]);
        pulseRing.setStroke(line);

        if (n == 1) {
            gc.setFill(line);
            gc.fillOval(xs[0] - 3.5, ys[0] - 3.5, 7, 7);
            return;
        }

        // Area fill — gradient from line color to transparent
        LinearGradient fill = new LinearGradient(0, padT, 0, bottomY, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(line.getRed(), line.getGreen(), line.getBlue(), 0.20)),
                new Stop(1, Color.color(line.getRed(), line.getGreen(), line.getBlue(), 0.02)));
        gc.setFill(fill);
        gc.beginPath();
        gc.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            double[] cp = catmullToBezier(xs, ys, i);
            gc.bezierCurveTo(cp[0], cp[1], cp[2], cp[3], xs[i], ys[i]);
        }
        gc.lineTo(xs[n - 1], bottomY);
        gc.lineTo(xs[0], bottomY);
        gc.closePath();
        gc.fill();

        // Line
        gc.setStroke(line);
        gc.setLineWidth(1.5);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.beginPath();
        gc.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            double[] cp = catmullToBezier(xs, ys, i);
            gc.bezierCurveTo(cp[0], cp[1], cp[2], cp[3], xs[i], ys[i]);
        }
        gc.stroke();

        // Data points
        gc.setFill(line);
        for (int i = 0; i < n - 1; i++) {
            gc.fillOval(xs[i] - 1.8, ys[i] - 1.8, 3.6, 3.6);
        }

        // Latest point — larger with white center
        gc.fillOval(xs[n - 1] - 4, ys[n - 1] - 4, 8, 8);
        gc.setFill(Color.color(1, 1, 1, 0.92));
        gc.fillOval(xs[n - 1] - 1.8, ys[n - 1] - 1.8, 3.6, 3.6);
    }

    // ── Stats math ────────────────────────────────────────────────────────

    private static double computeJitter(List<Integer> pings) {
        if (pings.size() < 2) return 0;
        double sum = 0;
        for (int i = 1; i < pings.size(); i++) {
            sum += Math.abs(pings.get(i) - pings.get(i - 1));
        }
        return sum / (pings.size() - 1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static double[] catmullToBezier(double[] xs, double[] ys, int i) {
        int n = xs.length;
        double t = 0.25;
        int i0 = Math.max(i - 2, 0), i1 = i - 1, i2 = i, i3 = Math.min(i + 1, n - 1);
        double cp1x = xs[i1] + (xs[i2] - xs[i0]) * t;
        double cp1y = ys[i1] + (ys[i2] - ys[i0]) * t;
        double cp2x = xs[i2] - (xs[i3] - xs[i1]) * t;
        double cp2y = ys[i2] - (ys[i3] - ys[i1]) * t;
        return new double[]{cp1x, cp1y, cp2x, cp2y};
    }

    private static int niceMax(int rawMax) {
        int padded = rawMax + rawMax / 4 + 10;
        if (padded <= 50) return 50;
        if (padded <= 100) return 100;
        if (padded <= 200) return 200;
        if (padded <= 500) return 500;
        return ((padded / 500) + 1) * 500;
    }

    static Color colorForPing(int ms) {
        if (ms < 0) return COLOR_BAD;
        if (ms <= 60) return COLOR_GOOD;
        if (ms <= 120) return COLOR_OK;
        if (ms <= 200) return COLOR_WARN;
        return COLOR_BAD;
    }

    private static Color jitterColor(double jitter) {
        if (jitter <= 15) return COLOR_GOOD;
        if (jitter <= 30) return COLOR_OK;
        if (jitter <= 60) return COLOR_WARN;
        return COLOR_BAD;
    }

    private static Color lossColor(int lossPct) {
        if (lossPct <= 1) return COLOR_GOOD;
        if (lossPct <= 5) return COLOR_OK;
        if (lossPct <= 15) return COLOR_WARN;
        return COLOR_BAD;
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255));
    }

    private static String pingValueStyle(Color c) {
        return "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + toHex(c) + ";";
    }

    private static void applyStatStyle(Label lbl, String text, Color c) {
        lbl.setText(text);
        lbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + toHex(c) + ";");
    }

    private HBox buildStatsRow() {
        avgValueLabel = new Label("--");
        jitterValueLabel = new Label("--");
        lossValueLabel = new Label("--");

        HBox row = new HBox(0);
        row.setAlignment(Pos.CENTER);
        Region s1 = new Region();
        HBox.setHgrow(s1, Priority.ALWAYS);
        Region s2 = new Region();
        HBox.setHgrow(s2, Priority.ALWAYS);
        row.getChildren().addAll(
                makeStatBlock("AVG", avgValueLabel),
                s1,
                makeStatBlock("JITTER", jitterValueLabel),
                s2,
                makeStatBlock("LOSS", lossValueLabel)
        );
        return row;
    }

    private static VBox makeStatBlock(String header, Label valueLabel) {
        Label h = new Label(header);
        h.setStyle("-fx-font-size: 9px; -fx-text-fill: rgba(255,255,255,0.35); -fx-font-weight: bold;");
        valueLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: rgba(255,255,255,0.55);");
        VBox box = new VBox(2, h, valueLabel);
        box.setAlignment(Pos.CENTER);
        return box;
    }
}
