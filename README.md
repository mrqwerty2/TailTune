# TailTune 0.3 — Offline Navidrome playlists

TailTune turns an Android phone into a headless music player controlled from an
iPhone browser. It connects to Navidrome through the Subsonic API, reads the
same **server-side playlists** shown in Substreamer, and can now save those
playlists into its own offline library.

## Features

- Browse Navidrome/Substreamer server playlists from Safari on an iPhone.
- Play, pause, seek, skip, jump within the queue, reorder and remove queue items.
- Download a full playlist by tapping **⇩** beside it.
- Prefer the removable microSD card for offline audio when Android exposes one.
- Fall back to app-specific phone storage when no writable microSD directory is available.
- Play downloaded playlists when Navidrome or the internet is unavailable.
- Resume an interrupted playlist download by tapping the download button again.
- Reuse one downloaded song across several TailTune playlists without storing duplicates.
- Show download progress and whether the current song is playing locally.
- Build an installable debug APK automatically with GitHub Actions.

## Important storage behavior

TailTune cannot read Substreamer's private offline cache on Android 11. Instead,
it downloads its own copy of the same Navidrome playlists. Offline audio is
stored in TailTune's app-specific external directory and is deleted if TailTune
is uninstalled. Removing one offline playlist does not delete a song that is
still used by another downloaded TailTune playlist.

## Install from Android Studio

1. Open the project in Android Studio.
2. Let Gradle sync.
3. Select the Samsung wireless-ADB device.
4. Press **Run**.
5. Enter the same Navidrome URL, username and password used by Substreamer.
6. Tap **Test Navidrome connection**.
7. Tap **Save and start web remote**.
8. Open the displayed `http://<Samsung-IP>:8787` address on the iPhone.
9. Tap **⇩** beside a playlist and leave TailTune's foreground notification running until it completes.

A local Navidrome URL commonly looks like:

```text
http://192.168.1.10:4533
```

Do not add `/rest`; TailTune adds it automatically.

## Build an APK locally

In Android Studio use:

```text
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

The APK will be created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install or update it through wireless ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Publish the APK on GitHub

Build the APK locally first, then from the repository folder run:

```bash
cp app/build/outputs/apk/debug/app-debug.apk TailTune-v0.3.0-debug.apk

gh release create v0.3.0 TailTune-v0.3.0-debug.apk \
  --title "TailTune v0.3.0" \
  --notes "Adds app-managed offline Navidrome playlist downloads and microSD preference."
```

Open the repository's **Releases** page on the Samsung, download the APK and
allow the browser or Files app to install unknown apps when Android asks.

The debug APK is signed by the debug key on the computer that built it. Keep
building release APKs from the same computer if you want Android to install
updates without first uninstalling TailTune.

## GitHub Actions

`.github/workflows/android-build.yml` builds a debug APK on every push to
`main`. Open **Actions → Build Android APK → Artifacts** to download the CI
build. CI debug signatures can differ between runs, so the locally built APK is
better for repeat updates on the Samsung.

## Offline test

1. Download a playlist and wait for the ✓ mark.
2. Stop Navidrome or disconnect the Samsung from the Navidrome network.
3. Keep the Samsung and iPhone connected to the same Wi-Fi so the web remote is reachable.
4. Refresh TailTune. It should show **Offline mode**.
5. Open and play the downloaded playlist.

## Current limitations

- TailTune syncs Navidrome's server-side playlists, not playlists existing only in Substreamer's private database.
- Downloads run one playlist at a time.
- There is no per-song download button yet.
- App-specific offline files are removed when the app is uninstalled.
- The web remote has no password and must not be exposed directly to the public internet.
- Navidrome credentials are stored in ordinary Android SharedPreferences in this MVP.

## License

TailTune is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
