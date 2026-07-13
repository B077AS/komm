package komm.webrtc.pipeline;

import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;

/**
 * Decodes a soundboard audio file (MP3 or WAV) into 48 kHz mono signed-16-bit
 * PCM samples, ready to be mixed into the WebRTC publisher track by
 * {@link SoundboardMixer}.
 * <p>
 * MP3 decoding is provided by the mp3spi SPI (jlayer + tritonus-share); the
 * resample / down-mix to the pipeline's 48 kHz mono format is handled by the
 * JDK's built-in PCM format converter.
 */
@Slf4j
public final class SoundboardDecoder {

    /**
     * Pipeline-native playback format: 48 kHz, 16-bit, mono, signed little-endian.
     */
    public static final AudioFormat TARGET =
            new AudioFormat(48_000f, 16, 1, true, false);

    private SoundboardDecoder() {
    }

    /**
     * Decode the given file to mono 48 kHz {@code short[]} PCM.
     *
     * @throws IOException if the file cannot be read or its format is unsupported
     */
    public static short[] decodeToPcm(File file) throws IOException {
        try (AudioInputStream src = AudioSystem.getAudioInputStream(file)) {
            AudioFormat srcFmt = src.getFormat();

            // Stage 1: ensure raw PCM_SIGNED 16-bit (decodes MPEG → PCM; widens 8/24-bit WAV).
            AudioInputStream pcm = src;
            if (srcFmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                    || srcFmt.getSampleSizeInBits() != 16) {
                float rate = srcFmt.getSampleRate() == AudioSystem.NOT_SPECIFIED
                        ? 44_100f : srcFmt.getSampleRate();
                int channels = srcFmt.getChannels() == AudioSystem.NOT_SPECIFIED
                        ? 2 : srcFmt.getChannels();
                AudioFormat pcmFmt = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels,
                        channels * 2, rate, false);
                pcm = AudioSystem.getAudioInputStream(pcmFmt, src);
            }

            // Stage 2: resample / down-mix to 48 kHz mono (JDK built-in converter).
            AudioInputStream out = matches(pcm.getFormat(), TARGET)
                    ? pcm : AudioSystem.getAudioInputStream(TARGET, pcm);

            byte[] bytes = out.readAllBytes();
            if (out != pcm) out.close();
            return AudioConverter.bytesToShort(bytes);

        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported audio format (only MP3 and WAV are supported)", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Could not decode audio: " + e.getMessage(), e);
        }
    }

    private static boolean matches(AudioFormat a, AudioFormat b) {
        return a.getEncoding().equals(b.getEncoding())
                && a.getSampleRate() == b.getSampleRate()
                && a.getSampleSizeInBits() == b.getSampleSizeInBits()
                && a.getChannels() == b.getChannels()
                && a.isBigEndian() == b.isBigEndian();
    }
}
