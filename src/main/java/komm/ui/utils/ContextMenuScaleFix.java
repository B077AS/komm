package komm.ui.utils;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

/**
 * Shrinks context-menu popups slightly while keeping them anchored flush
 * against the control that opened them.
 *
 * <p>This used to be a plain CSS {@code -fx-scale-x}/{@code -fx-scale-y}
 * rule on {@code .context-menu}, but {@link javafx.scene.control.PopupControl}
 * routes its CSS metadata through its own {@code getCssMetaData()} rather
 * than the generic node metadata, so the CSS engine silently never applied
 * the scale (other properties on the same rule, like background and
 * padding, worked fine — only the transform properties were dropped).
 *
 * <p>The scale (and its position correction) is applied here as a local
 * transform on the menu's own content node instead — deliberately not by
 * moving the popup window (via {@code PopupWindow.setX/setY}), because
 * JavaFX's own internal popup-anchoring logic re-positions that same window
 * every time the content's bounds change, which would otherwise race with
 * an external correction. Scaling and translating the node itself doesn't
 * touch its layout bounds, so the window is still sized and positioned by
 * JavaFX exactly as if the content were unscaled (i.e. correctly anchored),
 * and the node then renders shrunk — around its own center, hence the
 * {@code translate} to shift it back — inside that correctly-placed window.
 */
public class ContextMenuScaleFix {

    private static final double SCALE = 0.85;

    private static boolean installed = false;

    public static void install() {
        if (installed) return;
        installed = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (!change.wasAdded()) continue;
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof PopupWindow popup) {
                        handle(popup);
                    }
                }
            }
        });
    }

    private static void handle(PopupWindow popup) {
        if (popup.getScene() == null) return;
        Parent sceneRoot = popup.getScene().getRoot();
        if (sceneRoot == null) return;

        Node menuNode = findContextMenuNode(sceneRoot);
        if (menuNode == null) return;

        menuNode.setScaleX(SCALE);
        menuNode.setScaleY(SCALE);
        adjustTranslate(menuNode);

        if (menuNode.getProperties().put("komm.contextMenuScaleFix", Boolean.TRUE) == null) {
            menuNode.layoutBoundsProperty().addListener((obs, old, val) -> adjustTranslate(menuNode));
        }
    }

    private static void adjustTranslate(Node menuNode) {
        double w = menuNode.getLayoutBounds().getWidth();
        double h = menuNode.getLayoutBounds().getHeight();
        menuNode.setTranslateX(-w * (1 - SCALE) / 2);
        menuNode.setTranslateY(-h * (1 - SCALE) / 2);
    }

    private static Node findContextMenuNode(Node node) {
        if (node.getStyleClass().contains("context-menu")) return node;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findContextMenuNode(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
