package komm.ui.pages;

import javafx.application.Platform;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.javafx.FontIcon;
import atlantafx.base.controls.PasswordTextField;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.*;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.request.RegisterRequest;
import komm.ui.emojis.EmojiFilterTextField;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;

public class RegisterPage extends BorderPane {

    private RegisterRequest currentRequest;
    private volatile String registeredEmail;

    private final Service<Void> registerService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().hub().getUserService().register(currentRequest);
                    return null;
                }
            };
        }
    };

    public RegisterPage() {

        setOnMouseClicked(event -> requestFocus());
        // Same base every other page in the app uses (see .root in style.css:
        // -fx-background-color: -color-bg-default) so this doesn't read as a
        // separately-themed screen, just with a couple of soft accent blobs
        // behind the card for a bit of the marketing site's depth.
        setStyle(
                "-fx-background-color: " +
                "radial-gradient(center 20% 12%, radius 55%, -color-accent-subtle, transparent 70%), " +
                "radial-gradient(center 84% 88%, radius 60%, rgba(127,109,217,0.06), transparent 70%), " +
                "-color-bg-default;");

        HBox logoRow = new HBox(8, buildLogoMark(30), buildWordmark());
        logoRow.setAlignment(Pos.CENTER);

        VBox mainContainer = new VBox(20);
        mainContainer.setAlignment(Pos.CENTER);
        mainContainer.setPadding(new Insets(40));
        mainContainer.setMaxSize(500, 600);
        mainContainer.setMinSize(500, 600);
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

        // Header
        VBox headerBox = new VBox(10);
        headerBox.setAlignment(Pos.CENTER);
        Label titleLabel = new Label("Create an Account");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 24));
        Label subtitleLabel = new Label("Join Komm today");
        subtitleLabel.setStyle("-fx-text-fill: -color-fg-muted;");
        headerBox.getChildren().addAll(titleLabel, subtitleLabel);

        // Form
        VBox formBox = new VBox(15);
        formBox.setAlignment(Pos.CENTER);
        formBox.setPadding(new Insets(20, 40, 20, 40));

        EmojiFilterTextField usernameField = EmojiFilterTextField.username(32);
        usernameField.setPromptText("Username");
        styleFormField(usernameField);


        EmojiFilterTextField emailField = EmojiFilterTextField.email();
        emailField.setPromptText("Email Address");
        styleFormField(emailField);

        PasswordTextField passwordField = new PasswordTextField();
        passwordField.setPromptText("Password (min. 6 characters)");
        styleFormField(passwordField);
        FontIcon passwordIcon = new FontIcon(Feather.EYE_OFF);
        passwordIcon.setCursor(Cursor.HAND);
        passwordIcon.setOnMouseClicked(e -> {
            passwordIcon.setIconCode(passwordField.getRevealPassword() ? Feather.EYE_OFF : Feather.EYE);
            passwordField.setRevealPassword(!passwordField.getRevealPassword());
        });
        passwordField.setRight(passwordIcon);

        PasswordTextField confirmPasswordField = new PasswordTextField();
        confirmPasswordField.setPromptText("Confirm Password");
        styleFormField(confirmPasswordField);
        FontIcon confirmPasswordIcon = new FontIcon(Feather.EYE_OFF);
        confirmPasswordIcon.setCursor(Cursor.HAND);
        confirmPasswordIcon.setOnMouseClicked(e -> {
            confirmPasswordIcon.setIconCode(confirmPasswordField.getRevealPassword() ? Feather.EYE_OFF : Feather.EYE);
            confirmPasswordField.setRevealPassword(!confirmPasswordField.getRevealPassword());
        });
        confirmPasswordField.setRight(confirmPasswordIcon);

        // Closed beta: shown only if the hub says a key is required (GET /api/auth/register-info)
        TextField betaKeyField = new TextField();
        betaKeyField.setPromptText("Beta Key (KOMM-XXXX-XXXX-XXXX)");
        styleFormField(betaKeyField);
        betaKeyField.setVisible(false);
        betaKeyField.setManaged(false);

        Thread betaCheck = new Thread(() -> {
            try {
                if (App.getServices().hub().getUserService().getRegisterInfo().isBetaRequired()) {
                    Platform.runLater(() -> {
                        betaKeyField.setVisible(true);
                        betaKeyField.setManaged(true);
                        mainContainer.setMinHeight(650);
                        mainContainer.setMaxHeight(650);
                    });
                }
            } catch (Exception ignored) {
                // Hub unreachable or older hub without the endpoint — register()
                // will report a proper error if a key turns out to be required.
            }
        }, "beta-check");
        betaCheck.setDaemon(true);
        betaCheck.start();

        CheckBox termsCheckbox = new CheckBox("I agree to the Terms and Conditions");
        termsCheckbox.setSelected(true);
        Hyperlink termsLink = new Hyperlink("View Terms");
        Region termsFillerRegion = new Region();
        HBox.setHgrow(termsFillerRegion, Priority.ALWAYS);
        HBox termsBox = new HBox(termsCheckbox, termsFillerRegion, termsLink);
        termsBox.setSpacing(5);
        termsBox.setAlignment(Pos.CENTER_LEFT);
        termsBox.setVisible(false);
        termsBox.setManaged(false);

        Button registerButton = new Button("Create Account", new FontIcon(MaterialDesignA.ACCOUNT_PLUS));
        registerButton.setFocusTraversable(false);
        registerButton.setDefaultButton(true);
        registerButton.setStyle("-fx-font-size: 14px; -fx-padding: 8px 16px;");
        registerButton.setMaxWidth(Double.MAX_VALUE);

        ProgressIndicator registerSpinner = new ProgressIndicator();
        registerSpinner.setPrefSize(22, 22);
        registerSpinner.setMaxSize(22, 22);
        registerSpinner.setVisible(false);

        // Wire service callbacks once
        registerService.setOnSucceeded(e -> App.changePage(new EmailVerificationPage(registeredEmail)));
        registerService.setOnFailed(e -> {
            Throwable ex = registerService.getException();
            ex.printStackTrace();
            new CustomNotification("Registration Failed", HttpStatusException.extractMessage(ex), new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE))
                    .showNotification();
        });
        registerService.runningProperty().addListener((obs, wasRunning, isRunning) -> {
            registerButton.setDisable(isRunning);
            registerSpinner.setVisible(isRunning);
        });

        registerButton.setOnAction(event -> {
            String username = usernameField.getText().trim();
            String email = emailField.getText().trim();
            String password = passwordField.getPassword();
            String confirmPassword = confirmPasswordField.getPassword();

            if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
                new CustomNotification("Validation Error", "Please fill in all fields.", new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE))
                        .showNotification();
                return;
            }
            if (username.length() > 32) {
                new CustomNotification("Validation Error", "Username must be 32 characters or fewer.", new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE))
                        .showNotification();
                return;
            }
            if (!username.matches("[a-zA-Z0-9_-]+")) {
                new CustomNotification("Validation Error", "Username may only contain letters, numbers, _ and -.", new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE))
                        .showNotification();
                return;
            }
            if (password.length() < 6) {
                new CustomNotification("Validation Error", "Password must be at least 6 characters.", new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE))
                        .showNotification();
                return;
            }
            if (!password.equals(confirmPassword)) {
                new CustomNotification("Validation Error", "Passwords do not match.", new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE))
                        .showNotification();
                return;
            }
            if (!termsCheckbox.isSelected()) {
                new CustomNotification("Validation Error", "You must agree to the Terms and Conditions.", new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE))
                        .showNotification();
                return;
            }
            String betaKey = betaKeyField.getText().trim().toUpperCase();
            if (betaKeyField.isVisible() && betaKey.isEmpty()) {
                new CustomNotification("Validation Error", "A beta key is required during the closed beta.", new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE))
                        .showNotification();
                return;
            }

            registeredEmail = email;
            currentRequest = RegisterRequest.builder()
                    .username(username)
                    .email(email)
                    .password(password)
                    .betaKey(betaKey.isEmpty() ? null : betaKey)
                    .build();

            registerService.restart();
        });

        formBox.getChildren().addAll(
                usernameField, emailField, passwordField,
                confirmPasswordField, betaKeyField, termsBox, registerButton
        );

        Separator divider = new Separator();
        VBox.setMargin(divider, new Insets(-25, 40, -10, 40));

        // Footer
        HBox footerBox = new HBox();
        footerBox.setAlignment(Pos.CENTER);
        Label haveAccountLabel = new Label("Already have an account?");
        haveAccountLabel.setStyle("-fx-text-fill: -color-fg-muted;");
        Hyperlink loginLink = new Hyperlink("Sign in");
        loginLink.setOnAction(event -> App.changePage(new LoginPage()));
        footerBox.getChildren().addAll(haveAccountLabel, loginLink);

        mainContainer.getChildren().addAll(headerBox, formBox, divider, registerSpinner, footerBox);

        VBox pageContent = new VBox(18, logoRow, mainContainer);
        pageContent.setAlignment(Pos.CENTER);
        setCenter(pageContent);

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