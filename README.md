# TailTune 0.2 — Navidrome/Substreamer playlist edition

This version fixes the original architecture.

## What changed

- TailTune now connects directly to Navidrome through the Subsonic/OpenSubsonic API.
- It loads the same **server-side playlists** that appear in Substreamer.
- Playlist tracks can be browsed and played from the iPhone web interface.
- Queue items can be selected, moved and removed.
- Every ExoPlayer read/write is serialized onto the player's application thread.
- The web service starts as a valid foreground service with a notification, so pressing Start Web Remote no longer triggers the earlier foreground-service crash.
- Errors are shown in the iPhone interface instead of failing silently.

## Important limitation

Android 11 does not allow TailTune to read Substreamer's private offline cache. This version streams directly from Navidrome. Server-side playlists are shared because both apps retrieve them from the same Navidrome account. A playlist that exists only inside Substreamer's private local database cannot be read by TailTune without root or an export feature from Substreamer.

## Setup

1. Open this folder in Android Studio.
2. Sync Gradle and run it on the Samsung.
3. In TailTune, enter the exact same:
   - Navidrome server URL
   - username
   - password
   used in Substreamer.
4. Tap **Test Navidrome connection**.
5. Tap **Save and start web remote**.
6. Open the displayed `http://<Samsung-IP>:8787` address in Safari on the iPhone.

For a local Navidrome installation, the server URL often looks like:

`http://192.168.1.10:4533`

Do not add `/rest`; TailTune adds it automatically.

## Updating the existing project

The simplest path is to open this as a fresh project. It uses the same application ID as the original, so Android Studio can replace the installed debug build. If installation reports a signature mismatch, uninstall the old TailTune app first and run again.

## Security

- The password is stored in Android SharedPreferences for this personal MVP.
- The iPhone never receives the Navidrome password; it sends commands only to TailTune on the Samsung.
- Port 8787 has no login. Use it only on a trusted LAN or through a private network such as Tailscale. Do not expose it publicly.
