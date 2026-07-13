package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.cards.ChannelCard;
import komm.ui.customnodes.CustomNotification;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ForceDisconnectPayload;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;

import java.util.UUID;

@Slf4j
public class ForceDisconnectHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.FORCE_DISCONNECT;
    }

    @Override
    public void handle(JsonObject payload) {
        ForceDisconnectPayload fp = gson.fromJson(payload, ForceDisconnectPayload.class);
        String reason = fp != null && fp.getReason() != null ? fp.getReason() : "KICKED";

        Platform.runLater(() -> {
            UUID channelId = App.getWebrtcRoomClient().getCurrentChannelId();
            if (channelId != null) {
                ChannelCard.onServerForcedLeave(channelId);
                App.getWebrtcRoomClient().disconnectFromChannel();
            }

            String message = switch (reason) {
                case "BANNED"            -> "You have been banned from this server.";
                case "DUPLICATE_SESSION" -> "You have been disconnected because you connected from another window.";
                case "SERVER_DELETED"    -> "This server has been deleted.";
                default                  -> "You have been kicked from this server.";
            };
            new CustomNotification("Disconnected", message, new FontIcon(MaterialDesignD.DOOR_CLOSED)).showNotification();

            App.getServices().disconnectInstallation();
            App.setCachedServerPage(null);
            App.changePage(App.getOrCreateHomePage());
        });

        log.info("FORCE_DISCONNECT received — reason={}", reason);
    }
}
