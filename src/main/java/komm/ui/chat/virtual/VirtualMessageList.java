package komm.ui.chat.virtual;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import komm.websocket.messages.payloads.MessageReceivedPayload;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.flowless.VirtualFlow;
import org.fxmisc.flowless.VirtualizedScrollPane;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A bottom-anchored, virtualized message list built on Flowless.
 *
 * <p>Prototype replacement for {@code DmChatSection}'s {@code ScrollPane + VBox} and
 * its scroll-correction machinery. Only the message subtrees near the viewport are
 * ever in the scene graph — Flowless builds a cell when a row scrolls in and
 * {@link MessageCell#dispose() disposes} it when it scrolls out — so memory no longer
 * grows with the number of messages loaded.
 *
 * <h2>Threading contract</h2>
 * Flowless (via reactfx {@code MemoizationList}) corrupts its internal state if the
 * backing list is mutated re-entrantly — i.e. from inside a scroll/size property
 * invalidation that Flowless itself fired. So:
 * <ul>
 *   <li>public mutators ({@link #setInitial}, {@link #prepend}, {@link #appendMessage},
 *       …) must only be called from a clean FX callback (an event handler or
 *       {@code Platform.runLater}), never from a listener;</li>
 *   <li>the near-top callback and every {@code flow.*} scroll call this class makes
 *       are deferred with {@code Platform.runLater} so they never run inside the
 *       viewport-change notification.</li>
 * </ul>
 *
 * <h2>Known prototype limitations</h2>
 * <ul>
 *   <li>Scrollbar thumb is an estimate — drifts as unmeasured heights get realized.</li>
 *   <li>Prepend anchoring re-pins over the next pulses; a very tall just-loaded
 *       message can hop once before it settles.</li>
 *   <li>A reaction on a scrolled-out message updates the model but shows only when
 *       the cell is rebuilt.</li>
 * </ul>
 */
@Slf4j
public class VirtualMessageList extends Region {

    /** Right-edge allowance for the always-on vertical scrollbar. */
    private static final double GUTTER = 24.0;

    /** Fire "load older" when the first visible row is within this many rows of the top. */
    private static final int NEAR_TOP_ROWS = 4;

    private final ObservableList<MessageRow> rows = FXCollections.observableArrayList();
    private final Map<UUID, MessageRow> rowById = new HashMap<>();

    private final VirtualFlow<MessageRow, MessageCell> flow;
    private final VirtualizedScrollPane<VirtualFlow<MessageRow, MessageCell>> vsp;

    @Setter
    private Function<MessageReceivedPayload, Region> messageNodeFactory;
    @Setter
    private Runnable onNearTop;
    @Setter
    private Consumer<Boolean> onAtBottomChanged;
    /**
     * -- SETTER --
     * Called with (id, node) when a cell is torn down — drop the id from any realized-item map.
     */
    @Setter
    private BiConsumer<UUID, Region> onCellRetired;

    /** Pixels of downward-scroll slack before a scrollY drop is read as a deliberate scroll-up. */
    private static final double SCROLL_UP_SLACK = 12.0;

    private boolean nearTopArmed = true;
    private boolean followBottom = true;
    private boolean lastAtBottom = true;
    private double lastScrollY = 0;

    private boolean inViewportChange = false;
    private boolean programmaticScroll = false;

    // ── page-navigation anchor ────────────────────────────────────────────────
    private MessageRow savedAnchorRow;
    private double savedAnchorOffset;
    private boolean savedWasBottom = true;

    public VirtualMessageList() {
        flow = VirtualFlow.createVertical(rows, this::createCell, VirtualFlow.Gravity.REAR);
        vsp = new VirtualizedScrollPane<>(flow,
                ScrollPane.ScrollBarPolicy.NEVER, ScrollPane.ScrollBarPolicy.ALWAYS);

        setMinSize(0, 0);
        setStyle("-fx-background-color: -color-bg-default;");
        flow.setStyle("-fx-background-color: -color-bg-default;");
        vsp.setStyle("-fx-background-color: -color-bg-default;");
        getChildren().add(vsp);

        InvalidationListener viewport = o -> onViewportChanged();
        flow.estimatedScrollYProperty().addListener(viewport);
        flow.totalHeightEstimateProperty().addListener(viewport);
    }

    // ── content mutation (call only from a clean FX callback) ──────────────────

    public void clear() {
        rows.clear();
        rowById.clear();
        nearTopArmed = true;
        followBottom = true;
        lastAtBottom = true;
        lastScrollY = 0;
    }

    /** Replace all content with an initial page (oldest→newest) and pin to the bottom. */
    public void setInitial(List<MessageReceivedPayload> msgs) {
        rowById.clear();
        nearTopArmed = true;
        followBottom = true;
        lastAtBottom = true;
        lastScrollY = 0;
        List<MessageRow> fresh = new ArrayList<>(msgs.size());
        for (MessageReceivedPayload p : msgs) {
            MessageRow r = MessageRow.message(p);
            fresh.add(r);
            rowById.put(p.getMessageId(), r);
        }
        rows.setAll(fresh); // single structural change
        pinBottomSoon();
    }

    /** Insert an older page (oldest→newest) above the current content, keeping the viewport anchored. */
    public void prepend(List<MessageReceivedPayload> older) {
        if (older == null || older.isEmpty()) return;

        int firstIdx = safeFirstVisible();
        MessageRow anchorRow = (firstIdx >= 0 && firstIdx < rows.size()) ? rows.get(firstIdx) : null;
        double anchorOffset = anchorRow != null ? cellTopOffset(firstIdx) : 0.0;

        List<MessageRow> fresh = new ArrayList<>(older.size());
        for (MessageReceivedPayload p : older) {
            MessageRow r = MessageRow.message(p);
            fresh.add(r);
            rowById.put(p.getMessageId(), r);
        }
        rows.addAll(0, fresh);

        if (anchorRow != null) {
            final MessageRow a = anchorRow;
            final double off = anchorOffset;
            Runnable reanchor = () -> {
                int i = rows.indexOf(a);
                if (i >= 0) safe(() -> flow.showAtOffset(i, off));
            };
            Platform.runLater(reanchor);
            Platform.runLater(reanchor);
        }
    }

    /** Append a newly received message; follows the bottom only if the viewport is already there. */
    public void appendMessage(MessageReceivedPayload m) {
        boolean wasBottom = followBottom || isAtBottom();
        MessageRow r = MessageRow.message(m);
        rowById.put(m.getMessageId(), r);
        rows.add(r);
        if (wasBottom) pinBottomSoon();
    }

    public void removeById(UUID id) {
        MessageRow r = rowById.remove(id);
        if (r == null) return;
        int i = rows.indexOf(r);
        if (i >= 0) rows.remove(i);
    }

    /**
     * Force the cell for {@code id} to rebuild from its (already-mutated) payload.
     * Used after an edit / reaction so a scrolled-out message picks the change up
     * when next realized, and an on-screen one refreshes now.
     */
    public void refreshId(UUID id) {
        MessageRow r = rowById.get(id);
        if (r == null) return;
        int i = rows.indexOf(r);
        if (i < 0) return;
        MessageRow nr = MessageRow.message(r.payload());
        rowById.put(id, nr);
        rows.set(i, nr);
    }

    // ── queries ───────────────────────────────────────────────────────────────

    public boolean containsId(UUID id) { return rowById.containsKey(id); }

    public boolean isLastId(UUID id) {
        MessageReceivedPayload last = lastPayload();
        return last != null && last.getMessageId().equals(id);
    }

    public MessageReceivedPayload payload(UUID id) {
        MessageRow r = rowById.get(id);
        return r == null ? null : r.payload();
    }

    public MessageReceivedPayload lastPayload() {
        return rows.isEmpty() ? null : rows.get(rows.size() - 1).payload();
    }

    /** Whether the newest message is currently in view (estimate-based, no navigator). */
    public boolean isAtBottom() {
        Double sy = flow.estimatedScrollYProperty().getValue();
        Double th = flow.totalHeightEstimateProperty().getValue();
        double viewport = flow.getHeight();
        if (sy == null || th == null || viewport <= 0) return true;
        if (th <= viewport + 1) return true;      // content fits — always "at bottom"
        return sy >= th - viewport - 4;
    }

    // ── scrolling ─────────────────────────────────────────────────────────────

    public void scrollToBottom() {
        pinBottomSoon();
    }

    /**
     * Synchronously re-pins to the bottom right now, with no deferred retries.
     * Meant to be driven from an external per-pulse listener (e.g. a container
     * resize animation) so the view stays glued to the bottom on every frame
     * instead of drifting during the resize and snapping back once at the end.
     */
    public void pinBottomImmediate() {
        followBottom = true;
        pinBottomNow();
    }

    // ── page navigation (Home ↔ DM) ───────────────────────────────────────────

    public void saveAnchor() {
        savedWasBottom = followBottom || isAtBottom();
        savedAnchorRow = null;
        int f = safeFirstVisible();
        if (f >= 0 && f < rows.size()) {
            savedAnchorRow = rows.get(f);
            savedAnchorOffset = cellTopOffset(f);
        }
    }

    public void restoreAnchor() {
        if (savedWasBottom || savedAnchorRow == null) {
            pinBottomSoon();
            return;
        }
        final MessageRow a = savedAnchorRow;
        final double off = savedAnchorOffset;
        Runnable r = () -> {
            int i = rows.indexOf(a);
            if (i >= 0) safe(() -> flow.showAtOffset(i, off));
        };
        Platform.runLater(r);
        Platform.runLater(r);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private MessageCell createCell(MessageRow row) {
        Region node = messageNodeFactory.apply(row.payload());
        node.setMinWidth(0);
        node.prefWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(0, getWidth() - GUTTER), widthProperty()));
        node.setMaxWidth(Region.USE_PREF_SIZE);
        return new MessageCell(row, node, retired -> {
            node.prefWidthProperty().unbind();
            if (onCellRetired != null) onCellRetired.accept(retired.id(), node);
        });
    }

    /**
     * Fired from {@code estimatedScrollY} / {@code totalHeightEstimate} invalidations —
     * i.e. inside Flowless's own notification cascade. MUST NOT mutate {@link #rows}
     * or call {@code flow.*} synchronously; everything that could is deferred.
     */
    private void onViewportChanged() {
        if (inViewportChange) return;
        inViewportChange = true;
        try {
            int first = safeFirstVisible();
            if (first >= 0) {
                if (first <= NEAR_TOP_ROWS) fireNearTopDeferred();
                else nearTopArmed = true;
            }

            Double syObj = flow.estimatedScrollYProperty().getValue();
            double sy = syObj == null ? 0 : syObj;
            boolean atBottom = isAtBottom();

            // Only a real downward drag turns off follow-bottom. Content growing
            // underneath (a GIF committing its real height, a code block, an image)
            // pushes the bottom away without moving scrollY, so it must NOT unstick.
            if (!programmaticScroll) {
                if (sy < lastScrollY - SCROLL_UP_SLACK) followBottom = false;
                else if (atBottom) followBottom = true;
            }
            lastScrollY = sy;

            if (atBottom != lastAtBottom) {
                lastAtBottom = atBottom;
                if (onAtBottomChanged != null) onAtBottomChanged.accept(atBottom);
            }
            if (followBottom && !programmaticScroll && !atBottom) {
                Platform.runLater(this::pinBottomNow);
            }
        } catch (Exception e) {
            log.debug("[vlist] viewport change error: {}", e.toString());
        } finally {
            inViewportChange = false;
        }
    }

    private void fireNearTopDeferred() {
        if (!nearTopArmed || onNearTop == null) return;
        nearTopArmed = false;
        Platform.runLater(() -> {
            if (onNearTop != null) onNearTop.run();
        });
    }

    /** Push the scroll offset to the end via the estimate — avoids Navigator's "cell N not visible" crash. */
    private void pinBottomNow() {
        Double th = flow.totalHeightEstimateProperty().getValue();
        if (th == null) return;
        programmaticScroll = true;
        try {
            safe(() -> flow.estimatedScrollYProperty().setValue(th));
        } finally {
            programmaticScroll = false;
        }
        updateAtBottomFlag();
    }

    /**
     * Pins to the bottom now and again over the next ~360 ms — the initial page and
     * new messages settle their height across several layout pulses (code blocks,
     * images, emoji). Replaces the old frame-by-frame burst timer.
     */
    private void pinBottomSoon() {
        followBottom = true;
        Platform.runLater(this::pinBottomNow);
        for (double ms : new double[]{60, 180, 360}) {
            Timeline t = new Timeline(new KeyFrame(Duration.millis(ms), e -> {
                if (followBottom) pinBottomNow();
            }));
            t.play();
        }
    }

    private void updateAtBottomFlag() {
        boolean atBottom = isAtBottom();
        if (atBottom != lastAtBottom) {
            lastAtBottom = atBottom;
            if (onAtBottomChanged != null) onAtBottomChanged.accept(atBottom);
        }
    }

    private int safeFirstVisible() {
        try {
            return flow.getFirstVisibleIndex();
        } catch (Exception e) {
            return -1;
        }
    }

    private double cellTopOffset(int index) {
        try {
            return flow.getCellIfVisible(index)
                    .map(c -> flow.cellToViewport(c, c.getNode().getBoundsInLocal()).getMinY())
                    .orElse(0.0);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.debug("[vlist] flow op skipped: {}", e.toString());
        }
    }

    @Override
    protected void layoutChildren() {
        vsp.resizeRelocate(0, 0, getWidth(), getHeight());
    }
}
