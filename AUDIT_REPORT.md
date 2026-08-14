# TailTune 0.6.0 architecture and reliability audit

## Scope

This revision is a redesign of the uploaded TailTune Android/web project rather than a small patch. The audit covered Android service lifecycle, Media3/ExoPlayer, embedded HTTP/WebSocket behavior, SQLite/offline cache integrity, Navidrome/Subsonic networking, downloads, browser recovery, the new native iOS controller, local discovery, security, concurrency, bounded-resource behavior, and failure recovery.

## Why the old design disappeared after idle playback

The old HTTP/WebSocket listener lived inside `PlaybackService : MediaSessionService`. That couples remote availability to the media-service lifecycle. In 0.6 the network listener is owned by a separate `RemoteServerService` foreground service and `PlaybackService` is media-only.

```text
RemoteServerService (connectedDevice foreground service)
  ├── NanoWSD HTTP/WebSocket listener :8787
  ├── token authentication
  ├── listener watchdog/retry
  └── Bonjour/DNS-SD advertisement
             │ local binding
             ▼
PlaybackService (MediaSessionService)
  ├── MediaSession / ExoPlayer
  ├── OfflineStore
  ├── LibrarySyncManager
  └── OfflineDownloadManager
```

This is the central lifecycle correction: pausing music no longer intentionally tears down the network listener.

## Additional compile/lifecycle defects caught during the 0.6 review

- Added the required `RemoteServerService.onBind()` implementation. A direct subclass of `android.app.Service` must implement the abstract binding method even when it is a start-only service.
- Changed `PlaybackService` to `android:exported="true"` on its standard Media3 service intent, matching Media3 service discovery requirements. The network-facing `RemoteServerService` remains unexported.
- Replaced deprecated Gradle `kotlinOptions` configuration with `compilerOptions`/`JvmTarget.JVM_17`.
- Corrected NanoWSD WebSocket `close(...)` calls to use the three-argument API.
- Queue-dirty state is now cleared only after a successful playback snapshot and only if no newer queue revision appeared while the snapshot was being built.
- Added an explicit `PlaybackService.onTaskRemoved()` policy so the custom player application looper never falls into Media3's main-looper-only `pauseAllPlayersAndStopSelf()` helper path. The dedicated remote binding owns paused/headless lifetime.

## Android remote-service recovery

`RemoteServerService` now:

- enters foreground state immediately before database/player/socket work;
- uses foreground type `connectedDevice` and its type-specific permission;
- binds to playback with `BIND_AUTO_CREATE`;
- uses a 12-second binding timeout;
- distinguishes a temporary service disconnect from a dead/null binding;
- retries failures with bounded exponential delay (1 s to 30 s);
- polls playback initialization every 500 ms without escalating the delay;
- uses a generation counter so stale asynchronous listener starts cannot attach after stop/reload;
- checks the NanoHTTPD listener every 20 seconds and restarts a dead listener;
- restarts as `START_STICKY` when Android recreates it;
- re-advertises Bonjour on network changes;
- honors explicit Stop/disabled state and does not fight an intentional user shutdown.

## Main-thread and ANR hardening

The old uploaded logs showed storage/cache monitor contention and ANRs. The new path avoids the original global monitor and moves expensive initialization off Android lifecycle/UI threads:

- SQLite uses WAL;
- storage maintenance/reconciliation has a dedicated executor;
- `OfflineStore`, Keystore/settings load, and library component initialization run on `TailTune-PlaybackInit`;
- ExoPlayer has its own application looper;
- queue/media state is serialized on the player looper with a bounded wait;
- large playlist local-file lookup is batched in SQL;
- remote configuration/network checks run on a configuration executor;
- service destruction never waits on ExoPlayer, SQLite, socket, or executor shutdown on the main thread;
- debug builds enable StrictMode logging so regressions become visible during testing.

## Cached library and Navidrome behavior

- Normal startup performs one lightweight playlist-summary sync, not one detail request per playlist.
- Cached summaries remain available while Navidrome is offline.
- An empty Navidrome playlist response does not erase a useful existing cache.
- Cached playlist details return immediately when usable.
- Stale details can refresh in the background.
- A forced full refresh remains available for repair/testing.
- Playlist playback resolves all local files with one batched lookup rather than one database lookup per song.

## Offline download/data-integrity hardening

- deterministic safe filenames based on song identity;
- resumable `.part` downloads;
- HTTP Range validation and safe restart behavior;
- explicit network timeouts and response limits;
- free-space reserve before downloads;
- flush + `fsync` before final promotion;
- expected-size validation when Navidrome supplies a size;
- atomic move where supported, with vendor-filesystem fallback;
- incomplete finalized files are demoted/reconciled;
- stale partial cleanup is maintenance work;
- shared audio is retained while another offline-requested playlist references it;
- SQLite corruption recovery preserves recoverable audio files rather than deleting the music cache blindly.

## HTTP/WebSocket hardening

- 192-bit per-install access token;
- constant-time token comparison;
- browser token held in URL fragment rather than normal HTTP query for page loads;
- native iOS sends `X-TailTune-Token` in the WebSocket handshake;
- 64 KiB HTTP JSON request cap;
- 64 KiB WebSocket request cap;
- maximum 8 authorized WebSocket clients;
- 2 WebSocket request workers;
- bounded queue of 64 pending native requests;
- server-push events are coalesced by event type;
- per-socket send serialization;
- sanitized errors and secret redaction;
- CSP, frame denial, no-sniff, no-referrer/no-index and restrictive browser permission headers;
- static browser assets are served no-cache to avoid stale APK UI code.

## Native iOS controller

The new `ios/` source is an iOS 16+ SwiftUI controller. It uses:

- `NWBrowser` to discover `_tailtune._tcp` Bonjour services;
- `NWConnection` + `NWProtocolWebSocket`;
- Keychain token storage;
- a 12-second request timeout;
- a 20-second heartbeat;
- bounded 1–15 second reconnect delay;
- connection generations to ignore stale callbacks;
- automatic foreground resume/reconnect;
- explicit bad-token handling that stops an infinite reconnect loop;
- manual LAN/Tailscale address fallback when Bonjour is unavailable.

The browser remote remains available, so native iOS is optional.

## Verification executed in the audit environment

Completed host-independent checks:

- `tools/static_audit.py`: **39 checks, 0 failures**;
- primary lifecycle fault model: **5,100,000 modeled operations, PASS**;
- two additional lifecycle seeds: **3,200,000 modeled operations, PASS**;
- primary cache/offline fault model: **1,000,000 modeled operations, PASS**;
- one additional cache seed: **1,000,000 modeled operations, PASS**;
- combined model-based operations across successful runs: **10,300,000**;
- dedicated stale-listener-start races: **300,000**;
- dedicated shared-offline-file retention cases: **200,000**;
- pure Kotlin retry/address/error-sanitization smoke test: **PASS**;
- JavaScript syntax parse with Node: **PASS**;
- all iOS Swift sources parsed by `swiftc`: **PASS**;
- pure Swift model typecheck: **PASS**;
- Android manifest and iOS plist parse: **PASS**;
- both GitHub Actions workflow YAML files parse: **PASS**.

The lifecycle/cache simulations are model-based tests of invariants, not an Android emulator. They deliberately inject repeated process death, listener death, port contention/recovery, binding failure/timeout, initialization failure, stale asynchronous start completions, explicit stops, empty remote libraries, shared files, truncated/missing files, and reconciliation sequences.

## Build-validation limitation

This audit container does not have Android SDK 36 or a populated Gradle dependency cache, so it cannot execute the real Android Gradle compiler/lint/APK build. It also cannot perform an Xcode iOS build because the environment is Linux rather than macOS/Xcode. Those two checks are therefore provided as CI quality gates:

- `.github/workflows/android-build.yml`: static checks + simulations + Android unit tests + lint + APK assembly;
- `.github/workflows/ios-build.yml`: XcodeGen + iOS Simulator build/tests.

A successful real build on the user's Android SDK machine and a physical-device soak test are still required before calling a release production-ready.

## Remaining platform limits

No app can guarantee recovery from every possible condition. TailTune cannot override:

- an explicit Android **Force stop** until the user starts the app again;
- device shutdown/battery depletion;
- revoked required permissions or OS policy;
- catastrophic storage/hardware corruption;
- unreachable Navidrome for songs that were never downloaded;
- a hostile LAN intercepting plain HTTP/WebSocket traffic;
- iOS suspending the native controller in the background (the controller reconnects when it becomes active again).

For a dedicated Samsung, keep TailTune in **Unrestricted** battery mode and **Never sleeping apps** and complete the real-device torture tests in `verification/DEVICE-TORTURE-TEST.md`.
