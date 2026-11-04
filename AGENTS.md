# Shinkan — context for assistants

A child lock for Android that holds the whole device as its **device owner**:
app allow-list with hours, device restrictions, its own traffic filter as
the always-on lockdown VPN, a share target that blocks any link device-wide,
a built-in player that blocks creators, a parent PIN. The specification is
[docs/SPEC.md](docs/SPEC.md); read it before changing behaviour, and keep it
in step with the code. What is next, block by block with what each builds and how it is proved:
[docs/ROADMAP.md](docs/ROADMAP.md); a session builds one block and reads
its section first.

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
| `core/` | Pure Kotlin: `Policy` and `Window` (what is usable when), `Restriction` (the `UserManager` keys), `PolicyText` (the stored form), `Pin`, `Volume`; `net/`: `Blocklist`, `BuiltinList` and `Category` (the built-in list), `Host`, `Families`, `Link` (what a shared text means), `BlocklistText`, `Segments` (the lookup of §7.5), `DnsQuestion` and `UdpDatagram`/`Checksum` (the filter's packets), `TcpSegment` and `TcpFlow` (the relay's connections), `SeenHosts` (the names of addresses), `ClientHello` (the name TLS shows). Tests in `core/src/test/`. No Android. |
| `app/` | Compose UI, package `shinkan.app`, application id `io.github.iverlein.shinkan`. `admin/`: `ShinkanAdmin` the device admin receiver, `DeviceHold` the policy applied through `DevicePolicyManager`, `PolicyStore`, `ReapplyReceiver`. `filter/`: `FilterService` the VPN, `Relay` its TCP, `BlocklistStore`, `ShareActivity` the share target. `ui/`: the pages of SPEC §8. `LauncherIcon`: the alias switch for the icon of SPEC §8.5, drawn in `res/drawable/icon_*.xml`. Interface text in `res/values/strings.xml`. |
| `fastlane/metadata/android/` | F-Droid store listing per language. |
| `docs/` | `SPEC.md`, `ROADMAP.md`; `fdroid/` the recipe once submitted. |
| `tools/` | Python only. `emulator/`: `emulator.py`, the emulator tool, `stand.py`, the stand of the QR flow, and their README with the traps and the checks of SPEC §10. `blocklist/refresh.py`: the built-in block list fetched into `app/src/main/assets/blocklist/` before a release. `provision_qr.py`: the QR code of SPEC §3.2, with `test_provision_qr.py`. |

## Commands

```bash
./gradlew :core:test                      # the policy engine
./gradlew :app:assembleDebug              # APK in app/build/outputs/apk/debug/
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk   # the seven of SPEC §5.1, nothing else
python3 tools/blocklist/refresh.py               # the built-in block list, before a release (--check: are the assets whole)
python3 tools/provision_qr.py --url … --apk … out.png   # the QR code that provisions a device just reset
python3 tools/test_provision_qr.py               # its codes read back by zbarimg
python3 tools/emulator/emulator.py create        # the AVD shinkan-test; nothing when it exists
python3 tools/emulator/emulator.py start         # boot with the window, from the snapshot clean (--cold: a full boot)
python3 tools/emulator/emulator.py snapshot      # save clean after a boot with nothing installed
python3 tools/emulator/emulator.py restore       # load clean through the console; works when adb is cut
python3 tools/emulator/emulator.py install       # the debug APK; refused when it is there
python3 tools/emulator/emulator.py provision     # make the beta the device owner, then open it
python3 tools/emulator/emulator.py shot out.png  # a screenshot, by adb or else by the console
python3 tools/emulator/emulator.py tap Parent    # tap the node whose text is "Parent"
python3 tools/emulator/emulator.py type --field 1 --clear - < policy.txt   # lines typed into a field
python3 tools/emulator/emulator.py ctap 540 1786 # a tap through the console, for the release whose hold cuts adb
python3 tools/emulator/emulator.py store policy plan.txt   # the beta's policy (or blocklist, or plan state) written, the app restarted
python3 tools/emulator/emulator.py stop          # quit; nothing is saved
python3 tools/emulator/stand.py create           # the AVD shinkan-stand, for the QR flow alone
python3 tools/emulator/stand.py reset            # wipe it and mark setup not finished
python3 tools/emulator/stand.py flow             # the QR flow from the code to the hold, checked (--apk, --package)
python3 tools/emulator/stand.py stop
```

The toolchain on MONOLITH (JDK 21, SDK in `~/Android/Sdk`) is in
`~/RUNBOOK.md` §7. Always pass `-s <serial>` to adb. **Test on an emulator
only**, never on the owner's phone or any real device: a device owner is
removed only by the app itself or a factory reset. The emulator for this
project is `shinkan-test` (Pixel 8, `system-images;android-37.0;google_apis;x86_64`,
no Play; created 2026-11-01, its clean boot saved as the snapshot `clean`,
which `restore` loads); never `zanshin-test`, which belongs to another
project. `shinkan-stand` is the second AVD, of the same image: wiped for every run of
the QR flow, on `emulator-5560`, for nothing else.
`tools/emulator/README.md` holds the traps and the checklist.

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

5. **Pushes main** right after the fast-forward (owner's standing word,
   2026-11-01); no other branch is pushed.

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
  push main after every fast-forward; no assistant signature in commits.
