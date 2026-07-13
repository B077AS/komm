package komm.ui.screenshare;

import atlantafx.base.theme.Styles;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.util.Duration;
import komm.App;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for simultaneous screen-share streams.
 *
 * <h3>Layout modes</h3>
 * <ul>
 *   <li><b>Focus</b> (default when ≥ 2 streams): the most-recently-joined stream
 *       is "featured" and fills ~78 % of the width; the remaining streams stack as
 *       16:9 thumbnails in the right 22 %, centred vertically.</li>
 *   <li><b>Grid</b>: all streams share space equally.</li>
 * </ul>
 *
 * <h3>Sidebar pagination</h3>
 * When there are more thumbnail streams than can fit vertically, up/down chevron
 * buttons appear over the sidebar on hover and fade away after 2.5 s of inactivity.
 * The featured stream is never affected by pagination.
 */
@Slf4j
public class MultiStreamView extends StackPane {

    private static final double GAP = 4;

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<StreamTile> tiles = new ArrayList<>();
    private boolean focusMode     = false;
    private boolean expandedMode  = false;
    private String  focusedUserId = null;
    private StreamTile fullscreenTile = null;

    // ── UI ────────────────────────────────────────────────────────────────────

    private final StreamLayoutPane layoutPane;
    private Timeline switchTl;

    // ── Callback ──────────────────────────────────────────────────────────────

    @FunctionalInterface
    public interface StreamRemovedCallback {
        void onRemoved(String userId, int remaining);
    }
    private StreamRemovedCallback onStreamRemoved;
    public void setOnStreamRemoved(StreamRemovedCallback cb) { this.onStreamRemoved = cb; }

    // ── Constructor ───────────────────────────────────────────────────────────

    public MultiStreamView() {
        setStyle("-fx-background-color: -color-bg-void;");
        setMinSize(0, 0);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        layoutPane = new StreamLayoutPane();
        layoutPane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        getChildren().add(layoutPane);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean hasStream(String userId) {
        return tiles.stream().anyMatch(t -> t.getUserId().equals(userId));
    }
    public int getStreamCount() { return tiles.size(); }

    public String getHeaderTitle() {
        if (tiles.isEmpty()) return "";
        if (tiles.size() == 1) return tiles.get(0).getUsername() + "'s Screen";
        return tiles.size() + " Live Streams";
    }

    public void addStream(String userId, String username) {
        if (hasStream(userId)) return;

        StreamTile tile = new StreamTile(userId, username);
        tile.setOnLeave(() -> removeStream(userId));
        tile.setOnPopOut(() -> popOut(tile));
        tile.setOnFocusToggle(() -> handleTileClick(tile));
        tile.setOnExpand(this::toggleExpanded);
        tile.setOnFullscreen(() -> toggleFullscreen(tile));
        tiles.add(tile);

        if (tiles.size() > 1) {
            focusMode     = true;
            focusedUserId = userId;
        }
        refresh();
    }

    public void removeStream(String userId) {
        StreamTile tile = tiles.stream().filter(t -> t.getUserId().equals(userId))
                .findFirst().orElse(null);
        if (tile == null) return;

        boolean wasFullscreen = tile == fullscreenTile;
        tile.dispose();
        tiles.remove(tile);

        // The tile lives in App's full-screen overlay, not layoutPane, while
        // fullscreen — exit it now (tiles no longer contains the removed tile,
        // so the exit callback's refresh() below won't try to reinsert it).
        if (wasFullscreen) {
            fullscreenTile = null;
            App.exitStreamFullscreen();
        }

        if (userId.equals(focusedUserId)) {
            expandedMode = false;
            if (tiles.isEmpty()) { focusedUserId = null; focusMode = false; }
            else { focusedUserId = tiles.get(0).getUserId(); focusMode = tiles.size() > 1; }
        }

        int remaining = tiles.size();
        if (remaining > 0) refresh();
        else layoutPane.clear();

        if (onStreamRemoved != null) onStreamRemoved.onRemoved(userId, remaining);
    }

    /** Silently disposes all tiles without firing callbacks. */
    public void removeAllStreams() {
        new ArrayList<>(tiles).forEach(StreamTile::dispose);
        tiles.clear();
        layoutPane.clear();
        focusMode     = false;
        expandedMode  = false;
        focusedUserId = null;
        if (fullscreenTile != null) {
            fullscreenTile = null;
            App.exitStreamFullscreen();
        }
    }

    /** No longer used for pagination — kept for API compatibility. */
    public void setChatOpen(boolean open) { }

    public List<String> getStreamUserIds() {
        return tiles.stream().map(StreamTile::getUserId).toList();
    }

    public void updateViewerCount(String userId, int count) {
        tiles.stream().filter(t -> t.getUserId().equals(userId))
                .findFirst().ifPresent(t -> t.setViewerCount(count));
    }

    // ── Focus / grid toggle ───────────────────────────────────────────────────

    private void handleTileClick(StreamTile tile) {
        if (tiles.size() <= 1) return;

        if (focusMode && tile.getUserId().equals(focusedUserId)) {
            focusMode     = false;
            focusedUserId = null;
            expandedMode  = false;
        } else {
            focusMode     = true;
            focusedUserId = tile.getUserId();
            expandedMode  = false;
        }
        refresh();
    }

    // ── Expand toggle ─────────────────────────────────────────────────────────

    private void toggleExpanded() {
        expandedMode = !expandedMode;
        refresh();
    }

    // ── Fullscreen toggle ─────────────────────────────────────────────────────

    /**
     * Moves {@code tile} into App's OS-level full-screen overlay (or exits it
     * if already there). The tile keeps its own hover pill and controls — it's
     * just reparented and resized, same as any other layout change.
     */
    private void toggleFullscreen(StreamTile tile) {
        if (fullscreenTile == tile) {
            App.exitStreamFullscreen();
            return;
        }
        fullscreenTile = tile;
        tile.setFullscreenState(true);
        // Drop the tile from layoutPane's bookkeeping *before* App reparents it —
        // otherwise a stray layoutChildren() pass (e.g. from a window resize while
        // fullscreen) would still resizeRelocate() it using stale grid/focus math,
        // fighting with the fullscreen overlay's own sizing of the same node.
        refresh();
        App.enterStreamFullscreen(tile, () -> {
            tile.setFullscreenState(false);
            fullscreenTile = null;
            refresh(); // tile is still in `tiles` — this reparents it back into layoutPane
        });
    }

    // ── Layout refresh ────────────────────────────────────────────────────────

    private void refresh() {
        List<StreamTile> visible = fullscreenTile == null
                ? tiles
                : tiles.stream().filter(t -> t != fullscreenTile).toList();

        if (visible.isEmpty()) { layoutPane.clear(); return; }

        boolean actualFocus = focusMode && visible.size() > 1;
        int featuredIdx = 0;
        if (actualFocus && focusedUserId != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).getUserId().equals(focusedUserId)) {
                    featuredIdx = i;
                    break;
                }
            }
        }

        boolean hadTiles = !layoutPane.getChildren().isEmpty();

        for (StreamTile t : visible) {
            boolean isFeatured = actualFocus && focusedUserId != null
                    && t.getUserId().equals(focusedUserId);
            t.setExpandButtonVisible(isFeatured);
            if (isFeatured) t.setExpanded(expandedMode);
        }

        layoutPane.update(visible, actualFocus, featuredIdx, expandedMode);

        if (hadTiles) {
            if (switchTl != null) switchTl.stop();
            layoutPane.setOpacity(0.0);
            switchTl = new Timeline(
                    new KeyFrame(Duration.ZERO,       new KeyValue(layoutPane.opacityProperty(), 0.0)),
                    new KeyFrame(Duration.millis(200), new KeyValue(layoutPane.opacityProperty(), 1.0, Interpolator.EASE_OUT)));
            switchTl.play();
        } else {
            layoutPane.setOpacity(1.0);
        }
    }

    // ── Pop-out ───────────────────────────────────────────────────────────────

    private void popOut(StreamTile tile) {
        StreamPopOutWindow w = new StreamPopOutWindow(
                tile.getUserId(), tile.getUsername(),
                () -> Platform.runLater(tile::reattachAfterPopIn));
        tile.markPoppedOut(w);
        w.show();
    }

    // ── Inner layout pane ─────────────────────────────────────────────────────

    /**
     * Positions tiles via {@code resizeRelocate} in {@code layoutChildren}.
     * In focus mode the sidebar thumbnails have their own page state and
     * hover-reveal up/down nav buttons; the featured tile is unaffected.
     */
    private static final class StreamLayoutPane extends Pane {

        private List<StreamTile> tiles        = List.of();
        private boolean          focusMode    = false;
        private boolean          expandedMode = false;
        private int              featuredIdx  = 0;
        private int              sidebarPage  = 0;

        // ── Sidebar nav ───────────────────────────────────────────────────────
        private final Button  sideUpBtn;
        private final Button  sideDownBtn;
        private boolean       sideNavNeeded  = false;
        private boolean       sideNavVisible = false;
        private Timeline      sideNavFade;
        private Timeline      sideNavAutoHide;
        // Sidebar region bounds (updated each layout pass) for hover detection
        private double        sideRegionStart = -1;
        private double        sideRegionEnd   = -1;

        StreamLayoutPane() {
            sideUpBtn   = makeNavBtn(Feather.CHEVRON_UP);
            sideDownBtn = makeNavBtn(Feather.CHEVRON_DOWN);
            sideUpBtn.setOpacity(0);   sideUpBtn.setMouseTransparent(true);
            sideDownBtn.setOpacity(0); sideDownBtn.setMouseTransparent(true);

            sideUpBtn.setOnAction(e   -> { sidebarPage--; requestLayout(); scheduleSideHide(); });
            sideDownBtn.setOnAction(e -> { sidebarPage++; requestLayout(); scheduleSideHide(); });

            addEventHandler(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
            addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
                cancelSideHide();
                if (sideNavVisible) fadeSideNav(false);
            });

            getChildren().addAll(sideUpBtn, sideDownBtn);
        }

        // ── API ───────────────────────────────────────────────────────────────

        void update(List<StreamTile> tiles, boolean focus, int featuredIdx, boolean expanded) {
            this.tiles        = tiles;
            this.focusMode    = focus;
            this.expandedMode = expanded;
            this.featuredIdx  = tiles.isEmpty() ? 0 : Math.min(featuredIdx, tiles.size() - 1);
            sidebarPage = 0;
            getChildren().setAll(tiles);
            getChildren().addAll(sideUpBtn, sideDownBtn); // always on top
            requestLayout();
        }

        void clear() {
            tiles = List.of();
            getChildren().setAll(sideUpBtn, sideDownBtn);
            clearSideNav();
        }

        // ── Hover ─────────────────────────────────────────────────────────────

        private void onMouseMoved(MouseEvent e) {
            if (!sideNavNeeded) return;
            double x = e.getX();
            if (x >= sideRegionStart && x <= sideRegionEnd) {
                if (!sideNavVisible) fadeSideNav(true);
                scheduleSideHide();
            }
        }

        private void fadeSideNav(boolean in) {
            sideNavVisible = in;
            sideUpBtn.setMouseTransparent(!in);
            sideDownBtn.setMouseTransparent(!in);
            if (sideNavFade != null) sideNavFade.stop();
            double target = in ? 1.0 : 0.0;
            sideNavFade = new Timeline(new KeyFrame(Duration.millis(in ? 120 : 180),
                    new KeyValue(sideUpBtn.opacityProperty(),   target, Interpolator.EASE_BOTH),
                    new KeyValue(sideDownBtn.opacityProperty(), target, Interpolator.EASE_BOTH)));
            sideNavFade.play();
        }

        private void scheduleSideHide() {
            cancelSideHide();
            sideNavAutoHide = new Timeline(new KeyFrame(Duration.millis(2500),
                    e -> fadeSideNav(false)));
            sideNavAutoHide.play();
        }

        private void cancelSideHide() {
            if (sideNavAutoHide != null) { sideNavAutoHide.stop(); sideNavAutoHide = null; }
        }

        private void clearSideNav() {
            sideNavNeeded   = false;
            sideRegionStart = -1;
            sideRegionEnd   = -1;
            cancelSideHide();
            if (sideNavVisible) {
                sideNavVisible = false;
                sideUpBtn.setOpacity(0);   sideUpBtn.setMouseTransparent(true);
                sideDownBtn.setOpacity(0); sideDownBtn.setMouseTransparent(true);
            }
        }

        // ── Layout ────────────────────────────────────────────────────────────

        @Override
        protected void layoutChildren() {
            double w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || tiles.isEmpty()) return;
            tiles.forEach(t -> { t.setTranslateX(0); t.setTranslateY(0); t.setVisible(true); });
            if (focusMode && tiles.size() > 1) doFocusLayout(w, h);
            else { doGridLayout(w, h); clearSideNav(); }
        }

        private void doFocusLayout(double w, double h) {
            StreamTile featured = tiles.get(featuredIdx);

            if (expandedMode) {
                featured.resizeRelocate(0, 0, w, h);
                tiles.stream().filter(t -> t != featured).forEach(t -> t.setVisible(false));
                clearSideNav();
                return;
            }

            List<StreamTile> side = new ArrayList<>(tiles);
            side.remove(featured);
            if (side.isEmpty()) {
                featured.resizeRelocate(0, 0, w, h);
                clearSideNav();
                return;
            }

            // Sidebar width: 22% of container; thumbnail height derived from 16:9
            double sideW = w * 0.22;
            double mainW = w - sideW - GAP;
            double tH    = sideW * 9.0 / 16.0;

            // Featured tile: always exactly 16:9 so the video fills it with no
            // pillarboxing. For wide/short containers mainH = h and the 16:9 width
            // is narrower than mainW; for tall containers it equals mainW exactly.
            // The pair (featured + sidebar) is centred in the full available width.
            double mainH    = Math.min(h, mainW * 9.0 / 16.0);
            double mainWFit = mainH * 16.0 / 9.0;   // always ≤ mainW
            double mainY    = (h - mainH) / 2.0;
            double offsetX  = (mainW - mainWFit) / 2.0;  // centres the pair in w
            featured.resizeRelocate(offsetX, mainY, mainWFit, mainH);

            sideRegionStart = offsetX + mainWFit + GAP;
            sideRegionEnd   = sideRegionStart + sideW;

            // Sidebar pagination: how many 16:9 thumbnails fit within mainH
            int perPage = Math.max(1, (int) ((mainH + GAP) / (tH + GAP)));
            int total   = (int) Math.ceil((double) side.size() / perPage);

            sidebarPage = Math.max(0, Math.min(sidebarPage, total - 1));

            int start = sidebarPage * perPage;
            int end   = Math.min(start + perPage, side.size());

            for (int i = 0; i < side.size(); i++)
                side.get(i).setVisible(i >= start && i < end);

            List<StreamTile> vis = side.subList(start, end);
            double totH = vis.size() * tH + GAP * (vis.size() - 1);
            double sy   = (h - totH) / 2.0;
            for (int i = 0; i < vis.size(); i++)
                vis.get(i).resizeRelocate(sideRegionStart, sy + i * (tH + GAP), sideW, tH);

            // Nav buttons — hover-revealed, centred in sidebar column
            sideNavNeeded = total > 1;
            double bw = snapSizeX(sideUpBtn.prefWidth(-1));
            double bh = snapSizeY(sideUpBtn.prefHeight(bw));
            double bx = sideRegionStart + (sideW - bw) / 2.0;
            sideUpBtn.resizeRelocate(bx,           10,      bw, bh);
            sideDownBtn.resizeRelocate(bx, h - bh - 10,     bw, bh);
            sideUpBtn.setDisable(sidebarPage == 0);
            sideDownBtn.setDisable(sidebarPage >= total - 1);

            if (!sideNavNeeded && sideNavVisible) fadeSideNav(false);
        }

        private void doGridLayout(double w, double h) {
            int n = tiles.size();
            if (n == 1) {
                tiles.get(0).resizeRelocate(0, 0, w, h);
            } else if (n == 2) {
                double tw = (w - GAP) / 2;
                tiles.get(0).resizeRelocate(0,       0, tw, h);
                tiles.get(1).resizeRelocate(tw + GAP, 0, tw, h);
            } else if (n == 3) {
                double tw = (w - GAP) / 2, hh = (h - GAP) / 2;
                tiles.get(0).resizeRelocate(0,        0,       tw, hh);
                tiles.get(1).resizeRelocate(tw + GAP, 0,       tw, hh);
                tiles.get(2).resizeRelocate(0,        hh + GAP, w, hh);
            } else {
                int    cols = (int) Math.ceil(Math.sqrt(n));
                int    rows = (int) Math.ceil((double) n / cols);
                double tw   = (w - GAP * (cols - 1)) / cols;
                double th   = (h - GAP * (rows - 1)) / rows;
                for (int i = 0; i < n; i++) {
                    int col = i % cols, row = i / cols;
                    tiles.get(i).resizeRelocate(col * (tw + GAP), row * (th + GAP), tw, th);
                }
            }
        }

        private static Button makeNavBtn(Feather icon) {
            Button btn = new Button(null, new FontIcon(icon));
            btn.setFocusTraversable(false);
            // Use the CSS class to apply accent colour — setting -color-accent-base
            // via an inline style causes a String→Paint ClassCastException because
            // looked-up colours are not resolved in the inline-style pipeline.
            btn.getStyleClass().add(Styles.ACCENT);
            btn.setStyle("""
                    -fx-background-radius: 50;
                    -fx-padding: 6 8 6 8;
                    -fx-cursor: hand;
                    """);
            return btn;
        }
    }
}
