<h1 align="center">
  <img src="src/main/resources/icon.png" alt="Komm icon" width="80"><br>
  komm
</h1>

<p align="center">
  <b>The desktop client for <a href="https://kommvoice.com">Komm</a> - a free, self-hosted voice, video &amp; text chat platform.</b><br>
  Voice &amp; video · HD screen sharing · Rich messaging · Soundboards · Global hotkeys · Windows &amp; Linux
</p>

<p align="center">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white">
  <img alt="JavaFX 27" src="https://img.shields.io/badge/JavaFX-27-1B6AC6">
  <img alt="WebRTC" src="https://img.shields.io/badge/WebRTC-native-333333?logo=webrtc&logoColor=white">
  <img alt="Windows | Linux" src="https://img.shields.io/badge/Windows%20%7C%20Linux-X11%20%2B%20Wayland-4CAF50">
  <img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-blue">
</p>

---

## What is Komm?

Komm is a modern chat platform built around a simple idea: **your community's messages and voice traffic belong on hardware you control.** Every community runs on its own self-hosted server - crystal-clear WebRTC voice channels, HD screen sharing, rich messaging, soundboards, roles & permissions, moderation tools and global hotkeys - without handing your conversations to anyone else. Free, no ads, no tracking, on Windows 10/11 and Linux (both X11 and Wayland, with native PipeWire support).

The platform has three pieces - you choose how many to run:

| Piece | Role | Who runs it |
|---|---|---|
| **komm** (this repo: the client + its launcher in [`launcher/`](launcher/)) | Desktop client for Windows & Linux, kept up to date by the launcher | Everyone |
| [komm-server](https://github.com/B077AS/komm-server) | A community's own server: channels, messages, voice rooms, permissions. One JAR, embedded database | Community owners |
| [komm-hub](https://github.com/B077AS/komm-hub) | The network's directory: accounts, friends, DMs, and the CA that vouches for servers | Almost nobody - most people use [kommvoice.com](https://kommvoice.com) |

This repo is the app everyone actually looks at: a native-feeling JavaFX desktop client that talks to the hub for your account, friends and DMs, and connects **directly** to each community's own server for chat and voice. Messages and voice never pass through the hub. It also contains the [launcher](launcher/) - the small auto-updater that the Windows installer and Linux AppImage are built from.

> 📥 **Just want to use Komm?** Don't build this repo - grab the [launcher](https://kommvoice.com/download) (Windows installer / Linux AppImage). It installs the client and keeps it up to date automatically, every time you start the app.

## Features

- **Crystal-clear voice** - low-latency WebRTC voice channels with a custom DSP pipeline: echo cancellation (AEC3), RNNoise noise suppression, automatic gain control and WebRTC voice activity detection (see [The audio pipeline](#the-audio-pipeline))
- **HD screen sharing with system audio** - multiple people can stream at once; viewers can pop streams out into separate windows, with live viewer counts. Encoded on your graphics card where possible (NVENC / Media Foundation / VA-API H.264, CPU VP8/VP9 as fallback or by choice), and only the streams you're actually watching are sent to you. System audio is captured natively - WASAPI loopback on Windows, PipeWire on Linux
- **Video** - camera support in voice channels
- **Rich text chat** - channels and DMs with editing, deletion, emoji reactions, typing indicators, GIF search, file attachments and unread/read state
- **Code snippets** - dedicated code blocks with automatic language detection and syntax highlighting (Java, JavaScript, Python, Go, HTML, CSS)
- **Soundboards** - server-wide soundboards plus your own personal one, triggered by click or global hotkey
- **Friends & DMs** - friend requests, direct messages and pokes, delivered over the hub WebSocket wherever you are
- **Roles & permissions** - the client renders custom roles and enforces fine-grained per-channel permissions with bitmask speed
- **Moderation tools** - kick, ban, server mute/deafen, move members between voice channels - right from the member list
- **Global hotkeys** - mute, deafen and soundboard triggers work system-wide (JNativeHook), even while you're in a game. Fully rebindable
- **Connection insight** - live ping graphs and packet-loss tracking for you and everyone in your voice channel
- **Themes** - light and dark themes (AtlantaFX), with Windows system-theme detection
- **Invite links** - `komm://` protocol handler registered on install, so invite links open straight in the app

## Architecture

**Trust bootstrap** (once, when a server is created) - the hub acts as a CA and is the only reason the diagram below can skip domain names and public certs entirely:

```
┌────────────────┐                                  ┌────────────────┐
│  Komm Server   │ ───────────────────────────────► │    komm-hub    │
│ (installation) │ ◄─────────────────────────────── │       CA       │
└────────────────┘                                  └────────────────┘
```

1. The server generates an EC P-384 keypair and a CSR (`CN=installationId`), and sends it with a one-time setup token from `POST /api/installations/create`.
2. The hub validates both, signs the CSR with its own CA key, and returns an X.509 certificate with **both `serverAuth` and `clientAuth` EKUs** - the same cert works as a TLS server certificate for clients and as a client certificate back to the hub.

**Runtime** (every session):

```           
┌─────────────┐     ticket + TLS      ┌───────────────┐
│   Client    │ ────────────────────► │  Komm Server  │  ← channels, messages,
│ (this repo) │ (CN-pinned to hub CA) │ (komm-server) │    voice, files, permissions
└─────┬───────┘                       └───────┬───────┘
      │ account, friends, DMs                 │ X.509 mutual auth (mTLS)
      ▼                                       ▼
┌──────────────────────────────────────────────┐
│              komm-hub  ·  CA                 │  ← accounts, friends, DMs,
│  accounts · directory · certificate signing  │    directory, website
└──────────────────────────────────────────────┘
```

**How the client joins a community server:**

1. You log in to the hub once - sessions are ES384-signed JWTs, and the refresh token is stored locally so the app signs you back in on startup.
2. When you open a community, the client asks the hub for a ticket. The hub verifies your membership, checks the server is online and its certificate isn't revoked, and issues a short-lived (**60 s**) single-purpose JWT ticket.
3. The client connects **directly** to that community's server with the ticket - over TLS: the server presents the certificate the hub issued it, and the client checks it belongs to exactly that installation by matching its `CN` against the hub's CA (fetched once per session from `GET /api/auth/ca`). The ticket is what authenticates the client, at the application layer. It then receives the server's own session tokens, and from that point the hub is out of the loop - every message and voice packet flows straight between you and the community's hardware.
4. Separately, the server keeps a **persistent, genuinely mutual TLS** connection to the hub (`/ws/installations`): both sides present the certificate from the bootstrap step above, so each authenticates the other. This is the channel the hub uses to proxy permission checks and moderation events through to an online installation.

### Two connections, one client

The `ServiceContainer` holds two independent connection stacks:

- **`HubConnection`** - always available after login. HTTP client + token manager + services for your account, friends, DMs, server directory and GIF search, plus the hub WebSocket (`AppWebSocketClient`) for real-time friend/DM/status events.
- **`InstallationConnection`** - created when you enter a community server. A separate HTTP client and token manager for that server's REST API, plus its own WebSocket (`InstallationWsClient`) for channel messages, voice presence, typing, reactions, permission changes and moderation events.

Both WebSockets speak the same envelope format - `{ "type": "WS_MESSAGE_TYPE", "payload": {...} }` - and dispatch each message type to a registered handler (70+ handlers under `websocket/handlers/`).

### Voice & media

Voice and video run on a dedicated daemon thread (`webrtc-mta-thread`) using native WebRTC ([webrtc-java](https://github.com/devopvoid/webrtc-java)) with LiveKit signaling against the SFU embedded in each komm-server. Joining or leaving a voice channel is a WebSocket message; media flows peer-to-SFU directly.

On Linux, screen sharing works natively on Wayland via webrtc-java's built-in PipeWire/xdg-desktop-portal support - no XWayland workarounds needed.

**Screen-share encoding.** The peer-connection factory uses webrtc-java's hardware-first encoder factory: for each codec the GPU encoder goes first where the machine has one, with the software encoder of the same codec as automatic fallback. The share modal's *Encoder* row (`VideoCodecChoice`) picks the codec and with it GPU vs CPU - **Auto** (default: GPU H.264 when available, else CPU VP8), **H.264 · GPU** / **AV1 · GPU** (only listed where this machine encodes them in hardware), **VP9 · CPU**, **VP8 · CPU**. The choice is applied as the screen-share transceiver's codec preference and declared to LiveKit in the `AddTrackRequest`; the log shows the encoder actually in use (`[ScreenShare] Encoder in use: …`). Decoding stays in software by default - GPU-decoded frames are copied back to RAM, which measured slower at stream sizes (opt in with `-Dkomm.video.hardwareDecode=true`); `-Dkomm.video.hardware=false` turns hardware codecs off entirely.

**Only watched streams are received.** Everyone in a voice channel is auto-subscribed to every track, so the client tells the SFU (`UpdateTrackSettings{disabled}`) to stop forwarding every screen share you aren't watching and to resume (with a fresh key frame) when you open it - no download or decode cost for streams you don't look at.

### The audio pipeline

Your microphone doesn't go straight to the network - every 20 ms frame runs through a DSP chain on its own processing thread:

```
mic ─► capture ─► AEC3 ─► RNNoise / WebRTC-NS ─► AGC2 ─► WebRTC VAD gate ─► WebRTC track ─► SFU
        (echo cancel)   (noise suppression)   (gain)   (speech gate)
```

- **WebRTC VAD** (webrtc-java's `StandaloneVoiceActivityDetector`) decides when you're actually speaking; it analyses pre-RNNoise audio, and the cleaned signal must carry real signal to open or hold the gate, which keeps keyboard clatter out while quiet voices stay in.
- **RNNoise** is the primary noise suppressor with WebRTC-NS as fallback, running inference asynchronously so the capture thread never stalls.
- On the receiving side, per-user jitter buffering with a prefill cushion keeps playback smooth even on spiky connections (particularly on Linux/PipeWire).
- Every stage can be toggled live from the audio settings, with a real-time mic activity meter.

## Under the hood

| Package | Responsibility |
|---|---|
| `api/` | HTTP layer: `HubConnection` / `InstallationConnection`, token managers, auth, Gson deserializers |
| `service/` | One class per API area: users, channels, messages, DMs, friends, members, invites, soundboards, permissions, GIFs |
| `websocket/` | Hub + installation WebSocket clients, message envelopes and the per-type handler registry |
| `webrtc/` | Voice/video room client, LiveKit signaling, screen share client, system-audio loopback capture |
| `webrtc/pipeline/` | The mic DSP chain (AEC, NS, AGC, VAD), soundboard mixing/decoding, per-user voice receivers |
| `ui/pages/` | Top-level pages: login, register, email verification, home (friends & DMs), server |
| `ui/sections/`, `ui/chat/` | The server layout: channel list · chat · member list, plus the DM experience |
| `ui/modals/` | Everything modal: user/server/channel/installation settings, invites, screen share picker, profiles |
| `ui/code/`, `ui/emojis/`, `ui/gifs/` | Code blocks (RSyntaxTextArea tokenizers + RichTextFX), emoji rendering & pickers, GIF search |
| `utils/` | App config, global hotkeys, audio device discovery, ping/packet-loss history, user settings |
| `update/` | `LauncherUpdateService` - applies staged launcher updates and catches up launchers that can't update themselves (see below) |
| `launcher/` (separate Maven project) | The auto-updating launcher: installer/AppImage packaging, client update + launch, shares the client's theme sources - see [launcher/README.md](launcher/README.md) |

A few rules keep the client sane:

- **`AppState` is the single source of truth** for mic/speaker/user status - UI components bind to its JavaFX properties and never touch WebRTC or the WebSocket directly.
- UI mutations happen on the FX thread (`Platform.runLater`); HTTP calls happen off it (virtual threads).
- `PermissionManager` mirrors the server's role/channel permission model with bitmask checks, updated in real time over WebSocket.

### Keeping the launcher up to date

Client and launcher are released together from this repo and share one version: every release attaches the client JAR and the launcher's self-update JARs. The launcher checks this repo's latest release for its own update on every start; the client's `update/LauncherUpdateService` backs that up:

- **Windows**: the launcher can't overwrite its own locked `app/komm-launcher.jar`, so it stages updates at `bin/komm-launcher.jar.pending`; the client moves that into place once it runs (the launcher has exited by then).
- **Catch-up**: the launcher forwards its version as `-Dlauncher.version`. If it differs from the client's own version - e.g. a launcher installed before the move from the old `komm-launcher` repo, which still polls that deleted repo - the client downloads the launcher JAR from its own release here (verified against GitHub's SHA-256 digest) and installs it: `app/komm-launcher.jar` on Windows, `bin/komm-launcher.jar` inside an AppImage (the launcher relaunches into it on its next start).
- Entirely best-effort and silent: any failure is logged and swallowed, since a launcher update must never interfere with the client running.

See [launcher/README.md](launcher/README.md#how-the-launcher-updates-itself) for the full mechanics.

## Getting the app (users)

Download the **launcher** from [kommvoice.com/download](https://kommvoice.com/download) - a Windows installer or Linux AppImage. It installs the client into your app data directory and updates it automatically on every start. Create an account, join a community via an invite link, done.

> Komm is currently in **closed beta** - registration on the official hub needs an invite key. [Request access](https://kommvoice.com/#beta-access) from the website.

### Linux prerequisites

Komm relies on the standard PipeWire audio stack. Most modern desktop distros (Fedora, Ubuntu 22.04+, Arch with a desktop environment) ship all of this out of the box - but on a minimal install make sure the following are present:

| Component | Why Komm needs it |
|---|---|
| `pipewire` | The audio/video server itself - voice, playback and screen capture all run through it |
| `wireplumber` | PipeWire's session manager - without it PipeWire routes nothing |
| `pipewire-pulse` | PulseAudio compatibility - Komm captures system audio for screen sharing through the PulseAudio API |
| `pipewire-alsa` | ALSA routing - Java's audio (mic capture & voice playback) reaches PipeWire through the ALSA layer |
| `alsa-utils` | ALSA utilities so audio devices are properly set up and visible |
| `pulseaudio-utils` / `libpulse` | Provides `pactl`, used to set up the screen-share audio tap |
| PipeWire CLI tools (`pw-dump`, `pw-link`) | Used to wire other apps' audio into the screen-share stream - part of `pipewire` on Arch, `pipewire-bin` on Debian/Ubuntu, `pipewire-utils` on Fedora |

For example, on Arch:

```bash
sudo pacman -S --needed pipewire wireplumber pipewire-pulse pipewire-alsa alsa-utils libpulse
```

or Debian/Ubuntu:

```bash
sudo apt install pipewire wireplumber pipewire-pulse pipewire-alsa alsa-utils pulseaudio-utils
```

If some of these are missing, the app still runs - but you may end up with no audio devices, or screen sharing without system audio (the client logs a warning telling you which tool it couldn't find). For screen sharing on Wayland you'll also want `xdg-desktop-portal` with the backend for your desktop (GNOME/KDE ship it by default).

## Building from source (developers)

Requirements: **Java 25** and Maven.

```bash
# Run in development
mvn javafx:run

# Point at a different hub without editing app.properties
mvn javafx:run -Dapi.url=https://kommvoice.com

# Build a fat JAR for distribution (bundles Windows + Linux JavaFX natives)
mvn clean package -Ppackage

# Production fat JAR - same, but baked for the official hub (https://kommvoice.com)
mvn clean package -Ppackage,prod

# The launcher (separate Maven project in launcher/, see launcher/README.md)
mvn -f launcher/pom.xml javafx:run -Dlauncher.demo=true       # visual demo, no network
mvn -f launcher/pom.xml clean package -Pinstaller             # Windows installer (needs the client jar above)
mvn -f launcher/pom.xml clean package -Pappimage              # Linux AppImage (needs the client jar above)
```

`mvn javafx:run` starts the client with the same memory options the launcher uses for the packaged client (`launcher/.../update/ClientJvmOptions.java`): small initial heap with a 2 GB ceiling, periodic G1 GC with tight heap free ratios so idle memory goes back to the OS, two GC threads and capped direct memory.

Releases are built by `.github/workflows/release.yml`: publishing a GitHub release builds the client jar, the launcher's self-update jars, the Windows installer and the AppImage, all versioned from the tag, and attaches them to that release.

By default the client points at a hub on `localhost` - the pom's `hub.url` / `hub.ws.url` properties are baked into `app.properties` at build time, and the `prod` profile switches them to the official hub. To bake any other hub: `-Dhub.url=https://my-hub -Dhub.ws.url=wss://my-hub/ws`. The production URLs end up **only inside the jar**: after packaging, the prod profile restores the localhost values in `target/classes`, so running the main class straight from your IDE after a prod build still targets your local hub.

For dev runs, a `-Dapi.url=` override wins over whatever is baked, no rebuild needed - the hub's WebSocket endpoint is derived from it automatically (`http → ws`, `https → wss`, plus `/ws`). Pass `-Dwebsocket.url=` as well only if your hub's WebSocket lives somewhere non-standard.

To develop end-to-end, run a local [komm-hub](https://github.com/B077AS/komm-hub) (and a [komm-server](https://github.com/B077AS/komm-server) registered against it) - or point at the official hub with the override above.

On first launch the client creates its app data directory - `%APPDATA%\Komm` on Windows, `~/.config/Komm` on Linux - where it keeps your settings and credentials (the refresh token lives under `config/credentials`).

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 25 |
| UI | JavaFX 27, [AtlantaFX](https://github.com/mkpaz/atlantafx) 3.0 themes and custom title bar, Ikonli icon packs, RichTextFX, emojifx |
| Voice / video | [webrtc-java](https://github.com/devopvoid/webrtc-java) 1.0.0 (hardware-first video encoders: NVENC, Media Foundation, VA-API), LiveKit signaling |
| Audio DSP | RNNoise (rnnoise4j), WebRTC VAD (webrtc-java), WebRTC AEC3/AGC2, mp3spi |
| System integration | JNA / JNA Platform (WASAPI loopback, PipeWire patch bay), JNativeHook (global hotkeys) |
| Networking | Java `HttpClient`, Tyrus WebSocket client, Spring WebSocket/messaging (client-side) |
| Code highlighting | RSyntaxTextArea's `TokenMaker` parsers, used headlessly (Java, JavaScript, Python, Go, HTML, CSS) |
| Serialization / crypto | Gson, BouncyCastle (CSR generation for hosting installations in-app), hub-CA TLS trust for community-server connections |
| Build | Maven - `javafx-maven-plugin` for dev, `maven-shade-plugin` for the distributable fat JAR; the launcher adds jpackage + jlink, Inno Setup 6 and appimagetool |

## Related repositories

| Repo | What it is |
|---|---|
| komm | This repo - desktop client (JavaFX, Windows & Linux) and its auto-updating launcher ([`launcher/`](launcher/): Windows installer & Linux AppImage) |
| [komm-server](https://github.com/B077AS/komm-server) | Self-hosted community server (single JAR, embedded database) |
| [komm-hub](https://github.com/B077AS/komm-hub) | Accounts, friends & DMs, server directory, CA, and the kommvoice.com website |

## FAQ

**Is Komm really free?** Yes - the client, launcher, server and hub are all free. No ads, no tracking, no paid tiers.

**Do I need to host anything?** No. Install the launcher, create an account and join communities via invite links. Hosting your own server is optional - it's for communities that want full ownership of their data.

**Does it really work on Wayland?** Yes - screen capture goes through the PipeWire portal (xdg-desktop-portal) via a patched native WebRTC build, and system-audio capture uses PipeWire directly. No XWayland tricks.

**Can the hub read my community's messages?** No. After a one-time 60-second ticket exchange, the client talks directly to the community's server - messages, voice and files never pass through the hub.

**How do updates work?** The launcher checks this repo's GitHub releases on every start and swaps in the new client JAR automatically; it updates itself from the same releases, and the client backs that up (see [Keeping the launcher up to date](#keeping-the-launcher-up-to-date)). No hub involved in either direction - install once, forget about it.

**Does screen sharing use my GPU?** Yes, by default: *Auto* encodes with your graphics card's H.264 encoder where there is one and falls back to the CPU (VP8) otherwise. You can pick the encoder yourself in the share dialog's *Quality* tab.

## License

This project is licensed under the [MIT License](LICENSE).
