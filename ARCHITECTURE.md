# TailTune 0.6 architecture

## Design goals

1. The remote must remain reachable while playback is paused.
2. Cached/offline music must work when Navidrome and the internet are unavailable.
3. Android lifecycle threads must not perform database/network/recursive-storage work.
4. A dead socket, dead playback binding, network change, or process recreation must have a bounded recovery path.
5. A client must not be able to grow queues/memory without bound.

## Android services

```text
RemoteServerService (Service)
  foreground type: connectedDevice
  START_STICKY
  owns TailTuneServer + Bonjour
  │
  │ local binding
  ▼
PlaybackService (MediaSessionService)
  foreground type: mediaPlayback when Media3 requires it
  owns ExoPlayer + MediaSession
  owns OfflineStore/LibrarySync/Downloads
```

The separation is deliberate. The network listener no longer depends on Media3's paused/stopped playback foreground lifecycle.

## RemoteServerService recovery state machine

- Promotes to foreground immediately in `onCreate()`.
- Binds to `PlaybackService` using `BIND_AUTO_CREATE`.
- Polls every 500 ms while playback components initialize off-main-thread.
- Drops/retries failed or dead bindings with bounded backoff.
- Starts NanoWSD only after playback/cache components are ready.
- Uses a generation counter so a stale asynchronous listener-start cannot attach after stop/reload.
- Checks the listener every 20 seconds and restarts it if `isAlive` becomes false.
- Refreshes Bonjour advertisement after default-network changes.
- Explicit Stop disables sticky/boot recovery.

## Thread/executor ownership

| Thread/executor | Responsibility |
|---|---|
| Android main | Activity widgets, Service lifecycle, lightweight notification/binding callbacks |
| `TailTune-PlaybackInit` | OfflineStore/Keystore/client initialization and configuration reload |
| `TailTune-Player` | all ExoPlayer calls/state serialization |
| `TailTune-LibrarySync` | Navidrome summaries/details |
| `TailTune-Downloads` | sequential resumable download/removal |
| `TailTune-StorageMaintenance` | migration, reconciliation, capacity probes |
| `TailTune-RemoteServer` | token load + NanoWSD listener startup |
| NanoHTTPD workers | HTTP requests |
| two bounded WebSocket request workers | native protocol operations |
| `TailTune-WebSocketEvents` | coalesced server-push events |

## Cached-library path

```text
start
  → SQLite/cache initialization on TailTune-PlaybackInit
  → cached summaries available
  → one Navidrome getPlaylists request (when configured)
  → summaries updated in one transaction
  → playlist details loaded from SQLite first
  → stale details refresh in background
```

Normal startup does not make one network request per playlist.

## Playback path

```text
playlist song IDs
  → batched SQLite local-file query
  → reject missing/truncated offline files
  → local file URI where available
  → Navidrome stream URI only when remote path is usable
  → queue submitted to dedicated ExoPlayer looper
```

The web/native state event omits the full queue unless the queue revision changed.

## Download path

```text
<sha256(song-id)>.<ext>.part
  → optional HTTP Range resume
  → bounded buffered write
  → flush + fsync
  → exact-size check when known
  → atomic move (normal replacement fallback)
  → SQLite registration
```

Storage maintenance demotes invalid finalized files, preserves fresh resumable fragments, and reconciles index sizes without holding a global read lock.

## Remote protocol

Transport: token-authenticated WebSocket for the native iOS client; HTTP remains available to the browser fallback.

Server-push event types:

- `snapshot`
- `playback`
- `library`
- `downloads`

Native request operations:

- `snapshot`
- `playlist`
- `playPlaylist`
- `control`
- `queue`
- `sync`
- `download`
- `removeDownload`
- `ping`

See [PROTOCOL.md](PROTOCOL.md).

## Resource bounds

- authorized WebSocket clients: 8
- WebSocket message: 64 KiB
- HTTP JSON body: 64 KiB
- WebSocket request workers: 2
- pending WebSocket requests: 64
- API JSON response input from Navidrome: 8 MiB
- explicit network timeouts for API/download/client operations

## iOS controller

The iOS app uses:

- SwiftUI UI;
- `NWBrowser` Bonjour discovery for `_tailtune._tcp`;
- `NWConnection` + `NWProtocolWebSocket`;
- 12-second request timeout;
- 20-second heartbeat;
- capped 1–15 second reconnect backoff;
- generation checks to ignore callbacks from stale connections;
- Keychain token storage;
- manual host/Tailscale fallback.

If iOS suspends the app, `resume()` reconnects when the app becomes active again.

## Expected unrecoverable/intentional cases

No Android architecture can override an explicit user **Force stop**, app uninstall, device power-off, revoked required OS permission, or catastrophic storage/hardware failure. TailTune surfaces/retries recoverable failures and documents the cases that require user action.
