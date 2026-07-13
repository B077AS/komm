package komm.websocket.handlers;

import com.google.gson.JsonObject;
import komm.App;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * A member stopped the sounds they had triggered. Every client halts that member's
 * locally-playing sounds (SoundboardMixer) and the ring clears via LiveKit SPEAKERS_CHANGED.
 */
@Slf4j
public class SoundboardStoppedHandler implements WsInboundMessageHandler {

    @Override
    public WsMessageType getType() { return WsMessageType.SOUNDBOARD_STOPPED; }

    @Override
    public void handle(JsonObject payload) {
        if (!payload.has("userId")) return;
        UUID userId = UUID.fromString(payload.get("userId").getAsString());
        App.getWebrtcRoomClient().stopSoundboardsBy(userId);
    }
}
