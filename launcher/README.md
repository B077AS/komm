<h1 align="center">
  <img src="../src/main/resources/icon.png" alt="Komm icon" width="80"><br>
  Komm launcher
</h1>

<p align="center">
  <b>The update window of <a href="https://kommvoice.com">Komm</a>, on top of <a href="https://github.com/B077AS/jpack">jpack</a>: Windows installer, Linux AppImage, zero-maintenance updates, <code>komm://</code> invite links.</b>
</p>

---

Komm is installed and updated with [jpack](https://github.com/B077AS/jpack). Users download a Windows installer (`Komm-Setup.exe`) or a Linux AppImage (`Komm.AppImage`) from [kommvoice.com/download](https://kommvoice.com/download). Everything that used to be hand-rolled here (downloading the client jar, swapping it, patching the launcher, the AppImage mount guard, seeding, rebranding `javaw.exe`) is now jpack's job. What is left in this folder is the **window**: a small, Discord-style card that shows "Checking for updates... / Downloading... / Starting Komm..." and the installer script.

## What a start looks like

```
Komm.exe  (jpack stub - the only program that starts processes)
   |  1. starts the "launcher" entry on the bundled runtime
   v
launcher (this folder)
   |  2. jpack.check()    - is the latest GitHub release different from what is installed?
   |  3. jpack.download() - only the parts whose hash changed, with progress for the bar
   |  4. jpack.startAfterExit("app"), then exits
   v
Komm.exe   5. swaps the downloaded files in (nothing is running, so nothing is locked), starts "app"
   v
komm client (komm.Launcher)
```

- **Two JVMs, on purpose.** The launcher's JVM must be gone before the files it is running from (jars, runtime) can be replaced, so the client cannot start inside it.
- **Everything is a part of one release**: Java runtime, client jar, launcher jar and the stub itself. The launcher is therefore updated by the same mechanism and is always the same version as the client. No self-update code, no "catch-up" path.
- **Every update is mandatory.** Once a newer release is known, a failed download shows an error and the launcher never starts the stale install. Only when the update server can't be asked at all (offline, GitHub down, no release published yet) does the installed version start as-is.
- **Atomic and safe.** Verified downloads, folder-rename swaps, automatic rollback of a release that can't start (all jpack).
- **Several windows** can be open; files are only swapped when the last one closes.
- **Arguments pass through.** `Komm.exe "komm://invite/CODE"` reaches the client (the launcher ignores it).

## Invite links (`komm://`)

On every start the launcher (`ProtocolRegistrar`) registers `komm://` to the file jpack names in `JPACK_EXE` - `Komm.exe`, or the AppImage file itself - so links always go through the update check and the client gets its JVM options:

- **Windows**: per-user registry keys under `HKCU\Software\Classes\komm` (no admin rights). The installer writes the same keys, so the handler exists before the first run.
- **Linux**: a per-user `~/.local/share/applications/komm-url-handler.desktop` (`MimeType=x-scheme-handler/komm`) plus `xdg-mime default`. (jpack's generated AppImage `.desktop` has no `MimeType`, and an AppImage has no installer to register one.)

A client started by the stub never re-registers itself (it would bypass the launcher).

## What's inside the install

```
Komm.exe  /  AppRun in the AppImage     the jpack stub
jpack.json                              what is installed (the manifest)
runtime/                                one jlink runtime for launcher AND client (modules found with jdeps)
  bin/Komm.exe                          javaw.exe renamed and branded, so Task Manager says "Komm" (Windows)
app/komm-<version>.jar                  the client fat jar
launcher/komm-launcher.jar              this project's fat jar (single-platform JavaFX natives)
```

JVM options live in [`../src/jpack/jpack.json`](../src/jpack/jpack.json): the client's (`-Xms32m -Xmx2g`, periodic G1 GC with tight free ratios, `ParallelGCThreads=2 ConcGCThreads=1`, `MaxDirectMemorySize=1g`, `UseCompactObjectHeaders`, native access; env `MALLOC_ARENA_MAX=2` for Linux) and the launcher's (Serial GC, 128 MB heap, C1 only). The root `pom.xml`'s `javafx:run` options mirror the client's - keep them in sync. Branding (icon, "Komm" file properties) comes from `src/jpack/icon.ico`; the AppImage icon from `src/jpack/icon.png`.

Per-user data (`%APPDATA%/Komm`, `~/.config/Komm`) is never touched by an update.

## Styling

The launcher doesn't carry its own copy of the look; it uses the client's:

- **Same user-agent stylesheet**: `KommStylesheet` (stock AtlantaFX 3 Dracula patched in place by `komm-theme.css`) and its fonts, compiled and packaged straight from the client's `src/` via `build-helper-maven-plugin` plus a compiler `<includes>` list (`KommStylesheet`, `AppTheme`, `CustomDebugConfig`). Change the client theme and the launcher follows.
- **The user's accent colour**: the client mirrors the active accent into `config/accent`; the launcher applies the same `AppTheme` palette and draws the logo badge and pulse rings in it. Default purple until the client has run once.
- **`launcher.css`** only adds the card layout, the status line and a flat, borderless close button; plain `ButtonSkin` instead of the client's notched button skin.

## Building

Requirements: **Java 25** (with `jmods`, i.e. a full JDK) and Maven. Windows installer additionally needs **Inno Setup 6** (`winget install JRSoftware.InnoSetup`), the AppImage needs [appimagetool](https://github.com/AppImage/appimagetool) (`APPIMAGETOOL=/path/to/appimagetool`). jpack builds for the machine it runs on, so build Windows on Windows and Linux on Linux.

```bash
# Launcher dev run: has no jpack stub, so it only previews the UI
mvn -f launcher/pom.xml javafx:run -Dlauncher.demo=true

# The launcher jar jpack packages (single-platform natives)
mvn -f launcher/pom.xml clean package -Pwin-natives      # or -Plinux-natives

# From the repo root: client fat jar + the whole install
mvn clean package -Ppackage,prod,jpack
# -> target/jpack/image/<platform>/   install folder (run Komm.exe from here to try it)
#    target/jpack/release/            every file an update is served from (+ the AppImage on Linux)

# Windows installer around that install folder
ISCC.exe /DAppVersion=<v> /DImageDir=<repo>\target\jpack\image\windows-x64 /DOutputDir=<repo>\target\installer launcher\packaging\windows\komm.iss
```

Testing against a fork or another location: `-Djpack.updateUrl=https://github.com/you/komm/releases/latest/download` at build time (the update address is baked into the install). Running an image you just built is safe to try, but note it registers `komm://` to that folder and (Windows) rewrites the installed version shown in Apps & Features; set `APPDATA` to a scratch folder to keep your real config out of it.

## Releases

Built by the repo's single `.github/workflows/release.yml`. Publishing a GitHub release (tag e.g. `0.0.3`) runs one job on Windows and one on Linux, each with the tag's version. They attach to the release: **all of `target/jpack/release/`** (manifest `jpack-<platform>.json`, the part zips, the AppImage and its `.blocks` index), plus `Komm-Setup-<v>.exe` / `Komm-<v>-x86_64.AppImage` and the stable aliases `Komm-Setup.exe` / `Komm.AppImage`. Installed copies read `releases/latest/download/`, so a release has to carry every file, changed or not, and the manifest goes up last. A manual run (Actions -> Run workflow, existing tag) rebuilds and replaces both platforms' assets.

The launcher's pom version is deliberately **not** bumped by releases and its jar has fixed timestamps: the same sources give the same bytes, so the launcher part keeps its hash and isn't downloaded again until the launcher really changes.

## Under the hood

| Class | Responsibility |
|---|---|
| `Launcher` | Entry point: app-data dirs, logging properties, protocol registration, hands off to the UI |
| `LauncherApp` | The frameless JavaFX window: phases, progress bar, animations; closes on X or Esc |
| `LauncherTheme` | Reads the user's accent (`config/accent`) and builds its palette stylesheet |
| `update/UpdateManager` | check -> download -> `startAfterExit("app")` through the `jpack-api` library, plus the scripted demo |
| `update/Phase` | The status phrases (`CHECKING`, `DOWNLOADING`, `INSTALLING`, `STARTING`, `UP_TO_DATE`, `DONE`) |
| `ProtocolRegistrar` | `komm://` scheme registration (Windows registry / Linux `.desktop` + `xdg-mime`) |
| `packaging/windows/komm.iss` | Inno Setup script wrapping the jpack install folder |
