# TailTune Remote for iPhone

Native SwiftUI controller for a TailTune 0.6 Android player.

## Why native

The browser UI remains a fallback, but iOS may suspend browser/app networking while backgrounded. The native controller uses Network.framework, Bonjour discovery, explicit request timeouts, heartbeats and reconnect logic. Audio and offline music remain on the Android player.

## Build

A Mac with Xcode is required to build/sign an iOS app:

```bash
brew install xcodegen
cd ios
xcodegen generate
open TailTuneRemote.xcodeproj
```

Select your Apple team and run on an iPhone. First launch requests Local Network access.

## Pairing

1. Enable the TailTune remote on Android.
2. Paste the 32-character Android access token **or the full secure TailTune browser URL** once.
3. Tap the discovered `TailTune <phone model>` device.
4. The token is stored in iOS Keychain.

For Tailscale, enter the Android `100.x.x.x` address manually. For an iPhone hotspot where Bonjour does not surface the Samsung, enter its `172.20.10.x` hotspot address manually.

## Resilience behavior

- 12-second request timeout.
- 20-second heartbeat.
- reconnect backoff capped at 15 seconds.
- stale NWConnection callbacks ignored through connection generations.
- foreground `resume()` reconnects after iOS suspension.
- authentication failure clears the bad token and stops automatic retry instead of looping forever.
- incoming/outgoing protocol messages are capped at 64 KiB.

## Current scope

0.6 is a **controller**. The Android device remains the player/server. An iPhone-as-player mode is a future feature and should not reuse Android assumptions about indefinitely running arbitrary background listeners.
