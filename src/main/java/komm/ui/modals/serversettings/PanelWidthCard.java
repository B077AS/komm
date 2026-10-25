package komm.ui.modals.serversettings;

import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Read-only display of a server's default channel panel width: a miniature of the server page with the
 * channel list highlighted, the number set large beside it, and the one action that can change it
 * ("Use my current width"). The pending value lives here; {@code onPendingChange} is told about every
 * change ({@code null} once undone back to the saved value).
 */
public class PanelWidthCard extends HBox {

    /** Fallback the app uses when a server has no default, and the channel panel's own minimum. */
    private static final int FALLBACK_WIDTH = 240;
    /** Window width the miniature and the percentages are drawn against. */
    private static final double REFERENCE_WINDOW = 1280;
    private static final double PREVIEW_W = 280;
    private static final double PREVIEW_H = 118;

    private final Integer savedWidth;
    private final IntSupplier myWidth;
    private final Consumer<Integer> onPendingChange;

    private Integer pending;

    private final Pane preview = new Pane();
    private final Label bigNumber = new Label();
    private final Label shareLabel = new Label();
    private final Button useButton = new Button();
    private final HBox unsavedRow = new HBox(6);

    public PanelWidthCard(Integer savedWidth, IntSupplier myWidth, Consumer<Integer> onPendingChange) {
        super(20);
        this.savedWidth = savedWidth != null && savedWidth > 0 ? savedWidth : null;
        this.myWidth = myWidth;
        this.onPendingChange = onPendingChange;

        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(16));
        setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 4px;"
                + " -fx-border-color: -color-border-default; -fx-border-radius: 4px; -fx-border-width: 1px;");

        preview.setPrefSize(PREVIEW_W, PREVIEW_H);
        preview.setMinSize(PREVIEW_W, PREVIEW_H);
        preview.setMaxSize(PREVIEW_W, PREVIEW_H);
        preview.setStyle("-fx-background-color: -color-bg-inset; -fx-border-color: -color-border-default;"
                + " -fx-border-radius: 4px; -fx-background-radius: 4px;");
        preview.setClip(new Rectangle(PREVIEW_W, PREVIEW_H));

        getChildren().addAll(preview, buildStats());
        refresh();
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private VBox buildStats() {
        bigNumber.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: -color-accent-fg;");
        Label unit = new Label("px");
        unit.setStyle("-fx-font-size: 14px; -fx-text-fill: -color-fg-muted;");
        HBox number = new HBox(4, bigNumber, unit);
        number.setAlignment(Pos.BASELINE_LEFT);

        shareLabel.setWrapText(true);
        shareLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");

        useButton.setFocusTraversable(false);
        useButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        useButton.setOnAction(e -> {
            pending = myWidth.getAsInt();
            onPendingChange.accept(pending);
            refresh();
        });

        Label unsaved = new Label();
        unsaved.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-warning-fg;");
        unsaved.setId("unsaved");
        Button undo = new Button("Undo");
        undo.setFocusTraversable(false);
        undo.getStyleClass().addAll(Styles.FLAT, Styles.SMALL, Styles.ACCENT);
        undo.setOnAction(e -> {
            pending = null;
            onPendingChange.accept(null);
            refresh();
        });
        unsavedRow.getChildren().addAll(unsaved, undo);
        unsavedRow.setAlignment(Pos.CENTER_LEFT);

        VBox stats = new VBox(4, number, shareLabel, spacer(), useButton, unsavedRow);
        stats.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(stats, Priority.ALWAYS);
        return stats;
    }

    private static Region spacer() {
        Region r = new Region();
        r.setMinHeight(6);
        return r;
    }

    // ── State → UI ────────────────────────────────────────────────────────────

    private int effectiveWidth() {
        if (pending != null) return pending;
        return savedWidth != null ? savedWidth : FALLBACK_WIDTH;
    }

    private void refresh() {
        int width = effectiveWidth();
        int mine = myWidth.getAsInt();

        bigNumber.setText(String.valueOf(width));
        long share = Math.round(width / REFERENCE_WINDOW * 100);
        shareLabel.setText((savedWidth == null && pending == null ? "Not set, members start at the minimum. " : "")
                + share + "% of a 1280 px window, chat gets " + Math.round(REFERENCE_WINDOW - width) + " px");

        boolean same = mine == width;
        useButton.setText(same ? "Already your width" : "Use my current width (" + mine + " px)");
        useButton.setDisable(same);

        boolean changed = pending != null && !pending.equals(savedWidth);
        unsavedRow.setVisible(changed);
        unsavedRow.setManaged(changed);
        if (changed) {
            ((Label) unsavedRow.lookup("#unsaved")).setText(
                    "Unsaved: was " + (savedWidth != null ? savedWidth : FALLBACK_WIDTH) + " px");
        }

        drawPreview(width);
    }

    /** Redraws the miniature: channel list at {@code width}, chat filling the rest. */
    private void drawPreview(int width) {
        double scale = PREVIEW_W / REFERENCE_WINDOW;
        double panelW = Math.max(40, width * scale);

        preview.getChildren().clear();

        // Channel list: accent-tinted so it reads as "the thing being set".
        VBox channels = new VBox(5);
        channels.setPadding(new Insets(8, 6, 8, 6));
        channels.setPrefSize(panelW, PREVIEW_H);
        channels.setMinSize(panelW, PREVIEW_H);
        channels.setMaxSize(panelW, PREVIEW_H);
        channels.setStyle("-fx-background-color: -color-accent-subtle;"
                + " -fx-border-color: transparent -color-accent-muted transparent transparent; -fx-border-width: 0 1 0 0;");
        channels.getChildren().add(skeleton((panelW - 12) * 0.55, 7, "-color-accent-muted"));
        channels.getChildren().add(skeleton(1, 3, "transparent"));
        double inner = panelW - 12;
        double[] fractions = {0.9, 0.7, 0.8, 0.6, 0.85, 0.75, 0.65, 0.9};
        for (double f : fractions) {
            channels.getChildren().add(skeleton(inner * f, 7, "rgba(255,255,255,0.14)"));
        }
        channels.setLayoutX(0);

        // Chat: a few bubbles bottom-aligned, own messages to the right.
        double chatX = panelW;
        double chatW = PREVIEW_W - panelW;
        VBox chat = new VBox(6);
        chat.setAlignment(Pos.BOTTOM_LEFT);
        chat.setPadding(new Insets(8, 10, 8, 10));
        chat.setPrefSize(chatW, PREVIEW_H);
        chat.setMinSize(chatW, PREVIEW_H);
        chat.setMaxSize(chatW, PREVIEW_H);
        double w = Math.max(10, chatW - 20);
        chat.getChildren().addAll(
                bubble(w * 0.6, false),
                bubble(w * 0.42, true),
                bubble(w * 0.72, false),
                bubble(w * 0.3, true),
                bubble(w, false, 14));
        chat.setLayoutX(chatX);

        preview.getChildren().addAll(channels, chat);
    }

    private static Region skeleton(double width, double height, String color) {
        Region r = new Region();
        double w = Math.max(4, width);
        r.setPrefSize(w, height);
        r.setMinSize(w, height);
        r.setMaxSize(w, height);
        r.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 2px;");
        return r;
    }

    private static HBox bubble(double width, boolean mine) {
        return bubble(width, mine, 9);
    }

    private static HBox bubble(double width, boolean mine, double height) {
        String color = mine ? "-color-accent-muted" : "rgba(255,255,255,0.09)";
        if (height > 9) color = "rgba(255,255,255,0.05)";   // the message-input bar
        HBox row = new HBox(skeleton(width, height, color));
        row.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return row;
    }
}
