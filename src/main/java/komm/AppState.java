package komm;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import komm.model.dto.summary.MainUserSummary;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.UserDeafenedPayload;
import komm.websocket.messages.payloads.UserMutedPayload;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Observable audio and status state shared across all UI components.
 * <p>
 * All components that display or control mic/speaker/status must read from and
 * write through this class. Never mutate WebRTC or send WS audio messages directly.
 */
@Slf4j
public class AppState {

    private AppState() {
    }

    // ── Observable properties ─────────────────────────────────────────────────

    private static final BooleanProperty micEnabledProp =
            new SimpleBooleanProperty(true);
    private static final BooleanProperty speakerEnabledProp =
            new SimpleBooleanProperty(true);
    private static final BooleanProperty serverMicEnabledProp =
            new SimpleBooleanProperty(true);
    private static final BooleanProperty serverSpeakerEnabledProp =
            new SimpleBooleanProperty(true);
    private static final ObjectProperty<UserStatus> userStatusProp =
            new SimpleObjectProperty<>(UserStatus.ONLINE);
    /**
     * Bumped whenever the logged-in user's own avatar changes. Live components that
     * render the self avatar (toolbar, own connected-user card) listen to this and
     * re-read the fresh bytes from {@link App#getUser()} / the avatar cache.
     */
    private static final IntegerProperty selfAvatarRevisionProp =
            new SimpleIntegerProperty(0);
    private static volatile boolean pokesEnabled = true;

    /**
     * Tracks whether the mic was on when the user self-deafened, so undeafening can restore it.
     * Starts {@code true} so that loading the app already-deafened (flag never set by a
     * deafen action in this session) still restores the mic on first undeafen.
     */
    private static volatile boolean micWasEnabledBeforeDeafen = true;

    // ── AV state persistence (debounced) ──────────────────────────────────────

    /** Quiet period after the last toggle before the settled state is persisted to the hub. */
    private static final long AV_PERSIST_DEBOUNCE_MS = 1500;

    private static final ScheduledExecutorService avPersistExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "av-state-persist");
                t.setDaemon(true);
                return t;
            });
    private static ScheduledFuture<?> pendingAvPersist;

    /**
     * Last values successfully persisted to the hub, used to skip redundant calls.
     * {@code null} = unknown (nothing persisted this session yet), so the first
     * settled toggle always goes out; the hub skips the write if nothing changed.
     */
    private static volatile Boolean lastPersistedMic;
    private static volatile Boolean lastPersistedSpeaker;

    public static BooleanProperty micEnabledProperty() {
        return micEnabledProp;
    }

    public static BooleanProperty speakerEnabledProperty() {
        return speakerEnabledProp;
    }

    public static BooleanProperty serverMicEnabledProperty() {
        return serverMicEnabledProp;
    }

    public static BooleanProperty serverSpeakerEnabledProperty() {
        return serverSpeakerEnabledProp;
    }

    public static ObjectProperty<UserStatus> userStatusProperty() {
        return userStatusProp;
    }

    public static IntegerProperty selfAvatarRevisionProperty() {
        return selfAvatarRevisionProp;
    }

    public static boolean isPokesEnabled() {
        return pokesEnabled;
    }

    public static void setPokesEnabled(boolean enabled) {
        pokesEnabled = enabled;
    }

    /**
     * Signals that the logged-in user's own avatar bytes changed. Safe to call from any thread.
     */
    public static void notifySelfAvatarChanged() {
        Platform.runLater(() -> selfAvatarRevisionProp.set(selfAvatarRevisionProp.get() + 1));
    }

    // ── Sync from user model ──────────────────────────────────────────────────

    /**
     * Called by App.setUser() — pushes the user model's values into the observable properties.
     */
    static void syncFromUser(MainUserSummary user) {
        if (user == null) return;
        micEnabledProp.set(user.isMicEnabled());
        speakerEnabledProp.set(user.isSpeakerEnabled());
        UserStatus s = user.getStatus();
        // OFFLINE means the backend hasn't marked us online yet; UNKNOWN means the field was absent.
        // Both resolve to ONLINE since the user is actively connected.
        userStatusProp.set(s != null && s != UserStatus.OFFLINE && s != UserStatus.UNKNOWN ? s : UserStatus.ONLINE);

        // Sync the pre-deafen mic flag with the loaded state:
        // - Deafened on load → assume mic was on (we can't know, but this is the right default)
        // - Not deafened on load → clear the flag so future deafen/undeafen works from a clean slate
        micWasEnabledBeforeDeafen = !user.isSpeakerEnabled();

        // Keep WebRTC client in sync with user model
        var rtc = App.getWebrtcRoomClient();
        if (rtc != null) {
            rtc.setMicrophoneMuted(!user.isMicEnabled());
            rtc.setSpeakerMuted(!user.isSpeakerEnabled());
        }
    }

    public static void syncStatusFromUser(MainUserSummary user) {
        if (user == null) return;
        UserStatus s = user.getStatus();
        userStatusProp.set(s != null && s != UserStatus.OFFLINE && s != UserStatus.UNKNOWN ? s : UserStatus.ONLINE);
    }

    // ── Audio control ─────────────────────────────────────────────────────────

    /**
     * Enable or disable the microphone.
     * Unmuting also undeafens headphones; muting leaves headphones unaltered.
     * Blocked when the server has muted this user.
     */
    public static void applyMicEnabled(boolean enabled) {
        if (enabled && !serverMicEnabledProp.get()) return;
        var rtc = App.getWebrtcRoomClient();
        if (rtc != null) rtc.setMicrophoneMuted(!enabled);
        var user = App.getUser();
        if (user != null) user.setMicEnabled(enabled);
        micEnabledProp.set(enabled);
        sendMicWs(enabled);

        if (enabled && !speakerEnabledProp.get()) {
            if (rtc != null) rtc.setSpeakerMuted(false);
            if (user != null) user.setSpeakerEnabled(true);
            speakerEnabledProp.set(true);
            sendSpeakerWs(true);
        }
        schedulePersistAvState();
    }

    /**
     * Enable or disable the speaker (headphones / deafen).
     * Deafening also mutes the mic and remembers whether it was on.
     * Undeafening restores the mic if it was on before deafening.
     * Blocked when the server has deafened this user.
     */
    public static void applySpeakerEnabled(boolean enabled) {
        if (enabled && !serverSpeakerEnabledProp.get()) return;
        var rtc = App.getWebrtcRoomClient();
        if (rtc != null) rtc.setSpeakerMuted(!enabled);
        var user = App.getUser();
        if (user != null) user.setSpeakerEnabled(enabled);
        speakerEnabledProp.set(enabled);
        sendSpeakerWs(enabled);

        if (!enabled) {
            micWasEnabledBeforeDeafen = micEnabledProp.get();
            if (micEnabledProp.get()) {
                if (rtc != null) rtc.setMicrophoneMuted(true);
                if (user != null) user.setMicEnabled(false);
                micEnabledProp.set(false);
                sendMicWs(false);
            }
        } else {
            if (micWasEnabledBeforeDeafen) {
                micWasEnabledBeforeDeafen = false;
                applyMicEnabled(true);
            }
        }
        schedulePersistAvState();
    }

    /**
     * Called when the server applies or lifts a server-side mute.
     */
    public static void applyServerMicEnabled(boolean serverMicEnabled) {
        serverMicEnabledProp.set(serverMicEnabled);
        var rtc = App.getWebrtcRoomClient();
        if (!serverMicEnabled) {
            if (rtc != null) rtc.setMicrophoneMuted(true);
        } else {
            if (rtc != null) rtc.setMicrophoneMuted(!micEnabledProp.get());
        }
    }

    /**
     * Called when the server applies or lifts a server-side deafen.
     */
    public static void applyServerSpeakerEnabled(boolean serverSpeakerEnabled) {
        serverSpeakerEnabledProp.set(serverSpeakerEnabled);
        var rtc = App.getWebrtcRoomClient();
        if (!serverSpeakerEnabled) {
            if (rtc != null) rtc.setSpeakerMuted(true);
        } else {
            if (rtc != null) rtc.setSpeakerMuted(!speakerEnabledProp.get());
        }
    }

    /**
     * Resets server mute/deafen state when leaving a voice channel.
     */
    public static void resetServerAudioState() {
        serverMicEnabledProp.set(true);
        serverSpeakerEnabledProp.set(true);
    }

    // ── Status control ────────────────────────────────────────────────────────

    private static UserStatus pendingNewStatus;
    private static UserStatus pendingPreviousStatus;

    private static final Service<MainUserSummary> statusService = new Service<>() {
        @Override
        protected Task<MainUserSummary> createTask() {
            final UserStatus status = pendingNewStatus;
            return new Task<>() {
                @Override
                protected MainUserSummary call() throws Exception {
                    return App.getServices().hub().getUserService().updateStatus(status);
                }
            };
        }
    };

    static {
        statusService.setOnSucceeded(e -> {
            MainUserSummary updated = statusService.getValue();
            App.setUser(updated);
            UserStatus confirmed = updated.getStatus();
            userStatusProp.set(confirmed != null ? confirmed : pendingPreviousStatus);
        });
        statusService.setOnFailed(e -> {
            log.warn("Status update failed, reverting: {}", statusService.getException().getMessage());
            userStatusProp.set(pendingPreviousStatus);
            var u = App.getUser();
            if (u != null) u.setStatus(pendingPreviousStatus);
        });
    }

    /**
     * Optimistically applies the new status in the UI and persists it to the hub.
     * Reverts to the previous status if the API call fails.
     */
    public static void requestStatusChange(UserStatus newStatus) {
        UserStatus previous = userStatusProp.get();
        if (newStatus == previous) return;

        userStatusProp.set(newStatus);
        var user = App.getUser();
        if (user != null) user.setStatus(newStatus);

        pendingNewStatus = newStatus;
        pendingPreviousStatus = previous;
        statusService.restart();
    }

    // ── WebSocket helpers ─────────────────────────────────────────────────────

    private static void sendMicWs(boolean micEnabled) {
        var rtc = App.getWebrtcRoomClient();
        // Guard: only send if the user has started joining a voice channel.
        // We intentionally use currentChannelId != null rather than isInChannel()
        // so the WS message is not dropped during the ~1-second WebRTC setup window
        // between CHANNEL_JOIN and the publisher peer-connection reaching CONNECTED.
        if (rtc == null || rtc.getCurrentChannelId() == null) return;
        try {
            App.getServices().installation().getWsClient().send(
                    WsMessageType.USER_MUTED,
                    UserMutedPayload.builder()
                            .userId(App.getUser() != null ? App.getUser().getUserId() : null)
                            .micEnabled(micEnabled)
                            .build());
        } catch (Exception ignored) {
        }
    }

    private static void sendSpeakerWs(boolean speakerEnabled) {
        var rtc = App.getWebrtcRoomClient();
        if (rtc == null || rtc.getCurrentChannelId() == null) return;
        try {
            App.getServices().installation().getWsClient().send(
                    WsMessageType.USER_DEAFENED,
                    UserDeafenedPayload.builder()
                            .userId(App.getUser() != null ? App.getUser().getUserId() : null)
                            .speakerEnabled(speakerEnabled)
                            .build());
        } catch (Exception ignored) {
        }
    }

    // ── Hub persistence (debounced) ───────────────────────────────────────────

    /**
     * Debounced persistence of the mic/speaker toggles to the hub: every toggle
     * (re)starts the timer, so rapid toggling and the deafen/undeafen cascades
     * collapse into a single PATCH carrying the settled state. Best-effort — local
     * state stays authoritative; the hub copy is only read back at the next login.
     */
    private static synchronized void schedulePersistAvState() {
        boolean mic = micEnabledProp.get();
        boolean speaker = speakerEnabledProp.get();
        if (pendingAvPersist != null) pendingAvPersist.cancel(false);
        pendingAvPersist = avPersistExecutor.schedule(
                () -> persistAvState(mic, speaker), AV_PERSIST_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private static void persistAvState(boolean micEnabled, boolean speakerEnabled) {
        if (lastPersistedMic != null && lastPersistedMic == micEnabled
                && lastPersistedSpeaker != null && lastPersistedSpeaker == speakerEnabled) {
            return;
        }
        try {
            App.getServices().hub().getUserService().updateAvState(micEnabled, speakerEnabled);
            lastPersistedMic = micEnabled;
            lastPersistedSpeaker = speakerEnabled;
        } catch (Exception e) {
            log.warn("Failed to persist mic/speaker state to hub: {}", e.getMessage());
        }
    }
}
