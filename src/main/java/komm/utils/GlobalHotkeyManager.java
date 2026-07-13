package komm.utils;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

@Slf4j
public class GlobalHotkeyManager implements NativeKeyListener {

    private static GlobalHotkeyManager instance;

    private final Set<Integer> pressedKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> firedActions = ConcurrentHashMap.newKeySet();

    private volatile int[] muteKeys = new int[0];
    private volatile int[] deafenKeys = new int[0];
    private volatile Runnable muteAction;
    private volatile Runnable deafenAction;
    private volatile RecordingSession recordingSession;
    private boolean started = false;

    private GlobalHotkeyManager() {}

    public static GlobalHotkeyManager getInstance() {
        if (instance == null) instance = new GlobalHotkeyManager();
        return instance;
    }

    public void start() {
        Logger nativeLogger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        nativeLogger.setLevel(Level.WARNING);
        nativeLogger.setUseParentHandlers(false);
        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            loadBindings();
            started = true;
            log.info("GlobalHotkeyManager started");
        } catch (NativeHookException e) {
            log.warn("Failed to register native hook (hotkeys unavailable): {}", e.getMessage());
        }
    }

    public void stop() {
        if (!started) return;
        try {
            GlobalScreen.removeNativeKeyListener(this);
            GlobalScreen.unregisterNativeHook();
            log.info("GlobalHotkeyManager stopped");
        } catch (NativeHookException e) {
            log.warn("Failed to unregister native hook: {}", e.getMessage());
        }
        started = false;
    }

    public boolean isStarted() { return started; }

    public void loadBindings() {
        UserSettings us = UserSettings.getInstance();
        muteKeys = us.getKeybindingMuteKeys();
        deafenKeys = us.getKeybindingDeafenKeys();
        firedActions.clear();
        pressedKeys.clear();
    }

    public void clearBindings() {
        muteKeys = new int[0];
        deafenKeys = new int[0];
        firedActions.clear();
        pressedKeys.clear();
    }

    public void setMuteAction(Runnable action) { this.muteAction = action; }
    public void setDeafenAction(Runnable action) { this.deafenAction = action; }

    // ── NativeKeyListener ─────────────────────────────────────────────────────

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        int code = e.getKeyCode();
        pressedKeys.add(code);

        RecordingSession session = recordingSession;
        if (session != null) {
            session.onKeyPressed(code);
            return;
        }

        checkAndFire("mute", muteKeys, muteAction);
        checkAndFire("deafen", deafenKeys, deafenAction);
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        int code = e.getKeyCode();
        pressedKeys.remove(code);

        RecordingSession session = recordingSession;
        if (session != null) {
            session.onKeyReleased(code);
            return;
        }

        if (muteKeys.length > 0 && arrayContains(muteKeys, code)) firedActions.remove("mute");
        if (deafenKeys.length > 0 && arrayContains(deafenKeys, code)) firedActions.remove("deafen");
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void checkAndFire(String actionId, int[] keys, Runnable action) {
        if (keys.length == 0 || action == null) return;
        if (firedActions.contains(actionId)) return;
        if (allPressed(keys)) {
            firedActions.add(actionId);
            action.run();
        }
    }

    private boolean allPressed(int[] keys) {
        for (int k : keys) if (!pressedKeys.contains(k)) return false;
        return true;
    }

    private static boolean arrayContains(int[] arr, int val) {
        for (int v : arr) if (v == val) return true;
        return false;
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    public RecordingSession startRecording(Consumer<int[]> onComplete) {
        RecordingSession session = new RecordingSession(onComplete, () -> recordingSession = null);
        recordingSession = session;
        return session;
    }

    public static class RecordingSession {
        private final LinkedHashSet<Integer> capturedKeys = new LinkedHashSet<>();
        private final Set<Integer> currentlyPressed = new HashSet<>();
        private final Consumer<int[]> onComplete;
        private final Runnable onEnd;
        private boolean finished = false;

        RecordingSession(Consumer<int[]> onComplete, Runnable onEnd) {
            this.onComplete = onComplete;
            this.onEnd = onEnd;
        }

        void onKeyPressed(int code) {
            if (finished) return;
            if (code == NativeKeyEvent.VC_ESCAPE) {
                cancel();
                return;
            }
            currentlyPressed.add(code);
            if (capturedKeys.size() < 2) capturedKeys.add(code);
        }

        void onKeyReleased(int code) {
            if (finished) return;
            currentlyPressed.remove(code);
            if (currentlyPressed.isEmpty() && !capturedKeys.isEmpty()) {
                finish(capturedKeys.stream().mapToInt(Integer::intValue).toArray());
            }
        }

        public void cancel() {
            finish(null);
        }

        private void finish(int[] result) {
            if (finished) return;
            finished = true;
            onEnd.run();
            onComplete.accept(result);
        }
    }

    // ── Key display ───────────────────────────────────────────────────────────

    public static String keyDisplayName(int vc) {
        return switch (vc) {
            case NativeKeyEvent.VC_CONTROL -> "Ctrl";
            case NativeKeyEvent.VC_SHIFT   -> "Shift";
            case NativeKeyEvent.VC_ALT     -> "Alt";
            case NativeKeyEvent.VC_META    -> "Win";
            default -> NativeKeyEvent.getKeyText(vc);
        };
    }

    public static String keysDisplayName(int[] keys) {
        if (keys == null || keys.length == 0) return "Not set";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(" + ");
            sb.append(keyDisplayName(keys[i]));
        }
        return sb.toString();
    }
}
