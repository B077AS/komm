package komm.ui.chat.virtual;

import javafx.scene.Node;
import javafx.scene.layout.Region;
import komm.ui.code.CodeBlockView;
import komm.ui.gifs.GifMessageCell;
import org.fxmisc.flowless.Cell;

import java.util.function.Consumer;

/**
 * A Flowless {@link Cell} that owns one message node (an {@code EmojiMessageItem}).
 *
 * <p>Cells are <b>not reused</b> ({@link #isReusable()} is left {@code false}): each
 * message gets a freshly built node when it scrolls into view and that node is torn
 * down when it scrolls out. This is the whole point of the virtualization — the
 * scene graph only ever holds the handful of message subtrees near the viewport,
 * not every message ever loaded.
 */
final class MessageCell implements Cell<MessageRow, Region> {

    private final MessageRow row;
    private final Region node;
    private final Consumer<MessageRow> onDisposed;
    private boolean disposed = false;

    MessageCell(MessageRow row, Region node, Consumer<MessageRow> onDisposed) {
        this.row = row;
        this.node = node;
        this.onDisposed = onDisposed;
    }

    MessageRow row() {
        return row;
    }

    Region node() {
        return node;
    }

    @Override
    public Region getNode() {
        return node;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        retire(node);
        if (onDisposed != null) onDisposed.accept(row);
    }

    /**
     * Releases the heavyweight resources a message subtree can hold: animated GIF /
     * remote-image decoders and RichTextFX code editors. Everything else is plain
     * scene-graph and is collected once the node is unreferenced.
     */
    static void retire(Node node) {
        if (node instanceof GifMessageCell gif) {
            gif.dispose();
            return;
        }
        if (node instanceof CodeBlockView code) {
            code.dispose();
            return;
        }
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) retire(child);
        }
    }
}
