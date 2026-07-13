package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.customnodes.CustomNotification;
import komm.ui.pages.HomePage;
import komm.ui.pages.ServerPage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ServerDeletedPayload;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;

import java.util.UUID;

/**
 * Hub push: a server the user belongs to has been deleted. Remove it from the home list and, if the
 * user is currently viewing it, disconnect from the installation and return to the home page.
 */
@Slf4j
public class ServerDeletedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.SERVER_DELETED;
    }

    @Override
    public void handle(JsonObject payload) {
        ServerDeletedPayload p = gson.fromJson(payload, ServerDeletedPayload.class);
        if (p == null || p.getServerId() == null) return;
        UUID serverId = p.getServerId();

        Platform.runLater(() -> {
            ServerPage sp = App.getCachedServerPage();
            boolean viewing = sp != null && sp.getServer() != null
                    && serverId.equals(sp.getServer().getServerId());
            if (viewing) {
                App.getServices().disconnectInstallation();
                App.setCachedServerPage(null);
                App.changePage(App.getOrCreateHomePage());
                new CustomNotification("Server Deleted", "This server has been permanently deleted.",
                        new FontIcon(MaterialDesignD.DELETE)).showNotification();
            }

            HomePage homePage = App.getCachedHomePage();
            if (homePage != null) homePage.removeServer(serverId);
        });

        log.info("SERVER_DELETED received — serverId={}", serverId);
    }
}
