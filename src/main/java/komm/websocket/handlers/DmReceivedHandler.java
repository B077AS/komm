package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.AppState;
import komm.api.json.GsonProvider;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import komm.utils.NotificationSounds;
import komm.utils.UserSettings;
import komm.ui.avatar.AvatarCache;
import komm.ui.customnodes.CustomNotification;
import komm.ui.pages.DirectMessagePage;
import komm.ui.pages.HomePage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.DmReceivedPayload;
import komm.websocket.messages.payloads.MessageReceivedPayload;
import java.util.UUID;

public class DmReceivedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.DM_RECEIVED;
    }

    @Override
    public void handle(JsonObject payload) {
        DmReceivedPayload p = gson.fromJson(payload, DmReceivedPayload.class);
        Platform.runLater(() -> {
            DirectMessagePage page = App.getCachedDirectMessagePage();
            if (page != null) {
                page.getChatSection().addMessage(p);
                page.getSidebarSection().onMessageReceived(p);
            }

            // Only show notification for incoming messages (not the sender's own echo)
            UUID myId = App.getUser() != null ? App.getUser().getUserId() : null;
            if (myId == null || myId.equals(p.getSenderId())) return;

            // Mark global DM unread badge
            boolean isViewing = page != null && page.getScene() != null
                    && p.getSenderId().equals(page.getChatSection().getActiveConversationPartnerId());
            if (!isViewing) {
                App.setDmUnread(true);
                if (page == null) {
                    App.addPendingDmUnreadPartner(p.getSenderId());
                }
            }

            // Suppress if notifications are disabled or user is on Do Not Disturb
            if (!UserSettings.getInstance().isDmNotificationsEnabled()) return;
            if (AppState.userStatusProperty().get() == UserStatus.DO_NOT_DISTURB) return;

            if (isViewing) return;

            // Derive preview from the payload
            final String preview;
            if (p.getMessageType() == MessageReceivedPayload.MessageType.GIF) {
                preview = "GIF";
            } else if (p.isHasAttachments()) {
                preview = p.getFileName() != null ? p.getFileName() : "Attachment";
            } else {
                String content = p.getContent() != null ? p.getContent() : "";
                preview = content.length() > 80 ? content.substring(0, 80) + "..." : content;
            }

            // Resolve sender (fetches from hub if not cached; instant if already present)
            UUID senderId = p.getSenderId();
            App.getAvatarCache().resolve(senderId).thenAccept(cu -> {
                String senderName = cu != null && cu.username() != null ? cu.username() : "New message";
                Platform.runLater(() -> {
                    NotificationSounds.play(NotificationSounds.MESSAGE_RECEIVED, 0.5);
                    new CustomNotification(senderName, preview, senderId)
                        .withOnClick(() -> {
                            if (App.getCurrentPage() instanceof HomePage hp) {
                                hp.navigateToDm(senderId, senderName);
                            } else if (App.getCachedHomePage() != null) {
                                App.changePage(App.getCachedHomePage());
                                Platform.runLater(() -> {
                                    if (App.getCurrentPage() instanceof HomePage hp) {
                                        hp.navigateToDm(senderId, senderName);
                                    }
                                });
                            }
                        })
                        .showNotification();
                });
            });
        });
    }
}
