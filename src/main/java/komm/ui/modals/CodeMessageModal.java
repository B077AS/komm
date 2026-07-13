package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import komm.App;
import komm.ui.code.CodeAreaSupport;
import komm.ui.code.CodeHighlighter;
import komm.ui.code.CodeLanguage;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.util.function.BiConsumer;

/**
 * Modal editor for composing or editing a code message. Hosts a RichTextFX
 * {@link CodeArea} with live syntax highlighting, a language picker, and a
 * character counter. Reused for both new messages and edits — pass the existing
 * content/language and {@code editMode = true} for the latter.
 *
 * <p>On Send the supplied {@code onSend} callback receives the final code and
 * selected language; the caller is responsible for the actual WS send.
 */
public class CodeMessageModal extends VBox {

    public static final int MAX_LENGTH = 50000;
    private static final double WIDTH = 860;
    private static final double EDITOR_HEIGHT = 420;

    private final boolean editMode;
    private final BiConsumer<String, CodeLanguage> onSend;

    private final CodeArea codeArea = new CodeArea();
    private boolean emojiGuard = false;
    private ComboBox<CodeLanguage> languageBox;
    private Label counter;
    private Button sendButton;

    private CodeLanguage language;

    public CodeMessageModal(String initialCode, CodeLanguage initialLanguage,
                            boolean editMode, BiConsumer<String, CodeLanguage> onSend) {
        this.editMode = editMode;
        this.onSend = onSend;
        this.language = initialLanguage == null ? CodeLanguage.PLAIN_TEXT : initialLanguage;

        getStyleClass().add("custom-modal");
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);
        setMaxHeight(Region.USE_PREF_SIZE);

        getChildren().addAll(buildHeader(), buildBody(), buildFooter());

        if (initialCode != null && !initialCode.isEmpty()) {
            codeArea.replaceText(initialCode);
        }
        reHighlight();
        CodeAreaSupport.applyRowStripes(codeArea);
        updateCounter();
        Platform.runLater(() -> {
            codeArea.requestFocus();
            codeArea.moveTo(codeArea.getLength());
        });
    }

    // ── Header ──────────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        FontIcon icon = new FontIcon(MaterialDesignC.CODE_BRACES);
        icon.getStyleClass().add("custom-icon-20-accent");
        StackPane iconWrap = new StackPane(icon);
        iconWrap.setMinSize(40, 40);
        iconWrap.setMaxSize(40, 40);
        iconWrap.setStyle("-fx-background-color: -color-accent-subtle; -fx-background-radius: 10;");

        Label title = new Label(editMode ? "Edit code" : "Code message");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label subtitle = new Label("Up to " + MAX_LENGTH + " characters · syntax highlighted");
        subtitle.setStyle("-fx-font-size: 11.5px; -fx-text-fill: -color-fg-muted;");
        VBox titleBox = new VBox(1, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeButton = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeButton.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeButton.setFocusTraversable(false);
        closeButton.setOnAction(e -> App.closeModal());

        HBox header = new HBox(14, iconWrap, titleBox, spacer, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 16, 16, 20));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent;"
                + " -fx-border-width: 0 0 1 0;");
        return header;
    }

    // ── Body ────────────────────────────────────────────────────────────────────

    private VBox buildBody() {
        languageBox = new ComboBox<>();
        languageBox.getItems().addAll(CodeLanguage.values());
        languageBox.setValue(language);
        languageBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(CodeLanguage l) {
                return l == null ? "" : l.getDisplayName();
            }

            @Override
            public CodeLanguage fromString(String s) {
                return CodeLanguage.fromString(s);
            }
        });
        languageBox.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                language = val;
                reHighlight();
            }
        });

        Label langLabel = sectionLabel("LANGUAGE");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        counter = new Label();
        counter.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        HBox topRow = new HBox(10, langLabel, languageBox, spacer, counter);
        topRow.setAlignment(Pos.CENTER_LEFT);

        codeArea.getStyleClass().add("code-area");
        // Inline background reliably overrides RichTextFX's default white surface;
        // the surrounding editorWrap supplies the visible inset colour.
        codeArea.setStyle("-fx-background-color: transparent;");
        codeArea.setParagraphGraphicFactory(CodeAreaSupport.lineNumberFactory(codeArea));
        codeArea.setWrapText(false);
        // updateCounter is cheap — fire immediately on every change
        codeArea.multiPlainChanges().subscribe(c -> updateCounter());
        // highlighting + stripes are O(chars) / O(lines) — debounce so they only
        // run 250 ms after the user pauses, never on every individual keystroke
        codeArea.multiPlainChanges()
                .successionEnds(java.time.Duration.ofMillis(250))
                .subscribe(c -> {
                    reHighlight();
                    CodeAreaSupport.applyRowStripes(codeArea);
                });
        codeArea.textProperty().addListener((obs, ov, nv) -> {
            if (emojiGuard) return;
            if (!stripEmoji(nv).equals(nv)) {
                // Defer the replacement to the next FX pulse so we don't call
                // replaceText() re-entrantly while a paste is still executing
                // (which corrupts RichTextFX's internal selection state and causes
                // IndexOutOfBoundsException on the next paste).
                emojiGuard = true;
                Platform.runLater(() -> {
                    String current = codeArea.getText();
                    String clean = stripEmoji(current);
                    if (!clean.equals(current)) {
                        int pos = Math.min(codeArea.getCaretPosition(), clean.length());
                        codeArea.replaceText(clean);
                        codeArea.moveTo(Math.max(0, pos));
                    }
                    emojiGuard = false;
                });
            }
        });

        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(codeArea);
        scroll.setPrefHeight(EDITOR_HEIGHT);
        StackPane editorWrap = new StackPane(scroll);
        editorWrap.setStyle("-fx-background-color: -color-bg-inset;"
                + "-fx-background-radius: 8px;"
                + "-fx-border-color: -color-border-default;"
                + "-fx-border-radius: 8px;"
                + "-fx-border-width: 1px;");
        editorWrap.setPadding(new Insets(4));
        VBox.setVgrow(editorWrap, Priority.ALWAYS);

        VBox body = new VBox(10, topRow, editorWrap);
        body.setPadding(new Insets(16, 20, 16, 20));
        return body;
    }

    // ── Footer ────────────────────────────────────────────────────────────────────

    private HBox buildFooter() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().add(Styles.SMALL);
        cancelButton.setFocusTraversable(false);
        cancelButton.setOnAction(e -> App.closeModal());

        sendButton = new Button(editMode ? "Save" : "Send");
        sendButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        sendButton.setFocusTraversable(false);
        sendButton.setOnAction(e -> submit());

        HBox footer = new HBox(10, spacer, cancelButton, sendButton);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(14, 20, 16, 20));
        footer.setStyle("-fx-border-color: -color-border-default transparent transparent transparent;"
                + " -fx-border-width: 1 0 0 0;");
        return footer;
    }

    // ── Behaviour ─────────────────────────────────────────────────────────────────

    private void reHighlight() {
        String snapshot = codeArea.getText();
        CodeLanguage lang = language;
        Thread.ofVirtual().start(() -> {
            var spans = CodeHighlighter.computeHighlighting(snapshot, lang);
            Platform.runLater(() -> {
                // drop result if user has already typed more since we started
                if (snapshot.equals(codeArea.getText())) {
                    codeArea.setStyleSpans(0, spans);
                }
            });
        });
    }

    private void updateCounter() {
        int len = codeArea.getLength();
        boolean over = len > MAX_LENGTH;
        boolean empty = codeArea.getText().isBlank();
        counter.setText(len + "/" + MAX_LENGTH);
        counter.setStyle("-fx-font-size: 11px; -fx-text-fill: "
                + (over ? "-color-danger-fg" : "-color-fg-subtle") + ";");
        if (sendButton != null) sendButton.setDisable(over || empty);
    }

    private void submit() {
        String code = codeArea.getText();
        if (code == null) return;
        code = stripTrailing(code);
        if (code.isBlank() || code.length() > MAX_LENGTH) return;
        if (onSend != null) onSend.accept(code, language);
        App.closeModal();
    }

    /** Removes trailing blank lines/whitespace while preserving leading indentation. */
    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private static boolean isEmojiCodePoint(int cp) {
        // Supplementary plane characters (U+10000+) are almost exclusively emoji/symbols;
        // OTHER_SYMBOL covers BMP emoji like ©, ™, ☀, ❤, ✅ etc.
        return cp > 0xFFFF || Character.getType(cp) == Character.OTHER_SYMBOL;
    }

    private static String stripEmoji(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!isEmojiCodePoint(cp)) sb.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private Label sectionLabel(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        return l;
    }
}
