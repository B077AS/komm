package komm.ui.customnodes;

import javafx.scene.layout.Region;
import javafx.scene.shape.Polygon;

/**
 * Shapes a plain Region (not a Control, so it can't take a custom Skin) to the
 * same "Signal Panel" notched silhouette as {@link NotchedButtonSkin} - two
 * opposite corners (top-left, bottom-right) cut at a fixed pixel size, kept in
 * sync with the region's actual layout bounds.
 *
 * Use this for clickable cards/rows built as a StackPane/HBox/VBox instead of a
 * Button (e.g. a filled soundboard slot), where the button-only CSS skin hook
 * can't reach.
 */
public final class NotchShape {

    private static final double DEFAULT_NOTCH = 8;

    private NotchShape() {
    }

    public static void apply(Region region) {
        apply(region, DEFAULT_NOTCH);
    }

    public static void apply(Region region, double notch) {
        Polygon shape = new Polygon();
        region.setShape(shape);
        region.widthProperty().addListener((obs, oldV, newV) -> update(shape, notch, newV.doubleValue(), region.getHeight()));
        region.heightProperty().addListener((obs, oldV, newV) -> update(shape, notch, region.getWidth(), newV.doubleValue()));
        update(shape, notch, region.getWidth(), region.getHeight());
    }

    private static void update(Polygon shape, double notch, double width, double height) {
        if (width <= 0 || height <= 0) {
            shape.getPoints().clear();
            return;
        }
        double n = Math.min(notch, Math.min(width, height) / 2);
        shape.getPoints().setAll(
                n, 0.0,
                width, 0.0,
                width, height - n,
                width - n, height,
                0.0, height,
                0.0, n
        );
    }
}
