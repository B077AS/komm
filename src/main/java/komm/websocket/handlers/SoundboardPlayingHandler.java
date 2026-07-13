package komm.websocket.handlers;

import com.google.gson.JsonObject;
import komm.App;
import komm.AppState;
import komm.service.PersonalSoundboardStore;
import komm.service.SoundboardService;
import komm.webrtc.pipeline.SoundboardDecoder;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.UUID;

/**
 * A member triggered a soundboard. Every client (including the triggering user)
 * downloads its own copy and plays it locally at its own volume. Deafened users ignore it.
 *
 * The speaking ring is driven entirely by LiveKit's SPEAKERS_CHANGED signaling event:
 * the triggering client pushes the soundboard PCM into its dedicated WebRTC soundboard
 * track, LiveKit detects the audio energy, and sends SPEAKERS_CHANGED to all peers —
 * exactly like microphone speaking detection. No client-side timer is needed.
 */
@Slf4j
public class SoundboardPlayingHandler implements WsInboundMessageHandler {


    @Override
    public WsMessageType getType() { return WsMessageType.SOUNDBOARD_PLAYING; }

    @Override
    public void handle(JsonObject payload) {
        if (!payload.has("soundboardId") || !payload.has("userId")) return;
        UUID soundboardId = UUID.fromString(payload.get("soundboardId").getAsString());
        UUID userId = UUID.fromString(payload.get("userId").getAsString());

        // Deafened users neither hear nor play soundboards.
        if (!AppState.speakerEnabledProperty().get() || !AppState.serverSpeakerEnabledProperty().get()) {
            return;
        }

        // Only the triggering user needs to play locally — they never receive their own
        // WebRTC tracks back from LiveKit, so SoundboardMixer is their only audio path.
        // Everyone else hears the sound through the received WebRTC soundboard track in
        // real-time, which is inherently in sync with the sender.
        boolean isSelf = App.getUser() != null && App.getUser().getUserId().equals(userId);
        if (!isSelf) return;

        App.getServices().getExecutor().submit(() -> {
            try {
                // Personal sounds are stored locally — skip the server entirely.
                Path file = PersonalSoundboardStore.getInstance().getFileById(soundboardId);
                if (file == null) {
                    SoundboardService svc;
                    try { svc = App.getServices().installation().getSoundboardService(); }
                    catch (Exception e) { return; }
                    if (svc == null) return;
                    file = svc.cachedFileById(soundboardId);
                }
                short[] pcm = SoundboardDecoder.decodeToPcm(file.toFile());
                App.getWebrtcRoomClient().playSoundboard(userId + ":" + soundboardId, pcm);
            } catch (Exception ex) {
                log.warn("[Soundboard] Failed to play triggered sound {}: {}", soundboardId, ex.getMessage());
            }
        });
    }
}
