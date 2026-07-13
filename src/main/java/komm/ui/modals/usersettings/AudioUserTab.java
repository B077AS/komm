package komm.ui.modals.usersettings;

import atlantafx.base.theme.Styles;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import komm.App;
import komm.AppState;
import komm.api.HttpStatusException;
import komm.ui.customnodes.CustomNotification;
import komm.ui.pages.HomePage;
import komm.ui.sections.ProfileSection;
import komm.utils.AudioDeviceService;
import komm.utils.UserSettings;
import komm.webrtc.WebrtcRoomClient;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.util.Objects;

import static komm.ui.modals.usersettings.UserSettingsUi.*;

/** "Audio" tab: device selection, VAD, and audio processing toggles. */
public class AudioUserTab implements UserSettingsTab {

    private final UserSettingsContext ctx;

    private final ComboBox<String> microphonesCombobox = new ComboBox<>();
    private final ComboBox<String> speakersCombobox = new ComboBox<>();
    private final AudioDeviceService audioService;
    private final CheckBox vadToggle = new CheckBox("Enable VAD");
    private final Slider sensitivitySlider;
    private final CheckBox noiseSuppressionToggle = new CheckBox("Noise Suppression (RNNoise)");
    private final CheckBox agcToggle = new CheckBox("Auto Gain Control");
    private final CheckBox echoCancellationToggle = new CheckBox("Echo Cancellation (AEC3)");
    private final MicActivityMeter micMeter = new MicActivityMeter();
    private final Button testMicButton = new Button("Test Microphone");
    private Timeline meterTimeline;
    private boolean testing = false;
    private boolean startedTestCapture = false;
    private boolean forcedMuteDeafen = false;
    private boolean prevMicEnabledBeforeTest;
    private boolean prevSpeakerEnabledBeforeTest;

    private String origMicDevice;
    private String origSpkDevice;
    private boolean origVadEnabled;
    private float origVadThreshold;
    private boolean origNoiseSuppression;
    private boolean origEchoCancellation;
    private boolean origAgcEnabled;

    private volatile boolean saving = false;
    private final ScrollPane pane;

    public AudioUserTab(UserSettingsContext ctx) {
        this.ctx = ctx;
        this.audioService = new AudioDeviceService(App.getWebrtcRoomClient());

        UserSettings us = UserSettings.getInstance();
        origMicDevice      = us.getInputDevice();
        origSpkDevice      = us.getOutputDevice();
        origVadEnabled     = us.isVadEnabled();
        origVadThreshold   = us.getVadSensitivity();
        origNoiseSuppression = us.isNoiseSuppression();
        origEchoCancellation = us.isEchoCancellation();
        origAgcEnabled     = us.isAgcEnabled();

        sensitivitySlider = new Slider(0, 100, thresholdToSlider(origVadThreshold));

        this.pane = buildPane();
    }

    @Override public String name() { return "Audio"; }
    @Override public String description() { return "Configure your microphone, speakers, and voice detection"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignM.MICROPHONE_OUTLINE); }
    @Override public Node getPane() { return pane; }
    @Override public boolean participatesInSave() { return true; }
    @Override public String saveButtonText() { return "Save Audio"; }
    @Override public boolean isBusy() { return saving; }

    private void toggleMicTest() {
        if (testing) stopMicTest();
        else startMicTest();
    }

    private void startMicTest() {
        WebrtcRoomClient rtc = App.getWebrtcRoomClient();
        if (rtc == null) {
            new CustomNotification("Microphone Test Unavailable", "The voice engine isn't ready yet.",
                    new FontIcon(MaterialDesignA.ALERT_OUTLINE)).showNotification();
            return;
        }

        startedTestCapture = rtc.startMicTestIfIdle();
        testing = true;
        testMicButton.setText("Stop Test");
        micMeter.setTesting(true);

        if (!startedTestCapture) {
            // Piggybacking a real channel's capture — force mute+deafen for the test's
            // duration, same as Discord's own mic test: it keeps device switching from being
            // heard by other participants and stops the room from bleeding into the test
            // playback. Restored on stop to whatever it was before, whether that was on,
            // off, or already muted/deafened.
            prevMicEnabledBeforeTest     = AppState.micEnabledProperty().get();
            prevSpeakerEnabledBeforeTest = AppState.speakerEnabledProperty().get();
            forcedMuteDeafen = true;
            AppState.applySpeakerEnabled(false);
            AppState.applyMicEnabled(false);
        }

        // Local loopback: a separate pair of peer connections, so this never touches
        // publisherPc/subscriberPc and cannot interfere with an active voice channel.
        Thread.ofVirtual().start(rtc::startMicTestLoopback);

        meterTimeline = new Timeline(new KeyFrame(Duration.millis(50), e -> refreshMeter()));
        meterTimeline.setCycleCount(Animation.INDEFINITE);
        meterTimeline.play();
    }

    private void stopMicTest() {
        if (meterTimeline != null) {
            meterTimeline.stop();
            meterTimeline = null;
        }
        WebrtcRoomClient rtc = App.getWebrtcRoomClient();
        boolean restoreMuteDeafen = forcedMuteDeafen;
        boolean restoreSpeaker    = prevSpeakerEnabledBeforeTest;
        boolean restoreMic        = prevMicEnabledBeforeTest;
        boolean stopCapture       = startedTestCapture;
        forcedMuteDeafen  = false;
        startedTestCapture = false;

        // Sequenced on one thread: the loopback's own SourceDataLine (on the shared output
        // device) must be fully closed before the real participants' lines resume writing —
        // running these concurrently on separate threads races on the underlying audio device
        // and causes audible crackling on Linux (ALSA/PulseAudio handles overlapping
        // open/close on the same sink far worse than WASAPI shared mode).
        if (rtc != null) {
            Thread.ofVirtual().start(() -> {
                rtc.stopMicTestLoopback();
                if (restoreMuteDeafen) {
                    AppState.applySpeakerEnabled(restoreSpeaker);
                    AppState.applyMicEnabled(restoreMic);
                }
                if (stopCapture) rtc.stopMicTest();
            });
        }

        testing = false;
        testMicButton.setText("Test Microphone");
        micMeter.setTesting(false);
    }

    private void refreshMeter() {
        WebrtcRoomClient rtc = App.getWebrtcRoomClient();
        if (rtc == null || !rtc.isCapturingAudio()) return;
        micMeter.update(rtc.getMicLevelRms(), rtc.isVadSpeaking());
    }

    @Override
    public boolean isDirty() {
        if (!Objects.equals(microphonesCombobox.getValue(), origMicDevice)) return true;
        if (!Objects.equals(speakersCombobox.getValue(), origSpkDevice)) return true;
        if (vadToggle.isSelected() != origVadEnabled) return true;
        if (Math.abs(sliderToThreshold(sensitivitySlider.getValue()) - origVadThreshold) > 0.0001f) return true;
        if (noiseSuppressionToggle.isSelected() != origNoiseSuppression) return true;
        if (agcToggle.isSelected() != origAgcEnabled) return true;
        if (echoCancellationToggle.isSelected() != origEchoCancellation) return true;
        return false;
    }

    @Override
    public void save() {
        final String  mic       = microphonesCombobox.getValue();
        final String  spk       = speakersCombobox.getValue();
        final boolean vadOn     = vadToggle.isSelected();
        final float   threshold = sliderToThreshold(sensitivitySlider.getValue());
        final boolean ns        = noiseSuppressionToggle.isSelected();
        final boolean agc       = agcToggle.isSelected();
        final boolean ec        = echoCancellationToggle.isSelected();
        final String  prevMic   = origMicDevice;
        final String  prevSpk   = origSpkDevice;

        saving = true;
        ctx.setSaving(true);
        ctx.refreshSaveButton();

        Thread.ofVirtual().start(() -> {
            try {
                var rtc = App.getWebrtcRoomClient();
                var us  = UserSettings.getInstance();

                if (!Objects.equals(mic, prevMic)) rtc.changeInputDevice(mic);
                if (!Objects.equals(spk, prevSpk)) rtc.changeOutputDevice(spk);

                rtc.setNoiseSuppression(ns);
                rtc.setAutoGainControl(agc);
                rtc.setEchoCancellation(ec);
                rtc.setVadEnabled(vadOn);
                rtc.setVadThreshold(threshold);

                if (mic != null) us.setInputDevice(mic);
                if (spk != null) us.setOutputDevice(spk);

                Platform.runLater(() -> {
                    origMicDevice        = mic;
                    origSpkDevice        = spk;
                    origVadEnabled       = vadOn;
                    origVadThreshold     = threshold;
                    origNoiseSuppression = ns;
                    origAgcEnabled       = agc;
                    origEchoCancellation = ec;
                    saving = false;
                    ctx.setSaving(false);
                    ctx.refreshSaveButton();

                    Node currentPage = App.getCurrentPage();
                    if (currentPage instanceof HomePage hp) {
                        ProfileSection ps = hp.getProfileSection();
                        if (ps != null) ps.refreshDeviceSelections();
                    }

                    new CustomNotification("Audio Settings Saved", "Your audio settings have been applied.",
                            new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE)).showNotification();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    saving = false;
                    ctx.setSaving(false);
                    ctx.refreshSaveButton();
                    new CustomNotification("Save Failed", HttpStatusException.extractMessage(e),
                            new FontIcon(MaterialDesignA.ALERT_OUTLINE)).showNotification();
                });
            }
        });
    }

    @Override
    public void dispose() {
        // Capture before stopMicTest() resets the flags — needed below to know whether the
        // mic device was live-previewed against a real channel's capture (which stopMicTest()
        // does NOT tear down, unlike a standalone test capture).
        boolean wasPiggybackCapture = testing && !startedTestCapture;
        if (testing) stopMicTest();

        var rtc = App.getWebrtcRoomClient();
        if (rtc != null) {
            // Every toggle below previews live while testing (see buildPane()), and each of
            // those setters persists immediately — so anything still dirty here was never
            // confirmed with Save and must be rolled back. The speaker preview needs no such
            // rollback (it never touched UserSettings or the real output, and the test
            // loopback that used it was already torn down by stopMicTest() above) — but the
            // mic device does, if it was previewed against a real channel's ongoing capture.
            float currentThreshold = sliderToThreshold(sensitivitySlider.getValue());
            if (Math.abs(currentThreshold - origVadThreshold) > 0.0001f) {
                Thread.ofVirtual().start(() -> rtc.setVadThreshold(origVadThreshold));
            }
            if (vadToggle.isSelected() != origVadEnabled) {
                Thread.ofVirtual().start(() -> rtc.setVadEnabled(origVadEnabled));
            }
            if (noiseSuppressionToggle.isSelected() != origNoiseSuppression) {
                Thread.ofVirtual().start(() -> rtc.setNoiseSuppression(origNoiseSuppression));
            }
            if (agcToggle.isSelected() != origAgcEnabled) {
                Thread.ofVirtual().start(() -> rtc.setAutoGainControl(origAgcEnabled));
            }
            if (echoCancellationToggle.isSelected() != origEchoCancellation) {
                Thread.ofVirtual().start(() -> rtc.setEchoCancellation(origEchoCancellation));
            }
            if (wasPiggybackCapture && !Objects.equals(microphonesCombobox.getValue(), origMicDevice)) {
                Thread.ofVirtual().start(() -> rtc.previewInputDeviceForTest(origMicDevice));
            }
        }
        audioService.unbind(microphonesCombobox, speakersCombobox);
    }

    private ScrollPane buildPane() {
        // ── Devices ───────────────────────────────────────────────────────────────
        Label micLabel = new Label("MICROPHONE");
        micLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        microphonesCombobox.setFocusTraversable(false);
        microphonesCombobox.setMaxWidth(Double.MAX_VALUE);
        microphonesCombobox.getStyleClass().add("compact-combo");

        Label spkLabel = new Label("HEADPHONES");
        spkLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        speakersCombobox.setFocusTraversable(false);
        speakersCombobox.setMaxWidth(Double.MAX_VALUE);
        speakersCombobox.getStyleClass().add("compact-combo");

        audioService.setDeferred(true, ctx::refreshSaveButton);
        audioService.bind(microphonesCombobox, speakersCombobox);

        // Live device preview while testing — independent of the deferred Save flow above, and
        // never persisted, so closing without Save just leaves the real settings untouched.
        // Safe to hot-swap even while piggybacking a real channel's capture: startMicTest()
        // force-mutes in that case, so the switch is never actually heard by other participants.
        microphonesCombobox.valueProperty().addListener((obs, o, n) -> {
            if (!testing || n == null) return;
            WebrtcRoomClient r = App.getWebrtcRoomClient();
            if (r != null) Thread.ofVirtual().start(() -> r.previewInputDeviceForTest(n));
        });
        speakersCombobox.valueProperty().addListener((obs, o, n) -> {
            if (!testing || n == null) return;
            WebrtcRoomClient r = App.getWebrtcRoomClient();
            if (r != null) r.previewMicTestOutputDevice(n);
        });

        // ── VAD ───────────────────────────────────────────────────────────────────
        vadToggle.setSelected(origVadEnabled);
        vadToggle.setFocusTraversable(false);
        Label vadDesc = new Label("Only transmits audio when your voice is detected. Disable to always transmit.");
        vadDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        vadDesc.setWrapText(true);

        sensitivitySlider.setFocusTraversable(false);
        sensitivitySlider.setDisable(!origVadEnabled);
        sensitivitySlider.setShowTickMarks(true);
        sensitivitySlider.setShowTickLabels(true);
        sensitivitySlider.setMajorTickUnit(50);
        sensitivitySlider.setMinorTickCount(4);
        sensitivitySlider.setSnapToTicks(false);
        sensitivitySlider.setLabelFormatter(new javafx.util.StringConverter<>() {
            @Override public String toString(Double v) {
                if (v <= 1) return "Lenient";
                if (v >= 49 && v <= 51) return "Balanced";
                if (v >= 99) return "Strict";
                return "";
            }
            @Override public Double fromString(String s) { return 0.0; }
        });
        sensitivitySlider.setMaxWidth(Double.MAX_VALUE);

        Label thresholdCaption = new Label("SENSITIVITY");
        thresholdCaption.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        Label thresholdDesc = new Label("Lower picks up whispers and quiet speech; higher transmits only " +
                "clear talking. If your voice gets cut when speaking quietly, move it left.");
        thresholdDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        thresholdDesc.setWrapText(true);

        var rtc = App.getWebrtcRoomClient();

        vadToggle.selectedProperty().addListener((obs, o, enabled) -> {
            sensitivitySlider.setDisable(!enabled);
            ctx.refreshSaveButton();
            if (testing && rtc != null) Thread.ofVirtual().start(() -> rtc.setVadEnabled(enabled));
        });
        sensitivitySlider.valueProperty().addListener((obs, o, n) -> {
            ctx.refreshSaveButton();
            float liveThreshold = sliderToThreshold(n.doubleValue());
            if (testing && rtc != null) {
                Thread.ofVirtual().start(() -> rtc.setVadThreshold(liveThreshold));
            }
        });

        Label meterSectionLabel = new Label("MICROPHONE TEST");
        meterSectionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        testMicButton.setFocusTraversable(false);
        testMicButton.getStyleClass().add(Styles.SMALL);
        testMicButton.setOnAction(e -> toggleMicTest());

        Region meterHeaderSpacer = new Region();
        HBox.setHgrow(meterHeaderSpacer, Priority.ALWAYS);
        HBox meterHeaderRow = new HBox(8, meterSectionLabel, meterHeaderSpacer, testMicButton);
        meterHeaderRow.setAlignment(Pos.CENTER_LEFT);

        Label meterHint = new Label("Click Test Microphone and speak — you'll hear yourself back, and the bar " +
                "turns green while others would hear you. Every setting on this page previews live during the " +
                "test; nothing is kept unless you hit Save Audio. If you're in a voice channel, testing mutes " +
                "and deafens you until you stop. Use headphones to avoid feedback.");
        meterHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        meterHint.setWrapText(true);

        // ── Audio Processing ──────────────────────────────────────────────────────
        noiseSuppressionToggle.setSelected(origNoiseSuppression);
        noiseSuppressionToggle.setFocusTraversable(false);
        Label nsDesc = new Label("Removes background noise and static using the RNNoise neural denoiser.");
        nsDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        nsDesc.setWrapText(true);

        agcToggle.setSelected(origAgcEnabled);
        agcToggle.setFocusTraversable(false);
        Label agcDesc = new Label("Normalises microphone volume automatically. Works well with high-end microphones.");
        agcDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        agcDesc.setWrapText(true);

        echoCancellationToggle.setSelected(origEchoCancellation);
        echoCancellationToggle.setFocusTraversable(false);
        Label ecDesc = new Label("Reduces echo from speakers picked up by your microphone.");
        ecDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        ecDesc.setWrapText(true);

        noiseSuppressionToggle.selectedProperty().addListener((obs, o, n) -> {
            ctx.refreshSaveButton();
            if (testing && rtc != null) Thread.ofVirtual().start(() -> rtc.setNoiseSuppression(n));
        });
        agcToggle.selectedProperty().addListener((obs, o, n) -> {
            ctx.refreshSaveButton();
            if (testing && rtc != null) Thread.ofVirtual().start(() -> rtc.setAutoGainControl(n));
        });
        echoCancellationToggle.selectedProperty().addListener((obs, o, n) -> {
            ctx.refreshSaveButton();
            if (testing && rtc != null) Thread.ofVirtual().start(() -> rtc.setEchoCancellation(n));
        });

        VBox pane = new VBox(16);
        pane.setPadding(new Insets(20, 28, 20, 28));
        pane.getChildren().addAll(
                sectionLabel("Audio Devices"),
                new VBox(4, micLabel, microphonesCombobox),
                new VBox(4, spkLabel, speakersCombobox),
                new Separator(Orientation.HORIZONTAL),
                sectionLabel("Voice Activity Detection"),
                new VBox(6, vadToggle, vadDesc),
                new VBox(4, thresholdCaption, sensitivitySlider, thresholdDesc),
                new VBox(8, meterHeaderRow, micMeter, meterHint),
                new Separator(Orientation.HORIZONTAL),
                sectionLabel("Audio Processing"),
                new VBox(4, noiseSuppressionToggle, nsDesc),
                new VBox(4, agcToggle, agcDesc),
                new VBox(4, echoCancellationToggle, ecDesc)
        );
        return wrapScroll(pane);
    }

    // Sensitivity slider maps to the Silero probability threshold in [THRESHOLD_MIN, THRESHOLD_MAX].
    //   0 (Lenient) → 0.10   50 (Balanced) → 0.525   100 (Strict) → 0.95
    // This is the ONLY thing the slider controls. The loudness floor (noise gate) is a
    // fixed −40 dBFS click backstop with no UI — see UserSettings.getVadNoiseGateDb().
    private static final double THRESHOLD_MIN = 0.10;
    private static final double THRESHOLD_MAX = 0.95;

    private static double thresholdToSlider(float threshold) {
        double clamped = Math.max(THRESHOLD_MIN, Math.min(THRESHOLD_MAX, threshold));
        return (clamped - THRESHOLD_MIN) / (THRESHOLD_MAX - THRESHOLD_MIN) * 100.0;
    }

    private static float sliderToThreshold(double slider) {
        return (float) (THRESHOLD_MIN + slider / 100.0 * (THRESHOLD_MAX - THRESHOLD_MIN));
    }
}
