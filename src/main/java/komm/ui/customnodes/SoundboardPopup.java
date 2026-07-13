package komm.ui.customnodes;

import atlantafx.base.theme.Styles;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import komm.App;
import komm.AppState;
import komm.api.HttpStatusException;
import komm.model.dto.summary.SoundboardSummary;
import komm.model.permissions.Permission;
import komm.service.PersonalSoundboardEntry;
import komm.service.PersonalSoundboardStore;
import komm.service.SoundboardCache;
import komm.service.SoundboardService;
import komm.ui.modals.AddSoundModal;
import komm.utils.UserSettings;
import komm.websocket.messages.WsMessageType;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class SoundboardPopup extends Popup {

    // Fully qualified to avoid clashing with the javafx.application.Platform import.
    private static final boolean IS_LINUX = com.sun.jna.Platform.isLinux();

    private static final int SLOT_COUNT = 16;
    private static final int COLS = 4;
    private static final double SLOT_W = 116, SLOT_H = 56;

    private static final double WIN_W = 680;
    private static final double WIN_H = 400;

    private enum Tab { SERVER, MINE, ADVANCED }

    private final UUID channelId;
    private final StackPane content = new StackPane();
    private final VBox navBox = new VBox(2);
    private Tab currentTab;
    private boolean showingGrid;

    public SoundboardPopup(UUID channelId) {
        this.channelId = channelId;

        setAutoHide(true);
        setConsumeAutoHidingEvents(false);
        setHideOnEscape(true);

        HBox root = new HBox();
        root.getStyleClass().add("custom-pop-up");
        root.setMinSize(WIN_W, WIN_H);
        root.setMaxSize(WIN_W, WIN_H);
        root.setPadding(Insets.EMPTY);

        root.getChildren().addAll(buildSidebar(), buildContentColumn());
        getContent().add(root);

        SoundboardCache.setOnUpdate(() -> Platform.runLater(() -> {
            if (currentTab == Tab.SERVER && showingGrid) showServerTabFromCache();
        }));
        setOnHidden(e -> SoundboardCache.setOnUpdate(null));

        selectTab(Tab.SERVER);
    }

    // ── Layout ──────────────────────────────────────────────────────────────────

    private VBox buildSidebar() {
        VBox side = new VBox(0);
        side.setMinWidth(150);
        side.setMaxWidth(150);
        side.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 12px 0 0 12px;");

        Label title = new Label("SOUNDBOARD");
        title.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle; -fx-padding: 14 0 6 14;");

        navBox.setPadding(new Insets(2, 8, 12, 8));
        navBox.getChildren().addAll(
                navItem(new FontIcon(MaterialDesignS.SERVER), "Server", Tab.SERVER),
                navItem(new FontIcon(MaterialDesignV.VOLUME_HIGH), "Mine", Tab.MINE),
                navItem(new FontIcon(MaterialDesignC.COG), "Advanced", Tab.ADVANCED));

        side.getChildren().addAll(title, navBox);
        return side;
    }

    private VBox navItem(FontIcon icon, String text, Tab tab) {
        icon.setIconSize(15);
        Label lbl = new Label(text);
        lbl.getStyleClass().add("nav-label");
        HBox row = new HBox(10, icon, lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setFocusTraversable(false);
        VBox item = new VBox(row);
        item.setUserData(tab);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(9, 12, 9, 12));
        item.getStyleClass().add("nav-item");
        item.setFocusTraversable(false);
        item.setOnMouseClicked(e -> selectTab(tab));
        return item;
    }

    private VBox buildContentColumn() {
        VBox col = new VBox(0);
        HBox.setHgrow(col, Priority.ALWAYS);

        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 12, 10, 20));
        Label title = new Label("Soundboard");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        close.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        close.setFocusTraversable(false);
        close.setOnAction(e -> hide());
        header.getChildren().addAll(title, spacer, close);

        StackPane.setAlignment(content, Pos.TOP_LEFT);
        VBox.setVgrow(content, Priority.ALWAYS);
        content.setPadding(new Insets(4, 16, 16, 16));

        col.getChildren().addAll(header, content);
        return col;
    }

    private void selectTab(Tab tab) {
        currentTab = tab;
        showingGrid = false;
        for (var node : navBox.getChildren()) {
            if (node instanceof VBox v) {
                v.getStyleClass().removeAll("nav-active", "nav-inactive");
                if (tab.equals(v.getUserData())) { v.getStyleClass().add("nav-active"); }
                else v.getStyleClass().add("nav-inactive");
            }
        }
        switch (tab) {
            case SERVER   -> showServerTab();
            case MINE     -> showMineTab();
            case ADVANCED -> showAdvancedTab();
        }
    }

    // ── Permission / state helpers ──────────────────────────────────────────────

    private boolean canUse() {
        return App.getPermissionManager().hasInChannel(channelId, Permission.USE_SOUNDBOARD);
    }

    private boolean canManageServer() {
        return App.getPermissionManager().hasInChannel(channelId, Permission.MANAGE_SERVER_SOUNDBOARD);
    }

    private boolean isDeafened() {
        return !AppState.speakerEnabledProperty().get() || !AppState.serverSpeakerEnabledProperty().get();
    }

    // ── Server tab ───────────────────────────────────────────────────────────────

    private void showServerTab() {
        renderServerGrid();
        refreshAll();
    }

    private void showServerTabFromCache() {
        renderServerGrid();
    }

    private void renderServerGrid() {
        GridPane grid = newGrid();
        boolean manage = canManageServer();
        boolean playable = canUse() && !isDeafened();
        log.info("[Soundboard] showServerTab — canUse={} isDeafened={} canManage={} playable={}", canUse(), isDeafened(), manage, playable);

        SoundboardSummary[] bySlot = new SoundboardSummary[SLOT_COUNT];
        for (SoundboardSummary sb : SoundboardCache.getServer()) {
            if (sb.getSlotIndex() >= 0 && sb.getSlotIndex() < SLOT_COUNT) bySlot[sb.getSlotIndex()] = sb;
        }

        for (int i = 0; i < SLOT_COUNT; i++) {
            final int slot = i;
            SoundboardSummary sb = bySlot[i];
            if (sb != null) {
                grid.add(filledSlot(sb.getName(), sb.getEmoji(), playable,
                        () -> triggerPlay(sb),
                        manage ? () -> showEditForm(sb) : null,
                        manage ? () -> deleteServer(sb) : null), i % COLS, i / COLS);
            } else {
                grid.add(emptySlot(manage ? () -> showAddForm(true, slot) : null), i % COLS, i / COLS);
            }
        }
        setContent(grid, manage
                ? "Server sounds — click to play, right-click to edit or remove, + to add (max 20 MB)"
                : "Server sounds — click to play");
        showingGrid = true;
    }

    private void refreshAll() {
        SoundboardService svc = serverService();
        if (svc == null) return;
        App.getServices().getExecutor().submit(() -> {
            try {
                List<SoundboardSummary> list = svc.list();
                Platform.runLater(() -> SoundboardCache.setServer(list));
            } catch (Exception ignored) {}
        });
    }

    private void triggerPlay(SoundboardSummary sb) {
        triggerPlayById(sb.getSoundboardId());
    }

    private void triggerPlayById(UUID soundboardId) {
        if (!canUse() || isDeafened()) return;
        App.getServices().installation().getWsClient()
                .send(WsMessageType.SOUNDBOARD_PLAY, Map.of("soundboardId", soundboardId.toString()));
    }

    private void deleteServer(SoundboardSummary sb) {
        SoundboardService svc = serverService();
        if (svc == null) return;
        App.getServices().getExecutor().submit(() -> {
            try { svc.delete(sb.getSoundboardId()); refreshAll(); }
            catch (Exception ex) { notifyError("Couldn't remove sound: " + HttpStatusException.extractMessage(ex)); }
        });
    }

    // ── Mine tab ───────────────────────────────────────────────────────────────

    private void showMineTab() {
        renderMineGrid();
    }

    private void renderMineGrid() {
        GridPane grid = newGrid();
        boolean playable = canUse() && !isDeafened();

        PersonalSoundboardEntry[] bySlot = new PersonalSoundboardEntry[SLOT_COUNT];
        for (PersonalSoundboardEntry e : PersonalSoundboardStore.getInstance().list()) {
            if (e.getSlotIndex() >= 0 && e.getSlotIndex() < SLOT_COUNT) bySlot[e.getSlotIndex()] = e;
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            final int slot = i;
            PersonalSoundboardEntry entry = bySlot[i];
            if (entry != null) {
                grid.add(filledSlot(entry.getName(), entry.getEmoji(), playable,
                        () -> triggerPlayById(entry.getId()),
                        () -> showEditForm(entry),
                        () -> deletePersonal(entry)), i % COLS, i / COLS);
            } else {
                grid.add(emptySlot(() -> showAddForm(false, slot)), i % COLS, i / COLS);
            }
        }

        setContent(grid, "Your private sounds — stored locally on this device, available on every server. Right-click to edit or remove.");
        showingGrid = true;
    }

    private void deletePersonal(PersonalSoundboardEntry entry) {
        App.getServices().getExecutor().submit(() -> {
            try {
                PersonalSoundboardStore.getInstance().delete(entry.getId());
                Platform.runLater(this::renderMineGrid);
            } catch (Exception ex) {
                notifyError("Couldn't remove sound: " + ex.getMessage());
            }
        });
    }

    /**
     * Opens a native file chooser so it appears on top and the main window stays
     * maximized. The {@code dialog} function is handed the owner window to use.
     *
     * <p>On Windows/macOS we simply own the chooser to this popup, which puts it
     * above the popup without disturbing the main window.
     *
     * <p>On Linux this popup is an override-redirect window that draws above a
     * native chooser, and owning the chooser to any window in the main stage's
     * chain un-maximizes that stage. So we hide the popup for the duration of the
     * (modal) chooser and open it with no owner, then bring the popup back
     * exactly where it was. The temporary hide must not tear down our listeners,
     * so {@code onHidden} is detached around it.
     */
    private File pickFile(java.util.function.Function<javafx.stage.Window, File> dialog) {
        if (!IS_LINUX) {
            setAutoHide(false);
            try {
                return dialog.apply(this);
            } finally {
                setAutoHide(true);
            }
        }
        javafx.event.EventHandler<javafx.stage.WindowEvent> onHidden = getOnHidden();
        javafx.stage.Window owner = getOwnerWindow();
        double ax = getAnchorX(), ay = getAnchorY();
        setOnHidden(null);
        hide();
        try {
            return dialog.apply(null);
        } finally {
            if (owner != null) show(owner, ax, ay);
            setOnHidden(onHidden);
        }
    }

    private void exportSoundboards() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export personal soundboard");
        fc.setInitialFileName("soundboard.zip");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP archive", "*.zip"));
        File f = pickFile(owner -> fc.showSaveDialog(owner));
        if (f == null) return;
        App.getServices().getExecutor().submit(() -> {
            try {
                PersonalSoundboardStore.getInstance().exportZip(f.toPath());
            } catch (Exception ex) {
                notifyError("Export failed: " + ex.getMessage());
            }
        });
    }

    private void importSoundboards() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Import soundboard ZIP");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP archive", "*.zip"));
        File f = pickFile(owner -> fc.showOpenDialog(owner));
        if (f == null) return;
        App.getServices().getExecutor().submit(() -> {
            try {
                PersonalSoundboardStore.getInstance().importZip(f.toPath());
                Platform.runLater(this::renderMineGrid);
            } catch (Exception ex) {
                notifyError("Import failed: " + ex.getMessage());
            }
        });
    }

    // ── Add form ───────────────────────────────────────────────────────────────

    /**
     * Opens the "add sound" form as a real modal ({@link AddSoundModal}). A JavaFX
     * {@link Popup} cannot hold OS keyboard focus on Windows, so an editable field
     * can't live inside this popup — the modal lives in the main stage instead.
     * The popup is hidden while the modal is up and reopened where it was when the
     * modal closes (by Add, Cancel or the X). The modal refreshes the cache/store
     * before closing, so the reopened grid shows the new sound immediately.
     */
    private void showAddForm(boolean server, int slot) {
        openSoundModal(server ? Tab.SERVER : Tab.MINE, () -> new AddSoundModal(server, slot));
    }

    private void showEditForm(SoundboardSummary sb) {
        openSoundModal(Tab.SERVER, () -> AddSoundModal.editServer(sb));
    }

    private void showEditForm(PersonalSoundboardEntry entry) {
        openSoundModal(Tab.MINE, () -> AddSoundModal.editPersonal(entry));
    }

    private void openSoundModal(Tab tab, java.util.function.Supplier<AddSoundModal> modalFactory) {
        javafx.stage.Window owner = getOwnerWindow();
        double px = getX(), py = getY();
        javafx.event.EventHandler<javafx.stage.WindowEvent> onHidden = getOnHidden();
        setOnHidden(null);
        hide();

        @SuppressWarnings("unchecked")
        javafx.beans.value.ChangeListener<Boolean>[] reopen = new javafx.beans.value.ChangeListener[1];
        reopen[0] = (obs, was, showing) -> {
            if (showing) return;
            App.modalPane.displayProperty().removeListener(reopen[0]);
            if (owner != null) {
                show(owner, px, py);
                setOnHidden(onHidden);
                selectTab(tab);
            }
        };
        App.modalPane.displayProperty().addListener(reopen[0]);

        App.showModal(modalFactory.get());
    }

    // ── Advanced tab ───────────────────────────────────────────────────────────

    private void showAdvancedTab() {
        VBox box = new VBox(18);
        box.setPadding(new Insets(8, 4, 4, 4));

        double initialPct = Math.min(100.0, UserSettings.getInstance().getSoundboardVolume() * 100);
        Slider slider = new Slider(0, 100, initialPct);
        slider.setShowTickLabels(true);
        slider.setShowTickMarks(true);
        slider.setMajorTickUnit(25);
        slider.setBlockIncrement(5);
        slider.setFocusTraversable(false);

        Label valLabel = new Label(Math.round(slider.getValue()) + "%");
        valLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        valLabel.setMinWidth(36);
        valLabel.setPrefWidth(36);

        slider.valueProperty().addListener((o, a, b) -> {
            valLabel.setText(Math.round(b.doubleValue()) + "%");
            App.getWebrtcRoomClient().setSoundboardVolume((float) (b.doubleValue() / 100.0));
        });

        Label volHint = new Label("Your own setting — applies to every soundboard you hear, including your own. Other members can pick their own level.");
        volHint.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 11px;");
        volHint.setWrapText(true);

        HBox sliderRow = new HBox(10, slider, valLabel);
        sliderRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);

        Button stop = new Button("Stop my sounds", new FontIcon(MaterialDesignS.STOP));
        stop.getStyleClass().addAll(Styles.DANGER, Styles.SMALL);
        stop.setFocusTraversable(false);
        stop.setOnAction(e -> {
            App.getServices().installation().getWsClient().send(WsMessageType.SOUNDBOARD_STOP, Map.of());
            if (App.getUser() != null) App.getWebrtcRoomClient().stopSoundboardsBy(App.getUser().getUserId());
        });

        Button exportBtn = new Button("Export ZIP", new FontIcon(MaterialDesignF.FILE_EXPORT));
        exportBtn.getStyleClass().add(Styles.SMALL);
        exportBtn.setFocusTraversable(false);
        exportBtn.setOnAction(e -> exportSoundboards());

        Button importBtn = new Button("Import ZIP", new FontIcon(MaterialDesignF.FILE_IMPORT));
        importBtn.getStyleClass().add(Styles.SMALL);
        importBtn.setFocusTraversable(false);
        importBtn.setOnAction(e -> importSoundboards());

        Label transferHint = new Label("Back up or share your personal sounds. Export packs all slots into a ZIP; import merges a ZIP into your current slots (imported slot overrides local).");
        transferHint.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 11px;");
        transferHint.setWrapText(true);

        HBox transferRow = new HBox(8, importBtn, exportBtn);

        box.getChildren().addAll(
                new VBox(6, smallLabel("Soundboard volume"), sliderRow, volHint),
                new VBox(6, smallLabel("Playback"), stop),
                new VBox(6, smallLabel("Personal sounds"), transferRow, transferHint));
        content.getChildren().setAll(box);
    }

    // ── Slot widgets ───────────────────────────────────────────────────────────

    private GridPane newGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        return grid;
    }

    private void setContent(GridPane grid, String hintText) {
        Label hint = new Label(hintText);
        hint.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 11px; -fx-padding: 0 0 8 0;");
        hint.setWrapText(true);
        VBox box = new VBox(2, hint, grid);
        content.getChildren().setAll(box);
    }

    private StackPane filledSlot(String name, String emoji, boolean playable,
                                 Runnable onPlay, Runnable onEdit, Runnable onRemove) {
        log.info("[Soundboard] slot '{}' created — playable={}", name, playable);

        HBox content = new HBox(6);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(4, 8, 4, 8));
        content.setMouseTransparent(true);

        if (emoji != null && !emoji.isBlank()) {
            List<Node> nodes = TextUtils.convertToTextAndImageNodes(emoji, 16);
            if (!nodes.isEmpty()) {
                Node n = nodes.get(0);
                if (n instanceof javafx.scene.image.ImageView iv) {
                    iv.setFitWidth(16);
                    iv.setFitHeight(16);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                } else if (n instanceof javafx.scene.text.Text t) {
                    t.setStyle("-fx-font-size: 16px;");
                }
                content.getChildren().add(n);
            }
        }

        Label nameLabel = new Label(name);
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(SLOT_W - 12);
        nameLabel.setStyle("-fx-font-size: 11px;");
        content.getChildren().add(nameLabel);

        StackPane slot = new StackPane(content);
        slot.setMinSize(SLOT_W, SLOT_H);
        slot.setMaxSize(SLOT_W, SLOT_H);
        slot.getStyleClass().add("soundboard-slot");
        slot.setFocusTraversable(false);

        if (!playable) {
            slot.setOpacity(0.45);
            slot.setStyle("-fx-cursor: default;");
            Tooltip.install(slot, new Tooltip(isDeafened()
                    ? "You can't play sounds while deafened"
                    : "You don't have permission to use the soundboard here"));
        }

        slot.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && playable && onPlay != null) {
                log.info("[Soundboard] slot '{}' clicked", name);
                onPlay.run();
            }
        });

        if (onEdit != null || onRemove != null) {
            ContextMenu menu = new ContextMenu();
            if (onEdit != null) {
                MenuItem editItem = new MenuItem("Edit sound");
                editItem.setOnAction(e -> onEdit.run());
                menu.getItems().add(editItem);
            }
            if (onRemove != null) {
                MenuItem removeItem = new MenuItem("Remove sound");
                removeItem.setOnAction(e -> onRemove.run());
                menu.getItems().add(removeItem);
            }
            slot.setOnContextMenuRequested(e -> menu.show(slot, e.getScreenX(), e.getScreenY()));
        }

        return slot;
    }

    private StackPane emptySlot(Runnable onAdd) {
        Button b = new Button(null, new FontIcon(MaterialDesignP.PLUS));
        b.setMinSize(SLOT_W, SLOT_H);
        b.setMaxSize(SLOT_W, SLOT_H);
        b.getStyleClass().addAll("soundboard-slot", "soundboard-slot-empty");
        b.setFocusTraversable(false);
        if (onAdd != null) {
            b.setOnAction(e -> onAdd.run());
        } else {
            b.setDisable(true);
        }
        return new StackPane(b);
    }

    private Label smallLabel(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        return l;
    }

    // ── Misc helpers ───────────────────────────────────────────────────────────

    private SoundboardService serverService() {
        try { return App.getServices().installation().getSoundboardService(); }
        catch (Exception e) { return null; }
    }

    private void notifyError(String message) {
        Platform.runLater(() ->
                new CustomNotification("Soundboard Error", message, new FontIcon(MaterialDesignC.CLOSE_CIRCLE_OUTLINE)).showNotification());
    }
}
