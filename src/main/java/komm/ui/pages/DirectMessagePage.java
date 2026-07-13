package komm.ui.pages;

import javafx.application.Platform;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import komm.ui.chat.DmChatSection;
import komm.ui.sections.DirectMessageSidebarSection;
import komm.utils.UserSettings;
import lombok.Getter;

import java.util.UUID;

public class DirectMessagePage extends BorderPane {

    @Getter
    private final DirectMessageSidebarSection sidebarSection;
    @Getter
    private final DmChatSection chatSection;

    public DirectMessagePage() {
        setStyle("-fx-background-color: -color-bg-default;");

        sidebarSection = new DirectMessageSidebarSection();
        chatSection = new DmChatSection();

        double savedPx = UserSettings.getInstance().getDmSplitPosition();

        SplitPane splitPane = new SplitPane(sidebarSection, chatSection);
        splitPane.widthProperty().addListener((obs, oldW, newW) -> {
            if (newW.doubleValue() == 0) return;
            if (oldW.doubleValue() == 0) {
                splitPane.setDividerPosition(0, savedPx / newW.doubleValue());
            } else {
                double pixelWidth = splitPane.getDividerPositions()[0] * oldW.doubleValue();
                Platform.runLater(() -> splitPane.setDividerPosition(0, pixelWidth / newW.doubleValue()));
            }
        });
        splitPane.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin == null) return;
            Platform.runLater(() -> {
                javafx.scene.Node dividerNode = splitPane.lookup(".split-pane-divider");
                if (dividerNode != null) {
                    dividerNode.setOnMouseReleased(e -> {
                        double px = splitPane.getDividerPositions()[0] * splitPane.getWidth();
                        if (px > 0) UserSettings.getInstance().setDmSplitPosition(px);
                    });
                }
            });
        });
        SplitPane.setResizableWithParent(sidebarSection, false);

        setCenter(splitPane);

        sidebarSection.setOnConversationSelected((partnerId, partnerUsername) -> {
            sidebarSection.setSelectedPartner(partnerId);
            chatSection.setActiveConversation(partnerId, partnerUsername);
        });
    }

    public void openConversation(UUID partnerId, String partnerUsername) {
        sidebarSection.ensureConversation(partnerId, partnerUsername);
        sidebarSection.setSelectedPartner(partnerId);
        chatSection.setActiveConversation(partnerId, partnerUsername);
    }

    public void handleMessageDeleted(java.util.UUID messageId, java.util.UUID partnerId) {
        if (chatSection.isActive(partnerId)) {
            chatSection.removeMessage(messageId, partnerId,
                    newLast -> sidebarSection.onMessageDeleted(partnerId, newLast));
        } else {
            sidebarSection.reload();
        }
    }

    public void reload() {
        sidebarSection.reload();
    }

    public void onBecameVisible() {
        sidebarSection.clearUnreadForSelected();
    }
}
