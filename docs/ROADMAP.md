# Shinkan — Roadmap and build sheet

Blocks in order. Each block is one branch, built in its own worktree by the
protocol of [AGENTS.md](../AGENTS.md), and is **done** only when every item
of its *Done when* list holds, its docs are current (SPEC, this file's
*State* column, README, the listing) and main is fast-forwarded. Spec
sections in brackets; the spec rules where this sheet is silent.

## What every block needs read first

1. [AGENTS.md](../AGENTS.md): the worktree protocol, the commands, the bans.
2. [.agents/rules/app.md](../.agents/rules/app.md): platform policies only,
   nothing of Shinkan's through the tunnel, the emulator and its traps.
3. [SPEC.md](SPEC.md) §1, §2 and the sections the block names.
4. The block's own section below, then the code it names, before any edit.

Conventions that hold for every block:

- **Decisions in `core/`, calls in `app/`.** Anything with one right answer
  (what is open now, what a line means, what a rule matches) is a pure
  Kotlin function in `core/` with a JUnit 5 test beside it; `app/` turns
  the result into platform calls and Compose pages.
- **Strings in `app/src/main/res/values/strings.xml`**, never literals.
- **A new library** is checked for licence (free, on Maven Central or
  JitPack as NewPipe's own are) and for the merged manifest: no permission
  beyond the seven of SPEC §5.1, no connection of its own (§2.1).
- **Emulator only**: `shinkan-test`, never `zanshin-test`, never a phone.
  Install once, provision, test; a reinstall over the provisioned device
  applies the hold and cuts adb, and the clean snapshot is the way back
  (app rule, "Install once, then provision").
- **The debug build keeps adb** (SPEC §10): `Restriction.DEBUGGING_FEATURES`
  is not applied when `BuildConfig.DEBUG`, so the beta can be driven by
  `adb shell input` after the hold. The release build is checked by eye
  once per release (block 10).
- **Commits**: `core:`, `app:`, `docs:`, `tools:` prefixes, a body that
  says what changed and what was checked, no assistant signature.

## Order

**Release 1.0 needs** blocks 1, 2, 3a, 3b, 3e, 4, 5, 6a and 6b, then 10:
a child lock that holds the closed device with a filtered browser and a
player that hides blocked creators, behind a PIN, with the built-in list.
They are built in that order; the other blocks follow 1.0 in the table's
order. Numbers are identifiers and do not change when the order does.

| Block | What | State |
| --- | --- | --- |
| 0 | Skeleton: core model and tests, device owner receiver, the hold applied, the DNS filter as always-on lockdown VPN, the share target with sites, families and YouTube creators, three bare pages, docs, licence, F-Droid listing | done 2026-11-01 |
| 1 | The emulator tool: create, snapshot, provision, drive, restore `shinkan-test` | done 2026-11-01 |
| 2 | The PIN | done 2026-11-01 |
| 3a | The engine: forms, day plans, stages, rests, ceilings, in `core/` | done 2026-11-01 |
| 3b | The closed home: lock task, the two doors as stubs, landscape, sumi | done 2026-11-01 |
| 3c | The counter and the rest page | |
| 3d | The volume ceiling of the doors | |
| 3e | The parent's editor | |
| 4 | The built-in block list | |
| 5 | The browser door | |
| 6a | The player: playback and search on NewPipe Extractor; the filter passes Shinkan's own queries | |
| 6b | The player: the creator block list applied to every list | |
| 6c | The player: a shared video resolved to its creator; the official app held | |
| 6d | The player: SponsorBlock segments | |
| 7a | The open device: the launcher kept, the named apps closed | |
| 7b | The open device: `device` stages and the pinned rest page | |
| 7c | The open device: the device ceiling by clamp | |
| 8 | Permissions of the usable apps | |
| 9 | QR provisioning | |
| 10 | Release 1.0 | |
| 11a | The tunnel: all routes through a userspace TCP relay | |
| 11b | The tunnel: UDP relayed, QUIC to blocked hosts dropped | |
| 11c | The tunnel: SNI read and refused, resolvers by address refused, known DoH hosts built in | |
| 11d | The tunnel: the per-app pass for the player over TCP | |
| 12 | IPv6 in the tunnel | |
| A1 | Lock task features tuned on real devices: notifications, power dialog, keyguard [§4.1] | |
| A2 | The icon drawn: the crest by default; the gate, the drawn gate and the seal 関 chosen on the parent door [§8.5] | done 2026-11-01 |
| A3 | Translations: Russian first, Weblate as for Zanshin | |

## The blocks

### 1. The emulator tool [§10]

**Builds.** `tools/emulator/emulator.py` (Python 3, standard library plus
what `adb`, `avdmanager` and `emulator` give), one command with
subcommands: `create` (the AVD `shinkan-test` from
`system-images;android-37.0;google_apis;x86_64`, device `pixel_8`, no Play;
a no-op when it exists), `start` (with the window; sway places it),
`snapshot` (saves `clean` after a boot with nothing installed), `restore`
(loads `clean` through the emulator console, port 5554, `auth` with
`~/.emulator_console_auth_token`, `avd snapshot load clean`, then waits for
`sys.boot_completed`; works when adb is cut), `install` (the debug APK,
refusing when the package is already there), `provision` (`dpm
set-device-owner` of the beta's receiver, then opens the app), `shot` (a
screenshot to a path, by adb when up, else by the console's
`screenrecord screenshot`), `tap TEXT` (finds the node whose `text` is TEXT
in a `uiautomator dump` and taps its centre). `--serial` defaults to
`emulator-5554`.

**Leaves alone.** The app. The AVD that exists already is reused.

**Files.** `tools/emulator/emulator.py`, `tools/emulator/README.md`
(the commands and the traps), AGENTS.md's Commands section (one line per
subcommand).

**Done when.** From the main checkout: `restore` brings back a device with
no Shinkan; `install` then `provision` shows the held home of the beta;
`tap Parent` opens the door; `shot` writes a PNG both before and after adb
is cut (cut it on purpose with `adb shell settings put global adb_enabled
0`); `restore` again returns a clean device. The checks of SPEC §10 are
written as the README's checklist.

**Traps.** The AVD exists since 2026-11-01 with the snapshot `clean`.
`pm enable`/`pm disable` on a third-party component need `adb root`; never
`install -r` over the provisioned app. The emulator window must not be
moved or resized (machine rule 9).

### 2. The PIN [§9.1, §8.2, §8.3]

**Builds.** `core/`: `PinGate`, pure: given the count of wrong attempts,
the seconds to wait before the next (0, 0, 0, 5, 10, 20, 40, …, capped at
an hour), and `Pin` as it is. `app/`: a PIN page used by the first opening
after the hold (set it, twice), the parent door (enter it), the share
target (enter it before *Block*) and release (enter it again); the wait
shown as a countdown; the door closes to the home after two minutes
without a touch. `PolicyStore.pinHash` exists; add the attempt count and
the last attempt's time to the store.

**As built.** The PIN page has its own keypad (no keyboard; `tap 4` drives
it). The door's idle time is read in the beta from the system property
`debug.shinkan.door_idle` (seconds); the door also closes on `ON_STOP`.
The check found the filter's reload, sent by the share target before the
hold had started the filter, killing the app (a `startForegroundService`
that never reached `startForeground`); a reload now starts a filter that
is not up.

**Leaves alone.** Everything the PIN gates: the door's contents stay the
skeleton's.

**Files.** `core/src/main/kotlin/shinkan/core/Pin.kt` (add `PinGate`),
`core/src/test/.../PinTest.kt`, `app/.../ui/PinPage.kt`,
`app/.../ui/ShinkanApp.kt`, `app/.../filter/ShareActivity.kt`,
`app/.../admin/PolicyStore.kt`, strings.

**Done when.** `PinTest` covers the wait sequence and the cap; on the
emulator, a wrong PIN shows the wait and a right one opens the door; the
door is back to the home two minutes after the last touch (shorten the
timeout by a debug-only constant, never by a shipped setting); the share
target asks for the PIN.

### 3a. The engine [§4.1, §4.2, §4.4, §4.6, §4.7]

**Builds.** The policy model of the spec, replacing `allowed`: `Policy(mode,
plans, closed, windows, volumes, restrictions)`; `Mode { CLOSED, OPEN }`;
`Door { BROWSER, PLAYER, DEVICE }`; `Plan(days, stages)`; `Stage.Span(from,
to, doors)` and `Stage.Budget(doors, minutes, rest: Rest?)`; `Rest.Minutes(n)`
and `Rest.Parent`; `PlanState(stage index, seconds spent in it, rest end)`
as the running state the app stores. Functions: `Policy.openAt(now, state):
Set<Door>`; `Policy.closedPackagesAt(now): Set<String>` for the open device;
`Policy.nextChange(now, state): LocalDateTime?` (the next clock edge, or
`now` plus the budget's remainder, or the rest's end); `PlanState.advance`
rules as §4.7 words them. `PolicyText` parses and renders the whole §4.4
form: `mode`, `plan` with indented stages, `close`, `window`, `volume`,
`restrict`; `allow` is gone. The smallest change in `DeviceHold.applyPackages`
and `ShinkanApp.HomePage` that keeps the app building and the skeleton's
behaviour (everything suspended on the closed device).

**Leaves alone.** Pages, counting, lock task: 3b and 3c.

**As built.** `PlanState(day, stage, spent, restEnd, restParent)`: the plan
day is kept so a new day starts fresh, and `rest parent` is a flag, not a
time. Beside the functions named: `Policy.resolve` (the state carried to a
moment by the clock), `count` (use added), `openNow`, `budgetLeft`,
`nextOpening` (for 3b's greyed doors). The day's edges are written into
SPEC §4.7. `DeviceHold.applyPackages` suspends every launchable package on
the closed device and the closed ones outside their windows on the open
one, and pins Shinkan alone in lock task; the home shows nothing to open
until 3b.

**Files.** `core/.../Policy.kt`, `core/.../PolicyText.kt`, `PolicyTest.kt`
(rewritten), a new `PlanTest.kt`; `app/.../admin/DeviceHold.kt`,
`app/.../ui/ShinkanApp.kt`.

**Done when.** Tests: both examples of §4.4 parse and render back
identically; the three-stage weekday plan opens both doors at 09:59 and
only the player at 10:00; thirty minutes of the player end the budget and
start a ninety-minute rest; the rest's end opens the browser alone; a span
crossing midnight belongs to its first day; `rest parent` never ends by
clock; `nextChange` gives the right edge in each case; an `open` policy
closes a package outside its windows and never a door. `./gradlew :core:test
:app:assembleDebug` passes.

### 3b. The closed home [§4.1, §8.1, §8.6]

**Builds.** `DeviceHold` on the closed device: Shinkan set as the default
home by the device owner itself (`addPersistentPreferredActivity` with the
HOME filter on `MainActivity`), `setLockTaskPackages(admin, [self])`,
`setLockTaskFeatures(admin, LOCK_TASK_FEATURE_NONE)`, and `MainActivity`
calls `startLockTask()` when it is owner and the mode is closed. The home
page: two large buttons, *Browser* and *YouTube*, one above the other in
portrait and side by side in landscape (`LocalConfiguration.orientation`),
each greyed with its next opening time when closed, each opening a stub
door page that names the block that fills it (5 or 6). Landscape on every
page: no `screenOrientation` in the manifest, pages laid out for both. The
sumi theme in `Theme.kt` and `themes.xml` (`values-night/`), following
`isSystemInDarkTheme()`. The debug build's adb exception of SPEC §10.

**Leaves alone.** Counting (3c), ceilings (3d), the editor (3e).

**As built.** The hold is applied when the PIN is set at the first opening
(SPEC §3.1), and `MainActivity` enters lock task in `onResume` once the PIN
is set. The open form clears the persistent home and the lock task packages
(the rest of 7a waits). The greyed door uses `Policy.nextOpening`; the home
reads the plan state as fresh until 3c keeps it. `emulator.py store KEY
FILE` writes the policy or the block list into the beta's store and ends
its process through `run-as`: `am force-stop` does not reach a device
owner, and the closed home comes back by itself.

Two faults of block 0 found by the check: the filter read the networks
without `ACCESS_NETWORK_STATE` and killed the app at the first forwarded
query (the permission is added, the seventh, SPEC §5.1); and lockdown with
the DNS-only route leaves the held device no network but DNS (SPEC §9.4,
*Open* below).

**Files.** `app/.../admin/DeviceHold.kt`, `app/.../MainActivity.kt`,
`app/.../ui/ShinkanApp.kt` (split pages into `ui/HomePage.kt`,
`ui/DoorPage.kt`), `app/.../ui/Theme.kt`, `res/values/themes.xml`,
`res/values-night/themes.xml`, strings, `AndroidManifest.xml` (no
orientation attribute).

**Done when.** On the provisioned emulator: the home is Shinkan's two
buttons; Home, Back and Recents do not leave it; the launcher is not
reachable; `adb shell settings put system accelerometer_rotation 0` then
`user_rotation 1` lays the doors side by side; `adb shell cmd uimode night
yes` shows sumi, `no` shows rice paper; a stub door opens and closes; adb
stays up on the debug build.

**Traps.** The launcher alias trap of §8.5 (the task finishes when its alias
is disabled). A device owner may start lock task from its own activity
without a prompt only when its package is in `setLockTaskPackages`.

### 3c. The counter and the rest page [§4.7, §8.7]

**Builds.** `app/.../plan/PlanClock.kt`: a receiver for `ACTION_SCREEN_ON`,
`ACTION_SCREEN_OFF`, `ACTION_USER_PRESENT`, registered by `FilterService`
(always running), and the doors' lifecycle reported by `DoorPage`; it
accumulates seconds into `PlanState` through `PolicyStore` (device-protected,
survives reboot), and on each change asks `DeviceHold.apply`, which uses
`Policy.openAt` and `nextChange` and sets the exact alarm. When a budget is
spent: a local broadcast the door pages pause on (`Door.pause()`), then the
rest page. `RestPage`: the ensō ring on a Compose `Canvas`, an arc of
varying stroke drawn as three overlaid arcs of different widths and
alpha, in vermilion (`gate`, or the lighter vermilion on sumi), closing
clockwise with the rest; the minutes left in large figures; whole and
still for `rest parent`; the *Parent* link. Both orientations.

**Leaves alone.** The open device's `device` stages (7b).

**Files.** `app/.../plan/PlanClock.kt`, `app/.../filter/FilterService.kt`
(registers it), `app/.../admin/PolicyStore.kt` (state), `app/.../admin/
DeviceHold.kt`, `app/.../ui/RestPage.kt`, `app/.../ui/DoorPage.kt`.

**Done when.** With `player 2 rest 3` on the emulator: the player stub
open, after two minutes the rest page shows with the ring open; `adb shell
input keyevent KEYCODE_SLEEP` during the budget stops the count (the budget
outlasts the sleep by the sleep's length); after three minutes the home is
back and the browser stage is open; `adb reboot` in a rest comes back in
the same rest with the same end; `rest parent` stays until *Open now* on the
door.

### 3d. The volume ceiling of the doors [§4.6]

**Builds.** `MainActivity.onKeyDown` for `KEYCODE_VOLUME_UP` and `_DOWN`
while a door is in front: `AudioManager.setStreamVolume(STREAM_MUSIC, …)`
never above the door's ceiling, a share of `getStreamMaxVolume`; on opening
a door whose ceiling is below the current volume, the volume is set to the
ceiling; `setVolumeControlStream(STREAM_MUSIC)` on the door pages. The
ceiling comes from `Policy.volumes[door]`.

**Leaves alone.** The device ceiling (7c), ringer and alarm streams.

**Files.** `app/.../MainActivity.kt`, `app/.../ui/DoorPage.kt`.

**Done when.** With `volume player 20`: inside the player stub, volume up
stops at 20 % (`adb shell dumpsys audio | grep -A3 STREAM_MUSIC` shows the
index), volume down works; on the home page the keys are the system's.

### 3e. The parent's editor [§8.2, §4.4]

**Builds.** The door's pages for the form (two radio buttons), the plans
(a list of days-sets, each a list of stages added, edited, reordered,
removed, with time pickers and minute fields), the closed apps of the open
device (the launchable list with a check and windows each), the volumes,
the restrictions with the two warnings, the theme pin (`system`, `rice
paper`, `sumi` in `PolicyStore`), and the text view: the policy as
`PolicyText.render`, editable, parsed on save with the `Malformed` line
shown. *Apply now* runs `DeviceHold.apply`.

**Leaves alone.** The block list's pages (block 4 adds the categories, 6d
the segments).

**Files.** `app/.../ui/ParentPage.kt` and sub-pages under `ui/parent/`,
`app/.../ui/Theme.kt` (the pin), strings.

**Done when.** On the emulator: a plan built on the door renders to the
text of §4.4 and back; a malformed line pasted in the text view names its
line; the theme pin overrides the system; every page works in landscape.

### 4. The built-in block list [§6.5]

**Builds.** `tools/blocklist/refresh.py` (Python): downloads Hagezi Multi
Normal in wildcard-domain format and StevenBlack's `extensions/gambling`,
`porn`, `fakenews`, `social` hosts files with their licence texts into
`app/src/main/assets/blocklist/`, writes `VERSIONS.md` with the dates and
commits fetched; run by the maintainer before a release, never by the
app. `core/`: `BuiltinList` (a `Set<String>` of domains and the suffix walk
`a.b.example.org` → `b.example.org` → `example.org`), `Blocklist` gains
`categories: Set<Category>` and `passes: Set<String>`, `blocksHost` consults
parent sites, then the built-in set for the enabled categories, then
`passes`; `BlocklistText` gains `pass example.org` and `category gambling`.
`app/`: `BlocklistStore` loads the assets once into memory; the door's
block-list page gets the four category switches.

**Leaves alone.** The filter's packet path.

**Files.** `tools/blocklist/refresh.py`, `app/src/main/assets/blocklist/*`,
`core/.../net/Blocklist.kt`, `BlocklistText.kt`, `BlocklistTest.kt`,
`app/.../filter/BlocklistStore.kt`, the door, strings, `LICENSE` notes in
`README.md` (the lists' licences named).

**Done when.** Tests: the suffix walk, a `pass` over a built-in name, a
category off. On the emulator (debug, adb up): `adb shell ping -c 1
ads.doubleclick.net` fails to resolve, `ping -c 1 example.org` resolves,
and a gambling host resolves until the category is switched on. The APK
grows by the lists and nothing else (`aapt2 dump permissions` unchanged).

### 5. The browser door [§7.6, §2.1]

**Builds.** `core/.../net/FilterList.kt`: a parser and matcher for the
network rules of EasyList and EasyPrivacy (Adblock Plus syntax): `||host^`,
`|` anchors, plain substrings with `*` and `^`, exceptions `@@`, the
options `third-party`, `~third-party`, `domain=`, and the type options
`script`, `image`, `stylesheet`, `xmlhttprequest`, `subdocument`, `font`,
`media`, `other`; every other rule (cosmetic `##`, `#@#`, `$csp`,
`$redirect`, `$removeparam`, regex rules) is dropped at parse time and
counted. Pure, with tests from real lines of the lists. `tools/blocklist/
refresh.py` also fetches `easylist.txt` and `easyprivacy.txt` into the
assets. `app/.../ui/BrowserPage.kt`: a `WebView` with
`shouldInterceptRequest` returning an empty response for a blocked
request, JavaScript on, third-party cookies off, no file access, a start
page the parent sets, an address bar, back, reload; `onPause` and
`pauseTimers` on `Door.pause()`; the ceiling of 3d; downloads off; no
history list, cookies and storage kept so sites work.

**Leaves alone.** DNS: the WebView resolves through the system, which the
filter answers.

**Files.** `core/.../net/FilterList.kt`, `FilterListTest.kt`, the assets,
`app/.../ui/BrowserPage.kt`, `app/.../ui/DoorPage.kt` (the stub replaced),
strings, SPEC §7.6 (what the browser keeps, once decided).

**Done when.** Tests: twenty real rules, each with a URL it matches and
one it must not, the options above, an exception beating a block. On the
emulator: `https://example.org` renders; a page that loads
`https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js` (served
from a local test page) shows the request blocked in the page's own
error handler; a rest pauses a playing video on the page; the ceiling holds.

### 6a. The player: playback and search [§7.1, §7.2, §5.5]

**Builds.** Dependencies: NewPipe Extractor (GPL-3.0, JitPack, as NewPipe
itself), AndroidX Media3 ExoPlayer (Apache-2.0), OkHttp (Apache-2.0) for
the extractor's `Downloader`; check the merged manifest after adding them.
`app/.../player/`: the `Downloader` on OkHttp with a fixed desktop
user agent; a `PlayerPage` with search, a result list (title, uploader,
duration, thumbnail), a stream page with Media3 playing the best
progressive stream, full screen in landscape; the channel page by
uploader. `FilterService` answers a query from Shinkan's own uid
unfiltered: `ConnectivityManager.getConnectionOwnerUid(IPPROTO_UDP, source,
destination)` for the datagram just read, compared with `Process.myUid()`
(§5.5; the family of §7.1 is blocked for every other app from 6c).

**Leaves alone.** The creator list (6b), segments (6d).

**Files.** `gradle/libs.versions.toml`, `app/build.gradle.kts`,
`settings.gradle.kts` (JitPack), `app/.../player/*`, `app/.../ui/
PlayerPage.kt`, `app/.../filter/FilterService.kt`, strings.

**Done when.** On the emulator: a search returns results, a result plays
with sound under the ceiling, rotation goes full screen; `aapt2 dump
permissions` unchanged; the merged manifest shows no new component; with
`site youtube.com` on the block list the player still plays while `adb
shell ping -c 1 www.youtube.com` fails to resolve.

### 6b. The player: the creator block list applied [§6.3, §7.1]

**Builds.** Every list the extractor returns (search, related, channel
contents, comments, a channel page itself) filtered by
`Blocklist.creators` on the uploader's url or id (`Creator.ref` normalised
as `Link.parse` does); a blocked channel page says so instead of loading.
Pure matching in `core/` (`Blocklist.hidesUploader(url)`), with tests.

**Done when.** Tests for `@handle`, `/channel/UC…`, `/c/`, `/user/` forms.
On the emulator: with `creator youtube @somehandle` on the list, that
channel's videos are missing from search and related, and the channel
page is refused.

### 6c. The player: a shared video; the official app held [§7.3, §6.2, §6.3]

**Builds.** `ShareActivity` for `Link.Item`: the extractor's `StreamInfo`
gives the uploader, shown by name, blocked as a creator on *Block* (the
`share_item_later` string goes). When the first creator is added:
`BlocklistStore` adds the YouTube family as a site and `DeviceHold`
suspends the official app's packages (`com.google.android.youtube`,
`com.google.android.apps.youtube.*`), telling the parent on the door.

**Done when.** On the emulator: sharing a video URL to *Block in Shinkan*
names the channel and blocks it; the family appears on the list and
`www.youtube.com` stops resolving for `ping` while the player plays.

### 6d. The player: SponsorBlock segments [§7.5, §2.1]

**Builds.** `app/.../player/Segments.kt`: `GET
https://sponsor.ajay.app/api/skipSegments/<prefix>?categories=[…]` with the
first four hex characters of the SHA-256 of the video id, the response
filtered to the video's own entry, the segments skipped by a Media3
position listener; the categories on the door (`sponsor`, `selfpromo`,
`interaction` on by default; `intro`, `outro`, `preview`, `music_offtopic`,
`filler` off); a mirror address field; the credit line in the player's
about text (data CC BY-NC-SA 4.0). Pure parts in `core/`: the prefix, the
response parsing, the skip decision for a position, with tests. Take the
request shape from Tubular (GPL-3.0) and name the origin in the file.

**Done when.** Tests for the prefix and the skip decision. On the
emulator: a video with known segments jumps past them; the request goes to
the API host only (check in the filter's log at debug level); with the
mirror set to an unreachable address the video plays without skips and the
player says so.

### 7a. The open device: the launcher kept, the named apps closed [§4.1, §4.2]

**Builds.** `DeviceHold` on `mode open`: `clearPackagePersistentPreferred-
Activities`, lock task packages empty, no `startLockTask`; the closed
packages suspended per `Policy.closedPackagesAt`; Shinkan opened as an app
shows the closed apps with their next window and the *Parent* link; the
door's closed-apps page (3e) in use.

**Done when.** On the emulator (debug): the system launcher is the home;
a closed app's icon opens the suspension dialog; a window opens it at its
time; the browser and the player open from the launcher.

### 7b. The open device: `device` stages and the pinned rest [§4.7, §8.7]

**Builds.** `PlanClock` counts screen-on, unlocked time for `device`
stages; when a budget is spent `MainActivity` is started with
`FLAG_ACTIVITY_NEW_TASK` on the rest page and calls `startLockTask()`
(the package is put in `setLockTaskPackages` for the rest's length);
`stopLockTask()` when the rest ends or the parent opens.

**Done when.** With `device 2 rest 2`: two minutes after unlock the rest
page covers whatever app was in front and Home does not leave it; after
two more minutes the launcher is back.

### 7c. The open device: the device ceiling [§4.6]

**Builds.** `FilterService` registers `AudioManager.VOLUME_CHANGED_ACTION`
and, when `STREAM_MUSIC` exceeds `Policy.volumes[DEVICE]`, sets it back to
the ceiling.

**Done when.** With `volume device 30`, the system volume slider snaps
back to 30 % within a second; lower values hold.

### 8. Permissions of the usable apps [§4.5]

**Builds.** `permission <package> deny camera|microphone|location` lines in
`PolicyText`; `DeviceHold` applies `setPermissionGrantState` with
`PERMISSION_GRANT_STATE_DENIED` for the matching runtime permissions; the
door's closed-apps page gets the three switches per app.

**Done when.** Tests for the lines; on the emulator, the camera app with
camera denied shows the system's refusal.

### 9. QR provisioning [§3.2]

**Builds.** `tools/provision_qr.py`: from a release APK's URL and SHA-256,
the JSON of `android.app.extra.PROVISIONING_*` for the receiver, rendered
as a QR PNG (a pure-Python QR encoder, no network). The setup page shows
the QR path beside the adb one.

**Done when.** A factory-reset emulator provisioned by the QR flow (six
taps on the welcome screen) ends on Shinkan's setup page with the hold
applied.

### 10. Release 1.0 [§11]

**Builds.** The fdroiddata recipe in `docs/fdroid/`, screenshots in
`fastlane/`, the changelog `fastlane/.../changelogs/<versionCode>.txt`,
the release build checked on the emulator by eye (R8 on; the receiver,
the aliases and the strings survive), the permission check, the tag.

**Done when.** `aapt2 dump permissions` on the release APK shows the seven
and nothing else; the release build on a clean emulator provisions, holds,
filters and plays; the tag is pushed by the owner.

### 11a–11d. The tunnel [§5.2, §5.4, §5.5]

**11a** routes all IPv4 through the VPN and relays TCP in user space
(a `protect`ed socket per connection, a small select loop; study
RethinkDNS's Go relay for the shape, write it in Kotlin). **11b** relays
UDP the same way and drops UDP 443 to blocked hosts so QUIC falls back to
TLS. **11c** reads the SNI of a TLS ClientHello (pure parser in `core/`,
tested on captured hellos) and refuses blocked names; refuses connections
to resolver addresses on port 53 and 853 other than the filter's own;
ships the known DoH hosts in the built-in list. **11d** passes the
player's TCP by uid (`getConnectionOwnerUid(IPPROTO_TCP, …)`).

**Done when.** Each: the checks of SPEC §10 for the filter, plus a speed
check (a 50 MB download through the relay within twice the direct time on
the emulator).

### 12. IPv6 in the tunnel [§5.2]

**Builds.** `Udp6`, the IPv6 route and the relay for both families.

## Open

- 2026-11-01: lockdown (SPEC §5.1) with the DNS-only tunnel of blocks 0 to
  10 refuses every app's traffic but DNS: a held device resolves names and
  reaches nothing. Either `setAlwaysOnVpnPackage(…, lockdown = false)` until
  block 11a routes everything (the filter is then bypassed while the VPN is
  down, which always-on keeps short), or lockdown stays and 11a moves ahead
  of 5 and 6a, whose browser and player need the network. The owner's call.

## Decided

- 2026-11-01: device owner, not accessibility or overlays (SPEC §2.2).
- 2026-11-01: the app filters all the device's traffic itself, as the
  always-on lockdown VPN; `INTERNET` for the tunnel and nothing else
  (SPEC §2.1, §5).
- 2026-11-01: creators are blocked by a built-in player on NewPipe
  Extractor, not by reading screens or inspecting TLS (SPEC §7). Grayjay
  set aside: Source First is not free software.
- 2026-11-01: GPL-3.0-or-later.
- 2026-11-01: four icons from the name 真関, the crest by default, the
  choice on the parent door; the other four variants shown that day dropped
  (SPEC §8.5).
- 2026-11-01: two forms of the hold, the closed device first (Shinkan the
  whole home, two doors: browser and player), the open device second (the
  launcher kept, the named apps closed) (SPEC §4.1).
- 2026-11-01: ads and trackers blocked from a bundled list (Hagezi,
  StevenBlack) with the parent's categories; oisd set aside for want of a
  licence (SPEC §6.5). Sponsor segments skipped with SponsorBlock, the
  code from Tubular, the lookup by hash prefix as the second named request
  (SPEC §7.5). The browser is Shinkan's own WebView page with EasyList and
  EasyPrivacy: no F-Droid browser can be configured by the device owner, so
  the earlier managed-configuration block is dropped (SPEC §7.6).
- 2026-11-01: landscape on every page from the first real UI; two themes,
  rice paper (light) and sumi (dark) (SPEC §8.6). A parent-set volume
  ceiling, per door on the closed device, one for the open device (SPEC
  §4.6). A day plan of stages: clock spans with the doors open in them,
  budgets of minutes per door, rests in minutes or until a parent opens,
  shown as a closing ring (SPEC §4.7, §8.7).
- 2026-11-01: the roadmap sliced into blocks a session can build and
  verify alone; the debug build keeps adb so the emulator can be driven
  after the hold (SPEC §10); the filter tells Shinkan's own queries by uid
  from block 6a, so the player works before the full tunnel (SPEC §5.5).
