# Install the TailTune iPhone app icon (Ubuntu + xtool)

This project is the **TailTune v1.3 iPhone SwiftUI source**, with a new full-size
`AppIcon.png` (1024 × 1024) and `iconPath: AppIcon.png` in `xtool.yml`.
No change is needed to the Swift source or `Package.swift`.

## Update an existing installation

1. Extract this ZIP into a NEW directory, preserving `iphone/`.
2. Keep your old iPhone app installed. Do not uninstall it.
3. Confirm that `iphone/xtool.yml` contains `bundleID: dev.mahimn.tailtuneble`.
4. Connect and unlock your iPhone. Accept Trust if prompted.
5. From the extracted `iphone/` folder, run `xtool dev`.
6. Check the icon on the Home Screen or App Library. If the old icon remains,
   restart the iPhone to clear the launcher icon cache.

**Requirements:** xtool, a configured Darwin SDK, USB device trust, Developer
Mode, and working Apple Account signing. Creating/changing the icon cannot
bypass Apple's signing/2FA requirements.

**Backup:** App bundle ID stays unchanged (`dev.mahimn.tailtuneble`), so an
update signed by the compatible team should preserve app data. The bundle
version is bumped to 3 (marketing version 1.3). Different signing certificates
may block an update; do not uninstall until you have secured your app data.

If `xtool dev` reports an unknown `iconPath` property, update xtool to a version
that supports it. xtool's SwiftPM app builder supports a root PNG via `iconPath`.

No full iOS build or physical-device test was possible here.
