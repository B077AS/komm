package komm.ui.emojis;

import io.github.b077as.emojifx.EmojiData;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.util.Duration;
import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class EmojiReactionBar extends FlowPane {

    private static final double EMOJI_SIZE = 16.0;
    private static final double PILL_H_PAD = 7.0;
    private static final double PILL_V_PAD = 3.0;
    private static final double PILL_GAP = 4.0;
    private static final double PILL_RADIUS = 12.0;

    @Getter
    private final Map<String, EmojiReactionEntry> reactions = new LinkedHashMap<>();
    @Setter
    private Consumer<double[]> onPickerRequested;
    @Setter
    private Consumer<String> onReactionAdded;
    @Setter
    private Consumer<String> onReactionRemoved;

    public EmojiReactionBar() {
        setHgap(PILL_GAP);
        setVgap(PILL_GAP);
        setPadding(new Insets(4, 0, 0, 0));
        setAlignment(Pos.CENTER_LEFT);
        setVisible(false);
        setManaged(false);
    }

    public void setReaction(String emojiChar, int count, boolean selfReacted) {
        EmojiReactionEntry existing = reactions.get(emojiChar);
        if (existing != null) {
            existing.setCount(count);
            existing.setSelfReacted(selfReacted);
        } else {
            reactions.put(emojiChar, EmojiReactionEntry.builder().count(count).selfReacted(selfReacted).emojiChar(emojiChar).build());
        }
        rebuild();
    }

    public void incrementReaction(String emojiUnicode, boolean isSelf) {
        String emojiChar = EmojiData.emojiFromCodepoints(emojiUnicode).get().character();
        EmojiReactionEntry existing = reactions.get(emojiChar);
        if (existing != null) {
            if (isSelf && existing.isSelfReacted()) return;
            existing.setCount(existing.getCount() + 1);
            if (isSelf) existing.setSelfReacted(true);
            rebuild();
        } else {
            setReaction(emojiChar, 1, isSelf);
        }
    }

    /**
     * Called when the server echoes a reaction removal.
     * If it's our own removal and we already decremented optimistically, skip.
     */
    public void decrementReaction(String emojiUnicode, boolean isSelf) {
        String emojiChar = EmojiData.emojiFromCodepoints(emojiUnicode).get().character();
        EmojiReactionEntry existing = reactions.get(emojiChar);
        if (existing == null) return;
        if (isSelf && !existing.isSelfReacted()) return; // skip — already decremented optimistically
        existing.setCount(existing.getCount() - 1);
        if (isSelf) existing.setSelfReacted(false);
        if (existing.getCount() <= 0) reactions.remove(emojiChar);
        rebuild();
    }

    public void clearReactions() {
        reactions.clear();
        rebuild();
    }

    // ── Internal rebuild ──────────────────────────────────────────────────────

    private EmojiReactionBar findSiblingBar() {
        Parent p = getParent();
        if (p == null) return null;
        for (Node n : p.getChildrenUnmodifiable()) {
            if (n instanceof EmojiReactionBar bar && bar != this) return bar;
        }
        return null;
    }

    // Merges this bar's reactions into the sibling and triggers a rebuild on it.
    // Returns true if a sibling was found (caller should stay hidden).
    private boolean mergeIntoSiblingIfPresent() {
        EmojiReactionBar sibling = findSiblingBar();
        if (sibling == null) return false;
        boolean changed = false;
        for (Map.Entry<String, EmojiReactionEntry> e : reactions.entrySet()) {
            if (!sibling.reactions.containsKey(e.getKey())) {
                sibling.reactions.put(e.getKey(), EmojiReactionEntry.builder()
                        .count(e.getValue().getCount())
                        .selfReacted(e.getValue().isSelfReacted())
                        .emojiChar(e.getKey())
                        .build());
                changed = true;
            }
        }
        if (changed) sibling.rebuild();
        return true;
    }

    private void rebuild() {
        Set<String> existing = getChildren().stream()
                .filter(n -> n.getUserData() instanceof String)
                .map(n -> (String) n.getUserData())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        getChildren().clear();
        if (reactions.isEmpty()) {
            setVisible(false);
            setManaged(false);
            return;
        }
        if (mergeIntoSiblingIfPresent()) return;
        setVisible(true);
        setManaged(true);
        for (Map.Entry<String, EmojiReactionEntry> e : reactions.entrySet()) {
            if (e.getValue().getCount() > 0) {
                boolean isNew = !existing.contains(e.getKey());
                HBox pill = buildPill(e.getValue(), isNew);
                pill.setUserData(e.getKey());
                getChildren().add(pill);
            }
        }
        getChildren().add(buildAddButton());
    }

    public void rebuildWithoutAnimation() {
        getChildren().clear();
        if (reactions.isEmpty()) {
            setVisible(false);
            setManaged(false);
            return;
        }
        if (mergeIntoSiblingIfPresent()) return;
        setVisible(true);
        setManaged(true);
        for (Map.Entry<String, EmojiReactionEntry> e : reactions.entrySet()) {
            if (e.getValue().getCount() > 0) {
                HBox pill = buildPill(e.getValue(), false);
                pill.setUserData(e.getKey());
                getChildren().add(pill);
            }
        }
        getChildren().add(buildAddButton());
    }

    // ── Pill factory ──────────────────────────────────────────────────────────

    private HBox buildPill(EmojiReactionEntry entry, boolean animate) {
        HBox pill = new HBox(4);
        pill.setAlignment(Pos.CENTER);
        pill.setPadding(new Insets(PILL_V_PAD, PILL_H_PAD, PILL_V_PAD, PILL_H_PAD));
        pill.setMaxHeight(Double.MAX_VALUE);
        applyPillStyle(pill, entry.isSelfReacted());

        Node emojiNode = buildEmojiNode(entry);
        if (emojiNode != null) pill.getChildren().add(emojiNode);

        Label countLabel = new Label(formatCount(entry.getCount()));
        countLabel.setStyle(
                "-fx-font-size: 12px;" +
                        "-fx-font-weight: " + (entry.isSelfReacted() ? "bold" : "normal") + ";" +
                        "-fx-text-fill: " + (entry.isSelfReacted() ? "-color-accent-fg" : "-color-fg-muted") + ";"
        );
        countLabel.setMouseTransparent(true);
        pill.getChildren().add(countLabel);

        pill.setOnMouseClicked(e -> {
            if (entry.isSelfReacted()) {
                if (onReactionRemoved != null) onReactionRemoved.accept(entry.getEmojiChar());
            } else {
                if (onReactionAdded != null) onReactionAdded.accept(entry.getEmojiChar());
            }
            e.consume();
        });

        pill.setOnMouseEntered(ev -> applyPillHoverStyle(pill, entry.isSelfReacted()));
        pill.setOnMouseExited(ev -> applyPillStyle(pill, entry.isSelfReacted()));

        if (animate) animatePop(pill);
        return pill;
    }

    private Node buildEmojiNode(EmojiReactionEntry entry) {
        String character = entry.getEmojiChar();
        if (character == null || character.isEmpty()) return null;
        List<Node> nodes = TextUtils.convertToTextAndImageNodes(character, EMOJI_SIZE);
        if (nodes.isEmpty()) return null;
        Node n = nodes.get(0);
        if (n instanceof ImageView iv) {
            iv.setFitWidth(EMOJI_SIZE);
            iv.setFitHeight(EMOJI_SIZE);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            iv.setMouseTransparent(true);
            return iv;
        } else if (n instanceof Text t) {
            t.setStyle("-fx-font-size: " + EMOJI_SIZE + "px;");
            t.setMouseTransparent(true);
            return t;
        }
        return n;
    }

    // ── "+" add-reaction button ───────────────────────────────────────────────

    private HBox buildAddButton() {
        Label plus = new Label("+");
        plus.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-subtle;");
        plus.setMouseTransparent(true);

        HBox btn = new HBox(plus);
        btn.setAlignment(Pos.CENTER);
        btn.setPadding(new Insets(PILL_V_PAD, PILL_H_PAD, PILL_V_PAD, PILL_H_PAD));
        btn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-border-color: -color-border-subtle;" +
                        "-fx-border-width: 1px;" +
                        "-fx-border-radius: " + PILL_RADIUS + "px;" +
                        "-fx-background-radius: " + PILL_RADIUS + "px;" +
                        "-fx-cursor: hand;"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: -color-neutral-subtle;" +
                        "-fx-border-color: -color-border-default;" +
                        "-fx-border-width: 1px;" +
                        "-fx-border-radius: " + PILL_RADIUS + "px;" +
                        "-fx-background-radius: " + PILL_RADIUS + "px;" +
                        "-fx-cursor: hand;"
        ));
        btn.setOnMouseExited(e -> btn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-border-color: -color-border-subtle;" +
                        "-fx-border-width: 1px;" +
                        "-fx-border-radius: " + PILL_RADIUS + "px;" +
                        "-fx-background-radius: " + PILL_RADIUS + "px;" +
                        "-fx-cursor: hand;"
        ));
        btn.setOnMouseClicked(e -> {
            if (onPickerRequested != null) onPickerRequested.accept(new double[]{e.getScreenX(), e.getScreenY()});
            e.consume();
        });
        return btn;
    }

    // ── Style helpers ─────────────────────────────────────────────────────────

    private void applyPillStyle(HBox pill, boolean selfReacted) {
        pill.setStyle(
                "-fx-background-color: " + (selfReacted ? "-color-accent-subtle" : "-color-neutral-subtle") + ";" +
                        "-fx-border-color: " + (selfReacted ? "-color-accent-emphasis" : "-color-border-subtle") + ";" +
                        "-fx-border-width: 1px;" +
                        "-fx-border-radius: " + PILL_RADIUS + "px;" +
                        "-fx-background-radius: " + PILL_RADIUS + "px;" +
                        "-fx-cursor: hand;"
        );
    }

    private void applyPillHoverStyle(HBox pill, boolean selfReacted) {
        pill.setStyle(
                "-fx-background-color: " + (selfReacted ? "-color-accent-muted" : "-color-neutral-muted") + ";" +
                        "-fx-border-color: " + (selfReacted ? "-color-accent-emphasis" : "-color-border-default") + ";" +
                        "-fx-border-width: 1px;" +
                        "-fx-border-radius: " + PILL_RADIUS + "px;" +
                        "-fx-background-radius: " + PILL_RADIUS + "px;" +
                        "-fx-cursor: hand;"
        );
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    private void animatePop(Node node) {
        ScaleTransition st = new ScaleTransition(Duration.millis(120), node);
        st.setFromX(0.75);
        st.setFromY(0.75);
        st.setToX(1.0);
        st.setToY(1.0);
        st.play();
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private static String formatCount(int count) {
        return count >= 1000 ? (count / 1000) + "k" : String.valueOf(count);
    }
}