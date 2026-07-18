package komm.utils;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
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
 *
 * <p>On Linux, JavaFX Media decodes mp3 via the system libavcodec and only knows a
 * fixed set of sonames — distros shipping a newer ffmpeg (7/8) leave it unable to
 * create a player at all. The first successful/failed {@link MediaPlayer} probe
 * decides the backend for the session: if JavaFX Media is unusable, the mp3s are
 * converted once to WAV (pure-Java mp3spi decoder) and played through Java Sound.
 */
@Slf4j
public final class NotificationSounds {

    public static final String MESSAGE_RECEIVED = "universfield-new-notification-010-352755.mp3";

    private static final String CLASSPATH_DIR = "/sounds";

    /** Players currently playing. A MediaPlayer referenced only by a local variable
     *  can be garbage-collected mid-playback, silencing the sound. */
    private static final Set<MediaPlayer> ACTIVE_PLAYERS = ConcurrentHashMap.newKeySet();

    /** null = not probed yet; probed lazily because it needs the FX toolkit running. */
    private static volatile Boolean jfxMediaUsable;

    private NotificationSounds() {}

    /**
     * Extracts every bundled sound not already present in data/sounds, then probes
     * JavaFX Media and pre-converts the mp3s to WAV if it is unusable. Call off the
     * FX thread, after the FX toolkit has started.
     */
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

        if (!isJfxMediaUsable()) {
            convertAllToWav();
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

            if (isJfxMediaUsable()) {
                playWithJfxMedia(file, fileName, volume);
            } else {
                // Conversion and playback both block; keep them off the caller
                // (usually FX) thread.
                Thread.ofVirtual().start(() -> {
                    Path wav = ensureWav(file);
                    if (wav != null) playWav(wav, volume);
                });
            }
        } catch (Exception e) {
            log.warn("Failed to play notification sound {}: {}", fileName, e.getMessage());
        }
    }

    private static void playWithJfxMedia(Path file, String fileName, double volume) {
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
    }

    /**
     * Probes JavaFX Media once by constructing a throwaway player. On Linux systems
     * without a libavcodec that JavaFX supports, the constructor throws
     * "Could not create player!" synchronously.
     */
    private static boolean isJfxMediaUsable() {
        Boolean usable = jfxMediaUsable;
        if (usable != null) return usable;
        synchronized (NotificationSounds.class) {
            if (jfxMediaUsable != null) return jfxMediaUsable;

            Path probe = Launcher.getSoundsDirectory().resolve(MESSAGE_RECEIVED);
            if (!Files.exists(probe)) extractOne(MESSAGE_RECEIVED, probe);
            if (!Files.exists(probe)) return true; // nothing to probe with; assume default backend

            try {
                new MediaPlayer(new Media(probe.toUri().toString())).dispose();
                jfxMediaUsable = true;
            } catch (Exception e) {
                log.info("JavaFX Media unusable ({}); notification sounds fall back to Java Sound WAV playback",
                        e.getMessage());
                jfxMediaUsable = false;
            }
            return jfxMediaUsable;
        }
    }

    /** Converts every extracted mp3 without a WAV sibling. Runs once at startup on affected systems. */
    private static void convertAllToWav() {
        try (Stream<Path> files = Files.list(Launcher.getSoundsDirectory())) {
            for (Path mp3 : (Iterable<Path>) files::iterator) {
                if (mp3.getFileName().toString().endsWith(".mp3")) ensureWav(mp3);
            }
        } catch (Exception e) {
            log.warn("Failed to convert notification sounds to WAV: {}", e.getMessage());
        }
    }

    /** Returns the WAV sibling of the given mp3, decoding it via mp3spi if not present yet. */
    private static Path ensureWav(Path mp3) {
        Path wav = mp3.resolveSibling(mp3.getFileName().toString().replaceFirst("\\.mp3$", ".wav"));
        if (Files.exists(wav)) return wav;
        try (AudioInputStream mp3In = AudioSystem.getAudioInputStream(mp3.toFile())) {
            AudioFormat src = mp3In.getFormat();
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(), 16, src.getChannels(),
                    src.getChannels() * 2, src.getSampleRate(), false);
            Path tmp = Files.createTempFile(wav.getParent(), wav.getFileName().toString(), ".tmp");
            try (AudioInputStream pcmIn = AudioSystem.getAudioInputStream(pcm, mp3In)) {
                AudioSystem.write(pcmIn, AudioFileFormat.Type.WAVE, tmp.toFile());
                Files.move(tmp, wav, StandardCopyOption.REPLACE_EXISTING);
                log.info("Converted notification sound {} to WAV", mp3.getFileName());
            } finally {
                Files.deleteIfExists(tmp);
            }
            return wav;
        } catch (Exception e) {
            log.warn("Failed to convert notification sound {} to WAV: {}", mp3.getFileName(), e.getMessage());
            return null;
        }
    }

    /** Blocking WAV playback through Java Sound. Call on a background thread. */
    private static void playWav(Path wav, double volume) {
        if (volume <= 0) return;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            AudioFormat format = in.getFormat();
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format);
                if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                    float db = (float) (20.0 * Math.log10(volume));
                    gain.setValue(Math.clamp(db, gain.getMinimum(), gain.getMaximum()));
                }
                line.start();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    line.write(buffer, 0, read);
                }
                line.drain();
            }
        } catch (Exception e) {
            log.warn("Failed to play notification sound {}: {}", wav.getFileName(), e.getMessage());
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
