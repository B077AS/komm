package komm;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import atlantafx.base.controls.ModalPane;
import io.github.b077as.emojifx.EmojiInitializer;
import io.github.b077as.emojifx.EmojiVendor;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.beans.value.ChangeListener;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import komm.ui.chat.ChatSection;
import komm.ui.pages.DirectMessagePage;
import lombok.Setter;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import komm.ui.customnodes.CustomNotification;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import komm.api.ServiceContainer;
import komm.model.dto.summary.MainUserSummary;
import komm.model.dto.summary.ServerSummary;
import komm.model.permissions.PermissionManager;
import komm.ui.avatar.AvatarCache;
import komm.ui.pages.HomePage;
import komm.ui.pages.LoginPage;
import komm.ui.pages.ServerPage;
import komm.ui.utils.WindowsThemeUtil;
import komm.utils.AppConfig;
import komm.utils.GlobalHotkeyManager;
import komm.utils.KommUtils;
import komm.utils.UserSettings;
import komm.webrtc.WebrtcRoomClient;
import komm.websocket.AppWebSocketClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static com.sun.jna.Platform.isWindows;

@Slf4j
public class App extends Application {

    @Getter
    public static StackPane stackPane;
    @Getter
    public static ModalPane modalPane;
    @Getter
    private static MainUserSummary user;
    @Getter
    public static WebrtcRoomClient webrtcRoomClient;
    @Getter
    @Setter
    private static ServerPage cachedServerPage;
    @Getter
    private static HomePage cachedHomePage;
    @Getter
    @Setter
    private static DirectMessagePage cachedDirectMessagePage;
    @Getter
    private static AvatarCache avatarCache;
    @Getter
    private static final ServiceContainer services = ServiceContainer.getInstance();

    @Getter
    private static final PermissionManager permissionManager = new PermissionManager();
    @Getter
    private static javafx.scene.Scene scene;

    // ── DM unread tracking ────────────────────────────────────────────────────
    private static final BooleanProperty dmUnread = new SimpleBooleanProperty(false);
    private static final Set<UUID> pendingDmUnreadPartners = new HashSet<>();

    public static BooleanProperty dmUnreadProperty() {
        return dmUnread;
    }

    public static boolean isDmUnread() {
        return dmUnread.get();
    }

    public static void setDmUnread(boolean value) {
        dmUnread.set(value);
    }

    // ── Friend request pending tracking ──────────────────────────────────────
    private static final BooleanProperty friendRequestPending = new SimpleBooleanProperty(false);

    public static BooleanProperty friendRequestPendingProperty() {
        return friendRequestPending;
    }

    public static boolean isFriendRequestPending() {
        return friendRequestPending.get();
    }

    public static void setFriendRequestPending(boolean value) {
        friendRequestPending.set(value);
    }

    public static void addPendingDmUnreadPartner(UUID partnerId) {
        pendingDmUnreadPartners.add(partnerId);
    }

    public static Set<UUID> getAndClearPendingDmUnreadPartners() {
        Set<UUID> result = new HashSet<>(pendingDmUnreadPartners);
        pendingDmUnreadPartners.clear();
        return result;
    }

    // Clear a single pending unread partner (e.g. conversation deleted/hidden before
    // the DM page was ever opened) and drop the global badge if nothing is left.
    public static void removePendingDmUnreadPartner(UUID partnerId) {
        if (pendingDmUnreadPartners.remove(partnerId) && pendingDmUnreadPartners.isEmpty()) {
            dmUnread.set(false);
        }
    }

    private static AppWebSocketClient webSocketClient;
    private static Thread webRtcThread;
    private static StackPane mainStackPane;
    private static final ArrayDeque<ModalPane> modalPaneStack = new ArrayDeque<>();

    // ── User setter ───────────────────────────────────────────────────────────

    public static void setUser(MainUserSummary newUser) {
        if (newUser != null && user != null) {
            newUser.setMicEnabled(user.isMicEnabled());
            newUser.setSpeakerEnabled(user.isSpeakerEnabled());
        }
        user = newUser;
        if (newUser != null) {
            UserSettings.init(newUser.getUserId());
            komm.ui.theme.ThemeManager.get().applyFromSettings();
            GlobalHotkeyManager hkm = GlobalHotkeyManager.getInstance();
            hkm.loadBindings();
            hkm.setMuteAction(() ->
                    Platform.runLater(() -> AppState.applyMicEnabled(!AppState.micEnabledProperty().get())));
            hkm.setDeafenAction(() ->
                    Platform.runLater(() -> AppState.applySpeakerEnabled(!AppState.speakerEnabledProperty().get())));
        } else {
            GlobalHotkeyManager.getInstance().clearBindings();
        }
        AppState.syncFromUser(newUser);
    }

    // ── Application lifecycle ─────────────────────────────────────────────────

    @Override
    public void start(Stage primaryStage) throws Exception {
        EmojiInitializer.initialize(EmojiVendor.TWITTER, Launcher.getEmojiDirectory())
                .thenAccept(ok -> {
                    if (!ok) log.warn("Emoji assets failed to load");
                })
                .join();

        Image icon = new Image(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("icon.png")));
        primaryStage.getIcons().add(icon);

        avatarCache = new AvatarCache();
        stackPane = new StackPane();
        modalPane = new ModalPane();
        modalPane.setPersistent(false);
        setupTransitions(modalPane);

        mainStackPane = new StackPane();
        mainStackPane.getChildren().addAll(stackPane, modalPane);

        if (tryAutoLogin()) {
            Platform.runLater(() -> {
                App.changePage(App.getOrCreateHomePage());
                checkPendingInvite();
            });
        } else {
            changePage(new LoginPage());
        }

        scene = new Scene(mainStackPane, 1305, 500);
        Application.setUserAgentStylesheet(
                Objects.requireNonNull(App.class.getClassLoader().getResource("style.css")).toExternalForm());

        primaryStage.setTitle("Komm");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1305);
        primaryStage.setMinHeight(780);
        primaryStage.setMaximized(true);
        primaryStage.setOpacity(0);
        // Stream pop-out windows are independent top-level stages, so JavaFX's
        // implicit exit (which waits for every window to close) would otherwise
        // leave the app — and the live voice/stream connection — running in the
        // background after the main window is closed. Force a full shutdown instead.
        primaryStage.setOnCloseRequest(e -> Platform.exit());
        primaryStage.show();

        komm.ui.theme.ThemeManager.get().init();
        komm.ui.theme.ThemeManager.get().applyFromSettings();

        if (isWindows()) {
            Thread.ofVirtual().start(() -> {
                WindowsThemeUtil.enableDarkTitleBar("Komm");
                Platform.runLater(() -> primaryStage.setOpacity(1));
            });
        } else {
            Platform.runLater(() -> primaryStage.setOpacity(1));
        }
        initializeWebRTCDelayed();
        initializeGlobalHotkeys();
    }

    public static void startWebSocket() {
        if (services.hub().getTokenManager().getAccessToken() == null) {
            log.warn("Cannot start WebSocket — no access token");
            return;
        }
        stopWebSocket();
        webSocketClient = new AppWebSocketClient(AppConfig.getInstance().getWebSocketUrl(), services.hub().getTokenManager());
        webSocketClient.setOnDisconnect(reason -> Platform.runLater(() -> {
            String body ="Reconnecting in " + AppWebSocketClient.RECONNECT_DELAY_SEC + "s...";
            new CustomNotification("Connection Lost", body,
                    new FontIcon(MaterialDesignL.LAN_DISCONNECT)).showNotification();
        }));
        webSocketClient.connect();
        log.info("WebSocket client started");
    }

    public static void stopWebSocket() {
        if (webSocketClient != null) {
            webSocketClient.shutdown();
            webSocketClient = null;
            log.info("WebSocket client stopped");
        }
    }

    private boolean tryAutoLogin() {
        String refreshToken = loadRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            log.debug("No refresh token found, skipping auto-login");
            return false;
        }
        try {
            services.hub().getTokenManager().setTokens(null, refreshToken);
            services.hub().getTokenManager().refreshAccessToken();
            setUser(services.hub().getUserService().getCurrentUser());
            log.info("Auto-login successful for user: {}", user.getUsername());
            startWebSocket();
            return true;
        } catch (Exception e) {
            log.warn("Auto-login failed: {}", e.getMessage());
            services.hub().getTokenManager().clearTokens();
            return false;
        }
    }

    public static String loadRefreshToken() {
        File tokenFile = new File(Launcher.getCredentialsFile().toString());
        if (!tokenFile.exists()) {
            log.debug("Credentials file not found at: {}", tokenFile.getPath());
            return null;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(tokenFile)) {
            props.load(in);
            String token = props.getProperty("refresh_token");
            log.debug("Refresh token {}", token != null ? "loaded successfully" : "not found in credentials file");
            return token;
        } catch (IOException e) {
            log.error("Failed to load refresh token: {}", e.getMessage());
            return null;
        }
    }

    public static void logout() {
        log.info("Logging out user: {}", user != null ? user.getUsername() : "unknown");
        exitStreamFullscreen();
        komm.ui.screenshare.StreamPopOutWindow.closeAll();
        user = null;
        cachedHomePage = null;
        cachedServerPage = null;
        cachedDirectMessagePage = null;
        permissionManager.clear();
        disconnectFromVoice();
        stopWebSocket();
        services.hub().getTokenManager().clearTokens();
        File tokenFile = new File(Launcher.getCredentialsFile().toString());
        if (tokenFile.exists()) tokenFile.delete();
        changePage(new LoginPage());
    }

    private void initializeWebRTCDelayed() {
        webRtcThread = new Thread(() -> {
            webrtcRoomClient = new WebrtcRoomClient();
            webrtcRoomClient.start();
            log.debug("WebRTC Initialization complete");
        });
        webRtcThread.setName("webrtc-mta-thread");
        webRtcThread.setDaemon(true);
        webRtcThread.start();
    }

    private void initializeGlobalHotkeys() {
        Thread t = new Thread(() -> GlobalHotkeyManager.getInstance().start());
        t.setName("global-hotkey-init");
        t.setDaemon(true);
        t.start();
    }

    public static void disconnectFromVoice() {
        if (webrtcRoomClient != null && webrtcRoomClient.isInChannel()) {
            webrtcRoomClient.disconnectFromChannel();
        }
    }

    private static void stopWebRTC() {
        if (webrtcRoomClient != null) {
            try {
                webrtcRoomClient.stop();
            } catch (Exception e) {
                log.debug("[WebRTC] Error during stop: {}", e.getMessage());
            }
            webrtcRoomClient = null;
        }
        if (webRtcThread != null && webRtcThread.isAlive()) {
            webRtcThread.interrupt();
            try {
                webRtcThread.join(3000);
                if (webRtcThread.isAlive())
                    log.debug("[WebRTC] Thread did not stop within 3 s — continuing shutdown");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            webRtcThread = null;
        }
    }

    public static void changePage(Node node) {
        Node current = stackPane.getChildren().isEmpty() ? null : stackPane.getChildren().getFirst();
        log.debug("[scroll] changePage from={} to={}", current == null ? "null" : current.getClass().getSimpleName(), node.getClass().getSimpleName());
        if (current instanceof ServerPage sp) {
            sp.getChatSection().saveScrollPosition();
            if (!(node instanceof ServerPage)) {
                if (node instanceof HomePage && cachedHomePage != null) {
                    cachedHomePage.syncServerPermissions(
                            sp.getServer().getServerId(),
                            permissionManager.getEffectivePermissions());
                }
                permissionManager.clear();
            }
        }

        stackPane.getChildren().clear();
        stackPane.getChildren().add(node);

        if (node instanceof ServerPage sp) {
            // Auto-start loading for pages reached without going through connectToServer
            if (sp.getChannelSection().getLoadChannelsService().getState() == Worker.State.READY) {
                sp.getChannelSection().startLoading();
            }
            sp.getChatSection().notifyPageResumed();
            // Permissions were cleared when we left this server page. Re-fetch them now
            // so that channel-join checks work correctly on a cached (resumed) page.
            sp.getChannelSection().reloadPermissions();
        }
    }

    public static Node getCurrentPage() {
        return stackPane.getChildren().getFirst();
    }

    /**
     * The primary application stage, or {@code null} before the scene is shown.
     */
    public static Stage getPrimaryStage() {
        if (scene == null) return null;
        return (scene.getWindow() instanceof Stage s) ? s : null;
    }

    /**
     * Whether the app window currently has OS focus (false if another app is focused
     * or the window is minimized). Used to decide whether to play notification sounds.
     */
    public static boolean isWindowFocused() {
        Stage stage = getPrimaryStage();
        return stage != null && stage.isFocused();
    }

    public static void showModal(Node modal) {
        if (modalPane.isDisplay()) {
            int nextOrder = ModalPane.Z_FRONT - (modalPaneStack.size() + 1) * 5;
            ModalPane overlay = new ModalPane(nextOrder);
            overlay.setPersistent(false);
            setupTransitions(overlay);
            overlay.displayProperty().addListener((obs, was, now) -> {
                if (!now) {
                    modalPaneStack.remove(overlay);
                    mainStackPane.getChildren().remove(overlay);
                }
            });
            mainStackPane.getChildren().add(overlay);
            overlay.applyCss();
            modalPaneStack.push(overlay);
            overlay.setContent(modal);
            modal.applyCss();
            Platform.runLater(() -> {
                overlay.setDisplay(true);
                modal.requestFocus();
            });
            return;
        }

        Node cur = stackPane.getChildren().isEmpty() ? null : stackPane.getChildren().getFirst();
        // Freeze the chat scroll while a modal is open so opening/closing it doesn't
        // rebase the scroll position. Applies to both the server (channel) page and
        // the direct-message page.
        Runnable freeze = null, unfreeze = null;
        if (cur instanceof ServerPage sp) {
            var chat = sp.getChatSection();
            freeze = chat::freezeScroll;
            unfreeze = chat::unfreezeScroll;
        } else if (cur instanceof DirectMessagePage dp) {
            var chat = dp.getChatSection();
            freeze = chat::freezeScroll;
            unfreeze = chat::unfreezeScroll;
        } else if (cur instanceof HomePage hp && hp.getActiveDmChatSection() != null) {
            // The DM page is embedded inside HomePage, so the current page is HomePage.
            var chat = hp.getActiveDmChatSection();
            freeze = chat::freezeScroll;
            unfreeze = chat::unfreezeScroll;
        }
        if (freeze != null) {
            freeze.run();
            final Runnable unfreezeFinal = unfreeze;

            @SuppressWarnings("unchecked")
            ChangeListener<Boolean>[] l = new ChangeListener[1];
            l[0] = (obs, was, now) -> {
                if (!now) {
                    modalPane.displayProperty().removeListener(l[0]);
                    PauseTransition delay = new PauseTransition(Duration.millis(250));
                    delay.setOnFinished(e -> unfreezeFinal.run());
                    delay.play();
                }
            };
            modalPane.displayProperty().addListener(l[0]);
        }

        modalPane.setContent(modal);
        modal.applyCss();
        Platform.runLater(() -> {
            modalPane.setDisplay(true);
            modal.requestFocus();
        });
    }

    public static void closeModal() {
        if (!modalPaneStack.isEmpty()) {
            modalPaneStack.peek().hide(true);
        } else {
            modalPane.hide(true);
        }
    }

    // ── Stream fullscreen ─────────────────────────────────────────────────────
    // Reparents a single stream node (e.g. a StreamTile) into a dedicated
    // black overlay above everything else and puts the primary stage into true
    // OS-level full screen. ESC (JavaFX's built-in full-screen exit key) and any
    // other path that clears fullScreenProperty are all handled the same way via
    // the listener below, so there is exactly one teardown path.

    private static StackPane streamFullscreenLayer;
    private static Runnable streamFullscreenOnExit;
    private static ChangeListener<Boolean> streamFullscreenListener;

    /** Whether a stream is currently occupying the full-screen overlay. */
    public static boolean isStreamFullscreenActive() {
        return streamFullscreenLayer != null;
    }

    /**
     * Moves {@code streamNode} out of its current parent and into a full-screen
     * overlay, then switches the primary stage to full-screen mode.
     * {@code onExit} runs exactly once, whenever fullscreen ends (ESC, the
     * caller's own toggle button, or a forced {@link #exitStreamFullscreen()}) —
     * by then the node has already been detached so the caller is free to
     * reparent it back into its normal layout.
     */
    public static void enterStreamFullscreen(Node streamNode, Runnable onExit) {
        if (streamFullscreenLayer != null) exitStreamFullscreen();

        if (streamNode.getParent() instanceof javafx.scene.layout.Pane p) {
            p.getChildren().remove(streamNode);
        }

        streamFullscreenLayer = new StackPane(streamNode);
        streamFullscreenLayer.setStyle("-fx-background-color: black;");
        mainStackPane.getChildren().add(streamFullscreenLayer);
        streamFullscreenOnExit = onExit;

        Stage stage = getPrimaryStage();
        streamFullscreenListener = (obs, was, isNow) -> {
            if (!isNow) exitStreamFullscreen();
        };
        stage.fullScreenProperty().addListener(streamFullscreenListener);
        stage.setFullScreen(true);
    }

    /** No-op if no stream is currently full-screen. Safe to call defensively from any teardown path. */
    public static void exitStreamFullscreen() {
        if (streamFullscreenLayer == null) return;

        Stage stage = getPrimaryStage();
        if (streamFullscreenListener != null) {
            stage.fullScreenProperty().removeListener(streamFullscreenListener);
            streamFullscreenListener = null;
        }
        if (stage.isFullScreen()) stage.setFullScreen(false);

        streamFullscreenLayer.getChildren().clear(); // detach the stream node before the caller reparents it
        mainStackPane.getChildren().remove(streamFullscreenLayer);
        streamFullscreenLayer = null;

        Runnable cb = streamFullscreenOnExit;
        streamFullscreenOnExit = null;
        if (cb != null) cb.run();
    }

    private static void setupTransitions(ModalPane pane) {
        pane.setInTransitionFactory(node -> {
            ScaleTransition scale = new ScaleTransition(Duration.millis(150), node);
            scale.setFromX(0.92);
            scale.setFromY(0.92);
            scale.setToX(1.0);
            scale.setToY(1.0);
            scale.setInterpolator(Interpolator.EASE_OUT);

            FadeTransition fade = new FadeTransition(Duration.millis(150), node);
            fade.setFromValue(0.0);
            fade.setToValue(1.0);
            fade.setInterpolator(Interpolator.EASE_OUT);

            return new ParallelTransition(scale, fade);
        });
        pane.setOutTransitionFactory(node -> {
            FadeTransition fade = new FadeTransition(Duration.millis(80), node);
            fade.setFromValue(1.0);
            fade.setToValue(0.0);
            fade.setInterpolator(Interpolator.EASE_IN);
            return fade;
        });
    }

    public static AppWebSocketClient getHubWsClient() {
        return webSocketClient;
    }

    public static void onMyRoleChanged(UUID serverId, ServerSummary.Role newRole) {
        if (cachedServerPage != null && cachedServerPage.getServer().getServerId().equals(serverId)) {
            cachedServerPage.getServer().setRole(newRole);
        }
        if (cachedHomePage != null) {
            cachedHomePage.syncServerRole(serverId, newRole);
        }
        onPermissionsChanged(serverId);
    }

    public static void onPermissionsChanged(UUID serverId) {
        ServerPage sp = cachedServerPage;
        if (sp != null && sp.getServer().getServerId().equals(serverId)) {
            sp.getChannelSection().refreshPermissionUI();
        }
        if (cachedHomePage != null) {
            cachedHomePage.syncServerPermissions(serverId, permissionManager.getEffectivePermissions());
        }
    }

    public static ServerPage getOrCreateServerPage(ServerSummary server) {
        if (cachedServerPage == null ||
                !cachedServerPage.getServer().getServerId().equals(server.getServerId())) {
            if (cachedServerPage != null) {
                // The old page is being replaced — dispose its stream tiles and
                // close any pop-out stream windows, otherwise they would be
                // orphaned and never receive the STREAM_ENDED teardown.
                cachedServerPage.getChatSection().hideScreenShare();
            }
            cachedServerPage = new ServerPage(server);
        }
        return cachedServerPage;
    }

    private static void scheduleFirstUseWarmUp() {
        // Pre-load Ikonli fonts on the FX thread so the virtual thread that builds
        // ServerPage never needs to post font-registration work back to the FX thread.
        // A single FontIcon per font file is enough to cache the whole file.
        Platform.runLater(() -> {
            new FontIcon(MaterialDesignA.ACCOUNT_OUTLINE); // loads materialdesign2.ttf (all MD2 packs share it)
            new FontIcon(Feather.CHECK);                   // loads feather.ttf
        });

        // Pre-warm ChatSection on a virtual thread to trigger one-time static
        // initialisation (EmojiData, StreamSection, MessageInputBox, etc.)
        // so the first Connect doesn't pay that cost on the critical path.
        Thread.ofVirtual().start(() -> {
            try {
                new ChatSection();
            } catch (Exception e) {
                log.debug("ChatSection warmup exception (benign): {}", e.getMessage());
            }
        });
    }

    public static void setCachedHomePage(HomePage page) {
        cachedHomePage = page;
        cachedHomePage.startLoading();
        scheduleFirstUseWarmUp();
    }

    public static HomePage getOrCreateHomePage() {
        if (cachedHomePage == null) {
            cachedHomePage = new HomePage();
            cachedHomePage.startLoading();
            scheduleFirstUseWarmUp();
        }
        return cachedHomePage;
    }

    @Override
    public void stop() throws Exception {
        log.info("Application shutting down");
        exitStreamFullscreen();
        komm.ui.screenshare.StreamPopOutWindow.closeAll();
        disconnectFromVoice();
        stopWebRTC();
        stopWebSocket();
        GlobalHotkeyManager.getInstance().stop();
        ServiceContainer.reset();
        String refreshToken = services.hub().getTokenManager().getRefreshToken();
        if (refreshToken != null) KommUtils.saveRefreshToken(refreshToken);
        super.stop();
        // Force-exit to kill non-daemon threads left by Grizzly (WebSocket transport),
        // webrtc-java native threads, and jnativehook that survive explicit cleanup.
        System.exit(0);
    }

    public static void appStart(String[] args) {
        launch(args);
    }

    public static void checkPendingInvite() {
        String code = Launcher.getPendingInviteCode();
        if (code == null || code.isBlank()) return;
        Launcher.clearPendingInviteCode();
        Platform.runLater(() -> App.showModal(new komm.ui.modals.JoinViaInviteModal(code)));
    }
}
