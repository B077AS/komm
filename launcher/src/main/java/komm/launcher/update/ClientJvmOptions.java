package komm.launcher.update;

import com.sun.jna.Platform;

import java.util.List;
import java.util.Map;

/**
 * JVM options and environment the launcher starts the client with.
 *
 * <p>Without them the client ran on bare JVM defaults: a max heap of 25% of physical RAM that
 * G1 rarely gives back, and G1 bookkeeping (per-thread task queues, mark stack) sized for every
 * hardware thread. Measured at the login page (Windows, 12 threads, 16 GB) this set brought the
 * JVM's committed memory from ~197 MB to ~150 MB and G1's own structures from ~55 MB to ~14 MB,
 * while the live heap is only ~25 MB.
 *
 * <p>Keep the dev-run copy in the root {@code pom.xml} ({@code javafx-maven-plugin} options) in
 * sync with this list.
 */
public final class ClientJvmOptions {

    private ClientJvmOptions() {}

    private static final List<String> OPTIONS = List.of(
            // Start small and let the heap grow on demand; 2 GB is a ceiling for GIF-heavy chats,
            // not a target (live heap is a few dozen MB).
            "-Xms32m",
            "-Xmx2g",
            // Hand unused heap back to the OS: a periodic GC when idle plus tight free ratios.
            "-XX:G1PeriodicGCInterval=30000",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=20",
            // A desktop client's heap is small; a couple of GC threads are plenty and avoid
            // per-thread GC structures sized for every core.
            "-XX:ParallelGCThreads=2",
            "-XX:ConcGCThreads=1",
            // Video frame buffers are direct memory: cap it so a burst of discarded buffers
            // triggers a GC long before it can balloon (default cap = max heap).
            "-XX:MaxDirectMemorySize=1g",
            // JEP 472: JNA / webrtc-java / JavaFX native access without warnings.
            "--enable-native-access=ALL-UNNAMED"
    );

    public static List<String> get() {
        return OPTIONS;
    }

    /**
     * Linux: glibc gives every thread that allocates its own malloc arena (up to 8 per core), and
     * the client has dozens of native threads (WebRTC, audio, capture) churning large buffers —
     * memory freed in one arena isn't reusable by another, which shows up as RSS that only grows.
     * Two arenas is the usual cure for JVM + native workloads.
     */
    public static void applyEnvironment(Map<String, String> env) {
        if (Platform.isLinux()) env.putIfAbsent("MALLOC_ARENA_MAX", "2");
    }
}
