package komm.ui.modals;

import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Manage Bots" modal, reachable from the server context menu (gated behind
 * {@code Permission.MANAGE_BOTS}). Three views swapped in a shared {@link StackPane}: the bot
 * list, a bot-type picker (today just {@code ANIME_WAIFU}, but structured as a list so a second
 * type is additive), and the per-type config form — shared between "Add Bot" (from the picker)
 * and "Edit" (from a bot row), distinguished by {@link #editingBot}.
 */
public class BotsModal extends VBox {

    /** One entry per addable bot type — the picker view just iterates this. */
    private record BotTypeOption(BotSummary.BotType type, String label, String description,
                                  org.kordamp.ikonli.Ikon icon) {}

    private static final List<BotTypeOption> AVAILABLE_TYPES = List.of(
            new BotTypeOption(BotSummary.BotType.ANIME_WAIFU, "Anime Waifu Spawner",
                    "Posts a random anime image every so often as people chat. "
                            + "Capped to at most one image every 20 seconds per channel, no matter the chance.",
                    MaterialDesignR.ROBOT_HAPPY_OUTLINE)
    );

    private final ServerSummary server;

    private StackPane contentStack;
    private VBox listView;
    private VBox typePickerView;
    private VBox configView;
    private VBox listArea;

    private BotTypeOption selectedType;
    /** Non-null while the config view is editing an existing bot rather than creating one. */
    private BotSummary editingBot;

    private Label configSubtitle;
    private TextField nameField;
    private Slider spawnChanceSlider;
    private Label spawnChanceValueLabel;
    private CheckBox sfwOnlyCheck;
    private VBox channelChecklistBox;
    private Button submitBtn;
    private Button configCancelBtn;
    private final List<CheckBox> channelChecks = new ArrayList<>();
    private List<BotSummary> loadedBots = List.of();

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
        setMinSize(520, 520);
        setPrefSize(520, 520);
        setMaxSize(520, 560);
        setSpacing(0);

        listView = buildListView();
        typePickerView = buildTypePickerView();
        configView = buildConfigView();
        setActive(listView);

        contentStack = new StackPane(listView, typePickerView, configView);
        VBox.setVgrow(contentStack, Priority.ALWAYS);

        getChildren().addAll(buildHeader(), contentStack);
    }

    private void wireServices() {
        loadService.setOnSucceeded(e -> renderBots(loadService.getValue()));
        loadService.setOnFailed(e -> renderBots(List.of()));

        submitService.setOnSucceeded(e -> {
            String verb = editingBot == null ? "added" : "updated";
            new CustomNotification(editingBot == null ? "Bot Added" : "Bot Updated",
                    "\"" + submitService.getValue().getName() + "\" was " + verb + ".",
                    new FontIcon(MaterialDesignR.ROBOT_OUTLINE)).showNotification();
            showListView();
            loadService.restart();
        });
        submitService.setOnFailed(e -> new CustomNotification(
                "Error",
                HttpStatusException.extractMessage(submitService.getException()),
                new FontIcon(MaterialDesignC.CLOSE)).showNotification());
    }

    // ── Header ───────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 8, 0, 16));

        Label title = new Label("Bots");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeBtn.setOnAction(e -> App.closeModal());

        header.getChildren().addAll(title, spacer, closeBtn);
        return header;
    }

    // ── List view ────────────────────────────────────────────────────────────

    private VBox buildListView() {
        VBox root = new VBox(0);

        VBox content = new VBox(12);
        content.setPadding(new Insets(12, 16, 0, 16));
        VBox.setVgrow(content, Priority.ALWAYS);

        Label subtitle = new Label("Automated bots active on this server, and which channels they run in.");
        subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-muted;");
        subtitle.setWrapText(true);

        listArea = new VBox(8);
        VBox.setVgrow(listArea, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(listArea);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        content.getChildren().addAll(subtitle, scroll);

        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 16, 12, 16));

        Button addBotBtn = new Button("Add Bot", new FontIcon(MaterialDesignP.PLUS));
        addBotBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        addBotBtn.setOnAction(e -> showTypePickerView());

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add(Styles.SMALL);
        closeBtn.setOnAction(e -> App.closeModal());

        footer.getChildren().addAll(addBotBtn, closeBtn);

        root.getChildren().addAll(content, footer);
        return root;
    }

    private void renderBots(List<BotSummary> bots) {
        loadedBots = bots != null ? bots : List.of();
        listArea.getChildren().clear();
        if (bots == null || bots.isEmpty()) {
            listArea.getChildren().add(buildEmptyState());
            return;
        }
        for (BotSummary bot : bots) {
            listArea.getChildren().add(buildBotRow(bot));
        }
    }

    private VBox buildEmptyState() {
        VBox empty = new VBox(8);
        empty.setAlignment(Pos.CENTER);
        empty.setPadding(new Insets(32, 0, 32, 0));
        VBox.setVgrow(empty, Priority.ALWAYS);

        FontIcon icon = new FontIcon(MaterialDesignR.ROBOT_OUTLINE);
        icon.setIconSize(40);
        icon.setStyle("-fx-icon-color: -color-fg-subtle;");

        Label text = new Label("No bots yet");
        text.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-subtle;");

        empty.getChildren().addAll(icon, text);
        return empty;
    }

    private HBox buildBotRow(BotSummary bot) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 8px;");

        StackPane avatar = new StackPane();
        avatar.setPrefSize(32, 32);
        avatar.setMaxSize(32, 32);
        Circle bg = new Circle(16, AvatarColor.forNameJfx(bot.getName() != null ? bot.getName() : "B"));
        Label initial = new Label(bot.getName() != null && !bot.getName().isBlank()
                ? String.valueOf(bot.getName().charAt(0)).toUpperCase() : "B");
        initial.setStyle("-fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: bold;");
        avatar.getChildren().addAll(bg, initial);

        VBox textCol = new VBox(2);
        Label nameLbl = new Label(bot.getName());
        nameLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        int channelCount = bot.getChannelIds() != null ? bot.getChannelIds().size() : 0;
        Label subLbl = new Label(botTypeLabel(bot.getBotType()) + " · " + channelCount
                + (channelCount == 1 ? " channel" : " channels"));
        subLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        textCol.getChildren().addAll(nameLbl, subLbl);
        HBox.setHgrow(textCol, Priority.ALWAYS);

        ToggleSwitch enabledSwitch = new ToggleSwitch();
        enabledSwitch.setSelected(bot.isEnabled());
        enabledSwitch.selectedProperty().addListener((obs, was, isNow) -> {
            try {
                App.getServices().installation().getBotService().updateBot(bot.getBotId(),
                        BotUpdateRequest.builder().enabled(isNow).build());
            } catch (Exception ex) {
                new CustomNotification("Error", HttpStatusException.extractMessage(ex),
                        new FontIcon(MaterialDesignC.CLOSE)).showNotification();
            }
        });

        Button editBtn = new Button(null, new FontIcon(MaterialDesignP.PENCIL_OUTLINE));
        editBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        editBtn.setOnAction(e -> showEditConfigView(bot));

        Button deleteBtn = new Button(null, new FontIcon(MaterialDesignD.DELETE_OUTLINE));
        deleteBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        deleteBtn.setOnAction(e -> App.showModal(new ConfirmationModal(
                "Remove Bot",
                "Remove \"" + bot.getName() + "\" from this server? This cannot be undone.",
                new FontIcon(MaterialDesignD.DELETE_ALERT),
                () -> {
                    try {
                        App.getServices().installation().getBotService().deleteBot(bot.getBotId());
                        loadService.restart();
                    } catch (Exception ex) {
                        new CustomNotification("Error", HttpStatusException.extractMessage(ex),
                                new FontIcon(MaterialDesignC.CLOSE)).showNotification();
                    }
                })));

        row.getChildren().addAll(avatar, textCol, enabledSwitch, editBtn, deleteBtn);
        return row;
    }

    private static String botTypeLabel(BotSummary.BotType type) {
        if (type == null) return "Bot";
        return switch (type) {
            case ANIME_WAIFU -> "Anime Waifu Spawner";
        };
    }

    private static BotTypeOption typeOptionFor(BotSummary.BotType type) {
        return AVAILABLE_TYPES.stream()
                .filter(o -> o.type() == type)
                .findFirst()
                .orElse(AVAILABLE_TYPES.get(0));
    }

    // ── Type picker view ────────────────────────────────────────────────────

    private VBox buildTypePickerView() {
        VBox root = new VBox(0);

        VBox content = new VBox(10);
        content.setPadding(new Insets(12, 16, 0, 16));
        VBox.setVgrow(content, Priority.ALWAYS);

        Label subtitle = new Label("Choose a bot to add.");
        subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-muted;");

        VBox typeList = new VBox(8);
        for (BotTypeOption option : AVAILABLE_TYPES) {
            typeList.getChildren().add(buildTypeOptionCard(option));
        }

        content.getChildren().addAll(subtitle, typeList);

        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 16, 12, 16));

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add(Styles.SMALL);
        cancelBtn.setOnAction(e -> showListView());

        footer.getChildren().add(cancelBtn);

        root.getChildren().addAll(content, footer);
        return root;
    }

    private HBox buildTypeOptionCard(BotTypeOption option) {
        HBox card = new HBox(10);
        card.setPadding(new Insets(10));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setCursor(Cursor.HAND);
        card.setStyle("-fx-background-color: -color-bg-subtle;"
                + " -fx-border-color: -color-border-muted; -fx-border-width: 1.5px;"
                + " -fx-border-radius: 8px; -fx-background-radius: 8px;");

        VBox textCol = new VBox(6);
        HBox.setHgrow(textCol, Priority.ALWAYS);

        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        FontIcon icon = new FontIcon(option.icon());
        Label nameLbl = new Label(option.label());
        nameLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        titleRow.getChildren().addAll(icon, nameLbl);

        Label descLbl = new Label(option.description());
        descLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        descLbl.setWrapText(true);

        textCol.getChildren().addAll(titleRow, descLbl);

        // Chevron lives in the outer (card-level) HBox, not the title row, so CENTER_LEFT
        // vertically centers it against the whole card's height (title + description), not just
        // the thin title row.
        FontIcon chevron = new FontIcon(MaterialDesignA.ARROW_RIGHT);

        card.getChildren().addAll(textCol, chevron);
        card.setOnMouseClicked(e -> showAddConfigView(option));
        return card;
    }

    // ── Config view (shared by add + edit) ──────────────────────────────────

    private VBox buildConfigView() {
        VBox root = new VBox(0);

        VBox content = new VBox(14);
        content.setPadding(new Insets(12, 16, 12, 16));

        configSubtitle = new Label();
        configSubtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-muted;");
        configSubtitle.setWrapText(true);

        Label nameLabel = new Label("Name");
        nameLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        nameField = new TextField();

        Label spawnLabel = new Label("Spawn chance per message");
        spawnLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        spawnChanceSlider = new Slider(0, 100, 2);
        spawnChanceSlider.setShowTickLabels(false);
        spawnChanceValueLabel = new Label("2%");
        spawnChanceSlider.valueProperty().addListener((obs, was, now) ->
                spawnChanceValueLabel.setText(Math.round(now.doubleValue() * 10) / 10.0 + "%"));
        HBox spawnRow = new HBox(10, spawnChanceSlider, spawnChanceValueLabel);
        spawnRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spawnChanceSlider, Priority.ALWAYS);

        Label spawnNote = new Label("Regardless of chance, this bot won't post more than once "
                + "every 20 seconds in the same channel.");
        spawnNote.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        spawnNote.setWrapText(true);

        sfwOnlyCheck = new CheckBox("SFW images only");
        sfwOnlyCheck.setSelected(true);

        Label channelsLabel = new Label("Channels");
        channelsLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        channelChecklistBox = new VBox(4);
        VBox.setVgrow(channelChecklistBox, Priority.ALWAYS);

        content.getChildren().addAll(configSubtitle, nameLabel, nameField,
                spawnLabel, spawnRow, spawnNote, sfwOnlyCheck, channelsLabel, channelChecklistBox);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 16, 12, 16));

        submitBtn = new Button("Create");
        submitBtn.setDefaultButton(true);
        submitBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        submitBtn.setOnAction(e -> onSubmit());

        configCancelBtn = new Button("Cancel");
        configCancelBtn.getStyleClass().add(Styles.SMALL);
        configCancelBtn.setOnAction(e -> {
            if (editingBot != null) showListView();
            else showTypePickerView();
        });

        footer.getChildren().addAll(submitBtn, configCancelBtn);

        root.getChildren().addAll(scroll, footer);
        return root;
    }

    /** Channels this server has, of the types a bot can actually run in (text chat + voice chat). */
    private void populateChannelChecklist(List<UUID> preSelected) {
        channelChecklistBox.getChildren().clear();
        channelChecks.clear();

        Map<UUID, ChannelCard> boxes = App.getCachedServerPage() != null
                ? App.getCachedServerPage().getChannelSection().getChannelBoxes() : Map.of();

        List<ChannelCard> eligible = boxes.values().stream()
                .filter(c -> c.getChannel().getChannelType() == ChannelSummary.ChannelType.TEXT
                        || c.getChannel().getChannelType() == ChannelSummary.ChannelType.VOICE)
                .sorted((a, b) -> Integer.compare(a.getChannel().getPosition(), b.getChannel().getPosition()))
                .toList();

        if (eligible.isEmpty()) {
            Label none = new Label("No text or voice channels yet.");
            none.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-subtle;");
            channelChecklistBox.getChildren().add(none);
            return;
        }

        for (ChannelCard card : eligible) {
            UUID channelId = card.getChannel().getChannelId();
            boolean isVoice = card.getChannel().getChannelType() == ChannelSummary.ChannelType.VOICE;

            // A channel already running a different bot of this same type is off-limits — two
            // bots of the same type in one channel would just double the spawn rate unpredictably
            // (enforced server-side too; this just surfaces it before the user hits Create/Save).
            BotSummary conflicting = loadedBots.stream()
                    .filter(b -> (editingBot == null || !b.getBotId().equals(editingBot.getBotId())))
                    .filter(b -> b.getBotType() == selectedType.type())
                    .filter(b -> b.getChannelIds() != null && b.getChannelIds().contains(channelId))
                    .findFirst()
                    .orElse(null);

            CheckBox cb = new CheckBox((isVoice ? "🔊 " : "# ") + card.getChannel().getChannelName());
            cb.setUserData(channelId);
            if (conflicting != null) {
                cb.setDisable(true);
            } else if (preSelected != null && preSelected.contains(channelId)) {
                cb.setSelected(true);
            }
            channelChecks.add(cb);
            channelChecklistBox.getChildren().add(cb);
        }
    }

    private void onSubmit() {
        String name = nameField.getText() != null ? nameField.getText().trim() : "";
        if (name.isBlank()) {
            new CustomNotification("Validation Error", "Bot name cannot be empty.",
                    new FontIcon(MaterialDesignC.CLOSE)).showNotification();
            return;
        }

        List<UUID> selectedChannels = channelChecks.stream()
                .filter(CheckBox::isSelected)
                .map(cb -> (UUID) cb.getUserData())
                .toList();

        JsonObject config = new JsonObject();
        config.addProperty("spawnChancePercent", spawnChanceSlider.getValue());
        config.addProperty("sfwOnly", sfwOnlyCheck.isSelected());

        pendingSubmit = BotCreateRequest.builder()
                .botType(selectedType.type())
                .name(name)
                .config(GsonProvider.get().toJson(config))
                .channelIds(selectedChannels)
                .build();
        submitService.restart();
    }

    // ── View switching ───────────────────────────────────────────────────────

    private void showTypePickerView() {
        editingBot = null;
        setActive(typePickerView);
    }

    private void showAddConfigView(BotTypeOption option) {
        editingBot = null;
        selectedType = option;
        configSubtitle.setText("Adding: " + option.label());
        submitBtn.setText("Create");
        nameField.setText(option.label());
        spawnChanceSlider.setValue(2);
        sfwOnlyCheck.setSelected(true);
        populateChannelChecklist(null);
        setActive(configView);
    }

    private void showEditConfigView(BotSummary bot) {
        editingBot = bot;
        selectedType = typeOptionFor(bot.getBotType());
        configSubtitle.setText("Editing: " + selectedType.label());
        submitBtn.setText("Save Changes");
        nameField.setText(bot.getName());

        double spawnChance = 2;
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
        spawnChanceSlider.setValue(spawnChance);
        sfwOnlyCheck.setSelected(sfwOnly);
        populateChannelChecklist(bot.getChannelIds());
        setActive(configView);
    }

    private void showListView() {
        setActive(listView);
    }

    private void setActive(VBox view) {
        for (VBox v : new VBox[]{listView, typePickerView, configView}) {
            if (v == null) continue;
            boolean active = v == view;
            v.setVisible(active);
            v.setManaged(active);
        }
    }
}
