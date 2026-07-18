package komm.utils;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import komm.Launcher;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Bundled notification sounds. JavaFX {@link Media} cannot read jar: URLs, so the
 * mp3s shipped under /sounds on the classpath are extracted to the app data folder
 * ({@code data/sounds}) and always played from disk — same approach as the emoji
 * assets and the Silero VAD model.
 */
@Slf4j
public final class NotificationSounds {

    public static final String MESSAGE_RECEIVED = "universfield-new-notification-010-352755.mp3";

    private static final String CLASSPATH_DIR = "/sounds";

    /** Players currently playing. A MediaPlayer referenced only by a local variable
     *  can be garbage-collected mid-playback, silencing the sound. */
    private static final Set<MediaPlayer> ACTIVE_PLAYERS = ConcurrentHashMap.newKeySet();

    private NotificationSounds() {}

    /** Extracts every bundled sound not already present in data/sounds. Call off the FX thread. */
    public static void extractBundledSounds() {
        try {
            URI dirUri = NotificationSounds.class.getResource(CLASSPATH_DIR).toURI();
            if ("jar".equals(dirUri.getScheme())) {
                FileSystem fs;
                boolean closeFs = false;
                try {
                    fs = FileSystems.newFileSystem(dirUri, Map.of());
                    closeFs = true;
                } catch (FileSystemAlreadyExistsException e) {
                    fs = FileSystems.getFileSystem(dirUri);
                }
                try {
                    copyAll(fs.getPath(CLASSPATH_DIR));
                } finally {
                    if (closeFs) fs.close();
                }
            } else {
                copyAll(Paths.get(dirUri));
            }
        } catch (Exception e) {
            log.warn("Failed to extract bundled notification sounds: {}", e.getMessage());
        }
    }

    /** Plays a sound from data/sounds, extracting it from the jar first if missing. */
    public static void play(String fileName, double volume) {
        try {
            Path file = Launcher.getSoundsDirectory().resolve(fileName);
            if (!Files.exists(file)) {
                extractOne(fileName, file);
                if (!Files.exists(file)) return;
            }

            MediaPlayer player = new MediaPlayer(new Media(file.toUri().toString()));
            player.setVolume(volume);
            ACTIVE_PLAYERS.add(player);
            Runnable release = () -> {
                ACTIVE_PLAYERS.remove(player);
                player.dispose();
            };
            player.setOnEndOfMedia(release);
            player.setOnError(() -> {
                log.warn("Notification sound {} playback failed: {}", fileName,
                        player.getError() != null ? player.getError().getMessage() : "unknown error");
                release.run();
            });
            player.play();
        } catch (Exception e) {
            log.warn("Failed to play notification sound {}: {}", fileName, e.getMessage());
        }
    }

    private static void copyAll(Path sourceDir) throws Exception {
        Path targetDir = Launcher.getSoundsDirectory();
        Files.createDirectories(targetDir);
        try (Stream<Path> files = Files.list(sourceDir)) {
            for (Path src : (Iterable<Path>) files::iterator) {
                Path target = targetDir.resolve(src.getFileName().toString());
                if (!Files.exists(target)) {
                    Files.copy(src, target);
                    log.info("Extracted notification sound {}", target.getFileName());
                }
            }
        }
    }

    private static void extractOne(String fileName, Path target) {
        try (InputStream is = NotificationSounds.class.getResourceAsStream(CLASSPATH_DIR + "/" + fileName)) {
            if (is == null) {
                log.warn("Bundled notification sound not found on classpath: {}", fileName);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("Failed to extract notification sound {}: {}", fileName, e.getMessage());
        }
    }
}
