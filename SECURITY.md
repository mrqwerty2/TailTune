# Security

## Supported deployment

TailTune is intended for a trusted local network, direct private hotspot, or private Tailscale tailnet. Do **not** expose TCP port 8787 directly to the public internet.

## Controls

- 24 random bytes encoded as a 32-character Base64URL remote token.
- Constant-time token comparison on Android.
- Native iOS WebSocket sends the token as `X-TailTune-Token`.
- Android Keystore AES/GCM encryption for the Navidrome password.
- iOS Keychain storage for remote tokens.
- Android application backup disabled.
- 64 KiB maximum HTTP body and WebSocket request.
- 8 simultaneous authorized WebSocket clients.
- two WebSocket request workers with a 64-request bounded pending queue.
- bounded identifier/action/metadata lengths and strict JSON primitive validation.
- database-derived audio paths restricted to safe basenames in TailTune storage.
- no-store API responses, CSP, frame denial, no-sniff, restrictive permissions policy, same-origin resource policy and no-index headers.
- sanitized errors redact Subsonic authentication query parameters and TailTune token headers.
- The network-facing `RemoteServerService` and boot receiver are unexported. `PlaybackService` is exported through the standard Media3 `MediaSessionService` interface for Android media-controller discovery; its HTTP/WebSocket remote is not exposed through that binder interface.
- Bonjour TXT data advertises protocol/version metadata but never the secret token.

## Limitations

- TailTune's LAN/hotspot transport is plain HTTP/WebSocket. A hostile device able to sniff or actively intercept that local network could capture/alter traffic.
- Browser WebSocket APIs cannot set the custom token header, so the browser fallback includes the token in the WebSocket query string. TailTune does not intentionally log that query, but native iOS is preferable on untrusted LANs.
- Tailscale encrypts tailnet transport, but TailTune itself does not terminate TLS.
- Anyone with unlocked Android app-data/ADB access may obtain control of the device.
- Clearing Android app data resets the TailTune remote token and encrypted settings.
- This version does not implement an internet-facing relay. Do not substitute router port-forwarding.

## Reporting

Report security issues privately before publishing exploit details.
