package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.api.json.GsonProvider;
import komm.ui.customnodes.CustomNotification;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.DmSendRejectedPayload;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

public class DmSendRejectedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.DM_SEND_REJECTED;
    }

    @Override
    public void handle(JsonObject payload) {
        DmSendRejectedPayload p = gson.fromJson(payload, DmSendRejectedPayload.class);
        String title = p.getTitle() != null ? p.getTitle() : "Message not delivered";
        String description = p.getDescription() != null
                ? p.getDescription() : "Your message could not be delivered.";
        Platform.runLater(() -> new CustomNotification(
                title, description, new FontIcon(MaterialDesignM.MESSAGE_ALERT_OUTLINE)).showNotification());
    }
}
