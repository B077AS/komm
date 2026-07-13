package komm.ui.customnodes;

import io.github.b077as.emojifx.util.TextUtils;
import javafx.animation.*;
import javafx.scene.Cursor;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.pages.HomePage;
import org.kordamp.ikonli.javafx.FontIcon;
import atlantafx.base.controls.Notification;
import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import komm.App;

import java.util.UUID;

public class CustomNotification {

    private final Notification notification;
    private final Timeline slideInAnimation;
    private final Timeline slideOutAnimation;
    private final PauseTransition pause;
    private StackPane customPane;

    public CustomNotification(String content, FontIcon icon) {
        this(new KommNotification(content, icon));
    }

    public CustomNotification(Node content, FontIcon icon) {
        this(new KommNotification(content, icon));
    }

    public CustomNotification(String title, String content, FontIcon icon) {
        this(buildTitledText(title, content), icon);
    }

    public CustomNotification(String title, String content, UUID senderId) {
        this(new KommNotification(buildForMessage(title, content, senderId), buildSenderAvatar(senderId)));
    }

    public CustomNotification(String title, Node content, FontIcon icon) {
        this(buildTitled(title, content), icon);
    }

    public CustomNotification(String channelName, String senderName, String content, FontIcon icon) {
        this(buildForChannelMessage(channelName, senderName, content), icon);
    }

    private static Node buildForChannelMessage(String channelName, String senderName, String content) {
        Text titleText = new Text(senderName + " in " + channelName);
        titleText.setStyle("-fx-font-weight: bold;");

        HBox textBox = new HBox();
        textBox.setAlignment(Pos.CENTER_LEFT);
        textBox.getChildren().addAll(TextUtils.convertToTextAndImageNodes(content, 12));

        return new VBox(2, titleText, textBox);
    }

    private static StackPane buildSenderAvatar(UUID senderId) {
        double size = 28;
        StackPane pane = new StackPane();
        pane.setPrefSize(size, size);
        pane.setMinSize(size, size);
        pane.setMaxSize(size, size);
        pane.setStyle("-fx-background-color: " + AvatarColor.forName(null) + ";" +
                "-fx-background-radius: " + (size / 2) + "px;");

        AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(senderId);
        if (cached != null) {
            fillAvatarPane(pane, cached, size);
        } else {
            Text t = new Text("?");
            t.setFill(Color.WHITE);
            t.setFont(Font.font("System", FontWeight.BOLD, size / 2.5));
            pane.getChildren().add(t);
            App.getAvatarCache().resolve(senderId).thenAccept(cu -> {
                if (cu != null) Platform.runLater(() -> fillAvatarPane(pane, cu, size));
            });
        }
        return pane;
    }

    private static void fillAvatarPane(StackPane pane, AvatarCache.CachedUser cu, double size) {
        pane.getChildren().clear();
        if (cu.avatar() != null && cu.avatar().length > 0) {
            try {
                Image img = new Image(new java.io.ByteArrayInputStream(cu.avatar()), size, size, true, true);
                ImageView iv = new ImageView(img);
                iv.setFitWidth(size);
                iv.setFitHeight(size);
                iv.setPreserveRatio(false);
                iv.setClip(new Circle(size / 2, size / 2, size / 2));
                pane.getChildren().add(iv);
                pane.setStyle("-fx-background-radius: " + (size / 2) + "px;");
                return;
            } catch (Exception ignored) {}
        }
        String letter = (cu.username() != null && !cu.username().isEmpty())
                ? String.valueOf(cu.username().charAt(0)).toUpperCase() : "?";
        Text t = new Text(letter);
        t.setFill(Color.WHITE);
        t.setFont(Font.font("System", FontWeight.BOLD, size / 2.5));
        pane.getChildren().add(t);
        pane.setStyle("-fx-background-color: " + AvatarColor.forName(cu.username()) + ";" +
                "-fx-background-radius: " + (size / 2) + "px;");
    }

    private static Node buildTitledText(String title, String content) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add(Styles.TEXT_BOLD);

        Label contentLabel = new Label(content);
        contentLabel.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        contentLabel.setWrapText(true);

        return new VBox(2, titleLabel, contentLabel);
    }

    private static Node buildForMessage(String title, String content, UUID senderId) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add(Styles.TEXT_BOLD);

        HBox textBox = new HBox();
        textBox.setAlignment(Pos.CENTER_LEFT);

        textBox.getChildren().addAll(TextUtils.convertToTextAndImageNodes(content, 12));

        return new VBox(2, titleLabel, textBox);
    }

    private static Node buildTitled(String title, Node content) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add(Styles.TEXT_BOLD);
        return new VBox(2, titleLabel, content);
    }

    private CustomNotification(Notification notification) {
        this.notification = notification;
        this.notification.getStyleClass().add(Styles.ELEVATED_1);
        this.notification.getStyleClass().add(Styles.ACCENT);
        this.notification.setPrefHeight(Region.USE_PREF_SIZE);
        this.notification.setMaxHeight(Region.USE_PREF_SIZE);

        StackPane.setAlignment(this.notification, Pos.TOP_RIGHT);
        StackPane.setMargin(this.notification, new Insets(70, 10, 0, 0));

        slideInAnimation = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(this.notification.translateXProperty(), 300)),
                new KeyFrame(Duration.millis(200), new KeyValue(this.notification.translateXProperty(), 0)));

        slideOutAnimation = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(this.notification.translateXProperty(), 0)),
                new KeyFrame(Duration.millis(250), new KeyValue(this.notification.translateXProperty(), 300)));

        pause = new PauseTransition(Duration.seconds(3));
        pause.setOnFinished(event -> dismissNotification());

        this.notification.setOnMouseEntered(event -> {
            App.getModalPane().setPersistent(true);
            pause.pause();
            slideOutAnimation.pause();
        });

        this.notification.setOnMouseExited(event -> {
            App.getModalPane().setPersistent(false);
            pause.play();
            if (slideOutAnimation.getStatus() == Animation.Status.PAUSED) {
                slideOutAnimation.play();
            }
        });

        this.notification.setOnClose(e -> dismissNotification());

        slideInAnimation.setOnFinished(e -> pause.play());
        slideOutAnimation.setOnFinished(e -> {
            if (customPane != null) {
                customPane.getChildren().remove(this.notification);
            } else {
                App.getStackPane().getChildren().remove(this.notification);
            }
        });
    }

    public CustomNotification withOnClick(Runnable action) {
        notification.setCursor(Cursor.HAND);
        notification.setOnMouseClicked(e -> {
            dismissNotification();
            action.run();
        });
        return this;
    }

    public void showNotification() {
        showNotificationAuto();
    }

    private void showNotificationAuto() {
        StackPane target;

        if (App.getModalPane().isDisplay()) {
            var content = App.getModalPane().getContent();
            if (content != null && content.getParent() instanceof StackPane contentParent) {
                target = contentParent;
            } else {
                target = App.getStackPane();
            }
        } else {
            target = App.getStackPane();
        }

        double topMargin = App.getCurrentPage() instanceof HomePage ? 110 : 70;
        StackPane.setMargin(notification, new Insets(topMargin, 10, 0, 0));

        showNotificationOnCustomPane(target);
    }

    private void showNotificationOnCustomPane(StackPane pane) {
        this.customPane = pane;

        StackPane.setMargin(notification, new Insets(70, 10, 0, 0));

        notification.setTranslateX(300);

        if (pane.getChildren().contains(notification)) {
            pane.getChildren().remove(notification);
        }
        Platform.runLater(() -> pane.getChildren().add(notification));
        slideInAnimation.playFromStart();
    }

    private void dismissNotification() {
        slideInAnimation.stop();
        pause.stop();
        slideOutAnimation.playFromStart();
    }
}
