---
description: Docs on every feature merge, the permission check, the F-Droid listing and releases
trigger: glob
globs: '**/fastlane/**, **/docs/fdroid/**, **/app/build.gradle.kts, **/README.md'
paths:
- fastlane/**
- docs/fdroid/**
- app/build.gradle.kts
- README.md
---

# Releases and the listing

- **Docs on every feature merge** (AGENTS.md): SPEC, ROADMAP, README, the
  listing in `fastlane/metadata/android/<locale>/` (`short_description.txt`
  under 80 characters, `full_description.txt` plain text), screenshots once
  there is a UI to show.
- **The permission check before any release**: `aapt2 dump permissions` on
  the release APK shows `INTERNET`, `ACCESS_NETWORK_STATE`, `QUERY_ALL_PACKAGES`,
  `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS`, and beside
  them only androidx's own `<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
  (declared and used by the app itself; SPEC §5.1). A library that adds
  another (Media3 adds `WAKE_LOCK`) gets it removed in the manifest with
  `tools:node="remove"`.
- **Versions**: `versionCode` up by one, `versionName` as the tag `v<name>`;
  the changelog in `fastlane/.../changelogs/<versionCode>.txt`, under 500
  characters, written at the release.
- **The release build is checked on the emulator** (R8 on, shrinking on)
  before the tag: the receiver is kept by the manifest, but a renamed class
  in a log or a string breaks silently.
- **F-Droid**: the recipe in `docs/fdroid/io.github.iverlein.shinkan.yml`
  mirrors fdroiddata; the first submission follows the 1.0 tag (ROADMAP
  block 10).
