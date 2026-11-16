package komm.update;

import io.github.b077as.jpack.Jpack;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tells the jpack stub that the main window is on screen. The launcher started the client next to itself
 * ({@code Jpack.startNow}) and stays open until it hears this, so there is no moment with nothing on screen between the two.
 *
 * <p>Said once per run. Without a stub (IDE, {@code java -jar}) it does nothing; with a stub older than jpack 0.1.3,
 * saying it does nothing either and the launcher closes after its timeout.
 */
@Slf4j
public final class OnScreen {

    private static final AtomicBoolean SAID = new AtomicBoolean();

    private OnScreen() {
    }

    /** Call once the main window is visible. Safe from any thread; never blocks the caller. */
    public static void tellLauncher() {
        if (!Jpack.isAvailable() || !SAID.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("jpack-shown").start(OnScreen::tellTheStub);
    }

    private static void tellTheStub() {
        try (Jpack jpack = Jpack.connect()) {
            jpack.notifyShown().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // The launcher closes on its own after a while; nothing the user needs to see.
            log.warn("Could not tell the jpack stub that the window is on screen: {}", e.toString());
        }
    }
}
