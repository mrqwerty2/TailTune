# Changelog

## 0.6.0 — split-service + native iOS controller

### Architecture

- Moved HTTP/WebSocket/Bonjour ownership into `RemoteServerService`, an independent `connectedDevice` foreground service.
- Restored `PlaybackService` to a media-focused `MediaSessionService`; a local binder connects the remote service to playback/cache state.
- Added sticky remote restart, explicit binding recovery, bind timeout, listener watchdog and stale-start generation guards.
- Moved OfflineStore/SQLite/Keystore initialization off Android's service lifecycle thread.

### Reliability and performance

- Added background initialization polling so slow microSD/database startup no longer causes a premature remote-ready failure.
- Added capped retry backoff for real failures and listener restart after unexpected NanoHTTPD death.
- Added bounded two-worker/64-pending WebSocket request execution to prevent an unbounded client work queue.
- Serialized per-socket sends and moved initial WebSocket snapshots off the socket-open callback.
- Added safe network-change Bonjour re-advertisement and Android multicast-lock management.
- Added durable download `flush` + `fsync` before final-file promotion.
- Increased Gradle wrapper network timeout to tolerate slower downloads.

### iPhone

- Added native SwiftUI TailTune Remote source for iOS 16+.
- Added Bonjour discovery, Network.framework WebSocket transport, 12-second request timeout, heartbeat and capped reconnect.
- Added generation guards for stale connection callbacks and bad-token reconnect suppression.
- Added Keychain token storage and manual LAN/Tailscale fallback.

### Verification

- Added static architecture/security checks and model-based lifecycle fault simulation.
- Added pure Kotlin retry/network/error-sanitizer smoke checks.
- Added Android and iOS CI quality gates plus a real-device torture-test procedure.

## 0.5.0 — audited reliability release

### Fixed

- Removed the process-wide `OfflineStore` monitor that caused 10–16 second lock contention and Android input-dispatch ANRs.
- Replaced recursive storage scans in live API snapshots with atomic cached counters.
- Kept SQLite and filesystem work off `MainActivity`'s UI thread.
- Prevented normal startup from fetching every playlist detail sequentially.
- Fixed idle WebSocket disconnects with browser heartbeats and a longer NanoHTTPD read timeout.
- Prevented MediaSessionService from demoting the explicitly enabled headless remote after playback stays paused/stopped.
- Prevented stale WebSocket callbacks from closing or overwriting a newer browser connection.
- Made service destruction non-blocking; executor joins, socket shutdown and database close now run on a cleanup thread.
- Prevented stale or truncated finalized audio from being treated as a valid offline track.
- Serialized browser mutations so quick taps are queued rather than silently dropped.
- Stopped immutable browser caching from serving old JavaScript after an APK update.

### Added

- Android Keystore encryption for the Navidrome password.
- Random per-install token authentication for HTTP and WebSocket routes.
- SQLite WAL, indexes, bounded transactions and corruption backup handling.
- Batched local-file lookup for large playlists.
- Lazy background playlist-detail refresh.
- HTTP range-resumable `.part` downloads, fsync and atomic promotion.
- Samsung battery reporting in the web remote.
- Foreground-service persistence, boot restart where Android allows it, and a user-visible Stop action in the Android screen.
- Tailscale RFC 6598 address detection and local/hotspot URL discovery.
- Request-size limits, strict JSON types, metadata bounds, managed-file path containment, security headers and sanitized errors.
- Atomic WebSocket admission and graceful close of active clients during shutdown.
- Unit tests, lint quality gate and GitHub Actions build artifacts.

### Changed

- Compile/target SDK 36, JDK 17, Gradle 8.11.1 and Media3 1.10.1.
- Normal refresh now fetches summaries only; full detail repair is explicit.
- Playback state WebSocket events omit the queue unless it changed.
- Offline playback builds a local-only queue when Navidrome is known to be unavailable.

## 0.4.0

- Added SQLite cache, bootstrap endpoint and WebSocket updates.

## 0.3.0

- Added TailTune-managed offline playlist downloads and removable-storage preference.
