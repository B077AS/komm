package komm.utils;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.ComboBox;
import komm.webrtc.WebrtcRoomClient;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * AudioDeviceService — bridges VoiceChannelClient and JavaFX UI controls.
 *
 * Design principles:
 *  • Depends on VoiceChannelClient via constructor injection, not via App globals.
 *  • Does NOT own any permanent listener state — each call to bind() is self-contained.
 *  • Device list refresh is driven by VoiceChannelClient's OS device-change callback,
 *    so the ComboBoxes update automatically when headphones are plugged in or out.
 *  • All JavaFX mutations are guarded by Platform.runLater().
 *  • mic/speaker mute are thin delegates — no state duplication.
 *
 * Usage (e.g. from a settings controller):
 *
 *   AudioDeviceService svc = new AudioDeviceService(App.__activeVoiceClient__);
 *   svc.bind(inputComboBox, outputComboBox);
 *   // later, when the settings page is closed:
 *   svc.unbind(inputComboBox, outputComboBox);
 */
@Slf4j
public class AudioDeviceService {

    private final WebrtcRoomClient voiceClient;

    // Held so we can cleanly remove them in unbind()
    private ChangeListener<String> inputListener;
    private ChangeListener<String> outputListener;

    // Held so we can unregister the hardware-change hook in unbind()
    private Runnable deviceListRefresher;

    // When true, combo changes do NOT apply to the RTC client immediately;
    // onDirtyChange is called instead so the caller can track pending state.
    private boolean  deferred     = false;
    private Runnable onDirtyChange;

    // Stored so updateSelection() can act without needing the combos passed in again.
    private ComboBox<String> boundInput;
    private ComboBox<String> boundOutput;

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCTION
    // ─────────────────────────────────────────────────────────────────────────

    public AudioDeviceService(WebrtcRoomClient voiceClient) {
        if (voiceClient == null) throw new IllegalArgumentException("voiceClient must not be null");
        this.voiceClient = voiceClient;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DEFERRED MODE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enable deferred mode: combo selection changes will NOT be applied to the
     * RTC client immediately. Instead, {@code onDirtyChange} is invoked so the
     * caller can track pending state and apply everything at once (e.g., on Save).
     * Call this before {@link #bind}.
     */
    public void setDeferred(boolean deferred, Runnable onDirtyChange) {
        this.deferred      = deferred;
        this.onDirtyChange = onDirtyChange;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BIND  —  populate + wire up two ComboBoxes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Populate the ComboBoxes with current devices, restore saved selections,
     * attach change listeners, and register for OS device-change events.
     *
     * Must be called on the JavaFX application thread (or any thread — internal
     * Platform.runLater() guards ensure thread safety).
     */
    public void bind(ComboBox<String> inputComboBox, ComboBox<String> outputComboBox) {
        this.boundInput  = inputComboBox;
        this.boundOutput = outputComboBox;

        // Register a refresher that fires whenever the OS device list changes
        deviceListRefresher = () -> Platform.runLater(
            () -> repopulate(inputComboBox, outputComboBox)
        );
        voiceClient.addDeviceListListener(deviceListRefresher);

        // Initial population
        Platform.runLater(() -> repopulate(inputComboBox, outputComboBox));
    }

    /**
     * Remove all listeners registered by bind(). Call this when the UI component
     * (settings page, popup, etc.) is being closed or disposed.
     */
    public void unbind(ComboBox<String> inputComboBox, ComboBox<String> outputComboBox) {
        if (inputListener  != null) inputComboBox.getSelectionModel().selectedItemProperty()
                                                  .removeListener(inputListener);
        if (outputListener != null) outputComboBox.getSelectionModel().selectedItemProperty()
                                                   .removeListener(outputListener);

        if (deviceListRefresher != null) {
            voiceClient.removeDeviceListListener(deviceListRefresher);
            deviceListRefresher = null;
        }

        inputListener  = null;
        outputListener = null;
        boundInput     = null;
        boundOutput    = null;
    }

    /**
     * Force-select specific devices in the bound ComboBoxes without triggering
     * the change listeners (so the RTC client is NOT called a second time).
     * Safe to call from any thread.
     */
    public void updateSelection(String inputDevice, String outputDevice) {
        if (boundInput == null || boundOutput == null) return;
        Runnable action = () -> {
            detachListeners(boundInput, boundOutput);
            if (inputDevice != null && boundInput.getItems().contains(inputDevice))
                boundInput.setValue(inputDevice);
            if (outputDevice != null && boundOutput.getItems().contains(outputDevice))
                boundOutput.setValue(outputDevice);
            attachListeners(boundInput, boundOutput);
        };
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MIC & SPEAKER CONTROL
    // ─────────────────────────────────────────────────────────────────────────

    /** Toggle microphone. Pass {@code true} to mute, {@code false} to unmute. */
    public void setMicrophoneMuted(boolean muted) {
        voiceClient.setMicrophoneMuted(muted);
    }

    public boolean isMicrophoneMuted() {
        return voiceClient.isMicrophoneMuted();
    }

    /** Toggle speaker output. Pass {@code true} to mute, {@code false} to unmute. */
    public void setSpeakerMuted(boolean muted) {
        voiceClient.setSpeakerMuted(muted);
    }

    public boolean isSpeakerMuted() {
        return voiceClient.isSpeakerMuted();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE — repopulate & wire
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * (Re)populate both ComboBoxes. Removes old listeners before touching items,
     * restores the previously selected device, then reattaches listeners.
     * Must be called on the JavaFX thread.
     */
    private void repopulate(ComboBox<String> inputComboBox, ComboBox<String> outputComboBox) {
        Thread.ofVirtual().start(() -> {
            List<String> inputDevices;
            List<String> outputDevices;
            try {
                inputDevices  = voiceClient.getInputDevices();
                outputDevices = voiceClient.getOutputDevices();
            } catch (Exception e) {
                log.error("[DEBUG-ADS] Error fetching device lists: {}", e.getMessage());
                return;
            }

            final List<String> finalInputs  = inputDevices;
            final List<String> finalOutputs = outputDevices;

            Platform.runLater(() -> {
                detachListeners(inputComboBox, outputComboBox);
                try {
                    String currentInput  = inputComboBox.getValue();
                    String currentOutput = outputComboBox.getValue();
                    inputComboBox.getItems().setAll(finalInputs);
                    outputComboBox.getItems().setAll(finalOutputs);
                    String savedInput  = UserSettings.getInstance().getInputDevice();
                    String savedOutput = UserSettings.getInstance().getOutputDevice();
                    setSelection(inputComboBox,  currentInput,  savedInput,  finalInputs);
                    setSelection(outputComboBox, currentOutput, savedOutput, finalOutputs);
                } catch (Exception e) {
                    log.error("[DEBUG-ADS] Error populating: {}", e.getMessage());
                } finally {
                    attachListeners(inputComboBox, outputComboBox);
                }
            });
        });
    }

    /**
     * Picks the best value to show in the combo after repopulation:
     *  1. Keep the combo's current value if it still exists in the new list.
     *  2. Else use the persisted setting if it exists.
     *  3. Else fall back to the first item (always "Default").
     */
    private void setSelection(ComboBox<String> combo, String current, String saved, List<String> items) {
        if (current != null && items.contains(current)) {
            combo.setValue(current);
        } else if (saved != null && items.contains(saved)) {
            combo.setValue(saved);
        } else if (!items.isEmpty()) {
            combo.setValue(items.get(0));
        }
    }

    private void attachListeners(ComboBox<String> inputComboBox, ComboBox<String> outputComboBox) {
        inputListener = createListener(inputComboBox, true);
        outputListener = createListener(outputComboBox, false);

        inputComboBox.getSelectionModel().selectedItemProperty().addListener(inputListener);
        outputComboBox.getSelectionModel().selectedItemProperty().addListener(outputListener);
    }

    private void detachListeners(ComboBox<String> inputComboBox, ComboBox<String> outputComboBox) {
        if (inputListener  != null) inputComboBox.getSelectionModel().selectedItemProperty()
                                                  .removeListener(inputListener);
        if (outputListener != null) outputComboBox.getSelectionModel().selectedItemProperty()
                                                   .removeListener(outputListener);
    }

    /**
     * Creates a listener for one ComboBox.
     * On selection change: delegates to VoiceChannelClient, persists setting,
     * and reverts the combo on error.
     */
    private ChangeListener<String> createListener(ComboBox<String> combo, boolean isInput) {
        return (obs, oldVal, newVal) -> {
            if (newVal == null || newVal.equals(oldVal)) return;
            if (deferred) {
                if (onDirtyChange != null) onDirtyChange.run();
                return;
            }
            try {
                if (isInput) voiceClient.changeInputDevice(newVal);
                else         voiceClient.changeOutputDevice(newVal);
            } catch (Exception e) {
                log.error("Device change failed: {}", e.getMessage());
                revert(combo, oldVal, isInput ? inputListener : outputListener);
            }
        };
    }

    private void revert(ComboBox<String> combo, String oldVal, ChangeListener<String> listener) {
        // Must run on the FX thread; if already on it this is fine, if not wrap it
        Runnable revertAction = () -> {
            combo.getSelectionModel().selectedItemProperty().removeListener(listener);
            combo.setValue(oldVal);
            combo.getSelectionModel().selectedItemProperty().addListener(listener);
        };

        if (Platform.isFxApplicationThread()) {
            revertAction.run();
        } else {
            Platform.runLater(revertAction);
        }
    }
}