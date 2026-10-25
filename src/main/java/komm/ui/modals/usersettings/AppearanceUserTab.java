package komm.ui.modals.usersettings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import komm.ui.theme.AppTheme;
import komm.ui.theme.ThemeManager;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import static komm.ui.modals.usersettings.UserSettingsUi.*;

/** "Appearance" tab: accent theme picker. Applies immediately on click, no Save button needed. */
public class AppearanceUserTab implements UserSettingsTab {

    private final ScrollPane pane;
    private static final double TILE_WIDTH = 150;

    private FlowPane swatchGrid;

    public AppearanceUserTab() {
        pane = wrapScroll(buildContent());
    }

    private VBox buildContent() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(24));

        root.getChildren().add(sectionLabel("Accent Colour"));

        swatchGrid = new FlowPane(10, 10);
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
        String hex = theme.getSwatchColor();

        // Mini preview: a message, a reply and a button in this theme's accent.
        VBox preview = new VBox(5,
                bar(70, 8, "-fx-background-color: " + hex + "66;"),
                bar(45, 8, "-fx-background-color: rgba(255,255,255,0.10);"),
                bar(44, 14, "-fx-background-color: " + hex + ";"));
        preview.setPadding(new Insets(8));
        preview.setStyle("-fx-background-color: -color-bg-inset; -fx-background-radius: 3px;");

        Label name = new Label(theme.getDisplayName());
        name.setStyle("-fx-font-size: 12px;");
        Label code = new Label(hex);
        code.setStyle("-fx-font-size: 10.5px; -fx-text-fill: -color-fg-subtle;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(name, spacer, code);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox tile = new VBox(8, preview, footer);
        tile.setPrefWidth(TILE_WIDTH);
        tile.setMinWidth(TILE_WIDTH);
        tile.setPadding(new Insets(10));
        tile.setStyle(tileStyle(hex, active, false));

        if (!active) {
            tile.setOnMouseEntered(e -> tile.setStyle(tileStyle(hex, false, true)));
            tile.setOnMouseExited(e -> tile.setStyle(tileStyle(hex, false, false)));
        }
        tile.setOnMouseClicked(e -> {
            ThemeManager.get().apply(theme);
            populateSwatches();
        });
        return tile;
    }

    private static Region bar(double width, double height, String style) {
        Region r = new Region();
        r.setPrefSize(width, height);
        r.setMinSize(width, height);
        r.setMaxSize(width, height);
        r.setStyle(style + "-fx-background-radius: 2px;");
        return r;
    }

    private static String tileStyle(String hex, boolean active, boolean hover) {
        // Constant 2px border so selecting a tile never changes its size and shifts the grid.
        String border = active ? hex : (hover ? "-color-fg-subtle" : "-color-border-default");
        return "-fx-background-color: -color-bg-subtle;" +
               "-fx-background-radius: 4px;" +
               "-fx-border-color: " + border + ";" +
               "-fx-border-radius: 4px;" +
               "-fx-border-width: 2px;" +
               "-fx-cursor: hand;";
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
