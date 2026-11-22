# TailTune

**An offline-first music player on Android, controlled from an iPhone over Bluetooth Low Energy (BLE).**

The Samsung/Android device handles Navidrome synchronization, downloads and audio playback. The iPhone acts as a lightweight SwiftUI remote for the library, playlists, queue and playback controls. **Audio does not travel over BLE**: headphones and speakers remain connected to the Android phone.

> **Project status:** The BLE/Navidrome edition is **v1.3 beta**. TailTune v1.4 playlist caching is being developed separately. The earlier v0.6 Wi-Fi/WebSocket edition is retained as legacy code. A successful build does not by itself mean both phones have been updated or all device-level scenarios have been tested.

## How it works

```text
  Navidrome (Subsonic-compatible server)
               |
      sync / download / metadata
               v
   Samsung / Android player
   - Navidrome library + playlists
   - local audio/cache + playback
   - BLE GATT RPC server
               |
          BLE controls + metadata
          (NO AUDIO STREAMING)
               |
               v
     iPhone SwiftUI remote
     - scan and connect
     - browse playlists + tracks
     - queue / play / pause / seek
     - connection and device status

  Headphones / speaker <--- Samsung audio output
```

## Repository layout

| Directory | What it contains |
| --- | --- |
| [`ble-v1.3/samsung/`](ble-v1.3/samsung/) | Current Android/Navidrome music player and BLE GATT server (Kotlin / Gradle). |
| [`ble-v1.3/iphone/`](ble-v1.3/iphone/) | Current iPhone remote (SwiftUI / CoreBluetooth / SwiftPM / xtool). |
| [`ble-v1.3/iphone/protocol-tests/`](ble-v1.3/iphone/protocol-tests/) | Host-independent BLE/RPC protocol tests. |
| [`app/`](app/) and [`ios/`](ios/) | Earlier v0.6 Android and iOS networking-based implementations, retained for reference. |
| [`.github/workflows/`](.github/workflows/) | Android build, legacy iOS quality gate and BLE Swift protocol checks. |

**Do not confuse** the old `ios/` XcodeGen project with the BLE iPhone app at `ble-v1.3/iphone/`. The legacy iOS quality gate does **not** compile the full BLE iPhone app; verify that app with `xtool dev` on a configured Linux development machine.

## Features in the BLE/Navidrome edition

- Android music playback with Navidrome/Subsonic-compatible library and playlist integration.
- Offline-first Android downloads/cache; the phone owning playback can keep playing available local tracks without an active Navidrome connection.
- iPhone SwiftUI remote over BLE for browsing, queues, playback and volume controls.
- BLE requests for playlist listing and paginated playlist tracks.
- Android device status surfaced to the remote (availability depends on device permissions and runtime state).
- Android Keystore-based handling of saved Navidrome credentials.

**In progress (v1.4 branch):** faster playlist reopening, iPhone-side metadata caching and refresh logic. Do not assume these changes are in a release until the feature branch is merged and tested on-device.

## Requirements

- **Android:** JDK 17, Android SDK Platform 35 and matching build tools, a USB-connected or otherwise accessible Android device, and a Navidrome server for library sync.
- **iPhone:** iPhone with BLE support, an iOS-compatible Swift/xtool development environment, and access to the iPhone for signing and installation. Check `Package.swift` for the current iOS deployment target; actual device compatibility still requires testing.
- **Ubuntu/Linux development:** `git`, `adb`, Android SDK and `xtool` for iPhone builds. A Mac is not required for the workflow already used with xtool, but provisioning and compatible Apple SDKs are still required.

## Quick start — Samsung / Android

```bash
cd ble-v1.3/samsung
# Use your Android SDK's actual path:
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

Debug APK: `ble-v1.3/samsung/app/build/outputs/apk/debug/app-debug.apk` (from the repository root).

To update an existing installation, **the new APK must use the same Android signing certificate as the installed app**. GitHub Actions debug APKs are generally signed using a different ephemeral runner key; Android will reject an incompatible replacement (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). **Do not uninstall TailTune to bypass this error**: uninstalling can erase cached music, settings and saved credentials. Verify signing certificates before using `adb install -r`.

Do not modify an existing Samsung phone's ADB, scrcpy or TailTune setup simply to test a new APK.

## Quick start — iPhone / Ubuntu

```bash
cd ble-v1.3/iphone
xtool dev
```

`xtool dev` builds, provisions, signs and installs the SwiftUI remote onto the connected iPhone when the developer environment is configured. Use the current project directory, not a historical extracted ZIP folder. Keep the original bundle identifier and compatible signing configuration to preserve app data during updates.

**Installing code is a separate step from pushing it to GitHub.** GitHub source changes do not automatically update either phone.

## Connect the phones

1. Start TailTune on Samsung; grant its required Bluetooth/audio permissions and start its BLE remote/advertising service.
2. Ensure the Samsung firmware supports BLE peripheral advertising.
3. Open TailTune on iPhone; scan for the Samsung and connect.
4. Browse playlists and control playback. Audio continues to play from Samsung to its chosen output device.
5. If BLE discovery fails, inspect Samsung advertising logs (`adb logcat -d | grep -Ei 'TailTune|advertis|GATT'`) before resetting or changing phone settings.

## Testing and CI

```bash
# iPhone BLE protocol tests (runs on Linux or macOS)
cd ble-v1.3/iphone/protocol-tests
swift test

# Android checks (with SDK configured)
cd ../../samsung
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

GitHub Actions provides an Android quality gate, legacy iOS quality gate and Swift BLE protocol tests. A passing set of checks **does not** replace an on-device BLE interaction test or a full build of `ble-v1.3/iphone/`.

## Security and data preservation

- Never commit Navidrome credentials, signing keys, debug keystores, `.env` files, tokens, provisioning profiles or local `xtool` state.
- Keep Navidrome credentials in the platform's protected storage rather than hard-coded source.
- BLE protocol v1 should not be treated as an authenticated, hardened public-use control channel without additional device pairing/encryption or application-layer authentication.
- Preserve downloaded audio and app data. Avoid uninstall, clearing data, `adb shell pm clear`, or destructive migration steps during ordinary updates.
- Keep personal device identities and private server URLs out of logs and GitHub issues.

## Download TailTune

The BLE/Navidrome beta release and any attached Samsung APKs are published under **[GitHub Releases](https://github.com/mrqwerty2/TailTune/releases)**. The v1.3 beta tag is [`v1.3.0-ble-beta.1`](https://github.com/mrqwerty2/TailTune/releases/tag/v1.3.0-ble-beta.1).

**Android APK warning:** The beta APK may be developer/debug-signed. Android updates require the same signing certificate as the currently installed TailTune package. An APK that installs on one phone may not upgrade another device's installation. Never uninstall the existing player merely to bypass a signature mismatch; preserve downloaded audio, app settings and credentials.

**iPhone:** The native SwiftUI remote is distributed as source code. Build/install with `xtool dev` and your own provisioning configuration; this repository does not currently promise a universally installable iOS IPA.

## Releases and development branches

- **`main`**: publicly documented, merged project source.
- **`import-ble-v1.3`**: original import of Android/iPhone BLE code and CI corrections (historical working branch).
- **`feature/v1.4-fast-playlists`**: work in progress; separate review and physical-device verification required.
- **Releases**: created explicitly through GitHub tags. A GitHub Actions artifact is **not** automatically a downloadable release asset.

Beta APKs, when attached, are **testing builds rather than guaranteed drop-in upgrades** until stable release signing is established. iOS installations built with xtool require the developer's own provisioning/signing.

## Development with coding assistants

Repository-level guardrails are in [`AGENTS.md`](AGENTS.md) (Codex and other agents) and [`CLAUDE.md`](CLAUDE.md) (Claude Code). Use a feature branch for every change, inspect diffs before committing and run relevant tests. On limited-memory machines, prefer focused tasks; large local-model context windows may exceed available RAM.

## License

GPL-3.0. See [LICENSE](LICENSE).
