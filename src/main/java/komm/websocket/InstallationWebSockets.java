package komm.websocket;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import org.glassfish.tyrus.client.SslEngineConfigurator;

import javax.net.ssl.SSLContext;

/**
 * WebSocket containers for connections to an installation server — its app WebSocket
 * ({@link InstallationWsClient}) and its LiveKit signaling proxy
 * ({@code komm.webrtc.LiveKitSignalingClient}).
 */
public final class InstallationWebSockets {

    private InstallationWebSockets() {
    }

    /**
     * @param sslContext hub-CA trust for wss ({@code HubCaTrust.contextFor}); null for plain ws.
     */
    public static WebSocketContainer container(SSLContext sslContext) {
        if (sslContext == null) {
            return ContainerProvider.getWebSocketContainer();
        }
        // Tyrus needs the custom SSLContext via client properties. Host verification
        // is off because installations sit on bare IPs — the trust manager already
        // checks the hub-signed CN identity instead.
        ClientManager client = ClientManager.createClient();
        SslEngineConfigurator ssl = new SslEngineConfigurator(sslContext, true, false, false);
        ssl.setHostVerificationEnabled(false);
        client.getProperties().put(ClientProperties.SSL_ENGINE_CONFIGURATOR, ssl);
        return client;
    }
}
