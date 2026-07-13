package komm.ui.emojis;

import io.github.b077as.emojifx.Emoji;
import io.github.b077as.emojifx.EmojiData;
import io.github.b077as.emojifx.EmojiSkinTone;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class EmojiPickerPopup {

    private static final double POPUP_WIDTH = 360;
    private static final double POPUP_HEIGHT = 440;
    private static final double H_PADDING = 10;
    private static final int COLS = 8;
    private static final double CELL_GAP = 2;
    public static final double EMOJI_SIZE = 26;
    private static final int BATCH_SIZE = 40;

    private double emojiBtnSize = 38;

    private static final String[][] CATEGORIES = {
            {"\u2665\uFE0E", "Smileys & Emotion"},
            {"\uD83E\uDDCD", "People & Body"},
            {"\uD83D\uDC3B", "Animals & Nature"},
            {"\uD83C\uDF4E", "Food & Drink"},
            {"\uD83E\uDD4E", "Activities"},
            {"\uD83C\uDFE0", "Travel & Places"},
            {"\uD83D\uDCBE", "Objects"},
            {"\uD83C\uDD97", "Symbols"},
            {"\uD83C\uDFC1", "Flags"},
    };

    private static final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "emoji-loader");
                t.setDaemon(true);
                return t;
            });

    private static final Map<String, List<Emoji>> EMOJI_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>());

    // Flat list of all base emojis across every category, used for search.
    // Built once during pre-warming, never modified after that.
    private static List<Emoji> ALL_BASE_EMOJIS = null;

    static {
        prewarmAll();
    }

    private final Popup popup = new Popup();
    private final VBox root = new VBox();

    private TextField searchField;
    private TilePane emojiGrid;
    private ScrollPane gridScroll;
    private HBox categoryBar;
    private Label loadingLabel;
    private Label noResultsLabel;

    private String activeCategory = CATEGORIES[0][1];
    private Consumer<Emoji> onEmojiSelected;

    private Task<?> currentLoadTask;
    private int currentGeneration = 0;
    private boolean hasPopulatedOnce = false;

    public EmojiPickerPopup() {
        buildUI();
        popup.setAutoHide(true);
        popup.setConsumeAutoHidingEvents(false);
        popup.setHideOnEscape(true);
        popup.getContent().add(root);
        popup.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (!isShowing) {
                searchField.clear();
                selectCategory(CATEGORIES[0][1]);
                highlightCategory((Button) categoryBar.getChildren().get(0));
            }
        });
        loadCategory(activeCategory);
    }

    // ── pre-warming ───────────────────────────────────────────────────────────

    private static void prewarmAll() {
        executor.submit(() -> {
            List<Emoji> all = new ArrayList<>();
            for (String[] cat : CATEGORIES) {
                all.addAll(buildEmojiCache(cat[1]));
            }
            ALL_BASE_EMOJIS = Collections.unmodifiableList(all);
        });
    }

    private static List<Emoji> buildEmojiCache(String groupName) {
        return EMOJI_CACHE.computeIfAbsent(groupName, g ->
                EmojiData.getEmojiCollection().stream()
                        .filter(e -> g.equalsIgnoreCase(e.getCategory()))
                        .filter(EmojiPickerPopup::isBaseEmoji)
                        .sorted(Comparator.comparingInt(Emoji::getSortOrder))
                        .collect(Collectors.toList())
        );
    }

    private static boolean isBaseEmoji(Emoji e) {
        String name = e.getName();
        if (name == null) return true;
        String lower = name.toLowerCase();
        return !lower.contains("light skin tone")
                && !lower.contains("medium-light skin tone")
                && !lower.contains("medium skin tone")
                && !lower.contains("medium-dark skin tone")
                && !lower.contains("dark skin tone");
    }

    // ── search against our own cache ──────────────────────────────────────────

    /**
     * Searches the flat ALL_BASE_EMOJIS list by matching the query against
     * getName() — the exact same field used to build the tooltip — so results
     * are always consistent with what the user sees on hover.
     * Falls back to per-category cache iteration if ALL_BASE_EMOJIS isn't
     * ready yet (very first search while pre-warming is still running).
     */
    private static List<Emoji> searchOwn(String query) {
        String q = query.toLowerCase().replace(":", "").replace("_", " ").trim();

        Iterable<Emoji> source = ALL_BASE_EMOJIS != null
                ? ALL_BASE_EMOJIS
                : EMOJI_CACHE.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        List<Emoji> exact = new ArrayList<>();
        List<Emoji> startsWith = new ArrayList<>();
        List<Emoji> contains = new ArrayList<>();

        for (Emoji e : source) {
            if (e.getName() == null) continue;
            String name = e.getName().toLowerCase().replace("_", " ");
            if (name.equals(q)) exact.add(e);
            else if (name.startsWith(q)) startsWith.add(e);
            else if (name.contains(q)) contains.add(e);
        }

        List<Emoji> result = new ArrayList<>(exact.size() + startsWith.size() + contains.size());
        result.addAll(exact);
        result.addAll(startsWith);
        result.addAll(contains);
        return result;
    }

    // ── public API ────────────────────────────────────────────────────────────

    public void setOnEmojiSelected(Consumer<Emoji> handler) {
        this.onEmojiSelected = handler;
    }

    public void attachToField(EmojiTextField field) {
        setOnEmojiSelected(field::insertEmoji);

        Button btn = field.getPickerButton();
        if (btn == null) return;

        btn.setOnAction(e -> {
            if (popup.isShowing()) {
                popup.hide();
            } else {
                javafx.geometry.Bounds b = btn.localToScreen(btn.getBoundsInLocal());
                if (b != null) {
                    double x = b.getMinX();
                    double y = b.getMinY() - POPUP_HEIGHT - 4;
                    if (y < 0) y = b.getMaxY() + 4;
                    show(btn.getScene().getWindow(), x, y);
                }
            }
        });
    }

    public void show(Window owner, double x, double y) {
        popup.show(owner, x, y);
        root.requestFocus();
    }

    public void hide() {
        popup.hide();
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        root.setPrefWidth(POPUP_WIDTH);
        root.setMaxWidth(POPUP_WIDTH);
        root.setPrefHeight(POPUP_HEIGHT);
        root.setMaxHeight(POPUP_HEIGHT);
        root.setAlignment(Pos.TOP_CENTER);
        root.setPadding(new Insets(8, H_PADDING, 8, H_PADDING));
        root.setSpacing(10);
        root.getStyleClass().add("custom-pop-up");
        root.setFocusTraversable(true);

        searchField = new TextField();
        searchField.setPromptText("Search emoji...");
        searchField.setMaxWidth(Double.MAX_VALUE);
        searchField.textProperty().addListener((obs, old, val) -> onSearch(val));

        categoryBar = new HBox(2);
        categoryBar.getStyleClass().add("emoji-category-bar");
        categoryBar.setAlignment(Pos.CENTER);
        categoryBar.setMaxWidth(Double.MAX_VALUE);

        for (String[] cat : CATEGORIES) {
            Button catBtn = new Button(cat[0]);
            catBtn.getStyleClass().add("emoji-category-btn");
            catBtn.setFocusTraversable(false);
            catBtn.setUserData(cat[1]);
            catBtn.setOnAction(e -> {
                selectCategory(cat[1]);
                highlightCategory(catBtn);
                root.requestFocus();
            });
            categoryBar.getChildren().add(catBtn);
        }
        highlightCategory((Button) categoryBar.getChildren().get(0));

        loadingLabel = new Label("Loading...");
        loadingLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        loadingLabel.setVisible(false);
        loadingLabel.setManaged(false);

        noResultsLabel = new Label("No emoji found");
        noResultsLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        noResultsLabel.setVisible(false);
        noResultsLabel.setManaged(false);

        emojiGrid = new TilePane();
        emojiGrid.getStyleClass().add("emoji-grid");
        emojiGrid.setPrefColumns(COLS);
        emojiGrid.setHgap(CELL_GAP);
        emojiGrid.setVgap(CELL_GAP);
        emojiGrid.setAlignment(Pos.TOP_CENTER);
        emojiGrid.setPadding(new Insets(5, 5, 0, 5));
        emojiGrid.setMinHeight(POPUP_HEIGHT - 120);

        gridScroll = new ScrollPane(emojiGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        gridScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(gridScroll, Priority.ALWAYS);
        gridScroll.setMaxWidth(Double.MAX_VALUE);

        gridScroll.widthProperty().addListener((obs, oldW, newW) -> {
            recalcButtonSize(newW.doubleValue());
        });

        root.getChildren().addAll(searchField, categoryBar, loadingLabel, noResultsLabel, gridScroll);
    }

    private void recalcButtonSize(double scrollPaneWidth) {
        double scrollBarWidth = 12;
        Insets gridPadding = emojiGrid.getPadding();
        double available = scrollPaneWidth
                - scrollBarWidth
                - gridPadding.getLeft()
                - gridPadding.getRight();

        double newSize = Math.floor((available - (COLS - 1) * CELL_GAP) / COLS);
        if (newSize <= 0 || newSize == emojiBtnSize) return;

        emojiBtnSize = newSize;
        emojiGrid.setPrefTileWidth(emojiBtnSize);
        emojiGrid.setPrefTileHeight(emojiBtnSize);

        for (Node node : emojiGrid.getChildren()) {
            if (node instanceof Button btn) {
                btn.setMinSize(emojiBtnSize, emojiBtnSize);
                btn.setMaxSize(emojiBtnSize, emojiBtnSize);
                btn.setPrefSize(emojiBtnSize, emojiBtnSize);
            }
        }
    }

    // ── category / search logic ───────────────────────────────────────────────

    private void selectCategory(String category) {
        activeCategory = category;
        if (searchField.getText().isBlank()) {
            loadCategory(activeCategory);
        } else {
            searchField.clear();
        }
    }

    private void highlightCategory(Button selected) {
        for (Node n : categoryBar.getChildren()) {
            if (n instanceof Button b) b.getStyleClass().remove("selected");
        }
        selected.getStyleClass().add("selected");
    }

    private void onSearch(String query) {
        if (query == null || query.isBlank()) {
            setNoResults(false);
            loadCategory(activeCategory);
            return;
        }

        cancelCurrentTask();
        int gen = ++currentGeneration;

        // Clear synchronously — no fade race
        emojiGrid.getChildren().clear();
        gridScroll.setVvalue(0);
        setLoading(true);
        setNoResults(false);

        Task<List<Emoji>> task = new Task<>() {
            @Override
            protected List<Emoji> call() {
                return searchOwn(query.trim());
            }
        };

        task.setOnSucceeded(e -> {
            if (gen != currentGeneration) return;
            setLoading(false);
            List<Emoji> results = task.getValue();
            if (results.isEmpty()) {
                setNoResults(true);
            } else {
                startBatchInsert(results, 0, gen);
            }
        });

        task.setOnFailed(e -> {
            if (gen != currentGeneration) return;
            setLoading(false);
            setNoResults(true);
        });

        currentLoadTask = task;
        executor.submit(task);
    }

    private void loadCategory(String groupName) {
        cancelCurrentTask();
        int gen = ++currentGeneration;

        List<Emoji> cached = EMOJI_CACHE.get(groupName);
        if (cached != null) {
            fadeAndClear(() -> startBatchInsert(cached, 0, gen));
            return;
        }

        fadeAndClear(() -> setLoading(true));

        Task<List<Emoji>> task = new Task<>() {
            @Override
            protected List<Emoji> call() {
                return buildEmojiCache(groupName);
            }
        };

        task.setOnSucceeded(e -> {
            setLoading(false);
            if (gen == currentGeneration) {
                startBatchInsert(task.getValue(), 0, gen);
            }
        });
        task.setOnFailed(e -> setLoading(false));

        currentLoadTask = task;
        executor.submit(task);
    }

    // ── batched FX-thread insertion ───────────────────────────────────────────

    private void startBatchInsert(List<Emoji> emojis, int from, int gen) {
        if (gen != currentGeneration) return;
        if (from >= emojis.size()) return;

        int to = Math.min(from + BATCH_SIZE, emojis.size());
        for (int i = from; i < to; i++) {
            emojiGrid.getChildren().add(buildEmojiButton(emojis.get(i)));
        }

        if (from == 0) {
            gridScroll.setVvalue(0);
            if (hasPopulatedOnce) {
                FadeTransition ft = new FadeTransition(Duration.millis(80), emojiGrid);
                ft.setFromValue(0.0);
                ft.setToValue(1.0);
                ft.play();
            } else {
                emojiGrid.setOpacity(1.0);
                hasPopulatedOnce = true;
            }
        }

        if (to < emojis.size()) {
            Platform.runLater(() -> startBatchInsert(emojis, to, gen));
        }
    }

    private void fadeAndClear(Runnable loader) {
        FadeTransition ft = new FadeTransition(Duration.millis(60), emojiGrid);
        ft.setFromValue(emojiGrid.getOpacity());
        ft.setToValue(0.0);
        ft.setOnFinished(e -> {
            emojiGrid.getChildren().clear();
            loader.run();
        });
        ft.play();
    }

    // ── button factory ────────────────────────────────────────────────────────

    private Button buildEmojiButton(Emoji emoji) {
        Button btn = new Button();
        btn.getStyleClass().add("emoji-btn");
        btn.setMinSize(emojiBtnSize, emojiBtnSize);
        btn.setMaxSize(emojiBtnSize, emojiBtnSize);
        btn.setPrefSize(emojiBtnSize, emojiBtnSize);
        btn.setFocusTraversable(false);

        List<Node> nodes = TextUtils.convertToTextAndImageNodes(emoji.character(), EMOJI_SIZE);
        if (!nodes.isEmpty()) {
            Node n = nodes.get(0);
            if (n instanceof ImageView iv) {
                iv.setFitWidth(EMOJI_SIZE);
                iv.setFitHeight(EMOJI_SIZE);
                iv.setPreserveRatio(true);
                btn.setGraphic(iv);
            } else if (n instanceof Text t) {
                btn.setText(t.getText());
            }
        } else {
            btn.setText(emoji.character());
        }

        if (emoji.getName() != null) {
            btn.setTooltip(new Tooltip(
                    ":" + emoji.getName().replace(" ", "_").toLowerCase() + ":"
            ));
        }

        btn.setOnAction(e -> {
            if (onEmojiSelected != null) onEmojiSelected.accept(emoji);
            root.requestFocus();
        });

        ContextMenu cm = buildSkinToneMenu(emoji);
        if (cm != null) btn.setContextMenu(cm);

        return btn;
    }

    private ContextMenu buildSkinToneMenu(Emoji baseEmoji) {
        Map<String, Emoji> variants = baseEmoji.getSkinVariationMap();
        if (variants == null || variants.isEmpty()) return null;

        List<MenuItem> items = new ArrayList<>();

        for (EmojiSkinTone tone : EmojiSkinTone.values()) {
            if (tone == EmojiSkinTone.NO_SKIN_TONE) continue;

            Emoji variant = EmojiData.emojiWithTone(baseEmoji, tone);
            if (variant == null || variant.character().equals(baseEmoji.character())) continue;

            List<Node> nodes = TextUtils.convertToTextAndImageNodes(variant.character(), EMOJI_SIZE);
            Node graphic = nodes.isEmpty() ? null : nodes.get(0);
            if (graphic instanceof ImageView iv) {
                iv.setFitWidth(EMOJI_SIZE);
                iv.setFitHeight(EMOJI_SIZE);
                iv.setPreserveRatio(true);
            }

            String label = tone.name().replace("_", " ").toLowerCase();
            label = Character.toUpperCase(label.charAt(0)) + label.substring(1);

            MenuItem item = new MenuItem(label, graphic);
            item.setOnAction(e -> {
                if (onEmojiSelected != null) onEmojiSelected.accept(variant);
            });
            items.add(item);
        }

        if (items.isEmpty()) return null;

        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(items);
        return menu;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void cancelCurrentTask() {
        if (currentLoadTask != null && currentLoadTask.isRunning()) {
            currentLoadTask.cancel();
        }
        currentLoadTask = null;
    }

    private void setLoading(boolean loading) {
        loadingLabel.setVisible(loading);
        loadingLabel.setManaged(loading);
    }

    private void setNoResults(boolean show) {
        noResultsLabel.setVisible(show);
        noResultsLabel.setManaged(show);
    }

    public void setOnHidden(EventHandler<WindowEvent> handler) {
        popup.setOnHidden(handler);
    }
}