# Shinkan — context for assistants

A child lock for Android that holds the whole device as its **device owner**:
app allow-list with hours, device restrictions, its own traffic filter as
the always-on lockdown VPN, a share target that blocks any link device-wide,
a built-in player that blocks creators, a parent PIN. The specification is
[docs/SPEC.md](docs/SPEC.md); read it before changing behaviour, and keep it
in step with the code. What is next: [docs/ROADMAP.md](docs/ROADMAP.md).

## How the rules are laid out

This file holds the layout, the commands, the session protocol and the bans
no path can catch. Rules bound to a part of the tree live in `.agents/rules/`,
one file per area, loaded when a file under its paths is read or edited:
Claude Code reads the directory through the symlink `.claude/rules`,
Antigravity reads it natively. An agent without such a loader opens the file
by hand before working there:

| Working in | Read | Holds |
| --- | --- | --- |
| `app/`, `core/`, `tools/emulator/` | `.agents/rules/app.md` | Platform policies for the hold, nothing of Shinkan's through the tunnel, the emulator and never a real device, how the hold and the filter are checked |
| `fastlane/`, `docs/fdroid/`, `app/build.gradle.kts`, `README.md` | `.agents/rules/release.md` | Docs on every feature merge, the permission check, releases |

## Layout

| Path | What |
| --- | --- |
| `core/` | Pure Kotlin: `Policy` and `Window` (what is usable when), `Restriction` (the `UserManager` keys), `PolicyText` (the stored form), `Pin`; `net/`: `Blocklist`, `Host`, `Families`, `Link` (what a shared text means), `BlocklistText`, `DnsQuestion` and `Udp4`/`Checksum` (the filter's packets). Tests in `core/src/test/`. No Android. |
| `app/` | Compose UI, package `shinkan.app`, application id `io.github.iverlein.shinkan`. `admin/`: `ShinkanAdmin` the device admin receiver, `DeviceHold` the policy applied through `DevicePolicyManager`, `PolicyStore`, `ReapplyReceiver`. `filter/`: `FilterService` the VPN, `BlocklistStore`, `ShareActivity` the share target. `ui/`: the pages of SPEC §8. Interface text in `res/values/strings.xml`. |
| `fastlane/metadata/android/` | F-Droid store listing per language. |
| `docs/` | `SPEC.md`, `ROADMAP.md`; `fdroid/` the recipe once submitted. |
| `tools/` | Python only (`tools/emulator/` to come, ROADMAP block 1). |

## Commands

```bash
./gradlew :core:test                      # the policy engine
./gradlew :app:assembleDebug              # APK in app/build/outputs/apk/debug/
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk   # the six of SPEC §5.1 and §4.2, nothing else
```

The toolchain on MONOLITH (JDK 21, SDK in `~/Android/Sdk`) is in
`~/RUNBOOK.md` §7. Always pass `-s <serial>` to adb. **Test on an emulator
only**, never on the owner's phone or any real device: a device owner is
removed only by the app itself or a factory reset. The emulator for this
project is `shinkan-test`, to be created without Google Play (ROADMAP block
1); never `zanshin-test`, which belongs to another project.

## One worktree per session

Several sessions may build at once; a shared checkout mixes their work. So
every session, from its first edit:

1. **Works in its own worktree**, never in the main checkout
   (`~/Documents/Git/Shinkan`), which stays on `main` with no uncommitted
   changes:

   ```bash
   cd ~/Documents/Git/Shinkan
   W=../Shinkan-wt/<name>
   git worktree add -b work/<name> $W main
   cp local.properties $W/
   ```

2. **Commits its work without asking** once it is verified (tests, the
   emulator check where it applies) and, for a feature, its docs are current:
   separate `core:` / `app:` / `docs:` commits with substantive bodies, no
   assistant signature.
3. **Rebases on main, tests again if the rebase touched shared files, then
   fast-forwards main** from the main checkout: `git merge --ff-only
   work/<name>`.
4. **Cleans up**: `git worktree remove --force $W`, `git branch -d
   work/<name>`.

Pushing waits for the owner's word.

## Docs on every feature merge

A feature is not merged until every place that describes the app says what
it now does: `docs/SPEC.md`, `docs/ROADMAP.md`, `README.md`, this file or the
rule file, the F-Droid listing. Only the changelog waits for the release.

## Rules that are easy to break

- **Nothing of Shinkan's through the tunnel** (SPEC §2.1): no endpoint, no
  update check, no list download. `INTERNET` is for forwarding the device's
  own traffic; the one request on the parent's behalf is the creator lookup
  of SPEC §7.3, to the platform. Check the merged manifest after adding a
  library.
- **Only platform policies for the hold** (SPEC §2.2): no accessibility
  service, no overlay, no root, no watchdog that kills apps. If
  `DevicePolicyManager` cannot do it, the answer is "not promised", written
  into SPEC §9.4.
- **The filter sees hosts, not pages** (SPEC §2.3): a creator is blocked by
  the player, never by a guess at traffic.
- **Every restriction and policy call is named in SPEC §4 or §5** next to its
  feature, with its `UserManager` or `DevicePolicyManager` name.
- **No data about the child**: no usage logs, no query logs, no statistics,
  nothing exported (SPEC §2.5).
- **No personal data in the repository**: it is public.
- **Commit verified work without asking**, from the session's own worktree;
  push only when the owner says; no assistant signature in commits.
