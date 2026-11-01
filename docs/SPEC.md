# Shinkan — Specification

Status: draft of 2026-11-01, revised the same day for the filter (§5), the
block list (§6), the player (§7), the icon (§8.5), the two forms of the
hold (§4.1), the built-in list (§6.5), segments and the browser (§7.5,
§7.6), the look (§8.6), the volume ceiling (§4.6) and screen time (§4.7).

## 1. What it is

Shinkan (真関, "the true barrier": 関 is the checkpoint on an old Japanese
road) holds an Android device for a child. It is made the **device owner**
once, on a freshly set-up device, and from then on decides which apps exist
for the child, at which hours, what the device itself may do, and what the
device may reach on the network. A parent opens it with a PIN that lives only
on the device.

The hold has two forms (§4.1). On the **closed device**, the first, Shinkan
is the child's whole home: two doors, a browser and a YouTube player, both
Shinkan's own and both filtered, each at the hours the parent set; every
other app is suspended. On the **open device**, the second, the child keeps
the launcher and the parent closes the apps named, at the hours named.

Any link shared to Shinkan from any app is blocked device-wide, with every
subdomain and the platform's other hosts. A creator on YouTube (and the
other platforms of §7) is blocked by sharing their page: the official app and
site are held, and the built-in player hides the creator everywhere.

There is no account, no server and no companion app. Shinkan is free software
on F-Droid.

## 2. Principles

1. **Nothing of Shinkan's goes out.** The filter forwards the device's own
   traffic and that alone: Shinkan has no endpoint, no update check, no
   list download. The `INTERNET` permission exists for the tunnel. Two
   requests on the child's behalf are named, and no other: the creator
   lookup to the platform (§7.3) and the segment lookup to the SponsorBlock
   API, by hash prefix (§7.5). The built-in block list (§6.5) and the
   browser's filter lists (§7.6) ship inside the APK and are refreshed with
   each release, never downloaded. Reviewed on every change to
   `app/.../filter/`.
2. **The platform's own policies for the hold.** Every control of §4 is one
   documented call on `DevicePolicyManager` or one `UserManager`
   restriction, named next to the feature. No accessibility service, no
   overlay, no root. Shinkan's own pages, the browser and the player, are
   governed by Shinkan itself (their hours, their volume ceiling, their
   filters): that is an app ruling its own windows, not a policy over
   others.
3. **The filter is honest about what it sees.** Encrypted traffic shows a
   host, never a page: a site is blocked by its hosts (§6), a creator by the
   player that knows them (§7). What cannot be done is written in §9.4, not
   promised.
4. **The parent can always get out.** Release (§9.3) lifts everything and
   gives the ownership back; the PIN is the only key, and what happens when
   it is lost is written down (§9.4).
5. **Nothing about the child leaves the device.** No usage statistics, no
   query logs beyond the system's, nothing to export.
6. **Decisions are pure.** What is usable when, what a shared link means,
   whether a host is blocked, how a DNS refusal looks: plain Kotlin in
   `core/` with tests; `app/` only applies the result.

## 3. Taking the device

Android lets one app be the device owner, set only while the device has no
accounts and (on most builds) only through provisioning.

### 3.1 By adb

On a device just after setup, with no Google or other account added and USB
debugging on:

```
adb shell dpm set-device-owner io.github.iverlein.shinkan/shinkan.app.admin.ShinkanAdmin
```

The setup page (§8.3) shows this command. The command alone changes no
policy; once the setup of §8.3 is completed Shinkan applies the default
restrictions (§4.3), which include `no_debugging_features`: adb stops working
on the device, by design, so the child cannot use it either.

### 3.2 By QR code at first boot

Tapping the welcome screen six times opens the QR provisioning flow, which
downloads the APK from a URL and verifies its checksum. ROADMAP block 9.

### 3.3 What a device owner is not

A device owner is not root. It cannot read other apps' data, cannot decrypt
their traffic, cannot stop the recovery-mode wipe of a device without factory
reset protection. §9.4 lists what a determined child can still do.

## 4. The hold

### 4.1 Two forms: the closed device and the open device

**The closed device** (ROADMAP block 3, the first form). Shinkan registers
as a launcher (`CATEGORY_HOME`) and the parent makes it the default home
once; lock task mode (`setLockTaskPackages`, Shinkan alone) keeps the child
in it: no other launcher, no status-bar settings, no recents. The child's
page (§8.1) has two doors and nothing else: the browser (§7.6) and the
player (§7), each usable within its windows; every other launchable package
is suspended.

**The open device** (block 7, the second form). The device keeps its own
launcher and Shinkan is one app among the others; lock task is off. The
packages the parent names are **closed**: suspended always, or outside
their windows. Everything else of the hold stays: the restrictions of
§4.3, the filter of §5 with the block list of §6, the browser and the
player as apps the child may open.

The form is one line of the policy text (`mode`, §4.4) and is changed on the
parent door; changing it applies the policy at once.

### 4.2 Packages: usable, windowed, suspended

At every application of the policy (`DeviceHold.apply`), each launchable
package is either usable now, and resumed, or not, and **suspended**
(`setPackagesSuspended`): on the closed device every package but Shinkan;
on the open device the closed ones outside their windows. Installs and
uninstalls are blocked by restriction (§4.3), so the set of packages changes
only through the parent.

A window is when a thing is usable: a door of the closed device with no
window is open always, one with windows only inside one; a closed app of the
open device with windows is usable inside them and suspended outside
(`Policy.usableAt`). The policy is applied again at boot, when the clock or
the zone changes, and by an exact alarm at the next window edge
(`Policy.nextChange`). The clock is protected by `no_config_date_time`.

### 4.3 Device restrictions

`Restriction` lists the `UserManager` keys Shinkan knows; `Restriction.DEFAULT`
is what a freshly held device gets.

| Restriction | Why |
| --- | --- |
| `no_factory_reset`, `no_safe_boot` | the two ways to boot without the hold |
| `no_add_user` | a second user is an unheld device |
| `no_install_apps`, `no_install_unknown_sources`, `no_uninstall_apps` | the package set is the parent's |
| `no_modify_accounts` | no account that could reinstall apps or sync content |
| `no_config_date_time` | windows are measured on the device's clock |
| `no_debugging_features` | adb is a shell as the child |
| `disallow_config_private_dns`, `no_config_vpn` | the filter of §5 stays |
| `no_usb_file_transfer`, `no_config_credentials` | off by default; for stricter homes |

### 4.4 The policy text

Stored and shown as text, one statement per line, parsed and rendered by
`PolicyText`:

```
mode closed
window browser mon,tue,wed,thu,fri 16:00-18:00
window player sat,sun 10:00-12:00
volume browser 60
volume player 40
screen 45
cooldown 60
restrict factory_reset
```

```
mode open
close com.example.game
window com.example.game sat,sun 10:00-12:00
close com.zhiliaoapp.musically
volume device 70
screen 90
cooldown parent
```

`mode` is `closed` or `open` (§4.1); `browser` and `player` name Shinkan's
two doors; `close` names a package of the open device; `window` gives one
span on given weekdays, which may cross midnight and then belongs to the day
it opens; `volume` sets a ceiling (§4.6); `screen` and `cooldown` give the
budget and the rest in minutes, `cooldown parent` a rest only a parent ends
(§4.7); `restrict` names one of §4.3 by the enum's name. When no `restrict` line is written the default set applies.
Block 3 replaces the skeleton's `allow` lines with this form.

### 4.5 Permissions of the usable apps

`setPermissionGrantState` lets the parent deny camera, microphone and
location to a usable app regardless of what it asks. ROADMAP block 8.

### 4.6 The volume ceiling

The parent caps how loud the device may play, as a share of the media
stream's maximum (`volume` lines, §4.4). On the closed device the ceiling is
**per door**: `volume browser` and `volume player` each hold while that door
is in front. The doors are Shinkan's own pages, so Shinkan handles the
volume keys itself (`onKeyDown` on `KEYCODE_VOLUME_UP` and `_DOWN`,
`AudioManager.setStreamVolume` on `STREAM_MUSIC`, never above the ceiling)
and sets the stream to the ceiling when a door opens above it; in lock task
mode the status bar's slider is not reachable. On the open device no
platform policy tells Shinkan which app is in front (usage access cannot be
granted by a device owner), so the ceiling is **one for the device**
(`volume device`): the filter's foreground service listens for
`AudioManager.VOLUME_CHANGED_ACTION` and sets the media stream back to the
ceiling when it is exceeded, a clamp that acts after the change, not a lock
(§9.4). The child may always go lower. Ringer and alarm streams are the
device's own. Block 3 for the doors, block 7 for the device.

### 4.7 Screen time and rest

The parent gives the child a **budget** of screen time and a **rest** that
follows it (`screen` and `cooldown` lines, §4.4). Screen time is time the
screen is on and the device unlocked, on either form: the filter's
foreground service counts it from `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF`
and `ACTION_USER_PRESENT`, and the rest page itself does not count. When
the budget is spent, playback stops (the player pauses, the browser's page
is paused) and the rest page (§8.7) takes the screen: on the closed device
it is the home; on the open device Shinkan brings it to the front and pins
it with lock task (`startLockTask`, allowed to a device owner's own
activity without a prompt), so the child cannot leave it for another app.
The rest ends in one of two ways, the parent's choice: after a set number
of minutes of wall time, counted whether the screen is on or off (`cooldown
60`), or when a parent opens the door and taps *Open* (`cooldown parent`).
A rest that ends starts a fresh budget. Nothing is logged (§2.5): what is
kept is the running budget and the rest's end, in device-protected storage
so a reboot neither resets nor extends them; the clock is protected by
`no_config_date_time`. Block 3 for the closed device, block 7 for the open.

## 5. The filter

### 5.1 Always on, locked down

Shinkan's `FilterService` is a `VpnService`. The device owner makes it the
**always-on VPN with lockdown** (`setAlwaysOnVpnPackage(pkg, true)`): it
starts at boot before any app, and while it is down nothing reaches the
network. `no_config_vpn` keeps the child out of the VPN settings; the
system's private DNS is set to opportunistic (`setGlobalPrivateDnsModeOpportunistic`)
and locked (`disallow_config_private_dns`), so the device's own
DNS-over-TLS never bypasses the tunnel. The service runs in the foreground
with a silent notice.

### 5.2 The tunnel

Block 0 routes **DNS only**: the tunnel's address is 10.111.0.1, its resolver
10.111.0.2, and the one route is that resolver. Every app's name lookups
enter the tunnel; the rest of the traffic flows as before. The service reads
IPv4/UDP datagrams to port 53 (`Udp4`), answers or forwards them, and writes
the reply back with checksums set (`Checksum`, RFC 1071).

Block 8 routes **everything** (0.0.0.0/0 and ::/0) through a userspace
TCP/UDP relay so that the filter also sees TLS server names (SNI), drops
QUIC to blocked hosts, blocks hard-coded resolvers by address, and can tell
apps apart by connection (`ConnectivityManager.getConnectionOwnerUid`).

### 5.3 Answers

A query (`DnsQuestion.parse`) for A, AAAA or HTTPS records whose name is a
blocked host (§6.1) is answered **NXDOMAIN** (`DnsQuestion.refuse`): same
id and question, authoritative, no records. Apps and browsers show "site not
found" and stop; nothing hangs. Every other query is sent to the underlying
network's own resolver through a protected socket and the answer is written
back unchanged. Shinkan brings no resolver and no list of its own.

### 5.4 Ways around, and the answers

| Way around | Answer |
| --- | --- |
| DNS-over-TLS by the system | private DNS opportunistic and locked (§5.1) |
| DNS-over-HTTPS by a browser | Shinkan's browser is a WebView and resolves through the system, so the filter answers it (§7.6); known DoH hosts are on the built-in block list (block 11) |
| a resolver by IP address | block 11, by address |
| QUIC (HTTP/3) | block 11: UDP 443 to blocked hosts dropped, TLS falls back |
| Encrypted Client Hello | no SNI to read; DNS already refused the name, and the HTTPS record that carries the ECH key is refused with it |
| a VPN app | `no_config_vpn`, and only one VPN runs |
| a browser's built-in proxy or "VPN" | it is a host too; its family is blocked by sharing one of its links |

### 5.5 Per app

Until block 11 the filter cannot tell which app asked. From block 11 the
player's own connections to a platform pass while the platform's hosts are
blocked for every other app (§7.1).

## 6. The block list

### 6.1 Sites

A site is a registered domain; blocking it blocks the host itself and every
subdomain (`Host.under`): `example.org`, `www.example.org`,
`a.b.example.org`, never `notexample.org`. Hosts are compared lowercase, with
no trailing dot and no port.

### 6.2 Sharing a link

Any app's share sheet offers *Block in Shinkan*. The first URL in the shared
text (or a bare domain) is read by `Link.parse`:

- the host loses its mobile and www prefixes (`m.`, `www.`, `mobile.`,
  `amp.`, `touch.`) to give the **site**;
- the site is widened to its **family**, the platform's other hosts
  (`Families`): sharing a TikTok video blocks `tiktok.com`, `tiktokv.com`,
  `tiktokcdn.com`, `musical.ly` and the rest, so the app goes dark too. The
  table is Shinkan's own and grows by issue; an unknown site is blocked by
  itself;
- a **creator's page** on a platform the player serves (§7) is read as the
  creator: `youtube.com/@handle`, `/channel/UC…`, `/c/name`, `/user/name`;
- a **single video or post** (`youtube.com/watch?v=`, `youtu.be/`,
  `/shorts/`) is read as an item whose creator the player looks up (§7.3).

The share page shows what will be blocked and asks for one tap (the PIN gate
of §9.1 from block 2). The filter reloads at once.

### 6.3 Creators

A creator is a platform and the platform's stable reference (a channel id or
handle). It blocks nothing in the filter: the official app and site of the
platform are blocked as a family when the first creator is added (the parent
is told), and the player (§7) hides the creator's channel, videos, search
results, comments and recommendations.

### 6.4 The text

```
site tiktok.com
creator youtube @somehandle Some Name
```

`BlocklistText` parses and renders it; the parent may see and paste it.

### 6.5 The built-in list

Ads and trackers are blocked on every device from a list that ships inside
the APK (ROADMAP block 4): **Hagezi** (GPL-3.0, the Multi tier chosen for
size, wildcard-domain format, which is the subdomain match of §6.1) and
**StevenBlack's** unified hosts (MIT) with its extensions. The extensions
are the categories the parent switches on the door: *gambling*, *porn*,
*fake news*, *social*; ads and trackers are always on. The lists live in
the repository under their own licences and are refreshed with each
release, never downloaded (§2.1). The filter answers a built-in name as it
answers a blocked site; a `pass example.org` line of the block list lets a
built-in name through. oisd was set aside: it states no licence.

## 7. The player

### 7.1 Why

The YouTube app pins its certificates and the web shows the tunnel only
`youtube.com`: no filter sees a channel. The one way to block a creator is a
front end that knows one. Shinkan's player is that front end: YouTube
without the official app or site, with the parent's block list applied to
every list the platform returns. The official app is suspended and the
family's hosts are blocked for every other app (§5.5); the player's own
connections pass.

### 7.2 Extractor

The player is built on **NewPipe Extractor** (GPL-3.0-or-later, the library
behind NewPipe on F-Droid), which talks to the platforms' public web
endpoints with no Google API, no key and no account. Grayjay was considered
and set aside: FUTO's Source First licence is not free software and F-Droid
does not carry it. ROADMAP block 6.

### 7.3 A creator from a video

A shared video (§6.2) is resolved by the extractor to its channel, shown to
the parent by name, and blocked as a creator. The lookup is the one network
request Shinkan makes on the parent's behalf; it goes to the platform, not
to Shinkan.

### 7.4 What it covers

YouTube first; PeerTube, SoundCloud and Bandcamp as the extractor serves
them. Other platforms (TikTok, Instagram, Twitch) have no free front end:
they are blocked whole or allowed whole, by family.

### 7.5 Segments

Sponsor, self-promotion and interaction segments inside a video are skipped
with **SponsorBlock**'s community data (server AGPL-3.0, data CC BY-NC-SA
4.0, credited in the player). The player asks the public API once per
video for the segments of every video whose id's SHA-256 starts with the
same four hex characters, so the server sees a bucket of videos, never the
one played: this is the second named request of §2.1. The categories are
the parent's choice; `sponsor`, `selfpromo` and `interaction` by default.
The parent may name a mirror. The code comes from Tubular (GPL-3.0, NewPipe
with SponsorBlock), block 6.

### 7.6 The browser

The closed device's other door, a page of Shinkan's own on the system
WebView (block 5). Every request passes **EasyList** and **EasyPrivacy**
(GPL-3.0 and CC BY-SA 3.0, bundled, §2.1) in `shouldInterceptRequest`: the
request-level blocking of uBlock Origin with those lists. Hiding of page
elements (cosmetic filtering) is partial and said so in §9.4. The WebView
resolves names through the system, so the DNS filter of §5 and the built-in
list of §6.5 answer it, and DNS-over-HTTPS does not arise. No browser on
F-Droid can take the place of this page: Firefox builds ignore managed
configuration on Android and Cromite ignores enterprise policies, so a
blocker the child can switch off is not a hold. The browser's volume
ceiling is §4.6; what it keeps between sessions is decided in block 5.

## 8. Pages

### 8.1 Home

On the closed device, the child's whole screen: two large buttons, *Browser*
and *YouTube*, side by side in landscape and one above the other in
portrait; a door outside its window is greyed and says when it opens next.
On the open device Shinkan is opened like any app and shows the closed apps
with their next window. A small *Parent* link leads to §8.2.

### 8.2 Parent door

PIN entry, then: the hold's status; the form (§4.1); the policy as a list
(the doors' windows and ceilings on the closed device, the closed apps with
their windows and the device ceiling on the open device, restrictions); the
block list (sites, creators, the built-in categories of §6.5, the segment
categories of §7.5); the budget and the rest with what is left of each and
*Open now* during a rest (§4.7); the icon (§8.5); the theme (§8.6); *Apply
now*; *Release this device*. Closes by itself after two minutes without a
touch.

### 8.3 Setup

Shown while Shinkan is not device owner: the adb command of §3.1 and what
follows. Once held, the first opening asks for the PIN to be set and for the
form: the closed device needs nothing more; the open device lists every
launchable package, none closed.

### 8.4 Block in Shinkan

The share target of §6.2: what will be blocked, *Block*, *Cancel*.

### 8.5 Icon

The launcher icon is chosen on the parent door among four drawn from the
name: the **crest** (the gate inside a ring, the default), the **gate**, the
**drawn gate** and the **seal** (関 on a hanko, the character traced from
Shippori Mincho). Each is an adaptive icon on paper with a monochrome layer
for themed icons, in `res/drawable/icon_*.xml`.

Four `activity-alias` entries on `MainActivity` carry the `LAUNCHER` filter,
one per icon; exactly one is enabled, through
`PackageManager.setComponentEnabledSetting` with `DONT_KILL_APP`, a state the
package manager keeps across reboots and updates (`LauncherIcon`). The
`HOME` filter stays on the activity itself, so the default home and lock task
of §4.1 do not depend on the choice. The choice is stored (§9.2, key `icon`)
and applied when the activity stops, not at the tap: the system finishes
every task whose root is the alias just disabled, even with `DONT_KILL_APP`,
so applied at the tap it would close the door on a parent who opened Shinkan
from the launcher. The door says so under the row. The application's own
icon, shown by Settings, the share sheet and the home chooser, is the crest
and cannot be changed at run time (§9.4).

### 8.6 Look

Every page works in portrait and in landscape from its first version; no
page locks an orientation, and the player fills the screen in landscape.
Two themes follow the system's setting, and the parent may pin one on the
door: **rice paper**, the light theme, sepia paper with ink text and
vermilion for the gate (the `paper`, `ink` and `gate` colours of
`res/values/themes.xml`); **sumi**, the dark theme, an ink ground with paper
text and the lighter vermilion the icons use on dark. The child's buttons
are large enough for a small hand in either orientation. Block 3 brings the
dark theme and landscape with the first real pages.

### 8.7 Rest

The page the child sees when the budget is spent (§4.7): a brush-drawn ring
in vermilion, an ensō, that closes as the rest passes, with the time left
under it in large figures; when the rest is the parent's to end, the ring
is whole and the page says so. Nothing else is on it but the small *Parent*
link. It is drawn for both orientations, and in both themes.

## 9. The parent

### 9.1 The PIN

Four or more digits, stored as a salted PBKDF2-HMAC-SHA256 hash (`Pin`,
120 000 iterations). Entry is rate-limited: a wrong PIN waits a growing number
of seconds. Set when the device is first held (§8.3); asked at the parent
door, at the share target and at release.

### 9.2 Storage

Policy text, block list and PIN hash are in device-protected shared
preferences, readable before the first unlock after a boot so the hold and
the filter come up at boot. Only the device owner reads them; `allowBackup`
is off.

### 9.3 Release

*Release this device* lifts every restriction and suspension, clears the lock
task packages, unsets the always-on VPN, stops the filter, then
`clearDeviceOwnerApp`. After it Shinkan is an ordinary app and can be
uninstalled. Release asks for the PIN once more.

### 9.4 Known limits

- **A lost PIN** means a factory reset from recovery mode (the restriction of
  §4.3 blocks the reset in Settings, not the bootloader's).
- **Recovery-mode wipe** is open to the child for the same reason: the hold
  ends with the data, and the parent will know.
- **A creator inside another app** (a TikTok account, an Instagram profile)
  cannot be blocked: the app is blocked whole or not at all (§7.4).
- **A page inside an allowed site** cannot be blocked: hosts are what the
  filter sees. The answer is the site, or the browser's filter lists
  (§7.6), which see requests, not pages either.
- **Cosmetic filtering** in the browser is partial: requests are blocked as
  uBlock Origin blocks them, elements are hidden only where a simple rule
  allows. A third-party browser on the open device gets the host filter and
  nothing more.
- **Until block 11**, an app that resolves names by itself over HTTPS or by
  address slips the DNS filter; Shinkan's own browser cannot, it resolves
  through the system (§7.6).
- **The volume ceiling of the open device** is one for the device and a
  clamp: a sound may be loud for a moment before it is pulled down, and an
  app that sets the volume itself is pulled down again each time. Per-app
  ceilings exist only for the two doors of the closed device (§4.6).
- **Segments** are community data: one nobody marked is played.
- **Screen time is screen-on time**, not attention: a page left open counts,
  and on the open device the budget cannot tell one app from another (the
  same limit as the ceiling). A rest in minutes runs on the wall clock,
  which is protected; a rest the parent ends waits for the parent.
- **Suspended apps' widgets and shortcuts** still show; their taps open the
  suspension dialog.
- **Emergency calls** are never blocked; the phone app is the OEM's.
- **The chosen icon** (§8.5) shows on launchers only: Settings, the share
  sheet's *Block in Shinkan* and the home chooser show the crest, the
  application's icon, which Android lets no app change at run time.

## 10. Testing

Core: `./gradlew :core:test`. App: on the emulator only, never on a real
device during development, and never on the `zanshin-test` emulator, whose
snapshot other work restores. The AVD `shinkan-test` (an image without Google
Play, so that no account blocks §3.1) is created and provisioned by
`tools/emulator/` (ROADMAP block 1). The hold is checked by `adb shell dumpsys
device_policy` before the restrictions cut adb; the filter by `nslookup` of a
blocked and an unblocked name from an allowed terminal app, and by the
emulator's `logcat` on its console.

## 11. Release

Versions are tags `v<versionName>`; `versionCode` counts up by one. F-Droid
builds from the tag with the recipe in `docs/fdroid/` once submitted (ROADMAP
block 10). Reproducible: `dependenciesInfo` off, no proprietary library.
