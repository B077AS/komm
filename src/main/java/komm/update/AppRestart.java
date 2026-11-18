package komm.update;

import io.github.b077as.jpack.Jpack;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

/**
 * Restarts Komm through the jpack stub: the stub is told to start this entry again once every Java process has
 * exited (a downloaded update, if one is waiting, is swapped in first), then the client shuts down normally
 * ({@code Platform.exit} → {@code App.stop}, which leaves voice and ends with {@code System.exit}).
 *
 * <p>Only possible when the stub started us ({@link #isSupported()}); from the IDE / {@code java -jar} there is
 * nobody to start the client again, so callers fall back to "takes effect after restarting Komm".
 */
@Slf4j
public class AppRestart {

    public static boolean isSupported() {
        return Jpack.isAvailable();
    }

    /** Safe from any thread. If the stub can't be told, Komm keeps running (and nothing is lost). */
    public static void restart() {
        if (!isSupported()) return;
        Thread.ofVirtual().name("jpack-restart").start(() -> {
            try (Jpack jpack = Jpack.connect()) {
                jpack.restartAfterExit(); // blocks until the stub has acknowledged it
            } catch (Exception e) {
                log.warn("Could not ask the jpack stub to restart Komm; staying open: {}", e.toString());
                return;
            }
            log.info("Restarting Komm (jpack will start it again after this process exits)");
            Platform.runLater(Platform::exit);
        });
    }
}
