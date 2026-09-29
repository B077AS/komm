package komm.ui.customnodes;

import javafx.scene.control.Button;
import javafx.scene.control.skin.ButtonSkin;

/**
 * Shapes a button to a rectangle with two opposite corners (top-left, bottom-right)
 * cut at a fixed pixel size, matching the "Signal Panel" notched-button look from
 * the marketing site - see {@link NotchShape} for the actual geometry, shared with
 * plain (non-Control) Regions that want the same look but can't take a Skin.
 *
 * Uses {@code -fx-shape} rather than a node clip: a clip only masks the already
 * -painted background layers, which leaves {@code -fx-border-color} invisible along
 * the diagonal notch edges (the border was never actually painted there in the
 * first place, just clipped background). Setting the region's shape instead makes
 * JavaFX fill *and* stroke that exact polygon, so the border follows the notch too.
 *
 * Wired up purely via CSS: {@code -fx-skin: "komm.ui.customnodes.NotchedButtonSkin";}
 * on whichever button selectors should get the notch (see style.css, .button.accent).
 */
public class NotchedButtonSkin extends ButtonSkin {

    public NotchedButtonSkin(Button button) {
        super(button);
        NotchShape.apply(button);
    }
}
