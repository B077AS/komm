package komm.launcher.update;

import io.github.b077as.jpack.Jpack;
import io.github.b077as.jpack.JpackException;
import io.github.b077as.jpack.UpdateCheck;
import lombok.extern.slf4j.Slf4j;

import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drives the launcher sequence on a background thread, on top of the jpack stub that started it:
 *
 * <ol>
 *   <li>ask the stub whether the release published on GitHub differs from the installed one
 *       ({@link Jpack#check()}),</li>
 *   <li>if so, have it download and verify the changed parts, following the progress
 *       ({@link Jpack#download}) - jars, the Java runtime, the stub and this launcher itself are all
 *       parts of the same release, so there is no separate launcher or client update path,</li>
 *   <li>start the client ({@link Jpack#startNow}). With nothing downloaded it starts right away and
 *       the launcher stays on screen until the client's window is up ({@link Jpack#whenShown}). With an
 *       update downloaded, the stub first swaps the files in once this process has exited (no Java
 *       process may run during the swap), then starts the {@value #APP_ENTRY} entry.</li>
 * </ol>
 *
 * <p>Every update is mandatory: once a newer release is known, a failed download shows an error
 * instead of falling back to the stale install. Only when the update server cannot be asked at all
 * (offline, GitHub down) does the installed version start as-is.
 *
 * <p>All progress is reported through {@link Listener}; callbacks fire on the background thread (or
 * jpack's connection thread), so the UI marshals them onto the FX thread itself.
 *
 * <p>A scripted {@link #runDemo()} reproduces the same visuals without a stub or a network call.
 */
@Slf4j
public class UpdateManager {

    /** The launch entry of the client in {@code jpack.json} (the plugin names it "app"). */
    private static final String APP_ENTRY = "app";

    /** How long the launcher waits for the client's window before closing anyway (a slow first start, an old client). */
    private static final long SHOWN_TIMEOUT_SECONDS = 60;

    /** Callbacks for the UI. They fire off the FX thread. */
    public interface Listener {
        void onPhase(Phase phase);

        /** Determinate download progress, 0..1. */
        void onProgress(double fraction);

        /** Terminal failure; the client was not started. */
        void onError(String message);

        /** The client was handed over to the stub; the launcher should now close. */
        void onLaunched();
    }

    private final Listener listener;

    public UpdateManager(Listener listener) {
        this.listener = listener;
    }

    // -- Real flow --------------------------------------------------------------------------

    public void run() {
        if (!Jpack.isAvailable()) {
            // A dev run of the launcher: there is no stub, hence nothing to update and nothing to hand over to.
            log.warn("Not started by a jpack stub; nothing to update or start (use -Dlauncher.demo=true to preview the UI)");
            listener.onError("Komm must be started from its installation");
            return;
        }
        try (Jpack jpack = Jpack.connect()) {
            log.info("Installed {} {} (entry '{}', canUpdate={})", jpack.appName(), jpack.appVersion(), jpack.entry(), jpack.canUpdate());
            // Flipped once a newer release is known; from that point the stale install is never started as a fallback.
            boolean updateRequired = false;
            try {
                listener.onPhase(Phase.CHECKING);
                pause(450); // let the phrase land, Discord-style

                if (jpack.canUpdate()) {
                    UpdateCheck update = jpack.check().get(30, TimeUnit.SECONDS);
                    log.info("Installed version={}, latest version={} (available={}, {} bytes to download)",
                            jpack.appVersion(), update.version(), update.available(), update.downloadSize());
                    if (update.available()) {
                        updateRequired = true;
                        downloadAndStage(jpack);
                    } else {
                        listener.onPhase(Phase.UP_TO_DATE);
                        pause(550);
                    }
                } else {
                    // No update address (dev build) or an install folder that can't be written to.
                    log.info("This installation cannot update itself; starting the installed version");
                    listener.onPhase(Phase.UP_TO_DATE);
                    pause(550);
                }
                handOver(jpack, 350);
            } catch (Exception e) {
                log.warn("Update flow failed: {}", describe(e));
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // The update server could not be asked (offline, GitHub down, rate-limited): run what is
                // installed. A known-stale install is never started - the error stays on screen.
                if (!updateRequired) {
                    try {
                        handOver(jpack, 300);
                        return;
                    } catch (Exception handOverFailure) {
                        log.error("Failed to hand over to the installed client: {}", describe(handOverFailure));
                    }
                }
                listener.onError(friendlyError(e));
            }
        } catch (JpackException e) {
            log.error("Could not reach the jpack stub: {}", e.getMessage());
            listener.onError("Komm could not start. Please reinstall it");
        }
    }

    /** Downloads the changed parts of the new release; the swap itself happens once this process has exited. */
    private void downloadAndStage(Jpack jpack) throws Exception {
        listener.onPhase(Phase.DOWNLOADING);
        listener.onProgress(0);
        jpack.download((received, total) -> listener.onProgress(total <= 0 ? 1.0 : Math.min(1.0, (double) received / total)))
                .get(30, TimeUnit.MINUTES);
        listener.onProgress(1.0);

        listener.onPhase(Phase.INSTALLING);
        pause(400);
    }

    /**
     * Starts the client. With nothing to swap in, it starts now, next to the launcher, and the launcher closes once the
     * client's window is on screen (the client calls {@code notifyShown}). With an update downloaded, it starts after this
     * process has exited (the files are swapped first): "Starting Komm" is the last thing on screen, and a short gap before
     * the client appears cannot be avoided.
     */
    private void handOver(Jpack jpack, long beat) throws Exception {
        listener.onPhase(Phase.STARTING);
        long since = System.nanoTime();
        OptionalLong client = jpack.startNow(APP_ENTRY).get(30, TimeUnit.SECONDS);
        if (client.isEmpty()) {
            log.info("The client starts once the launcher has exited and the update is swapped in");
            pause(beat);
            listener.onLaunched();
            return;
        }
        log.info("Client started next to the launcher (pid {})", client.getAsLong());
        try {
            jpack.whenShown(APP_ENTRY).get(SHOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Client on screen {} ms after the hand-over", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since));
        } catch (ExecutionException e) {
            log.error("The client did not start: {}", describe(e));
            listener.onError("Komm closed before its window appeared. Try again");
            return;
        } catch (TimeoutException e) {
            log.warn("The client did not say it was on screen within {} s; closing the launcher anyway", SHOWN_TIMEOUT_SECONDS);
        }
        // Keep "Starting Komm..." readable if the client was quicker than that.
        pause(Math.max(0, beat - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since)));
        listener.onLaunched();
    }

    private static String describe(Throwable e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return cause.toString();
    }

    private static String friendlyError(Exception e) {
        if (e instanceof TimeoutException) return "The update server isn't answering";
        return "Update failed — please try again";
    }

    // -- Demo flow --------------------------------------------------------------------------

    /** Scripted sequence so the visuals can be reviewed without a stub or a real release. */
    public void runDemo() {
        try {
            listener.onPhase(Phase.CHECKING);
            pause(1100);

            listener.onPhase(Phase.DOWNLOADING);
            for (int i = 0; i <= 100; i += 2) {
                listener.onProgress(i / 100.0);
                pause(45);
            }

            listener.onPhase(Phase.INSTALLING);
            pause(1200);

            listener.onPhase(Phase.STARTING);
            pause(1000);

            listener.onPhase(Phase.DONE);
        } catch (Exception e) {
            listener.onError("Demo interrupted");
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
