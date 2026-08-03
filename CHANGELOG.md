# Changelog

## 0.4.0

- Start the embedded web server before Navidrome synchronization.
- Add a SQLite library cache and migrate v0.3 JSON metadata.
- Add WebSocket state updates with reconnecting browser fallback.
- Add a single fast bootstrap endpoint.
- Move full library synchronization to a background executor.
- Cache versioned web assets in Safari.
- Display home-Wi-Fi and Tailscale remote URLs.
- Push download progress without repeated browser polling.

## 0.3.0

- Add TailTune-managed offline Navidrome playlist downloads.
- Prefer removable microSD app storage.
- Support offline playback and shared downloaded tracks across playlists.
