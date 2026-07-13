package komm.ui.customnodes;

import atlantafx.base.controls.Notification;
import atlantafx.base.controls.NotificationSkin;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Skin;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

public class KommNotification extends Notification {

    private final Node contentNode;

    public KommNotification(String message, Node graphic) {
        super(message, graphic);
        this.contentNode = null;
    }

    public KommNotification(Node contentNode, Node graphic) {
        super(null, graphic);
        this.contentNode = contentNode;
    }

    public Node getContentNode() {
        return contentNode;
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new KommNotificationSkin(this);
    }

    static class KommNotificationSkin extends NotificationSkin {

        KommNotificationSkin(KommNotification control) {
            super(control);
            Node content = control.getContentNode();
            if (content != null) {
                HBox.setHgrow(content, Priority.ALWAYS);
                int idx = header.getChildren().indexOf(messageText);
                if (idx >= 0) {
                    header.getChildren().set(idx, content);
                }
                header.setAlignment(Pos.CENTER_LEFT);
                HBox.setMargin(actionsBox, new Insets(0, -8, 0, 0));
            }
        }
    }
}
