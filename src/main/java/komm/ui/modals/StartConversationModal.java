package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.ImagePattern;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import komm.App;
import komm.model.dto.summary.FriendSummary;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import komm.model.dto.summary.UserSummary;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

@Slf4j
public class StartConversationModal extends VBox {

    private static final int PAGE_SIZE = 15;
    private static final double PREFETCH_THRESHOLD = 0.85;

    private final BiConsumer<UUID, String> onConversationSelected;

    private final List<FriendSummary> allFriends = new ArrayList<>();
    private final List<FriendSummary> filtered = new ArrayList<>();
    private int loadedCount = 0;
    private boolean allLoaded = false;
    private boolean loading = false;

    private UUID selectedPartnerId;
    private String selectedPartnerUsername;

    private final VBox listContent = new VBox(2);
    private ScrollPane scroll;
    private Button openBtn;
    private TextField filterField;
    private ChangeListener<Number> scrollListener;

    private final Service<List<FriendSummary>> loadService = new Service<>() {
        @Override
        protected Task<List<FriendSummary>> createTask() {
            return new Task<>() {
                @Override
                protected List<FriendSummary> call() throws Exception {
                    List<FriendSummary> friends = App.getServices().hub().getFriendService().getFriends();
                    if (friends != null && !friends.isEmpty()) {
                        UUID myId = App.getUser() != null ? App.getUser().getUserId() : null;
                        List<UUID> ids = friends.stream()
                                .map(fs -> myId != null && myId.equals(fs.getRequester())
                                        ? fs.getAddressee() : fs.getRequester())
                                .filter(id -> id != null)
                                .distinct()
                                .toList();
                        App.getAvatarCache().resolveAll(ids).join();
                    }
                    return friends;
                }
            };
        }
    };

    public StartConversationModal(BiConsumer<UUID, String> onConversationSelected) {
        this.onConversationSelected = onConversationSelected;

        getStyleClass().add("custom-modal");
        setMinSize(420, 540);
        setMaxSize(420, 540);
        setPrefSize(420, 540);

        getChildren().addAll(buildHeader(), buildBody(), buildFooter());

        fetchFriends();
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("New Message");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label subtitle = new Label("Select a friend to start chatting");
        subtitle.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeBtn.setFocusTraversable(false);
        closeBtn.setOnAction(e -> App.closeModal());

        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 12, 14, 20));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");
        header.getChildren().addAll(new VBox(2, title, subtitle), spacer, closeBtn);
        return header;
    }

    // ── Body ──────────────────────────────────────────────────────────────────

    private VBox buildBody() {
        // ── Filter field ──────────────────────────────────────────────────────
        filterField = new TextField();
        filterField.setPromptText("Search friends…");
        filterField.getStyleClass().add(Styles.SMALL);
        filterField.setMaxWidth(Double.MAX_VALUE);
        filterField.setStyle("-fx-background-radius: 20px; -fx-padding: 5px 12px; -fx-font-size: 12px;");
        HBox.setHgrow(filterField, Priority.ALWAYS);

        HBox filterRow = new HBox(8, filterField);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow.setPadding(new Insets(10, 16, 8, 16));

        filterField.textProperty().addListener((obs, old, val) -> onFilterChanged(val));

        // ── Friend list ───────────────────────────────────────────────────────
        listContent.setFillWidth(true);
        listContent.setPadding(new Insets(0, 8, 8, 8));

        scroll = new ScrollPane(listContent);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        scrollListener = (obs, oldVal, newVal) -> {
            if (!allLoaded && !loading && newVal.doubleValue() >= PREFETCH_THRESHOLD)
                loadNextPage();
        };
        scroll.vvalueProperty().addListener(scrollListener);

        VBox body = new VBox(filterRow, scroll);
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private HBox buildFooter() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add(Styles.SMALL);
        cancelBtn.setFocusTraversable(false);
        cancelBtn.setOnAction(e -> App.closeModal());

        openBtn = new Button("Open Chat");
        openBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        openBtn.setFocusTraversable(false);
        openBtn.setDisable(true);
        openBtn.setOnAction(e -> handleOpen());

        HBox footer = new HBox(8, spacer, cancelBtn, openBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 16, 14, 16));
        footer.setStyle("-fx-border-color: -color-border-default transparent transparent transparent; -fx-border-width: 1 0 0 0;");
        return footer;
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void fetchFriends() {
        loading = true;
        listContent.getChildren().setAll(buildLoadingChip());

        loadService.setOnSucceeded(e -> {
            allFriends.clear();
            allFriends.addAll(loadService.getValue());
            applyFilter(filterField.getText());
            loading = false;
        });
        loadService.setOnFailed(e -> {
            log.error("Failed to load friends: {}", loadService.getException().getMessage());
            loading = false;
            listContent.getChildren().setAll(buildEmptyState("Failed to load friends"));
        });
        loadService.start();
    }

    private void onFilterChanged(String query) {
        applyFilter(query);
    }

    private void applyFilter(String query) {
        String q = (query == null ? "" : query.trim().toLowerCase());
        filtered.clear();
        loadedCount = 0;
        allLoaded = false;

        for (FriendSummary fs : allFriends) {
            UUID otherId = resolveOtherId(fs);
            if (otherId == null) continue;
            // Match by cached username if available
            AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(otherId);
            String username = cached != null ? cached.username() : null;
            if (!q.isEmpty() && username != null && !username.toLowerCase().contains(q)) continue;
            filtered.add(fs);
        }

        listContent.getChildren().clear();
        if (filtered.isEmpty() && allFriends.isEmpty()) {
            listContent.getChildren().add(buildEmptyState("No friends yet"));
        } else if (filtered.isEmpty()) {
            listContent.getChildren().add(buildEmptyState("No results for \"" + (query == null ? "" : query.trim()) + "\""));
        } else {
            loadNextPage();
        }
    }

    private void loadNextPage() {
        if (allLoaded) return;
        loading = true;

        int from = loadedCount;
        int to = Math.min(from + PAGE_SIZE, filtered.size());
        List<FriendSummary> batch = filtered.subList(from, to);
        loadedCount = to;

        if (loadedCount >= filtered.size()) allLoaded = true;

        for (FriendSummary fs : batch) {
            UUID otherId = resolveOtherId(fs);
            if (otherId == null) continue;
            listContent.getChildren().add(buildFriendRow(fs, otherId));
        }
        loading = false;
    }

    // ── Row builder ───────────────────────────────────────────────────────────

    private VBox buildFriendRow(FriendSummary fs, UUID partnerId) {
        // ── Avatar + status dot ───────────────────────────────────────────────
        StackPane avatarBg = buildAvatarBg(34);

        Circle statusDot = new Circle(4);
        statusDot.getStyleClass().addAll("status-dot", UserStatus.OFFLINE.getCssClass());
        statusDot.setStyle("-fx-stroke: -color-bg-default; -fx-stroke-width: 2;");

        StackPane avatarStack = new StackPane(avatarBg, statusDot);
        avatarStack.setMinSize(36, 36);
        avatarStack.setMaxSize(36, 36);
        avatarStack.setAlignment(Pos.CENTER);
        StackPane.setAlignment(statusDot, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(statusDot, new Insets(0, 0, 1, 0));

        // ── Name + status label ───────────────────────────────────────────────
        Label nameLbl = new Label("…");
        nameLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        Label statusLbl = new Label(UserStatus.OFFLINE.getValue());
        statusLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        VBox textBox = new VBox(1, nameLbl, statusLbl);
        textBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        // ── Row ───────────────────────────────────────────────────────────────
        HBox inner = new HBox(10, avatarStack, textBox);
        inner.setAlignment(Pos.CENTER_LEFT);
        inner.setPadding(new Insets(8, 10, 8, 10));

        VBox row = new VBox(inner);
        row.setFillWidth(true);
        row.setUserData(partnerId);
        applyRowStyle(row, false);

        row.setOnMouseEntered(e -> {
            if (!partnerId.equals(selectedPartnerId)) applyRowStyle(row, true);
        });
        row.setOnMouseExited(e -> {
            if (!partnerId.equals(selectedPartnerId)) applyRowStyle(row, false);
        });
        row.setOnMouseClicked(e -> selectRow(row, partnerId, nameLbl.getText()));

        // ── Resolve avatar + status async ─────────────────────────────────────
        AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(partnerId);
        if (cached != null) {
            nameLbl.setText(cached.username());
            fillAvatarBg(avatarBg, cached);
        }

        App.getAvatarCache().resolve(partnerId).thenAccept(cu -> {
            if (cu == null) return;
            Platform.runLater(() -> {
                nameLbl.setText(cu.username());
                fillAvatarBg(avatarBg, cu);
                // Re-apply selection name if this is the selected row
                if (partnerId.equals(selectedPartnerId)) selectedPartnerUsername = cu.username();
            });
        });

        Service<UserSummary> statusSvc = new Service<>() {
            @Override
            protected Task<UserSummary> createTask() {
                return new Task<>() {
                    @Override
                    protected UserSummary call() throws Exception {
                        return App.getServices().hub().getUserService().getUserSummary(partnerId);
                    }
                };
            }
        };
        statusSvc.setOnSucceeded(e -> {
            UserSummary summary = statusSvc.getValue();
            if (summary == null || summary.getStatus() == null) return;
            UserStatus st = summary.getStatus();
            for (UserStatus s : UserStatus.values()) statusDot.getStyleClass().remove(s.getCssClass());
            statusDot.getStyleClass().add(st.getCssClass());
            statusDot.setStyle("-fx-stroke: -color-bg-default; -fx-stroke-width: 2;");
            statusLbl.setText(st.getValue());
        });
        statusSvc.start();

        return row;
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    private void selectRow(VBox row, UUID partnerId, String username) {
        if (partnerId.equals(selectedPartnerId)) {
            // Deselect
            selectedPartnerId = null;
            selectedPartnerUsername = null;
            applyRowStyle(row, false);
            openBtn.setDisable(true);
        } else {
            // Deselect previous
            listContent.getChildren().forEach(n -> {
                if (n instanceof VBox r && r.getUserData() instanceof UUID id
                        && id.equals(selectedPartnerId)) {
                    applyRowStyle(r, false);
                }
            });
            selectedPartnerId = partnerId;
            selectedPartnerUsername = username;
            applyRowStyle(row, true);
            openBtn.setDisable(false);
        }
    }

    private void applyRowStyle(VBox row, boolean highlighted) {
        boolean isSelected = row.getUserData() instanceof UUID id && id.equals(selectedPartnerId);
        if (isSelected) {
            row.setStyle("-fx-background-color: -color-accent-subtle; -fx-background-radius: 8px; -fx-cursor: hand;");
        } else if (highlighted) {
            row.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 8px; -fx-cursor: hand;");
        } else {
            row.setStyle("-fx-background-color: transparent; -fx-background-radius: 8px; -fx-cursor: hand;");
        }
    }

    // ── Open ──────────────────────────────────────────────────────────────────

    private void handleOpen() {
        if (selectedPartnerId == null) return;
        UUID partnerId = selectedPartnerId;
        String username = selectedPartnerUsername != null ? selectedPartnerUsername : "…";
        App.closeModal();
        if (onConversationSelected != null) onConversationSelected.accept(partnerId, username);
    }

    // ── Avatar helpers ────────────────────────────────────────────────────────

    private StackPane buildAvatarBg(double size) {
        StackPane bg = new StackPane();
        bg.setPrefSize(size, size);
        bg.setMinSize(size, size);
        bg.setMaxSize(size, size);
        bg.setStyle("-fx-background-color: " + AvatarColor.forName(null) + ";" +
                "-fx-background-radius: " + (size / 2) + "px;");

        Label letter = new Label("?");
        letter.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: white;");
        letter.setMouseTransparent(true);
        letter.setUserData("letter");
        bg.getChildren().add(letter);
        bg.setAlignment(Pos.CENTER);
        return bg;
    }

    private void fillAvatarBg(StackPane bg, AvatarCache.CachedUser cu) {
        Label letter = bg.getChildren().stream()
                .filter(n -> n instanceof Label && "letter".equals(n.getUserData()))
                .map(n -> (Label) n)
                .findFirst().orElse(null);

        if (cu.avatar() != null && cu.avatar().length > 0) {
            try {
                double size = bg.getPrefWidth();
                Image img = new Image(new ByteArrayInputStream(cu.avatar()), size, size, true, true);
                if (!img.isError()) {
                    Circle clip = new Circle(size / 2, size / 2, size / 2);
                    // Use an imageCircle overlay like DmChatHeader
                    Circle imgCircle = new Circle(size / 2);
                    imgCircle.setFill(new ImagePattern(img));
                    if (letter != null) letter.setVisible(false);
                    // Remove any old image circle
                    bg.getChildren().removeIf(n -> n instanceof Circle);
                    bg.getChildren().add(imgCircle);
                    bg.setStyle("-fx-background-color: transparent; -fx-background-radius: " + (size / 2) + "px;");
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        // Fallback: initials
        bg.getChildren().removeIf(n -> n instanceof Circle);
        if (letter != null) {
            String l = (cu.username() != null && !cu.username().isEmpty())
                    ? String.valueOf(cu.username().charAt(0)).toUpperCase() : "?";
            letter.setText(l);
            letter.setVisible(true);
        }
        double sz = bg.getPrefWidth();
        bg.setStyle("-fx-background-color: " + AvatarColor.forName(cu.username()) + ";" +
                "-fx-background-radius: " + (sz / 2) + "px;");
    }

    // ── Empty / loading states ────────────────────────────────────────────────

    private Label buildLoadingChip() {
        Label chip = new Label("Loading…");
        chip.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;" +
                "-fx-padding: 4 12 4 12; -fx-background-color: -color-bg-subtle;" +
                "-fx-background-radius: 10px;");
        chip.setMaxWidth(Double.MAX_VALUE);
        chip.setAlignment(Pos.CENTER);
        VBox.setMargin(chip, new Insets(8, 8, 8, 8));
        return chip;
    }

    private VBox buildEmptyState(String message) {
        FontIcon icon = new FontIcon(MaterialDesignM.MESSAGE_OUTLINE);
        icon.getStyleClass().add("custom-icon-35");
        icon.setOpacity(0.25);

        Label lbl = new Label(message);
        lbl.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        lbl.setWrapText(true);
        lbl.setAlignment(Pos.CENTER);

        VBox box = new VBox(8, icon, lbl);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(32, 16, 16, 16));
        return box;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID resolveOtherId(FriendSummary fs) {
        UUID me = App.getUser() != null ? App.getUser().getUserId() : null;
        if (me == null) return null;
        return me.equals(fs.getRequester()) ? fs.getAddressee() : fs.getRequester();
    }
}
