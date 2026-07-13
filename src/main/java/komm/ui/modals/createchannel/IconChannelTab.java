package komm.ui.modals.createchannel;

import atlantafx.base.theme.Styles;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import komm.App;
import komm.model.dto.request.ChannelUpdateRequest;
import komm.ui.customnodes.CustomNotification;
import komm.ui.utils.IkonSearcher;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.util.List;

/** "Icon" tab: searchable icon picker for the channel's glyph. */
public class IconChannelTab implements ChannelSettingsTab {

    private static final int ICON_BTN_SIZE = 34;
    private static final int ICON_COLS = 7;
    private static final int GRID_SAMPLE_SIZE = 42;  // 6 rows shown by default
    private static final int MAX_ICONS = 144;         // max results when searching

    private final ChannelSettingsContext ctx;

    private TilePane iconGrid;
    private Button selectedIconBtn;
    private Ikon selectedIcon;
    private Label selectedIconLabel;
    private Hyperlink resetLink;
    private FontIcon iconPreviewGlyph;

    private boolean iconDirty = false;
    private final VBox pane;
    private ChannelUpdateRequest pendingIconRequest;

    private final Service<Void> saveIconService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            final ChannelUpdateRequest req = pendingIconRequest;
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

    public IconChannelTab(ChannelSettingsContext ctx) {
        this.ctx = ctx;
        this.pane = buildPane();

        if (ctx.isEditMode()) {
            saveIconService.runningProperty().addListener((obs, was, isRunning) -> {
                ctx.setSaving(isRunning);
                ctx.refreshSaveButton();
            });
            saveIconService.setOnSucceeded(e -> {
                ctx.setOrigIcon(pendingIconRequest.getIcon());
                iconDirty = false;
                ctx.refreshSaveButton();
                new CustomNotification("Icon Updated", "Channel icon has been saved.",
                        new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE)).showNotification();
            });
            saveIconService.setOnFailed(e -> ChannelSettingsUi.showSaveError(saveIconService.getException()));
        }

        Platform.runLater(() -> {
            populateIconGrid(IkonSearcher.search("", GRID_SAMPLE_SIZE));
            if (ctx.isEditMode()) preSelectIcon();
        });
    }

    // ── ChannelSettingsTab ────────────────────────────────────────────────────────

    @Override public String name() { return "Icon"; }
    @Override public String description() { return "Pick an icon to represent this channel"; }
    @Override public FontIcon icon() { return new FontIcon(Feather.IMAGE); }
    @Override public Node getPane() { return pane; }
    @Override public boolean participatesInSave() { return ctx.isEditMode(); }
    @Override public boolean isDirty() { return iconDirty; }
    @Override public boolean isBusy() { return saveIconService.isRunning(); }
    @Override public String saveButtonText() { return "Save Icon"; }
    @Override public void save() { handleSaveIcon(); }

    // ── Create-mode accessor (read by the shell when building the create request) ──

    public String getSelectedIconDescription() {
        return selectedIcon != null ? selectedIcon.getDescription() : null;
    }

    // ── Pane ─────────────────────────────────────────────────────────────────────

    private VBox buildPane() {
        // ── Search ────────────────────────────────────────────────────────────
        TextField search = new TextField();
        search.setPromptText("Search 2000+ icons…");
        search.setMaxWidth(Double.MAX_VALUE);

        Label hint = new Label("Showing a random sample — search to find any icon by name");
        hint.setStyle("-fx-font-size: 10.5px; -fx-text-fill: -color-fg-subtle;");

        // ── Icon grid in a scroll pane ────────────────────────────────────────
        iconGrid = new TilePane();
        iconGrid.setPrefColumns(ICON_COLS);
        iconGrid.setHgap(4);
        iconGrid.setVgap(4);
        iconGrid.setPadding(new Insets(4));

        ScrollPane gridScroll = new ScrollPane(iconGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        gridScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(gridScroll, Priority.ALWAYS);

        PauseTransition debounce = new PauseTransition(Duration.millis(280));
        search.textProperty().addListener((obs, o, val) -> {
            debounce.setOnFinished(e -> {
                int limit = val == null || val.isBlank() ? GRID_SAMPLE_SIZE : MAX_ICONS;
                List<Ikon> icons = IkonSearcher.search(val, limit);
                Platform.runLater(() -> populateIconGrid(icons));
            });
            debounce.playFromStart();
        });

        // ── Selected icon preview ─────────────────────────────────────────────
        iconPreviewGlyph = new FontIcon();
        iconPreviewGlyph.setIconSize(22);
        iconPreviewGlyph.setVisible(false);
        iconPreviewGlyph.setManaged(false);

        StackPane iconSlot = new StackPane(iconPreviewGlyph);
        iconSlot.setMinSize(40, 40);
        iconSlot.setMaxSize(40, 40);
        iconSlot.setPrefSize(40, 40);
        iconSlot.setAlignment(Pos.CENTER);
        iconSlot.setStyle(
                "-fx-background-color: -color-bg-default;" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: -color-border-default;" +
                "-fx-border-radius: 8;" +
                "-fx-border-width: 1;");

        selectedIconLabel = new Label("No icon selected");
        selectedIconLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");

        resetLink = new Hyperlink("Remove");
        resetLink.setStyle("-fx-font-size: 11px;");
        resetLink.setVisible(false);
        resetLink.setManaged(false);
        resetLink.setOnAction(e -> clearCustomIcon());

        VBox previewText = new VBox(2, selectedIconLabel, resetLink);
        previewText.setAlignment(Pos.CENTER_LEFT);

        HBox previewPanel = new HBox(12, iconSlot, previewText);
        previewPanel.setAlignment(Pos.CENTER_LEFT);
        previewPanel.setPadding(new Insets(10, 12, 10, 12));
        previewPanel.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: -color-border-default;" +
                "-fx-border-radius: 8;" +
                "-fx-border-width: 1;");

        VBox pane = new VBox(8, ChannelSettingsUi.sectionLabel("ICON"), search, hint, gridScroll, previewPanel);
        pane.setPadding(new Insets(24, 24, 12, 24));
        pane.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(gridScroll, Priority.ALWAYS);
        VBox.setVgrow(pane, Priority.ALWAYS);
        return pane;
    }

    private void preSelectIcon() {
        String origIcon = ctx.getOrigIcon();
        if (origIcon == null || origIcon.isBlank()) return;
        try {
            FontIcon fi = new FontIcon(origIcon);
            selectedIcon = fi.getIconCode();
            iconPreviewGlyph.setIconCode(selectedIcon);
            iconPreviewGlyph.setVisible(true);
            iconPreviewGlyph.setManaged(true);
            selectedIconLabel.setText(IkonSearcher.label(selectedIcon));
            selectedIconLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-default;");
            resetLink.setVisible(true);
            resetLink.setManaged(true);
        } catch (Exception ignored) {
        }
    }

    // ── Icon grid ─────────────────────────────────────────────────────────────

    private void populateIconGrid(List<Ikon> icons) {
        iconGrid.getChildren().clear();
        for (Ikon ikon : icons) iconGrid.getChildren().add(buildIconButton(ikon));
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
        btn.setTooltip(new Tooltip(IkonSearcher.label(ikon)));
        btn.setOnAction(e -> selectIcon(ikon, btn));
        return btn;
    }

    private void selectIcon(Ikon ikon, Button btn) {
        if (selectedIconBtn != null) {
            selectedIconBtn.getStyleClass().remove(Styles.ACCENT);
            selectedIconBtn.setStyle("");
        }
        selectedIcon = ikon;
        selectedIconBtn = btn;
        btn.getStyleClass().add(Styles.ACCENT);
        iconPreviewGlyph.setIconCode(ikon);
        iconPreviewGlyph.setVisible(true);
        iconPreviewGlyph.setManaged(true);
        selectedIconLabel.setText(IkonSearcher.label(ikon));
        selectedIconLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-default;");
        resetLink.setVisible(true);
        resetLink.setManaged(true);
        if (ctx.isEditMode()) updateIconDirtyState();
    }

    private void clearCustomIcon() {
        selectedIcon = null;
        if (selectedIconBtn != null) {
            selectedIconBtn.getStyleClass().remove(Styles.ACCENT);
            selectedIconBtn = null;
        }
        iconPreviewGlyph.setVisible(false);
        iconPreviewGlyph.setManaged(false);
        selectedIconLabel.setText("No icon selected");
        selectedIconLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        resetLink.setVisible(false);
        resetLink.setManaged(false);
        if (ctx.isEditMode()) updateIconDirtyState();
    }

    // ── Edit-mode dirty state / save ────────────────────────────────────────────

    private void updateIconDirtyState() {
        String current = selectedIcon != null ? selectedIcon.getDescription() : null;
        iconDirty = !java.util.Objects.equals(current, ctx.getOrigIcon());
        ctx.refreshSaveButton();
    }

    private void handleSaveIcon() {
        String newIconDesc = selectedIcon != null ? selectedIcon.getDescription() : null;
        pendingIconRequest = ChannelUpdateRequest.builder()
                .channelName(ctx.getOrigName())
                .icon(newIconDesc)
                .build();
        saveIconService.restart();
    }
}
