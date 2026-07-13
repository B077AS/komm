package komm.ui.customnodes;

import komm.api.ServiceContainer;
import komm.utils.UserPingHistory;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.UserPingRequestPayload;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Real-time latency graph popup for <em>another</em> user.
 *
 * <p>On show, sends {@code USER_PING_REQUEST} to the installation server so it
 * starts relaying the target user's self-reported RTT samples via
 * {@code USER_PING_UPDATE}.  On hide, sends {@code USER_PING_UNSUBSCRIBE} and
 * the local history for that user is left to expire naturally.</p>
 */
@Slf4j
public class UserPingGraphPopup extends PingGraphPopupBase {

    private final UUID targetUserId;
    private final String targetUserIdKey;

    public UserPingGraphPopup(UUID targetUserId, String targetUsername) {
        super(targetUsername.toUpperCase() + " — LATENCY");
        this.targetUserId = targetUserId;
        this.targetUserIdKey = targetUserId.toString();
    }

    @Override
    protected List<Integer> currentHistory() {
        return UserPingHistory.getHistory(targetUserIdKey);
    }

    @Override
    protected void subscribe(Consumer<Integer> listener) {
        UserPingHistory.addListener(targetUserIdKey, listener);
    }

    @Override
    protected void unsubscribe(Consumer<Integer> listener) {
        UserPingHistory.removeListener(targetUserIdKey, listener);
    }

    @Override
    protected int currentLossPercent() {
        return UserPingHistory.getLossPercent(targetUserIdKey);
    }

    @Override
    protected void onShow() {
        try {
            ServiceContainer.getInstance()
                    .installation()
                    .getWsClient()
                    .send(WsMessageType.USER_PING_REQUEST,
                            UserPingRequestPayload.builder().targetUserId(targetUserId).build());
        } catch (Exception e) {
            log.warn("Failed to send USER_PING_REQUEST: {}", e.getMessage());
        }
    }

    @Override
    protected void onHide() {
        try {
            ServiceContainer.getInstance()
                    .installation()
                    .getWsClient()
                    .send(WsMessageType.USER_PING_UNSUBSCRIBE,
                            UserPingRequestPayload.builder().targetUserId(targetUserId).build());
        } catch (Exception e) {
            log.warn("Failed to send USER_PING_UNSUBSCRIBE: {}", e.getMessage());
        }
    }
}
