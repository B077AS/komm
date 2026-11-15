package komm.ui.mention;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import komm.App;
import komm.ui.avatar.AvatarColor;
import komm.ui.emojis.EmojiTextArea;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The @mention suggestion list, docked on top of an input row the same way the attachment bar
 * is (the owner places it directly above the row and flattens the row's top corners while it's
 * open — see {@link #setOnOpenChanged}). Opens while the caret is in an {@code @query}, filters
 * the candidates locally, and handles ↑ ↓ Tab Enter Esc before the text area sees them.
 *
 * <p>Candidates are user ids (who can see the channel); names come from the
 * {@link komm.ui.avatar.AvatarCache}, so ids whose name isn't cached yet are skipped until it is.
 */
public class MentionSuggestBar extends VBox {

    private static final int MAX_ROWS = 6;
    private static final double AVATAR = 24;

    private record Match(UUID id, String name, int matchAt, int recency) {
    }

    private final EmojiTextArea area;
    private final Supplier<Collection<UUID>> candidates;
    private final ToIntFunction<UUID> recency;

    private final VBox rows = new VBox(2);
    private final Label header = new Label("MEMBERS");
    private final Label countLabel = new Label();
    private final Map<UUID, Image> avatarImages = new HashMap<>();

    private List<Match> matches = List.of();
    private int selected = 0;
    /** Query the user dismissed with Esc; the bar stays closed until it changes. */
    private String dismissedQuery;
    private String lastQuery;
    private boolean topRounded = true;

    /** Called with the new state whenever the bar opens or closes (owners fix up corner radii). */
    @Setter
    private Runnable onOpenChanged;

    /**
     * @param candidates ids of everyone who can be mentioned here (re-read on every keystroke)
     * @param recency    smaller = spoke more recently here; {@code Integer.MAX_VALUE} = not recently
     */
    public MentionSuggestBar(EmojiTextArea area, Supplier<Collection<UUID>> candidates,
                             ToIntFunction<UUID> recency) {
        this.area = area;
        this.candidates = candidates;
        this.recency = recency;

        header.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        countLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = new Label("↑ ↓  ·  Tab / Enter to insert  ·  Esc");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        HBox head = new HBox(8, header, countLabel, spacer, hint);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(2, 8, 4, 8));

        getChildren().addAll(head, rows);
        setPadding(new Insets(8, 8, 6, 8));
        applyStyle();
        setOpen(false);

        area.setOnCaretChanged(this::refresh);
        area.setKeyPreFilter(this::handleKey);
        area.focusWithinProperty().addListener((obs, was, now) -> {
            if (!now) close();
        });
    }

    public void setHeaderText(String text) {
        header.setText(text);
    }

    public boolean isOpen() {
        return isVisible();
    }

    /** Rounded top corners when nothing else (reply / attachment bar) sits above the bar. */
    public void setTopRounded(boolean rounded) {
        if (topRounded == rounded) return;
        topRounded = rounded;
        applyStyle();
    }

    private void applyStyle() {
        setStyle("-fx-background-color: -color-bg-subtle;"
                + "-fx-background-radius: " + (topRounded ? "8px 8px 0 0" : "0") + ";"
                + "-fx-border-color: transparent transparent -color-border-muted transparent;"
                + "-fx-border-width: 0 0 1px 0;");
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Re-reads the query at the caret; opens, filters or closes the list. */
    public void refresh() {
        String query = area.getMentionQuery();
        if (query == null) {
            dismissedQuery = null;
            lastQuery = null;
            close();
            return;
        }
        if (query.equals(dismissedQuery)) return;
        dismissedQuery = null;
        if (!query.equals(lastQuery)) selected = 0;
        lastQuery = query;

        matches = rank(query);
        if (matches.isEmpty()) {
            close();
            return;
        }
        selected = Math.min(selected, matches.size() - 1);
        renderRows(query);
        if (!isOpen()) {
            setOpen(true);
            FadeTransition ft = new FadeTransition(Duration.millis(120), this);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.play();
        }
    }

    public void close() {
        if (isOpen()) setOpen(false);
    }

    private void setOpen(boolean open) {
        setVisible(open);
        setManaged(open);
        if (!open) rows.getChildren().clear();
        if (onOpenChanged != null) onOpenChanged.run();
    }

    private List<Match> rank(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        Collection<UUID> ids = candidates.get();
        if (ids == null || ids.isEmpty()) return List.of();
        List<Match> out = new ArrayList<>();
        for (UUID id : ids) {
            String name = App.getAvatarCache().getUsername(id);
            if (name == null) continue;
            int at = name.toLowerCase(Locale.ROOT).indexOf(q);
            if (at < 0) continue;
            out.add(new Match(id, name, at, recency != null ? recency.applyAsInt(id) : Integer.MAX_VALUE));
        }
        countLabel.setText(out.size() == 1 ? "1 match" : out.size() + " matches");
        out.sort(Comparator
                .comparingInt((Match m) -> m.matchAt == 0 ? 0 : 1) // prefix matches first
                .thenComparingInt(Match::recency)                     // then who spoke recently here
                .thenComparing(m -> m.name.toLowerCase(Locale.ROOT)));
        return out.size() > MAX_ROWS ? out.subList(0, MAX_ROWS) : out;
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    private boolean handleKey(KeyEvent e) {
        if (!isOpen() || matches.isEmpty()) return false;
        if (e.isControlDown() || e.isMetaDown() || e.isAltDown()) return false;
        switch (e.getCode()) {
            case UP -> moveSelection(-1);
            case DOWN -> moveSelection(+1);
            case TAB, ENTER -> {
                if (e.isShiftDown()) return false; // Shift+Enter keeps inserting a newline
                pick(selected);
            }
            case ESCAPE -> {
                dismissedQuery = lastQuery;
                close();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void moveSelection(int delta) {
        selected = Math.floorMod(selected + delta, matches.size());
        renderRows(lastQuery == null ? "" : lastQuery);
    }

    private void pick(int index) {
        if (index < 0 || index >= matches.size()) return;
        UUID id = matches.get(index).id;
        close();
        area.insertMention(id);
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void renderRows(String query) {
        List<Node> children = new ArrayList<>(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            children.add(buildRow(matches.get(i), query.length(), i == selected, i));
        }
        rows.getChildren().setAll(children);
    }

    private HBox buildRow(Match m, int queryLen, boolean isSelected, int index) {
        String nameColor = isSelected ? "-color-fg-default" : "-color-fg-muted";
        int hitEnd = Math.min(m.name.length(), m.matchAt + queryLen);
        Text before = new Text(m.name.substring(0, m.matchAt));
        Text hit = new Text(m.name.substring(m.matchAt, hitEnd));
        Text after = new Text(m.name.substring(hitEnd));
        before.setStyle("-fx-fill: " + nameColor + "; -fx-font-weight: bold;");
        after.setStyle("-fx-fill: " + nameColor + "; -fx-font-weight: bold;");
        hit.setStyle("-fx-fill: -color-accent-3; -fx-font-weight: bold;");
        TextFlow name = new TextFlow(before, hit, after);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label sub = new Label(m.recency != Integer.MAX_VALUE ? "talked recently" : "");
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        HBox row = new HBox(10, avatar(m.id, m.name), name, spacer, sub);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 8, 4, 8));
        row.setStyle("-fx-background-radius: 4; -fx-background-color: "
                + (isSelected ? "-color-bg-middle;" : "transparent;"));
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseEntered(e -> {
            if (selected != index) {
                selected = index;
                renderRows(lastQuery == null ? "" : lastQuery);
            }
        });
        row.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                pick(index);
                e.consume();
            }
        });
        return row;
    }

    private Node avatar(UUID id, String name) {
        StackPane pane = new StackPane();
        pane.setMinSize(AVATAR, AVATAR);
        pane.setMaxSize(AVATAR, AVATAR);
        Image img = avatarImages.computeIfAbsent(id, k -> App.getAvatarCache().getAvatarImage(k, AVATAR));
        if (img != null) {
            ImageView iv = new ImageView(img);
            iv.setFitWidth(AVATAR);
            iv.setFitHeight(AVATAR);
            pane.getChildren().add(iv);
        } else {
            Circle bg = new Circle(AVATAR / 2.0, AvatarColor.forNameJfx(name));
            Label initial = new Label(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT));
            initial.setStyle("-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold;");
            pane.getChildren().addAll(bg, initial);
        }
        pane.setClip(new Circle(AVATAR / 2.0, AVATAR / 2.0, AVATAR / 2.0));
        return pane;
    }
}
