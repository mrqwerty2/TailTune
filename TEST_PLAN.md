# TailTune 0.6 validation plan

Run the host checks first:

```bash
python3 tools/static_audit.py
python3 tools/fault_simulation.py --output verification/fault-simulation.json
python3 tools/cache_fault_simulation.py --output verification/cache-fault-simulation.json
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

Install over existing data:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## A. Installation and migration

1. Upgrade with `adb install -r`; settings, DB and downloads survive.
2. Legacy plaintext password migrates to Android Keystore-backed ciphertext.
3. Legacy JSON metadata imports into SQLite.
4. Corrupt metadata DB is backed up/rebuilt without deleting deterministic audio files.
5. App starts offline when a valid existing cache exists.

## B. Remote/playback service lifecycle

Exercise the new split-service architecture:

- remote enabled while nothing is playing for 15, 30, 120 minutes and 8 hours;
- playback paused/stopped while remote stays reachable;
- playback process/service disconnect and rebind;
- listener unexpectedly dead while RemoteServerService remains alive;
- stale asynchronous listener-start completion after stop/reload;
- repeated Save/Start taps;
- remove configuration activity from Recents;
- screen locked;
- reboot / package update;
- explicit notification Stop action;
- explicit Android Force stop (expected: no automatic recovery until user action).

Pass: remote recovers from recoverable failures without creating duplicate bindings/listeners.

## C. Cached library performance

Populate 1, 20, 200 and 1,000 playlists; include 10, 100, 500 and 2,000-song playlists.

1. Airplane mode and restart.
2. Cached summaries appear without Navidrome.
3. Cached playlist details load without a remote request.
4. Empty remote `getPlaylists` does not erase a useful cache.
5. Remote deletion prunes non-offline playlists but retains requested offline content.
6. Shared songs survive removal of one offline playlist if another still references them.

M30s targets:

- cached bootstrap: normally <500 ms;
- cached 500-track playlist detail: normally <500 ms;
- local play command: normally <1 s.

## D. Playback

- play/pause/toggle/next/previous/jump/seek;
- previous before/after five-second threshold;
- queue add/move/remove/clear;
- rapid command bursts and serialized ordering;
- 1/100/500/2,000-song queues;
- USB-C unplug / audio-becoming-noisy;
- audio focus interruption;
- local-only, remote-only and partially cached playlists;
- missing/truncated local file never considered playable offline.

## E. Downloads and storage faults

- normal download, cancel, resume, remove, redownload;
- process kill at 10%, 50%, after fsync and after promotion;
- Range accepted, ignored, malformed Content-Range and HTTP 416;
- known/unknown content length;
- connection drop, read timeout;
- <100 MB free, exact required space, full filesystem mid-write;
- microSD absent at startup, removed during idle, reinserted;
- stale `.part` cleanup;
- truncated/oversized finalized file;
- shared track across multiple offline playlists.

Pass: no invalid/truncated file is indexed as available and no shared required file is deleted.

## F. Browser remote

Safari/Chrome/Home Screen shortcut over:

- home Wi-Fi;
- iPhone hotspot with no internet;
- Samsung hotspot with no internet;
- Tailscale;
- Wi-Fi/network transitions.

Background/lock for 5, 30 and 120 minutes. Verify WebSocket reconnect and polling fallback.

## G. Native iOS controller

- Bonjour discovery on LAN;
- manual LAN/hotspot IP;
- manual Tailscale IP;
- valid/wrong/rotated token;
- background then foreground;
- Android listener restart while iOS remains open;
- request timeout and reconnect;
- 64 KiB boundary;
- 8 WebSocket clients then ninth refusal;
- 100 reconnect cycles;
- 1,000 control requests.

## H. Security/fuzz corpus

- absent/wrong/malformed token;
- invalid JSON;
- empty message;
- binary WebSocket frame;
- >64 KiB body/message;
- missing/incorrect primitive types;
- huge/negative seek/indices;
- control characters in IDs;
- HTML/script metadata rendered only as text;
- more than 64 pending native requests;
- more than 8 WebSocket clients;
- ensure surfaced errors contain no Navidrome password, `u`, `t`, `s`, or TailTune token.

## I. Soak/performance

At minimum:

- 8 h offline playback;
- 8 h remote streaming;
- 8 h paused/idle remote;
- 1,000 control commands;
- 100 playlist opens;
- repeated download/remove cycles.

Observe:

```bash
adb shell dumpsys meminfo dev.tailtune.remote
adb shell dumpsys batterystats --charged dev.tailtune.remote
adb logcat | grep -E 'ANR|FATAL EXCEPTION|Long monitor contention|StrictMode|TailTune-Remote|TailTune-Playback'
```

Pass: no crash/ANR, runaway memory, corrupt finalized file, permanently dead recoverable listener, or unexplained lost command sequence.

See `verification/DEVICE-TORTURE-TEST.md` for copy/paste ADB procedures.
