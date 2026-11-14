package komm.ui.modals;

import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import komm.App;
import komm.ui.utils.IconColorUtil;
import komm.websocket.messages.payloads.PollCreatePayload;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Modal for creating a channel poll: question, 2–10 answers, single/multiple choice, anonymous
 * switch and a duration. {@code onCreate} receives the question and the poll spec; the caller
 * sends it. Limits mirror the installation's {@code PollService}.
 *
 * <p>Answers live in one framed list whose last row is "Add answer". The list has a fixed
 * height (four rows: three answers + the add row fill it exactly when the modal opens) and
 * scrolls beyond that, so the modal never changes size.
 */
public class PollComposerModal extends VBox {

    public static final int MAX_QUESTION_LENGTH = 300;
    public static final int MAX_OPTION_LENGTH = 100;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 10;
    private static final int INITIAL_OPTIONS = 3;
    private static final double WIDTH = 540;
    private static final double ROW_HEIGHT = 40;
    private static final int VISIBLE_ROWS = 4;

    private static final String ROW_STYLE =
            "-fx-border-color: transparent transparent -color-border-subtle transparent; -fx-border-width: 0 0 1 0;";

    private enum PollDuration {
        HOUR_1("1 hour", 60),
        HOURS_4("4 hours", 4 * 60),
        DAY_1("1 day", 24 * 60),
        DAYS_3("3 days", 3 * 24 * 60),
        WEEK_1("1 week", 7 * 24 * 60),
        NONE("Never", 0);

        final String label;
        final int minutes;

        PollDuration(String label, int minutes) {
            this.label = label;
            this.minutes = minutes;
        }
    }

    /** One answer row: badge, borderless field, Enter hint, remove button. */
    private record AnswerRow(HBox root, Label badge, TextField field, Label hint, Button remove) {}

    private final BiConsumer<String, PollCreatePayload> onCreate;

    private final TextField questionField = new TextField();
    private final Label questionCounter = new Label();
    private final Label answersCounter = new Label();
    private final VBox optionsBox = new VBox();
    private final ScrollPane optionsScroll = new ScrollPane();
    private final List<AnswerRow> rows = new ArrayList<>();
    private final Button addOptionButton = new Button("Add answer");
    private final ToggleSwitch anonymousSwitch = new ToggleSwitch();
    private final List<Button> durationChips = new ArrayList<>();

    private VBox singleCard;
    private VBox multipleCard;
    private boolean allowMultiple = false;
    private PollDuration duration = PollDuration.DAY_1;
    private Button createButton;

    public PollComposerModal(BiConsumer<String, PollCreatePayload> onCreate) {
        this.onCreate = onCreate;

        getStyleClass().add("custom-modal");
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);
        setMaxHeight(Region.USE_PREF_SIZE);

        getChildren().addAll(buildHeader(), buildBody(), buildFooter());

        for (int i = 0; i < INITIAL_OPTIONS; i++) addOptionField(false);
        validate();
        Platform.runLater(questionField::requestFocus);
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        FontIcon icon = IconColorUtil.colored(MaterialDesignP.POLL, "-color-accent-emphasis", 20);
        StackPane iconWrap = new StackPane(icon);
        iconWrap.setMinSize(40, 40);
        iconWrap.setMaxSize(40, 40);
        iconWrap.setStyle("-fx-background-color: -color-accent-subtle; -fx-background-radius: 4;");

        Label title = new Label("Create poll");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label subtitle = new Label("Results update live in the channel");
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
        header.setStyle("-fx-border-color: transparent transparent -color-border-muted transparent;"
                + " -fx-border-width: 0 0 1 0;");
        return header;
    }

    // ── Body ──────────────────────────────────────────────────────────────────

    private VBox buildBody() {
        VBox body = new VBox(18, buildQuestionSection(), buildAnswersSection(),
                buildVotingSection(), buildDurationSection());
        body.setPadding(new Insets(18, 20, 20, 20));
        return body;
    }

    private VBox buildQuestionSection() {
        questionField.setPromptText("Ask a question");
        questionField.setTextFormatter(maxLength(MAX_QUESTION_LENGTH));
        questionField.textProperty().addListener((o, a, b) -> validate());
        questionField.setOnAction(e -> {
            if (!rows.isEmpty()) rows.get(0).field().requestFocus();
        });
        questionField.setMinHeight(38);
        questionCounter.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        return new VBox(7, sectionHeader("QUESTION", questionCounter), questionField);
    }

    private VBox buildAnswersSection() {
        answersCounter.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        addOptionButton.setGraphic(IconColorUtil.colored(MaterialDesignP.PLUS, "-color-accent-fg", 18));
        addOptionButton.setGraphicTextGap(10);
        addOptionButton.setFocusTraversable(false);
        addOptionButton.setMaxWidth(Double.MAX_VALUE);
        addOptionButton.setAlignment(Pos.CENTER_LEFT);
        addOptionButton.setMinHeight(ROW_HEIGHT);
        addOptionButton.setPrefHeight(ROW_HEIGHT);
        addOptionButton.setMaxHeight(ROW_HEIGHT);
        // Plain button skin + no chrome: it reads as the list's last row, not a separate button.
        addOptionButton.setStyle("-fx-skin: \"javafx.scene.control.skin.ButtonSkin\";"
                + " -fx-background-color: transparent; -fx-border-color: transparent;"
                + " -fx-background-radius: 0; -fx-border-width: 0; -fx-padding: 0 12 0 12;"
                + " -fx-text-fill: -color-accent-fg; -fx-font-size: 13px; -fx-font-weight: bold;");
        addOptionButton.setOnAction(e -> addOptionField(true).requestFocus());
        optionsBox.getChildren().add(addOptionButton);

        optionsScroll.setContent(optionsBox);
        optionsScroll.setFitToWidth(true);
        optionsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        optionsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        double viewport = ROW_HEIGHT * VISIBLE_ROWS;
        optionsScroll.setMinHeight(viewport);
        optionsScroll.setPrefHeight(viewport);
        optionsScroll.setMaxHeight(viewport);
        optionsScroll.getStyleClass().add("edge-to-edge");
        optionsScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane frame = new StackPane(optionsScroll);
        frame.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 3;"
                + " -fx-border-color: -color-border-default; -fx-border-radius: 3; -fx-border-width: 1;");
        frame.setPadding(new Insets(1));

        return new VBox(7, sectionHeader("ANSWERS", answersCounter), frame);
    }

    private VBox buildVotingSection() {
        singleCard = choiceCard("Single choice", "One answer per person", false);
        multipleCard = choiceCard("Multiple choice", "Pick as many as you like", true);

        GridPane choices = new GridPane();
        choices.setHgap(8);
        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        choices.getColumnConstraints().addAll(half, half);
        choices.add(singleCard, 0, 0);
        choices.add(multipleCard, 1, 0);
        refreshChoiceCards();

        FontIcon eyeOff = IconColorUtil.colored(MaterialDesignE.EYE_OFF_OUTLINE, "-color-fg-muted", 18);
        Label anonTitle = new Label("Anonymous");
        anonTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        Label anonDesc = new Label("Only totals are shown, not who voted");
        anonDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        VBox anonText = new VBox(1, anonTitle, anonDesc);
        HBox.setHgrow(anonText, Priority.ALWAYS);
        anonymousSwitch.setFocusTraversable(false);

        HBox anonRow = new HBox(12, eyeOff, anonText, anonymousSwitch);
        anonRow.setAlignment(Pos.CENTER_LEFT);
        anonRow.setPadding(new Insets(10, 12, 10, 12));
        anonRow.setCursor(Cursor.HAND);
        anonRow.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 3;"
                + " -fx-border-color: -color-border-muted; -fx-border-radius: 3; -fx-border-width: 1;");
        anonRow.setOnMouseClicked(e -> {
            // The switch handles its own clicks; the rest of the row toggles it too.
            if (!isInside(e.getPickResult().getIntersectedNode(), anonymousSwitch)) {
                anonymousSwitch.setSelected(!anonymousSwitch.isSelected());
            }
        });

        return new VBox(7, sectionLabel("VOTING"), choices, anonRow);
    }

    private VBox choiceCard(String title, String description, boolean multiple) {
        Label t = new Label(title);
        t.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        Label d = new Label(description);
        d.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        VBox card = new VBox(2, t, d);
        card.setPadding(new Insets(10, 12, 10, 12));
        card.setMaxWidth(Double.MAX_VALUE);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> {
            allowMultiple = multiple;
            refreshChoiceCards();
        });
        return card;
    }

    private void refreshChoiceCards() {
        singleCard.setStyle(cardStyle(!allowMultiple));
        multipleCard.setStyle(cardStyle(allowMultiple));
    }

    private static String cardStyle(boolean selected) {
        return (selected
                ? "-fx-background-color: -color-accent-subtle; -fx-border-color: -color-accent-emphasis;"
                : "-fx-background-color: -color-bg-subtle; -fx-border-color: -color-border-default;")
                + " -fx-background-radius: 3; -fx-border-radius: 3; -fx-border-width: 1.5;";
    }

    private VBox buildDurationSection() {
        FlowPane chips = new FlowPane(6, 6);
        for (PollDuration d : PollDuration.values()) {
            Button chip = new Button(d.label);
            chip.setUserData(d);
            chip.setFocusTraversable(false);
            chip.setOnAction(e -> {
                duration = d;
                refreshDurationChips();
            });
            durationChips.add(chip);
            chips.getChildren().add(chip);
        }
        refreshDurationChips();
        return new VBox(7, sectionLabel("ENDS AFTER"), chips);
    }

    private void refreshDurationChips() {
        for (Button chip : durationChips) {
            boolean on = chip.getUserData() == duration;
            chip.setStyle("-fx-skin: \"javafx.scene.control.skin.ButtonSkin\"; -fx-padding: 6 12 6 12;"
                    + " -fx-font-size: 12px; -fx-background-radius: 3; -fx-border-radius: 3; -fx-border-width: 1;"
                    + (on
                    ? " -fx-background-color: -color-accent-subtle; -fx-border-color: -color-accent-emphasis;"
                      + " -fx-text-fill: -color-fg-default; -fx-font-weight: bold;"
                    : " -fx-background-color: transparent; -fx-border-color: -color-border-default;"
                      + " -fx-text-fill: -color-fg-muted;"));
        }
    }

    // ── Answers ───────────────────────────────────────────────────────────────

    private TextField addOptionField(boolean scrollToEnd) {
        Label badge = new Label();
        badge.setAlignment(Pos.CENTER);
        badge.setMinSize(20, 20);
        badge.setMaxSize(20, 20);
        badge.setStyle("-fx-background-color: -color-accent-subtle; -fx-background-radius: 3;"
                + " -fx-text-fill: -color-accent-fg; -fx-font-size: 11px; -fx-font-weight: bold;");

        TextField field = new TextField();
        field.setPromptText("Type an answer");
        field.setTextFormatter(maxLength(MAX_OPTION_LENGTH));
        field.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0;");
        field.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(field, Priority.ALWAYS);

        Label hint = new Label("Enter ↵ adds another");
        hint.setStyle("-fx-font-size: 10.5px; -fx-text-fill: -color-fg-subtle;");
        hint.setVisible(false);
        hint.setManaged(false);

        Button remove = new Button(null, IconColorUtil.colored(MaterialDesignC.CLOSE, "-color-fg-subtle", 14));
        remove.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_ICON, Styles.SMALL);
        remove.setFocusTraversable(false);

        HBox root = new HBox(10, badge, field, hint, remove);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(0, 6, 0, 12));
        root.setMinHeight(ROW_HEIGHT);
        root.setPrefHeight(ROW_HEIGHT);
        root.setMaxHeight(ROW_HEIGHT);
        root.setStyle(ROW_STYLE);
        root.setCursor(Cursor.TEXT);
        root.setOnMouseClicked(e -> field.requestFocus());

        AnswerRow row = new AnswerRow(root, badge, field, hint, remove);

        field.textProperty().addListener((o, a, b) -> {
            refreshHint(row);
            validate();
        });
        field.focusedProperty().addListener((o, was, focused) -> {
            refreshHint(row);
            if (focused) Platform.runLater(() -> ensureVisible(root));
        });

        remove.setOnAction(e -> {
            if (rows.size() <= MIN_OPTIONS) return;
            rows.remove(row);
            optionsBox.getChildren().remove(root);
            renumber();
            validate();
        });

        // Enter: next answer, or a new one from the last (non-empty) field, or submit.
        field.setOnKeyPressed(e -> {
            if (e.getCode() != KeyCode.ENTER) return;
            int i = rows.indexOf(row);
            if (i < rows.size() - 1) {
                rows.get(i + 1).field().requestFocus();
            } else if (rows.size() < MAX_OPTIONS && !field.getText().isBlank()) {
                addOptionField(true).requestFocus();
            } else if (!createButton.isDisabled()) {
                submit();
            }
            e.consume();
        });

        rows.add(row);
        optionsBox.getChildren().add(optionsBox.getChildren().size() - 1, root); // above "Add answer"
        renumber();
        validate();
        if (scrollToEnd) {
            Platform.runLater(() -> {
                optionsScroll.layout();
                optionsScroll.setVvalue(optionsScroll.getVmax());
            });
        }
        return field;
    }

    /** "Enter adds another" only on the focused, last, empty-or-typing row while there's room. */
    private void refreshHint(AnswerRow row) {
        boolean show = row.field().isFocused()
                && rows.indexOf(row) == rows.size() - 1
                && rows.size() < MAX_OPTIONS
                && !row.field().getText().isBlank();
        row.hint().setVisible(show);
        row.hint().setManaged(show);
    }

    /** Scrolls the answers list just enough to show {@code node}. */
    private void ensureVisible(Region node) {
        optionsScroll.layout();
        double contentH = optionsBox.getHeight();
        double viewportH = optionsScroll.getViewportBounds().getHeight();
        if (contentH <= viewportH) return;
        double scrollable = contentH - viewportH;
        double top = optionsScroll.getVvalue() * scrollable;
        double nodeTop = node.getBoundsInParent().getMinY();
        double nodeBottom = node.getBoundsInParent().getMaxY();
        if (nodeTop < top) {
            optionsScroll.setVvalue(nodeTop / scrollable);
        } else if (nodeBottom > top + viewportH) {
            optionsScroll.setVvalue((nodeBottom - viewportH) / scrollable);
        }
    }

    private void renumber() {
        boolean canRemove = rows.size() > MIN_OPTIONS;
        for (int i = 0; i < rows.size(); i++) {
            AnswerRow r = rows.get(i);
            r.badge().setText(String.valueOf(i + 1));
            r.remove().setDisable(!canRemove);
            refreshHint(r);
        }
        boolean canAdd = rows.size() < MAX_OPTIONS;
        addOptionButton.setVisible(canAdd);
        addOptionButton.setManaged(canAdd);
        answersCounter.setText(rows.size() + " of " + MAX_OPTIONS);
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private HBox buildFooter() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().add(Styles.SMALL);
        cancelButton.setFocusTraversable(false);
        cancelButton.setOnAction(e -> App.closeModal());

        createButton = new Button("Create poll");
        createButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        createButton.setFocusTraversable(false);
        createButton.setOnAction(e -> submit());

        HBox footer = new HBox(10, spacer, cancelButton, createButton);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(14, 20, 16, 20));
        footer.setStyle("-fx-border-color: -color-border-muted transparent transparent transparent;"
                + " -fx-border-width: 1 0 0 0;");
        return footer;
    }

    // ── Behaviour ─────────────────────────────────────────────────────────────

    /** Non-blank answers, trimmed, duplicates (case-insensitive) dropped — same rule as the server. */
    private List<String> distinctOptions() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (AnswerRow r : rows) {
            String t = r.field().getText() == null ? "" : r.field().getText().trim();
            if (!t.isEmpty() && seen.add(t.toLowerCase(Locale.ROOT))) out.add(t);
        }
        return out;
    }

    private void validate() {
        int len = questionField.getText() == null ? 0 : questionField.getText().length();
        questionCounter.setText(len + " / " + MAX_QUESTION_LENGTH);
        if (createButton == null) return;
        boolean ok = !questionField.getText().isBlank() && distinctOptions().size() >= MIN_OPTIONS;
        createButton.setDisable(!ok);
    }

    private void submit() {
        String question = questionField.getText() == null ? "" : questionField.getText().trim();
        List<String> options = distinctOptions();
        if (question.isEmpty() || options.size() < MIN_OPTIONS) return;
        PollCreatePayload spec = PollCreatePayload.builder()
                .options(options)
                .allowMultiple(allowMultiple)
                .anonymous(anonymousSwitch.isSelected())
                .durationMinutes(duration.minutes > 0 ? duration.minutes : null)
                .build();
        if (onCreate != null) onCreate.accept(question, spec);
        App.closeModal();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isInside(javafx.scene.Node node, javafx.scene.Node ancestor) {
        for (javafx.scene.Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) return true;
        }
        return false;
    }

    private static TextFormatter<String> maxLength(int max) {
        return new TextFormatter<>(change ->
                change.getControlNewText().length() <= max ? change : null);
    }

    private static HBox sectionHeader(String text, Label right) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(sectionLabel(text), spacer, right);
        header.setAlignment(Pos.BASELINE_LEFT);
        return header;
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 10.5px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");
        return l;
    }
}
