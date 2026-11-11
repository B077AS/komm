package komm.ui.modals.createchannel;

import atlantafx.base.theme.Styles;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import komm.App;
import komm.model.dto.request.ChannelUpdateRequest;
import komm.model.dto.summary.ChannelSummary.ChannelType;
import komm.ui.customnodes.CustomNotification;
import komm.ui.emojis.EmojiFilterTextField;
import komm.ui.utils.IkonSearcher;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.util.List;
import java.util.Objects;

/**
 * "Details" tab: channel icon tile + name on one row, voice/text type, and the icon picker.
 * Name, type and icon live in the shared {@link ChannelSettingsContext} so the modal's sidebar
 * block can mirror them live.
 */
public class DetailsChannelTab implements ChannelSettingsTab {

    private static final int ICON_BTN_SIZE = 34;
    private static final int GRID_SAMPLE_SIZE = 60;
    private static final int MAX_ICONS = 144;

    private final ChannelSettingsContext ctx;

    private final TextField channelNameField;
    private final TextField searchField = new TextField();
    private HBox voiceCard, textCard;
    private TilePane iconGrid;
    private Hyperlink resetLink;

    private boolean dirty = false;
    private final VBox pane;
    private ChannelUpdateRequest pendingRequest;

    private final Service<Void> saveService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            final ChannelUpdateRequest req = pendingRequest;
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().installation().getChannelService()
                            .updateChannel(ctx.editChannel().getChannelId(), req);
                    return null;
                }
            };
        }
    };

    public DetailsChannelTab(ChannelSettingsContext ctx) {
        this.ctx = ctx;
        this.channelNameField = EmojiFilterTextField.maxLength(100);
        this.pane = buildPane();

        if (ctx.isEditMode()) {
            ctx.draftNameProperty().addListener((o, a, b) -> updateDirtyState());
            ctx.draftIconProperty().addListener((o, a, b) -> updateDirtyState());

            saveService.runningProperty().addListener((obs, was, isRunning) -> {
                ctx.setSaving(isRunning);
                ctx.refreshSaveButton();
            });
            saveService.setOnSucceeded(e -> {
                ctx.setOrigName(pendingRequest.getChannelName());
                ctx.setOrigIcon(pendingRequest.getIcon());
                updateDirtyState();
                new CustomNotification("Channel Updated", "Channel settings have been saved.",
                        new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE)).showNotification();
            });
            saveService.setOnFailed(e -> ChannelSettingsUi.showSaveError(saveService.getException()));
        }

        Platform.runLater(() -> populateIconGrid(IkonSearcher.search("", GRID_SAMPLE_SIZE)));
    }

    // ── ChannelSettingsTab ────────────────────────────────────────────────────────

    @Override public String name() { return "Details"; }
    @Override public String description() { return "Name, type and icon"; }
    @Override public FontIcon icon() { return new FontIcon(Feather.SLIDERS); }
    @Override public Node getPane() { return pane; }
    @Override public boolean participatesInSave() { return ctx.isEditMode(); }
    @Override public boolean isDirty() { return dirty; }
    @Override public boolean isBusy() { return saveService.isRunning(); }
    @Override public String saveButtonText() { return "Save Details"; }
    @Override public void save() { handleSave(); }

    // ── Create-mode accessors (read by the shell when building the create request) ─

    public String getChannelName() { return channelNameField.getText().trim(); }
    public void focusNameField() { channelNameField.requestFocus(); }

    // ── Pane ─────────────────────────────────────────────────────────────────────

    private VBox buildPane() {
        // Identity row: icon tile + name
        StackPane tile = ChannelSettingsUi.channelTile(ctx, 64, "large");
        tile.setCursor(Cursor.HAND);
        Tooltip.install(tile, new Tooltip("Pick an icon below"));
        tile.setOnMouseClicked(e -> searchField.requestFocus());

        channelNameField.setPromptText("e.g. general, announcements");
        channelNameField.setMaxWidth(Double.MAX_VALUE);
        channelNameField.setText(ctx.getDraftName());
        channelNameField.textProperty().addListener((obs, o, n) -> ctx.setDraftName(n == null ? "" : n));

        VBox nameBox = new VBox(6, ChannelSettingsUi.sectionLabel("CHANNEL NAME"), channelNameField);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        HBox identityRow = new HBox(14, tile, nameBox);
        identityRow.setAlignment(Pos.BOTTOM_LEFT);

        // Type cards
        voiceCard = buildTypeCard(MaterialDesignM.MICROPHONE, "Voice Channel",
                "Talk, screen share, and send messages");
        textCard = buildTypeCard(Feather.HASH, "Text Channel",
                "Send messages and share files");
        voiceCard.setOnMouseClicked(e -> { if (!ctx.isEditMode()) ctx.setVoice(true); });
        textCard.setOnMouseClicked(e -> { if (!ctx.isEditMode()) ctx.setVoice(false); });

        if (ctx.isEditMode()) {
            ctx.setVoice(ctx.editChannel().getChannelType() == ChannelType.VOICE);
            voiceCard.setDisable(true);
            textCard.setDisable(true);
            voiceCard.setOpacity(0.5);
            textCard.setOpacity(0.5);
        } else {
            ctx.setVoice(true);
        }
        ctx.voiceProperty().addListener((o, a, b) -> refreshTypeCards());
        refreshTypeCards();

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        col1.setHgrow(Priority.ALWAYS);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        col2.setHgrow(Priority.ALWAYS);
        GridPane typeRow = new GridPane();
        typeRow.getColumnConstraints().addAll(col1, col2);
        typeRow.setHgap(10);
        typeRow.setMaxWidth(Double.MAX_VALUE);
        voiceCard.setMaxWidth(Double.MAX_VALUE);
        textCard.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(voiceCard, Priority.ALWAYS);
        GridPane.setHgrow(textCard, Priority.ALWAYS);
        typeRow.add(voiceCard, 0, 0);
        typeRow.add(textCard, 1, 0);

        VBox typeBox = new VBox(8, ChannelSettingsUi.sectionLabel("CHANNEL TYPE"), typeRow);

        // Icon picker
        searchField.setPromptText("Search 2000+ icons…");
        searchField.setMaxWidth(Double.MAX_VALUE);

        resetLink = new Hyperlink("Use default icon");
        resetLink.setStyle("-fx-font-size: 11px;");
        resetLink.setOnAction(e -> ctx.setDraftIcon(null));
        resetLink.visibleProperty().bind(ctx.draftIconProperty().isNotNull());
        resetLink.managedProperty().bind(resetLink.visibleProperty());

        Region labelSpacer = new Region();
        HBox.setHgrow(labelSpacer, Priority.ALWAYS);
        HBox iconHeader = new HBox(ChannelSettingsUi.sectionLabel("ICON"), labelSpacer, resetLink);
        iconHeader.setAlignment(Pos.CENTER_LEFT);

        iconGrid = new TilePane();
        iconGrid.setHgap(4);
        iconGrid.setVgap(4);
        iconGrid.setPadding(new Insets(4));
        iconGrid.setPrefTileWidth(ICON_BTN_SIZE);
        iconGrid.setPrefTileHeight(ICON_BTN_SIZE);

        ScrollPane gridScroll = new ScrollPane(iconGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        gridScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(gridScroll, Priority.ALWAYS);

        PauseTransition debounce = new PauseTransition(Duration.millis(280));
        searchField.textProperty().addListener((obs, o, val) -> {
            debounce.setOnFinished(e -> {
                int limit = val == null || val.isBlank() ? GRID_SAMPLE_SIZE : MAX_ICONS;
                List<Ikon> icons = IkonSearcher.search(val, limit);
                Platform.runLater(() -> populateIconGrid(icons));
            });
            debounce.playFromStart();
        });
        ctx.draftIconProperty().addListener((o, a, b) -> highlightSelected());

        Label hint = new Label("Showing a sample - search to find any icon by name");
        hint.setStyle("-fx-font-size: 10.5px; -fx-text-fill: -color-fg-subtle;");

        VBox iconBox = new VBox(6, iconHeader, searchField, gridScroll, hint);
        VBox.setVgrow(iconBox, Priority.ALWAYS);

        VBox pane = new VBox(18, identityRow, typeBox, iconBox);
        pane.setPadding(new Insets(22, 24, 10, 24));
        pane.setMaxWidth(Double.MAX_VALUE);
        return pane;
    }

    // ── Type cards ────────────────────────────────────────────────────────────

    private HBox buildTypeCard(Ikon iconCode, String title, String description) {
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(20);

        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        Label descLbl = new Label(description);
        descLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        descLbl.setWrapText(true);

        VBox text = new VBox(2, titleLbl, descLbl);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox card = new HBox(12, icon, text);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(12, 14, 12, 14));
        card.setMinHeight(66);
        return card;
    }

    private void refreshTypeCards() {
        applyCardStyle(voiceCard, ctx.isVoice());
        applyCardStyle(textCard, !ctx.isVoice());
    }

    private void applyCardStyle(HBox card, boolean selected) {
        card.setStyle(
                (selected
                        ? "-fx-background-color: -color-accent-subtle; -fx-border-color: -color-accent-emphasis;"
                        : "-fx-background-color: -color-bg-subtle;    -fx-border-color: -color-border-default;") +
                        " -fx-background-radius: 3; -fx-border-radius: 3; -fx-border-width: 1.5; -fx-cursor: hand;");
    }

    // ── Icon grid ─────────────────────────────────────────────────────────────

    private void populateIconGrid(List<Ikon> icons) {
        iconGrid.getChildren().clear();
        for (Ikon ikon : icons) iconGrid.getChildren().add(buildIconButton(ikon));
        highlightSelected();
    }

    private Button buildIconButton(Ikon ikon) {
        FontIcon icon = new FontIcon(ikon);
        icon.setIconSize(18);
        Button btn = new Button(null, icon);
        btn.setMinSize(ICON_BTN_SIZE, ICON_BTN_SIZE);
        btn.setMaxSize(ICON_BTN_SIZE, ICON_BTN_SIZE);
        btn.setPrefSize(ICON_BTN_SIZE, ICON_BTN_SIZE);
        btn.setFocusTraversable(false);
        btn.getStyleClass().add(Styles.FLAT);
        btn.setUserData(ikon);
        btn.setTooltip(new Tooltip(IkonSearcher.label(ikon)));
        btn.setOnAction(e -> {
            // Clicking the selected icon again goes back to the type's default glyph.
            boolean already = ikon.equals(ctx.getDraftIcon());
            ctx.setDraftIcon(already ? null : ikon);
        });
        return btn;
    }

    private void highlightSelected() {
        Ikon sel = ctx.getDraftIcon();
        for (Node n : iconGrid.getChildren()) {
            boolean on = sel != null && sel.equals(n.getUserData());
            if (on) {
                if (!n.getStyleClass().contains(Styles.ACCENT)) n.getStyleClass().add(Styles.ACCENT);
            } else {
                n.getStyleClass().remove(Styles.ACCENT);
            }
        }
    }

    // ── Edit-mode dirty state / save ────────────────────────────────────────────

    private void updateDirtyState() {
        String name = ctx.getDraftName().trim();
        String icon = ctx.getDraftIcon() != null ? ctx.getDraftIcon().getDescription() : null;
        dirty = !name.equals(ctx.getOrigName()) || !Objects.equals(blankToNull(icon), blankToNull(ctx.getOrigIcon()));
        ctx.refreshSaveButton();
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private void handleSave() {
        String name = channelNameField.getText().trim();
        if (name.isEmpty()) {
            new CustomNotification("Validation Error", "Channel name is required.", new FontIcon(MaterialDesignP.POUND))
                    .showNotification();
            channelNameField.requestFocus();
            return;
        }
        if (name.length() > 100) {
            new CustomNotification("Validation Error", "Channel name must be 100 characters or fewer.", new FontIcon(MaterialDesignP.POUND))
                    .showNotification();
            channelNameField.requestFocus();
            return;
        }
        pendingRequest = ChannelUpdateRequest.builder()
                .channelName(name)
                .icon(ctx.getDraftIcon() != null ? ctx.getDraftIcon().getDescription() : null)
                .build();
        saveService.restart();
    }
}
