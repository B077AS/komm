package komm.ui.emojis;

import io.github.b077as.emojifx.Emoji;
import io.github.b077as.emojifx.EmojiData;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Text;
import komm.App;
import komm.model.permissions.Permission;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.profile.UserProfilePopup;
import komm.websocket.messages.payloads.MessageReceivedPayload;
import lombok.Getter;
import lombok.Setter;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static komm.ui.chat.ChatSection.truncate;
import static komm.ui.emojis.EmojiMessageContent.H_PAD;

public class EmojiMessageItem extends HBox {

    private static final double AVATAR_SIZE = 38.0;
    private static final double AVATAR_RADIUS = AVATAR_SIZE / 2.0;
    private static final double LEFT_PAD = 16.0;
    private static final double RIGHT_PAD = 16.0;
    private static final double TOP_PAD = 6.0;
    private static final double BOTTOM_PAD = 6.0;
    private static final double AVATAR_RIGHT_GAP = 12.0;
    private static final double MINI_SIZE = 14.0;
    private static final double MINI_RADIUS = MINI_SIZE / 2.0;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static EmojiMessageItem stickyHovered = null;

    private final StackPane avatarContainer;
    private final Label nameLabel;
    private HBox actionBar;
    private boolean dmContext = false;

    @Getter
    @Setter
    private EmojiMessageContent bubble;
    @Getter
    private final MessageReceivedPayload payload;
    @Getter
    private final VBox contentCol;
    private Label editedLabel;
    private boolean hovered = false;

    public EmojiMessageItem(MessageReceivedPayload payload, boolean isOwnMessage) {
        super(0);
        setAlignment(Pos.TOP_LEFT);
        setPadding(new Insets(TOP_PAD, RIGHT_PAD, BOTTOM_PAD, LEFT_PAD));
        setFillHeight(false);

        this.payload = payload;

        UUID senderId = payload.getSenderId();

        // ── Avatar ────────────────────────────────────────────────────────────
        avatarContainer = buildAvatarPlaceholder();
        HBox.setMargin(avatarContainer, new Insets(2, AVATAR_RIGHT_GAP, 0, 0));

        // Left-click on avatar opens profile popup
        if (senderId != null) {
            avatarContainer.setStyle("-fx-cursor: hand;");
            avatarContainer.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    UUID serverId = dmContext ? null
                            : (App.getCachedServerPage() != null
                                    ? App.getCachedServerPage().getServer().getServerId() : null);
                    UserProfilePopup.getInstance().show(
                            App.getStackPane().getScene().getWindow(),
                            e.getScreenX(), e.getScreenY(), senderId, serverId, null);
                    e.consume();
                }
            });
        }

        // ── Outer right column ────────────────────────────────────────────────
        VBox outerCol = new VBox(2);
        outerCol.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(outerCol, Priority.ALWAYS);

        // ── Optional reply strip + connector lines ────────────────────────────
        if (payload.getRepliedToId() != null) {
            HBox replyStrip = buildReplyStrip(payload);
            VBox.setMargin(replyStrip, new Insets(0, 0, 4, AVATAR_SIZE + AVATAR_RIGHT_GAP));
            outerCol.getChildren().add(replyStrip);

            Line vertLine = new Line();
            Line horizLine = new Line();
            for (Line l : new Line[]{vertLine, horizLine}) {
                l.setStyle("-fx-stroke-width: 1.5; -fx-stroke: -color-accent-9;");
                l.setStrokeLineCap(StrokeLineCap.ROUND);
                l.setMouseTransparent(true);
            }

            Pane linePane = new Pane(vertLine, horizLine);
            linePane.setManaged(false);
            linePane.setMouseTransparent(true);
            linePane.setPickOnBounds(false);
            linePane.prefWidthProperty().bind(widthProperty());
            linePane.prefHeightProperty().bind(heightProperty());

            Node accentBar = replyStrip.getChildren().get(0);

            Runnable updateLines = () -> {
                if (getScene() == null) return;
                Bounds av = sceneToLocal(avatarContainer.localToScene(avatarContainer.getBoundsInLocal()));
                Bounds ab = sceneToLocal(accentBar.localToScene(accentBar.getBoundsInLocal()));

                double startX = av.getMinX() + av.getWidth() / 2.0;
                double startY = av.getMinY() - 4;
                double endX = ab.getMinX() - 4;
                double endY = ab.getMinY() + ab.getHeight() / 2.0;

                vertLine.setStartX(startX);
                vertLine.setStartY(startY);
                vertLine.setEndX(startX);
                vertLine.setEndY(endY);
                horizLine.setStartX(startX);
                horizLine.setStartY(endY);
                horizLine.setEndX(endX);
                horizLine.setEndY(endY);
            };

            avatarContainer.boundsInParentProperty().addListener((o, a, b) -> updateLines.run());
            replyStrip.boundsInParentProperty().addListener((o, a, b) -> updateLines.run());
            sceneProperty().addListener((o, a, scene) -> {
                if (scene != null) Platform.runLater(updateLines::run);
            });
            Platform.runLater(updateLines::run);
            getChildren().add(linePane);
        }

        // ── Name + timestamp header ───────────────────────────────────────────
        nameLabel = new Label("Unknown");
        nameLabel.setStyle(
                "-fx-font-weight: bold;" +
                        "-fx-font-size: 13px;" +
                        "-fx-cursor: hand;" +
                        (isOwnMessage
                                ? "-fx-text-fill: -color-accent-emphasis;"
                                : "-fx-text-fill: -color-fg-default;")
        );
        if (senderId != null) {
            nameLabel.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    UUID serverId = dmContext ? null
                            : (App.getCachedServerPage() != null
                                    ? App.getCachedServerPage().getServer().getServerId() : null);
                    UserProfilePopup.getInstance().show(
                            App.getStackPane().getScene().getWindow(),
                            e.getScreenX(), e.getScreenY(), senderId, serverId, null);
                    e.consume();
                }
            });
        }

        Label timeLabel = new Label(formatTime(payload.getSentAt()));
        timeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        editedLabel = new Label("(Edited)");
        editedLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-subtle;");
        editedLabel.setVisible(payload.isEdited());
        editedLabel.setManaged(payload.isEdited());

        HBox header = new HBox(8, nameLabel, timeLabel, editedLabel);
        header.setAlignment(Pos.BASELINE_LEFT);

        // ── Message bubble ────────────────────────────────────────────────────
        if (payload.getMessageType() == MessageReceivedPayload.MessageType.GIF) {
            // content holds the fullMp4Url — that's what the server persists and broadcasts.
            // Width/height are 0 for now (not stored); GifMessageCell uses fallback ratio.
            String gifUrl = payload.getContent();
            bubble = EmojiMessageContent.ofGif(gifUrl, 0, 0);
        } else if (payload.getMessageType() == MessageReceivedPayload.MessageType.CODE) {
            bubble = EmojiMessageContent.ofCode(
                    payload.getContent() == null ? "" : payload.getContent(),
                    payload.getCodeLanguage());
        } else {
            bubble = EmojiMessageContent.of(
                    payload.getContent() == null ? "" : payload.getContent());
        }
        VBox.setMargin(bubble, new Insets(0, 0, 0, -H_PAD));

        bubble.setDeleteVisibleSupplier(() -> isOwnMessage || App.getPermissionManager().has(Permission.DELETE_OTHERS_MSGS));
        bubble.setOnAllPopupsClosed(() -> {
            if (!isHover()) setHoverActive(false);
        });
        bubble.getBubbleStack().addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                if (stickyHovered != null && stickyHovered != this) {
                    stickyHovered.setHoverActive(false);
                    stickyHovered.hovered = false;
                    stickyHovered = null;
                }
                stickyHovered = this;
                setHoverActive(true);
                bubble.showContextMenuAt(e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });

        // ── Inner content column (name + bubble) ──────────────────────────────
        contentCol = new VBox(2);
        contentCol.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(contentCol, Priority.ALWAYS);
        contentCol.getChildren().addAll(header, bubble);

        // ── Row: avatar + content ─────────────────────────────────────────────
        HBox avatarRow = new HBox(0, avatarContainer, contentCol);
        avatarRow.setAlignment(Pos.TOP_LEFT);
        outerCol.getChildren().add(avatarRow);

        // ── Action bar overlaid on full item width, straddling the top border ─
        actionBar = buildMessageActionBar(isOwnMessage);
        actionBar.setOpacity(0);
        actionBar.setMouseTransparent(true);

        // itemStack covers the full width so TOP_RIGHT truly means top-right of the row
        StackPane itemStack = new StackPane(outerCol, actionBar);
        HBox.setHgrow(itemStack, Priority.ALWAYS);
        StackPane.setAlignment(actionBar, Pos.TOP_RIGHT);
        actionBar.setTranslateY(-14); // straddle: half of bar height above the top border
        actionBar.setTranslateX(-8);  // slight inset from the right edge

        // insert at 0 so any linePane added above stays rendered on top
        getChildren().add(0, itemStack);

        setStyle("-fx-background-color: transparent;");
        setOnMouseEntered(e -> setHoverActive(true));
        setOnMouseExited(e -> setHoverActive(false));

        // ── Right-click on the row (outside bubble) ───────────────────────────
        setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                // Always clear the previous sticky — right-clicking a new item closes
                // the old context menu anyway, so it should lose its hover.
                if (stickyHovered != null && stickyHovered != this) {
                    stickyHovered.setHoverActive(false);
                    stickyHovered.hovered = false;
                    stickyHovered = null;  // clear it now, don't wait for onAllPopupsClosed
                }
                stickyHovered = this;
                bubble.showContextMenuAt(e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });

        // ── Resolve username + avatar ─────────────────────────────────────────
        if (senderId != null) {
            AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(senderId);
            if (cached != null && cached.getChatImage() != null) {
                applyUser(cached);
            } else {
                App.getAvatarCache()
                        .resolve(senderId)
                        .thenAcceptAsync(user -> {
                            if (user != null) {
                                App.getAvatarCache().ensureImages(user);
                                Platform.runLater(() -> applyUser(user));
                            }
                        });
            }
        }
    }

    public static EmojiMessageItem of(MessageReceivedPayload payload, boolean isOwnMessage) {
        return new EmojiMessageItem(payload, isOwnMessage);
    }

    public static EmojiMessageItem ofDm(MessageReceivedPayload payload, boolean isOwnMessage) {
        EmojiMessageItem item = new EmojiMessageItem(payload, isOwnMessage);
        item.dmContext = true;
        return item;
    }

    private HBox buildReplyStrip(MessageReceivedPayload payload) {
        HBox strip = new HBox(4);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.setMaxWidth(Double.MAX_VALUE);

        Rectangle accentBar = new Rectangle(3, 22);
        accentBar.setArcWidth(3);
        accentBar.setArcHeight(3);
        accentBar.setStyle("-fx-fill: -color-accent-8;");
        HBox.setMargin(accentBar, new Insets(0, 2, 0, 0));

        StackPane miniAvatar = buildMiniAvatarPlaceholder();

        UUID replyToSenderIdForClick = payload.getReplyToSenderId();
        if (replyToSenderIdForClick != null) {
            miniAvatar.setStyle("-fx-cursor: hand;");
            miniAvatar.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    UUID serverId = dmContext ? null
                            : (App.getCachedServerPage() != null
                                    ? App.getCachedServerPage().getServer().getServerId() : null);
                    UserProfilePopup.getInstance().show(
                            App.getStackPane().getScene().getWindow(),
                            e.getScreenX(), e.getScreenY(), replyToSenderIdForClick, serverId, null);
                    e.consume();
                }
            });
        }

        Label replyName = new Label("Unknown");
        replyName.setStyle(
                "-fx-font-size: 12px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-cursor: hand;" +
                        "-fx-text-fill: -color-fg-default;" +
                        "-fx-opacity: 0.75;"
        );
        if (replyToSenderIdForClick != null) {
            replyName.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    UUID serverId = dmContext ? null
                            : (App.getCachedServerPage() != null
                                    ? App.getCachedServerPage().getServer().getServerId() : null);
                    UserProfilePopup.getInstance().show(
                            App.getStackPane().getScene().getWindow(),
                            e.getScreenX(), e.getScreenY(), replyToSenderIdForClick, serverId, null);
                    e.consume();
                }
            });
        }

        String preview = payload.getReplyToContent();
        if (preview == null || preview.isBlank()) {
            preview = replyPreviewPlaceholder(payload);
        } else {
            preview = preview.replaceAll("\\R", " ").strip();
            preview = truncate(preview, 80);
        }

        HBox textBox = new HBox();
        textBox.setAlignment(Pos.CENTER_LEFT);
        textBox.getChildren().addAll(TextUtils.convertToTextAndImageNodes(preview, 12));

        strip.getChildren().addAll(accentBar, miniAvatar, replyName, textBox);

        UUID replyToSenderId = payload.getReplyToSenderId();
        if (replyToSenderId != null) {
            AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(replyToSenderId);
            if (cached != null && cached.getMiniImage() != null) {
                if (cached.username() != null) replyName.setText(cached.username());
                applyMiniAvatar(miniAvatar, cached);
            } else {
                App.getAvatarCache()
                        .resolve(replyToSenderId)
                        .thenAcceptAsync(user -> {
                            if (user != null) {
                                App.getAvatarCache().ensureImages(user);
                                Platform.runLater(() -> {
                                    if (user.username() != null) replyName.setText(user.username());
                                    applyMiniAvatar(miniAvatar, user);
                                });
                            }
                        });
            }
        } else {
            replyName.setText("Unknown");
        }

        return strip;
    }

    private HBox buildMessageActionBar(boolean isOwnMessage) {
        HBox bar = new HBox(1);
        bar.setAlignment(Pos.CENTER);
        bar.setPadding(new Insets(2, 4, 2, 4));
        bar.setStyle(
                "-fx-background-color: -color-bg-default;" +
                        "-fx-border-color: -color-border-subtle;" +
                        "-fx-border-width: 1px;" +
                        "-fx-border-radius: 8px;" +
                        "-fx-background-radius: 8px;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 6, 0, 0, 2);"
        );
        // Makes sure it doesn't stretch the layout
        bar.setMaxHeight(Region.USE_PREF_SIZE);
        bar.setMaxWidth(Region.USE_PREF_SIZE);

        Emoji heart = EmojiData.emojiFromCodepoints("2764-FE0F").get();
        List<Node> heartNodes = TextUtils.convertToTextAndImageNodes(heart.character(), 14);
        Node heartGlyph = heartNodes.get(0);
        if (heartGlyph instanceof ImageView iv) {
            iv.setFitWidth(14);
            iv.setFitHeight(14);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
        } else if (heartGlyph instanceof Text t) {
            t.setStyle("-fx-font-size: 14px;");
        }
        heartGlyph.setMouseTransparent(true);

        Button heartBtn = buildActionButton(heartGlyph);
        heartBtn.setOnAction(e -> bubble.fireQuickReact(heart));

        FontIcon reactIcon = new FontIcon(MaterialDesignE.EMOTICON_OUTLINE);
        reactIcon.getStyleClass().add("custom-icon-15");

        Button reactBtn = buildActionButton(reactIcon);
        reactBtn.setOnMouseClicked(e -> {
            if (stickyHovered != null && stickyHovered != this) {
                stickyHovered.setHoverActive(false);
                stickyHovered.hovered = false;
                stickyHovered = null;
            }
            stickyHovered = this;
            setHoverActive(true);
            bubble.showReactionPickerAt(e.getScreenX(), e.getScreenY());
            e.consume();
        });

        FontIcon replyIcon = new FontIcon(MaterialDesignR.REPLY);
        replyIcon.getStyleClass().add("custom-icon-15");

        Button replyBtn = buildActionButton(replyIcon);
        replyBtn.setOnAction(e -> bubble.fireReply());

        bar.getChildren().addAll(heartBtn, reactBtn);

        if (isOwnMessage && payload.getMessageType() != MessageReceivedPayload.MessageType.GIF) {
            FontIcon editIcon = new FontIcon(MaterialDesignP.PENCIL_OUTLINE);
            editIcon.getStyleClass().add("custom-icon-15");
            Button editBtn = buildActionButton(editIcon);
            editBtn.setOnAction(e -> bubble.fireEdit());
            bar.getChildren().add(editBtn);
        }

        bar.getChildren().add(replyBtn);
        return bar;
    }

    private Button buildActionButton(Node graphic) {
        Button btn = new Button(null, graphic);
        btn.setFocusTraversable(false);
        btn.setMinSize(26, 24);
        btn.setMaxSize(26, 24);
        btn.setPrefSize(26, 24);
        btn.setPadding(new Insets(3));
        btn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-background-radius: 6px;" +
                        "-fx-cursor: hand;"
        );
        return btn;
    }

    private StackPane buildMiniAvatarPlaceholder() {
        StackPane pane = new StackPane();
        pane.setMinSize(MINI_SIZE, MINI_SIZE);
        pane.setMaxSize(MINI_SIZE, MINI_SIZE);
        pane.setPrefSize(MINI_SIZE, MINI_SIZE);

        Circle bg = new Circle(MINI_RADIUS);
        bg.setFill(AvatarColor.forNameJfx(null));
        bg.setMouseTransparent(true);

        Label lbl = new Label("?");
        lbl.setStyle("-fx-text-fill: white; -fx-font-size: 7px; -fx-font-weight: bold;");
        lbl.setMouseTransparent(true);

        pane.getChildren().addAll(bg, lbl);
        pane.setClip(new Circle(MINI_RADIUS, MINI_RADIUS, MINI_RADIUS));
        return pane;
    }

    private void applyMiniAvatar(StackPane pane, AvatarCache.CachedUser user) {
        Image image = user.getMiniImage();
        if (image != null) {
            ImageView iv = new ImageView(image);
            iv.setFitWidth(MINI_SIZE);
            iv.setFitHeight(MINI_SIZE);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            iv.setMouseTransparent(true);
            pane.getChildren().setAll(iv);
            pane.setClip(new Circle(MINI_RADIUS, MINI_RADIUS, MINI_RADIUS));
            return;
        }
        if (user.username() != null && !user.username().isBlank()) {
            pane.getChildren().stream()
                    .filter(n -> n instanceof Circle)
                    .map(n -> (Circle) n)
                    .findFirst()
                    .ifPresent(c -> c.setFill(AvatarColor.forNameJfx(user.username())));
            pane.getChildren().stream()
                    .filter(n -> n instanceof Label)
                    .map(n -> (Label) n)
                    .findFirst()
                    .ifPresent(lbl -> lbl.setText(
                            String.valueOf(user.username().charAt(0)).toUpperCase()));
        }
    }

    // ── Apply resolved user data (main avatar) ────────────────────────────────

    private void applyUser(AvatarCache.CachedUser user) {
        if (user.username() != null) nameLabel.setText(user.username());
        Image image = user.getChatImage();
        if (image != null) {
            applyAvatarImage(image);
            return;
        }
        if (user.username() != null && !user.username().isBlank()) {
            avatarContainer.getChildren().stream()
                    .filter(n -> n instanceof Circle)
                    .map(n -> (Circle) n)
                    .findFirst()
                    .ifPresent(c -> c.setFill(AvatarColor.forNameJfx(user.username())));
            avatarContainer.getChildren().stream()
                    .filter(n -> n instanceof Label)
                    .map(n -> (Label) n)
                    .findFirst()
                    .ifPresent(lbl -> lbl.setText(
                            String.valueOf(user.username().charAt(0)).toUpperCase()));
        }
    }

    private void applyAvatarImage(Image image) {
        ImageView iv = new ImageView(image);
        iv.setFitWidth(AVATAR_SIZE);
        iv.setFitHeight(AVATAR_SIZE);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        iv.setMouseTransparent(true);
        avatarContainer.getChildren().setAll(iv);
        avatarContainer.setClip(new Circle(AVATAR_RADIUS, AVATAR_RADIUS, AVATAR_RADIUS));
    }

    // ── Full avatar placeholder ───────────────────────────────────────────────

    private StackPane buildAvatarPlaceholder() {
        StackPane pane = new StackPane();
        pane.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        pane.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
        pane.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);

        Circle bg = new Circle(AVATAR_RADIUS);
        bg.setFill(AvatarColor.forNameJfx(null));
        bg.setMouseTransparent(true);

        Label lbl = new Label("?");
        lbl.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold;");
        lbl.setMouseTransparent(true);

        pane.getChildren().addAll(bg, lbl);
        pane.setClip(new Circle(AVATAR_RADIUS, AVATAR_RADIUS, AVATAR_RADIUS));
        return pane;
    }

    private static String replyPreviewPlaceholder(MessageReceivedPayload payload) {
        MessageReceivedPayload.MessageType type = payload.getReplyToMessageType();
        if (type == MessageReceivedPayload.MessageType.GIF) return "GIF";
        if (type == MessageReceivedPayload.MessageType.CODE) return "Code snippet";
        if (payload.isReplyToHasAttachments()) {
            String name = payload.getReplyToFileName();
            if (name != null && !name.isBlank()) return name;
            String mime = payload.getReplyToFileType();
            return (mime != null && mime.startsWith("image/")) ? "Image" : "Attachment";
        }
        return "Message deleted";
    }

    private static String formatTime(LocalDateTime dt) {
        if (dt == null) return "";
        LocalDateTime now = LocalDateTime.now();
        if (dt.toLocalDate().equals(now.toLocalDate()))
            return "Today at " + dt.format(TIME_FMT);
        return dt.format(DATE_TIME_FMT);
    }

    public void setEdited(boolean edited) {
        editedLabel.setVisible(edited);
        editedLabel.setManaged(edited);
    }

    public void onAllPopupsClosed() {
        if (stickyHovered == this) stickyHovered = null;
        if (!hovered) setHoverActive(false);
    }

    private static EmojiMessageItem activeItem = null;

    void setHoverActive(boolean active) {
        if (active) {
            // Clear previous non-sticky activeItem
            if (activeItem != null && activeItem != this) {
                activeItem.hovered = false;
                activeItem.setStyle("-fx-background-color: transparent;");
                activeItem.actionBar.setMouseTransparent(true);
                activeItem.actionBar.setOpacity(0);
            }
            activeItem = this;
            hovered = true;
            setStyle("-fx-background-color: rgba(255,255,255,0.03);");
            actionBar.setMouseTransparent(false);
            actionBar.setOpacity(1.0);

            // Re-apply highlight to sticky if we just wiped it (it's a different item)
            if (stickyHovered != null && stickyHovered != this && stickyHovered.bubble.isAnyPopupOpen()) {
                stickyHovered.setStyle("-fx-background-color: rgba(255,255,255,0.03);");
                stickyHovered.actionBar.setMouseTransparent(false);
                stickyHovered.actionBar.setOpacity(1.0);
            }

        } else {
            // Never un-highlight sticky while its popup is open
            if (stickyHovered == this && bubble.isAnyPopupOpen()) return;

            hovered = false;
            setStyle("-fx-background-color: transparent;");
            actionBar.setMouseTransparent(true);
            actionBar.setOpacity(0);
            if (activeItem == this) activeItem = null;
        }
    }

    /**
     * Finds the content column (VBox containing name header + bubble) inside an EmojiMessageItem.
     */
    private VBox findContentColumn(EmojiMessageItem item) {
        // The structure is: HBox (item) -> StackPane (itemStack) -> VBox (outerCol) -> HBox (avatarRow) -> VBox (contentCol)
        if (item.getChildren().isEmpty()) return null;

        Node firstChild = item.getChildren().get(0);
        if (!(firstChild instanceof StackPane stackPane)) return null;
        if (stackPane.getChildren().isEmpty()) return null;

        Node outerCol = stackPane.getChildren().get(0);
        if (!(outerCol instanceof VBox vbox)) return null;
        if (vbox.getChildren().isEmpty()) return null;

        // Find the avatarRow (HBox containing avatar + contentCol)
        for (Node child : vbox.getChildren()) {
            if (child instanceof HBox avatarRow) {
                // contentCol is the second child of avatarRow (after avatarContainer)
                if (avatarRow.getChildren().size() >= 2) {
                    Node contentCol = avatarRow.getChildren().get(1);
                    if (contentCol instanceof VBox) {
                        return (VBox) contentCol;
                    }
                }
            }
        }
        return null;
    }
}