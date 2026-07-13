package komm.ui.customnodes;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.util.Duration;
import komm.App;
import komm.ui.theme.ThemeManager;

import java.time.LocalTime;

public class ClockCanvas extends Canvas {

    private static final int    PX   = 3;
    private static final int    PG   = 1;
    private static final int    STEP = PX + PG;           // 4
    private static final int    DW   = 5 * STEP - PG;     // 19
    private static final int    DH   = 5 * STEP - PG;     // 19
    private static final int    SEP  = 3;
    private static final int    COL  = STEP;               // 4
    private static final int    PAD  = 4;
    private static final double W    =
            PAD + (DW + SEP) * 2 + COL + SEP
            + (DW + SEP) * 2 + COL + SEP
            + (DW + SEP) + DW + PAD;                       // 151

    private static final Color ACCENT_DEFAULT = Color.web("#5B9CF6");

    // 5×5 pixel maps for digits 0–9 (row-major, col 0 = left)
    private static final boolean[][][] DIGITS = {
        {{false,true, true, true, false},{true, false,false,false,true },{true, false,false,false,true },{true, false,false,false,true },{false,true, true, true, false}}, // 0
        {{false,false,true, false,false},{false,true, true, false,false},{false,false,true, false,false},{false,false,true, false,false},{false,true, true, true, false}}, // 1
        {{false,true, true, true, false},{false,false,false,false,true },{false,false,true, true, false},{false,true, false,false,false},{true, true, true, true, true }}, // 2
        {{true, true, true, true, false},{false,false,false,false,true },{false,true, true, true, false},{false,false,false,false,true },{true, true, true, true, false}}, // 3
        {{true, false,false,true, false},{true, false,false,true, false},{true, true, true, true, true },{false,false,false,true, false},{false,false,false,true, false}}, // 4
        {{true, true, true, true, true },{true, false,false,false,false},{true, true, true, true, false},{false,false,false,false,true },{true, true, true, true, false}}, // 5
        {{false,true, true, true, false},{true, false,false,false,false},{true, true, true, true, false},{true, false,false,false,true },{false,true, true, true, false}}, // 6
        {{true, true, true, true, true },{false,false,false,false,true },{false,false,false,true, false},{false,false,true, false,false},{false,false,true, false,false}}, // 7
        {{false,true, true, true, false},{true, false,false,false,true },{false,true, true, true, false},{true, false,false,false,true },{false,true, true, true, false}}, // 8
        {{false,true, true, true, false},{true, false,false,false,true },{false,true, true, true, true },{false,false,false,false,true },{false,true, true, true, false}}, // 9
    };

    private final Timeline timeline;

    public ClockCanvas() {
        super(W, DH);

        timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            Color accent = (Color) getUserData();
            if (accent != null) draw(accent);
        }));
        timeline.setCycleCount(Animation.INDEFINITE);

        sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                Platform.runLater(() -> {
                    Color accent = resolveAccent();
                    setUserData(accent);
                    draw(accent);
                    timeline.play();
                });
            } else {
                timeline.stop();
            }
        });

        // Re-resolve the accent color on live theme switches — the scene-property
        // listener above only fires on attach/detach, so it misses in-place theme
        // changes while this node stays mounted (e.g. switching theme on the server page).
        ThemeManager.get().themeProperty().addListener((obs, old, newTheme) -> {
            if (getScene() != null) {
                Color accent = resolveAccent();
                setUserData(accent);
                draw(accent);
            }
        });
    }

    public double getPreferredWidth() { return W; }
    public double getPreferredHeight() { return DH; }

    private Color resolveAccent() {
        try {
            Region probe = new Region();
            probe.setStyle("-fx-background-color: -color-accent-emphasis;");
            probe.setPrefSize(1, 1);
            App.getStackPane().getChildren().add(probe);
            probe.applyCss();
            Background bg = probe.getBackground();
            App.getStackPane().getChildren().remove(probe);
            if (bg != null && !bg.getFills().isEmpty()) {
                Paint fill = bg.getFills().get(0).getFill();
                if (fill instanceof Color c) return c;
            }
        } catch (Exception ignored) {}
        return ACCENT_DEFAULT;
    }

    private void draw(Color on) {
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0, 0, W, DH);
        LocalTime t = LocalTime.now();
        int h = t.getHour(), m = t.getMinute(), s = t.getSecond();
        boolean colonOn = s % 2 == 0;
        double x = PAD;
        drawDigit(gc, h / 10, x, on); x += DW + SEP;
        drawDigit(gc, h % 10, x, on); x += DW + SEP;
        drawColon(gc, x, colonOn, on); x += COL + SEP;
        drawDigit(gc, m / 10, x, on); x += DW + SEP;
        drawDigit(gc, m % 10, x, on); x += DW + SEP;
        drawColon(gc, x, colonOn, on); x += COL + SEP;
        drawDigit(gc, s / 10, x, on); x += DW + SEP;
        drawDigit(gc, s % 10, x, on);
    }

    private void drawDigit(GraphicsContext gc, int d, double x, Color on) {
        boolean[][] grid = DIGITS[d];
        for (int row = 0; row < 5; row++)
            for (int col = 0; col < 5; col++)
                if (grid[row][col]) {
                    gc.setFill(on);
                    gc.fillRect(x + col * STEP, row * STEP, PX, PX);
                }
    }

    private void drawColon(GraphicsContext gc, double x, boolean on, Color onColor) {
        if (!on) return;
        gc.setFill(onColor);
        gc.fillRect(x, STEP,     PX, PX);
        gc.fillRect(x, STEP * 3, PX, PX);
    }
}
