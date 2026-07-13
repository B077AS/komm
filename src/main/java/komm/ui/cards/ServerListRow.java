package komm.ui.cards;

import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import komm.App;
import komm.model.dto.summary.ServerSummary;
import lombok.Getter;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;

import java.io.ByteArrayInputStream;
import komm.ui.avatar.AvatarColor;

public class ServerListRow extends HBox {

    @Getter
    private final ServerSummary serverSummary;

    private static final int AVATAR_SIZE = 38;

    private Label onlineLabel;
    private Button connectButton;
    private ProgressIndicator connectingSpinner;

    public ServerListRow(ServerSummary summary) {
        this.serverSummary = summary;
        getStyleClass().add("server-list-row");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(9, 12, 9, 12));
        setSpacing(11);
        setCursor(Cursor.HAND);
        setMaxWidth(Double.MAX_VALUE);
        build();
    }

    private void build() {
        StackPane avatar = buildAvatar();

        Label name = new Label(serverSummary.getServerName());
        name.setStyle("-fx-font-size: 14.5px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        name.setMaxWidth(Double.MAX_VALUE);

        onlineLabel = new Label(formatOnline(serverSummary.getActiveUsers()));
        onlineLabel.setStyle("-fx-font-size: 10.5px; -fx-text-fill: -color-fg-subtle;");
        onlineLabel.setMaxWidth(Double.MAX_VALUE);

        VBox info = new VBox(2, name, onlineLabel);
        info.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(info, Priority.ALWAYS);

        // ── Connect icon (revealed on hover) ──────────────────────────────────
        FontIcon icon = new FontIcon(MaterialDesignL.LOGIN);
        icon.setIconSize(16);
        connectButton = new Button(null, icon);
        connectButton.setFocusTraversable(false);
        connectButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        connectButton.setTooltip(new Tooltip("Connect to server"));
        connectButton.setOpacity(0.0);
        connectButton.setMinSize(26, 26);
        connectButton.setMaxSize(26, 26);
        connectButton.setPrefSize(26, 26);
        connectButton.setOnAction(e -> {
            e.consume();
            App.changePage(App.getOrCreateServerPage(serverSummary));
        });


        addEventHandler(MouseEvent.MOUSE_ENTERED, e -> connectButton.setOpacity(0.75));
        addEventHandler(MouseEvent.MOUSE_EXITED, e -> connectButton.setOpacity(0.0));

        getChildren().addAll(avatar, info, connectButton);
    }

    private StackPane buildAvatar() {
        int stackSize = AVATAR_SIZE + 4;
        double r = AVATAR_SIZE / 2.0;

        Circle bgCircle = new Circle(r);

        byte[] bytes = serverSummary.getAvatarBytes();
        if (bytes != null && bytes.length > 0) {
            try {
                Image img = new Image(new ByteArrayInputStream(bytes), AVATAR_SIZE, AVATAR_SIZE, true, true);
                if (!img.isError()) {
                    bgCircle.setFill(new javafx.scene.paint.ImagePattern(img));
                    bgCircle.setStyle("-fx-stroke: -color-border-default; -fx-stroke-width: 1.5px;");
                } else bytes = null;
            } catch (Exception ignored) {
                bytes = null;
            }
        }

        StackPane stack = new StackPane(bgCircle);
        stack.setMinSize(stackSize, stackSize);
        stack.setMaxSize(stackSize, stackSize);
        stack.setPrefSize(stackSize, stackSize);
        stack.setAlignment(Pos.CENTER);

        if (bytes == null || bytes.length == 0) {
            String name = serverSummary.getServerName().trim();
            bgCircle.setFill(AvatarColor.forNameJfx(name));
            bgCircle.setStyle("-fx-stroke: -color-border-default; -fx-stroke-width: 1.5px;");
            String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
            Text t = new Text(letter);
            t.setFill(Color.WHITE);
            t.setFont(Font.font("System", FontWeight.BOLD, 14));
            stack.getChildren().add(t);
        }

        Circle dot = new Circle(5.5);
        dot.getStyleClass().add(statusCssClass());
        dot.setStyle("-fx-stroke: -color-bg-subtle; -fx-stroke-width: 2px;");
        StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
        stack.getChildren().add(dot);

        return stack;
    }

    public void updateOnlineCount(int count) {
        serverSummary.setActiveUsers(count);
        if (onlineLabel != null) onlineLabel.setText(formatOnline(count));
    }

    private String formatOnline(int count) {
        return count == 1 ? "1 online" : count + " online";
    }

    private String statusCssClass() {
        try {
            if (serverSummary.getStatus() != null) {
                return switch (serverSummary.getStatus().toString().toUpperCase()) {
                    case "ONLINE" -> "server-status-online";
                    case "OFFLINE" -> "server-status-offline";
                    default -> "server-status-unknown";
                };
            }
        } catch (Exception ignored) {
        }
        return "server-status-unknown";
    }

    public void setConnectAction(Runnable action) {
        connectButton.setOnAction(e -> {
            e.consume();
            action.run();
        });
    }

    public void showConnecting() {
        if (connectingSpinner != null) return;
        connectingSpinner = new ProgressIndicator();
        connectingSpinner.setPrefSize(16, 16);
        connectingSpinner.setMaxSize(16, 16);
        connectingSpinner.setMinSize(16, 16);
        int idx = getChildren().indexOf(connectButton);
        if (idx >= 0) getChildren().set(idx, connectingSpinner);
    }

    public void clearConnecting() {
        if (connectingSpinner == null) return;
        int idx = getChildren().indexOf(connectingSpinner);
        if (idx >= 0) getChildren().set(idx, connectButton);
        connectingSpinner = null;
    }

    public void setSelected(boolean selected) {
        if (selected) {
            if (!getStyleClass().contains("selected")) getStyleClass().add("selected");
        } else {
            getStyleClass().remove("selected");
        }
    }
}
