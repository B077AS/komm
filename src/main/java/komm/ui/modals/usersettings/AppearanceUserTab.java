package komm.ui.modals.usersettings;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import atlantafx.base.controls.ToggleSwitch;
import komm.ui.theme.AppTheme;
import komm.ui.theme.ThemeManager;
import komm.ui.theme.ThemeMode;
import komm.App;
import komm.ui.modals.ConfirmationModal;
import komm.ui.utils.WindowChrome;
import komm.update.AppRestart;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import static komm.ui.modals.usersettings.UserSettingsUi.*;

/** "Appearance" tab: light/dark mode + accent picker. Applies immediately on click, no Save button needed. */
public class AppearanceUserTab implements UserSettingsTab {

    private final ScrollPane pane;
    private static final double TILE_MIN_WIDTH = 120;
    private static final int SWATCH_COLUMNS = 4;
    private static final double MODE_TILE_MIN_WIDTH = 160;

    /** Fixed palettes for the mode previews (each tile always shows its own mode, whatever is active). */
    private record Preview(String bg, String side, String border, String faint) {}
    private static final Preview DARK_PREVIEW = new Preview("#161616", "#1c1c1e", "#3a3a3c", "rgba(255,255,255,0.10)");
    private static final Preview LIGHT_PREVIEW = new Preview("#ffffff", "#f4f4f7", "#dedee3", "rgba(0,0,0,0.10)");

    private HBox modeRow;
    private GridPane swatchGrid;
    /** Strongly held here, weakly by ThemeManager, so a closed settings tab can be collected. */
    private final ChangeListener<Boolean> darkListener = (o, was, now) -> refresh();

    public AppearanceUserTab() {
        pane = wrapScroll(buildContent());
    }

    private VBox buildContent() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(20, 28, 20, 28));

        root.getChildren().add(sectionLabel("Theme"));
        modeRow = new HBox(10);
        modeRow.setPadding(new Insets(4, 0, 0, 0));
        root.getChildren().add(modeRow);

        root.getChildren().add(new Separator(Orientation.HORIZONTAL));

        Label accentTitle = sectionLabel("Accent Colour");
        Label accentHint = new Label("Shades are tuned per theme so text and buttons stay readable");
        accentHint.setStyle("-fx-font-size: 11.5px; -fx-text-fill: -color-fg-subtle;");
        HBox accentHeader = new HBox(10, accentTitle, accentHint);
        accentHeader.setAlignment(Pos.BASELINE_LEFT);
        root.getChildren().add(accentHeader);

        swatchGrid = new GridPane(10, 10);
        for (int i = 0; i < SWATCH_COLUMNS; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(100.0 / SWATCH_COLUMNS);
            col.setHgrow(Priority.ALWAYS);
            col.setFillWidth(true);
            swatchGrid.getColumnConstraints().add(col);
        }
        swatchGrid.setPadding(new Insets(4, 0, 0, 0));
        root.getChildren().add(swatchGrid);

        root.getChildren().add(new Separator(Orientation.HORIZONTAL));
        root.getChildren().add(sectionLabel("Window"));

        Label barTitle = new Label("Custom title bar");
        Label hint = new Label("Turn off to use your system's title bar instead, e.g. if dragging the window "
                + "misbehaves. Takes effect after restarting Komm.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11.5px; -fx-text-fill: -color-fg-subtle;");
        VBox barText = new VBox(2, barTitle, hint);
        HBox.setHgrow(barText, Priority.ALWAYS);

        ToggleSwitch customBar = new ToggleSwitch();
        customBar.setSelected(WindowChrome.isCustomPreferred());
        customBar.selectedProperty().addListener((o, was, now) -> {
            WindowChrome.setCustomPreferred(now);
            // A window's style can't change once it exists, so offer to restart right away. Only when
            // the new choice differs from what this window uses (switching back and forth asks nothing)
            // and when jpack can start us again; otherwise the hint's "after restarting" applies.
            if (now != WindowChrome.isExtended() && AppRestart.isSupported()) {
                App.showModal(new ConfirmationModal(
                        "Restart Komm?",
                        "The title bar changes after a restart. Restart now? You'll leave any voice channel you're in.",
                        new FontIcon(MaterialDesignR.RESTART),
                        AppRestart::restart));
            }
        });

        HBox barRow = new HBox(16, barText, customBar);
        barRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(barRow);

        refresh();
        // System mode can flip while the tab is open (OS switched scheme).
        ThemeManager.get().darkProperty().addListener(new WeakChangeListener<>(darkListener));
        return root;
    }

    private void refresh() {
        modeRow.getChildren().setAll(
                buildModeTile(ThemeMode.DARK, null),
                buildModeTile(ThemeMode.LIGHT, null),
                buildModeTile(ThemeMode.SYSTEM, "Follows OS"));
        swatchGrid.getChildren().clear();
        for (AppTheme theme : AppTheme.values()) {
            int i = theme.ordinal();
            swatchGrid.add(buildSwatch(theme), i % SWATCH_COLUMNS, i / SWATCH_COLUMNS);
        }
    }

    // ── Theme mode ────────────────────────────────────────────────────────────

    private VBox buildModeTile(ThemeMode mode, String hintText) {
        ThemeManager tm = ThemeManager.get();
        boolean active = tm.getMode() == mode;
        String accentNow = tm.getCurrentTheme().swatchColor(tm.isDark());
        AppTheme accent = tm.getCurrentTheme();

        StackPane frame = switch (mode) {
            case DARK -> miniWindow(DARK_PREVIEW, accent.swatchColor(true));
            case LIGHT -> miniWindow(LIGHT_PREVIEW, accent.swatchColor(false));
            case SYSTEM -> splitWindow(accent);
        };

        Region radio = new Region();
        radio.setMinSize(14, 14);
        radio.setMaxSize(14, 14);
        radio.setStyle("-fx-background-radius: 7px; -fx-border-radius: 7px; -fx-border-width: 2px;"
                + "-fx-border-color: " + (active ? accentNow : "-color-border-default") + ";"
                + "-fx-background-color: " + (active ? accentNow + ", -color-bg-subtle, " + accentNow : "transparent") + ";"
                + "-fx-background-insets: 0, 2, 4;");

        Label name = new Label(mode.getDisplayName());
        name.setStyle("-fx-font-size: 13px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, radio, name, spacer);
        footer.setAlignment(Pos.CENTER_LEFT);
        if (hintText != null) {
            Label h = new Label(hintText);
            h.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
            footer.getChildren().add(h);
        }

        VBox tile = new VBox(10, frame, footer);
        // The three tiles share the row equally and stretch with the modal.
        tile.setMinWidth(MODE_TILE_MIN_WIDTH);
        tile.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tile, Priority.ALWAYS);
        tile.setPadding(new Insets(10));
        tile.setStyle(tileStyle(accentNow, active, false));
        if (!active) {
            tile.setOnMouseEntered(e -> tile.setStyle(tileStyle(accentNow, false, true)));
            tile.setOnMouseExited(e -> tile.setStyle(tileStyle(accentNow, false, false)));
        }
        tile.setOnMouseClicked(e -> {
            if (tm.getMode() != mode) {
                tm.applyMode(mode);
                refresh();
            }
        });
        return tile;
    }

    /** A tiny app window (sidebar + two message bars + a button) in the given palette. */
    private static StackPane miniWindow(Preview p, String accent) {
        VBox side = new VBox(5,
                bar(24, 5, "-fx-background-color: " + p.faint() + ";"),
                bar(24, 5, "-fx-background-color: " + accent + "66;"),
                bar(24, 5, "-fx-background-color: " + p.faint() + ";"));
        side.setPadding(new Insets(8, 6, 8, 6));
        side.setMinWidth(38);
        side.setStyle("-fx-background-color: " + p.side() + ";");

        Region push = new Region();
        VBox.setVgrow(push, Priority.ALWAYS);
        VBox content = new VBox(5,
                bar(90, 7, "-fx-background-color: " + accent + "66;"),
                bar(60, 7, "-fx-background-color: " + p.faint() + ";"),
                push,
                bar(40, 13, "-fx-background-color: " + accent + ";"));
        content.setPadding(new Insets(8));
        HBox.setHgrow(content, Priority.ALWAYS);

        HBox win = new HBox(side, content);
        win.setStyle("-fx-background-color: " + p.bg() + ";");
        StackPane frame = new StackPane(win);
        frame.setPrefHeight(84);
        frame.setMinHeight(84);
        frame.setMaxHeight(84);
        frame.setStyle("-fx-border-color: " + p.border() + "; -fx-border-radius: 3px; -fx-background-radius: 3px;");
        clip(frame);
        return frame;
    }

    /** Left half light, right half dark. */
    private static StackPane splitWindow(AppTheme accent) {
        StackPane light = miniWindow(LIGHT_PREVIEW, accent.swatchColor(false));
        StackPane dark = miniWindow(DARK_PREVIEW, accent.swatchColor(true));
        Rectangle rightHalf = new Rectangle();
        rightHalf.xProperty().bind(dark.widthProperty().divide(2));
        rightHalf.widthProperty().bind(dark.widthProperty().divide(2));
        rightHalf.heightProperty().bind(dark.heightProperty());
        dark.setClip(rightHalf);    // replaces its rounded clip; the outer frame's clip rounds the corners
        StackPane frame = new StackPane(light, dark);
        frame.setPrefHeight(84);
        frame.setMinHeight(84);
        frame.setMaxHeight(84);
        clip(frame);
        return frame;
    }

    private static void clip(Region r) {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(6);
        clip.setArcHeight(6);
        clip.widthProperty().bind(r.widthProperty());
        clip.heightProperty().bind(r.heightProperty());
        r.setClip(clip);
    }

    // ── Accent ────────────────────────────────────────────────────────────────

    private VBox buildSwatch(AppTheme theme) {
        boolean active = ThemeManager.get().getCurrentTheme() == theme;
        String hex = theme.swatchColor(ThemeManager.get().isDark());

        // Mini preview: a message, a reply and a button in this theme's accent.
        VBox preview = new VBox(5,
                bar(70, 8, "-fx-background-color: " + hex + "66;"),
                bar(45, 8, "-fx-background-color: -color-overlay-5;"),
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
        tile.setMinWidth(TILE_MIN_WIDTH);
        tile.setMaxWidth(Double.MAX_VALUE);
        tile.setPadding(new Insets(10));
        tile.setStyle(tileStyle(hex, active, false));

        if (!active) {
            tile.setOnMouseEntered(e -> tile.setStyle(tileStyle(hex, false, true)));
            tile.setOnMouseExited(e -> tile.setStyle(tileStyle(hex, false, false)));
        }
        tile.setOnMouseClicked(e -> {
            ThemeManager.get().apply(theme);
            refresh();
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
