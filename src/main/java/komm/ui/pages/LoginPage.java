package komm.ui.pages;

import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import komm.ui.customnodes.CustomNotification;
import lombok.extern.slf4j.Slf4j;
import atlantafx.base.controls.PasswordTextField;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import komm.ui.emojis.EmojiFilterTextField;
import javafx.scene.Group;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import komm.model.dto.summary.MainUserSummary;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import komm.App;
import komm.utils.AppConfig;
import komm.utils.KommUtils;

import static komm.App.startWebSocket;

@Slf4j
public class LoginPage extends BorderPane {

    private String loginUsername;
    private String loginPassword;
    private boolean loginRemember;
    private volatile MainUserSummary loggedInUser;

    private final Service<HomePage> loginService = new Service<>() {
        @Override
        protected Task<HomePage> createTask() {
            return new Task<>() {
                @Override
                protected HomePage call() throws Exception {
                    long t = System.currentTimeMillis();
                    App.getServices().hub().login(loginUsername, loginPassword);
                    startWebSocket();
                    App.setRememberMe(loginRemember);
                    if (loginRemember) {
                        KommUtils.saveRefreshToken(App.getServices().hub().getTokenManager().getRefreshToken());
                    } else {
                        KommUtils.clearSavedToken();
                    }
                    loggedInUser = App.getServices().hub().getUserService().getCurrentUser();

                    HomePage homePage = new HomePage();
                    return homePage;
                }
            };
        }
    };

    public LoginPage() {

        this.setOnMouseClicked(event -> this.requestFocus());
        // Same base every other page in the app uses (see .root in style.css:
        // -fx-background-color: -color-bg-default) so this doesn't read as a
        // separately-themed screen, just with a couple of soft accent blobs
        // behind the card for a bit of the marketing site's depth.
        this.setStyle(
                "-fx-background-color: " +
                "radial-gradient(center 20% 12%, radius 55%, -color-accent-subtle, transparent 70%), " +
                "radial-gradient(center 84% 88%, radius 60%, rgba(127,109,217,0.06), transparent 70%), " +
                "-color-bg-default;");

        HBox logoRow = new HBox(8, buildLogoMark(30), buildWordmark());
        logoRow.setAlignment(Pos.CENTER);

        VBox mainContainer = new VBox(20);
        mainContainer.setAlignment(Pos.CENTER);
        mainContainer.setPadding(new Insets(40));
        mainContainer.setMaxSize(450, 550);
        mainContainer.setMinSize(450, 550);
        // Same surface/border pair every other floating Komm panel already uses
        // (see .custom-modal in style.css: -color-bg-overlay + -color-accent-8),
        // plus a plain dark drop shadow (not accent-tinted) for real elevation.
        mainContainer.setStyle(
                "-fx-background-color: -color-bg-overlay; " +
                "-fx-background-radius: 14px; " +
                "-fx-border-radius: 14px; " +
                "-fx-border-color: -color-accent-8; " +
                "-fx-border-width: 1px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 26, 0, 0, 10);");

        // Header section
        VBox headerBox = new VBox(10);
        headerBox.setAlignment(Pos.CENTER);

        Label titleLabel = new Label("Welcome back");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 24));

        Label subtitleLabel = new Label("Sign in to continue");
        subtitleLabel.setStyle("-fx-text-fill: -color-fg-muted;");

        headerBox.getChildren().addAll(titleLabel, subtitleLabel);

        // Form section
        VBox formBox = new VBox(15);
        formBox.setAlignment(Pos.CENTER);
        formBox.setPadding(new Insets(20, 40, 20, 40));

        EmojiFilterTextField usernameTextField = new EmojiFilterTextField();
        usernameTextField.setPromptText("Username or Email");
        styleFormField(usernameTextField);

        PasswordTextField passwordTextField = new PasswordTextField();
        passwordTextField.setPromptText("Password");
        styleFormField(passwordTextField);

        // Password toggle icon
        FontIcon passwordIcon = new FontIcon(Feather.EYE_OFF);
        passwordIcon.setCursor(Cursor.HAND);
        passwordIcon.setOnMouseClicked(e -> {
            passwordIcon.setIconCode(passwordTextField.getRevealPassword() ? Feather.EYE_OFF : Feather.EYE);
            passwordTextField.setRevealPassword(!passwordTextField.getRevealPassword());
        });
        passwordTextField.setRight(passwordIcon);

        // Remember me and forgot password row
        HBox optionsRow = new HBox();
        optionsRow.setAlignment(Pos.CENTER);
        optionsRow.setSpacing(5);

        CheckBox rememberCheckbox = new CheckBox("Keep me signed in");

        Region remembermeFillerRegion = new Region();
        HBox.setHgrow(remembermeFillerRegion, Priority.ALWAYS);

        Hyperlink forgotPasswordLink = new Hyperlink("Forgot password?");
        forgotPasswordLink.setOnAction(event ->
                KommUtils.openUrl(AppConfig.getInstance().getApiUrl() + "/forgot-password"));

        optionsRow.getChildren().addAll(rememberCheckbox, remembermeFillerRegion, forgotPasswordLink);

        // Login button
        Button loginButton = new Button("Sign In", new FontIcon(MaterialDesignL.LOGIN));
        loginButton.setFocusTraversable(false);
        loginButton.setDefaultButton(true);
        loginButton.setStyle("-fx-font-size: 14px; -fx-padding: 8px 16px;");
        loginButton.setMaxWidth(Double.MAX_VALUE);

        // Spinner always present as a spacer; only visibility toggles
        ProgressIndicator loginSpinner = new ProgressIndicator();
        loginSpinner.setPrefSize(22, 22);
        loginSpinner.setMaxSize(22, 22);
        loginSpinner.setVisible(false);

        loginButton.setOnAction(event -> {
            String username = usernameTextField.getText().trim();
            String password = passwordTextField.getPassword();
            if (username.isEmpty() || password.isEmpty()) return;

            loginUsername = username;
            loginPassword = password;
            loginRemember = rememberCheckbox.isSelected();

            loginButton.setDisable(true);
            usernameTextField.setDisable(true);
            passwordTextField.setDisable(true);
            loginSpinner.setVisible(true);

            loginService.restart();
        });

        loginService.setOnSucceeded(e -> {
            HomePage homePage = loginService.getValue();
            App.setUser(loggedInUser);
            homePage.getProfileSection().refresh();
            App.setCachedHomePage(homePage);
            App.changePage(homePage);
            App.checkPendingInvite();
        });

        loginService.setOnFailed(e -> {
            loginSpinner.setVisible(false);
            loginButton.setDisable(false);
            usernameTextField.setDisable(false);
            passwordTextField.setDisable(false);
            new CustomNotification("Login Failed", "Incorrect username or password.",
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE))
                    .showNotification();
        });

        // Footer with sign up link
        HBox footerBox = new HBox(5);
        footerBox.setAlignment(Pos.CENTER);

        Label noAccountLabel = new Label("Don't have an account?");
        noAccountLabel.setStyle("-fx-text-fill: -color-fg-muted;");

        Hyperlink signUpLink = new Hyperlink("Sign up");
        signUpLink.setOnAction(event -> App.changePage(new RegisterPage()));

        footerBox.getChildren().addAll(noAccountLabel, signUpLink);

        Separator divider = new Separator();
        VBox.setMargin(divider, new Insets(-25, 40, -10, 40));

        formBox.getChildren().addAll(
                usernameTextField,
                passwordTextField,
                optionsRow,
                loginButton
        );

        mainContainer.getChildren().addAll(
                headerBox,
                formBox,
                divider,
                loginSpinner,
                footerBox
        );

        VBox pageContent = new VBox(18, logoRow, mainContainer);
        pageContent.setAlignment(Pos.CENTER);
        this.setCenter(pageContent);

        Platform.runLater(mainContainer::requestFocus);
    }

    private void styleFormField(TextField field) {
        field.setStyle("-fx-font-size: 14px; -fx-padding: 8px 12px;");
        field.setMaxWidth(Double.MAX_VALUE);
    }

    private static Label buildWordmark() {
        Label wordmark = new Label("Komm");
        wordmark.setStyle("-fx-font-size: 20px; -fx-font-weight: 800;");
        return wordmark;
    }

    /** Redraws the komm mark (badge circle + waveform bars) from icon.svg's 64x64 geometry. */
    private static Group buildLogoMark(double size) {
        double s = size / 64.0;

        Circle badge = new Circle(32 * s, 32 * s, 32 * s);
        badge.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#b8a4ff")),
                new Stop(0.55, Color.web("#9580ff")),
                new Stop(1, Color.web("#6b5bb3"))));

        Group logo = new Group(badge);
        double[][] bars = {{9, 25, 14}, {19, 19, 26}, {29, 13, 38}, {39, 19, 26}, {49, 25, 14}};
        for (double[] bar : bars) {
            Rectangle barRect = new Rectangle(bar[0] * s, bar[1] * s, 6 * s, bar[2] * s);
            barRect.setArcWidth(6 * s);
            barRect.setArcHeight(6 * s);
            barRect.setFill(Color.web("#0d0e12"));
            logo.getChildren().add(barRect);
        }
        logo.setStyle("-fx-effect: dropshadow(gaussian, -color-accent-emphasis, 8, 0.2, 0, 0);");
        return logo;
    }
}
