package komm.ui.customnodes;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import komm.App;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.utils.WindowsThemeUtil;

import static com.sun.jna.Platform.isWindows;

import java.io.ByteArrayInputStream;
import java.util.UUID;

public class PokeNotificationWindow {

    private static final double AVATAR_SIZE = 36;

    private final UUID senderUserId;
    private final String senderUsername;
    private final String message;

    public PokeNotificationWindow(UUID senderUserId, String senderUsername, String message) {
        this.senderUserId = senderUserId;
        this.senderUsername = senderUsername;
        this.message = message;
    }

    public void show() {
        StackPane avatarPane = buildAvatarPane();

        Label usernameLabel = new Label(senderUsername != null ? senderUsername : "Unknown");
        usernameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        HBox senderRow = new HBox(10, avatarPane, usernameLabel);
        senderRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, senderRow);
        root.setPadding(new Insets(16));
        root.setMinWidth(260);

        if (message != null && !message.isBlank()) {
            Label msgLabel = new Label(message);
            msgLabel.setWrapText(true);
            root.getChildren().add(msgLabel);
        }

        Stage stage = new Stage();
        stage.setTitle("Poke from " + (senderUsername != null ? senderUsername : "someone"));
        stage.initModality(Modality.NONE);
        stage.initOwner(App.getStackPane().getScene().getWindow());
        stage.setResizable(false);
        stage.setAlwaysOnTop(true);
        stage.setScene(new Scene(root));
        Stage primary = (Stage) App.getStackPane().getScene().getWindow();
        stage.getIcons().addAll(primary.getIcons());

        stage.setOpacity(0);
        stage.show();
        centerOnPhysicalScreen(stage);

        if (isWindows()) {
            String title = stage.getTitle();
            Thread.ofVirtual().start(() -> {
                WindowsThemeUtil.enableDarkTitleBar(title);
                Platform.runLater(() -> stage.setOpacity(1));
            });
        } else {
            stage.setOpacity(1);
        }
    }

    private void centerOnPhysicalScreen(Stage stage) {
        Window owner = App.getStackPane().getScene().getWindow();
        Screen screen = Screen.getScreensForRectangle(
                owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight())
                .stream().findFirst().orElse(Screen.getPrimary());
        Rectangle2D bounds = screen.getBounds();
        stage.setX(bounds.getMinX() + (bounds.getWidth() - stage.getWidth()) / 2.0);
        stage.setY(bounds.getMinY() + (bounds.getHeight() - stage.getHeight()) / 2.0);
    }

    private StackPane buildAvatarPane() {
        StackPane pane = new StackPane();
        pane.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        pane.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        pane.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);

        AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(senderUserId);
        fillAvatarPane(pane, cached);

        if (cached == null) {
            App.getAvatarCache().resolve(senderUserId).thenAccept(cu -> {
                if (cu != null) javafx.application.Platform.runLater(() -> fillAvatarPane(pane, cu));
            });
        }

        return pane;
    }

    private void fillAvatarPane(StackPane pane, AvatarCache.CachedUser cu) {
        pane.getChildren().clear();

        if (cu != null && cu.avatar() != null && cu.avatar().length > 0) {
            try {
                Image img = new Image(new ByteArrayInputStream(cu.avatar()),
                        AVATAR_SIZE, AVATAR_SIZE, true, true);
                ImageView iv = new ImageView(img);
                iv.setFitWidth(AVATAR_SIZE);
                iv.setFitHeight(AVATAR_SIZE);
                iv.setPreserveRatio(false);
                Circle clip = new Circle(AVATAR_SIZE / 2, AVATAR_SIZE / 2, AVATAR_SIZE / 2);
                iv.setClip(clip);
                pane.getChildren().add(iv);
                pane.setStyle("-fx-background-color: transparent; -fx-background-radius: " + (AVATAR_SIZE / 2) + "px;");
                return;
            } catch (Exception ignored) {}
        }

        String name = (cu != null && cu.username() != null) ? cu.username() : senderUsername;
        String letter = (name != null && !name.isEmpty())
                ? String.valueOf(name.charAt(0)).toUpperCase() : "?";
        Text t = new Text(letter);
        t.setFill(Color.WHITE);
        t.setFont(Font.font("System", FontWeight.BOLD, AVATAR_SIZE / 2.5));
        pane.getChildren().add(t);
        pane.setStyle("-fx-background-color: " + AvatarColor.forName(name) + ";" +
                "-fx-background-radius: " + (AVATAR_SIZE / 2) + "px;");
    }
}
