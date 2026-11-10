package komm.ui.attachments;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import komm.App;
import komm.media.ImageProbe;
import komm.media.MediaImages;
import komm.media.MediaStore;
import komm.ui.utils.FileChooserUtil;
import komm.websocket.messages.payloads.MessageReceivedPayload;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The bubble for an image attachment.
 *
 * <p>The server delivers the picture inline as base64 ({@code file64}). Keeping that string
 * on the payload pins every picture of the loaded history on the heap, and decoding it on
 * the FX thread each time the row scrolls into view stalls scrolling. So:
 * <ul>
 *   <li>the first time the bubble is shown, the bytes are moved to the on-disk
 *       {@link MediaStore} and {@code file64} is released;</li>
 *   <li>the picture is decoded off the FX thread at display size and kept in the bounded
 *       {@link MediaImages} cache, so scrolling back shows it instantly;</li>
 *   <li>when neither cache has it any more it is read back from disk, or re-downloaded
 *       from the server if the disk copy was trimmed.</li>
 * </ul>
 */
@Slf4j
final class ImageAttachmentView {

    private static final double IMG_W = 320, IMG_H = 240;
    private static final double VIEWER_W = 800, VIEWER_H = 600;

    /** Messages whose inline image was moved to disk — they must still render as an image, not a file chip. */
    private static final Set<UUID> OFFLOADED = ConcurrentHashMap.newKeySet();

    private ImageAttachmentView() {
    }

    /** Whether {@code msg} carries (or carried) an inline picture this class can show. */
    static boolean canShow(MessageReceivedPayload msg) {
        if (msg.getFileType() == null || !msg.getFileType().startsWith("image/")) return false;
        return hasInline(msg) || (msg.getMessageId() != null && OFFLOADED.contains(msg.getMessageId()));
    }

    /**
     * Moves an inline (base64) picture to the disk cache right away, so a freshly fetched
     * history page does not sit on the heap until each message happens to be scrolled to.
     * Blocking — call from the fetch thread.
     *
     * @return true if the picture is now on disk and the caller can drop its {@code file64}
     */
    static boolean offload(UUID messageId, String fileType, String file64) {
        if (messageId == null || fileType == null || !fileType.startsWith("image/")
                || file64 == null || file64.isBlank()) return false;
        try {
            byte[] bytes = Base64.getDecoder().decode(file64);
            String key = "att:" + messageId;
            if (!MediaStore.put(key, bytes)) return false;
            int[] size = ImageProbe.size(bytes);
            if (size != null) MediaImages.rememberSize(key, size[0], size[1]);
            OFFLOADED.add(messageId);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean hasInline(MessageReceivedPayload msg) {
        return msg.getFile64() != null && !msg.getFile64().isBlank();
    }

    private static String mediaKey(MessageReceivedPayload msg) {
        return msg.getMessageId() == null ? null : "att:" + msg.getMessageId();
    }

    /**
     * @param fallback builds the generic file chip, used if the picture turns out to be undecodable
     */
    static Node build(MessageReceivedPayload msg, String fileName, Function<MessageReceivedPayload, Node> fallback) {
        MediaImages.renderScale(); // prime on the FX thread
        String key = mediaKey(msg);

        int[] known = key != null ? MediaImages.knownSize(key) : null;
        if (known == null && hasInline(msg)) {
            known = ImageProbe.sizeOfBase64(msg.getFile64());
            if (known != null && key != null) MediaImages.rememberSize(key, known[0], known[1]);
        }
        final boolean sizeKnown = known != null;

        ImageView iv = new ImageView();
        iv.setPreserveRatio(true);
        iv.setFitWidth(IMG_W);
        iv.setFitHeight(IMG_H);

        StackPane frame = new StackPane(iv);
        frame.setAlignment(Pos.TOP_LEFT);
        applyFrameSize(frame, known);

        VBox wrapper = new VBox(frame);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(wrapper, new Insets(4, 0, 0, 0));

        frame.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && iv.getImage() != null) {
                openViewer(msg, fileName, iv.getImage());
                e.consume();
            }
        });

        Image ready = key != null ? MediaImages.cached(key) : null;
        if (ready != null) {
            iv.setImage(ready);
            if (!sizeKnown) applyFrameSize(frame, new int[]{(int) ready.getWidth(), (int) ready.getHeight()});
            return wrapper;
        }

        boolean[] started = {false};
        Runnable load = () -> {
            if (started[0]) return;
            started[0] = true;
            MediaImages.LOADER.execute(() -> {
                try {
                    byte[] bytes = originalBytes(msg);
                    int[] real = ImageProbe.size(bytes);
                    Image img = MediaImages.decodeToFit(bytes, IMG_W, IMG_H);
                    int[] size = real != null ? real : new int[]{(int) img.getWidth(), (int) img.getHeight()};
                    if (key != null) {
                        MediaImages.cache(key, img);
                        MediaImages.rememberSize(key, size[0], size[1]);
                    }
                    Platform.runLater(() -> {
                        if (!sizeKnown) applyFrameSize(frame, size);
                        iv.setImage(img);
                    });
                } catch (Exception ex) {
                    log.warn("Could not load image attachment {}: {}", msg.getMessageId(), ex.toString());
                    Platform.runLater(() -> {
                        Node chip = fallback.apply(msg);
                        if (chip != null) wrapper.getChildren().setAll(chip);
                    });
                }
            });
        };
        if (frame.getScene() != null) load.run();
        frame.sceneProperty().addListener((o, a, scene) -> {
            if (scene != null) load.run();
        });
        return wrapper;
    }

    /** Sizes the bubble for a picture fitted into the 320x240 box; a tinted placeholder while unknown. */
    private static void applyFrameSize(StackPane frame, int[] size) {
        double w = IMG_W, h = IMG_H;
        if (size != null && size[0] > 0 && size[1] > 0) {
            double s = Math.min(IMG_W / size[0], IMG_H / size[1]);
            w = Math.max(1, Math.round(size[0] * s));
            h = Math.max(1, Math.round(size[1] * s));
        }
        frame.setStyle("-fx-cursor: hand; -fx-background-color: -color-bg-subtle; -fx-background-radius: 3px;");
        frame.setMinSize(w, h);
        frame.setPrefSize(w, h);
        frame.setMaxSize(w, h);
    }

    /**
     * The attachment's original bytes: the inline base64 (then offloaded to disk and
     * released), else the disk cache, else a download from the server. Blocking.
     */
    private static byte[] originalBytes(MessageReceivedPayload msg) throws Exception {
        String key = mediaKey(msg);
        String inline = msg.getFile64();
        if (inline != null && !inline.isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(inline);
            if (key != null && MediaStore.put(key, bytes)) {
                OFFLOADED.add(msg.getMessageId());
                Platform.runLater(() -> {
                    if (msg.getFile64() == inline) msg.setFile64(null);
                });
            }
            return bytes;
        }
        if (key == null) throw new IllegalStateException("attachment has no data");
        byte[] cached = MediaStore.get(key);
        if (cached != null) return cached;

        byte[] bytes = msg.getServerId() == null
                ? App.getServices().hub().getDirectMessageService()
                        .downloadAttachment(msg.getMessageId(), p -> { })
                : App.getServices().installation().getMessageService()
                        .downloadAttachmentWithProgress(msg.getMessageId(), p -> { });
        if (bytes == null || bytes.length == 0) throw new IllegalStateException("empty attachment");
        MediaStore.put(key, bytes);
        return bytes;
    }

    private static void openViewer(MessageReceivedPayload msg, String fileName, Image preview) {
        ImageView modalImage = new ImageView(preview);
        modalImage.setPreserveRatio(true);
        modalImage.setFitWidth(VIEWER_W);
        modalImage.setFitHeight(VIEWER_H);

        // the thumbnail is up at once; swap in a sharper decode when it is ready
        MediaImages.LOADER.execute(() -> {
            try {
                Image big = MediaImages.decodeToFit(originalBytes(msg), VIEWER_W, VIEWER_H);
                Platform.runLater(() -> {
                    if (modalImage.getScene() != null) modalImage.setImage(big);
                });
            } catch (Exception ex) {
                log.debug("Viewer decode failed for {}: {}", msg.getMessageId(), ex.toString());
            }
        });

        MenuItem miDownload = new MenuItem("Download image…");
        miDownload.setOnAction(ev -> save(msg, fileName, modalImage));
        ContextMenu modalMenu = new ContextMenu(miDownload);

        modalImage.setOnContextMenuRequested(ev ->
                modalMenu.show(modalImage, ev.getScreenX(), ev.getScreenY()));
        modalImage.setOnMousePressed(ev -> {
            if (ev.isPrimaryButtonDown() && modalMenu.isShowing()) modalMenu.hide();
        });

        App.getModalPane().show(modalImage);
        modalImage.requestFocus();
    }

    private static void save(MessageReceivedPayload msg, String fileName, Node ownerNode) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save image");
        chooser.setInitialFileName(fileName);
        File dest = FileChooserUtil.showSaveDialog(chooser, ownerNode.getScene().getWindow());
        if (dest == null) return;

        Thread.ofVirtual().start(() -> {
            try {
                Files.write(dest.toPath(), originalBytes(msg));
                log.info("Image saved to {}", dest.getAbsolutePath());
            } catch (Exception ex) {
                log.error("Failed to save image", ex);
            }
        });
    }
}
