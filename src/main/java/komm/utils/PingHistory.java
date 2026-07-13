package komm.utils;

import javafx.application.Platform;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class PingHistory {

    public static final int MAX_SIZE = 60;

    private static final LinkedList<Integer> history = new LinkedList<>();
    private static final List<Consumer<Integer>> listeners = new CopyOnWriteArrayList<>();

    private PingHistory() {}

    public static void record(int ms) {
        Platform.runLater(() -> {
            if (history.size() >= MAX_SIZE) history.removeFirst();
            history.addLast(ms);
            for (Consumer<Integer> l : listeners) l.accept(ms);
        });
    }

    public static List<Integer> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public static void addListener(Consumer<Integer> l) {
        listeners.add(l);
    }

    public static void removeListener(Consumer<Integer> l) {
        listeners.remove(l);
    }
}
