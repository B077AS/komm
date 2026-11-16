package komm.ui.poll;

import atlantafx.base.theme.Styles;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import komm.App;
import komm.model.permissions.Permission;
import komm.ui.mention.Mentions;
import komm.ui.utils.IconColorUtil;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.MessageReceivedPayload;
import komm.websocket.messages.payloads.PollClosePayload;
import komm.websocket.messages.payloads.PollSummary;
import komm.websocket.messages.payloads.PollVotePayload;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Chat bubble body for a {@code POLL} message: question, one bar per option (share of voters),
 * vote count / time left, and "End poll" for the author or moderators.
 *
 * <p>The view renders from the message's {@link PollSummary}, which {@code ChatSection} keeps
 * current from {@code CHANNEL_POLL_UPDATED}; on-screen views get {@link #update} directly and
 * cells built later read the updated payload. Clicking an option sends the user's full new
 * selection ({@code CHANNEL_POLL_VOTE}); the tick marks change immediately, the counts when the
 * server's update arrives.
 */
public class PollMessageView extends VBox {

    private static final double MAX_WIDTH = 460;

    private final MessageReceivedPayload message;
    private final VBox optionsBox = new VBox(6);
    private final Label hintLabel = new Label();
    private final Label statusLabel = new Label();
    private final Button endButton = new Button("End poll");
    private final Timeline clock = new Timeline(new KeyFrame(Duration.seconds(30), e -> refreshStatus()));

    public PollMessageView(MessageReceivedPayload message) {
        this.message = message;

        getStyleClass().add("poll-message");
        setSpacing(10);
        setPadding(new Insets(12, 14, 12, 14));
        setMaxWidth(MAX_WIDTH);
        setStyle("-fx-background-color: -color-bg-subtle;"
                + "-fx-background-radius: 6px;"
                + "-fx-border-color: -color-border-default;"
                + "-fx-border-radius: 6px;"
                + "-fx-border-width: 1px;");

        Label question = new Label(message.getContent() == null ? "" : Mentions.toPlain(message.getContent()));
        question.setWrapText(true);
        question.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        question.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(question, Priority.ALWAYS);
        question.setMaxWidth(Double.MAX_VALUE);

        HBox header = new HBox(8, IconColorUtil.colored(MaterialDesignP.POLL, "-color-accent-emphasis", 18), question);
        header.setAlignment(Pos.TOP_LEFT);

        hintLabel.setStyle("-fx-font-size: 11.5px; -fx-text-fill: -color-fg-muted;");

        statusLabel.setStyle("-fx-font-size: 11.5px; -fx-text-fill: -color-fg-muted;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        endButton.getStyleClass().addAll(Styles.FLAT, Styles.SMALL);
        endButton.setFocusTraversable(false);
        endButton.setOnAction(e -> sendClose());
        HBox footer = new HBox(8, statusLabel, spacer, endButton);
        footer.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(header, hintLabel, optionsBox, footer);

        // Tick the "ends in" text only while visible.
        clock.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) clock.stop();
            else if (poll() != null && !poll().isEnded()) clock.play();
        });

        rebuild();
    }

    /** Applies new server state (already stored in the message payload by the caller). */
    public void update(PollSummary summary) {
        message.setPoll(summary);
        rebuild();
    }

    private PollSummary poll() {
        return message.getPoll();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void rebuild() {
        PollSummary p = poll();
        optionsBox.getChildren().clear();
        if (p == null || p.getOptions() == null) {
            hintLabel.setText("Poll unavailable");
            statusLabel.setText("");
            endButton.setVisible(false);
            endButton.setManaged(false);
            return;
        }

        boolean ended = p.isEnded();
        Set<UUID> mine = p.getMyOptionIds() == null ? Set.of() : Set.copyOf(p.getMyOptionIds());
        int maxVotes = p.getOptions().stream().mapToInt(PollSummary.Option::getVotes).max().orElse(0);

        for (PollSummary.Option option : p.getOptions()) {
            optionsBox.getChildren().add(buildOptionRow(p, option, mine.contains(option.getOptionId()),
                    ended, ended && maxVotes > 0 && option.getVotes() == maxVotes));
        }

        String kind = p.isAllowMultiple() ? "Select one or more answers" : "Select one answer";
        hintLabel.setText(ended ? (p.isAnonymous() ? "Final results · anonymous" : "Final results")
                : kind + (p.isAnonymous() ? " · anonymous" : ""));

        boolean canEnd = !ended && canEnd();
        endButton.setVisible(canEnd);
        endButton.setManaged(canEnd);

        refreshStatus();
        if (ended) clock.stop();
        else if (getScene() != null) clock.play();
    }

    private Region buildOptionRow(PollSummary p, PollSummary.Option option, boolean selected,
                                  boolean ended, boolean winner) {
        int total = p.getTotalVoters();
        double share = total == 0 ? 0 : (double) option.getVotes() / total;

        Ikon indicatorIcon = p.isAllowMultiple()
                ? (selected ? MaterialDesignC.CHECKBOX_MARKED : MaterialDesignC.CHECKBOX_BLANK_OUTLINE)
                : (selected ? MaterialDesignR.RADIOBOX_MARKED : MaterialDesignR.RADIOBOX_BLANK);
        var indicator = IconColorUtil.colored(indicatorIcon,
                selected ? "-color-accent-emphasis" : "-color-fg-muted", 16);

        Label text = new Label(option.getText());
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        text.setMaxWidth(Double.MAX_VALUE);
        text.setStyle(winner || selected ? "-fx-font-weight: bold;" : "");
        HBox.setHgrow(text, Priority.ALWAYS);

        Label result = new Label(Math.round(share * 100) + "%  ·  " + option.getVotes());
        result.setMinWidth(Region.USE_PREF_SIZE);
        result.setStyle("-fx-font-size: 11.5px; -fx-text-fill: -color-fg-muted;");

        HBox content = new HBox(8, indicator, text, result);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(7, 10, 7, 10));

        // Share-of-voters bar behind the content.
        Region fill = new Region();
        fill.setMouseTransparent(true);
        fill.setStyle("-fx-background-color: " + (selected || winner ? "-color-accent-subtle" : "-color-neutral-muted")
                + "; -fx-background-radius: 4px;");
        StackPane row = new StackPane(fill, content);
        row.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        DoubleProperty fraction = new SimpleDoubleProperty(share);
        fill.maxWidthProperty().bind(row.widthProperty().multiply(fraction));
        fill.prefWidthProperty().bind(fill.maxWidthProperty());
        fill.maxHeightProperty().bind(row.heightProperty());

        String border = selected ? "-color-accent-emphasis" : "-color-border-muted";
        String baseStyle = "-fx-background-color: -color-bg-default; -fx-background-radius: 4px;"
                + "-fx-border-color: " + border + "; -fx-border-radius: 4px; -fx-border-width: 1px;";
        row.setStyle(baseStyle);

        if (!ended) {
            row.setCursor(javafx.scene.Cursor.HAND);
            row.setOnMouseEntered(e -> row.setStyle(baseStyle.replace(border, "-color-accent-muted")));
            row.setOnMouseExited(e -> row.setStyle(baseStyle));
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) toggle(option.getOptionId());
            });
        }

        if (!p.isAnonymous() && option.getVoterIds() != null && !option.getVoterIds().isEmpty()) {
            Tooltip tip = new Tooltip();
            tip.setShowDelay(Duration.millis(400));
            List<UUID> voterIds = option.getVoterIds();
            tip.setOnShowing(e -> {
                tip.setText(voterNames(voterIds));
                // Voters who never posted may not be cached yet: resolve, then refresh the text.
                App.getAvatarCache().resolveAll(voterIds.subList(0, Math.min(15, voterIds.size())))
                        .thenRun(() -> javafx.application.Platform.runLater(() -> {
                            if (tip.isShowing()) tip.setText(voterNames(voterIds));
                        }));
            });
            Tooltip.install(row, tip);
        }
        return row;
    }

    private void refreshStatus() {
        PollSummary p = poll();
        if (p == null) return;
        int voters = p.getTotalVoters();
        String votes = voters == 1 ? "1 vote" : voters + " votes";
        String time;
        if (p.isEnded()) {
            time = "Poll ended";
            clock.stop();
            if (endButton.isVisible()) {
                endButton.setVisible(false);
                endButton.setManaged(false);
            }
        } else if (p.getClosesAt() != null) {
            time = "Ends in " + remaining(LocalDateTime.now(), p.getClosesAt());
        } else {
            time = "No end date";
        }
        statusLabel.setText(votes + "  ·  " + time);
    }

    private static String remaining(LocalDateTime now, LocalDateTime end) {
        long minutes = Math.max(1, java.time.Duration.between(now, end).toMinutes());
        if (minutes < 60) return minutes + (minutes == 1 ? " minute" : " minutes");
        long hours = minutes / 60;
        if (hours < 48) return hours + (hours == 1 ? " hour" : " hours");
        long days = hours / 24;
        return days + " days";
    }

    private static String voterNames(List<UUID> voterIds) {
        List<String> names = new ArrayList<>();
        int shown = 0;
        for (UUID id : voterIds) {
            if (shown == 15) break;
            var cached = App.getAvatarCache().getIfPresent(id);
            names.add(cached != null && cached.username() != null ? cached.username() : "Unknown user");
            shown++;
        }
        String text = String.join(", ", names);
        if (voterIds.size() > shown) text += " and " + (voterIds.size() - shown) + " more";
        return text;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private boolean canEnd() {
        UUID me = App.getUser() != null ? App.getUser().getUserId() : null;
        if (me != null && me.equals(message.getSenderId())) return true;
        return message.getChannelId() != null
                && App.getPermissionManager().hasInChannel(message.getChannelId(), Permission.DELETE_OTHERS_MSGS);
    }

    private void toggle(UUID optionId) {
        PollSummary p = poll();
        if (p == null || p.isEnded()) return;
        Set<UUID> next = new LinkedHashSet<>(p.getMyOptionIds() == null ? List.of() : p.getMyOptionIds());
        if (next.contains(optionId)) {
            next.remove(optionId);           // clicking your own choice takes it back
        } else {
            if (!p.isAllowMultiple()) next.clear();
            next.add(optionId);
        }
        List<UUID> chosen = List.copyOf(next);
        p.setMyOptionIds(chosen);            // optimistic tick; counts follow from the server
        rebuild();
        Thread.ofVirtual().start(() -> App.getServices().installation().getWsClient()
                .send(WsMessageType.CHANNEL_POLL_VOTE, PollVotePayload.builder()
                        .messageId(message.getMessageId())
                        .optionIds(chosen)
                        .build()));
    }

    private void sendClose() {
        endButton.setDisable(true);
        Thread.ofVirtual().start(() -> App.getServices().installation().getWsClient()
                .send(WsMessageType.CHANNEL_POLL_CLOSE, PollClosePayload.builder()
                        .messageId(message.getMessageId())
                        .build()));
    }
}
