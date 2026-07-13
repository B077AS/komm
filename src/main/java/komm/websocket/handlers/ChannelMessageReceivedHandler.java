package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import komm.App;
import komm.AppState;
import komm.api.json.GsonProvider;
import komm.model.dto.summary.ChannelSummary;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import komm.ui.chat.ChatSection;
import komm.ui.customnodes.CustomNotification;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.MessageReceivedPayload;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class ChannelMessageReceivedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_MESSAGE_RECEIVED;
    }

    @Override
    public void handle(JsonObject payload) {
        MessageReceivedPayload p = gson.fromJson(payload, MessageReceivedPayload.class);

        ChatSection chat = App.getCachedServerPage() != null ? App.getCachedServerPage().getChatSection() : null;
        if (chat != null) {
            chat.addMessage(p);
        }

        Platform.runLater(() -> {
            UUID myId = App.getUser() != null ? App.getUser().getUserId() : null;
            if (myId == null || myId.equals(p.getSenderId())) return;

            var serverPage = App.getCachedServerPage();
            if (serverPage == null) return;

            var channelCard = serverPage.getChannelSection().getChannelBoxes().get(p.getChannelId());
            if (channelCard == null) return;
            ChannelSummary channel = channelCard.getChannel();

            // Voice channels only notify if we're currently connected to that channel's call.
            boolean isVoice = channel.getChannelType() == ChannelSummary.ChannelType.VOICE;
            if (isVoice) {
                UUID currentVoiceChannelId = App.getWebrtcRoomClient() != null
                        ? App.getWebrtcRoomClient().getCurrentChannelId() : null;
                if (!p.getChannelId().equals(currentVoiceChannelId)) return;
            }

            // "Actually looking at" this channel means it's the open channel on the visible ServerPage.
            ChatSection chatSection = serverPage.getChatSection();
            boolean isViewingChannel = App.getCurrentPage() == serverPage
                    && chatSection != null && p.getChannelId() != null
                    && p.getChannelId().equals(chatSection.getActiveTextChannelId());
            boolean isWindowFocused = App.isWindowFocused();

            // Unread dot mirrors "am I looking at it", independent of window focus.
            if (!isViewingChannel) {
                channelCard.showNotificationDot();
            } else if (isWindowFocused && chatSection.isAtBottom()) {
                // Fully seen live (open + focused + scrolled to the newest message) — keep the
                // server-side read watermark fresh so it doesn't report this channel as unread
                // again on next login/resume. If the user is scrolled up reading history, this
                // new message is off-screen and must not be marked read yet.
                UUID channelId = p.getChannelId();
                Thread.ofVirtual().start(() -> {
                    try {
                        App.getServices().installation().getChannelService().markChannelRead(channelId);
                    } catch (Exception e) {
                        log.warn("Failed to mark channel {} as read: {}", channelId, e.getMessage());
                    }
                });
            }

            if (AppState.userStatusProperty().get() == UserStatus.DO_NOT_DISTURB) return;
            if (!serverPage.getServer().isChannelNotificationsEnabled()) return;

            // Toast only when not viewing the channel; sound also plays if viewing it
            // but the app window isn't focused (looking at another app).
            boolean showToast = !isViewingChannel;
            boolean playSound = !isViewingChannel || !isWindowFocused;
            if (!showToast && !playSound) return;

            if (playSound) {
                try {
                    var url = getClass().getResource("/sounds/universfield-new-notification-010-352755.mp3");
                    if (url != null) {
                        MediaPlayer player = new MediaPlayer(new Media(url.toExternalForm()));
                        player.setVolume(0.5);
                        player.setOnEndOfMedia(player::dispose);
                        player.play();
                    }
                } catch (Exception ignored) {}
            }

            if (!showToast) return;

            final String preview;
            if (p.getMessageType() == MessageReceivedPayload.MessageType.GIF) {
                preview = "GIF";
            } else if (p.isHasAttachments()) {
                preview = p.getFileName() != null ? p.getFileName() : "Attachment";
            } else {
                String content = p.getContent() != null ? p.getContent() : "";
                preview = content.length() > 80 ? content.substring(0, 80) + "..." : content;
            }

            FontIcon channelIcon;
            if (channel.getIcon() != null && !channel.getIcon().isBlank()) {
                try {
                    channelIcon = new FontIcon(channel.getIcon());
                } catch (Exception ignored) {
                    channelIcon = isVoice ? new FontIcon(MaterialDesignM.MICROPHONE) : new FontIcon(Feather.HASH);
                }
            } else {
                channelIcon = isVoice ? new FontIcon(MaterialDesignM.MICROPHONE) : new FontIcon(Feather.HASH);
            }
            final FontIcon finalChannelIcon = channelIcon;
            final String channelName = channel.getChannelName() != null ? channel.getChannelName() : "Channel";

            App.getAvatarCache().resolve(p.getSenderId()).thenAccept(cu -> {
                String senderName = cu != null && cu.username() != null ? cu.username() : "Unknown";
                Platform.runLater(() ->
                    new CustomNotification(channelName, senderName, preview, finalChannelIcon)
                        .showNotification()
                );
            });
        });
    }
}
