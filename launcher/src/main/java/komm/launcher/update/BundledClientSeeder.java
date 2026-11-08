package komm.launcher.update;

import komm.launcher.Launcher;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;

/**
 * First-run seeding of the client jar bundled inside the AppImage.
 *
 * <p>Linux-only in practice: the AppImage ships {@code komm-client-seed.jar}
 * next to the launcher jar in the app directory, since an AppImage has no
 * separate install phase to seed {@code %APPDATA%/Komm/bin/komm.jar} from
 * directly. The Windows installer does that itself (see {@code komm.iss}'s
 * {@code [Files]} section) and never stages a seed jar for this class to
 * find, so this is a harmless no-op there.
 *
 * <p>If {@code %APPDATA%/Komm/bin/komm.jar} does not exist yet — i.e. a fresh
 * install — the seed is copied there so the first start works offline and
 * skips a full download. The jar carries its own version in its embedded
 * {@code app.properties}, so no version marker is needed. From then on the
 * normal {@link UpdateManager} flow owns the jar and replaces it with
 * whatever GitHub serves.
 *
 * <p>In dev runs (classes dir instead of a jar, no seed file) this is also a no-op.
 */
@Slf4j
public class BundledClientSeeder {

    /** Best-effort: on any failure the launcher just downloads from the hub as usual. */
    public static void seedIfMissing() {
        try {
            Path clientJar = Launcher.getClientJar();
            if (Files.exists(clientJar)) return;

            Path appDir = launcherJarDirectory();
            if (appDir == null) return;
            Path seedJar = appDir.resolve("komm-client-seed.jar");
            if (!Files.exists(seedJar)) return;

            Files.createDirectories(clientJar.getParent());
            // Copy via a temp name so a crash mid-copy never leaves a broken komm.jar.
            Path tmp = clientJar.resolveSibling("komm.jar.seed");
            Files.copy(seedJar, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, clientJar, StandardCopyOption.REPLACE_EXISTING);
            log.info("Seeded bundled client jar {} -> {}", seedJar, clientJar);
        } catch (Exception e) {
            log.warn("Could not seed bundled client jar: {}", e.toString());
        }
    }

    /** Directory containing the running launcher jar, or null in dev runs. */
    private static Path launcherJarDirectory() throws Exception {
        CodeSource source = Launcher.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) return null;
        Path location = Path.of(source.getLocation().toURI());
        return Files.isRegularFile(location) ? location.getParent() : null;
    }
}
