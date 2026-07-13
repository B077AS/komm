package komm.webrtc.pipeline;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe lock-based ring buffer for float audio samples.
 * Decouples the real-time audio capture thread from the NS inference thread.
 * Ported from Java-vad reference project.
 */
public class RingBuffer {

    private final float[] buffer;
    private final int capacity;
    private int head      = 0;
    private int tail      = 0;
    private int available = 0;

    private final ReentrantLock lock     = new ReentrantLock();
    private final Condition     notEmpty = lock.newCondition();
    private final Condition     notFull  = lock.newCondition();

    public RingBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer   = new float[capacity];
    }

    /**
     * Write samples — non-blocking. Returns false (drops data) if buffer is full,
     * so the real-time audio thread is never blocked.
     */
    public boolean write(float[] data) {
        return write(data, 0, data.length);
    }

    public boolean write(float[] data, int offset, int length) {
        lock.lock();
        try {
            if (available + length > capacity) return false;
            for (int i = 0; i < length; i++) {
                buffer[tail] = data[offset + i];
                tail = (tail + 1) % capacity;
            }
            available += length;
            notEmpty.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Non-blocking read. Returns the number of samples actually read (may be less than length).
     */
    public int read(float[] dest, int offset, int length) {
        lock.lock();
        try {
            int toRead = Math.min(length, available);
            for (int i = 0; i < toRead; i++) {
                dest[offset + i] = buffer[head];
                head = (head + 1) % capacity;
            }
            available -= toRead;
            notFull.signalAll();
            return toRead;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocking read — waits up to {@code timeoutMs} for at least {@code length} samples.
     * Returns 0 on timeout.
     */
    public int readBlocking(float[] dest, int offset, int length, long timeoutMs)
            throws InterruptedException {
        lock.lock();
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (available < length) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return 0;
                notEmpty.awaitNanos(remaining * 1_000_000L);
            }
            return read(dest, offset, length);
        } finally {
            lock.unlock();
        }
    }

    public int available() {
        lock.lock();
        try { return available; }
        finally { lock.unlock(); }
    }

    public void clear() {
        lock.lock();
        try {
            head = tail = available = 0;
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
