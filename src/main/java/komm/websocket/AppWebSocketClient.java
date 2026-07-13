package komm.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import komm.api.auth.TokenManager;
import komm.api.json.GsonProvider;
import komm.websocket.handlers.*;
import komm.websocket.handlers.FriendRequestAcceptedHandler;
import komm.websocket.handlers.FriendRequestCancelledHandler;
import komm.websocket.handlers.FriendRequestDeclinedHandler;
import komm.websocket.handlers.FriendRequestReceivedHandler;
import komm.websocket.handlers.FriendRemovedHandler;
import komm.websocket.handlers.VoiceConnectedUsersUpdateHandler;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

import jakarta.websocket.*;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
public class AppWebSocketClient extends Endpoint {

    public static final int RECONNECT_DELAY_SEC = 5;

    private final String serverUrl;
    private final TokenManager tokenManager;
    private final Gson gson = GsonProvider.get();

    private volatile Session wsSession;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile boolean intentionalDisconnect = false;
    private volatile Throwable lastError;
    private volatile Consumer<String> onDisconnectListener;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-reconnect-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final Map<WsMessageType, WsInboundMessageHandler> handlers = new ConcurrentHashMap<>();

    public AppWebSocketClient(String serverUrl, TokenManager tokenManager) {
        this.serverUrl = serverUrl;
        this.tokenManager = tokenManager;

        List.of(
                new FriendRequestAcceptedHandler(),
                new FriendRequestReceivedHandler(),
                new FriendRequestDeclinedHandler(),
                new FriendRequestCancelledHandler(),
                new FriendRemovedHandler(),
                new VoiceConnectedUsersUpdateHandler(),
                new komm.websocket.handlers.ChannelJoinDeniedHandler(),
                new komm.websocket.handlers.UserStatusUpdatedHandler(),
                new komm.websocket.handlers.UserAvatarUpdatedHandler(),
                new DmReceivedHandler(),
                new DmSendRejectedHandler(),
                new DmDeletedHandler(),
                new DmEditedHandler(),
                new DmTypingHandler(),
                new DmReactionAddedHandler(),
                new DmReactionRemovedHandler(),
                new DmConversationHiddenHandler(),
                new DmConversationDeletedHandler(),
                new ServerDeletedHandler(),
                new InstallationDeletedHandler()
        ).forEach(h -> handlers.put(h.getType(), h));
    }

    public void connect() {
        intentionalDisconnect = false;
        doConnect();
    }

    public void disconnect() {
        intentionalDisconnect = true;
        closeQuietly();
    }

    public void shutdown() {
        disconnect();
        scheduler.shutdownNow();
    }

    public boolean isConnected() {
        return wsSession != null && wsSession.isOpen();
    }

    public void register(WsMessageType type, Consumer<JsonObject> handler) {
        handlers.put(type, new WsInboundMessageHandler() {
            @Override public WsMessageType getType() { return type; }
            @Override public void handle(JsonObject payload) { handler.accept(payload); }
        });
    }

    public void setOnDisconnect(Consumer<String> listener) {
        this.onDisconnectListener = listener;
    }

    public void unregister(WsMessageType type) {
        handlers.remove(type);
    }

    public void send(WsMessageType type, Object payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", type.name());
        envelope.add("payload", JsonParser.parseString(gson.toJson(payload)));
        sendRaw(gson.toJson(envelope));
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        this.wsSession = session;
        connecting.set(false);
        session.setMaxTextMessageBufferSize(100 * 1024 * 1024);
        session.addMessageHandler(String.class, this::handleMessage);
        log.info("WebSocket connected (session {})", session.getId());
    }

    @Override
    public void onClose(Session session, CloseReason reason) {
        wsSession = null;
        connecting.set(false);
        Throwable err = lastError;
        lastError = null;
        log.info("WebSocket closed: {}", reason.getReasonPhrase());
        if (!intentionalDisconnect) {
            String detail = (err != null && err.getMessage() != null)
                    ? err.getMessage()
                    : reason.getReasonPhrase();
            scheduleReconnect(detail);
        }
    }

    @Override
    public void onError(Session session, Throwable error) {
        log.error("WebSocket error", error);
        lastError = error;
        // onClose will fire after this — reconnect is handled there
    }

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
        } catch (Exception e) {
            log.error("Failed to handle incoming message", e);
        }
    }

    private void doConnect() {
        if (!connecting.compareAndSet(false, true)) {
            log.debug("Connection attempt already in progress, skipping");
            return;
        }
        try {
            tokenManager.refreshAccessToken();
        } catch (Exception e) {
            log.warn("Token refresh failed before WS connect: {}", e.getMessage());
            connecting.set(false);
            if (!intentionalDisconnect) scheduleReconnect("Token refresh failed: " + e.getMessage());
            return;
        }
        String token = tokenManager.getAccessToken();
        try {
            ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
                    .configurator(new ClientEndpointConfig.Configurator() {
                        @Override
                        public void beforeRequest(Map<String, List<String>> headers) {
                            headers.put("Authorization", List.of("Bearer " + token));
                        }
                    })
                    .build();

            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.connectToServer(this, config, URI.create(serverUrl));
            // connecting flag cleared in onOpen on success, or onClose on failure
        } catch (Exception e) {
            log.error("Failed to connect: {}", e.getMessage());
            connecting.set(false);
            if (!intentionalDisconnect) {
                scheduleReconnect("Could not reach server: " + e.getMessage());
            }
        }
    }

    private void scheduleReconnect(String reason) {
        log.info("Reconnecting in {}s...", RECONNECT_DELAY_SEC);
        Consumer<String> listener = onDisconnectListener;
        if (listener != null) {
            listener.accept(reason);
        }
        scheduler.schedule(this::doConnect, RECONNECT_DELAY_SEC, TimeUnit.SECONDS);
    }

    private void sendRaw(String text) {
        try {
            Session s = wsSession;
            if (s == null || !s.isOpen()) {
                log.warn("Cannot send — not connected");
                return;
            }
            s.getBasicRemote().sendText(text);
        } catch (IOException e) {
            log.warn("Send failed: {}", e.getMessage());
        }
    }

    private void closeQuietly() {
        try {
            Session s = wsSession;
            if (s != null && s.isOpen()) s.close();
        } catch (IOException ignored) {
        }
        wsSession = null;
    }
}