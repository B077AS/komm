package komm.media;

import komm.Launcher;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Size-capped on-disk cache for chat media (remote GIFs / images and image attachments).
 *
 * <p>It exists so the bytes do NOT have to live on the heap: a message cell that scrolls
 * out of view keeps nothing, and gets the file back from here (not from the network) when
 * it scrolls in again. Least-recently-used files are deleted once the cache passes
 * {@link #MAX_TOTAL_BYTES}. All methods block — call them off the FX thread.
 */
@Slf4j
public final class MediaStore {

    /** Largest single item that is downloaded / cached. */
    public static final long MAX_ITEM_BYTES = 48L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 384L * 1024 * 1024;
    private static final long TRIM_TO_BYTES = MAX_TOTAL_BYTES * 3 / 4;
    /** Don't rewrite a file's timestamp on every read. */
    private static final long TOUCH_GRANULARITY_MS = 60 * 60 * 1000L;

    private static final Path DIR = resolveDir();
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    /** Bytes on disk; -1 until the first scan. */
    private static final AtomicLong TOTAL = new AtomicLong(-1);

    private static volatile HttpClient http;

    private MediaStore() {
    }

    private static Path resolveDir() {
        String override = System.getProperty("komm.mediaCacheDir");
        Path dir = override != null ? Path.of(override) : Launcher.getDataDirectory().resolve("media-cache");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Media cache directory unavailable: {}", e.toString());
        }
        return dir;
    }

    /** @return the cached bytes for {@code key}, or null */
    public static byte[] get(String key) {
        Path f = fileFor(key);
        try {
            if (!Files.isRegularFile(f)) return null;
            byte[] bytes = Files.readAllBytes(f);
            touch(f);
            return bytes;
        } catch (IOException e) {
            return null;
        }
    }

    /** @return true if the bytes are now safely on disk */
    public static boolean put(String key, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ITEM_BYTES) return false;
        Path f = fileFor(key);
        try {
            if (Files.isRegularFile(f) && Files.size(f) == bytes.length) return true;
            Path tmp = Files.createTempFile(DIR, "dl-", ".tmp");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            account(bytes.length);
            return true;
        } catch (IOException e) {
            log.debug("Media cache write failed: {}", e.toString());
            return false;
        }
    }

    /**
     * The bytes behind an http(s) URL: from disk if cached, otherwise downloaded (once, even
     * when several cells ask for the same URL at the same time) and cached.
     */
    public static byte[] fetchUrl(String url) throws IOException {
        Path f = ensureUrlFile(url);
        if (f != null) {
            try {
                return Files.readAllBytes(f);
            } catch (IOException gone) {
                // trimmed between the check and the read: fall through to a plain download
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        download(url, out);
        return out.toByteArray();
    }

    /**
     * Like {@link #fetchUrl} but without putting the file on the heap: the cached file is
     * memory-mapped read-only. This is what lets a 20 MB GIF play at the cost of one canvas.
     * (A mapped file cannot be deleted on Windows until the buffer is collected; the trimmer
     * simply skips it until then.)
     */
    public static ByteBuffer openUrl(String url) throws IOException {
        Path f = ensureUrlFile(url);
        if (f != null) {
            try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
                long size = ch.size();
                if (size > 0 && size <= MAX_ITEM_BYTES) return ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
            } catch (IOException | RuntimeException unmappable) {
                // fall through to the heap
            }
        }
        return ByteBuffer.wrap(fetchUrl(url));
    }

    /** Makes sure the URL's bytes are in the cache directory; null if the directory can't be used. */
    private static Path ensureUrlFile(String url) throws IOException {
        String key = "url:" + url;
        Path f = fileFor(key);
        if (Files.isRegularFile(f)) {
            touch(f);
            return f;
        }
        Object lock = LOCKS.computeIfAbsent(key, k -> new Object());
        try {
            synchronized (lock) {
                if (Files.isRegularFile(f)) return f;
                Path tmp;
                try {
                    tmp = Files.createTempFile(DIR, "dl-", ".tmp");
                } catch (IOException noDisk) {
                    return null;
                }
                try {
                    long size;
                    try (OutputStream out = Files.newOutputStream(tmp)) {
                        size = download(url, out);
                    }
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                    account(size);
                    return f;
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
        } finally {
            LOCKS.remove(key, lock);
        }
    }

    /** Streams the URL into {@code out} (never holding the whole body on the heap); returns its size. */
    private static long download(String url, OutputStream out) throws IOException {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("bad URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("unsupported URL scheme");
        }
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 Komm")
                .header("Accept", "image/*,*/*;q=0.5")
                .GET().build();
        try {
            HttpResponse<InputStream> resp = client().send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
                long declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > MAX_ITEM_BYTES) throw new IOException("media too large");
                byte[] buf = new byte[32 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_ITEM_BYTES) throw new IOException("media too large");
                    out.write(buf, 0, n);
                }
                if (total == 0) throw new IOException("empty response");
                return total;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static HttpClient client() {
        HttpClient c = http;
        if (c == null) {
            synchronized (MediaStore.class) {
                c = http;
                if (c == null) {
                    http = c = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                }
            }
        }
        return c;
    }

    // ── bookkeeping ───────────────────────────────────────────────────────────

    private static Path fileFor(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return DIR.resolve(HexFormat.of().formatHex(h, 0, 20));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void touch(Path f) {
        try {
            long now = System.currentTimeMillis();
            if (now - Files.getLastModifiedTime(f).toMillis() > TOUCH_GRANULARITY_MS) {
                Files.setLastModifiedTime(f, FileTime.fromMillis(now));
            }
        } catch (IOException ignored) {
        }
    }

    private static void account(long added) {
        long total = TOTAL.get();
        if (total < 0) {
            trim(); // first write of the session: scan (and trim) once
            return;
        }
        if (TOTAL.addAndGet(added) > MAX_TOTAL_BYTES) trim();
    }

    private record Entry(Path path, long size, long modified) {
    }

    private static synchronized void trim() {
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    if (!a.isRegularFile()) continue;
                    if (p.getFileName().toString().endsWith(".tmp")) {
                        // leftover from a crashed write
                        if (System.currentTimeMillis() - a.lastModifiedTime().toMillis() > 3_600_000L) {
                            Files.deleteIfExists(p);
                        }
                        continue;
                    }
                    entries.add(new Entry(p, a.size(), a.lastModifiedTime().toMillis()));
                    total += a.size();
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            TOTAL.set(0);
            return;
        }
        if (total > MAX_TOTAL_BYTES) {
            entries.sort(Comparator.comparingLong(Entry::modified));
            for (Entry e : entries) {
                if (total <= TRIM_TO_BYTES) break;
                try {
                    Files.deleteIfExists(e.path());
                    total -= e.size();
                } catch (IOException ignored) {
                }
            }
        }
        TOTAL.set(total);
    }
}
