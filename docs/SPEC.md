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
   query logs beyond the system's, nothing to export. The one record kept
   is the player's history (§7.1), for the child's own *History* screen:
   on the device only, a hundred videos at most, cleared by the parent.
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
`tools/provision_qr.py` writes the code from a release's address and SHA-256:
the receiver, the address, the checksum (URL-safe base64, no padding),
`LEAVE_ALL_SYSTEM_APPS_ENABLED`, without which the setup disables the system
apps the open device keeps, and `ALLOW_OFFLINE`, without which a failed update
of the system's provisioning role holder ends the setup at "Can't set up
device". From Android 12 the system also asks the app which
mode it takes (`ACTION_GET_PROVISIONING_MODE`, answered
`PROVISIONING_MODE_FULLY_MANAGED_DEVICE`) and hands over at the end
(`ACTION_ADMIN_POLICY_COMPLIANCE`, which applies the hold); both are
`ProvisioningActivity`, open only to `BIND_DEVICE_ADMIN`. The flow from the
code to the hold is run on the stand of `tools/emulator/stand.py`; Google's
wizard itself is on no emulator image, so the six taps and its camera are
unrun: see ROADMAP block 9.

### 3.3 What a device owner is not

A device owner is not root. It cannot read other apps' data, cannot decrypt
their traffic, cannot stop the recovery-mode wipe of a device without factory
reset protection. §9.4 lists what a determined child can still do.

## 4. The hold

### 4.1 Two forms: the closed device and the open device

**The closed device** (ROADMAP blocks 3a to 3e, the first form). Shinkan
registers as a launcher (`CATEGORY_HOME`) and makes itself the default home
as device owner (`addPersistentPreferredActivity` with the HOME filter);
lock task mode (`setLockTaskPackages`, Shinkan alone; `setLockTaskFeatures`
with the keyguard alone, `LOCK_TASK_FEATURE_KEYGUARD`, until A1;
`startLockTask` from its own activity) keeps the child
in it: no other launcher, no status-bar settings, no recents. The home is
never behind the device's own lock screen, whether or not a device PIN is
set: on the held closed device it shows over the keyguard
(`Activity.setShowWhenLocked`) as soon as the screen comes on. Before the
first unlock after a boot the doors say they open once a parent has
unlocked, since the WebView and the player need the credential storage
that is shut until then, and offer *Unlock*, which brings up the device's
lock screen (`KeyguardManager.requestDismissKeyguard`) for the device PIN.
Lock task keeps the keyguard (its feature) for this alone: without it
there would be no lock screen to bring up. The child's
page (§8.1) has two doors and nothing else: the browser (§7.6) and the
player (§7), each usable within its windows; every other launchable package
is suspended, and so is every other home (the device's own launcher), so
that a crash of Shinkan's process falls back to Shinkan and not to the
launcher's old task. Shinkan's home is direct-boot aware: before the first
unlock after a boot it is the home already, where otherwise the system
would start the launcher and keep its task (both found on the emulator,
2026-11-02).

**The open device** (blocks 7a to 7c, the second form). The device keeps
its own launcher (`clearPackagePersistentPreferredActivities` of Shinkan's
preference, then `addPersistentPreferredActivity` naming the device's own
home, since Shinkan is a home too by its manifest and the Home key would
otherwise ask which) and Shinkan
is one app among the others; lock task is off except for the rest page
(§4.7). The
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
on the open device the closed ones outside their windows, and every app but
the homes while the budget is used up with no rest (§4.7); the other homes are
resumed. Installs and
uninstalls are blocked by restriction (§4.3), so the set of packages changes
only through the parent.

What is open when is the day plan of §4.7: a door of the closed device is
open in the spans that name it; a closed app of the open device with
windows is usable inside them and suspended outside (`Policy.openAt` for
the doors, `Policy.closedPackagesAt` for the closed apps). The policy is
applied again at boot, when the clock or the zone changes, when the screen
comes on, and at the next edge (`Policy.nextChange`): an alarm, exact only
where the system grants it (an exact alarm needs `SCHEDULE_EXACT_ALARM`
even from a device owner, and it is not among the permissions of §5.1),
and a timer in Shinkan's own process, which lives as long as the filter.
While the device sleeps neither is sure to be on time; the screen coming
on applies the policy before the child sees it. The clock is protected by
`no_config_date_time`.

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
plan mon,tue,wed,thu,fri
  06:00-09:00 player
  10:00-19:00 browser player 60 rest 30
  19:00-21:00 browser,player 45
plan sat,sun
  08:00-12:00 browser player
  14:00-20:00 browser player 60 rest parent
volume browser 60
volume player 40
restrict factory_reset
```

```
mode open
plan mon,tue,wed,thu,fri
  07:00-09:00 device
  16:00-21:00 device 60 rest 30
close com.example.game
window com.example.game sat,sun 10:00-12:00
close com.zhiliaoapp.musically
permission com.example.game deny camera
volume device 70
```

`mode` is `closed` or `open` (§4.1); `plan` opens a day plan for the
weekdays named and the indented lines are its spans, in order (§4.7): a span
is `HH:MM-HH:MM` and the doors open in it, a door free unless minutes follow
it, then `rest` with minutes or `parent`, and doors joined by a comma
(`browser,player 45`) share their minutes; `browser` and
`player` name Shinkan's two doors and `device` the whole screen of the open
device; `close` names a package of the open device and `window` gives it one
span on given weekdays, which may cross midnight and then belongs to the day
it opens; `permission` refuses a package the camera, the microphone or the
location (§4.5), one line each; `volume` sets a ceiling (§4.6); `start` gives the browser door's start
page, an `http` or `https` address (§7.6); `restrict` names one of §4.3 by
the enum's name. When no `restrict` line is written the default set applies;
`restrict none` lifts them all. A `window` line closes its package as a
`close` line does. A weekday is in one plan at most. A malformed line is
refused with its number (`PolicyText.Malformed`), and the text is written
back in this order: `mode`, the plans, each `close` with its windows,
`permission`, `volume`, `start`, `restrict`.

1.0 also wrote a budget with no hours as a line of its own (`player 30 rest
90`), a stage that ran after the one before it. A stored policy is read with no
fallback, so such a line is not refused but turned into a span as it is read,
and the policy is stored in the new form the next time a parent applies it. The
lines between two spans become one span from the end of the first to the start
of the next, or to the day's end. The conversion is never looser than what it
replaces: a door stays in its first budget only, a rest in minutes goes, since
it came once and would now fill the budget, and what had no time to run (no
gap between the spans, or a span across midnight before it, which ended the
plan day) is dropped.

### 4.5 Permissions of the usable apps

`setPermissionGrantState` lets the parent deny camera, microphone and
location to a usable app regardless of what it asks (`permission` lines,
§4.4; `PERMISSION_GRANT_STATE_DENIED`), with the three location permissions
counted as one. A denial taken off returns to
`PERMISSION_GRANT_STATE_DEFAULT`, so the app's own prompt decides again; what
had been granted before the denial is not restored. The door's closed-apps
page offers, under each app of the open device, what that app asks for.
Denials are applied on every application of the hold, to packages with a
launcher entry. ROADMAP block 8.

### 4.6 The volume ceiling

The parent caps how loud the device may play, as a share of the media
stream's maximum (`volume` lines, §4.4). On the closed device the ceiling is
**per door**: `volume browser` and `volume player` each hold while that door
is in front. The doors are Shinkan's own pages, so Shinkan handles the
volume keys itself (`dispatchKeyEvent` on `KEYCODE_VOLUME_UP` and `_DOWN`,
ahead of any view of the door's, `AudioManager.setStreamVolume` on
`STREAM_MUSIC`, one step a press, never above the ceiling, which is the
share of `getStreamMaxVolume` rounded down: `Volume` in `core/`) and sets
the stream to the ceiling when a door opens above it; on the home and the
other pages the keys are the system's; in lock task
mode the status bar's slider is not reachable. On the open device no
platform policy tells Shinkan which app is in front (usage access cannot be
granted by a device owner), so the ceiling is **one for the device**
(`volume device`): the filter's foreground service listens for
`AudioManager.VOLUME_CHANGED_ACTION` and sets the media stream back to the
ceiling when it is exceeded, a clamp that acts after the change, not a lock
(§9.4), and the hold does the same whenever it is applied, for a stream
already above a ceiling just set. `volume device` means nothing on the
closed device, nor a door's line on the open one (`Policy.deviceCeiling`).
The child may always go lower. Ringer and alarm streams are the
device's own. Block 3d for the doors, 7c for the device.

### 4.7 The day plan: spans, budgets and rests

A day is a **plan** of **spans** (`plan` lines, §4.4), one plan per set of
weekdays. A span is a clock period with the doors open in it, and each span
has its own settings: a door runs free to the span's end unless a **budget**
inside the span counts it, a number of minutes of use, after which an optional
**rest** follows. A budget counts the doors named in it; doors joined by a
comma share its minutes, and a door is in one budget of a span at most. So
"from six to nine YouTube is free, from ten to seven it is an hour of YouTube
followed by half an hour of rest" is two spans:

```
  06:00-09:00 player
  10:00-19:00 browser player 60 rest 30
```

The budget's rest closes its own doors and no others: in the second span the
browser stays open while the player rests. A rest lasts the minutes given on
the wall clock, screen on or off, or until a parent opens the door and taps
*Open* (`rest parent`). When it ends the budget fills again, so the span goes
in rounds, an hour on and half an hour off, until it ends. A budget with no
rest is spent once: its doors stay closed to the span's end. A span that
starts where the one before it ends (or earlier, taking over) carries on from
it, so the child meets no seam: each of its budgets takes the use of the
budgets before it that counted the same doors, and measures it by its own
minutes and rest. Minutes used stay used; short of the new budget the doors
are open with what is left of it; at or past it the budget is spent, and a
rest under way runs on with the new rest's length counted from its own start
(ending at once if that is already past, which fills the budget), or else the
new rest begins at the seam. A door that ran free starts its new budget whole,
a door free in the new span runs free, and a door the new span does not open
closes. Use counted across a seam goes to the span of each second. After a
gap between spans the next one starts with its budgets whole. Outside every span
nothing is open until the next one starts: on the closed device the doors are
greyed; on the open device, whose plan speaks of a budget and not of a curfew,
the apps stay usable and nothing is pinned. A plan belongs to the day its first
span starts, and a span may cross midnight.

A door whose budget is used up with no rest is greyed and says so, *Budget used
up*, and when it next opens (§8.1). On the open device the budget is the whole
screen's, so every app is suspended to the span's end: the launcher greys them,
and a tap brings the system's dialog with the words Shinkan set through
`setShortSupportMessage`, under the system's own title. The homes stay, and the
phone app and Settings, which cannot be suspended (§9.4); the screen is not
pinned, as it is in a rest. Shinkan's own two doors are greyed the same way
(`Policy.depletedAt`).

Use is time the screen is on and that door in front, over the lock screen
or not:
the filter's foreground service counts it from `ACTION_SCREEN_ON`,
`ACTION_SCREEN_OFF`, `ACTION_USER_PRESENT` and the doors' own lifecycle; the
rest page itself does not count. When a budget is spent, playback stops
(the player pauses, the browser's page is paused) and, if a rest follows,
the rest is shown: on the closed device the home keeps its doors and the
doors of the spent budget, and only they, carry a small ensō in its current state with the
minutes left (§8.1); on the open device the rest page (§8.7) takes the
screen, Shinkan brings it to the front and pins it with lock
task (`startLockTask`, allowed to a device owner's own activity without a
prompt), so the child cannot leave it for another app. On the open device
the subject of a span is `device`, the whole screen, since no policy tells
Shinkan which app is in front (§9.4); the closed apps keep their own
`window` lines beside the plan. Use there is time the screen is on and
unlocked. Shinkan stays in `setLockTaskPackages` on this form, which pins
nothing by itself, and calls `startLockTask` and `stopLockTask` for the rest:
a locked task that loses that permission is terminated by the system. Every
application of the hold brings the rest page to the front again, so the screen coming on or a reboot finds it; at its end
Shinkan steps back and what was in front returns. The rest page stays under
the keyguard, not over it as the closed home does: the lock screen keeps its
emergency call.

Nothing is logged (§2.5): what is kept is the current span, the seconds
spent of each of its budgets and their rests' ends, in device-protected
storage so a reboot neither resets nor extends them; the clock is protected by
`no_config_date_time`. `Policy.openAt` gives what is open now and
`Policy.nextChange` the next edge, by clock or by a budget's remainder;
`Policy.count` adds use to the budget that counts the door, split at the
plan's edges, `Policy.takeOver` carries the state into an edited plan (§8.2), `Policy.budgets`
is how each of them stands, `Policy.openNow` is the parent's *Open now*, which
ends every rest running and fills their budgets, `Policy.nextOpening` is what
a greyed door says.

The edges of a plan day, as `Policy` decides them: a plan day starts at
midnight, or when the day before's span across midnight ends, and ends at
the next midnight, or at the end of its own span across it; a new day
starts at its first span whatever the day before left (a rest included).
A later span takes over at its start from the one still running. A day with no plan opens nothing; a policy with no plan at
all sets no time and leaves every door open, as a freshly held device is.
Blocks 3c and 3e for the closed device, 7b for the open, 13 for spans with
their budgets.

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

The app asks for seven permissions, all granted at install without a
prompt, and no other: `INTERNET` for the tunnel's forwarding,
`ACCESS_NETWORK_STATE` to find the underlying network's resolver (§5.3),
`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` for the filter,
`POST_NOTIFICATIONS` for its notice, `RECEIVE_BOOT_COMPLETED` to apply the
hold at boot (§4.2), `QUERY_ALL_PACKAGES` to see the packages it suspends.
Beside them the APK declares androidx's own
`<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature
permission the app gives itself so that only it can reach the receivers
androidx registers as not exported; it grants nothing and no other app can
hold it. Media3's `WAKE_LOCK` is removed from the merged manifest.

### 5.2 The tunnel

The tunnel's addresses are 10.111.0.1 and fd11:1::1, its resolver 10.111.0.2,
and its routes everything (0.0.0.0/0 since block 11a, ::/0 since block 12). The service reads what enters: a
datagram to port 53 (`UdpDatagram`) is answered or forwarded and its reply
written back with checksums set (`Checksum`, RFC 1071), whatever address it
was sent to, so a resolver named by address gets the filter's answer; a TCP
segment (`TcpSegment`) and any other datagram go to the relay; ICMP is dropped.

The relay ends each connection in the service and carries it on over a
protected socket: `TcpFlow` in `core/` is the connection as a state machine
with no socket in it, `Relay` one thread and a selector over them. It offers
the MSS option alone, no window scaling (a window of at most 64 KB, shrinking
as writes to the far side lag and reopened as they catch up), drops a segment
out of order, and sends again after 400 ms or three repeated acks. A
connection the far side refuses reaches the app as refused; ten minutes of
silence ends one. Both families go the same way (`IpPacket`); a packet with
IPv6 extension headers is dropped. On a network with no IPv6 a connection to
an IPv6 address is refused at once, and an app goes on to IPv4.

Datagrams go on over a protected socket per sender and address, closed after
a minute of silence. A datagram shows an address and no name, so the filter
keeps the addresses of the answers it forwards, by the name asked for, the
newest 4096 in memory (`SeenHosts`; nothing stored, §2.5), and drops port 443
to a name the list blocks (`Blocklist.dropsDatagram`): that is QUIC, and the
app falls back to TLS. The address of a name refused from the start is never
learned, and needs no drop: the app has no address to use.

A connection to port 443 is held until its first bytes give the name the
TLS client asks for (`ClientHello`), over as many segments as the hello takes;
a name the list blocks is reset before anything reaches the far side, so a
site reached by an address the filter never answered is refused all the same.
What is not a hello, or names nothing, goes on. Ports 53 and 853 are refused
over TCP, and 853 over UDP, to any address: a resolver the filter would not
see. Public resolvers over HTTPS and TLS are built in (`KnownResolvers`), so
their names are refused by DNS and by this; `pass` lets one through.

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
| DNS-over-HTTPS by a browser | Shinkan's browser is a WebView and resolves through the system, so the filter answers it (§7.6); known DoH hosts are built in, refused by name and by the name TLS shows |
| a resolver by IP address | on port 53 over UDP it gets the filter's own answer (§5.2); over TCP, and on 853, it is refused; DoH to an unlisted host is not seen |
| a blocked site by IP address | the name its TLS hello shows is refused (§5.2) |
| QUIC (HTTP/3) | UDP 443 to blocked hosts dropped, TLS falls back; to an address the filter never answered it goes through, its name being encrypted |
| Encrypted Client Hello | no SNI to read; DNS already refused the name, and the HTTPS record that carries the ECH key is refused with it |
| a VPN app | `no_config_vpn`, and only one VPN runs |
| a browser's built-in proxy or "VPN" | it is a host too; its family is blocked by sharing one of its links |

### 5.5 Per app

Shinkan's own traffic stays out of its tunnel
(`VpnService.Builder.addDisallowedApplication` of its own package): inside
it, its requests would come back into its own relay, and the DNS-only route
before block 11a left it nothing but DNS, since lockdown exempts the VPN's
owner from its drop but not from its route (found on the emulator,
2026-11-02). So the player's connections to a platform pass while
the platform's hosts are blocked for every other app (§7.1), with no lookup
of the sender. In return Shinkan's own pages apply the block list
themselves: the browser asks `Blocklist.blocksHost` (the parent's sites,
the built-in list and its categories, the passes) for every request's host,
the page itself included, before its filter lists (§7.6), and a blocked
page shows a page that says so. The filter has no need to tell other apps
apart; `ConnectivityManager.getConnectionOwnerUid` would do it (block 11d).

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

The share page asks for the PIN (§9.1), then shows what will be blocked and
asks for one tap. The filter reloads at once, and starts when it was not
running.

### 6.3 Creators

A creator is a platform and the platform's stable reference (a channel id or
handle). It blocks nothing in the filter: the official app and site of the
platform are blocked as a family when the first creator is added to either
of the player's lists, by a shared link or the door's text (the parent is
told on the share page before the tap, and on the door's block list page),
and the player (§7) hides the creator's channel, videos, search results,
comments and recommendations. The family is ordinary `site` lines: a parent
who takes `site youtube.com` off lifts the block and the official app's
suspension together, and a later creator does not bring it back
(`Blocklist.holdingFamily`, `holdsPackage`).

As built (block 6b): an uploader's address is read as the reference a shared
link gives (`Link.creatorRef`: `UC…`, `@handle`, `c/name`, `user/name`) and
matched against the list (`Blocklist.hidesUploader`; handles and names
without case, channel ids exactly). The extractor names uploaders by
channel id, so a creator blocked by handle or name is resolved to its id
once per process through the extractor and kept in memory (the creator
lookup of §7.3). Search results, related videos and a channel's videos
leave the blocked out; a blocked creator's channel or video says so and
does not load.

### 6.4 The text

```
site tiktok.com
creator youtube @somehandle Some Name
player allow
allow youtube @kurzgesagt Kurzgesagt
history week
segments sponsor selfpromo
mirror https://sb.example.org
category gambling
pass example.org
```

`BlocklistText` parses and renders it; the parent may see and paste it on
the door's block-list page, which also has a switch per category. `category`
switches on a list of §6.5; `pass` lets a built-in name through with its
subdomains, never a parent's site. `segments` names the kinds the player
skips (§7.5): none when it stands alone, the defaults when it is missing;
`mirror` is asked instead of the public API. A change there is stored and
read by the filter at once, as a shared link is.

### 6.5 The built-in list

Ads and trackers are blocked on every device from a list that ships inside
the APK (ROADMAP block 4): **Hagezi** (GPL-3.0, the Multi tier chosen for
size, wildcard-domain format, which is the subdomain match of §6.1) and
**StevenBlack's** hosts extensions (MIT), each extension the merge of
upstream sources with licences of their own (MIT, and CC BY 4.0 for two
porn sources), all named in `assets/blocklist/VERSIONS.md`. A StevenBlack
name blocks its subdomains too, as every name of the filter does. The
*fake news* category also takes Shinkan's own list,
`tools/blocklist/state-media.txt`, kept by hand: Russian news outlets owned
or controlled by the Russian state (the federal or a regional government,
state companies and state banks) or named by the EU's sanctions on Russian
state propaganda (Council Regulation 833/2014); privately owned outlets are
left out whatever their line. Asked by the owner, 2026-11-02, after
gazeta.ru opened with the category on. The extensions
are the categories the parent switches on the door: *gambling*, *porn*,
*fake news*, *social*; ads and trackers are always on. The lists live in
the repository under their own licences (`app/src/main/assets/blocklist/`)
and are refreshed with each release by `tools/blocklist/refresh.py`, never
downloaded by the app (§2.1). The tool writes each list as lowercase ASCII
names sorted by bytes, a name covered by a shorter one dropped; the filter
reads them once into memory as they are (`BuiltinList`, a few megabytes for
some 230 000 names) and looks a host up by halving, then each domain above
it (`a.b.example.org`, `b.example.org`, `example.org`). The filter answers a built-in name as it
answers a blocked site; a `pass example.org` line of the block list lets a
built-in name through. oisd was set aside: it states no licence.

## 7. The player

### 7.1 Why

The YouTube app pins its certificates and the web shows the tunnel only
`youtube.com`: no filter sees a channel. The one way to block a creator is a
front end that knows one. Shinkan's player is that front end: YouTube
without the official app or site, with the parent's block list applied to
every list the platform returns. While `youtube.com` is on the list the
official apps (`com.google.android.youtube`, `com.google.android.apps.youtube.*`)
are suspended and the family's hosts are blocked for every other app (§5.5);
the player's own connections pass.

### 7.1.1 Modes and screens

The player has two modes, the parent's choice on the door's block list
page: **block list** (every creator but the blocked ones, §6.3) and **allow
list** (only the allowed creators). Both lists are kept, so a change of
mode turns neither into the other (`player allow`, `allow youtube @handle
Name` lines, §6.4); a shared channel offers *Allow in the player* beside
*Block*. Three screens, each as the mode makes it, are reached from icons
at the bottom (a house, a person, a clock): *Home* (a search, filtered by
the mode; in the allow mode, the newest videos of the allowed creators),
*Creators* (the allowed creators, or in the block mode the creators of the
videos watched, each by its avatar first, then its name; the avatar comes
from the channel through the extractor and is kept in memory), *History* (every video played, newest first, with how
much was watched; a video resumes where it stopped). The bar hides while a
video plays. A search, a channel's videos and Home's newest videos are
fetched a page at a time (the platform answers about twenty a request) as
the child nears the end of the list, with no end but the platform's
(2026-11-03). Home merges the allowed creators' pages newest first
(`Feed` in `core/`), fetching a creator's next page before going past its
last video in hand. A page that brings nothing to show (in the allow mode
most of a search is left out) or fails waits for a tap on *Show more* or
the retry, so a list never pages on by itself. Back from a video, a list
is where it was left. The history is a file in the app's storage (`History` in
`core/`, a hundred videos), never exported. On the door's block list page
the parent chooses how long it is kept (`history` line: not at all, a day,
a week, a month, or until cleared, the default), older entries dropped
whenever it is read or written; *Clear the player's history* empties it
(§2.5).

### 7.2 Extractor

The player is built on **NewPipe Extractor** (GPL-3.0-or-later, the library
behind NewPipe on F-Droid), which talks to the platforms' public web
endpoints with no Google API, no key and no account. Grayjay was considered
and set aside: FUTO's Source First licence is not free software and F-Droid
does not carry it. ROADMAP block 6a.

As built (block 6a): the extractor (from JitPack, as NewPipe takes it) does
its HTTP through OkHttp with a fixed desktop user agent and no cookie jar
(`app/.../player/Extractor`). Media3 ExoPlayer plays a picture at the
height asked for, or the nearest below: a stream with its own sound, or a
picture alone joined with the best sound (AAC first) in a
`MergingMediaSource`; H.264 is preferred to VP9, AV1 is never chosen
(`core/.../net/Quality`, `Media.streams`). The height asked for is the
screen's shorter side, never below 1080p; a quality button beside the
uploader lists the heights on offer, marks the one playing, and switches
from the same position. The heights above 360p come from the extractor's
visionOS client: its addresses are served to the end without a
proof-of-origin token, fetched with that client's user agent and always
with a range, without which the platform cuts the answer short. The iOS
and Android clients' separate streams stop with 403 after the first
megabytes without such a token, so they are not used. The platform gives
videos made for kids to none of the clients that need no token: those play
in the one 360p stream with sound, and the button offers only that
(2026-11-02). When the device's decoder
fails while decoding, the player prepares again preferring Android's
software decoders from the same position (1.0.1). The door has a search, its
results, a video with its uploader and related videos, and the uploader's
channel; a playing video fills the screen in landscape; a rest and leaving
the door pause it; the volume keys keep the door's ceiling. Thumbnails are
fetched by the same client and kept in memory only. The player's requests
leave outside the filter's tunnel (§5.5), so a family blocked for every
other app still plays here (§7.1). Media3's `WAKE_LOCK` is removed from the
merged manifest; the libraries add no component.

### 7.3 A creator from a video

A shared video (§6.2) is resolved by the extractor to its channel, shown to
the parent by name, and blocked as a creator, or allowed (§7.1.1). The lookup
is the one network request Shinkan makes on the parent's behalf; it goes to
the platform, not to Shinkan, and only after the PIN. A video that cannot be
read (private, removed, no network) says so and offers another try; nothing
is added.

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
The parent may name a mirror. The request's shape follows Tubular (GPL-3.0,
NewPipe with SponsorBlock), block 6d.

As built (block 6d): `GET <server>/api/skipSegments/<prefix>?categories=[…]`
with no cookie and no other mark, leaving outside the tunnel as all of
Shinkan's requests do (§5.5). The bucket's answer is narrowed to the video on
the device, and only `skip` actions are kept (`core/.../net/Segments`). A 404
means nothing is marked. The video plays while the lookup runs; the position
is looked at five times a second, and a position inside a segment jumps past
it and any segment overlapping or touching it, however it got there, a seek
or a resumed position included. Where segments were found, the video's page
carries the credit; where the server could not be asked, it says the video
plays without skips. *Video segments* on the door has the eight kinds of the
API and the mirror, kept with the block list (`segments`, `mirror`, §6.4) and
stored at once; with no kind chosen no request is made. A mirror over plain
`http` is refused by the release's network policy and reads as a failed
lookup.

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
ceiling is §4.6.

As built (block 5): the rules of the two lists are read once per process
from the APK (`core/.../net/FilterList`): host rules, anchors, `*` and `^`,
exceptions, and the options `third-party`, `domain=` and the request
types; every other rule (cosmetic, regex, `popup`, `csp`, `redirect`,
`removeparam` …) is dropped as it is read. A WebView does not say what kind
a request is, so the type is told from the `Accept` header and the file's
extension (`FilterList.typeOf`). A blocked request gets an empty 403, so a
page's own error handling sees it fail. The page keeps first-party cookies
and storage between sessions and no history list beyond the session's Back;
third-party cookies, file and content access, downloads and new windows are
off; only `http` and `https` load, and http only where the platform allows
cleartext, which the release does not (the browser is HTTPS only). Safe
Browsing and WebView metrics are off (manifest meta-data), as they would
send addresses to Google (§2.1). The address bar opens an address, a host
over HTTPS, or searches DuckDuckGo with safe search strict
(`core/.../net/Address`). The parent sets a start page (`start` line,
§4.4); without one the door opens on its address bar over a blank page of
its own, since a WebView that has loaded nothing draws no frame and leaves
its window white (found checking 1.0, fixed in 1.0.1). A rest or leaving the
door pauses the page and its timers.

## 8. Pages

### 8.1 Home

On the closed device, the child's whole screen: two large buttons, *Browser*
and *YouTube*, side by side in landscape and one above the other in
portrait; a door the plan has closed is greyed and says when it opens next
by the clock alone (`Policy.nextOpening`: "Opens at 16:00" today, "Opens
Tue 06:00" on another day, "Closed" when not within a week), an open one
with a budget shows what is left of it. During a rest the doors of the
spent budget are greyed, the span's other doors staying open, and carry, in place of their mark, a small ensō
in vermilion in its current state, closing as the rest passes, with the
minutes left under the name; for a rest a parent ends the ring is whole
and the door says so. A door whose budget is used up with no rest is greyed
and says *Budget used up* and when it next opens. Each door carries its drawn mark
above its name, a globe for the browser and a screen with the play sign
for the player (`res/drawable/door_*.xml`), in the button's own colour, so
a greyed door greys its mark. Back does nothing on the home; a
door returns to it on Back or *Close*.
On the open device Shinkan is opened like any app, and Back leaves it: the
same two doors, usable but in a rest or with the budget used up
(`Policy.doorsAt`), and under them the
closed apps by name, each with when it opens next (`Policy.closedApps`). A
small *Parent* link leads to §8.2.

### 8.2 Parent door

PIN entry, then: the hold's status; the form (§4.1); the policy as a list
(the doors' windows and ceilings on the closed device, the closed apps with
their windows and the device ceiling on the open device, restrictions); the
block list (sites, creators, the built-in categories of §6.5, the segment
categories of §7.5); the day plan with its current span, what is left of
it and *Open now* during a rest (§4.7); the icon (§8.5); the theme (§8.6);
*Apply now*; *Release this device*.

The policy is edited in sections, each a page: the form (two choices); the
day plans (a card per plan with its weekdays as chips, a weekday of another
plan greyed, and its spans, each moved up or down, edited in a dialog or
removed, with its budgets under it, added, edited and removed there, a door
offered to one budget of the span at most and taken out of its budget when the
span no longer opens it; times typed as `HH:MM`); the closed apps (every launchable package
by name, a check to close it, its windows added and removed); the volume
ceilings (a switch and a slider in steps of five per door); the
restrictions (a switch each, and two warnings: one while a way out of the
hold is open, `factory_reset`, `safe_boot`, `add_user` or
`debugging_features` lifted, one while a way around the filter is open,
`config_vpn` or `config_private_dns` lifted); the theme; and the policy as
its text, editable, read back by the parser, a malformed line named by its
number. A span is drawn, not spelled: its hours and the marks of the doors
open in it, a budget as its doors' marks, an hourglass and its
minutes, then an ensō with the rest's minutes, or an ensō and a key for a
rest a parent ends; the door's *Now* line draws it the same way, a span's
budgets under its hours, each with what is left of it or when its rest ends.

The sections edit a draft. *Apply now* checks it by its own text (it must
render and parse back, `PolicyText.problem`, so the editor and a pasted
text meet one parser), stores it and applies the hold; a changed day plan
takes effect at once on where the day stands: the use counted so far, the
minutes used and a rest under way in the span of this moment are measured by
the new budgets as at a seam (§4.7, `Policy.takeOver`), so a budget lowered
below what was used starts its rest, one raised during a rest opens its doors
with what is left, and a shortened rest ends at its start plus the new length.
A span moved off the present moment, or a budget no longer counting the doors
it counted, starts whole. Leaving the door with a draft not applied asks whether to apply
it, drop it or keep editing; the door closing by itself drops it. Closes to the home by itself after two
minutes without a touch, and as soon as Shinkan leaves the screen, so a
door left open is never found open by the child.

### 8.3 Setup

Shown while Shinkan is not device owner: the adb command of §3.1, what
follows, and the QR way of §3.2. Once held, the first opening asks for the PIN to be set and for the
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

Every page speaks English and Russian (`res/values-ru/`), following the
device's language, or the app's own language where the system offers one:
the build writes the two into `localeConfig` and keeps other languages of its
libraries out. The F-Droid listing has both (`fastlane/.../ru-RU`), and the
README (`README.ru.md`). The Russian is written here, by `docs/russian.md`.
The words of the policy text and of the block list text (§4.4, §6.4) stay
English in both: they are a format, not prose. What a parser says of a line is
a `TextProblem` in `core/`, worded by the app in either language.

Every page works in portrait and in landscape from its first version; no
page locks an orientation, and the player fills the screen in landscape.
Two themes follow the system's setting, and the parent may pin one on the
door (`theme` in the store, §9.2: the system's, rice paper or sumi; it
holds at once, and the bars' icons follow it): **rice paper**, the light theme, sepia paper with ink text and
vermilion for the gate (the `paper`, `ink` and `gate` colours of
`res/values/themes.xml`); **sumi**, the dark theme, an ink ground with paper
text and a lighter vermilion for the gate (`gate_light`). The child's buttons
are large enough for a small hand in either orientation. Both themes are
`ShinkanTheme` in `ui/Theme.kt`, dialogs and cards included (paper or ink a
shade darker, never the toolkit's own tint); the window's colour before
Compose draws is `values/themes.xml` and `values-night/themes.xml`.
`MainActivity` takes rotation, size and night mode itself
(`configChanges`): Compose redraws, and a parent door, a PIN half typed or
an edit in progress are not recreated, where a recreation would stop the
activity and so close the door.

### 8.7 Rest

The page the child sees during a rest on the open device (§4.7; on the
closed device the same ring is drawn small on the doors, §8.1): a brush-drawn ring
in vermilion, an ensō, that closes as the rest passes, with the time left
under it in large figures; when the rest is the parent's to end, the ring
is whole and the page says so. The ring is one stroke from the top,
clockwise, wide where the brush lands and thinning as it travels, with a
dry trail beside it, over a faint whole circle that shows what is left to
close. Nothing else is on it but the small *Parent*
link. It is drawn for both orientations, and in both themes.

## 9. The parent

### 9.1 The PIN

Four or more digits, stored as a salted PBKDF2-HMAC-SHA256 hash (`Pin`,
120 000 iterations), typed on a keypad of Shinkan's own (no keyboard, the
digits never shown). Entry is rate-limited (`PinGate`): three wrong attempts
in a row are free, then the wait before the next is 5 seconds and doubles
with each wrong one, up to an hour; the page shows it as a countdown with
the keypad shut. The count and the last attempt's time are kept beside the
hash (§9.2), and an attempt is counted before the hash is checked, so
closing the page mid-check skips nothing; a right PIN clears the count. Set
when the device is first held (§8.3), typed twice; asked at the parent door,
at the share target and at release.

### 9.2 Storage

Policy text, block list, PIN hash and the PIN's wrong attempts are in device-protected shared
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
- **An app that resolves names by itself over HTTPS, at a host not built in,**
  slips the DNS filter, and if it then speaks QUIC to an address the filter
  never answered, slips the rest; over TLS its name is still read. Shinkan's
  own browser cannot, it resolves through the system (§7.6).
- **Other apps on a held device have no ICMP**, so no `ping`.
- **The browser's cosmetic filtering** is none: only request rules apply
  (§7.6).
- **The volume ceiling of the open device** is one for the device and a
  clamp: a sound may be loud for a moment before it is pulled down, and an
  app that sets the volume itself is pulled down again each time. Per-app
  ceilings exist only for the two doors of the closed device (§4.6).
- **Segments** are community data: one nobody marked is played.
- **A budget counts screen-on time**, not attention: a page left open
  counts, and on the open device a span cannot tell one app from another
  (`device` only, the same limit as the ceiling). A budget used up there greys
  the apps under the system's own title, *Blocked by work policy*, which
  Shinkan cannot change. A rest in minutes runs on
  the wall clock, which is protected; a rest the parent ends waits for the
  parent.
- **The phone app and Settings cannot be suspended**: `setPackagesSuspended`
  hands them back (found on the emulator, 2026-11-04). On the closed device
  lock task keeps them out of reach; on the open device closing them does
  nothing, whatever the door's page says.
- **A permission granted through adb** over a denial stands until the denial
  is taken off and set again: the denial is a value of the system's policy
  engine, and setting the same value changes nothing. Settings cannot do it,
  and the release cuts adb (§4.3).
- **Suspended apps' widgets and shortcuts** still show; their taps open the
  suspension dialog.
- **Emergency calls** are never blocked; the phone app is the OEM's. During
  a rest of the open device the phone app is out of reach as every app is,
  and a call can be placed only from the lock screen's emergency button, on
  a device with a screen lock.
- **What another app plays** on the open device goes on behind the rest
  page: no policy pauses it, and Shinkan pauses only its own doors.
- **The chosen icon** (§8.5) shows on launchers only: Settings, the share
  sheet's *Block in Shinkan* and the home chooser show the crest, the
  application's icon, which Android lets no app change at run time.

## 10. Testing

Core: `./gradlew :core:test`. App: on the emulator only, never on a real
device during development, and never on the `zanshin-test` emulator, whose
snapshot other work restores. The AVD `shinkan-test` (an image without Google
Play, so that no account blocks §3.1) is created, provisioned, driven and
restored by `tools/emulator/emulator.py`, whose README lists these checks.
The debug build does not apply `no_debugging_features`
(`Restriction.DEBUGGING_FEATURES` is skipped under `BuildConfig.DEBUG`), so
the beta can be driven by adb after the hold; the
release build applies it and is checked by eye once per release. The beta
also reads the parent door's idle time in seconds from the system property
`debug.shinkan.door_idle` (`adb shell setprop debug.shinkan.door_idle 15`),
so the two minutes of §8.2 need not be waited out; the release build has no
such setting. The beta also draws the browser's WebView in a software
layer when `debug.shinkan.webview_software` is `1`, for an emulator whose
software GPU draws no Chromium page content; the release never does. The
emulator runs on the host's GPU, where neither is needed. The hold
is checked by `adb shell dumpsys device_policy`; the filter by `adb shell
ping -c 1` of a blocked and an unblocked name (a blocked one says `unknown
host`; an unblocked one resolves, and under lockdown its packet is then
refused, §9.4), and by the emulator's `logcat`. The tool's `store`
writes the policy or the block list into the beta's store and restarts it.

## 11. Release

Versions are tags `v<versionName>`; `versionCode` counts up by one. F-Droid
builds from the tag with the recipe in `docs/fdroid/` once submitted (ROADMAP
block 10). Reproducible: `dependenciesInfo` off, no proprietary library.
The recipe declares the `NonFreeNet` anti-feature, as F-Droid does for
NewPipe: the player shows videos from YouTube. 1.0 is versionCode 2, 1.0.1 versionCode 3; F-Droid is submitted with
1.0.1.
