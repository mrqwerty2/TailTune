# Upgrade to TailTune 0.6.0

Install 0.6 over the existing package so Android keeps the app-private SQLite cache, settings, access token and downloaded files:

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Do **not** uninstall first if you want to retain offline music.

## What 0.6 changes

- The HTTP/WebSocket listener moved out of `PlaybackService` into the dedicated `RemoteServerService` connected-device foreground service.
- Playback remains in Media3 `MediaSessionService`.
- SQLite/storage initialization is off the lifecycle/UI thread.
- Cached-library reads are prioritized over network refresh.
- The secure web URL remains supported.
- A native iOS SwiftUI controller and Bonjour discovery are now included under `ios/`.

Existing 0.3/0.4 metadata and audio migrations are still handled by `OfflineStore` maintenance. The old plaintext Navidrome password is migrated to Android Keystore encryption when loaded.

After upgrade, reopen TailTune once through the Android activity, press **Save and start TailTune remote**, then use the newly displayed secure URL or native iOS controller.
