package komm.utils;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks missed heartbeats for the local connection.
 *
 * <p>WebSocket rides on top of TCP, so there is no raw packet loss to observe —
 * a "miss" here means a PING did not get its PONG back before the next PING
 * was due, which signals a stall, hiccup, or reconnect rather than a dropped
 * IP packet. {@link komm.websocket.PingService} reports the outcome of the
 * previous tick each time it sends a new PING, and {@code PongHandler} marks
 * when a reply actually lands.</p>
 */
public class PingLossTracker {

    private static final int WINDOW = 60;

    private static final Deque<Boolean> outcomes = new ArrayDeque<>();
    private static volatile long lastPingSentAt = 0;
    private static volatile long lastPongReceivedAt = 0;

    private PingLossTracker() {}

    /** Call right before sending a new PING, with its timestamp. */
    public static void onPingSent(long timestamp) {
        synchronized (outcomes) {
            if (lastPingSentAt > 0) {
                boolean hit = lastPongReceivedAt >= lastPingSentAt;
                if (outcomes.size() >= WINDOW) outcomes.removeFirst();
                outcomes.addLast(hit);
            }
            lastPingSentAt = timestamp;
        }
    }

    /** Call whenever a PONG is received. */
    public static void onPongReceived(long timestamp) {
        lastPongReceivedAt = timestamp;
    }

    /** Reset all state — call when (re)starting the ping service for a fresh connection. */
    public static void reset() {
        synchronized (outcomes) {
            outcomes.clear();
            lastPingSentAt = 0;
            lastPongReceivedAt = 0;
        }
    }

    /** Percentage (0-100) of missed heartbeats over the current window. */
    public static int getLossPercent() {
        synchronized (outcomes) {
            if (outcomes.isEmpty()) return 0;
            long misses = outcomes.stream().filter(hit -> !hit).count();
            return (int) Math.round(misses * 100.0 / outcomes.size());
        }
    }
}
