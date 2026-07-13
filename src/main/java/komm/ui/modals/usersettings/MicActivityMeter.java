package komm.ui.modals.usersettings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;

/**
 * Live microphone meter for the Audio settings tab: one bar showing the post-AGC mic level
 * (the exact signal the transmit gate measures) on a −60→0 dBFS scale, and a status pill
 * that states the outcome — green "Transmitting" while the VAD gate is actually open,
 * grey "Listening" otherwise. The bar floods green while transmitting so what others hear
 * is visible at a glance.
 *
 * Deliberately carries NO threshold marker: the sensitivity slider controls a neural-net
 * confidence score that lives in probability space, not on this dB scale — any marker
 * drawn here would be a lie. The pill and the green flood ARE the feedback.
 * Colors mirror the app's own success/warning/danger palette (see style.css).
 */
public class MicActivityMeter extends VBox {

    private static final Color TRACK_BG  = Color.web("#0b0b0c");   // -color-bg-inset
    private static final Color GREEN     = Color.web("#8AFF80");   // -color-success-fg
    private static final Color AMBER     = Color.web("#FFCA80");   // -color-warning-fg
    private static final Color RED       = Color.web("#ef5350");   // -color-danger-fg
    private static final Color DOT_OFF   = Color.web("#7e7f86");   // -color-fg-subtle
    private static final Color IDLE_FILL = Color.web("#55565c");   // grey fill while not transmitting

    private static final double MIN_DB = -60.0;
    private static final double BAR_HEIGHT = 22;
    // Peak hold in dB per 50 ms UI tick (~50 dB/s fall) — keeps brief speech peaks visible.
    private static final double DB_DECAY_PER_TICK = 2.5;

    private final Canvas levelCanvas  = new Canvas(100, BAR_HEIGHT);
    private final Label  dbValueLabel = new Label("—");
    private final Circle statusDot    = new Circle(4, DOT_OFF);
    private final Label  statusLabel  = new Label("Not testing");

    private boolean testing      = false;
    private boolean transmitting = false;
    private double  heldDb       = MIN_DB;

    public MicActivityMeter() {
        setSpacing(6);
        setPadding(new Insets(10, 12, 10, 12));
        setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 8px;");

        Label caption = new Label("MIC LEVEL");
        caption.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-subtle; -fx-font-weight: bold;");

        dbValueLabel.setFont(Font.font("monospace", 10));
        dbValueLabel.setStyle("-fx-text-fill: -color-fg-subtle;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted; -fx-font-weight: bold;");
        HBox statusRow = new HBox(6, statusDot, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(8, caption, spacer, dbValueLabel, new Label("  ·  "), statusRow);
        header.setAlignment(Pos.CENTER_LEFT);

        levelCanvas.widthProperty().bind(header.widthProperty());
        levelCanvas.widthProperty().addListener((obs, o, n) -> redraw());
        Tooltip.install(levelCanvas, new Tooltip(
                "How loud your mic is after processing.\nGreen while you're actually transmitting."));

        getChildren().addAll(header, levelCanvas);
        redraw();
    }

    /** Enables/disables the meter. When not testing the bar is idle and empty. */
    public void setTesting(boolean testing) {
        this.testing = testing;
        this.transmitting = false;
        this.heldDb = MIN_DB;
        statusLabel.setText(testing ? "Listening — nothing is sent" : "Not testing");
        statusDot.setFill(DOT_OFF);
        if (!testing) dbValueLabel.setText("—");
        redraw();
    }

    /**
     * Refreshes the meter from the latest pipeline metrics. Call on every UI tick while testing.
     *
     * @param postAgcRms   post-NS/AGC mic RMS in [0, 1] — the signal the gate measures
     * @param transmitting true while the VAD gate is open (audio is actually being sent)
     */
    public void update(float postAgcRms, boolean transmitting) {
        double db = 20.0 * Math.log10(Math.max(postAgcRms, 1e-6));
        heldDb = Math.max(db, heldDb - DB_DECAY_PER_TICK);
        this.transmitting = transmitting;

        dbValueLabel.setText(db <= MIN_DB ? "-∞ dB" : String.format("%.1f dB", db));
        statusDot.setFill(transmitting ? GREEN : DOT_OFF);
        statusLabel.setText(transmitting ? "Transmitting — others hear this" : "Listening — nothing is sent");
        redraw();
    }

    private void redraw() {
        GraphicsContext gc = levelCanvas.getGraphicsContext2D();
        double w = levelCanvas.getWidth(), h = levelCanvas.getHeight();
        gc.clearRect(0, 0, w, h);

        gc.setFill(TRACK_BG);
        gc.fillRoundRect(0, 0, w, h, 6, 6);

        double levelFrac = clamp((heldDb - MIN_DB) / -MIN_DB);
        if (testing && levelFrac > 0) {
            // Grey while the gate is closed, loudness colors while transmitting — the green
            // flood is the "others can hear you" signal, same as Discord's meter.
            Color fill = !transmitting ? IDLE_FILL
                    : levelFrac < 0.65 ? GREEN : levelFrac < 0.85 ? AMBER : RED;
            gc.setFill(fill);
            gc.fillRoundRect(1, 1, levelFrac * (w - 2), h - 2, 5, 5);
        }
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
