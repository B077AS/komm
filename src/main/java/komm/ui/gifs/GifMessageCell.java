package komm.ui.gifs;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import komm.ui.utils.FileChooserUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class GifMessageCell extends StackPane {

    public static final double MAX_GIF_WIDTH = 300.0;
    public static final double MAX_GIF_HEIGHT = 400.0;
    public static final double MIN_GIF_HEIGHT = 80.0;

    private static final double PLACEHOLDER_H = 225.0;

    private static final ExecutorService LOADER =
            Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "gif-loader");
                t.setDaemon(true);
                return t;
            });

    private final String gifUrl;

    private double cellW = MAX_GIF_WIDTH;
    private double cellH = PLACEHOLDER_H;
    private boolean sizeCommitted = false;

    private volatile boolean disposed = false;
    private boolean loadStarted = false;

    /**
     * Called on the FX thread exactly once when the real GIF dimensions are
     * committed (i.e. the cell's pref size changes from the placeholder to the
     * real size).  The caller is responsible for deciding whether to scroll —
     * this cell never touches the ScrollPane directly.
     */
    @Setter
    @Getter
    private Runnable onSizeCommitted;

    private final ImageView imageView;
    private final ProgressIndicator spinner;
    private final VBox errorBox;

    // Scroll-pane hook — kept only to avoid memory leaks on dispose.
    // The listeners are intentionally no-ops; the hook/unhook cycle just
    // ensures we don't hold a stale reference after the scene changes.
    private ScrollPane watchedScrollPane;
    private ChangeListener<Number> vvalueListener;
    private ChangeListener<Number> heightListener;

    // ── Constructor ───────────────────────────────────────────────────────────

    public GifMessageCell(String gifUrl, int gifWidth, int gifHeight) {
        this.gifUrl = gifUrl;

        if (gifWidth > 0 && gifHeight > 0) {
            double[] wh = computeSize(gifWidth, gifHeight);
            cellW = wh[0];
            cellH = wh[1];
            sizeCommitted = true;
            setPrefSize(cellW, cellH);
            setMaxSize(cellW, cellH);
        } else {
            setPrefSize(MAX_GIF_WIDTH, PLACEHOLDER_H);
            setMaxSize(MAX_GIF_WIDTH, PLACEHOLDER_H);
        }

        imageView = new ImageView();
        imageView.setFitWidth(cellW);
        imageView.setFitHeight(cellH);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setMouseTransparent(true);
        imageView.setVisible(false);

        spinner = new ProgressIndicator();
        spinner.setMaxSize(32, 32);
        spinner.setMouseTransparent(true);

        Label errLabel = new Label("GIF unavailable");
        errLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        Button retryBtn = new Button("↺  Retry");
        retryBtn.setStyle("-fx-background-color: transparent; -fx-border-color: -color-fg-muted;" +
                "-fx-border-radius: 4px; -fx-text-fill: -color-fg-muted; -fx-font-size: 11px;" +
                "-fx-padding: 2px 8px; -fx-cursor: hand;");
        retryBtn.setOnAction(e -> retry());
        errorBox = new VBox(4, errLabel, retryBtn);
        errorBox.setAlignment(Pos.CENTER);
        errorBox.setVisible(false);
        errorBox.setManaged(false);

        getChildren().addAll(imageView, spinner, errorBox);
        setAlignment(Pos.CENTER);
        setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 8px;");
        setCursor(javafx.scene.Cursor.HAND);
        applyClip(cellW, cellH);

        setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY
                    && imageView.getImage() != null && imageView.isVisible()) {

                ImageView modalIv = new ImageView(imageView.getImage());
                modalIv.setPreserveRatio(true);
                modalIv.setSmooth(true);
                modalIv.setFitWidth(800);
                modalIv.setFitHeight(600);

                // ── Context menu on the modal image ──────────────────────────
                MenuItem miCopyUrl = new MenuItem("Copy GIF URL");
                MenuItem miDownload = new MenuItem("Download GIF…");
                ContextMenu modalMenu = new ContextMenu(miCopyUrl, miDownload);

                miCopyUrl.setOnAction(ev -> {
                    ClipboardContent cc = new ClipboardContent();
                    cc.putString(gifUrl);
                    Clipboard.getSystemClipboard().setContent(cc);
                });

                miDownload.setOnAction(ev -> downloadGif(gifUrl, modalIv));

                modalIv.setOnContextMenuRequested(ev ->
                        modalMenu.show(modalIv, ev.getScreenX(), ev.getScreenY()));

                // Hide context menu on left-click
                modalIv.setOnMousePressed(ev -> {
                    if (ev.isPrimaryButtonDown() && modalMenu.isShowing()) {
                        modalMenu.hide();
                    }
                });

                komm.App.getModalPane().show(modalIv);
                modalIv.requestFocus();
                e.consume();
            }
        });

        if (gifUrl == null || gifUrl.isBlank()) {
            showError();
            return;
        }

        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(() -> {
                    startLoad();
                    hookScrollPane();
                });
            } else {
                unhookScrollPane();
            }
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void dispose() {
        if (disposed) return;
        disposed = true;
        unhookScrollPane();
        onSizeCommitted = null;
        Platform.runLater(() -> {
            imageView.setImage(null);
            imageView.setVisible(false);
        });
    }

    public void rehookScrollPane() {
        if (!disposed && getScene() != null) hookScrollPane();
    }

    // ── Overlays ──────────────────────────────────────────────────────────────

    private void showSpinner() {
        imageView.setVisible(false);
        spinner.setVisible(true);
        spinner.setManaged(true);
        errorBox.setVisible(false);
        errorBox.setManaged(false);
    }

    private void showImage() {
        imageView.setVisible(true);
        spinner.setVisible(false);
        spinner.setManaged(false);
        errorBox.setVisible(false);
        errorBox.setManaged(false);
    }

    private void showError() {
        imageView.setVisible(false);
        spinner.setVisible(false);
        spinner.setManaged(false);
        errorBox.setVisible(true);
        errorBox.setManaged(true);
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    private void retry() {
        if (disposed) return;
        loadStarted = false;
        showSpinner();
        startLoad();
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void startLoad() {
        if (loadStarted || disposed) return;
        loadStarted = true;
        showSpinner();

        LOADER.submit(() -> {
            if (disposed) return;
            try {
                Image image = new Image(gifUrl, 0, 0, true, true, false);

                if (image.isError()) {
                    Throwable ex = image.getException();
                    log.warn("GIF failed to load [url={}]: {}", gifUrl, ex != null ? ex.getMessage() : "unknown error", ex.getMessage());
                    Platform.runLater(() -> {
                        if (!disposed) showError();
                    });
                    return;
                }

                Platform.runLater(() -> {
                    if (disposed) return;

                    if (!sizeCommitted) {
                        int w = (int) image.getWidth();
                        int h = (int) image.getHeight();
                        if (w > 0 && h > 0) {
                            double[] wh = computeSize(w, h);
                            cellW = wh[0];
                            cellH = wh[1];
                            commitSize(cellW, cellH);
                            applyClip(cellW, cellH);
                            imageView.setFitWidth(cellW);
                            imageView.setFitHeight(cellH);
                        } else {
                            log.warn("GIF reported 0×0 dimensions [url={}], skipping size commit", gifUrl);
                            sizeCommitted = true;
                        }
                    }

                    imageView.setImage(image);
                    showImage();
                });

            } catch (Exception e) {
                log.error("Unexpected error loading GIF [url={}]", gifUrl, e);
                Platform.runLater(() -> {
                    if (!disposed) showError();
                });
            }
        });
    }

    // ── Scroll-pane hook ──────────────────────────────────────────────────────

    private void hookScrollPane() {
        unhookScrollPane();
        ScrollPane sp = findAncestorScrollPane();
        if (sp == null) return;
        watchedScrollPane = sp;
        // No-op listeners — only here to keep the reference alive so we can
        // cleanly remove them on dispose / scene detach.
        vvalueListener = (obs, o, n) -> {
        };
        heightListener = (obs, o, n) -> {
        };
        sp.vvalueProperty().addListener(vvalueListener);
        sp.heightProperty().addListener(heightListener);
    }

    private void unhookScrollPane() {
        if (watchedScrollPane != null && vvalueListener != null) {
            watchedScrollPane.vvalueProperty().removeListener(vvalueListener);
            watchedScrollPane.heightProperty().removeListener(heightListener);
        }
        watchedScrollPane = null;
        vvalueListener = null;
        heightListener = null;
    }

    private ScrollPane findAncestorScrollPane() {
        Node n = getParent();
        while (n != null) {
            if (n instanceof ScrollPane sp) return sp;
            n = n.getParent();
        }
        return null;
    }

    // ── Sizing ────────────────────────────────────────────────────────────────

    private double[] computeSize(int gifW, int gifH) {
        double scale = MAX_GIF_WIDTH / (double) gifW;
        double w = MAX_GIF_WIDTH;
        double h = Math.round(gifH * scale);
        if (h > MAX_GIF_HEIGHT) {
            scale = MAX_GIF_HEIGHT / (double) gifH;
            h = MAX_GIF_HEIGHT;
            w = Math.round(gifW * scale);
        }
        h = Math.max(h, MIN_GIF_HEIGHT);
        return new double[]{w, h};
    }

    /**
     * Apply real pixel dimensions to this node and fire {@link #onSizeCommitted}.
     * <p>
     * This method deliberately does NOT touch the ScrollPane. The owner
     * ({@code ChatSection}) decides whether scrolling is appropriate based on
     * its own {@code isAtBottom} flag and the current channel context.
     */
    private void commitSize(double w, double h) {
        sizeCommitted = true;
        cellW = w;
        cellH = h;
        setPrefSize(w, h);
        setMinSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Runnable cb = onSizeCommitted;
        if (cb != null) cb.run();
    }

    private void applyClip(double w, double h) {
        Rectangle r = new Rectangle(w, h);
        r.setArcWidth(10);
        r.setArcHeight(10);
        setClip(r);
        Rectangle rv = new Rectangle(w, h);
        rv.setArcWidth(10);
        rv.setArcHeight(10);
        imageView.setClip(rv);
    }

    private void downloadGif(String url, javafx.scene.Node ownerNode) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save GIF");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("GIF / MP4 files", "*.gif", "*.mp4"));

        String suggested = url.contains("/")
                ? url.substring(url.lastIndexOf('/') + 1)
                : "animation.gif";
        if (suggested.contains("?")) suggested = suggested.substring(0, suggested.indexOf('?'));
        if (suggested.isBlank()) suggested = "animation.gif";
        chooser.setInitialFileName(suggested);

        File file = FileChooserUtil.showSaveDialog(chooser, ownerNode.getScene().getWindow());
        if (file == null) return;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                URL src = URI.create(url).toURL();
                try (InputStream in = src.openStream();
                     FileOutputStream out = new FileOutputStream(file)) {
                    in.transferTo(out);
                }
                return null;
            }
        };

        task.setOnSucceeded(e -> log.info("GIF saved to {}", file.getAbsolutePath()));
        task.setOnFailed(e -> log.warn("GIF download failed", task.getException()));

        Thread dl = new Thread(task, "gif-download");
        dl.setDaemon(true);
        dl.start();
    }
}