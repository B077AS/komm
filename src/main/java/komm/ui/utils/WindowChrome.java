package komm.ui.utils;

import atlantafx.decorations.HeaderButtonGroup;
import javafx.animation.AnimationTimer;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.input.MouseEvent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.geometry.Pos;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

import static com.sun.jna.Platform.isWindows;

/**
 * Dark custom title bar for every Komm window.
 *
 * <p>JavaFX 26+ can extend the scene into the title bar ({@link StageStyle#EXTENDED}); the window
 * then has no OS caption and we draw one ourselves with a {@link HeaderBar} plus AtlantaFX's
 * {@code atlantafx-decorations} caption button controls, styled by {@code komm-titlebar.css} (rectangular, full-height, flush with the window edge; accent-coloured Close). The platform still owns dragging, resizing,
 * double-click-to-maximize and (on Windows 11) the snap-layout flyout, because the buttons are
 * tagged with {@link javafx.scene.layout.HeaderButtonType}. Since the title bar is ordinary
 * JavaFX content it is always dark and there is no first-frame flash — which is exactly what the
 * old DWM workaround ({@link WindowsThemeUtil}) existed to hide.
 *
 * <p>If the platform lacks {@link ConditionalFeature#EXTENDED_WINDOW}, or {@code -Dkomm.titlebar=native}
 * is given, windows stay OS-decorated and Windows gets the old DWM dark-mode treatment.
 *
 * <p>Usage, in this order: {@link #prepare(Stage)} → build scene with {@link #wrap} as its root →
 * {@link #show(Stage)}.
 */
@Slf4j
public final class WindowChrome {

    private static final boolean ENABLED =
            !"native".equalsIgnoreCase(System.getProperty("komm.titlebar"))
                    && Platform.isSupported(ConditionalFeature.EXTENDED_WINDOW);

    static {
        log.info("[Chrome] custom title bar {}", ENABLED ? "enabled (StageStyle.EXTENDED)" : "disabled (native decorations)");
    }

    private WindowChrome() {}

    public static boolean isExtended() {
        return ENABLED;
    }

    /** Must run before the stage is shown for the first time. */
    public static void prepare(Stage stage) {
        if (ENABLED) stage.initStyle(StageStyle.EXTENDED);
    }

    /**
     * Wraps {@code content} under a Komm title bar (icon + stage title on the left, caption buttons
     * on the right). A no-op returning {@code content} when the custom title bar is not in use.
     *
     * @param closeOnly only a close button (fixed-size notification windows)
     */
    public static Parent wrap(Stage stage, Parent content, boolean closeOnly) {
        if (!ENABLED) return content;

        HeaderBar header = new HeaderBar();
        header.getStyleClass().add("komm-title-bar");
        header.setLeftSystemPadding(false);
        header.setRightSystemPadding(false);

        HBox titleBox = new HBox(8);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        titleBox.getStyleClass().add("komm-title-box");
        if (!stage.getIcons().isEmpty()) {
            Image icon = stage.getIcons().get(0);
            ImageView iv = new ImageView(icon);
            iv.setFitWidth(16);
            iv.setFitHeight(16);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            titleBox.getChildren().add(iv);
        }
        Label title = new Label();
        title.textProperty().bind(stage.titleProperty());
        title.getStyleClass().add("komm-title-text");
        titleBox.getChildren().add(title);
        header.setLeft(titleBox);
        HeaderBar.setDragType(titleBox, HeaderDragType.DRAGGABLE_SUBTREE);

        HeaderButtonGroup buttons = closeOnly ? HeaderButtonGroup.utilityGroup() : HeaderButtonGroup.standardGroup();
        buttons.install(header, stage);
        // Press a button, drag off it and release elsewhere: the release goes to whatever is under
        // the pointer, never to the button, so it stays :pressed. Mark it "stale" (CSS neutralises
        // hover/pressed) until JavaFX finally clears the pressed state.
        for (Node b : buttons.getButtons()) {
            b.pressedProperty().subscribe(pressed -> {
                if (!pressed) b.getStyleClass().remove("stale");
            });
        }
        stage.sceneProperty().subscribe(s -> {
            if (s == null) return;
            s.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
                for (Node b : buttons.getButtons()) {
                    if (b.isPressed() && e.getTarget() != b && !b.getStyleClass().contains("stale")
                            && !b.contains(b.sceneToLocal(e.getSceneX(), e.getSceneY()))) {
                        b.getStyleClass().add("stale");
                    }
                }
            });
        });

        // No title bar over a full-screen stream.
        header.visibleProperty().bind(stage.fullScreenProperty().not());
        header.managedProperty().bind(stage.fullScreenProperty().not());

        BorderPane root = new BorderPane(content);
        root.setTop(header);
        root.getStyleClass().add("komm-window-root");

        Scene scene = stage.getScene();
        String css = Objects.requireNonNull(WindowChrome.class.getResource("/komm-titlebar.css")).toExternalForm();
        if (scene != null) {
            if (!scene.getStylesheets().contains(css)) scene.getStylesheets().add(css);
        } else {
            // Scene not created yet: attach as soon as it is.
            stage.sceneProperty().subscribe(s -> {
                if (s != null && !s.getStylesheets().contains(css)) s.getStylesheets().add(css);
            });
        }
        return root;
    }

    /** The app's window background (-color-bg-default); shown instead of JavaFX's white before CSS is applied. */
    private static final Color WINDOW_BG = Color.web("#161616");

    /**
     * Shows the stage without a visible "small window, then maximise, then white, then content"
     * sequence: the stage is created fully transparent, the scene is pre-filled with the app
     * background, and the window is only revealed once it has had a few frames to maximise, apply CSS
     * and lay out. With native decorations on Windows this also applies the DWM dark-mode workaround.
     */
    public static void show(Stage stage) {
        Scene scene = stage.getScene();
        if (scene != null) scene.setFill(WINDOW_BG);

        if (!ENABLED && !isWindows()) {
            stage.show();
            return;
        }
        if (ENABLED) {
            // DWM cloak instead of opacity 0: a non-opaque stage becomes a layered window, and the first
            // maximise/restore of such a window gets no system animation.
            stage.show();
            boolean cloaked = isWindows() && WindowsThemeUtil.setCloaked(stage.getTitle(), true) > 0;
            if (!cloaked) stage.setOpacity(0);
            revealAfterFirstFrames(stage, cloaked);
            return;
        }
        stage.setOpacity(0);
        stage.show();
        // Native title bar: DWM only honours dark mode once the window exists (see WindowsThemeUtil).
        String title = stage.getTitle();
        Thread.ofVirtual().start(() -> {
            WindowsThemeUtil.enableDarkTitleBar(title);
            Platform.runLater(() -> revealAfterFirstFrames(stage, false));
        });
    }

    private static final int REVEAL_MIN_FRAMES = 3;
    private static final long REVEAL_MIN_NANOS = 120_000_000L;   // let the maximise animation settle
    private static final long REVEAL_MAX_NANOS = 900_000_000L;   // never leave the user without a window

    /** Sets opacity to 1 after a few rendered frames, bounded by a minimum settle time and a hard cap. */
    private static void revealAfterFirstFrames(Stage stage, boolean cloaked) {
        new AnimationTimer() {
            private long start = -1;
            private int frames = 0;

            @Override
            public void handle(long now) {
                if (start < 0) start = now;
                frames++;
                long elapsed = now - start;
                boolean ready = frames >= REVEAL_MIN_FRAMES && elapsed >= REVEAL_MIN_NANOS;
                if (ready || elapsed >= REVEAL_MAX_NANOS || !stage.isShowing()) {
                    stop();
                    if (cloaked) WindowsThemeUtil.setCloaked(stage.getTitle(), false);
                    else stage.setOpacity(1);
                }
            }
        }.start();
    }
}
