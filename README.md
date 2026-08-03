# TailTune 0.4 — Realtime remote and SQLite cache

TailTune turns an Android phone into a headless music player controlled from an
iPhone browser. It connects to Navidrome through the Subsonic API, reads the
same server-side playlists shown in Substreamer, downloads selected playlists
for offline playback, and serves a realtime web remote from the Samsung.

## What changed in 0.4

- The HTTP server starts **before** contacting Navidrome, so the website opens immediately.
- Playlist and song metadata is cached in a local **SQLite database**.
- The initial page uses one fast `/api/bootstrap` request instead of waiting for several requests.
- Playback, queue, download and synchronization updates are pushed over **WebSocket**.
- The browser falls back to light polling if a WebSocket connection drops.
- JavaScript and CSS are cached by Safari with versioned URLs.
- Navidrome synchronization runs on a background executor.
- Existing 0.3 JSON offline metadata is migrated into SQLite automatically.
- Both home-Wi-Fi and Tailscale URLs are shown in the Android app when available.

## Architecture

```text
iPhone Safari / Home Screen shortcut
          |
          | HTTP commands + WebSocket events
          v
TailTune HTTP/WebSocket server on Samsung :8787
          |
          +---- SQLite cached library
          +---- Offline playlist downloader
          +---- Navidrome/Subsonic client
          +---- Media3 ExoPlayer and MediaSession
          |
          v
Samsung USB-C audio output
```

The iPhone never receives the audio stream. It sends small remote-control
messages. Audio is played by the Samsung and therefore goes to the Xiaomi USB-C
ANC earphones connected to it.

## Build and install

1. Open the project in Android Studio.
2. Let Gradle sync and install Android SDK 36 when prompted.
3. Select the Samsung wireless-ADB device.
4. Press **Run**.
5. Enter the same Navidrome URL, username and password used by Substreamer.
6. Press **Save and start web remote**.
7. Open the displayed home-Wi-Fi or Tailscale URL on the iPhone.

The local address normally resembles:

```text
http://192.168.1.2:8787
```

The Tailscale address normally resembles:

```text
http://100.x.x.x:8787
```

## Offline playlists

Tap **⇩** beside a playlist in the iPhone interface. TailTune downloads its own
copy because Android does not allow it to read Substreamer's private download
cache. A removable microSD app directory is preferred when Android exposes one.

Downloaded tracks remain playable if Navidrome is unavailable. TailTune keeps
one local copy of a song even when it belongs to several downloaded playlists.

## Updating an existing TailTune installation

Build from the same computer that built the previous debug APK, then run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Version 0.4 keeps the same package name and migrates the 0.3 offline JSON index
to SQLite. Do not uninstall first if you want to preserve settings and offline
files.

## Build an APK locally

```text
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Publish a GitHub release

```bash
cp app/build/outputs/apk/debug/app-debug.apk TailTune-v0.4.0-debug.apk

gh release create v0.4.0 TailTune-v0.4.0-debug.apk \
  --title "TailTune v0.4.0" \
  --notes "Adds an instant-start web server, SQLite library cache and realtime WebSocket updates."
```

## Security

The web remote currently has no application-level password. Use it only on a
trusted LAN or inside your private Tailscale tailnet. Do not expose port 8787
directly to the public internet.

Navidrome credentials remain stored in Android SharedPreferences in this MVP.
Encrypted credential storage is planned for a later release.

## Current limitations

- TailTune accesses Navidrome server-side playlists, not playlists stored only in Substreamer's private database.
- Playlist downloads run sequentially.
- A partially downloaded track restarts from the beginning.
- App-specific offline files are deleted when TailTune is uninstalled.
- The WebSocket carries state updates; commands continue to use the HTTP API.

## License

TailTune is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
