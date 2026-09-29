package komm.ui.utils;

import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import komm.App;

import java.io.File;
import java.util.List;

/**
 * Wraps JavaFX {@link FileChooser} dialogs to work around a long-standing GTK
 * bug on Linux: when a native file chooser is given an owner window, GTK marks
 * the chooser transient-for that window and un-maximizes it for as long as the
 * dialog is open. The main app window visibly shrinks while the chooser is up,
 * and JavaFX's {@code maximizedProperty} desyncs from the real window state.
 *
 * <p>To stop the shrink entirely we show the chooser with <b>no owner</b> on
 * Linux when the owner would be the maximized primary stage, so GTK has nothing
 * to un-maximize. Non-stage owners (e.g. a {@code Popup}) are kept so the chooser
 * still stacks above them. As a safety net (some window
 * managers still poke the active window) we also snapshot the primary stage's
 * maximized state and force a clean re-maximize afterwards <i>only</i> if it
 * actually got un-maximized — which avoids any flicker in the common case.
 *
 * <p>On non-Linux platforms these helpers simply delegate to the underlying
 * {@link FileChooser} with the given owner.
 *
 * <p>Use these instead of calling {@code chooser.showXxxDialog(...)} directly.
 */
public class FileChooserUtil {

    // Fully qualified to avoid clashing with the javafx.application.Platform import above.
    private static final boolean IS_LINUX = com.sun.jna.Platform.isLinux();

    public static File showOpenDialog(FileChooser chooser, Window owner) {
        boolean wasMaximized = capture();
        try {
            requestForeground(owner);
            return chooser.showOpenDialog(effectiveOwner(owner));
        } finally {
            restore(wasMaximized);
        }
    }

    public static List<File> showOpenMultipleDialog(FileChooser chooser, Window owner) {
        boolean wasMaximized = capture();
        try {
            requestForeground(owner);
            return chooser.showOpenMultipleDialog(effectiveOwner(owner));
        } finally {
            restore(wasMaximized);
        }
    }

    public static File showSaveDialog(FileChooser chooser, Window owner) {
        boolean wasMaximized = capture();
        try {
            requestForeground(owner);
            return chooser.showSaveDialog(effectiveOwner(owner));
        } finally {
            restore(wasMaximized);
        }
    }

    /**
     * Nudges the owner window to the front before raising a native dialog. Under
     * {@code mvn javafx:run} the app is a child process forked from the terminal/IDE,
     * so Windows' focus-stealing prevention can leave a freshly created chooser
     * window unraised/unfocused (and therefore invisible) if that parent process —
     * not the app — currently holds the foreground. Bringing the owner to front and
     * focusing it first makes the chooser inherit an already-active owner instead of
     * being subject to that restriction. No-op if there is no window (owner may
     * legitimately be null for an unattached node).
     */
    private static void requestForeground(Window owner) {
        if (owner instanceof Stage stage) {
            stage.toFront();
            stage.requestFocus();
        }
    }

    /**
     * On Linux, drop the owner only when it's the maximized primary stage — that's
     * the window GTK would un-maximize while the chooser is open. For any other
     * owner (e.g. a {@code Popup}) we keep it, so the chooser still stacks above
     * that window instead of appearing behind it.
     */
    private static Window effectiveOwner(Window owner) {
        if (!IS_LINUX) return owner;
        return owner == App.getPrimaryStage() ? null : owner;
    }

    private static boolean capture() {
        if (!IS_LINUX) return false;
        Stage stage = App.getPrimaryStage();
        return stage != null && stage.isMaximized();
    }

    private static void restore(boolean wasMaximized) {
        if (!IS_LINUX || !wasMaximized) return;
        Stage stage = App.getPrimaryStage();
        if (stage == null || stage.isMaximized()) return; // never shrank — nothing to do
        // It did get un-maximized; JavaFX's maximizedProperty may be desynced, so
        // toggle off → on (across two FX pulses) to force a real re-maximize.
        Platform.runLater(() -> {
            stage.setMaximized(false);
            Platform.runLater(() -> stage.setMaximized(true));
        });
    }
}
