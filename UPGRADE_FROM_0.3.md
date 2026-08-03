# Upgrade from TailTune 0.3

Copy version 0.4 over the existing repository, commit the changes, and build the
APK using the same Android Studio installation/debug signing key.

Install without uninstalling:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

At first launch, TailTune imports `tailtune_offline_library.json` into
`tailtune.db` and renames the old JSON file. Existing downloaded audio remains
in the same app-specific storage directory.
