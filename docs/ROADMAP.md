# Shinkan — Roadmap

Blocks in order; each is one branch with tests and docs (AGENTS.md, *Docs
on every feature merge*). Spec sections in brackets.

| Block | What | State |
| --- | --- | --- |
| 0 | Skeleton: core model and tests, device owner receiver, the hold applied, the DNS filter as always-on lockdown VPN, the share target with sites, families and YouTube creators, three bare pages, docs, licence, F-Droid listing | done 2026-11-01 |
| 1 | `tools/emulator/`: create `shinkan-test` (no Play), provision by adb, restore; the emulator checks of SPEC §10 — the filter's first run on a device. The AVD itself exists since 2026-11-01 with a `clean` snapshot (AGENTS.md); the tool remains | |
| 2 | The PIN: set at first hold, gate on the parent door, the share target and release, rate limit, closes after two minutes [§9.1, §8.2] | |
| 3 | The policy editor: allowed apps from the launchable list, windows per app, restrictions with the two warnings; the block list as a list; text views [§4.4, §6.4, §8.2] | |
| 4 | Permissions of the allowed apps: camera, microphone, location denied per app [§4.5] | |
| 5 | The browser: managed configuration of the allowed browser (DoH off, URL allow-list where honoured); measure which F-Droid browsers honour which keys [§5.4, §9.4] | |
| 6 | QR provisioning: `tools/provision_qr.py` from the F-Droid APK's SHA-256 [§3.2] | |
| 7 | Release 1.0: fdroiddata recipe, screenshots, website page, the Releases workflow from Zanshin [§11] | |
| 8 | The full tunnel: all routes through a userspace relay; SNI read and refused, QUIC to blocked hosts dropped, resolvers by address blocked, known DoH hosts built in, per-app pass for the player [§5.2, §5.4, §5.5] | |
| 9 | The player on NewPipe Extractor: YouTube with the creator block list applied to channel, search, related and comments; a shared video resolved to its creator; the official app suspended and the family blocked when the first creator is added [§7] | |
| 10 | IPv6 in the tunnel [§5.2] | |
| A1 | Lock task features tuned on real devices: notifications, power dialog, keyguard [§4.1] | |
| A2 | The icon drawn: the crest by default; the gate, the drawn gate and the seal 関 chosen on the parent door [§8.5] | done 2026-11-01 |
| A3 | Translations: Russian first, Weblate as for Zanshin | |

## Decided

- 2026-11-01: device owner, not accessibility or overlays (SPEC §2.2).
- 2026-11-01: the app filters all the device's traffic itself, as the
  always-on lockdown VPN; `INTERNET` for the tunnel and nothing else
  (SPEC §2.1, §5). The earlier platform-only design (private DNS, browser
  configuration) stays as block 5, beside the filter.
- 2026-11-01: creators are blocked by a built-in player on NewPipe
  Extractor, not by reading screens or inspecting TLS (SPEC §7). Grayjay
  set aside: Source First is not free software.
- 2026-11-01: GPL-3.0-or-later.
- 2026-11-01: four icons from the name 真関, the crest by default, the
  choice on the parent door; the other four variants shown that day dropped
  (SPEC §8.5).
