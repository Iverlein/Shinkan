# Shinkan — Roadmap

Blocks in order; each is one branch with tests and docs (AGENTS.md, *Docs
on every feature merge*). Spec sections in brackets.

| Block | What | State |
| --- | --- | --- |
| 0 | Skeleton: core model and tests, device owner receiver, the hold applied, three bare pages, docs, licence, F-Droid listing | done 2026-11-01 |
| 1 | `tools/emulator/`: create `shinkan-test` (no Play), provision by adb, restore; the emulator checks of SPEC §8 | |
| 2 | The PIN: set at first hold, gate on the parent door, rate limit, closes after two minutes [§6.1, §7.2] | |
| 3 | The policy editor: allowed apps from the launchable list, windows per app, restrictions with the two warnings; text view [§5.4, §7.2] | |
| 4 | Permissions of the allowed apps: camera, microphone, location denied per app [§5.6] | |
| 5 | The web: private DNS host with lock; managed configuration of the allowed browser; measure which F-Droid browsers honour which keys [§5.5] | |
| 6 | QR provisioning: `tools/provision_qr.py` from the F-Droid APK's SHA-256 [§4.2] | |
| 7 | Release 1.0: fdroiddata recipe, screenshots, website page, the Releases workflow from Zanshin [§9] | |
| A1 | Lock task features tuned on real devices: notifications, power dialog, keyguard [§5.1] | |
| A2 | The icon drawn (the gate of `ic_launcher_foreground.xml` is a placeholder) | |
| A3 | Translations: Russian first, Weblate as for Zanshin | |

## Decided

- 2026-11-01: device owner, not accessibility or overlays (SPEC §2.2).
- 2026-11-01: the app never sees traffic; the web is handled through private
  DNS and the browser's managed configuration (SPEC §5.5).
- 2026-11-01: GPL-3.0-or-later.
