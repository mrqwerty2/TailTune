# Upgrade an existing TailTune 0.2 repository

Assuming your Git repository is:

```text
~/Downloads/TailTune-v0.2-Navidrome
```

and this folder was extracted as:

```text
~/Downloads/TailTune-v0.3-Offline
```

copy the update over the existing repository while preserving its `.git` directory:

```bash
cp -a ~/Downloads/TailTune-v0.3-Offline/. \
      ~/Downloads/TailTune-v0.2-Navidrome/

cd ~/Downloads/TailTune-v0.2-Navidrome
git status
git add -A
git commit -m "Add offline Navidrome playlist downloads"
git push
```

Then reopen/sync the existing project in Android Studio and press Run.
