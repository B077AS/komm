package komm.ui.pages;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import komm.App;
import komm.api.HttpStatusException;
import komm.ui.customnodes.CustomNotification;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class EmailVerificationPage extends BorderPane {

    private final String email;
    private volatile String pendingCode;
    private Timeline countdownTimeline;

    private final Service<Void> verifyService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().hub().verifyEmail(email, pendingCode);
                    return null;
                }
            };
        }
    };

    private final Service<Void> resendService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().hub().resendVerification(email);
                    return null;
                }
            };
        }
    };

    public EmailVerificationPage(String email) {
        this.email = email;
        buildUI();
    }

    private void buildUI() {
        setOnMouseClicked(e -> requestFocus());
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

        VBox mainContainer = new VBox(22);
        mainContainer.setAlignment(Pos.CENTER);
        mainContainer.setPadding(new Insets(40));
        mainContainer.setMaxWidth(470);
        mainContainer.setMinWidth(470);
        mainContainer.setMaxHeight(Region.USE_PREF_SIZE);
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

        // ── Header ──────────────────────────────────────────────────────────
        VBox headerBox = new VBox(10);
        headerBox.setAlignment(Pos.CENTER);

        FontIcon mailIcon = new FontIcon(Feather.MAIL);
        mailIcon.getStyleClass().add("custom-icon-35-emphasis");

        Label titleLabel = new Label("Check your inbox");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 24));

        String displayEmail = email.length() > 32 ? email.substring(0, 30) + "…" : email;
        Label subtitleLabel = new Label("We sent a 6-digit code to\n" + displayEmail);
        subtitleLabel.setStyle("-fx-text-fill: -color-fg-muted;");
        subtitleLabel.setWrapText(true);
        subtitleLabel.setTextAlignment(TextAlignment.CENTER);
        subtitleLabel.setAlignment(Pos.CENTER);

        Label expiryLabel = new Label("The code expires in 15 minutes.");
        expiryLabel.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 12px;");

        headerBox.getChildren().addAll(mailIcon, titleLabel, subtitleLabel, expiryLabel);

        // ── Code input boxes ─────────────────────────────────────────────────
        List<TextField> digitFields = new ArrayList<>();
        HBox codeBox = new HBox(10);
        codeBox.setAlignment(Pos.CENTER);

        for (int i = 0; i < 6; i++) {
            final int idx = i;
            TextField digit = new TextField();
            digit.setAlignment(Pos.CENTER);
            digit.setPrefWidth(52);
            digit.setMaxWidth(52);
            digit.setMinWidth(52);
            digit.setPrefHeight(60);
            digit.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-padding: 4px;");

            digit.textProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal.isEmpty()) return;
                // Distribute paste across remaining boxes
                String digits = newVal.replaceAll("[^0-9]", "");
                if (digits.length() > 1) {
                    for (int j = 0; j < digits.length() && (idx + j) < digitFields.size(); j++) {
                        digitFields.get(idx + j).setText(String.valueOf(digits.charAt(j)));
                    }
                    int next = Math.min(idx + digits.length(), digitFields.size() - 1);
                    digitFields.get(next).requestFocus();
                    return;
                }
                if (!digits.isEmpty()) {
                    digit.setText(digits);
                    if (idx < digitFields.size() - 1) {
                        digitFields.get(idx + 1).requestFocus();
                    }
                } else {
                    digit.setText("");
                }
            });

            digit.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.BACK_SPACE && digit.getText().isEmpty() && idx > 0) {
                    digitFields.get(idx - 1).clear();
                    digitFields.get(idx - 1).requestFocus();
                }
            });

            digitFields.add(digit);
            codeBox.getChildren().add(digit);
        }

        // ── Submit button ────────────────────────────────────────────────────
        Button submitButton = new Button("Verify Email");
        submitButton.setFocusTraversable(false);
        submitButton.setDefaultButton(true);
        submitButton.setStyle("-fx-font-size: 14px; -fx-padding: 8px 16px;");
        submitButton.setMaxWidth(Double.MAX_VALUE);

        // ── Resend section ───────────────────────────────────────────────────
        Label resendLabel = new Label("Didn't receive a code?");
        resendLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");

        Hyperlink resendLink = new Hyperlink("Resend");
        resendLink.setStyle("-fx-font-size: 13px;");
        resendLink.setDisable(true);

        AtomicInteger countdown = new AtomicInteger(60);
        Label timerLabel = new Label("(60s)");
        timerLabel.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 12px;");

        HBox resendBox = new HBox(6);
        resendBox.setAlignment(Pos.CENTER);
        resendBox.getChildren().addAll(resendLabel, resendLink, timerLabel);

        countdownTimeline = buildCountdownTimeline(countdown, timerLabel, resendLink);
        countdownTimeline.playFromStart();

        // ── Service callbacks ────────────────────────────────────────────────
        verifyService.setOnSucceeded(e -> {
            new CustomNotification("Email Verified", "You can now sign in with your account.",
                    new FontIcon(Feather.MAIL)).showNotification();
            App.changePage(new LoginPage());
        });
        verifyService.setOnFailed(e -> {
            Throwable ex = verifyService.getException();
            new CustomNotification("Verification Failed", HttpStatusException.extractMessage(ex),
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
        });
        verifyService.runningProperty().addListener((obs, was, isRunning) ->
                submitButton.setDisable(isRunning));

        resendService.setOnSucceeded(e -> {
            resendLink.setDisable(true);
            countdown.set(60);
            timerLabel.setText("(60s)");
            countdownTimeline.stop();
            countdownTimeline = buildCountdownTimeline(countdown, timerLabel, resendLink);
            countdownTimeline.playFromStart();
            new CustomNotification("Code Resent", "Check your inbox for a new verification code.",
                    new FontIcon(Feather.MAIL)).showNotification();
        });
        resendService.setOnFailed(e -> {
            Throwable ex = resendService.getException();
            new CustomNotification("Resend Failed", HttpStatusException.extractMessage(ex),
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
            resendLink.setDisable(false);
        });
        resendService.runningProperty().addListener((obs, was, isRunning) -> {
            if (isRunning) resendLink.setDisable(true);
        });

        submitButton.setOnAction(e -> {
            StringBuilder code = new StringBuilder();
            for (TextField f : digitFields) code.append(f.getText().trim());
            if (code.length() < 6) {
                new CustomNotification("Incomplete Code", "Please enter the complete 6-digit verification code.",
                        new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
                return;
            }
            pendingCode = code.toString();
            verifyService.restart();
        });

        resendLink.setOnAction(e -> resendService.restart());

        // ── Back to registration ─────────────────────────────────────────────
        HBox footerBox = new HBox(5);
        footerBox.setAlignment(Pos.CENTER);
        Label wrongEmailLabel = new Label("Wrong email?");
        wrongEmailLabel.setStyle("-fx-text-fill: -color-fg-muted;");
        Hyperlink backLink = new Hyperlink("Go back");
        backLink.setOnAction(e -> App.changePage(new RegisterPage()));
        footerBox.getChildren().addAll(wrongEmailLabel, backLink);

        // ── Layout ───────────────────────────────────────────────────────────
        VBox formSection = new VBox(14);
        formSection.setAlignment(Pos.CENTER);
        formSection.setPadding(new Insets(0, 30, 0, 30));
        formSection.getChildren().addAll(codeBox, submitButton);

        mainContainer.getChildren().addAll(headerBox, formSection, resendBox, footerBox);

        VBox pageContent = new VBox(18, logoRow, mainContainer);
        pageContent.setAlignment(Pos.CENTER);
        setCenter(pageContent);
        Platform.runLater(() -> digitFields.get(0).requestFocus());
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

    private Timeline buildCountdownTimeline(AtomicInteger countdown, Label timerLabel, Hyperlink resendLink) {
        Timeline tl = new Timeline(
                new KeyFrame(Duration.seconds(1), e -> {
                    int remaining = countdown.decrementAndGet();
                    if (remaining <= 0) {
                        timerLabel.setText("");
                        resendLink.setDisable(false);
                    } else {
                        timerLabel.setText("(" + remaining + "s)");
                    }
                })
        );
        tl.setCycleCount(60);
        return tl;
    }

}
