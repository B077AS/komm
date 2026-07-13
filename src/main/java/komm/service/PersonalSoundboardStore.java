package komm.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import komm.Launcher;
import komm.api.json.GsonProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Manages personal soundboard sounds stored locally under
 * {@code data/soundboards/personal/}. A JSON index ({@code index.json})
 * tracks metadata; audio files are stored beside it as {@code {uuid}.ext}.
 */
@Slf4j
public final class PersonalSoundboardStore {

    private static final PersonalSoundboardStore INSTANCE = new PersonalSoundboardStore();

    private final Gson gson = GsonProvider.get();
    private final Object lock = new Object();

    private PersonalSoundboardStore() {
    }

    public static PersonalSoundboardStore getInstance() {
        return INSTANCE;
    }

    private Path dir() {
        return Launcher.getSoundboardDirectory().resolve("personal");
    }

    private Path indexFile() {
        return dir().resolve("index.json");
    }

    /**
     * Filename used on disk: {@code {uuid}.ext} derived from the original fileName.
     */
    private String storedName(PersonalSoundboardEntry e) {
        String ext = FilenameUtils.getExtension(e.getFileName());
        return e.getId().toString() + (ext.isEmpty() ? "" : "." + ext);
    }

    private Path storedPath(PersonalSoundboardEntry e) {
        return dir().resolve(storedName(e));
    }

    public List<PersonalSoundboardEntry> list() {
        try {
            Path f = indexFile();
            if (!Files.exists(f)) return new ArrayList<>();
            String json = Files.readString(f, StandardCharsets.UTF_8);
            List<PersonalSoundboardEntry> entries = gson.fromJson(json,
                    new TypeToken<List<PersonalSoundboardEntry>>() {
                    }.getType());
            return entries != null ? entries : new ArrayList<>();
        } catch (Exception e) {
            log.warn("[PersonalSoundboard] Failed to read index: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public PersonalSoundboardEntry add(int slotIndex, String name, String emoji, String fileName,
                                       String fileType, byte[] bytes) throws Exception {
        synchronized (lock) {
            List<PersonalSoundboardEntry> entries = list();
            entries.stream().filter(e -> e.getSlotIndex() == slotIndex).findFirst()
                    .ifPresent(old -> {
                        try {
                            Files.deleteIfExists(storedPath(old));
                        } catch (Exception ignored) {
                        }
                    });
            entries.removeIf(e -> e.getSlotIndex() == slotIndex);

            UUID id = UUID.randomUUID();
            PersonalSoundboardEntry entry = PersonalSoundboardEntry.builder()
                    .id(id).slotIndex(slotIndex).name(name).emoji(emoji)
                    .fileName(fileName).fileType(fileType).fileSize(bytes.length)
                    .build();
            entries.add(entry);
            Files.createDirectories(dir());
            Files.write(storedPath(entry), bytes);
            saveIndex(entries);
            return entry;
        }
    }

    /**
     * Updates a sound's name/emoji and optionally replaces its audio file
     * ({@code bytes == null} keeps the current file). The id stays stable —
     * personal files are resolved from disk on every play, never cached by id.
     */
    public PersonalSoundboardEntry update(UUID id, String name, String emoji,
                                          String fileName, String fileType, byte[] bytes) throws Exception {
        synchronized (lock) {
            List<PersonalSoundboardEntry> entries = list();
            PersonalSoundboardEntry entry = entries.stream()
                    .filter(e -> e.getId().equals(id)).findFirst()
                    .orElseThrow(() -> new Exception("Sound not found"));

            if (name != null && !name.isBlank()) entry.setName(name.trim());
            entry.setEmoji(emoji != null && !emoji.isBlank() ? emoji.trim() : null);

            if (bytes != null) {
                // The stored filename derives from the original extension, so remove
                // the old file before the extension (and stored name) can change.
                Files.deleteIfExists(storedPath(entry));
                if (fileName != null) entry.setFileName(fileName);
                if (fileType != null) entry.setFileType(fileType);
                entry.setFileSize(bytes.length);
                Files.createDirectories(dir());
                Files.write(storedPath(entry), bytes);
            }
            saveIndex(entries);
            return entry;
        }
    }

    public void delete(UUID id) throws Exception {
        synchronized (lock) {
            List<PersonalSoundboardEntry> entries = list();
            entries.stream().filter(e -> e.getId().equals(id)).findFirst()
                    .ifPresent(e -> {
                        try {
                            Files.deleteIfExists(storedPath(e));
                        } catch (Exception ignored) {
                        }
                    });
            entries.removeIf(e -> e.getId().equals(id));
            saveIndex(entries);
        }
    }

    /**
     * Returns the local path for this id, or {@code null} if not a personal sound.
     */
    public Path getFileById(UUID id) {
        return list().stream()
                .filter(e -> e.getId().equals(id))
                .findFirst()
                .map(this::storedPath)
                .filter(Files::exists)
                .orElse(null);
    }

    // ── Import / Export ──────────────────────────────────────────────────────────

    public void exportZip(Path targetZip) throws Exception {
        List<PersonalSoundboardEntry> entries = list();
        Files.createDirectories(targetZip.getParent() == null ? Path.of(".") : targetZip.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip))) {
            byte[] indexBytes = gson.toJson(entries).getBytes(StandardCharsets.UTF_8);
            zos.putNextEntry(new ZipEntry("index.json"));
            zos.write(indexBytes);
            zos.closeEntry();
            for (PersonalSoundboardEntry entry : entries) {
                Path file = storedPath(entry);
                if (Files.exists(file)) {
                    zos.putNextEntry(new ZipEntry(storedName(entry)));
                    zos.write(Files.readAllBytes(file));
                    zos.closeEntry();
                }
            }
        }
        log.info("[PersonalSoundboard] Exported {} sounds to {}", entries.size(), targetZip);
    }

    /**
     * Imports sounds from a ZIP, merging by slot (imported slot overrides local).
     */
    public void importZip(Path zipFile) throws Exception {
        Map<String, byte[]> fileMap = new HashMap<>();
        byte[] indexBytes = null;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) != -1) baos.write(buf, 0, n);
                if ("index.json".equals(entry.getName())) {
                    indexBytes = baos.toByteArray();
                } else {
                    fileMap.put(entry.getName(), baos.toByteArray());
                }
                zis.closeEntry();
            }
        }

        if (indexBytes == null) throw new Exception("Invalid soundboard ZIP: missing index.json");
        List<PersonalSoundboardEntry> imported = gson.fromJson(
                new String(indexBytes, StandardCharsets.UTF_8),
                new TypeToken<List<PersonalSoundboardEntry>>() {
                }.getType());
        if (imported == null || imported.isEmpty()) return;

        synchronized (lock) {
            List<PersonalSoundboardEntry> existing = list();
            Files.createDirectories(dir());
            int count = 0;
            for (PersonalSoundboardEntry imp : imported) {
                // Zip entries are stored as {uuid}.ext — look up by that name
                String ext = FilenameUtils.getExtension(imp.getFileName());
                String zipName = imp.getId().toString() + (ext.isEmpty() ? "" : "." + ext);
                byte[] bytes = fileMap.get(zipName);
                if (bytes == null) continue;

                UUID newId = UUID.randomUUID();
                existing.stream().filter(e -> e.getSlotIndex() == imp.getSlotIndex()).findFirst()
                        .ifPresent(old -> {
                            try {
                                Files.deleteIfExists(storedPath(old));
                            } catch (Exception ignored) {
                            }
                        });
                existing.removeIf(e -> e.getSlotIndex() == imp.getSlotIndex());

                PersonalSoundboardEntry newEntry = PersonalSoundboardEntry.builder()
                        .id(newId).slotIndex(imp.getSlotIndex()).name(imp.getName())
                        .fileName(imp.getFileName()).fileType(imp.getFileType()).fileSize(bytes.length)
                        .build();
                existing.add(newEntry);
                Files.write(storedPath(newEntry), bytes);
                count++;
            }
            saveIndex(existing);
            log.info("[PersonalSoundboard] Imported {} sounds from {}", count, zipFile);
        }
    }

    private void saveIndex(List<PersonalSoundboardEntry> entries) throws Exception {
        Files.createDirectories(dir());
        Files.writeString(indexFile(), gson.toJson(entries), StandardCharsets.UTF_8);
    }
}
