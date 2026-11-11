package komm.launcher;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import komm.launcher.update.Phase;
import komm.launcher.update.UpdateManager;
import komm.ui.theme.AppTheme;
import komm.ui.theme.KommStylesheet;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * The launcher window: a small, frameless, rounded "card" in the style of
 * Discord's updater. It shows the Komm logo, an animated status phrase, and a
 * progress bar, then hands off to {@link UpdateManager} which does the actual
 * version check / download / launch.
 *
 * <p>Styled with the client's own theme: the same patched AtlantaFX user-agent stylesheet
 * ({@link KommStylesheet}), the user's accent ({@link LauncherTheme}), plus
 * {@code launcher.css} for the card layout.
 */
@Slf4j
public class LauncherApp extends Application implements UpdateManager.Listener {

    private static String[] launchArgs = new String[0];

    private Stage stage;
    private Label statusLabel;
    private ProgressBar progressBar;

    private double dragOffsetX;
    private double dragOffsetY;

    private boolean demoMode;
    private AppTheme accent = AppTheme.PURPLE;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        this.demoMode = isDemoMode();

        // Same user-agent stylesheet as the client (AtlantaFX Dracula patched by komm-theme.css),
        // then the user's accent palette and the launcher's own layout classes on top.
        Application.setUserAgentStylesheet(KommStylesheet.userAgentUrl());
        this.accent = LauncherTheme.savedAccent();

        StackPane root = new StackPane(buildCard());
        root.getStyleClass().add("launcher-root");
        root.setPadding(new Insets(30, 40, 52, 40));

        Scene scene = new Scene(root, 440, 486);
        scene.getStylesheets().addAll(LauncherTheme.accentStylesheet(accent),
                Objects.requireNonNull(LauncherApp.class.getClassLoader().getResource("launcher.css")).toExternalForm());
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) closeLauncher();
        });

        Image icon = new Image(Objects.requireNonNull(
                LauncherApp.class.getClassLoader().getResourceAsStream("icon.png")));
        primaryStage.getIcons().add(icon);

        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setTitle("Komm");
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.centerOnScreen();
        primaryStage.show();

        playEntranceAnimation(root);
        startUpdateWorker();
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private StackPane buildCard() {
        // Centerpiece: the app logo inside pulsating rings, wordmark below.
        Color ringColor = Color.web(accent.getSwatchColor());
        StackPane pulseArea = new StackPane(
                createPulseRing(ringColor, 0),
                createPulseRing(ringColor, 800),
                createPulseRing(ringColor, 1600),
                buildLogo(96)
        );
        pulseArea.setMinSize(180, 180);
        pulseArea.setMaxSize(180, 180);

        Label title = new Label("Komm");
        title.getStyleClass().add("launcher-title");

        VBox center = new VBox(2, pulseArea, title);
        center.setAlignment(Pos.CENTER);
        VBox.setVgrow(center, Priority.ALWAYS);

        // Bottom: status phrase + progress bar.
        statusLabel = new Label(Phase.CHECKING.getPhrase());
        statusLabel.getStyleClass().add("launcher-status");

        progressBar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.getStyleClass().add("launcher-progress");
        progressBar.setMaxWidth(Double.MAX_VALUE);

        VBox bottom = new VBox(12, statusLabel, progressBar);
        bottom.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, center, bottom);
        card.getStyleClass().add("launcher-card");
        card.setPadding(new Insets(16, 22, 26, 22));
        card.setPrefSize(360, 404);
        card.setAlignment(Pos.TOP_CENTER);
        enableWindowDrag(card);

        // Close button pinned to the card's top-right corner via StackPane overlay.
        Button close = new Button();
        close.setGraphic(FontIcon.of(Feather.X, 14));
        close.getStyleClass().add("launcher-window-button");
        close.setFocusTraversable(false);
        close.setOnAction(e -> closeLauncher());

        StackPane wrapper = new StackPane(card, close);
        StackPane.setAlignment(close, Pos.TOP_RIGHT);
        StackPane.setMargin(close, new Insets(8, 8, 0, 0));
        return wrapper;
    }

    /**
     * The komm logo redrawn from icon.svg's geometry as JavaFX shapes, but on a
     * circular badge instead of the rounded square, so it reads as the innermost
     * of the pulse rings.
     */
    private Group buildLogo(double size) {
        double s = size / 64.0; // icon.svg viewBox is 64x64
        Color swatch = Color.web(accent.getSwatchColor());

        Circle badge = new Circle(32 * s, 32 * s, 32 * s);
        badge.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, swatch.interpolate(Color.WHITE, 0.28)),
                new Stop(0.55, swatch),
                new Stop(1, swatch.interpolate(Color.BLACK, 0.3))));

        Group logo = new Group(badge);
        double[][] bars = {{9, 25, 14}, {19, 19, 26}, {29, 13, 38}, {39, 19, 26}, {49, 25, 14}};
        for (double[] bar : bars) {
            Rectangle r = new Rectangle(bar[0] * s, bar[1] * s, 6 * s, bar[2] * s);
            r.setArcWidth(6 * s); // rx=3 on a width-6 bar -> fully rounded caps
            r.setArcHeight(6 * s);
            r.setFill(Color.web("#0d0e12"));
            logo.getChildren().add(r);
        }
        return logo;
    }

    private void enableWindowDrag(Region handle) {
        handle.setOnMousePressed(e -> {
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        handle.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
    }

    // ── Animations ────────────────────────────────────────────────────────────

    private void playEntranceAnimation(Region root) {
        FadeTransition fade = new FadeTransition(Duration.millis(260), root);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);

        ScaleTransition scale = new ScaleTransition(Duration.millis(300), root);
        scale.setFromX(0.96); scale.setFromY(0.96);
        scale.setToX(1);     scale.setToY(1);
        scale.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(fade, scale).play();
    }

    private Circle createPulseRing(Color color, long delayMillis) {
        Circle ring = new Circle(80);
        ring.setFill(Color.TRANSPARENT);
        ring.setStroke(color);
        ring.setStrokeWidth(2);
        ring.setOpacity(0);

        ScaleTransition scale = new ScaleTransition(Duration.millis(2400), ring);
        scale.setFromX(0.25); scale.setFromY(0.25);
        scale.setToX(1.0);   scale.setToY(1.0);
        scale.setInterpolator(Interpolator.EASE_OUT);
        scale.setCycleCount(ScaleTransition.INDEFINITE);

        FadeTransition fade = new FadeTransition(Duration.millis(2400), ring);
        fade.setFromValue(0.55);
        fade.setToValue(0);
        fade.setInterpolator(Interpolator.EASE_IN);
        fade.setCycleCount(FadeTransition.INDEFINITE);

        ParallelTransition pt = new ParallelTransition(scale, fade);
        pt.setDelay(Duration.millis(delayMillis));
        pt.setCycleCount(ParallelTransition.INDEFINITE);
        pt.play();

        return ring;
    }

    /** Quick cross-fade of the status phrase whenever it changes. */
    private void setStatusText(String text, boolean error) {
        FadeTransition out = new FadeTransition(Duration.millis(110), statusLabel);
        out.setFromValue(statusLabel.getOpacity());
        out.setToValue(0);
        out.setOnFinished(e -> {
            statusLabel.setText(text);
            statusLabel.getStyleClass().remove("is-error");
            if (error) statusLabel.getStyleClass().add("is-error");
            FadeTransition in = new FadeTransition(Duration.millis(140), statusLabel);
            in.setFromValue(0);
            in.setToValue(1);
            in.play();
        });
        out.play();
    }

    // ── Worker wiring ─────────────────────────────────────────────────────────

    private void startUpdateWorker() {
        UpdateManager manager = new UpdateManager(this);
        Thread worker = new Thread(demoMode ? manager::runDemo : manager::run, "launcher-update-worker");
        worker.setDaemon(true);
        worker.start();
    }

    // ── UpdateManager.Listener (called off the FX thread) ─────────────────────

    @Override
    public void onPhase(Phase phase) {
        Platform.runLater(() -> {
            setStatusText(phase.getPhrase(), false);
            if (phase.isDeterminate()) {
                progressBar.setProgress(0);
            } else if (phase == Phase.DONE || phase == Phase.UP_TO_DATE) {
                progressBar.setProgress(1);
            } else {
                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            }
        });
    }

    @Override
    public void onProgress(double fraction) {
        Platform.runLater(() -> progressBar.setProgress(fraction));
    }

    @Override
    public void onError(String message) {
        Platform.runLater(() -> {
            setStatusText(message, true);
            progressBar.setVisible(false);
            progressBar.setManaged(false);
        });
    }

    @Override
    public void onLaunched() {
        Platform.runLater(() -> {
            // Brief beat on "Starting Komm…" before the launcher disappears.
            FadeTransition fade = new FadeTransition(Duration.millis(220), stage.getScene().getRoot());
            fade.setFromValue(1);
            fade.setToValue(0);
            fade.setOnFinished(e -> closeLauncher());
            fade.play();
        });
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private void closeLauncher() {
        Platform.exit();
        // The stub waits for this process to exit before it swaps the downloaded files in and
        // starts the client, so make sure the JVM really goes even if a non-daemon thread lingers.
        Thread.ofVirtual().start(() -> System.exit(0));
    }

    private static boolean isDemoMode() {
        if (Boolean.parseBoolean(System.getProperty("launcher.demo", "false"))) return true;
        for (String a : launchArgs) {
            if ("demo".equalsIgnoreCase(a) || "--demo".equalsIgnoreCase(a)) return true;
        }
        return false;
    }

    public static void appStart(String[] args) {
        launchArgs = args == null ? new String[0] : args;
        launch(args);
    }
}
