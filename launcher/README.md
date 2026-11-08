<h1 align="center">
  <img src="../src/main/resources/icon.png" alt="Komm icon" width="80"><br>
  Komm launcher
</h1>

<p align="center">
  <b>The auto-updating launcher for <a href="https://kommvoice.com">Komm</a>: Windows installer, Linux AppImage, zero-maintenance updates, <code>komm://</code> invite links.</b>
</p>

<p align="center">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white">
  <img alt="JavaFX 27" src="https://img.shields.io/badge/JavaFX-27-1B6AC6">
  <img alt="AtlantaFX 3" src="https://img.shields.io/badge/AtlantaFX-3.0-9580ff">
  <img alt="jpackage + jlink" src="https://img.shields.io/badge/jpackage-private%20runtime-555555">
</p>

---

This folder is the thing users download from [kommvoice.com/download](https://kommvoice.com/download): a small, Discord-style bootstrapper that installs the Komm client, keeps it up to date on every start, and then gets out of the way. It used to be the separate `komm-launcher` repository; it now lives here, next to the client it launches, and is released together with it (see [Releases](#releases)).

## What the launcher does

- **Checks for updates on every start**: asks GitHub for this repo's latest release and swaps in the new client JAR automatically, with a live progress bar
- **Works offline**: a bundled client seed makes the very first start work without internet; later, if GitHub is unreachable, the installed client launches as-is
- **Never breaks your install**: downloads are verified before they replace anything, and the swap is atomic (see [The update rules](#the-update-rules))
- **Starts the client lean**: passes the client's JVM memory options (small initial heap, periodic GC that returns memory, a couple of GC threads, capped direct memory) and on Linux `MALLOC_ARENA_MAX=2`, see `update/ClientJvmOptions`
- **Handles invite links**: registers the `komm://` URI scheme so browser links like `komm://invite/CODE` open the launcher, which forwards them to the client
- **Ships its own Java**: installer and AppImage carry a private jlink runtime shared by launcher and client, so users never install Java
- **Looks exactly like Komm**: it uses the client's own theme (see [Styling](#styling)); on Windows the client process even shows as **"Komm"** in Task Manager, not "Java Platform SE binary"

## How an update works

```
┌──────────────┐  1. GET api.github.com/repos/B077AS/komm/releases/latest  ┌──────────────┐
│   Launcher   │ ─────────────────────────────────────────────────────────►│    GitHub    │
│              │ ◄─────────────────────────────────────────────────────────│              │
└──────┬───────┘   { tag_name, assets: [{ name, url, digest }] }            └──────────────┘
       │
       │  2. stale/missing? stream komm-<version>.jar, verify against
       │     GitHub's own SHA-256 digest, atomically swap komm.jar
       ▼
┌──────────────┐
│ komm client  │  3. launched on the bundled runtime with the client JVM
│  (komm.jar)  │     options, deep-link args forwarded
└──────────────┘
```

1. **Check**: `GET api.github.com/repos/B077AS/komm/releases/latest` returns the release's tag and its assets, each with a `browser_download_url` and a `digest` GitHub computes itself. GitHub *is* the source of truth; no hub involved.
2. **Compare**: the tag (minus a leading `v`) against the `client.version` embedded in the installed JAR's `app.properties`. The JAR is **self-describing**, so there is no separate version file to drift out of sync.
3. **Download**: a missing or stale JAR streams to `komm.jar.download` with determinate progress, must be able to state its own version and match the `sha256:<hex>` digest GitHub reported, then atomically replaces `komm.jar`.
4. **Launch**: the client starts on the bundled runtime with the [client JVM options](#what-the-launcher-does) and any `komm://…` arguments, and the launcher closes.

### The update rules

- **Every update is mandatory.** Once GitHub has announced a newer release, a failed download shows an error; the launcher never falls back to a known-stale client.
- **Offline is fine.** Only when GitHub itself is unreachable (offline, GitHub down, rate-limited) does an already-installed client launch as-is.
- **A corrupt download never replaces a working install.**

## How the launcher updates itself

Launcher and client share one version: every release of this repo attaches the client JAR **and** the launcher's self-update JARs (`komm-launcher-windows.jar`, `komm-launcher-linux.jar`), built from `launcher/` with the release tag as `launcher.version`. Two paths keep an installed launcher current:

1. **The launcher checks itself** on every start, alongside the client check (`UpdateManager.checkAndStageLauncherUpdate`): if this repo's latest release tag differs from its own `launcher.version`, it downloads its per-OS JAR and verifies it against GitHub's digest.
   - *Windows*: a running JVM can't overwrite the jar it runs from, so the download is staged at `bin/komm-launcher.jar.pending`; the client moves it into `app/komm-launcher.jar` on start, after the launcher process has exited.
   - *Linux*: a running JVM keeps working off the old inode after its jar is atomically replaced (POSIX unlink-while-open), so the download goes straight to `bin/komm-launcher.jar` and `Launcher.main`'s `relaunchIfNewerLauncherStaged` relaunches into it on the next start.
2. **The client catches up launchers that can't update themselves** (the client's `update/LauncherUpdateService`). Launchers installed before the move to this repo look for updates in the old, deleted `komm-launcher` repo. Each one forwards its version to the client as `-Dlauncher.version`; when that differs from the client's own version, the client downloads the launcher JAR from **its own** release here and installs it the same way. After that one hop the launcher updates itself again.

Old installs whose jpackage `Komm.cfg` still points at a versioned jar name (`komm-launcher-0.0.1.jar`) are repointed to the fixed `komm-launcher.jar` on the first swap. Neither path touches the bundled Java runtime; a runtime change still requires reinstalling from the release page.

## Invite links (`komm://`)

Packaged builds register the `komm://` URI scheme on every start: per-user registry keys under `HKCU\Software\Classes\komm` on Windows (no admin rights), the AppImage's `.desktop` file (`MimeType=x-scheme-handler/komm`) on Linux. Clicking an invite starts the launcher, which runs its normal update flow and hands the URL to the client. A client started by the launcher doesn't overwrite that registration, so links always go through the update check.

## What's inside the installer / AppImage

```
Komm-Setup-<version>.exe
├─ Komm.exe                    the launcher itself
├─ komm-launcher.jar           launcher fat JAR
└─ runtime/                    private jlink runtime (Java 25 + JavaFX)
   └─ bin/Komm.exe             rebranded javaw.exe, the client's process

Komm-<version>-x86_64.AppImage
├─ Komm                        the launcher itself
├─ komm-launcher.jar           launcher fat JAR
├─ komm-client-seed.jar        the client version current at build time
└─ runtime/                    private jlink runtime (Java 25 + JavaFX)
```

- **Windows seeds the client at install time**: Inno Setup (`packaging/windows/komm.iss`) copies the client fat JAR into `%APPDATA%/Komm/bin/komm.jar`.
- **The AppImage seeds on first run**: `BundledClientSeeder` copies `komm-client-seed.jar` into the app-data directory when there's no `komm.jar` yet.
- **The private runtime is shared** by launcher and client. The launcher's own JVM runs with small, fixed options (Serial GC, 128 MB max heap, C1 only): inside an AppImage it stays alive, window-less, for the whole client session, because the FUSE mount with the runtime only lives as long as the process that started it (`appimage-mount-keeper`).
- **Task Manager branding (Windows)**: the build rewrites a copy of `javaw.exe` into `runtime/bin/Komm.exe` with [rcedit](https://github.com/electron/rcedit), and the launcher prefers it when starting the client.

## Styling

The launcher doesn't carry its own copy of the look; it uses the client's:

- **Same user-agent stylesheet**: `KommStylesheet` (stock AtlantaFX 3 Dracula patched in place by `komm-theme.css`) and its fonts, compiled and packaged straight from the client's `src/` via `build-helper-maven-plugin` plus a compiler `<includes>` list (`KommStylesheet`, `AppTheme`, `CustomDebugConfig`). Change the client theme and the launcher follows.
- **The user's accent colour**: the client mirrors the active accent into `config/accent`; the launcher applies the same `AppTheme` palette and draws the logo badge and pulse rings in it. Default purple until the client has run once.
- **`launcher.css`** only adds the card layout (6 px corners like the app's larger surfaces, 3 px on the progress bar), the status line and a flat, borderless close button; plain `ButtonSkin` instead of the client's notched button skin.

## Building from source

Requirements: **Java 25** and Maven. Run from the repo root (or `cd launcher` and drop `-f launcher/pom.xml`).

```bash
# Dev run: checks GitHub, downloads/updates the client jar, launches it
mvn -f launcher/pom.xml javafx:run

# Scripted visual demo: walks every phase with fake progress, no network or jar needed
mvn -f launcher/pom.xml javafx:run -Dlauncher.demo=true

# Point at a fork's releases without rebuilding
mvn -f launcher/pom.xml javafx:run -Dgithub.client.owner=you -Dgithub.client.repo=komm

# Fat jar with Windows + Linux JavaFX natives
mvn -f launcher/pom.xml clean package -Ppackage
```

### Windows installer

```bash
mvn clean package -Ppackage                     # the client fat jar → target/komm-<v>.jar
mvn -f launcher/pom.xml clean package -Pinstaller
# → launcher/target/installer/Komm-Setup-<client.version>.exe
```

Needs **Inno Setup 6** (`winget install JRSoftware.InnoSetup`; `-Discc.path=...` to override). jpackage assembles the app-image with the private runtime, a PowerShell step rebrands the client's `javaw.exe` copy with rcedit (auto-downloaded; `-Drcedit.path=...`), and Inno Setup wraps it in the branded wizard.

### Linux AppImage

```bash
mvn clean package -Ppackage
mvn -f launcher/pom.xml clean package -Pappimage
# → launcher/target/appimage/Komm-<client.version>-x86_64.AppImage
```

Needs [appimagetool](https://github.com/AppImage/appimagetool) (default `~/.local/bin/appimagetool`; `-Dappimagetool.path=...`). Both profiles default to the client jar in `../target/` and accept `-Dclient.version=0.0.2 -Dclient.jar=/path/komm.jar`.

### Self-update jars

```bash
mvn -f launcher/pom.xml clean package -Pwin-natives    # launcher/target/komm-launcher.jar, Windows natives
mvn -f launcher/pom.xml clean package -Plinux-natives  # launcher/target/komm-launcher.jar, Linux natives
```

## Releases

Built by the repo's single `.github/workflows/release.yml`. Publishing a GitHub release (tag e.g. `0.0.3`) builds, all with the tag's version: the client JAR, the two launcher self-update JARs, the Windows installer and the AppImage, and attaches them to that release. A manual run with an existing tag rebuilds only the launcher JARs and installers (for launcher-only fixes) against that release's client JAR.

## Under the hood

| Class | Responsibility |
|---|---|
| `Launcher` | Entry point: app-data dirs, logging, hands off to the UI; on Linux/AppImage relaunches into a self-staged update first |
| `LauncherApp` | The frameless JavaFX window: phases, progress bar, animations; closes on ✕ or Esc |
| `LauncherTheme` | Reads the user's accent (`config/accent`) and builds its palette stylesheet |
| `LauncherConfig` | Reads `app.properties` (`launcher.version`) |
| `update/UpdateManager` | Check → download → verify → install → launch for the client, plus the launcher's own update check |
| `update/ClientJvmOptions` | JVM options and environment the client is started with |
| `update/BundledClientSeeder` | Linux/AppImage first-run copy of the bundled client seed |
| `update/AppsFeaturesVersionUpdater` | Windows: keeps "Apps & Features" showing the current client version |
| `update/Phase` | The status phrases (`CHECKING`, `DOWNLOADING`, `INSTALLING`, `STARTING`, `UP_TO_DATE`, `DONE`) |
| `ProtocolRegistrar` | `komm://` scheme registration (Windows registry; packaged builds only) |
