package komm.utils;

import javafx.application.Platform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Stores per-user ping histories received via {@code USER_PING_UPDATE} messages.
 * All mutations are dispatched on the JavaFX application thread.
 */
public class UserPingHistory {

    public static final int MAX_SIZE = 60;

    // userId (as String) → LinkedList<pingMs>
    private static final Map<String, LinkedList<Integer>> histories = new ConcurrentHashMap<>();

    // userId (as String) → most recently reported missed-heartbeat rate (0-100)
    private static final Map<String, Integer> lossByUser = new ConcurrentHashMap<>();

    // userId (as String) → listeners
    private static final Map<String, List<Consumer<Integer>>> listenerMap = new ConcurrentHashMap<>();

    private UserPingHistory() {}

    public static void record(String userId, int ms, int lossPct) {
        Platform.runLater(() -> {
            LinkedList<Integer> hist = histories.computeIfAbsent(userId, k -> new LinkedList<>());
            if (hist.size() >= MAX_SIZE) hist.removeFirst();
            hist.addLast(ms);
            lossByUser.put(userId, lossPct);
            List<Consumer<Integer>> ls = listenerMap.get(userId);
            if (ls != null) ls.forEach(l -> l.accept(ms));
        });
    }

    public static List<Integer> getHistory(String userId) {
        LinkedList<Integer> hist = histories.get(userId);
        return hist == null ? Collections.emptyList() : Collections.unmodifiableList(hist);
    }

    /** Most recently reported missed-heartbeat rate (0-100) for this user, or 0 if unknown. */
    public static int getLossPercent(String userId) {
        return lossByUser.getOrDefault(userId, 0);
    }

    public static void addListener(String userId, Consumer<Integer> listener) {
        listenerMap.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public static void removeListener(String userId, Consumer<Integer> listener) {
        List<Consumer<Integer>> ls = listenerMap.get(userId);
        if (ls != null) ls.remove(listener);
    }

}
