package komm.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.websocket.*;
import komm.api.json.GsonProvider;
import komm.websocket.handlers.*;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessage;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
public class InstallationWsClient extends Endpoint {

    private final String baseUrl;
    private final javax.net.ssl.SSLContext sslContext;
    private final Gson gson = GsonProvider.get();

    private volatile Session session;
    private final PingService pingService = new PingService();

    private final Map<WsMessageType, WsInboundMessageHandler> handlers = new ConcurrentHashMap<>();

    public InstallationWsClient(String baseUrl) {
        this(baseUrl, null);
    }

    /**
     * @param sslContext custom trust for wss connections (hub-CA trust); null for plain ws.
     */
    public InstallationWsClient(String baseUrl, javax.net.ssl.SSLContext sslContext) {
        this.baseUrl = baseUrl;
        this.sslContext = sslContext;
        List.of(
                new UserJoinedChannelHandler(),
                new UserLeftChannelHandler(),
                new UserMutedHandler(),
                new UserDeafenedHandler(),
                new UserServerMutedHandler(),
                new UserServerDeafenedHandler(),
                new UserScreenShareHandler(),
                new ChannelMessageReceivedHandler(),
                new ChannelMessageDeleted(),
                new ChannelMessageReactionAddHandler(),
                new ChannelMessageReactionRemoveHandler(),
                new ChannelTypingHandler(),
                new ChannelMessageEditedHandler(),
                new PongHandler(),
                new ChannelCreatedHandler(),
                new ChannelUpdatedHandler(),
                new ChannelDeletedHandler(),
                new ChannelsReorderedHandler(),
                new ChannelPermissionsUpdatedHandler(),
                new ServerPermissionsUpdatedHandler(),
                new CustomRoleCreatedHandler(),
                new CustomRoleUpdatedHandler(),
                new CustomRoleDeletedHandler(),
                new CustomRoleMemberAssignedHandler(),
                new CustomRoleMemberRemovedHandler(),
                new ChannelUserPermissionsUpdatedHandler(),
                new SoundboardUpdatedHandler(),
                new SoundboardPlayingHandler(),
                new SoundboardStoppedHandler(),
                new MemberRoleUpdatedHandler(),
                new ChannelJoinDeniedHandler(),
                new UserPingUpdateHandler(),
                new ForcedChannelJoinHandler(),
                new ForceDisconnectHandler(),
                new ForcedVoiceDisconnectHandler(),
                new MemberLeftHandler(),
                new PokeReceivedHandler(),
                new StreamViewerCountHandler(),
                new StreamEndedHandler()
        ).forEach(h -> handlers.put(h.getType(), h));
    }

    public void connect(String accessToken) {
        try {
            ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
                    .configurator(new ClientEndpointConfig.Configurator() {
                        @Override
                        public void beforeRequest(Map<String, List<String>> headers) {
                            headers.put("Authorization", List.of("Bearer " + accessToken));
                        }
                    })
                    .build();
            WebSocketContainer container;
            if (sslContext != null) {
                // Tyrus needs the custom SSLContext via client properties. Host
                // verification is off because installations sit on bare IPs — the
                // trust manager already checks the hub-signed CN identity instead.
                org.glassfish.tyrus.client.ClientManager client =
                        org.glassfish.tyrus.client.ClientManager.createClient();
                org.glassfish.tyrus.client.SslEngineConfigurator ssl =
                        new org.glassfish.tyrus.client.SslEngineConfigurator(sslContext, true, false, false);
                ssl.setHostVerificationEnabled(false);
                client.getProperties().put(
                        org.glassfish.tyrus.client.ClientProperties.SSL_ENGINE_CONFIGURATOR, ssl);
                container = client;
            } else {
                container = ContainerProvider.getWebSocketContainer();
            }
            container.connectToServer(this, config, URI.create(baseUrl + "/ws"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to installation WS: " + e.getMessage(), e);
        }
    }

    public void disconnect() {
        pingService.stop();
        handlers.clear();
        closeQuietly();
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    // ── Sending ───────────────────────────────────────────────────────────────

    public void send(WsMessageType type, Object payload) {
        WsMessage msg = WsMessage.builder()
                .type(type)
                .payload(JsonParser.parseString(gson.toJson(payload)).getAsJsonObject())
                .build();
        sendRaw(gson.toJson(msg));
    }

    public void unregister(WsMessageType type) {
        handlers.remove(type);
    }

    // Convenience overload for simple lambda-based handlers
    public void register(WsMessageType type, Consumer<JsonObject> handler) {
        handlers.put(type, new WsInboundMessageHandler() {
            @Override public WsMessageType getType() { return type; }
            @Override public void handle(JsonObject payload) { handler.accept(payload); }
        });
    }

    // ── Endpoint callbacks ────────────────────────────────────────────────────

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        this.session = session;
        session.setMaxTextMessageBufferSize(100 * 1024 * 1024);
        session.addMessageHandler(String.class, this::handleMessage);
        pingService.start(this);
        log.info("Installation WS connected to {}", baseUrl);
    }

    @Override
    public void onClose(Session session, CloseReason reason) {
        pingService.stop();
        this.session = null;
        log.info("Installation WS closed: {}", reason.getReasonPhrase());
    }

    @Override
    public void onError(Session session, Throwable error) {
        log.error("Installation WS error: {}", error.getMessage());
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    private void handleMessage(String raw) {
        try {
            JsonObject envelope = JsonParser.parseString(raw).getAsJsonObject();
            WsMessageType type = WsMessageType.valueOf(envelope.get("type").getAsString());
            JsonObject payload = envelope.has("payload") && !envelope.get("payload").isJsonNull()
                    ? envelope.getAsJsonObject("payload")
                    : new JsonObject();

            WsInboundMessageHandler handler = handlers.get(type);
            if (handler != null) handler.handle(payload);
            else log.debug("No handler registered for message type: {}", type);

        } catch (IllegalArgumentException e) {
            log.warn("Unknown message type received: {}", raw);
        } catch (Throwable t) {
            // Catch Throwable (not just Exception) so that native errors from the
            // audio device module or WebRTC JNI layer cannot escape to Grizzly's
            // onError callback and appear as cryptic WS-level errors.
            log.error("Failed to handle incoming message", t);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendRaw(String text) {
        Session s = session;
        if (s == null || !s.isOpen()) {
            log.warn("Cannot send — not connected");
            return;
        }
        s.getAsyncRemote().sendText(text, result -> {
            if (!result.isOK()) {
                log.warn("Async send failed: {}", result.getException().getMessage());
            }
        });
    }

    private void closeQuietly() {
        try {
            Session s = session;
            if (s != null && s.isOpen()) s.close();
        } catch (IOException ignored) {
        }
        session = null;
    }
}