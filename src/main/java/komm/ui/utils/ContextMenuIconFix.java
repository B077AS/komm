package komm.ui.utils;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.MenuItem;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;
import org.kordamp.ikonli.feather.Feather;

import java.util.Map;
import java.util.TreeMap;

/**
 * Adds icons to JavaFX's built-in text-editing context menu (Cut/Copy/Paste/
 * Delete/Select All/Undo/Redo), the one {@code TextInputControl} pops up on
 * its own and never exposes as a real {@link javafx.scene.control.ContextMenu}.
 *
 * <p>JavaFX's {@code ContextMenuContent.MenuItemContainer} only allocates a
 * row's icon slot when {@link MenuItem#getGraphic()} is already non-null at
 * build time, which is never true for the default Cut/Copy/Paste items. So
 * instead of poking the rendered popup, this reaches the real, live
 * {@link MenuItem} instances — each row exposes its backing item publicly via
 * {@code node.getProperties().get(MenuItem.class)} (JavaFX puts it there
 * itself, for QA/testing) — and calls {@link MenuItem#setGraphic}. That's a
 * real property write, so JavaFX's own listener rebuilds the row correctly,
 * with all enabled/disabled/selection bindings and actions left untouched.
 */
public class ContextMenuIconFix {

    private static final Map<String, Ikon> ICONS_BY_LABEL = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    static {
        ICONS_BY_LABEL.put("Cut", MaterialDesignC.CONTENT_CUT);
        ICONS_BY_LABEL.put("Copy", MaterialDesignC.CONTENT_COPY);
        ICONS_BY_LABEL.put("Paste", MaterialDesignC.CONTENT_PASTE);
        ICONS_BY_LABEL.put("Delete", Feather.TRASH_2);
        ICONS_BY_LABEL.put("Select All", MaterialDesignS.SELECT_ALL);
        ICONS_BY_LABEL.put("Undo", MaterialDesignU.UNDO);
        ICONS_BY_LABEL.put("Redo", MaterialDesignR.REDO);
    }

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
        if (menuNode == null || !(menuNode instanceof Parent menuParent)) return;

        for (Node itemNode : menuParent.lookupAll(".menu-item")) {
            applyIcon(itemNode);
        }
    }

    private static void applyIcon(Node itemNode) {
        Object raw = itemNode.getProperties().get(MenuItem.class);
        if (!(raw instanceof MenuItem item)) return;
        if (item.getGraphic() != null) return;

        String text = item.getText();
        if (text == null) return;

        Ikon icon = ICONS_BY_LABEL.get(text.trim());
        if (icon == null) return;

        item.setGraphic(new FontIcon(icon));
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
