package komm.ui.utils;

import org.kordamp.ikonli.Ikon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IkonSearcher {

    private static volatile List<Ikon> ALL_ICONS = null;
    private static volatile List<Ikon> SHUFFLED_ICONS = null;
    private static final Object LOCK = new Object();

    private static final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ikon-loader");
        t.setDaemon(true);
        return t;
    });

    static {
        loader.submit(IkonSearcher::ensureLoaded);
    }

    private static void ensureLoaded() {
        if (ALL_ICONS != null) return;
        synchronized (LOCK) {
            if (ALL_ICONS != null) return;
            List<Ikon> icons = new ArrayList<>(6000);
            for (char c = 'A'; c <= 'Z'; c++) {
                try {
                    Class<?> cls = Class.forName("org.kordamp.ikonli.materialdesign2.MaterialDesign" + c);
                    if (cls.isEnum()) {
                        for (Object constant : cls.getEnumConstants()) {
                            icons.add((Ikon) constant);
                        }
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }
            ALL_ICONS = Collections.unmodifiableList(icons);
            List<Ikon> shuffled = new ArrayList<>(icons);
            Collections.shuffle(shuffled, new Random());
            SHUFFLED_ICONS = Collections.unmodifiableList(shuffled);
        }
    }

    public static List<Ikon> search(String query, int maxResults) {
        ensureLoaded();
        List<Ikon> all = ALL_ICONS;
        if (all == null) return Collections.emptyList();

        if (query == null || query.isBlank()) {
            List<Ikon> shuffled = SHUFFLED_ICONS;
            if (shuffled == null) shuffled = all;
            return shuffled.subList(0, Math.min(maxResults, shuffled.size()));
        }

        String q = query.toLowerCase().trim().replace(" ", "_").replace("-", "_");
        List<Ikon> exact = new ArrayList<>();
        List<Ikon> startsWith = new ArrayList<>();
        List<Ikon> contains = new ArrayList<>();

        for (Ikon icon : all) {
            String name = ((Enum<?>) icon).name().toLowerCase();
            if (name.equals(q)) exact.add(icon);
            else if (name.startsWith(q)) startsWith.add(icon);
            else if (name.contains(q)) contains.add(icon);
        }

        List<Ikon> result = new ArrayList<>(exact.size() + startsWith.size() + contains.size());
        result.addAll(exact);
        result.addAll(startsWith);
        result.addAll(contains);
        return result.subList(0, Math.min(maxResults, result.size()));
    }

    /** Human-readable label for display (e.g. MICROPHONE_OUTLINE → "Microphone Outline") */
    public static String label(Ikon icon) {
        String name = ((Enum<?>) icon).name().replace('_', ' ').toLowerCase();
        if (name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
