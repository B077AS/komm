package komm.ui.pages;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.model.dto.summary.ServerSummary;
import komm.ui.customnodes.ToolBar;
import komm.ui.sections.ChannelSection;
import komm.ui.chat.ChatSection;
import komm.utils.UserSettings;
import lombok.Getter;

public class ServerPage extends BorderPane {

    @Getter
    private ServerSummary server;
    @Getter
    private ChannelSection channelSection;
    @Getter
    private ChatSection chatSection;
    @Getter
    private ToolBar toolBar;

    public ServerPage(ServerSummary server) {
        this.server = server;
        this.setStyle("-fx-background-color: -color-bg-default;");

        channelSection = new ChannelSection(server);
        chatSection = new ChatSection(server);

        double savedPx = UserSettings.getInstance().getServerSplitPosition(server.getServerId(), server.getDefaultChannelPanelWidth());

        SplitPane splitPane = new SplitPane(channelSection, chatSection);
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
                        if (px > 0) UserSettings.getInstance().setServerSplitPosition(server.getServerId(), px);
                    });
                }
            });
        });
        SplitPane.setResizableWithParent(channelSection, false);

        this.setCenter(splitPane);
        toolBar = new ToolBar();
        this.setBottom(toolBar);
    }

}
