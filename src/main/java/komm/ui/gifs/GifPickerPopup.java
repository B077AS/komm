package komm.ui.gifs;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;
import komm.App;
import komm.model.dto.summary.GifPage;
import komm.model.dto.summary.GifResult;
import komm.ui.utils.IconColorUtil;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class GifPickerPopup {

    private static final double POPUP_WIDTH = 490;
    private static final double POPUP_HEIGHT = 460;
    private static final double SIDEBAR_WIDTH = 44;
    private static final int COLUMN_COUNT = 2;
    private static final double COLUMN_GAP = 6;
    private static final double CELL_WIDTH = 200;
    private static final double PRELOAD_THRESHOLD = 0.80;

    private static final ExecutorService executor =
            Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "gif-picker-loader");
                t.setDaemon(true);
                return t;
            });

    private final Popup popup = new Popup();
    private final HBox root = new HBox();

    // ── Sidebar ───────────────────────────────────────────────────────────────
    private StackPane searchTabBtn;
    private StackPane favoritesTabBtn;

    // ── Content widgets ───────────────────────────────────────────────────────
    private TextField searchField;
    private HBox masonryGrid;
    private VBox[] columns;
    private double[] columnHeights;
    private ScrollPane gridScroll;
    private Label loadingLabel;
    private Label noResultsLabel;
    private Label noFavoritesLabel;
    private HBox bottomSpinnerBox;

    // ── State ─────────────────────────────────────────────────────────────────
    private enum Tab { SEARCH, FAVORITES }
    private Tab activeTab = Tab.SEARCH;

    private final Set<String> favoritedIds = new HashSet<>();
    private final List<GifResult> favoriteGifs = new ArrayList<>();
    private boolean favoritesLoaded = false;

    private PauseTransition searchDebounce;
    private Task<?> currentTask;
    private int currentGeneration = 0;
    private String lastQuery = null;
    private int currentPage = 1;
    private boolean hasMore = false;
    private boolean loadingMore = false;
    private ChangeListener<Number> scrollListener;

    private Consumer<GifResult> onGifSelected;

    public void setOnGifSelected(Consumer<GifResult> callback) {
        this.onGifSelected = callback;
    }

    public GifPickerPopup() {
        buildUI();
        popup.setAutoHide(true);
        popup.setConsumeAutoHidingEvents(false);
        popup.setHideOnEscape(true);
        popup.getContent().add(root);

        popup.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                activeTab = Tab.SEARCH;
                updateSidebarStyles();
                searchField.setVisible(true);
                searchField.setManaged(true);
                searchField.clear();
                setNoFavorites(false);
                favoritesLoaded = false;
                favoritedIds.clear();
                favoriteGifs.clear();
                loadFavoritesAsync();
                loadTrending();
            }
        });
    }

    public void show(Window owner, double x, double y) { popup.show(owner, x, y); }
    public void hide() { popup.hide(); }
    public boolean isShowing() { return popup.isShowing(); }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        root.setPrefWidth(POPUP_WIDTH);
        root.setMaxWidth(POPUP_WIDTH);
        root.setPrefHeight(POPUP_HEIGHT);
        root.setMaxHeight(POPUP_HEIGHT);
        root.getStyleClass().add("custom-pop-up");

        VBox sidebar = buildSidebar();
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setMinWidth(SIDEBAR_WIDTH);
        sidebar.setMaxWidth(SIDEBAR_WIDTH);

        VBox contentPanel = buildContentPanel();
        HBox.setHgrow(contentPanel, Priority.ALWAYS);

        root.getChildren().addAll(sidebar, contentPanel);
    }

    private VBox buildSidebar() {
        VBox sidebar = new VBox(4);
        sidebar.setAlignment(Pos.TOP_CENTER);
        sidebar.setPadding(new Insets(10, 0, 10, 0));
        sidebar.setStyle(
            "-fx-background-color: -color-bg-subtle;" +
            "-fx-background-radius: 8px 0 0 8px;" +
            "-fx-border-color: transparent -color-border-default transparent transparent;" +
            "-fx-border-width: 0 1px 0 0;"
        );

        FontIcon searchIcon = new FontIcon(MaterialDesignM.MAGNIFY);
        searchTabBtn = buildTabIconBtn(searchIcon);
        searchTabBtn.setOnMouseClicked(e -> switchToSearch());

        FontIcon starIcon = new FontIcon(MaterialDesignS.STAR_OUTLINE);
        favoritesTabBtn = buildTabIconBtn(starIcon);
        favoritesTabBtn.setOnMouseClicked(e -> switchToFavorites());

        sidebar.getChildren().addAll(searchTabBtn, favoritesTabBtn);
        updateSidebarStyles();
        return sidebar;
    }

    private StackPane buildTabIconBtn(FontIcon icon) {
        StackPane btn = new StackPane(icon);
        btn.setPrefSize(36, 36);
        btn.setMaxSize(36, 36);
        btn.setAlignment(Pos.CENTER);
        btn.setStyle("-fx-background-radius: 6px; -fx-cursor: hand;");
        return btn;
    }

    private VBox buildContentPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(8, 8, 6, 8));
        panel.setAlignment(Pos.TOP_CENTER);

        searchField = new TextField();
        searchField.setPromptText("Search GIFs...");
        searchField.setMaxWidth(Double.MAX_VALUE);

        searchDebounce = new PauseTransition(Duration.millis(400));
        searchField.textProperty().addListener((obs, old, val) -> {
            searchDebounce.setOnFinished(e -> {
                if (val == null || val.isBlank()) loadTrending();
                else onSearch(val.trim());
            });
            searchDebounce.playFromStart();
        });

        loadingLabel = new Label("Loading...");
        loadingLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        loadingLabel.setVisible(false);
        loadingLabel.setManaged(false);

        noResultsLabel = new Label("No GIFs found");
        noResultsLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        noResultsLabel.setVisible(false);
        noResultsLabel.setManaged(false);

        noFavoritesLabel = new Label("No favorites yet");
        noFavoritesLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        noFavoritesLabel.setVisible(false);
        noFavoritesLabel.setManaged(false);

        columns = new VBox[COLUMN_COUNT];
        columnHeights = new double[COLUMN_COUNT];
        for (int i = 0; i < COLUMN_COUNT; i++) {
            columns[i] = new VBox(COLUMN_GAP);
            columns[i].setPrefWidth(CELL_WIDTH);
            columns[i].setMinWidth(CELL_WIDTH);
            columns[i].setMaxWidth(CELL_WIDTH);
        }
        masonryGrid = new HBox(COLUMN_GAP);
        masonryGrid.setPadding(new Insets(4, 4, 0, 4));
        masonryGrid.setAlignment(Pos.TOP_CENTER);
        masonryGrid.getChildren().addAll(columns);

        ProgressIndicator moreSpinner = new ProgressIndicator();
        moreSpinner.setMaxSize(28, 28);
        bottomSpinnerBox = new HBox(moreSpinner);
        bottomSpinnerBox.setAlignment(Pos.CENTER);
        bottomSpinnerBox.setPadding(new Insets(8, 0, 8, 0));
        bottomSpinnerBox.setVisible(false);
        bottomSpinnerBox.setManaged(false);

        VBox gridContent = new VBox(masonryGrid, bottomSpinnerBox);
        gridScroll = new ScrollPane(gridContent);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        gridScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(gridScroll, Priority.ALWAYS);

        scrollListener = (obs, oldVal, newVal) -> {
            if (activeTab != Tab.SEARCH || !hasMore || loadingMore) return;
            if (newVal.doubleValue() >= PRELOAD_THRESHOLD) loadNextPage();
        };
        gridScroll.vvalueProperty().addListener(scrollListener);

        Label attribution = new Label("Powered by KLIPY");
        attribution.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 10px;");
        attribution.setAlignment(Pos.CENTER);
        attribution.setMaxWidth(Double.MAX_VALUE);

        panel.getChildren().addAll(
                searchField,
                loadingLabel, noResultsLabel, noFavoritesLabel,
                gridScroll,
                attribution
        );
        return panel;
    }

    // ── Tab switching ─────────────────────────────────────────────────────────

    private void switchToSearch() {
        if (activeTab == Tab.SEARCH) return;
        activeTab = Tab.SEARCH;
        updateSidebarStyles();
        searchField.setVisible(true);
        searchField.setManaged(true);
        setNoFavorites(false);
        searchDebounce.stop();
        startFreshLoad();
    }

    private void switchToFavorites() {
        if (activeTab == Tab.FAVORITES) return;
        cancelCurrentTask();
        activeTab = Tab.FAVORITES;
        updateSidebarStyles();
        searchField.setVisible(false);
        searchField.setManaged(false);
        setLoading(false);
        setNoResults(false);
        setBottomLoading(false);
        showFavoritesGrid();
    }

    private void updateSidebarStyles() {
        if (searchTabBtn == null || favoritesTabBtn == null) return;
        String activeBg = "-fx-background-color: -color-neutral-muted; -fx-background-radius: 6px; -fx-cursor: hand;";
        String inactiveBg = "-fx-background-color: transparent; -fx-background-radius: 6px; -fx-cursor: hand;";

        boolean searchActive = activeTab == Tab.SEARCH;
        searchTabBtn.setStyle(searchActive ? activeBg : inactiveBg);
        favoritesTabBtn.setStyle(!searchActive ? activeBg : inactiveBg);
    }

    // ── Favorites ─────────────────────────────────────────────────────────────

    private void loadFavoritesAsync() {
        executor.submit(() -> {
            try {
                List<GifResult> gifs = App.getServices().hub().getGifService().getFavorites();
                Platform.runLater(() -> {
                    if (gifs != null) {
                        for (GifResult g : gifs) {
                            favoritedIds.add(g.getId());
                            favoriteGifs.add(g);
                        }
                    }
                    favoritesLoaded = true;
                    if (activeTab == Tab.FAVORITES) showFavoritesGrid();
                });
            } catch (Exception ignored) {
                Platform.runLater(() -> {
                    favoritesLoaded = true;
                    if (activeTab == Tab.FAVORITES) showFavoritesGrid();
                });
            }
        });
    }

    private void showFavoritesGrid() {
        clearGrid();
        gridScroll.setVvalue(0);
        if (!favoritesLoaded) {
            setLoading(true);
            setNoFavorites(false);
            return;
        }
        setLoading(false);
        if (favoriteGifs.isEmpty()) {
            setNoFavorites(true);
        } else {
            setNoFavorites(false);
            for (GifResult gif : favoriteGifs) {
                StackPane cell = buildGifCell(gif);
                addCellToMasonry(cell, cell.getPrefHeight());
            }
            FadeTransition ft = new FadeTransition(Duration.millis(120), masonryGrid);
            ft.setFromValue(0.0);
            ft.setToValue(1.0);
            ft.play();
        }
    }

    private void toggleFavoriteAsync(GifResult gif, boolean add) {
        executor.submit(() -> {
            try {
                if (add) App.getServices().hub().getGifService().addFavorite(gif);
                else App.getServices().hub().getGifService().removeFavorite(gif.getId());
            } catch (Exception ignored) {}
        });
    }

    // ── Loading — fresh ───────────────────────────────────────────────────────

    private void loadTrending() {
        lastQuery = null;
        startFreshLoad();
    }

    private void onSearch(String query) {
        lastQuery = query;
        startFreshLoad();
    }

    private void startFreshLoad() {
        cancelCurrentTask();
        int gen = ++currentGeneration;
        currentPage = 1;
        hasMore = false;
        loadingMore = false;
        clearGrid();
        gridScroll.setVvalue(0);
        setLoading(true);
        setNoResults(false);
        setNoFavorites(false);
        setBottomLoading(false);

        Task<GifPage> task = new Task<>() {
            @Override
            protected GifPage call() throws Exception { return fetchPage(1); }
        };
        task.setOnSucceeded(e -> {
            if (gen != currentGeneration) return;
            setLoading(false);
            GifPage page = task.getValue();
            if (page == null || page.getItems() == null || page.getItems().isEmpty()) {
                setNoResults(true);
            } else {
                hasMore = page.isHasNext();
                appendCells(page.getItems(), true);
            }
        });
        task.setOnFailed(e -> {
            if (gen != currentGeneration) return;
            setLoading(false);
            setNoResults(true);
        });
        currentTask = task;
        executor.submit(task);
    }

    // ── Loading — next page ───────────────────────────────────────────────────

    private void loadNextPage() {
        loadingMore = true;
        setBottomLoading(true);
        int nextPage = currentPage + 1;
        int gen = currentGeneration;

        Task<GifPage> task = new Task<>() {
            @Override
            protected GifPage call() throws Exception { return fetchPage(nextPage); }
        };
        task.setOnSucceeded(e -> {
            if (gen != currentGeneration) return;
            setBottomLoading(false);
            loadingMore = false;
            GifPage page = task.getValue();
            if (page != null && page.getItems() != null && !page.getItems().isEmpty()) {
                currentPage = nextPage;
                hasMore = page.isHasNext();
                appendCells(page.getItems(), false);
            } else {
                hasMore = false;
            }
        });
        task.setOnFailed(e -> {
            if (gen != currentGeneration) return;
            setBottomLoading(false);
            loadingMore = false;
        });
        executor.submit(task);
    }

    private GifPage fetchPage(int page) throws Exception {
        if (lastQuery == null) return App.getServices().hub().getGifService().getTrending(page);
        else return App.getServices().hub().getGifService().search(lastQuery, page);
    }

    // ── Cell insertion ────────────────────────────────────────────────────────

    private void appendCells(List<GifResult> gifs, boolean fadeIn) {
        for (GifResult gif : gifs) {
            StackPane cell = buildGifCell(gif);
            addCellToMasonry(cell, cell.getPrefHeight());
        }
        if (fadeIn) {
            gridScroll.setVvalue(0);
            FadeTransition ft = new FadeTransition(Duration.millis(120), masonryGrid);
            ft.setFromValue(0.0);
            ft.setToValue(1.0);
            ft.play();
        }
    }

    private void addCellToMasonry(StackPane cell, double cellHeight) {
        int col = 0;
        for (int i = 1; i < COLUMN_COUNT; i++) {
            if (columnHeights[i] < columnHeights[col]) col = i;
        }
        columns[col].getChildren().add(cell);
        columnHeights[col] += cellHeight + COLUMN_GAP;
    }

    // ── Cell factory ──────────────────────────────────────────────────────────

    private StackPane buildGifCell(GifResult gif) {
        String displayUrl = (gif.getPreviewUrl() != null && !gif.getPreviewUrl().isBlank())
                ? gif.getPreviewUrl() : gif.getFullUrl();

        double cellHeight = (gif.getWidth() > 0 && gif.getHeight() > 0)
                ? CELL_WIDTH * ((double) gif.getHeight() / gif.getWidth())
                : CELL_WIDTH * 0.75;

        StackPane cell = new StackPane();
        cell.setPrefWidth(CELL_WIDTH);
        cell.setPrefHeight(cellHeight);
        cell.setMinWidth(CELL_WIDTH);
        cell.setMaxWidth(CELL_WIDTH);
        cell.setMinHeight(cellHeight);
        cell.setMaxHeight(cellHeight);
        cell.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 6px; -fx-cursor: hand;");

        Rectangle clip = new Rectangle(CELL_WIDTH, cellHeight);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        cell.setClip(clip);

        cell.setOnMouseClicked(e -> {
            popup.hide();
            if (onGifSelected != null) onGifSelected.accept(gif);
        });

        ImageView imageView = new ImageView();
        imageView.setFitWidth(CELL_WIDTH);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setMouseTransparent(true);
        imageView.setVisible(false);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(28, 28);
        spinner.setMouseTransparent(true);

        // ── Star button ───────────────────────────────────────────────────────
        boolean[] isFav = { favoritedIds.contains(gif.getId()) };
        StackPane starBtn = buildStarButton(gif, isFav);
        StackPane.setAlignment(starBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(starBtn, new Insets(5));
        starBtn.setOpacity(0);

        cell.getChildren().addAll(imageView, spinner, starBtn);
        cell.setAlignment(Pos.CENTER);

        cell.setOnMouseEntered(e -> starBtn.setOpacity(1));
        cell.setOnMouseExited(e -> starBtn.setOpacity(0));

        if (displayUrl == null || displayUrl.isBlank()) {
            cell.getChildren().setAll(new Text("⚠"));
            return cell;
        }

        executor.submit(() -> {
            try {
                Image image = new Image(displayUrl, 0, 0, true, true, false);
                Platform.runLater(() -> {
                    if (image.isError()) {
                        cell.getChildren().setAll(new Text("⚠"));
                        return;
                    }
                    imageView.setImage(image);
                    imageView.setVisible(true);
                    cell.getChildren().remove(spinner);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> cell.getChildren().setAll(new Text("⚠")));
            }
        });

        return cell;
    }

    private StackPane buildStarButton(GifResult gif, boolean[] isFavRef) {
        StackPane btn = new StackPane();
        btn.setPrefSize(26, 26);
        btn.setMaxSize(26, 26);
        btn.setAlignment(Pos.CENTER);
        btn.setStyle("-fx-background-color: rgba(0,0,0,0.45); -fx-background-radius: 5px; -fx-cursor: hand;");
        btn.getChildren().setAll(buildStarIcon(isFavRef[0]));

        btn.setOnMouseClicked(e -> {
            e.consume();
            boolean nowFav = !isFavRef[0];
            isFavRef[0] = nowFav;
            if (nowFav) {
                favoritedIds.add(gif.getId());
                favoriteGifs.add(0, gif);
            } else {
                favoritedIds.remove(gif.getId());
                favoriteGifs.removeIf(g -> g.getId().equals(gif.getId()));
            }
            btn.getChildren().setAll(buildStarIcon(nowFav));
            toggleFavoriteAsync(gif, nowFav);
            if (!nowFav && activeTab == Tab.FAVORITES) showFavoritesGrid();
        });

        return btn;
    }

    private FontIcon buildStarIcon(boolean favorited) {
        if (favorited) {
            return IconColorUtil.colored(MaterialDesignS.STAR, "#FFD700", 18);
        }
        FontIcon icon = new FontIcon(MaterialDesignS.STAR_OUTLINE);
        return icon;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void clearGrid() {
        for (int i = 0; i < COLUMN_COUNT; i++) {
            columns[i].getChildren().clear();
            columnHeights[i] = 0;
        }
    }

    private void cancelCurrentTask() {
        if (currentTask != null && currentTask.isRunning()) currentTask.cancel();
        currentTask = null;
    }

    private void setLoading(boolean show) {
        loadingLabel.setVisible(show);
        loadingLabel.setManaged(show);
    }

    private void setNoResults(boolean show) {
        noResultsLabel.setVisible(show);
        noResultsLabel.setManaged(show);
    }

    private void setNoFavorites(boolean show) {
        noFavoritesLabel.setVisible(show);
        noFavoritesLabel.setManaged(show);
    }

    private void setBottomLoading(boolean show) {
        bottomSpinnerBox.setVisible(show);
        bottomSpinnerBox.setManaged(show);
    }
}
