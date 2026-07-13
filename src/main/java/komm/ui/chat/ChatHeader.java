package komm.ui.chat;

import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import komm.model.dto.summary.ChannelSummary;
import lombok.Setter;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

public class ChatHeader extends HBox {

    // ── Mode ──────────────────────────────────────────────────────────────────

    public enum Mode {CHAT, STREAM}

    // ── Callbacks ─────────────────────────────────────────────────────────────

    @Setter
    private Runnable onFriendsToggle;
    @Setter
    private Runnable onChatToggle;

    // ── Children ──────────────────────────────────────────────────────────────

    private final HBox leftPill;
    private final Label titleLabel;

    /** Cached pill nodes rebuilt each time a channel is set. */
    private FontIcon channelIconNode;
    private Label typeDivider;
    private Label typeTag;

    private final HBox controls;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ChatHeader() {
        setMinHeight(48);
        setMaxHeight(48);
        setPrefHeight(48);
        setAlignment(Pos.CENTER_LEFT);
        setStyle("""
                -fx-background-color: -color-bg-subtle;
                -fx-border-color: transparent transparent -color-border-muted transparent;
                -fx-border-width: 0 0 1px 0;
                """);

        titleLabel = new Label();
        titleLabel.setId("chatChannelName");
        titleLabel.setStyle("""
                -fx-font-weight: bold;
                -fx-font-size: 15px;
                -fx-text-fill: -color-fg-default;
                """);

        leftPill = new HBox(7);
        leftPill.setAlignment(Pos.CENTER_LEFT);
        leftPill.setPadding(new Insets(0, 0, 0, 16));
        HBox.setHgrow(leftPill, Priority.ALWAYS);

        controls = new HBox(4);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(0, 12, 0, 0));
        controls.setMinWidth(Region.USE_PREF_SIZE);
        controls.setMaxWidth(Region.USE_PREF_SIZE);

        getChildren().addAll(leftPill, controls);

        // Default to chat mode with no channel selected (empty header)
        setMode(Mode.CHAT, null);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setMode(Mode mode, String streamTitle) {
        controls.getChildren().clear();

        if (mode == Mode.CHAT) {
            // Restore the channel pill only if a channel is already selected
            if (titleLabel.getText() != null && !titleLabel.getText().isBlank()) {
                rebuildChannelPill();
            } else {
                leftPill.getChildren().clear();
            }

            Button friendsBtn = new Button(null, new FontIcon(MaterialDesignF.FORMAT_LIST_BULLETED));
            friendsBtn.setFocusTraversable(false);
            friendsBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
            friendsBtn.setOnAction(e -> { if (onFriendsToggle != null) onFriendsToggle.run(); });
            controls.getChildren().add(friendsBtn);

        } else {
            // Stream mode: keep the same channel pill
            rebuildChannelPill();

            Button chatBtn = new Button(null, new FontIcon(Feather.MESSAGE_CIRCLE));
            chatBtn.setFocusTraversable(false);
            chatBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
            chatBtn.setOnAction(e -> { if (onChatToggle != null) onChatToggle.run(); });

            Region vDivider = new Region();
            vDivider.setStyle("-fx-background-color: -color-border-muted;");
            vDivider.setMinSize(1, 16);
            vDivider.setMaxSize(1, 16);
            vDivider.setPrefSize(1, 16);
            HBox.setMargin(vDivider, new Insets(0, 4, 0, 4));

            Button friendsBtn = new Button(null, new FontIcon(MaterialDesignF.FORMAT_LIST_BULLETED));
            friendsBtn.setFocusTraversable(false);
            friendsBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
            friendsBtn.setOnAction(e -> { if (onFriendsToggle != null) onFriendsToggle.run(); });

            controls.getChildren().addAll(chatBtn, vDivider, friendsBtn);
        }
    }

    public void setChannel(ChannelSummary channel) {
        if (channel == null) {
            titleLabel.setText("");
            channelIconNode = null;
            typeDivider = null;
            typeTag = null;
            leftPill.getChildren().clear();
            return;
        }

        boolean isVoice = channel.getChannelType() == ChannelSummary.ChannelType.VOICE;

        channelIconNode = resolveChannelIcon(channel, isVoice);

        typeDivider = new Label("|");
        typeDivider.setStyle("-fx-font-size: 9px; -fx-text-fill: -color-accent-emphasis; -fx-padding: 2 0 0 0;");

        typeTag = new Label(isVoice ? "voice" : "text");
        typeTag.setStyle("-fx-font-size: 9px; -fx-text-fill: -color-fg-subtle; -fx-padding: 2 0 0 0;");

        titleLabel.setText(channel.getChannelName());
        rebuildChannelPill();
    }

    public void setStreamTitle(String title) {
        titleLabel.setText(title != null ? title : "Stream");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void rebuildChannelPill() {
        if (channelIconNode != null && typeDivider != null && typeTag != null) {
            leftPill.getChildren().setAll(channelIconNode, titleLabel, typeDivider, typeTag);
        } else {
            leftPill.getChildren().setAll(titleLabel);
        }
    }

    private static FontIcon resolveChannelIcon(ChannelSummary ch, boolean isVoice) {
        if (ch.getIcon() != null && !ch.getIcon().isBlank()) {
            try {
                return new FontIcon(ch.getIcon());
            } catch (Exception ignored) {
            }
        }
        return isVoice ? new FontIcon(MaterialDesignM.MICROPHONE) : new FontIcon(Feather.HASH);
    }
}
