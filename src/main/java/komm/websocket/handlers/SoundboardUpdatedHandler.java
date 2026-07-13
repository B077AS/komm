package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import komm.api.json.GsonProvider;
import komm.model.dto.summary.SoundboardSummary;
import komm.service.SoundboardCache;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class SoundboardUpdatedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.SOUNDBOARD_UPDATED;
    }

    @Override
    public void handle(JsonObject payload) {
        if (payload.has("soundboards")) {
            List<SoundboardSummary> list = gson.fromJson(payload.getAsJsonArray("soundboards"),
                    new TypeToken<List<SoundboardSummary>>() {}.getType());
            SoundboardCache.setServer(list);
            log.debug("SOUNDBOARD_UPDATED: {} server sounds", list != null ? list.size() : 0);
        }
    }
}
