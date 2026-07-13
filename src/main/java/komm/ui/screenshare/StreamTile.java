package komm.ui.screenshare;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import komm.App;
import komm.ui.avatar.AvatarColor;
import lombok.Getter;
import lombok.Setter;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.ByteArrayInputStream;
import java.util.UUID;

/**
 * A single stream tile rendered via a custom {@link Pane#layoutChildren()}
 * so every overlay element scales proportionally with the tile size —
 * no fixed pixel positions, no StackPane centering artefacts.
 *
 * <h3>Hover behaviour</h3>
 * On mouse enter: top-gradient + LIVE indicator (top-left) + bottom-gradient +
 * pill control bar (bottom-centre) all fade in together (120 ms).
 * Auto-hide after 2.5 s; instant hide on mouse exit.
 *
 * <h3>Click behaviour</h3>
 * Clicking the tile (outside the pill buttons) fires {@link #onFocusToggle}.
 * Clicks on pill buttons are consumed by the pill itself so they do not
 * propagate to the focus handler.
 *
 * <h3>Pop-out lifecycle</h3>
 * {@link #markPoppedOut} → unsubscribes this tile's sink, shows a placeholder.<br>
 * {@link #reattachAfterPopIn} → creates a fresh VideoSinkView, resubscribes.
 */
public class StreamTile extends Pane {

    private static final long HIDE_DELAY_MS = 2500;

    // ── Identity ──────────────────────────────────────────────────────────────

    @Getter private final String userId;
    @Getter private final String username;

    // ── Video ─────────────────────────────────────────────────────────────────

    private VideoSinkView videoSinkView;
    private final Label waitingLabel;

    // ── Overlay elements ──────────────────────────────────────────────────────

    private final Region topVignette;
    private final Region bottomVignette;
    private final HBox  topBar;          // LIVE dot + streamer name + viewer count
    private final Circle liveDot;
    private final HBox  pillBar;         // control pill (bottom-centre)
    private Label viewerCountLabel;
    private final Button expandBtn;
    private final Tooltip expandTooltip;
    private final Button fullscreenBtn;
    private final Tooltip fullscreenTooltip;
    private final StackPane poppedOverlay;

    // ── Content bounds (letterbox-aware, updated in layoutChildren) ───────────

    private double contentX = 0, contentY = 0, contentW = -1, contentH = -1;

    // ── Animation ─────────────────────────────────────────────────────────────

    private boolean     overlayVisible = false;
    private boolean     overlayPinned  = false; // true while volume popup is open
    private Timeline    hideTimer;
    private Timeline    fadeTl;
    private Timeline    liveTl;

    // ── Pop-out ───────────────────────────────────────────────────────────────

    private boolean            poppedOut   = false;
    private StreamPopOutWindow popOutWindow;

    // ── Callbacks ─────────────────────────────────────────────────────────────

    @Setter private Runnable onLeave;
    @Setter private Runnable onPopOut;
    @Setter private Runnable onFocusToggle;
    @Setter private Runnable onExpand;
    @Setter private Runnable onFullscreen;

    // ── Constructor ───────────────────────────────────────────────────────────

    public StreamTile(String userId, String username) {
        this.userId   = userId;
        this.username = username;
        setStyle("-fx-background-color: -color-bg-void;");
        setMinSize(0, 0);
        setCursor(Cursor.HAND);

        waitingLabel = new Label("Waiting for stream…");
        waitingLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 12px;");
        waitingLabel.setMouseTransparent(true);

        // Video fills tile — no side padding, subtle corner arc
        videoSinkView = new VideoSinkView(0, 6);
        videoSinkView.setOnFirstFrameReceived(
                () -> Platform.runLater(() -> waitingLabel.setVisible(false)));
        videoSinkView.setOnDimensionsChanged(() -> requestLayout());

        topVignette    = vignette(true);
        bottomVignette = vignette(false);

        liveDot = new Circle(4, Color.web("#e53e3e"));
        liveDot.setStyle("-fx-effect: dropshadow(gaussian,#e53e3e,5,0.7,0,0);");
        topBar  = buildTopBar();

        expandBtn     = new Button(null, new FontIcon(Feather.MAXIMIZE_2));
        expandBtn.getStyleClass().add("toolbar-chip-ghost");
        expandBtn.setMinSize(32, 32);
        expandBtn.setMaxSize(32, 32);
        expandBtn.setPrefSize(32, 32);
        expandBtn.setFocusTraversable(false);
        expandBtn.setVisible(false);
        expandBtn.setManaged(false);
        expandTooltip = new Tooltip("Expand");
        Tooltip.install(expandBtn, expandTooltip);

        fullscreenBtn = new Button(null, new FontIcon(Feather.MAXIMIZE));
        fullscreenBtn.getStyleClass().add("toolbar-chip-ghost");
        fullscreenBtn.setMinSize(32, 32);
        fullscreenBtn.setMaxSize(32, 32);
        fullscreenBtn.setPrefSize(32, 32);
        fullscreenBtn.setFocusTraversable(false);
        fullscreenTooltip = new Tooltip("Fullscreen");
        Tooltip.install(fullscreenBtn, fullscreenTooltip);

        pillBar = buildPill();

        poppedOverlay = buildPoppedOverlay();
        poppedOverlay.setVisible(false);

        // All overlay elements start invisible
        topVignette.setOpacity(0);
        bottomVignette.setOpacity(0);
        topBar.setOpacity(0);
        pillBar.setOpacity(0);
        pillBar.setMouseTransparent(true);

        getChildren().addAll(videoSinkView, topVignette, bottomVignette,
                waitingLabel, topBar, pillBar, poppedOverlay);

        // ── Event hooks ───────────────────────────────────────────────────────
        addEventHandler(MouseEvent.MOUSE_ENTERED, this::onMouseMove);
        addEventHandler(MouseEvent.MOUSE_MOVED,   this::onMouseMove);
        addEventHandler(MouseEvent.MOUSE_EXITED,  e -> hideNow());

        // Tile click fires focus toggle only when inside content area;
        // button clicks are consumed by the pill
        addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && !poppedOut
                    && inContentBounds(e.getX(), e.getY())
                    && onFocusToggle != null) {
                onFocusToggle.run();
            }
        });

        App.getWebrtcRoomClient().subscribeToRemoteVideo(userId, videoSinkView);
        startLivePulse();
    }

    // ── Custom layout ─────────────────────────────────────────────────────────

    @Override
    protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Video — full tile
        videoSinkView.resizeRelocate(0, 0, w, h);

        // Waiting label — centred over full tile
        double lw = snapSizeX(waitingLabel.prefWidth(-1));
        double lh = snapSizeY(waitingLabel.prefHeight(lw));
        waitingLabel.resizeRelocate((w - lw) / 2, (h - lh) / 2, lw, lh);

        // Compute actual video content bounds (letterbox-aware).
        // Falls back to full tile when no frames received yet.
        double cx = 0, cy = 0, cw = w, ch = h;
        int vw = videoSinkView.getVideoWidth();
        int vh = videoSinkView.getVideoHeight();
        if (vw > 0 && vh > 0) {
            double videoAspect = (double) vw / vh;
            double tileAspect  = w / h;
            if (videoAspect > tileAspect) {
                // wider video: pillar-fills width, black bars top/bottom
                ch = w / videoAspect;
                cy = (h - ch) / 2;
            } else {
                // taller video: fills height, black bars left/right
                cw = h * videoAspect;
                cx = (w - cw) / 2;
            }
        }
        contentX = cx; contentY = cy; contentW = cw; contentH = ch;

        // Vignettes — proportional height of content area, capped
        double vH = Math.min(ch * 0.28, 90);
        topVignette.resizeRelocate(cx, cy, cw, vH);
        bottomVignette.resizeRelocate(cx, cy + ch - vH, cw, vH);

        // Top bar — full content width, natural height, pinned to content top
        double tbH = snapSizeY(Math.max(topBar.prefHeight(cw), 30));
        topBar.resizeRelocate(cx, cy, cw, tbH);

        // Pill — pref size, horizontally centred, 12 px above content bottom
        double pw = snapSizeX(pillBar.prefWidth(-1));
        double ph = snapSizeY(pillBar.prefHeight(pw));
        pillBar.resizeRelocate(cx + (cw - pw) / 2, cy + ch - ph - 12, pw, ph);

        // Popped overlay — full tile
        if (poppedOverlay.isVisible()) poppedOverlay.resizeRelocate(0, 0, w, h);
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private Region vignette(boolean top) {
        Region r = new Region();
        r.setMouseTransparent(true);
        String dir = top ? "to bottom" : "to top";
        r.setStyle("-fx-background-color: linear-gradient(" + dir + "," +
                "rgba(0,0,0,0.72) 0%,rgba(0,0,0,0.45) 45%," +
                "rgba(0,0,0,0.06) 85%,rgba(0,0,0,0.00) 100%);");
        return r;
    }

    private HBox buildTopBar() {
        Label live = new Label("LIVE");
        live.setStyle("-fx-text-fill:#ff6b6b;-fx-font-size:10px;-fx-font-weight:bold;");

        Region vDivider = new Region();
        vDivider.setStyle("-fx-background-color: rgba(255,255,255,0.35);");
        vDivider.setMinSize(1, 12);
        vDivider.setMaxSize(1, 12);
        vDivider.setPrefSize(1, 12);

        StackPane avatar = buildStreamerAvatar(18);

        Label name = new Label(username);
        name.setStyle("-fx-text-fill:white;-fx-font-size:11px;-fx-font-weight:bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        FontIcon eyeIcon = new FontIcon(Feather.EYE);

        viewerCountLabel = new Label("0");
        viewerCountLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.8); -fx-font-size: 11px; -fx-font-weight: bold;");

        HBox viewerBox = new HBox(4, eyeIcon, viewerCountLabel);
        viewerBox.setAlignment(Pos.CENTER_RIGHT);
        viewerBox.setMouseTransparent(true);

        HBox bar = new HBox(6, liveDot, live, vDivider, avatar, name, spacer, viewerBox);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 0, 10));
        bar.setMouseTransparent(true);
        return bar;
    }

    public void setViewerCount(int count) {
        viewerCountLabel.setText(String.valueOf(count));
    }

    private StackPane buildStreamerAvatar(double size) {
        Circle bg = new Circle(size / 2.0);
        bg.setFill(AvatarColor.forNameJfx(username));

        String initial = (username != null && !username.isEmpty())
                ? String.valueOf(username.charAt(0)).toUpperCase() : "?";
        Label letter = new Label(initial);
        letter.setStyle("-fx-font-size: " + (int) (size * 0.42) + "px; -fx-font-weight: bold; -fx-text-fill: white;");
        letter.setMouseTransparent(true);

        StackPane sp = new StackPane(bg, letter);
        sp.setMinSize(size, size);
        sp.setMaxSize(size, size);
        sp.setAlignment(Pos.CENTER);
        sp.setMouseTransparent(true);

        try {
            UUID uid = UUID.fromString(userId);
            App.getAvatarCache().resolve(uid).thenAcceptAsync(cu -> {
                if (cu == null || cu.avatar() == null || cu.avatar().length == 0) return;
                try {
                    Image img = new Image(new ByteArrayInputStream(cu.avatar()), size, size, true, true);
                    if (!img.isError()) {
                        Platform.runLater(() -> {
                            bg.setFill(new ImagePattern(img));
                            letter.setVisible(false);
                        });
                    }
                } catch (Exception ignored) {}
            });
        } catch (IllegalArgumentException ignored) {}

        return sp;
    }

    private HBox buildPill() {
        Button muteBtn    = chip(Feather.VOLUME_2,      "Mute stream audio",        false);
        Button volChevron = buildVolumeChevron();
        Button popOutBtn  = chip(Feather.EXTERNAL_LINK, "Open in separate window",  false);
        Button leaveBtn   = chip(Feather.LOG_OUT,       "Leave stream",             true);

        boolean[] streamMuted = {false};
        muteBtn.setOnAction(e -> {
            streamMuted[0] = !streamMuted[0];
            ((FontIcon) muteBtn.getGraphic()).setIconCode(streamMuted[0] ? Feather.VOLUME_X : Feather.VOLUME_2);
            Tooltip.install(muteBtn, new Tooltip(streamMuted[0] ? "Unmute stream audio" : "Mute stream audio"));
            App.getWebrtcRoomClient().setStreamAudioMuted(userId, streamMuted[0]);
        });
        expandBtn.setOnAction(e -> { if (onExpand != null) onExpand.run(); });
        popOutBtn.setOnAction(e -> { if (onPopOut != null) onPopOut.run(); });
        fullscreenBtn.setOnAction(e -> { if (onFullscreen != null) onFullscreen.run(); });
        leaveBtn.setOnAction(e ->  { if (onLeave  != null) onLeave.run();  });

        // Group mute toggle + volume chevron so they visually read as one control
        HBox muteGroup = new HBox(2, muteBtn, volChevron);
        muteGroup.setAlignment(Pos.CENTER);

        HBox pill = new HBox(6, expandBtn, muteGroup, popOutBtn, fullscreenBtn, leaveBtn);
        pill.setAlignment(Pos.CENTER);
        pill.setPadding(new Insets(6, 16, 6, 16));
        pill.getStyleClass().add("toolbar-center-island");
        pill.setOnMouseClicked(javafx.event.Event::consume);
        return pill;
    }

    private Button buildVolumeChevron() {
        FontIcon icon = new FontIcon(Feather.CHEVRON_UP);
        icon.setIconSize(11);
        Button btn = new Button(null, icon);
        btn.getStyleClass().add("toolbar-chevron");
        btn.setMinSize(16, 32);
        btn.setMaxSize(16, 32);
        btn.setPrefSize(16, 32);
        btn.setFocusTraversable(false);
        Tooltip.install(btn, new Tooltip("Stream volume"));

        ContextMenu[] activeMenu = {null};
        btn.setOnAction(e -> {
            if (activeMenu[0] != null && activeMenu[0].isShowing()) {
                activeMenu[0].hide();
                return;
            }

            float saved = App.getWebrtcRoomClient().getStreamAudioVolume(userId);
            int savedPct = Math.round(saved * 100);

            Label titleLabel = new Label("Stream Volume");
            titleLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #b5bac1; -fx-font-weight: bold;");

            Label valueLabel = new Label(savedPct + "%");
            valueLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #b5bac1; -fx-min-width: 38px;");
            valueLabel.setAlignment(Pos.CENTER_RIGHT);

            Region spacerH = new Region();
            HBox.setHgrow(spacerH, Priority.ALWAYS);
            HBox header = new HBox(4, titleLabel, spacerH, valueLabel);
            header.setAlignment(Pos.CENTER_LEFT);

            Slider volumeSlider = new Slider(0, 200, savedPct);
            volumeSlider.setPrefWidth(190);
            volumeSlider.setBlockIncrement(5);
            volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                int pct = newVal.intValue();
                valueLabel.setText(pct + "%");
                App.getWebrtcRoomClient().setStreamAudioVolume(userId, pct / 100f);
            });

            VBox content = new VBox(4, header, volumeSlider);
            content.setPadding(new Insets(4, 8, 6, 8));
            content.setPrefWidth(210);

            CustomMenuItem menuItem = new CustomMenuItem(content, false);
            ContextMenu menu = new ContextMenu();
            menu.getItems().add(menuItem);

            // Strip hover highlight from the item cell once it enters the scene
            menu.setOnShown(ev -> Platform.runLater(() -> {
                if (menu.getSkin() != null) {
                    menu.getSkin().getNode().lookupAll(".menu-item").forEach(n ->
                        n.setStyle("-fx-background-color: transparent; -fx-padding: 0;"));
                }
            }));

            menu.setOnHidden(ev -> {
                btn.getStyleClass().remove("showing");
                activeMenu[0] = null;
                // Unpin overlay and let it fade after a short grace period
                overlayPinned = false;
                scheduleHide(HIDE_DELAY_MS);
            });

            // Pin the overlay so it stays visible while the popup is open
            overlayPinned = true;
            if (!overlayVisible) {
                overlayVisible = true;
                pillBar.setMouseTransparent(false);
                fade(true, 120);
            }
            cancelTimer();

            btn.getStyleClass().add("showing");
            menu.setAnchorLocation(javafx.stage.PopupWindow.AnchorLocation.WINDOW_BOTTOM_LEFT);
            javafx.geometry.Bounds b = btn.localToScreen(btn.getBoundsInLocal());
            if (b != null) {
                menu.show(btn, b.getMinX() - 4, b.getMinY() - 3);
            } else {
                menu.show(btn, Side.TOP, 0, -8);
            }
            activeMenu[0] = menu;
        });

        return btn;
    }

    private Button chip(Feather icon, String tip, boolean danger) {
        Button btn = new Button(null, new FontIcon(icon));
        btn.getStyleClass().add(danger ? "toolbar-chip-muted" : "toolbar-chip-ghost");
        btn.setMinSize(32, 32);
        btn.setMaxSize(32, 32);
        btn.setPrefSize(32, 32);
        btn.setFocusTraversable(false);
        Tooltip.install(btn, new Tooltip(tip));
        return btn;
    }

    private StackPane buildPoppedOverlay() {
        FontIcon icon = new FontIcon(Feather.EXTERNAL_LINK);
        icon.setIconSize(22);
        icon.setStyle("-fx-icon-color:#777;");
        Label lbl = new Label("In separate window");
        lbl.setStyle("-fx-text-fill:#777;-fx-font-size:11px;");
        VBox inner = new VBox(8, icon, lbl);
        inner.setAlignment(Pos.CENTER);
        StackPane ov = new StackPane(inner);
        ov.setStyle("-fx-background-color:rgba(0,0,0,0.68);");
        return ov;
    }

    // ── Hover logic ───────────────────────────────────────────────────────────

    private boolean inContentBounds(double x, double y) {
        if (contentW <= 0) return true; // no video yet → full tile is "content"
        return x >= contentX && x <= contentX + contentW
            && y >= contentY && y <= contentY + contentH;
    }

    private void onMouseMove(MouseEvent e) {
        if (poppedOut) return;
        if (inContentBounds(e.getX(), e.getY())) {
            setCursor(Cursor.HAND);
            onActivity();
        } else {
            setCursor(Cursor.DEFAULT);
            hideNow();
        }
    }

    private void onActivity() {
        if (poppedOut) return;
        if (!overlayVisible) {
            overlayVisible = true;
            pillBar.setMouseTransparent(false);
            fade(true, 120);
        }
        scheduleHide(HIDE_DELAY_MS);
    }

    private void hideNow() {
        if (overlayPinned) return;
        cancelTimer();
        if (overlayVisible) {
            overlayVisible = false;
            pillBar.setMouseTransparent(true);
            fade(false, 160);
        }
    }

    private void scheduleHide(long ms) {
        cancelTimer();
        hideTimer = new Timeline(new KeyFrame(Duration.millis(ms), e -> {
            if (overlayPinned) return;
            overlayVisible = false;
            pillBar.setMouseTransparent(true);
            fade(false, 220);
        }));
        hideTimer.play();
    }

    private void cancelTimer() {
        if (hideTimer != null) { hideTimer.stop(); hideTimer = null; }
    }

    private void fade(boolean in, double ms) {
        if (fadeTl != null) fadeTl.stop();
        double t = in ? 1.0 : 0.0;
        fadeTl = new Timeline(new KeyFrame(Duration.millis(ms),
                new KeyValue(topVignette.opacityProperty(),    t, Interpolator.EASE_BOTH),
                new KeyValue(bottomVignette.opacityProperty(), t, Interpolator.EASE_BOTH),
                new KeyValue(topBar.opacityProperty(),         t, Interpolator.EASE_BOTH),
                new KeyValue(pillBar.opacityProperty(),        t, Interpolator.EASE_BOTH)));
        fadeTl.play();
    }

    // ── LIVE dot pulse ────────────────────────────────────────────────────────

    private void startLivePulse() {
        liveTl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(liveDot.scaleXProperty(), 1.0),
                        new KeyValue(liveDot.scaleYProperty(), 1.0),
                        new KeyValue(liveDot.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(700),
                        new KeyValue(liveDot.scaleXProperty(), 1.5),
                        new KeyValue(liveDot.scaleYProperty(), 1.5),
                        new KeyValue(liveDot.opacityProperty(), 0.35)),
                new KeyFrame(Duration.millis(1400),
                        new KeyValue(liveDot.scaleXProperty(), 1.0),
                        new KeyValue(liveDot.scaleYProperty(), 1.0),
                        new KeyValue(liveDot.opacityProperty(), 1.0)));
        liveTl.setCycleCount(Animation.INDEFINITE);
        liveTl.play();
    }

    // ── Pop-out ───────────────────────────────────────────────────────────────

    /**
     * Unsubscribes this tile's VideoSinkView from the WebRTC track and shows
     * the "in separate window" placeholder.  Must be called BEFORE the
     * {@link StreamPopOutWindow} subscribes its own sink.
     */
    public void markPoppedOut(StreamPopOutWindow window) {
        poppedOut    = true;
        popOutWindow = window;
        App.getWebrtcRoomClient().unsubscribeRemoteVideo(userId);
        hideNow();
        poppedOverlay.setVisible(true);
        requestLayout();
    }

    /**
     * Called by the pop-out window after it has unsubscribed its own sink.
     * Creates a fresh VideoSinkView, resubscribes to the WebRTC track, and
     * hides the placeholder.
     */
    public void reattachAfterPopIn() {
        poppedOut    = false;
        popOutWindow = null;
        poppedOverlay.setVisible(false);

        videoSinkView.dispose();
        videoSinkView = new VideoSinkView(0, 6);
        videoSinkView.setOnFirstFrameReceived(
                () -> Platform.runLater(() -> waitingLabel.setVisible(false)));
        waitingLabel.setVisible(true);

        getChildren().set(0, videoSinkView); // index 0 is always the video
        requestLayout();
        App.getWebrtcRoomClient().subscribeToRemoteVideo(userId, videoSinkView);
    }

    // ── Expand button ─────────────────────────────────────────────────────────

    public void setExpandButtonVisible(boolean visible) {
        expandBtn.setVisible(visible);
        expandBtn.setManaged(visible);
    }

    public void setExpanded(boolean expanded) {
        ((FontIcon) expandBtn.getGraphic()).setIconCode(expanded ? Feather.MINIMIZE_2 : Feather.MAXIMIZE_2);
        expandTooltip.setText(expanded ? "Collapse" : "Expand");
    }

    // ── Fullscreen button ─────────────────────────────────────────────────────

    public void setFullscreenState(boolean fullscreen) {
        ((FontIcon) fullscreenBtn.getGraphic()).setIconCode(fullscreen ? Feather.MINIMIZE : Feather.MAXIMIZE);
        fullscreenTooltip.setText(fullscreen ? "Exit Fullscreen" : "Fullscreen");
    }

    // ── Disposal ──────────────────────────────────────────────────────────────

    public void dispose() {
        if (liveTl    != null) { liveTl.stop();  liveTl  = null; }
        if (fadeTl    != null) { fadeTl.stop();  fadeTl  = null; }
        cancelTimer();
        if (popOutWindow != null) { popOutWindow.closeAndCancel(); popOutWindow = null; }
        else if (!poppedOut) App.getWebrtcRoomClient().unsubscribeRemoteVideo(userId);
        videoSinkView.dispose();
    }
}
