package komm.ui.customnodes;

import atlantafx.base.theme.Styles;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;

/**
 * A fully self-contained color picker that opens as a {@link Popup} (NOT a Stage),
 * so it can never cause the minimize-windows bug (JDK-8260024).
 * <p>
 * Usage:
 * ColorPickerPopup picker = new ColorPickerPopup(Color.CORAL);
 * picker.setOnColorConfirmed(color -> doSomethingWith(color));
 * <p>
 * // To open it anchored below a node:
 * picker.show(myButton);
 * <p>
 * // Or at a specific screen position:
 * picker.show(anyNode, screenX, screenY);
 * <p>
 * // Read the current color at any time:
 * Color c = picker.getColor();
 */
public class ColorPickerPopup {

    // ── Public API ────────────────────────────────────────────────────────────

    private final ObjectProperty<Color> color = new SimpleObjectProperty<>(Color.WHITE);
    private Runnable onConfirmed;

    public Color getColor() {
        return color.get();
    }

    public void setColor(Color c) {
        color.set(c);
        applyColor(c);
    }

    public ObjectProperty<Color> colorProperty() {
        return color;
    }

    public void setOnColorConfirmed(Runnable r) {
        this.onConfirmed = r;
    }

    // ── Internal state ────────────────────────────────────────────────────────

    private final Popup popup = new Popup();
    private final Canvas gradientCanvas = new Canvas(220, 180);
    private final Circle cursor = new Circle(6);
    private final Canvas hueCanvas = new Canvas(220, 14);
    private final Circle hueCursor = new Circle(6);
    private final TextField hexField = new TextField();
    private final Rectangle preview = new Rectangle(28, 28);

    // Current hue (0-1), used to redraw the gradient
    private double currentHue = 0;
    private double cursorX = 220, cursorY = 0; // position in gradient
    private double hueCursorX = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ColorPickerPopup(Color initial) {
        popup.setAutoHide(true);
        popup.setConsumeAutoHidingEvents(false);
        popup.getContent().add(buildContent());
        hexField.getStyleClass().add(Styles.SMALL);
        applyColor(initial);
    }

    // ── Show helpers ──────────────────────────────────────────────────────────

    /**
     * Show anchored below-left of a node.
     */
    public void show(javafx.scene.Node owner) {
        javafx.geometry.Bounds b = owner.localToScreen(owner.getBoundsInLocal());
        popup.show(owner, b.getMinX(), b.getMaxY() + 4);
    }

    /**
     * Show at explicit screen coordinates.
     */
    public void show(javafx.scene.Node owner, double screenX, double screenY) {
        popup.show(owner, screenX, screenY);
    }

    public void hide() {
        popup.hide();
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private VBox buildContent() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(14));
        root.getStyleClass().add("custom-pop-up");
        root.setPrefWidth(248);
        root.setMaxWidth(248);

        // ── Gradient canvas (SB square) ────────────────────────────────────
        Pane gradientPane = new Pane();
        gradientCanvas.setWidth(220);
        gradientCanvas.setHeight(180);
        gradientCanvas.setStyle("-fx-cursor: crosshair;");
        drawGradient(currentHue);

        cursor.setFill(Color.TRANSPARENT);
        cursor.setStroke(Color.WHITE);
        cursor.setStrokeWidth(2);
        cursor.setMouseTransparent(true);
        cursor.setEffect(new javafx.scene.effect.DropShadow(4, Color.rgb(0, 0, 0, 0.5)));

        gradientPane.getChildren().addAll(gradientCanvas, cursor);
        gradientPane.setPrefSize(220, 180);
        gradientPane.setMaxSize(220, 180);

        gradientCanvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> pickFromGradient(e));
        gradientCanvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> pickFromGradient(e));

        // ── Hue bar ────────────────────────────────────────────────────────
        Pane huePane = new Pane();
        hueCanvas.setWidth(220);
        hueCanvas.setHeight(14);
        hueCanvas.setStyle("-fx-cursor: h-resize;");
        drawHueBar();

        hueCursor.setFill(Color.TRANSPARENT);
        hueCursor.setStroke(Color.WHITE);
        hueCursor.setStrokeWidth(2);
        hueCursor.setMouseTransparent(true);
        hueCursor.setEffect(new javafx.scene.effect.DropShadow(4, Color.rgb(0, 0, 0, 0.5)));
        hueCursor.setCenterY(7);

        huePane.getChildren().addAll(hueCanvas, hueCursor);
        huePane.setPrefSize(220, 14);
        huePane.setMaxSize(220, 14);

        hueCanvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> pickFromHue(e));
        hueCanvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> pickFromHue(e));

        // ── Bottom row: preview + hex + OK ─────────────────────────────────
        preview.setWidth(28);
        preview.setHeight(28);
        preview.setArcWidth(6);
        preview.setArcHeight(6);
        preview.setStroke(Color.gray(0.4));
        preview.setStrokeWidth(1);

        hexField.setPrefWidth(88);
        hexField.setStyle("-fx-font-size: 12px;");
        hexField.setOnAction(e -> commitHex());
        hexField.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) commitHex();
        });

        Button okBtn = new Button("OK");
        okBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        okBtn.setOnAction(e -> {
            if (onConfirmed != null) onConfirmed.run();
            popup.hide();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bottom = new HBox(8, preview, hexField, spacer, okBtn);
        bottom.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(gradientPane, huePane, bottom);
        return root;
    }

    // ── Gradient drawing ──────────────────────────────────────────────────────

    private void drawGradient(double hue) {
        GraphicsContext gc = gradientCanvas.getGraphicsContext2D();
        double w = gradientCanvas.getWidth();
        double h = gradientCanvas.getHeight();

        // Base hue color
        Color hueColor = Color.hsb(hue * 360, 1, 1);

        // White→hue horizontal gradient
        gc.clearRect(0, 0, w, h);
        LinearGradient horizontal = new LinearGradient(
                0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE), new Stop(1, hueColor)
        );
        gc.setFill(horizontal);
        gc.fillRect(0, 0, w, h);

        // Transparent→black vertical gradient
        LinearGradient vertical = new LinearGradient(
                0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.TRANSPARENT), new Stop(1, Color.BLACK)
        );
        gc.setFill(vertical);
        gc.fillRect(0, 0, w, h);
    }

    private void drawHueBar() {
        GraphicsContext gc = hueCanvas.getGraphicsContext2D();
        double w = hueCanvas.getWidth();
        double h = hueCanvas.getHeight();
        for (int x = 0; x < (int) w; x++) {
            gc.setFill(Color.hsb(x / w * 360, 1, 1));
            gc.fillRect(x, 0, 1, h);
        }
    }

    // ── Picking ───────────────────────────────────────────────────────────────

    private void pickFromGradient(MouseEvent e) {
        double x = Math.max(0, Math.min(e.getX(), gradientCanvas.getWidth()));
        double y = Math.max(0, Math.min(e.getY(), gradientCanvas.getHeight()));
        cursorX = x;
        cursorY = y;
        cursor.setCenterX(x);
        cursor.setCenterY(y);

        double sat = x / gradientCanvas.getWidth();
        double bri = 1 - (y / gradientCanvas.getHeight());
        Color picked = Color.hsb(currentHue * 360, sat, bri);
        setColorInternal(picked);
    }

    private void pickFromHue(MouseEvent e) {
        double x = Math.max(0, Math.min(e.getX(), hueCanvas.getWidth()));
        hueCursorX = x;
        hueCursor.setCenterX(x);
        currentHue = x / hueCanvas.getWidth();
        drawGradient(currentHue);

        // Re-pick from same gradient position
        double sat = cursorX / gradientCanvas.getWidth();
        double bri = 1 - (cursorY / gradientCanvas.getHeight());
        Color picked = Color.hsb(currentHue * 360, sat, bri);
        setColorInternal(picked);
    }

    private void commitHex() {
        try {
            String text = hexField.getText().trim();
            if (!text.startsWith("#")) text = "#" + text;
            Color parsed = Color.web(text);
            applyColor(parsed);
        } catch (IllegalArgumentException ignored) {
            // Restore valid hex
            hexField.setText(toHex(color.get()));
        }
    }

    // ── Internal color sync ───────────────────────────────────────────────────

    /**
     * Called when picking from canvas — updates property + hex + preview but not cursors (already moved).
     */
    private void setColorInternal(Color c) {
        color.set(c);
        preview.setFill(c);
        hexField.setText(toHex(c));
    }

    /**
     * Called from outside (setColor / commitHex) — syncs everything including cursors.
     */
    private void applyColor(Color c) {
        if (c == null) return;
        color.set(c);
        preview.setFill(c);
        hexField.setText(toHex(c));

        // Compute hue/sat/bri and move cursors
        double h = c.getHue() / 360.0;
        double s = c.getSaturation();
        double b = c.getBrightness();

        currentHue = h;
        hueCursorX = h * hueCanvas.getWidth();
        hueCursor.setCenterX(hueCursorX);
        drawGradient(currentHue);

        cursorX = s * gradientCanvas.getWidth();
        cursorY = (1 - b) * gradientCanvas.getHeight();
        cursor.setCenterX(cursorX);
        cursor.setCenterY(cursorY);
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}