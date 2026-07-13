package komm.ui.utils;

import atlantafx.base.theme.Styles;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.event.EventTarget;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import lombok.Getter;

/**
 * A reusable slide-over panel that animates in/out from the right edge.
 * <p>
 * Usage:
 * <pre>
 *   SidePanel panel = new SidePanel(380, 96, myContentNode);
 *   stackPane.getChildren().add(panel.getRoot());
 *   panel.toggle(headerButton);
 * </pre>
 */
public class SidePanel {

    @Getter
    private final VBox root;
    private final double width;
    @Getter
    private boolean open = false;
    private Timeline timeline;

    /**
     * @param width    panel width in pixels
     * @param topInset top margin — should match your header height so the panel
     *                 appears below the header bar
     * @param content  the ScrollPane or Node to embed inside the panel
     */
    public SidePanel(double width, double topInset, ScrollPane content) {
        this.width = width;

        VBox.setVgrow(content, Priority.ALWAYS);

        root = new VBox(content);
        root.setPrefWidth(width);
        root.setMinWidth(width);
        root.setMaxWidth(width);
        root.setMaxHeight(Double.MAX_VALUE);
        root.setStyle(
                "-fx-background-color: -color-bg-default;" +
                        "-fx-border-color: -color-border-default;" +
                        "-fx-border-width: 0 0 0 1px;"
        );
        root.setTranslateX(width);          // hidden off-screen to the right
        root.setMouseTransparent(true);
        root.setFocusTraversable(true);
        root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (!isInsideTextInput(e.getTarget())) root.requestFocus();
        });
        StackPane.setAlignment(root, Pos.TOP_RIGHT);
        StackPane.setMargin(root, new Insets(topInset, 0, 0, 0));
    }

    /**
     * The event target of a click inside a TextField/TextArea is usually an internal
     * skin node (e.g. {@code Text}), not the control itself, so a plain
     * {@code instanceof} check on the target always misses. Walk up to the nearest
     * {@link TextInputControl} ancestor instead.
     */
    private static boolean isInsideTextInput(EventTarget target) {
        Node node = (target instanceof Node) ? (Node) target : null;
        while (node != null) {
            if (node instanceof TextInputControl) return true;
            node = node.getParent();
        }
        return false;
    }

    /**
     * Open the panel and mark {@code triggerButton} as ACCENT.
     * If a {@code mutuallyExclusive} panel is provided it will be closed first.
     */
    public void open(javafx.scene.control.Button triggerButton, SidePanel... mutuallyExclusive) {
        for (SidePanel other : mutuallyExclusive) {
            if (other.isOpen()) other.close(null);
        }
        open = true;
        root.setMouseTransparent(false);
        if (triggerButton != null && !triggerButton.getStyleClass().contains(Styles.ACCENT))
            triggerButton.getStyleClass().add(Styles.ACCENT);
        animate(root.getTranslateX(), 0, null);
    }

    /**
     * Close the panel and remove ACCENT from {@code triggerButton}.
     */
    public void close(javafx.scene.control.Button triggerButton) {
        open = false;
        if (triggerButton != null)
            triggerButton.getStyleClass().remove(Styles.ACCENT);
        animate(root.getTranslateX(), width, () -> root.setMouseTransparent(true));
    }

    /**
     * Toggle open/closed.
     */
    public void toggle(javafx.scene.control.Button triggerButton, SidePanel... mutuallyExclusive) {
        if (open) close(triggerButton);
        else open(triggerButton, mutuallyExclusive);
    }

    /**
     * Returns true if the given scene coordinates are outside this panel's bounds.
     */
    public boolean isOutside(double sceneX, double sceneY) {
        var bounds = root.localToScene(root.getBoundsInLocal());
        return !bounds.contains(sceneX, sceneY);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void animate(double from, double to, Runnable onFinished) {
        if (timeline != null) timeline.stop();
        timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(root.translateXProperty(), from)),
                new KeyFrame(Duration.millis(260), new KeyValue(root.translateXProperty(), to,
                        Interpolator.EASE_BOTH))
        );
        if (onFinished != null) timeline.setOnFinished(e -> onFinished.run());
        timeline.play();
    }
}