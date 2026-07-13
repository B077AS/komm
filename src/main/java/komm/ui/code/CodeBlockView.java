package komm.ui.code;

import atlantafx.base.theme.Styles;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import lombok.Getter;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.Caret;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only, syntax-highlighted code card shown inside a message bubble.
 *
 * <p>Header shows the language, the line count, a copy button, and — when the
 * snippet is longer than {@value #COLLAPSED_LINES} lines — an expand/collapse
 * toggle (accordion). Collapsed it previews the first {@value #COLLAPSED_LINES}
 * lines; expanded it grows up to {@value #EXPANDED_MAX_LINES} lines and then
 * scrolls internally.
 */
public class CodeBlockView extends VBox {

    private static final double LINE_PX = 18.0;
    private static final int COLLAPSED_LINES = 8;
    private static final int EXPANDED_MAX_LINES = 30;
    private static final double MAX_WIDTH = 700.0;

    private static final List<WeakReference<CodeArea>> ALL_CODE_AREAS = new ArrayList<>();

    @Getter
    private final String code;
    @Getter
    private final CodeLanguage language;

    private final CodeArea codeArea;
    private final int totalLines;
    private final boolean expandable;
    private final boolean hasLongLine;

    private boolean expanded = false;
    private FontIcon toggleIcon;
    private Label toggleLabel;

    /**
     * Notifies the owner that this block's height is changing so it can re-anchor
     * the chat scroll. Fired from two places that cover different timings:
     * <ul>
     *   <li>a {@code heightProperty} listener — catches RichTextFX's deferred
     *       (async) height settle after the block is added (e.g. after sending);</li>
     *   <li>synchronously from {@link #toggle()} — arms the owner's scroll listener
     *       <em>before</em> the expand/collapse layout pulse, which the reactive
     *       path alone would race and miss for the newest message.</li>
     * </ul>
     */
    @lombok.Setter
    private Runnable onResize;

    public CodeBlockView(String code, CodeLanguage language) {
        super(0);
        this.code = code == null ? "" : code;
        this.language = language == null ? CodeLanguage.PLAIN_TEXT : language;
        this.totalLines = Math.max(1, (int) this.code.lines().count());
        this.expandable = totalLines > COLLAPSED_LINES;
        this.hasLongLine = this.code.lines().anyMatch(l -> l.length() > 80);

        setMaxWidth(MAX_WIDTH);
        setFillWidth(true);
        setStyle("-fx-background-color: -color-bg-inset;"
                + "-fx-background-radius: 8px;"
                + "-fx-border-color: -color-border-default;"
                + "-fx-border-radius: 8px;"
                + "-fx-border-width: 1px;");

        codeArea = new CodeArea(this.code);
        codeArea.getStyleClass().add("code-area");
        // Inline background reliably overrides RichTextFX's default white surface
        // (a stylesheet rule loses to the control's own author stylesheet).
        codeArea.setStyle("-fx-background-color: transparent;");
        codeArea.setEditable(false);
        codeArea.setFocusTraversable(false);
        codeArea.setWrapText(false);
        codeArea.setShowCaret(Caret.CaretVisibility.OFF);
        codeArea.setPadding(new Insets(6, 10, 6, 0));
        codeArea.setParagraphGraphicFactory(CodeAreaSupport.lineNumberFactory(codeArea));
        // Compute highlighting off the FX thread so large snippets don't freeze
        // the chat while the message list is being populated.
        String codeSnap = this.code;
        CodeLanguage langSnap = this.language;
        Thread.ofVirtual().start(() -> {
            var spans = CodeHighlighter.computeHighlighting(codeSnap, langSnap);
            Platform.runLater(() -> {
                codeArea.setStyleSpans(0, spans);
                CodeAreaSupport.applyRowStripes(codeArea);
            });
        });

        ALL_CODE_AREAS.add(new WeakReference<>(codeArea));
        codeArea.selectionProperty().addListener((obs, ov, nv) -> {
            if (nv.getLength() > 0) {
                ALL_CODE_AREAS.removeIf(ref -> ref.get() == null);
                for (WeakReference<CodeArea> ref : new ArrayList<>(ALL_CODE_AREAS)) {
                    CodeArea other = ref.get();
                    if (other != null && other != codeArea) other.deselect();
                }
            }
        });
        // Clicking anywhere outside this snippet (blank chat area, another message, a
        // button in the header, etc.) should clear a stale selection — plain background
        // nodes don't steal focus in JavaFX, so focus alone won't tell us that happened.
        EventHandler<MouseEvent> outsideClickDeselect = e -> {
            if (codeArea.getSelection().getLength() == 0) return;
            Node target = e.getTarget() instanceof Node ? (Node) e.getTarget() : null;
            if (!isDescendantOf(target, codeArea)) codeArea.deselect();
        };
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) oldScene.removeEventFilter(MouseEvent.MOUSE_PRESSED, outsideClickDeselect);
            if (newScene != null) newScene.addEventFilter(MouseEvent.MOUSE_PRESSED, outsideClickDeselect);
        });
        // When collapsed, block internal scrolling and forward the event up to the
        // chat ScrollPane so the outer scroll still works normally.
        codeArea.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (!expanded) {
                e.consume();
                Event.fireEvent(CodeBlockView.this, e.copyFor(CodeBlockView.this, CodeBlockView.this));
            }
        });

        getChildren().addAll(buildHeader(), codeArea);
        applyHeight();

        // Catches RichTextFX's deferred height settle after the block is added.
        heightProperty().addListener((o, ov, nv) -> {
            if (onResize != null) onResize.run();
        });
    }

    private HBox buildHeader() {
        FontIcon codeIcon = new FontIcon(MaterialDesignC.CODE_BRACES);
        codeIcon.getStyleClass().add("custom-icon-15");

        Label langLabel = new Label(language.getDisplayName());
        langLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");

        HBox left = new HBox(6, codeIcon, langLabel);
        left.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label lineCount = new Label(totalLines + (totalLines == 1 ? " line" : " lines"));
        lineCount.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        Button copyBtn = new Button(null, new FontIcon(MaterialDesignC.CONTENT_COPY));
        copyBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
        copyBtn.setFocusTraversable(false);
        copyBtn.setTooltip(new Tooltip("Copy code"));
        copyBtn.setOnAction(e -> copyCode(copyBtn));

        HBox header = new HBox(8, left, spacer, lineCount, copyBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 6, 4, 10));
        header.setStyle("-fx-background-color: -color-bg-subtle;"
                + "-fx-background-radius: 8px 8px 0 0;"
                + "-fx-border-color: transparent transparent -color-border-muted transparent;"
                + "-fx-border-width: 0 0 1px 0;");

        if (expandable) {
            toggleIcon = new FontIcon(MaterialDesignC.CHEVRON_DOWN);
            toggleIcon.getStyleClass().add("custom-icon-15");
            toggleLabel = new Label("Show more");
            toggleLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-accent-fg;");
            Button toggleBtn = new Button(null, toggleIcon);
            toggleBtn.setGraphic(new HBox(4, toggleLabel, toggleIcon));
            toggleBtn.getStyleClass().addAll(Styles.FLAT, Styles.SMALL);
            toggleBtn.setFocusTraversable(false);
            toggleBtn.setOnAction(e -> toggle());
            header.getChildren().add(toggleBtn);
        }

        return header;
    }

    private void toggle() {
        expanded = !expanded;
        if (toggleIcon != null)
            toggleIcon.setIconCode(expanded ? MaterialDesignC.CHEVRON_UP : MaterialDesignC.CHEVRON_DOWN);
        if (toggleLabel != null)
            toggleLabel.setText(expanded ? "Show less" : "Show more");
        applyHeight();
        if (onResize != null) onResize.run();
    }

    private void applyHeight() {
        int visible = expanded
                ? Math.min(totalLines, EXPANDED_MAX_LINES)
                : Math.min(totalLines, COLLAPSED_LINES);
        double h = visible * LINE_PX + 12 + (hasLongLine ? 12 : 0);
        codeArea.setPrefHeight(h);
        codeArea.setMinHeight(h);
        codeArea.setMaxHeight(h);
    }

    public String getSelectedText() {
        return codeArea.getSelectedText();
    }

    private static boolean isDescendantOf(Node node, Node ancestor) {
        while (node != null) {
            if (node == ancestor) return true;
            node = node.getParent();
        }
        return false;
    }

    private void copyCode(Button copyBtn) {
        ClipboardContent content = new ClipboardContent();
        content.putString(code);
        Clipboard.getSystemClipboard().setContent(content);

        FontIcon original = (FontIcon) copyBtn.getGraphic();
        copyBtn.setGraphic(new FontIcon(MaterialDesignC.CHECK));
        PauseTransition pt = new PauseTransition(Duration.millis(1100));
        pt.setOnFinished(e -> copyBtn.setGraphic(original));
        pt.play();
    }
}
