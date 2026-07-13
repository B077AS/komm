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
import komm.websocket.messages.payloads.InstallationDeletedPayload;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;

import java.util.UUID;

@Slf4j
public class InstallationDeletedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.INSTALLATION_DELETED;
    }

    @Override
    public void handle(JsonObject payload) {
        InstallationDeletedPayload p = gson.fromJson(payload, InstallationDeletedPayload.class);
        if (p == null || p.getInstallationId() == null) return;
        UUID installationId = p.getInstallationId();

        Platform.runLater(() -> {
            ServerPage sp = App.getCachedServerPage();
            boolean viewing = sp != null && sp.getServer() != null
                    && installationId.equals(sp.getServer().getInstallationId());

            if (viewing) {
                App.disconnectFromVoice();
                App.getServices().disconnectInstallation();
                App.setCachedServerPage(null);
                App.changePage(App.getOrCreateHomePage());
                new CustomNotification("Installation Deleted",
                        "This installation has been deleted by its owner.",
                        new FontIcon(MaterialDesignD.DELETE)).showNotification();
            }

            HomePage homePage = App.getCachedHomePage();
            if (homePage != null) homePage.removeInstallation(installationId);
        });

        log.info("INSTALLATION_DELETED received — installationId={}", installationId);
    }
}
