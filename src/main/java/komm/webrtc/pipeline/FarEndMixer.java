package komm.webrtc.pipeline;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Builds the echo-cancellation reference ("far-end") signal: the sum of everything
 * Komm is about to play out of the speakers.
 *
 * Each playback path (remote voice, remote soundboard, stream audio, local soundboard
 * monitor) owns a {@link Source} and writes the exact PCM it hands to its output line —
 * after the listener's volume — so the reference matches what the microphone can pick up.
 * The capture pipeline then pulls one 20 ms mix per captured frame via {@link #mixFrame}
 * and feeds it to AEC3's reverse stream.
 *
 * Writers run on WebRTC / playback threads, the reader on the pipeline thread; every
 * source is a small bounded FIFO so none of them can block or grow without limit. Only
 * 48 kHz 16-bit audio is accepted (that is what the headless ADM renders and what the
 * pipeline runs at); anything else is ignored rather than resampled.
 */
@Slf4j
public final class FarEndMixer {

    private static final int SAMPLE_RATE = 48_000;
    /** Largest backlog a source keeps (60 ms); older samples are dropped. */
    private static final int MAX_BUFFERED = SAMPLE_RATE * 60 / 1000;
    /**
     * Standing cushion a source must build before it is read, so the 10 ms writers and
     * the 20 ms reader (two unsynchronised clocks) don't leave gaps in the reference.
     */
    private static final int CUSHION = SAMPLE_RATE * 10 / 1000;

    private final CopyOnWriteArrayList<Source> sources = new CopyOnWriteArrayList<>();

    /** Registers a new playback path. Close it when that path stops playing for good. */
    public Source createSource() {
        Source s = new Source();
        sources.add(s);
        return s;
    }

    /**
     * Fills {@code out} with the saturated sum of the next {@code out.length} reference
     * samples of every source. Sources that haven't buffered enough yet contribute silence.
     */
    public void mixFrame(short[] out) {
        int n = out.length;
        int[] acc = new int[n];
        for (Source s : sources) s.drainInto(acc, n);
        for (int i = 0; i < n; i++) {
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, acc[i]));
        }
    }

    public final class Source implements AutoCloseable {
        private final short[] ring = new short[MAX_BUFFERED];
        private int head;   // index of the oldest sample
        private int size;
        private boolean primed;
        private volatile boolean closed;
        private boolean warned;

        /**
         * Appends little-endian 16-bit PCM. Multi-channel audio is down-mixed to mono.
         *
         * @param length number of valid bytes in {@code pcm}
         */
        public void write(byte[] pcm, int length, int bitsPerSample, int sampleRate, int channels) {
            if (closed) return;
            if (bitsPerSample != 16 || sampleRate != SAMPLE_RATE || channels < 1) {
                if (!warned) {
                    warned = true;
                    log.warn("[FarEnd] Ignoring unsupported playback format {}Hz {}bit {}ch — AEC loses this reference",
                            sampleRate, bitsPerSample, channels);
                }
                return;
            }
            int frames = length / (2 * channels);
            synchronized (this) {
                for (int f = 0; f < frames; f++) {
                    int sum = 0;
                    int base = f * channels * 2;
                    for (int c = 0; c < channels; c++) {
                        int i = base + c * 2;
                        sum += (short) ((pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8));
                    }
                    push((short) (sum / channels));
                }
            }
        }

        /** Appends mono 48 kHz samples. */
        public void write(short[] samples, int count) {
            if (closed) return;
            synchronized (this) {
                for (int i = 0; i < count; i++) push(samples[i]);
            }
        }

        private void push(short v) {
            if (size == ring.length) {          // full: drop the oldest sample
                head = (head + 1) % ring.length;
                size--;
            }
            ring[(head + size) % ring.length] = v;
            size++;
        }

        private synchronized void drainInto(int[] acc, int n) {
            if (!primed) {
                if (size < n + CUSHION) return;
                primed = true;
            }
            int take = Math.min(n, size);
            for (int i = 0; i < take; i++) {
                acc[i] += ring[(head + i) % ring.length];
            }
            head = (head + take) % ring.length;
            size -= take;
            if (take < n) primed = false;       // underrun: rebuild the cushion
        }

        @Override
        public void close() {
            closed = true;
            sources.remove(this);
        }
    }
}
