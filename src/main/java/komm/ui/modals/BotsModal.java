package komm.ui.modals;

import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import komm.App;
import komm.api.HttpStatusException;
import komm.api.json.GsonProvider;
import komm.model.dto.request.BotCreateRequest;
import komm.model.dto.request.BotUpdateRequest;
import komm.model.dto.summary.BotSummary;
import komm.model.dto.summary.ChannelSummary;
import komm.model.dto.summary.ServerSummary;
import komm.ui.avatar.AvatarColor;
import komm.ui.cards.ChannelCard;
import komm.ui.customnodes.CustomNotification;
import komm.ui.utils.IconColorUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * "Manage Bots" modal, reachable from the server context menu (gated behind
 * {@code Permission.MANAGE_BOTS}).
 *
 * <p>Master/detail in one window: the server's bots live in a rail on the left, the selected bot's
 * editor on the right. The right side is one of four panes ({@link Mode}): nothing yet, the bot-type
 * picker (today just {@code ANIME_WAIFU}, structured as a list so a second type is additive), or the
 * editor — shared between creating a bot ({@link Mode#NEW}) and editing one ({@link Mode#EDIT}),
 * distinguished by {@link #editingBot}. Only the channel list scrolls; header and footers stay put.
 */
public class BotsModal extends VBox {

    /** One entry per addable bot type — the picker pane just iterates this. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class BotTypeOption {
        private BotSummary.BotType type;
        private String label;
        private String description;
        private org.kordamp.ikonli.Ikon icon;
    }

    private static final List<BotTypeOption> AVAILABLE_TYPES = List.of(
            BotTypeOption.builder()
                    .type(BotSummary.BotType.ANIME_WAIFU)
                    .label("Anime Waifu Spawner")
                    .description("Posts a random anime image every so often as people chat. "
                            + "Capped to at most one image every 20 seconds per channel, no matter the chance.")
                    .icon(MaterialDesignF.FACE_WOMAN_SHIMMER_OUTLINE)
                    .build()
    );

    private static final double DEFAULT_SPAWN_CHANCE = 2;
    private static final String SUBTLE_LABEL_STYLE = "-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;";
    private static final String SECTION_LABEL_STYLE =
            "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;";
    /** Shared by every footer and the rail's "Add bot" strip so their top borders line up. */
    private static final double FOOTER_HEIGHT = 56;
    private static final String FOOTER_STYLE =
            "-fx-border-color: -color-border-default transparent transparent transparent; -fx-border-width: 1 0 0 0;";
    private static final String HEADER_STYLE =
            "-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;";

    /** What the right-hand side is currently showing. */
    private enum Mode {LOADING, EMPTY, PICK, NEW, EDIT}

    private final ServerSummary server;

    private Mode mode = Mode.LOADING;
    private BotTypeOption selectedType = AVAILABLE_TYPES.get(0);
    /** Non-null while the editor is editing an existing bot rather than creating one. */
    private BotSummary editingBot;
    private List<BotSummary> loadedBots = List.of();
    /** Bot to open once the next reload finishes (the one just created or saved). */
    private UUID pendingSelect;
    /** Signature of the editor's contents as loaded; the editor is "dirty" when it differs. */
    private String baselineSig = "";

    // rail
    private VBox railList;
    private Label draftRailName;

    // right-hand panes
    private StackPane mainStack;
    private VBox emptyPane;
    private VBox pickPane;
    private VBox editPane;
    private Label emptyTitle;
    private Label emptyText;
    private Button emptyAddBtn;

    // editor
    private Label editTitle;
    private HBox enabledBox;
    private ToggleSwitch enabledSwitch;
    private boolean suppressEnabledListener;
    private TextField nameField;
    private Slider spawnChanceSlider;
    private TextField spawnChanceField;
    private Label spawnFrequencyLabel;
    private ToggleSwitch sfwSwitch;
    private TextField channelSearch;
    private VBox channelListBox;
    private final List<CheckBox> channelChecks = new ArrayList<>();
    private Button deleteBtn;
    private Button revertBtn;
    private Button submitBtn;

    private final Service<List<BotSummary>> loadService = new Service<>() {
        @Override
        protected Task<List<BotSummary>> createTask() {
            return new Task<>() {
                @Override
                protected List<BotSummary> call() throws Exception {
                    return App.getServices().installation().getBotService().getBots();
                }
            };
        }
    };

    private BotCreateRequest pendingSubmit;
    private final Service<BotSummary> submitService = new Service<>() {
        @Override
        protected Task<BotSummary> createTask() {
            return new Task<>() {
                @Override
                protected BotSummary call() throws Exception {
                    var botService = App.getServices().installation().getBotService();
                    if (editingBot == null) {
                        return botService.createBot(pendingSubmit);
                    }
                    botService.updateBot(editingBot.getBotId(), BotUpdateRequest.builder()
                            .name(pendingSubmit.getName())
                            .config(pendingSubmit.getConfig())
                            .build());
                    return botService.assignChannels(editingBot.getBotId(), pendingSubmit.getChannelIds());
                }
            };
        }
    };

    public BotsModal(ServerSummary server) {
        this.server = server;
        buildUI();
        wireServices();
        loadService.restart();
    }

    private void buildUI() {
        getStyleClass().add("custom-modal");
        setMinSize(996, 600);
        setPrefSize(996, 600);
        setMaxSize(996, 600);
        setSpacing(0);

        emptyPane = buildEmptyPane();
        pickPane = buildPickPane();
        editPane = buildEditPane();
        mainStack = new StackPane(emptyPane, pickPane, editPane);
        HBox.setHgrow(mainStack, Priority.ALWAYS);

        Separator vDivider = new Separator(Orientation.VERTICAL);
        vDivider.setPadding(new Insets(0));

        HBox body = new HBox(buildRail(), vDivider, mainStack);
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(buildHeader(), body);
        showLoading();
    }

    private void wireServices() {
        loadService.setOnSucceeded(e -> renderBots(loadService.getValue()));
        loadService.setOnFailed(e -> renderBots(List.of()));

        submitService.setOnSucceeded(e -> {
            BotSummary saved = submitService.getValue();
            String verb = editingBot == null ? "added" : "updated";
            new CustomNotification(editingBot == null ? "Bot Added" : "Bot Updated",
                    "\"" + saved.getName() + "\" was " + verb + ".",
                    new FontIcon(MaterialDesignR.ROBOT_OUTLINE)).showNotification();
            pendingSelect = saved.getBotId();
            loadService.restart();
        });
        submitService.setOnFailed(e -> new CustomNotification(
                "Error",
                HttpStatusException.extractMessage(submitService.getException()),
                new FontIcon(MaterialDesignC.CLOSE)).showNotification());
    }

    // ── Header ───────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 16, 10, 16));   // 16 on the right, like the footers, so the hover circle lines up with Save
        header.setStyle(HEADER_STYLE);

        Label title = new Label("Bots");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label subtitle = new Label("Automated helpers on this server");
        subtitle.setStyle(SUBTLE_LABEL_STYLE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.setFocusTraversable(false);
        closeBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeBtn.setOnAction(e -> App.closeModal());

        VBox titleBox = new VBox(2, title, subtitle);

        header.getChildren().addAll(titleBox, spacer, closeBtn);
        return header;
    }

    // ── Rail (left) ──────────────────────────────────────────────────────────

    private VBox buildRail() {
        railList = new VBox(2);
        railList.setPadding(new Insets(8));

        ScrollPane scroll = new ScrollPane(railList);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button addBotBtn = new Button("Add bot", new FontIcon(MaterialDesignP.PLUS));
        addBotBtn.setFocusTraversable(false);
        addBotBtn.getStyleClass().add(Styles.SMALL);
        addBotBtn.setMaxWidth(Double.MAX_VALUE);
        addBotBtn.setOnAction(e -> {
            if (mode == Mode.PICK) return;
            guardUnsaved(this::showPick);
        });

        VBox addBox = new VBox(addBotBtn);
        addBox.setPadding(new Insets(0, 8, 0, 8));
        addBox.setAlignment(Pos.CENTER);
        addBox.setStyle(FOOTER_STYLE);
        lockHeight(addBox);

        VBox rail = new VBox(scroll, addBox);
        rail.setPrefWidth(270);
        rail.setMinWidth(270);
        rail.setMaxWidth(270);
        rail.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 0 0 0 4px;");
        return rail;
    }

    private void renderRail() {
        railList.getChildren().clear();
        draftRailName = null;

        if (mode == Mode.LOADING) {
            railList.getChildren().add(railHint("Loading bots..."));
            return;
        }
        for (BotSummary bot : loadedBots) {
            railList.getChildren().add(buildRailItem(bot));
        }
        if (mode == Mode.PICK || mode == Mode.NEW) {
            railList.getChildren().add(buildDraftRailItem());
        }
        if (loadedBots.isEmpty() && mode == Mode.EMPTY) {
            railList.getChildren().add(railHint("No bots yet."));
        }
    }

    private Label railHint(String text) {
        Label l = new Label(text);
        l.setStyle(SUBTLE_LABEL_STYLE);
        l.setPadding(new Insets(8, 10, 8, 10));
        return l;
    }

    private HBox buildRailItem(BotSummary bot) {
        boolean active = mode == Mode.EDIT && editingBot != null && editingBot.getBotId().equals(bot.getBotId());

        int count = bot.getChannelIds() != null ? bot.getChannelIds().size() : 0;
        Label name = new Label(bot.getName());
        name.getStyleClass().add("nav-label");
        Label sub = new Label(count + (count == 1 ? " channel" : " channels"));
        sub.setStyle(SUBTLE_LABEL_STYLE);
        VBox text = new VBox(1, name, sub);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox item = new HBox(10, avatar(bot.getName(), 13), text);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(8, 10, 8, 10));
        item.getStyleClass().add("nav-item");
        if (active) item.getStyleClass().add("nav-active");
        item.setOnMouseClicked(e -> {
            if (active) return;
            guardUnsaved(() -> openBot(bot));
        });
        return item;
    }

    /** The not-yet-saved entry shown while picking a type or filling in a new bot. */
    private HBox buildDraftRailItem() {
        boolean naming = mode == Mode.NEW;

        StackPane plus = new StackPane(IconColorUtil.colored(MaterialDesignP.PLUS, "-color-fg-muted", 14));
        plus.setMinSize(26, 26);
        plus.setMaxSize(26, 26);
        plus.setStyle("-fx-background-color: -color-border-default; -fx-background-radius: 13px;");

        draftRailName = new Label(naming ? draftDisplayName() : "New bot");
        draftRailName.getStyleClass().add("nav-label");
        Label sub = new Label(naming ? "Not saved yet" : "Choose a type");
        sub.setStyle(SUBTLE_LABEL_STYLE);
        VBox text = new VBox(1, draftRailName, sub);

        HBox item = new HBox(10, plus, text);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(8, 10, 8, 10));
        item.getStyleClass().addAll("nav-item", "nav-active");
        return item;
    }

    private String draftDisplayName() {
        String n = nameField.getText() == null ? "" : nameField.getText().trim();
        return n.isEmpty() ? "Unnamed bot" : n;
    }

    private static StackPane avatar(String name, double radius) {
        String safe = name != null && !name.isBlank() ? name : "B";
        Circle bg = new Circle(radius, AvatarColor.forNameJfx(safe));
        Label initial = new Label(String.valueOf(safe.trim().charAt(0)).toUpperCase());
        initial.setStyle("-fx-text-fill: white; -fx-font-size: " + Math.round(radius * 0.85) + "px; -fx-font-weight: bold;");
        StackPane pane = new StackPane(bg, initial);
        pane.setMinSize(radius * 2, radius * 2);
        pane.setMaxSize(radius * 2, radius * 2);
        return pane;
    }

    // ── Empty pane ───────────────────────────────────────────────────────────

    private VBox buildEmptyPane() {
        FontIcon icon = IconColorUtil.colored(MaterialDesignR.ROBOT_OUTLINE, "-color-accent-emphasis", 72);
        icon.setOpacity(0.18);

        emptyTitle = new Label("No bots yet");
        emptyTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        emptyText = new Label("Bots post things in your channels automatically. Add one to get started.");
        emptyText.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-subtle;");
        emptyText.setWrapText(true);
        emptyText.setMaxWidth(300);
        emptyText.setAlignment(Pos.CENTER);
        emptyText.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        emptyAddBtn = new Button("Add your first bot");
        emptyAddBtn.setFocusTraversable(false);
        emptyAddBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        emptyAddBtn.setOnAction(e -> showPick());

        VBox center = new VBox(10, icon, emptyTitle, emptyText, emptyAddBtn);
        center.setAlignment(Pos.CENTER);
        VBox.setVgrow(center, Priority.ALWAYS);

        Button closeBtn = new Button("Close");
        closeBtn.setFocusTraversable(false);
        closeBtn.getStyleClass().add(Styles.SMALL);
        closeBtn.setOnAction(e -> App.closeModal());

        return new VBox(center, footer(closeBtn));
    }

    // ── Type picker pane ─────────────────────────────────────────────────────

    private VBox buildPickPane() {
        Label heading = new Label("Choose a bot type");
        heading.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        HBox head = new HBox(heading);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(12, 16, 12, 16));
        head.setStyle(HEADER_STYLE);

        VBox typeList = new VBox(8);
        typeList.setPadding(new Insets(16));
        for (BotTypeOption option : AVAILABLE_TYPES) {
            typeList.getChildren().add(buildTypeOptionCard(option));
        }
        VBox.setVgrow(typeList, Priority.ALWAYS);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setFocusTraversable(false);
        cancelBtn.getStyleClass().add(Styles.SMALL);
        cancelBtn.setOnAction(e -> openDefault());

        return new VBox(head, typeList, footer(cancelBtn));
    }

    private HBox buildTypeOptionCard(BotTypeOption option) {
        HBox card = new HBox(12);
        card.setPadding(new Insets(12));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setCursor(Cursor.HAND);
        card.setStyle("-fx-background-color: -color-bg-subtle;"
                + " -fx-border-color: -color-border-muted; -fx-border-width: 1.5px;"
                + " -fx-border-radius: 3px; -fx-background-radius: 3px;");
        card.setOnMouseEntered(e -> card.setStyle("-fx-background-color: -color-bg-subtle;"
                + " -fx-border-color: -color-accent-emphasis; -fx-border-width: 1.5px;"
                + " -fx-border-radius: 3px; -fx-background-radius: 3px;"));
        card.setOnMouseExited(e -> card.setStyle("-fx-background-color: -color-bg-subtle;"
                + " -fx-border-color: -color-border-muted; -fx-border-width: 1.5px;"
                + " -fx-border-radius: 3px; -fx-background-radius: 3px;"));

        StackPane iconTile = new StackPane(IconColorUtil.colored(option.getIcon(), "-color-accent-emphasis", 20));
        iconTile.setMinSize(36, 36);
        iconTile.setMaxSize(36, 36);
        iconTile.setStyle("-fx-background-color: -color-accent-subtle; -fx-background-radius: 4px;");

        Label nameLbl = new Label(option.getLabel());
        nameLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        Label descLbl = new Label(option.getDescription());
        descLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        descLbl.setWrapText(true);
        VBox textCol = new VBox(4, nameLbl, descLbl);
        HBox.setHgrow(textCol, Priority.ALWAYS);

        // Chevron lives in the card-level HBox so CENTER_LEFT centres it against the whole card height.
        card.getChildren().addAll(iconTile, textCol, new FontIcon(MaterialDesignA.ARROW_RIGHT));
        card.setOnMouseClicked(e -> startNew(option));
        return card;
    }

    // ── Editor pane (shared by add + edit) ───────────────────────────────────

    private VBox buildEditPane() {
        // Head: avatar, title, type, and (existing bots only) the enabled switch.
        editTitle = new Label();
        editTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);

        Label enabledLbl = new Label("Enabled");
        enabledLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        enabledSwitch = new ToggleSwitch();
        enabledSwitch.setFocusTraversable(false);
        enabledSwitch.selectedProperty().addListener((obs, was, isNow) -> onEnabledToggled(was, isNow));
        // Delete lives next to the enabled switch (existing bots only), behind a confirmation.
        deleteBtn = new Button(null, IconColorUtil.colored(MaterialDesignD.DELETE_OUTLINE, "-color-danger-fg", 18));
        deleteBtn.setFocusTraversable(false);
        deleteBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        deleteBtn.setOnAction(e -> confirmDelete());

        Separator enabledDivider = new Separator(Orientation.VERTICAL);
        enabledDivider.setPadding(new Insets(0));
        enabledBox = new HBox(10, enabledLbl, enabledSwitch, enabledDivider, deleteBtn);
        enabledBox.setAlignment(Pos.CENTER_RIGHT);

        HBox head = new HBox(10, editTitle, headSpacer, enabledBox);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(12, 16, 12, 16));
        head.setStyle(HEADER_STYLE);

        // Body: settings column + channel picker.
        HBox editor = new HBox(16, buildSettingsColumn(), buildChannelColumn());
        editor.setPadding(new Insets(16));
        VBox.setVgrow(editor, Priority.ALWAYS);

        // Footer.
        revertBtn = new Button("Revert");
        revertBtn.setFocusTraversable(false);
        revertBtn.getStyleClass().add(Styles.SMALL);
        revertBtn.setOnAction(e -> {
            if (mode == Mode.NEW) openDefault();
            else openBot(editingBot);
        });

        submitBtn = new Button("Save changes");
        submitBtn.setDefaultButton(true);
        submitBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        submitBtn.setOnAction(e -> onSubmit());

        return new VBox(head, editor, footer(revertBtn, submitBtn));
    }

    private VBox buildSettingsColumn() {
        Label nameLabel = new Label("NAME");
        nameLabel.setStyle(SECTION_LABEL_STYLE);
        nameField = new TextField();
        nameField.textProperty().addListener((obs, was, now) -> {
            nameField.pseudoClassStateChanged(Styles.STATE_DANGER, false);
            if (draftRailName != null) draftRailName.setText(draftDisplayName());
            refreshFooter();
        });

        Label spawnLabel = new Label("SPAWN CHANCE");
        spawnLabel.setStyle(SECTION_LABEL_STYLE);
        spawnChanceSlider = new Slider(0, 100, DEFAULT_SPAWN_CHANCE);
        spawnChanceSlider.setMajorTickUnit(0.5);
        spawnChanceSlider.setMinorTickCount(0);
        spawnChanceSlider.setSnapToTicks(true);
        // Typed value and slider stay in sync both ways; the field is only rewritten while unfocused
        // so typing "1" on the way to "12" isn't reformatted under the cursor.
        spawnChanceField = new TextField();
        spawnChanceField.setAlignment(Pos.CENTER_RIGHT);
        spawnChanceField.setPrefWidth(60);
        spawnChanceField.setMinWidth(60);
        spawnChanceField.setMaxWidth(60);
        spawnChanceField.textProperty().addListener((obs, was, now) -> {
            if (!spawnChanceField.isFocused()) return;
            try {
                double v = Double.parseDouble(now.trim().replace(',', '.'));
                spawnChanceSlider.setValue(Math.round(Math.max(0, Math.min(100, v)) * 10) / 10.0);
            } catch (NumberFormatException ignored) {
                // incomplete input such as "" or "." — leave the slider where it is
            }
        });
        spawnChanceField.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) syncSpawnField();
        });
        spawnChanceField.setOnAction(e -> syncSpawnField());
        Label percent = new Label("%");
        percent.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");

        spawnChanceSlider.valueProperty().addListener((obs, was, now) -> {
            updateSpawnLabels();
            refreshFooter();
        });
        HBox spawnRow = new HBox(6, spawnChanceSlider, spawnChanceField, percent);
        spawnRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spawnChanceSlider, Priority.ALWAYS);

        spawnFrequencyLabel = new Label();
        spawnFrequencyLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        spawnFrequencyLabel.setWrapText(true);
        Label spawnNote = new Label("Never more than once every 20 s per channel, whatever the chance.");
        spawnNote.setStyle(SUBTLE_LABEL_STYLE);
        spawnNote.setWrapText(true);

        Label sfwTitle = new Label("SFW images only");
        sfwTitle.setStyle("-fx-font-size: 13px;");
        Label sfwSub = new Label("Filters out adult content");
        sfwSub.setStyle(SUBTLE_LABEL_STYLE);
        sfwSwitch = new ToggleSwitch();
        sfwSwitch.setFocusTraversable(false);
        sfwSwitch.selectedProperty().addListener((obs, was, now) -> refreshFooter());
        Region sfwSpacer = new Region();
        HBox.setHgrow(sfwSpacer, Priority.ALWAYS);
        HBox sfwRow = new HBox(10, new VBox(1, sfwTitle, sfwSub), sfwSpacer, sfwSwitch);
        sfwRow.setAlignment(Pos.CENTER_LEFT);

        VBox col = new VBox(14,
                new VBox(6, nameLabel, nameField),
                new VBox(6, spawnLabel, spawnRow, spawnFrequencyLabel, spawnNote),
                sfwRow);
        col.setMinWidth(310);
        col.setPrefWidth(310);
        col.setMaxWidth(310);
        return col;
    }

    private VBox buildChannelColumn() {
        Label channelsLabel = new Label("CHANNELS");
        channelsLabel.setStyle(SECTION_LABEL_STYLE);

        channelSearch = new TextField();
        channelSearch.setPromptText("Search channels");
        HBox.setHgrow(channelSearch, Priority.ALWAYS);
        channelSearch.textProperty().addListener((obs, was, now) -> filterChannels());

        HBox tools = new HBox(6, channelSearch);
        tools.setAlignment(Pos.CENTER_LEFT);
        tools.setPadding(new Insets(8));
        tools.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");

        channelListBox = new VBox(1);
        channelListBox.setPadding(new Insets(4));
        ScrollPane scroll = new ScrollPane(channelListBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("edge-to-edge");
        scroll.setMinHeight(0);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox box = new VBox(tools, scroll);
        box.setStyle("-fx-background-color: -color-bg-inset; -fx-background-radius: 3px;"
                + " -fx-border-color: -color-border-default; -fx-border-radius: 3px; -fx-border-width: 1px;");
        VBox.setVgrow(box, Priority.ALWAYS);

        VBox col = new VBox(6, channelsLabel, box);
        HBox.setHgrow(col, Priority.ALWAYS);
        col.setMinHeight(0);
        return col;
    }

    private static HBox footer(Node... buttons) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox foot = new HBox(8);
        foot.getChildren().add(spacer);
        foot.getChildren().addAll(buttons);
        foot.setAlignment(Pos.CENTER_RIGHT);
        foot.setPadding(new Insets(0, 16, 0, 16));
        foot.setStyle(FOOTER_STYLE);
        lockHeight(foot);
        return foot;
    }

    private static void lockHeight(Region r) {
        r.setMinHeight(FOOTER_HEIGHT);
        r.setPrefHeight(FOOTER_HEIGHT);
        r.setMaxHeight(FOOTER_HEIGHT);
    }

    // ── Channel checklist ────────────────────────────────────────────────────

    /** Channels this server has, of the types a bot can actually run in (text chat + voice chat). */
    private void populateChannelChecklist(Set<UUID> preSelected) {
        channelListBox.getChildren().clear();
        channelChecks.clear();
        channelSearch.clear();

        Map<UUID, ChannelCard> boxes = App.getCachedServerPage() != null
                ? App.getCachedServerPage().getChannelSection().getChannelBoxes() : Map.of();

        addChannelGroup("TEXT", MaterialDesignP.POUND, ChannelSummary.ChannelType.TEXT, boxes, preSelected);
        addChannelGroup("VOICE", MaterialDesignM.MICROPHONE, ChannelSummary.ChannelType.VOICE, boxes, preSelected);

        if (channelChecks.isEmpty()) {
            Label none = new Label("No text or voice channels yet.");
            none.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-subtle;");
            none.setPadding(new Insets(8));
            channelListBox.getChildren().setAll(none);
        }
    }

    private void addChannelGroup(String title, org.kordamp.ikonli.Ikon icon, ChannelSummary.ChannelType type,
                                 Map<UUID, ChannelCard> boxes, Set<UUID> preSelected) {
        List<ChannelCard> eligible = boxes.values().stream()
                .filter(c -> c.getChannel().getChannelType() == type)
                .sorted((a, b) -> Integer.compare(a.getChannel().getPosition(), b.getChannel().getPosition()))
                .toList();
        if (eligible.isEmpty()) return;

        Label group = new Label(title);
        group.setStyle(SECTION_LABEL_STYLE);
        group.setPadding(new Insets(8, 8, 3, 8));
        channelListBox.getChildren().add(group);

        for (ChannelCard card : eligible) {
            UUID channelId = card.getChannel().getChannelId();

            // A channel already running a different bot of this same type is off-limits — two
            // bots of the same type in one channel would just double the spawn rate unpredictably
            // (enforced server-side too; this just surfaces it before the user hits Create/Save).
            // Deliberately left unlabeled: plain disabled checkbox, no "used by" text.
            BotSummary conflicting = loadedBots.stream()
                    .filter(b -> editingBot == null || !b.getBotId().equals(editingBot.getBotId()))
                    .filter(b -> b.getBotType() == selectedType.getType())
                    .filter(b -> b.getChannelIds() != null && b.getChannelIds().contains(channelId))
                    .findFirst()
                    .orElse(null);

            CheckBox cb = new CheckBox(card.getChannel().getChannelName());
            cb.setGraphic(IconColorUtil.colored(icon, "-color-fg-subtle", 14));
            cb.setUserData(channelId);
            cb.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(cb, Priority.ALWAYS);
            if (conflicting != null) {
                cb.setDisable(true);
            } else if (preSelected != null && preSelected.contains(channelId)) {
                cb.setSelected(true);
            }
            cb.selectedProperty().addListener((obs, was, now) -> refreshFooter());
            channelChecks.add(cb);

            HBox row = new HBox(8, cb);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(3, 8, 3, 8));
            row.setUserData(card.getChannel().getChannelName().toLowerCase());
            cb.getProperties().put("row", row);
            channelListBox.getChildren().add(row);
        }
    }

    private void filterChannels() {
        String q = channelSearch.getText() == null ? "" : channelSearch.getText().trim().toLowerCase();
        for (Node n : channelListBox.getChildren()) {
            if (!(n instanceof HBox row) || !(row.getUserData() instanceof String name)) continue;
            boolean show = q.isEmpty() || name.contains(q);
            row.setVisible(show);
            row.setManaged(show);
        }
    }

    private Set<UUID> selectedChannelIds() {
        Set<UUID> ids = new HashSet<>();
        for (CheckBox cb : channelChecks) {
            if (cb.isSelected() && !cb.isDisabled()) ids.add((UUID) cb.getUserData());
        }
        return ids;
    }

    // ── Editor state ─────────────────────────────────────────────────────────

    private void updateSpawnLabels() {
        double v = spawnChanceSlider.getValue();
        if (!spawnChanceField.isFocused()) syncSpawnField();
        spawnFrequencyLabel.setText(v <= 0
                ? "The bot will never post."
                : "About 1 image every " + Math.round(100 / v) + " messages.");
    }

    private void syncSpawnField() {
        spawnChanceField.setText(formatChance(spawnChanceSlider.getValue()));
    }

    private static String formatChance(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    private String currentSig() {
        return nameField.getText().trim() + "|" + spawnChanceSlider.getValue() + "|" + sfwSwitch.isSelected()
                + "|" + new TreeSet<>(selectedChannelIds());
    }

    private boolean hasUnsavedChanges() {
        return (mode == Mode.EDIT || mode == Mode.NEW) && !currentSig().equals(baselineSig);
    }

    private void refreshFooter() {
        if (mode != Mode.EDIT && mode != Mode.NEW) return;
        boolean creating = mode == Mode.NEW;
        boolean dirty = hasUnsavedChanges();
        submitBtn.setDisable(!creating && !dirty);
        revertBtn.setDisable(!creating && !dirty);
    }

    /** Runs {@code go} straight away, or after a discard confirmation if the editor has unsaved changes. */
    private void guardUnsaved(Runnable go) {
        if (!hasUnsavedChanges()) {
            go.run();
            return;
        }
        App.showModal(new ConfirmationModal(
                "Discard changes?",
                "You have unsaved changes to this bot.",
                new FontIcon(MaterialDesignA.ALERT_OUTLINE),
                go));
    }

    // ── View switching ───────────────────────────────────────────────────────

    private void setActive(VBox pane) {
        for (VBox p : new VBox[]{emptyPane, pickPane, editPane}) {
            boolean active = p == pane;
            p.setVisible(active);
            p.setManaged(active);
        }
    }

    private void showLoading() {
        mode = Mode.LOADING;
        emptyTitle.setText("Loading bots...");
        emptyText.setText("");
        emptyAddBtn.setVisible(false);
        emptyAddBtn.setManaged(false);
        setActive(emptyPane);
        renderRail();
    }

    private void showEmpty() {
        mode = Mode.EMPTY;
        editingBot = null;
        baselineSig = "";
        emptyTitle.setText("No bots yet");
        emptyText.setText("Bots post things in your channels automatically. Add one to get started.");
        emptyAddBtn.setVisible(true);
        emptyAddBtn.setManaged(true);
        setActive(emptyPane);
        renderRail();
    }

    private void showPick() {
        mode = Mode.PICK;
        editingBot = null;
        baselineSig = "";
        setActive(pickPane);
        renderRail();
    }

    /** First bot if there is one, otherwise the empty state. */
    private void openDefault() {
        if (loadedBots.isEmpty()) showEmpty();
        else openBot(loadedBots.get(0));
    }

    private void startNew(BotTypeOption option) {
        mode = Mode.NEW;
        editingBot = null;
        selectedType = option;

        editTitle.setText("New bot");
        enabledBox.setVisible(false);
        enabledBox.setManaged(false);
        revertBtn.setText("Cancel");
        submitBtn.setText("Create bot");

        nameField.setText(option.getLabel());
        spawnChanceSlider.setValue(DEFAULT_SPAWN_CHANCE);
        sfwSwitch.setSelected(true);
        populateChannelChecklist(null);
        updateSpawnLabels();

        baselineSig = currentSig();
        setActive(editPane);
        renderRail();
        refreshFooter();
        Platform.runLater(() -> {
            nameField.requestFocus();
            nameField.selectAll();
        });
    }

    private void openBot(BotSummary bot) {
        mode = Mode.EDIT;
        editingBot = bot;
        selectedType = typeOptionFor(bot.getBotType());

        editTitle.setText("Edit bot");
        enabledBox.setVisible(true);
        enabledBox.setManaged(true);
        suppressEnabledListener = true;
        enabledSwitch.setSelected(bot.isEnabled());
        suppressEnabledListener = false;
        revertBtn.setText("Revert");
        submitBtn.setText("Save changes");

        double spawnChance = DEFAULT_SPAWN_CHANCE;
        boolean sfwOnly = true;
        if (bot.getConfig() != null && !bot.getConfig().isBlank()) {
            try {
                JsonObject config = JsonParser.parseString(bot.getConfig()).getAsJsonObject();
                if (config.has("spawnChancePercent")) spawnChance = config.get("spawnChancePercent").getAsDouble();
                if (config.has("sfwOnly")) sfwOnly = config.get("sfwOnly").getAsBoolean();
            } catch (Exception ignored) {
                // fall back to defaults above
            }
        }
        nameField.setText(bot.getName());
        spawnChanceSlider.setValue(spawnChance);
        sfwSwitch.setSelected(sfwOnly);
        populateChannelChecklist(bot.getChannelIds() != null ? new HashSet<>(bot.getChannelIds()) : Set.of());
        updateSpawnLabels();

        baselineSig = currentSig();
        setActive(editPane);
        renderRail();
        refreshFooter();
    }

    private static BotTypeOption typeOptionFor(BotSummary.BotType type) {
        return AVAILABLE_TYPES.stream()
                .filter(o -> o.getType() == type)
                .findFirst()
                .orElse(AVAILABLE_TYPES.get(0));
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    private void renderBots(List<BotSummary> bots) {
        loadedBots = bots != null ? bots : List.of();

        BotSummary target = pendingSelect == null ? null : loadedBots.stream()
                .filter(b -> b.getBotId().equals(pendingSelect)).findFirst().orElse(null);
        pendingSelect = null;

        if (target != null) {
            openBot(target);
        } else if (mode == Mode.LOADING) {
            openDefault();
        } else if (mode == Mode.EDIT && editingBot != null
                && loadedBots.stream().noneMatch(b -> b.getBotId().equals(editingBot.getBotId()))) {
            openDefault();   // the bot being edited no longer exists (deleted)
        } else if (mode == Mode.EMPTY && !loadedBots.isEmpty()) {
            openDefault();
        } else {
            renderRail();
        }
    }

    private void onEnabledToggled(boolean was, boolean isNow) {
        if (suppressEnabledListener || editingBot == null) return;
        BotSummary bot = editingBot;
        Thread.ofVirtual().start(() -> {
            try {
                App.getServices().installation().getBotService().updateBot(bot.getBotId(),
                        BotUpdateRequest.builder().enabled(isNow).build());
                Platform.runLater(() -> {
                    bot.setEnabled(isNow);
                    renderRail();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    suppressEnabledListener = true;
                    enabledSwitch.setSelected(was);
                    suppressEnabledListener = false;
                    new CustomNotification("Error", HttpStatusException.extractMessage(ex),
                            new FontIcon(MaterialDesignC.CLOSE)).showNotification();
                });
            }
        });
    }

    private void confirmDelete() {
        BotSummary bot = editingBot;
        if (bot == null) return;
        App.showModal(new ConfirmationModal(
                "Remove Bot",
                "Remove \"" + bot.getName() + "\" from this server? This cannot be undone.",
                new FontIcon(MaterialDesignD.DELETE_ALERT),
                () -> Thread.ofVirtual().start(() -> {
                    try {
                        App.getServices().installation().getBotService().deleteBot(bot.getBotId());
                        Platform.runLater(loadService::restart);
                    } catch (Exception ex) {
                        Platform.runLater(() -> new CustomNotification("Error", HttpStatusException.extractMessage(ex),
                                new FontIcon(MaterialDesignC.CLOSE)).showNotification());
                    }
                })));
    }

    private void onSubmit() {
        String name = nameField.getText() != null ? nameField.getText().trim() : "";
        if (name.isBlank()) {
            nameField.pseudoClassStateChanged(Styles.STATE_DANGER, true);
            nameField.requestFocus();
            new CustomNotification("Validation Error", "Bot name cannot be empty.",
                    new FontIcon(MaterialDesignC.CLOSE)).showNotification();
            return;
        }

        JsonObject config = new JsonObject();
        config.addProperty("spawnChancePercent", spawnChanceSlider.getValue());
        config.addProperty("sfwOnly", sfwSwitch.isSelected());

        pendingSubmit = BotCreateRequest.builder()
                .botType(selectedType.getType())
                .name(name)
                .config(GsonProvider.get().toJson(config))
                .channelIds(new ArrayList<>(selectedChannelIds()))
                .build();
        submitService.restart();
    }
}
