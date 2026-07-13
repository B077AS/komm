package komm.ui.modals.usersettings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import komm.ui.theme.AppTheme;
import komm.ui.theme.ThemeManager;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import static komm.ui.modals.usersettings.UserSettingsUi.*;

/** "Appearance" tab: accent theme picker. Applies immediately on click, no Save button needed. */
public class AppearanceUserTab implements UserSettingsTab {

    private final ScrollPane pane;
    private FlowPane swatchGrid;

    public AppearanceUserTab() {
        pane = wrapScroll(buildContent());
    }

    private VBox buildContent() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(24));

        root.getChildren().add(sectionLabel("Accent Colour"));

        swatchGrid = new FlowPane(12, 20);
        swatchGrid.setPadding(new Insets(4, 0, 0, 0));
        populateSwatches();

        root.getChildren().add(swatchGrid);
        return root;
    }

    private void populateSwatches() {
        swatchGrid.getChildren().clear();
        for (AppTheme theme : AppTheme.values()) {
            swatchGrid.getChildren().add(buildSwatch(theme));
        }
    }

    private VBox buildSwatch(AppTheme theme) {
        boolean active = ThemeManager.get().getCurrentTheme() == theme;

        Circle colorCircle = new Circle(20);
        colorCircle.setStyle("-fx-fill: " + theme.getSwatchColor() + ";");

        StackPane circlePane = new StackPane(colorCircle);
        if (active) {
            FontIcon check = new FontIcon(MaterialDesignC.CHECK);
            circlePane.getChildren().add(check);
        }

        Label name = new Label(theme.getDisplayName());
        name.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        VBox box = new VBox(8, circlePane, name);
        box.setAlignment(Pos.CENTER);
        box.setPrefWidth(84);
        box.setPadding(new Insets(10));

        if (active) {
            box.setStyle(
                "-fx-background-color: rgba(255,255,255,0.06);" +
                "-fx-background-radius: 8px;" +
                "-fx-border-color: " + theme.getSwatchColor() + ";" +
                "-fx-border-radius: 8px;" +
                "-fx-border-width: 2px;");
        } else {
            applyIdleStyle(box);
            box.setOnMouseEntered(e -> box.setStyle(
                "-fx-background-color: rgba(255,255,255,0.04);" +
                "-fx-background-radius: 8px;" +
                "-fx-border-color: transparent;" +
                "-fx-border-radius: 8px;" +
                "-fx-border-width: 2px;" +
                "-fx-cursor: hand;"));
            box.setOnMouseExited(e -> applyIdleStyle(box));
        }

        box.setOnMouseClicked(e -> {
            ThemeManager.get().apply(theme);
            populateSwatches();
        });

        return box;
    }

    private static void applyIdleStyle(VBox box) {
        box.setStyle(
            "-fx-background-radius: 8px;" +
            "-fx-border-color: transparent;" +
            "-fx-border-radius: 8px;" +
            "-fx-border-width: 2px;" +
            "-fx-cursor: hand;");
    }

    @Override
    public String name() { return "Appearance"; }

    @Override
    public String description() { return "Personalize the look and feel of the app"; }

    @Override
    public FontIcon icon() {
        return new FontIcon(MaterialDesignP.PALETTE_OUTLINE);
    }

    @Override
    public Node getPane() { return pane; }
}
