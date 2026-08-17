# TailTune 0.6 real-device torture test

These tests are intended for the Samsung test device. Back up anything important first. Commands marked **destructive** can interrupt playback/downloads but do not intentionally delete app data.

## Preflight

```bash
adb shell dumpsys package dev.tailtune.remote | grep versionName
adb shell am start -n dev.tailtune.remote/.MainActivity
```

Expected version: `0.6.0`.

After enabling the remote:

```bash
adb shell dumpsys activity services dev.tailtune.remote
adb shell ss -ltn | grep ':8787'
```

Expect `RemoteServerService` and `PlaybackService`, plus a listener on `8787`.

## 1. Fifty start/reload requests

```bash
for i in $(seq 1 50); do
  adb shell am start-foreground-service \
    -n dev.tailtune.remote/.RemoteServerService \
    -a dev.tailtune.remote.action.REMOTE_START_OR_RELOAD >/dev/null
  sleep 0.2
done
sleep 3
adb shell ss -ltn | grep ':8787'
adb shell dumpsys activity services dev.tailtune.remote
```

Pass: one remote service/listener, no ANR/FATAL.

## 2. Activity removed, service retained

```bash
adb shell am force-stop dev.tailtune.remote
adb shell am start -n dev.tailtune.remote/.MainActivity
# enable remote from UI, then use Android Recents to remove only the activity
```

After removal:

```bash
adb shell dumpsys activity services dev.tailtune.remote
adb shell ss -ltn | grep ':8787'
```

Pass: listener remains.

## 3. Screen-off idle soak

```bash
adb shell input keyevent 26
```

Leave playback paused for 30–120 minutes. Periodically from another device/laptop:

```bash
curl --max-time 2 -H 'X-TailTune-Token: <TOKEN>' \
  http://<SAMSUNG-IP>:8787/api/health
```

Pass: HTTP 200; if socket dies, watchdog recovers within roughly one watchdog/retry interval.

## 4. Process pressure / app process kill

**Destructive to current playback session, not app data.** Android may or may not honor `am kill` immediately for foreground work.

```bash
adb shell am kill dev.tailtune.remote
sleep 35
adb shell dumpsys activity services dev.tailtune.remote
adb shell ss -ltn | grep ':8787'
```

Pass when OS permits sticky recreation: remote returns. If the OS treats the action like an explicit force-stop, manual reopening is expected.

Do **not** use `am force-stop` as a recovery expectation: Android intentionally suppresses automatic restart after force-stop.

## 5. Network switch

While remote is running, move Samsung between home Wi-Fi and iPhone hotspot. Then:

```bash
adb shell ip -4 addr
adb logcat -d | grep -E 'TailTune-NSD|TailTune-Remote'
```

Pass: remote listener remains; Bonjour re-advertises; manual new IP works.

## 6. Fully offline cached playback

1. Download a playlist completely.
2. Disable internet while keeping a local LAN/hotspot.
3. Restart the controller connection.
4. Play the downloaded playlist.

Pass: cached library and local audio work; Navidrome failure is nonfatal.

## 7. Download interruption

Start a download, then interrupt network or kill the app process. Reopen/restart and resume the playlist download.

Pass: `.part` resumes/restarts safely; finalized truncated files are never shown as available.

## 8. Browser/iOS reconnect

Background the controller for 30 minutes while Android remains running, then foreground it.

Pass: browser/native client reconnects without restarting Android service.

## 9. Long soak logging

```bash
adb logcat -c
# run soak
adb logcat -d > tailtune-soak.log
adb logcat -d | grep -E 'FATAL EXCEPTION|ANR in dev.tailtune.remote|Long monitor contention|StrictMode|TailTune-Remote|TailTune-Playback'
```

Also capture:

```bash
adb shell dumpsys meminfo dev.tailtune.remote > tailtune-meminfo.txt
adb shell dumpsys batterystats --charged dev.tailtune.remote > tailtune-battery.txt
```
