# TailTune 0.6.0

TailTune turns an Android phone into a **headless, offline-first music player** and lets an iPhone control it either with the built-in web remote or the new native SwiftUI controller.

The Android phone owns playback and downloaded music. The controller sends only commands/state over the local network, hotspot, or Tailscale.

## 0.6 architecture

```text
                       TailTune
                          │
           ┌──────────────┴──────────────┐
           │                             │
   Android player                 iPhone controller
           │                             │
 RemoteServerService :8787        SwiftUI + Network.framework
 connectedDevice FGS              Bonjour + WebSocket
           │                             │
           └──────── TailTune Protocol ──┘
                          │
                 PlaybackService
                 MediaSessionService
                          │
                 Media3 / ExoPlayer
                          │
              SQLite/WAL + downloads
                          │
                    Navidrome
```

The key reliability change is that the HTTP/WebSocket listener no longer lives inside `MediaSessionService`. `RemoteServerService` is an independently foregrounded connected-device service, while `PlaybackService` is dedicated to media playback.

## Reliability work in 0.6

- separate always-on remote and playback service lifecycles;
- `START_STICKY` remote restart and boot/package-update restart when Android permits it;
- 20-second listener watchdog and generation guards against stale server-start races;
- explicit playback bind/rebind timeout and recovery;
- SQLite/storage initialization moved off Android's lifecycle thread;
- cached playlist summaries are available without a full Navidrome detail sync;
- one batched local-file query for playlist playback;
- bounded WebSocket request pool and message/body limits;
- WebSocket and native-client heartbeat/reconnect logic;
- resumable `.part` downloads with validation, flush + fsync, and safe promotion;
- Android Keystore encryption for the Navidrome password;
- per-install 192-bit remote access token;
- Bonjour/DNS-SD discovery with Android multicast-lock support;
- native iOS controller with Keychain token storage and manual Tailscale fallback.

## Requirements

### Android player

- Android 8.0+ (`minSdk 26`); target device tested by the project owner is Android 11.
- JDK 17.
- Android SDK 36.
- Navidrome/Subsonic server for online sync/streaming; already-downloaded content works without Navidrome.

### Native iPhone controller

- iOS 16+.
- Mac + Xcode to build/sign.
- XcodeGen (`brew install xcodegen`).

The browser remote remains available, so the iOS app is optional.

## Android build

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Do not uninstall the old package first** if you need to keep its SQLite cache, settings, and downloaded music.

If your machine already has another TailTune checkout with a valid Android SDK path, copy only its `local.properties` into this project:

```bash
cp ~/Downloads/TailTune-v0.2-Navidrome/local.properties ./local.properties
```

## Android setup

1. Open TailTune directly or through `scrcpy`.
2. Enter Navidrome URL/username/password, or leave them blank only when an existing cache is present.
3. Tap **Test connection**.
4. Tap **Save and start TailTune remote**.
5. Use either the secure browser URL or the native iOS controller.

Browser URL example:

```text
http://192.168.1.2:8787/#token=<32-character-token>
```

Tailscale example:

```text
http://100.64.x.x:8787/#token=<32-character-token>
```

## Native iOS build

```bash
cd ios
brew install xcodegen
xcodegen generate
open TailTuneRemote.xcodeproj
```

Choose your Apple signing team and run on the iPhone. On first use, allow Local Network access. Paste the Android token/full secure TailTune URL once; the token is stored in Keychain. Bonjour-discovered devices can then be selected directly. For Tailscale, enter the Samsung `100.x.x.x` address manually.

## No-internet operation

Download playlists before leaving internet coverage. Then either phone can provide a local hotspot; the controller and Android player communicate directly on that LAN. Internet and Tailscale are not required for local control of downloaded music.

For an iPhone hotspot where Bonjour discovery is unavailable, use the Samsung's hotspot-assigned IP manually in the native app/browser.

## Samsung reliability settings

For a dedicated headless phone, set TailTune to:

```text
Settings → Apps → TailTune → Battery → Unrestricted
Settings → Battery and device care → Battery → Background usage limits
         → Never sleeping apps → TailTune
```

Android can still prevent automatic recovery after an explicit **Force stop**. That state intentionally requires the user to reopen the app.

## Security

- HTTP and WebSocket APIs require the random TailTune token.
- Native iOS sends the token as a WebSocket header; the browser uses a tokenized WebSocket handshake.
- Navidrome password is AES-GCM encrypted with Android Keystore.
- API request/message sizes and fields are bounded.
- The network-facing `RemoteServerService` and boot receiver are unexported. `PlaybackService` is exported only through the standard Media3 `MediaSessionService` interface so Android/system media controllers can discover it; Media3 grants untrusted controllers read-only access by default.
- Do not port-forward `8787` to the public internet. Use LAN/hotspot or a private Tailscale network.

See [SECURITY.md](SECURITY.md).

## Verification

Host-independent checks:

```bash
python3 tools/static_audit.py
python3 tools/fault_simulation.py --output verification/fault-simulation.json
python3 tools/cache_fault_simulation.py --output verification/cache-fault-simulation.json
```

The completed audit ran **39 static checks** plus **10.3 million model-based lifecycle/cache operations across multiple deterministic seeds**, all passing. These are fault models, not substitutes for the included Android/iPhone real-device torture tests.

The repository also contains Android and macOS/iOS CI quality gates. See [TEST_PLAN.md](TEST_PLAN.md) and `verification/DEVICE-TORTURE-TEST.md` for real-device tests.

## Documentation

- [Architecture](ARCHITECTURE.md)
- [TailTune protocol](PROTOCOL.md)
- [Test plan](TEST_PLAN.md)
- [Security](SECURITY.md)
- [Changelog](CHANGELOG.md)
- [Audit report](AUDIT_REPORT.md)
- [Verification report](verification/VERIFICATION-REPORT.md)
- [iOS controller](ios/README.md)

## Kubernetes?

Kubernetes is intentionally **not** part of the phone architecture. It cannot prevent Android from suspending a phone service and would add substantial overhead. If TailTune later gets a public relay/device-registry backend with enough users to require orchestration, Kubernetes can be considered there. A small relay should begin much simpler (for example, one service/container) and scale only when usage justifies it.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
