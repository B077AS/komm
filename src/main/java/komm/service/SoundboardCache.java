package komm.service;

import komm.model.dto.summary.SoundboardSummary;
import lombok.Setter;

import java.util.List;

/**
 * Holds the latest known list of server-wide soundboards for the active server,
 * kept current by {@code SoundboardUpdatedHandler} (WS push) and by the popup's
 * own fetch on open. The open popup registers an {@code onUpdate} callback so it
 * can refresh live when another member adds/removes a sound.
 */
public final class SoundboardCache {

    private static volatile List<SoundboardSummary> serverSoundboards = List.of();
    @Setter
    private static volatile Runnable onUpdate;

    private SoundboardCache() {
    }

    public static List<SoundboardSummary> getServer() {
        return serverSoundboards;
    }

    public static void setServer(List<SoundboardSummary> list) {
        serverSoundboards = list != null ? list : List.of();
        Runnable r = onUpdate;
        if (r != null) r.run();
    }
}
