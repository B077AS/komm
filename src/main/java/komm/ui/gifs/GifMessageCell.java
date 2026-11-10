package komm.ui.gifs;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import komm.media.GifDecoder;
import komm.media.GifView;
import komm.media.ImageProbe;
import komm.media.MediaImages;
import komm.media.MediaStore;
import komm.ui.utils.FileChooserUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;

/**
 * A chat bubble showing "whatever image is at this URL": an animated GIF or a still image.
 *
 * <p>Memory model (see {@code komm.media}): the file is downloaded once into the on-disk
 * {@link MediaStore}; a GIF is played by a streaming {@link GifView} (one canvas, not one
 * bitmap per frame), a still is decoded at display size and shared through
 * {@link MediaImages}. Nothing but the remembered pixel size outlives the cell, so the
 * bubble has its final height the next time it scrolls into view.
 */
@Slf4j
public class GifMessageCell extends StackPane {

    public static final double MAX_GIF_WIDTH = 300.0;
    public static final double MAX_GIF_HEIGHT = 400.0;
    public static final double MIN_GIF_HEIGHT = 80.0;

    private static final double PLACEHOLDER_H = 225.0;
    private static final double VIEWER_W = 800, VIEWER_H = 600;

    private final String url;
    private final String kindLabel;
    private final String mediaKey;

    private double cellW = MAX_GIF_WIDTH;
    private double cellH = PLACEHOLDER_H;
    private boolean sizeCommitted;

    private volatile boolean disposed;
    private volatile boolean attached;
    private boolean loadStarted;

    /** The node currently showing the media: a {@link GifView} or a plain {@link ImageView}. */
    private ImageView media;
    /** Compressed GIF bytes while an animated bubble is alive (also held by its {@link GifView}). */
    private java.nio.ByteBuffer gifBytes;

    private final ProgressIndicator spinner;
    private final VBox errorBox;

    public GifMessageCell(String gifUrl, int gifWidth, int gifHeight) {
        this(gifUrl, gifWidth, gifHeight, "GIF");
    }

    /**
     * @param kindLabel what to call this in user-facing text ("GIF", "Image", ...)
     */
    public GifMessageCell(String url, int width, int height, String kindLabel) {
        this.url = url;
        this.kindLabel = kindLabel;
        this.mediaKey = "url:" + url;
        MediaImages.renderScale(); // prime on the FX thread

        if (width <= 0 || height <= 0) {
            int[] known = MediaImages.knownSize(mediaKey);
            if (known != null) {
                width = known[0];
                height = known[1];
            }
        }
        if (width > 0 && height > 0) {
            double[] wh = computeSize(width, height);
            cellW = wh[0];
            cellH = wh[1];
            sizeCommitted = true;
        }
        setPrefSize(cellW, cellH);
        setMaxSize(cellW, cellH);

        spinner = new ProgressIndicator();
        spinner.setMaxSize(32, 32);
        spinner.setMouseTransparent(true);

        Label errLabel = new Label(kindLabel + " unavailable");
        errLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        Button retryBtn = new Button("↺  Retry");
        retryBtn.setStyle("-fx-background-color: transparent; -fx-border-color: -color-fg-muted;" +
                "-fx-border-radius: 2px; -fx-text-fill: -color-fg-muted; -fx-font-size: 11px;" +
                "-fx-padding: 2px 8px; -fx-cursor: hand;");
        retryBtn.setOnAction(e -> retry());
        errorBox = new VBox(4, errLabel, retryBtn);
        errorBox.setAlignment(Pos.CENTER);
        errorBox.setVisible(false);
        errorBox.setManaged(false);

        getChildren().addAll(spinner, errorBox);
        setAlignment(Pos.CENTER);
        setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 3px;");
        setCursor(javafx.scene.Cursor.HAND);
        applyClip();

        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && media != null && media.isVisible()) {
                openViewer();
                e.consume();
            }
        });

        if (url == null || url.isBlank()) {
            showError();
            return;
        }

        // A still that was decoded recently is shown straight away — no spinner, no reload.
        Image ready = MediaImages.cached(mediaKey);
        if (ready != null) {
            showStill(ready);
            loadStarted = true;
        }

        sceneProperty().addListener((obs, oldScene, newScene) -> {
            attached = newScene != null;
            if (attached) startLoad();
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void dispose() {
        if (disposed) return;
        disposed = true;
        gifBytes = null;
        if (media instanceof GifView gv) gv.dispose();
        if (media != null) media.setImage(null);
    }

    // ── Overlays ──────────────────────────────────────────────────────────────

    private void showSpinner() {
        if (media != null) media.setVisible(false);
        spinner.setVisible(true);
        spinner.setManaged(true);
        errorBox.setVisible(false);
        errorBox.setManaged(false);
    }

    private void showMedia(ImageView node) {
        if (media != null && media != node) {
            if (media instanceof GifView old) old.dispose();
            getChildren().remove(media);
        }
        media = node;
        node.setFitWidth(cellW);
        node.setFitHeight(cellH);
        node.setPreserveRatio(true);
        node.setSmooth(true);
        node.setMouseTransparent(true);
        node.setVisible(true);
        if (!getChildren().contains(node)) getChildren().add(0, node);
        applyClip();
        spinner.setVisible(false);
        spinner.setManaged(false);
        errorBox.setVisible(false);
        errorBox.setManaged(false);
    }

    private void showStill(Image image) {
        if (!sizeCommitted) commitSize((int) image.getWidth(), (int) image.getHeight());
        showMedia(new ImageView(image));
    }

    private void showError() {
        if (media != null) media.setVisible(false);
        spinner.setVisible(false);
        spinner.setManaged(false);
        errorBox.setVisible(true);
        errorBox.setManaged(true);
    }

    private void retry() {
        if (disposed) return;
        loadStarted = false;
        startLoad();
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void startLoad() {
        if (loadStarted || disposed) return;
        loadStarted = true;
        showSpinner();

        MediaImages.LOADER.execute(() -> {
            if (disposed) return;
            if (!attached) {
                // scrolled away before its turn came: don't download for nobody
                Platform.runLater(() -> loadStarted = false);
                return;
            }
            try {
                // GIFs are played straight from the memory-mapped cache file (nothing on the heap);
                // stills are small enough to read, and are decoded once into the shared cache.
                java.nio.ByteBuffer data = MediaStore.openUrl(url);
                final java.nio.ByteBuffer gifData = GifDecoder.isGif(data) ? data : null;
                final byte[] bytes;
                int[] probed;
                if (gifData != null) {
                    bytes = null;
                    probed = new int[]{(gifData.get(6) & 0xFF) | (gifData.get(7) & 0xFF) << 8,
                            (gifData.get(8) & 0xFF) | (gifData.get(9) & 0xFF) << 8};
                } else {
                    bytes = new byte[data.limit()];
                    data.get(0, bytes);
                    probed = ImageProbe.size(bytes);
                }
                final int[] size = (probed != null && probed[0] > 0 && probed[1] > 0) ? probed : null;
                if (size != null) MediaImages.rememberSize(mediaKey, size[0], size[1]);

                if (gifData != null && size != null) {
                    Platform.runLater(() -> {
                        if (disposed) return;
                        try {
                            if (!sizeCommitted) commitSize(size[0], size[1]);
                            GifView gv = new GifView(gifData, cellW, cellH);
                            gv.setOnError(t -> {
                                log.warn("{} failed to decode [url={}]: {}", kindLabel, url, t.toString());
                                if (!disposed) showError();
                            });
                            gifBytes = gifData;
                            showMedia(gv);
                        } catch (IllegalArgumentException bad) {
                            log.warn("{} unusable [url={}]: {}", kindLabel, url, bad.getMessage());
                            showError();
                        }
                    });
                    return;
                }

                Image image = MediaImages.decodeToFit(bytes, MAX_GIF_WIDTH, MAX_GIF_HEIGHT);
                if (size == null) {
                    MediaImages.rememberSize(mediaKey, (int) image.getWidth(), (int) image.getHeight());
                }
                MediaImages.cache(mediaKey, image);
                Platform.runLater(() -> {
                    if (disposed) return;
                    if (!sizeCommitted) {
                        if (size != null) commitSize(size[0], size[1]);
                        else commitSize((int) image.getWidth(), (int) image.getHeight());
                    }
                    showMedia(new ImageView(image));
                });
            } catch (Exception e) {
                log.warn("{} failed to load [url={}]: {}", kindLabel, url, e.toString());
                Platform.runLater(() -> {
                    if (!disposed) showError();
                });
            }
        });
    }

    // ── Full-size viewer ──────────────────────────────────────────────────────

    private void openViewer() {
        ImageView viewer;
        if (gifBytes != null) {
            viewer = new GifView(gifBytes, VIEWER_W, VIEWER_H);
        } else {
            // show what we have, then swap in a sharper decode
            ImageView iv = new ImageView(media.getImage());
            viewer = iv;
            MediaImages.LOADER.execute(() -> {
                try {
                    Image big = MediaImages.decodeToFit(MediaStore.fetchUrl(url), VIEWER_W, VIEWER_H);
                    Platform.runLater(() -> {
                        if (iv.getScene() != null) iv.setImage(big);
                    });
                } catch (Exception ex) {
                    log.debug("{} viewer decode failed [url={}]: {}", kindLabel, url, ex.toString());
                }
            });
        }
        viewer.setPreserveRatio(true);
        viewer.setSmooth(true);
        viewer.setFitWidth(VIEWER_W);
        viewer.setFitHeight(VIEWER_H);

        MenuItem miCopyUrl = new MenuItem("Copy " + kindLabel + " URL");
        MenuItem miDownload = new MenuItem("Download " + kindLabel + "…");
        ContextMenu menu = new ContextMenu(miCopyUrl, miDownload);

        miCopyUrl.setOnAction(ev -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(url);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        miDownload.setOnAction(ev -> download(viewer));

        viewer.setOnContextMenuRequested(ev -> menu.show(viewer, ev.getScreenX(), ev.getScreenY()));
        viewer.setOnMousePressed(ev -> {
            if (ev.isPrimaryButtonDown() && menu.isShowing()) menu.hide();
        });

        komm.App.getModalPane().show(viewer);
        viewer.requestFocus();
    }

    // ── Sizing ────────────────────────────────────────────────────────────────

    private static double[] computeSize(int srcW, int srcH) {
        double scale = MAX_GIF_WIDTH / (double) srcW;
        double w = MAX_GIF_WIDTH;
        double h = Math.round(srcH * scale);
        if (h > MAX_GIF_HEIGHT) {
            scale = MAX_GIF_HEIGHT / (double) srcH;
            h = MAX_GIF_HEIGHT;
            w = Math.round(srcW * scale);
        }
        h = Math.max(h, MIN_GIF_HEIGHT);
        return new double[]{w, h};
    }

    private void commitSize(int srcW, int srcH) {
        sizeCommitted = true;
        if (srcW <= 0 || srcH <= 0) return;
        double[] wh = computeSize(srcW, srcH);
        cellW = wh[0];
        cellH = wh[1];
        setPrefSize(cellW, cellH);
        setMinSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        applyClip();
    }

    private void applyClip() {
        Rectangle r = new Rectangle(cellW, cellH);
        r.setArcWidth(10);
        r.setArcHeight(10);
        setClip(r);
    }

    private void download(javafx.scene.Node ownerNode) {
        boolean isGif = "GIF".equals(kindLabel);
        String fallbackName = isGif ? "animation.gif" : "image.jpg";

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save " + kindLabel);
        chooser.getExtensionFilters().add(isGif
                ? new FileChooser.ExtensionFilter("GIF / MP4 files", "*.gif", "*.mp4")
                : new FileChooser.ExtensionFilter("Image files", "*.jpg", "*.jpeg", "*.png", "*.webp", "*.gif"));

        String suggested = url.contains("/") ? url.substring(url.lastIndexOf('/') + 1) : fallbackName;
        if (suggested.contains("?")) suggested = suggested.substring(0, suggested.indexOf('?'));
        if (suggested.isBlank()) suggested = fallbackName;
        chooser.setInitialFileName(suggested);

        File file = FileChooserUtil.showSaveDialog(chooser, ownerNode.getScene().getWindow());
        if (file == null) return;

        Thread.ofVirtual().name("gif-download").start(() -> {
            try {
                Files.write(file.toPath(), MediaStore.fetchUrl(url));
                log.info("{} saved to {}", kindLabel, file.getAbsolutePath());
            } catch (Exception ex) {
                log.warn("{} download failed", kindLabel, ex);
            }
        });
    }
}
