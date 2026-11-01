# Shinkan — Specification

Status: draft of 2026-11-01.

## 1. What it is

Shinkan (真関, "the true barrier": 関 is the checkpoint on an old Japanese
road) holds an Android device for a child. It is made the **device owner**
once, on a freshly set-up device, and from then on decides which apps exist
for the child, at which hours, and what the device itself may do. A parent
opens it with a PIN that lives only on the device.

There is no account, no server, no companion app and no network. Shinkan is
free software on F-Droid.

## 2. Principles

1. **Offline by construction.** The manifest declares no `INTERNET`
   permission. Shinkan never sees traffic: what the child may reach on the web
   is controlled through the platform (§5.5), not by filtering in the app.
2. **Only the platform's own policies.** Every control is one documented call
   on `DevicePolicyManager` or one `UserManager` restriction, named in this
   spec next to the feature. No accessibility service, no overlay, no root:
   those can be switched off by the child or break with the next Android.
3. **The parent can always get out.** Release (§6.3) lifts everything and
   gives the ownership back; the PIN is the only key, and what happens when it
   is lost is written down (§6.4), not discovered.
4. **Nothing about the child leaves the device.** No usage statistics, no
   logs beyond the system's, nothing to export.
5. **Decisions are pure.** The policy and its evaluation (which packages are
   usable at a moment, when that next changes) are in `core/`, plain Kotlin
   with tests; `app/` only applies the result.

## 3. Modules

| Module | What |
| --- | --- |
| `core/` | `Policy`, `Window`, `Restriction`; `PolicyText` (the stored form, §5.4); `Pin` (§6.1). No Android. |
| `app/` | `admin/`: the device admin receiver, `DeviceHold` (the policy applied), `PolicyStore`, `ReapplyReceiver`. `ui/`: the three pages (§7). |

## 4. Taking the device

Android lets one app be the device owner, set only while the device has no
accounts and (on most builds) only through provisioning. Shinkan supports:

### 4.1 By adb

On a device just after setup, with no Google or other account added and USB
debugging on:

```
adb shell dpm set-device-owner io.github.iverlein.shinkan/shinkan.app.admin.ShinkanAdmin
```

The app's setup page (§7.3) shows this command. After it, Shinkan applies the
default restrictions (§5.3), which include `no_debugging_features`: adb stops
working on the device, by design, so the child cannot use it either.

### 4.2 By QR code at first boot

Tapping the welcome screen six times opens the QR provisioning flow, which
downloads the APK from a URL and verifies its checksum. The QR carries the
package, the receiver, the download URL and the SHA-256 of the APK. A
generator for the F-Droid APK is ROADMAP block 6; until then §4.1 is the way.

### 4.3 What a device owner is not

A device owner is not root. It cannot read other apps' data, cannot see
traffic, cannot stop the recovery-mode wipe of a device that has no factory
reset protection. §6.4 lists what a determined child can still do.

## 5. The hold

### 5.1 Home and lock task

Shinkan registers as a launcher (`CATEGORY_HOME`). The parent makes it the
default home once; with lock task mode (`setLockTaskPackages`, Shinkan plus
the usable apps) the child cannot leave it for another launcher, the status
bar's settings or the recents of other apps. Lock task features are the
minimum: home, notifications, the global actions dialog for power.

### 5.2 Packages: allowed, windowed, suspended

The policy names the packages the child may use. At every application of the
policy (`DeviceHold.apply`), each launchable package is either usable now, and
resumed, or not, and **suspended** (`setPackagesSuspended`): a suspended app
shows a dialog instead of opening, keeps its data and its notifications stay
quiet. Installs and uninstalls are blocked by restriction (§5.3), so the set of
packages changes only through the parent.

A package with no window is usable always; one with windows only inside one
(`Policy.usableAt`). The policy is applied again at boot, when the clock or
the zone changes, and by an exact alarm at the next window edge
(`Policy.nextChange`). The clock is protected by `no_config_date_time`.

### 5.3 Device restrictions

`Restriction` lists the `UserManager` keys Shinkan knows; `Restriction.DEFAULT`
is what a freshly held device gets. The parent can lift any of them in the
policy (§5.4) except that the policy editor warns when `no_factory_reset` or
`no_debugging_features` is lifted.

| Restriction | Why |
| --- | --- |
| `no_factory_reset`, `no_safe_boot` | the two ways to boot without the hold |
| `no_add_user` | a second user is an unheld device |
| `no_install_apps`, `no_install_unknown_sources`, `no_uninstall_apps` | the package set is the parent's |
| `no_modify_accounts` | no account that could reinstall apps or sync content |
| `no_config_date_time` | windows are measured on the device's clock |
| `no_debugging_features` | adb is a shell as the child |
| `disallow_config_private_dns` | the filtering resolver of §5.5 stays |
| `no_usb_file_transfer`, `no_config_credentials` | off by default; for stricter homes |

### 5.4 The policy text

The policy is stored and shown as text, one statement per line, parsed and
rendered by `PolicyText`:

```
allow org.fdroid.fdroid
allow com.example.game
window com.example.game mon,tue,wed,thu,fri 16:00-18:00
window com.example.game sat,sun 10:00-12:00
restrict factory_reset
```

`allow` names a package; `window` gives one span on given weekdays, which may
cross midnight and then belongs to the day it opens; `restrict` names one of
§5.3 by the enum's name. When no `restrict` line is written the default set
applies. The parent edits it as a list (ROADMAP block 3) and may see or paste
the text.

### 5.5 Content on the web

Shinkan does not look at traffic (§2). The web is controlled by three
platform means, all set by the device owner:

1. **No browser** is the default: a browser is just another package the
   policy may allow.
2. **A filtering resolver**: `setGlobalPrivateDns` to a family DNS of the
   parent's choice (the setting is a hostname, no list ships with Shinkan),
   locked by `disallow_config_private_dns`. ROADMAP block 5.
3. **Managed configuration of the allowed browser**:
   `setApplicationRestrictions` carries the browser's own policy keys
   (Chromium-based browsers honour `URLAllowlist`, `URLBlocklist`,
   `IncognitoModeAvailability`), so the parent writes an allow-list of sites.
   ROADMAP block 5; which browsers on F-Droid honour which keys is to be
   measured, not assumed.

Other apps' content (a video app's own catalogue) is beyond the device owner
and is not promised; the answer is the allow-list of apps.

### 5.6 Permissions of the allowed apps

`setPermissionGrantState` lets the parent deny camera, microphone and
location to an allowed app regardless of what it asks. ROADMAP block 4.

## 6. The parent

### 6.1 The PIN

A PIN of four or more digits, stored as a salted PBKDF2-HMAC-SHA256 hash
(`Pin`, 120 000 iterations). Entry is rate-limited in the UI: a wrong PIN
waits a growing number of seconds. The PIN is set when the device is first
held (§7.3).

### 6.2 Storage

Policy text and PIN hash are in device-protected shared preferences, readable
before the first unlock after a boot so the hold is applied at boot. Only the
device owner reads them; `allowBackup` is off.

### 6.3 Release

The parent door offers *Release this device*: every restriction and
suspension is lifted, lock task packages cleared, then
`clearDeviceOwnerApp`. After it Shinkan is an ordinary app and can be
uninstalled. Release asks for the PIN once more.

### 6.4 Known limits

- **A lost PIN** means a factory reset from recovery mode (the restriction of
  §5.3 blocks the reset in Settings, not the bootloader's). Factory reset
  protection, where a Google account exists, is outside Shinkan.
- **Recovery-mode wipe** is open to the child for the same reason: the hold
  ends with the data, and the parent will know.
- **Suspended apps' widgets and shortcuts** still show; their taps open the
  suspension dialog.
- **Emergency calls** are never blocked; the phone app is the OEM's.

## 7. Pages

### 7.1 Home

The child's page: the apps usable now, by label, one tap each; nothing else.
When no app is usable it says so. A small *Parent* link leads to §7.2.

### 7.2 Parent door

PIN entry, then: the hold's status, the policy as a list (allowed apps with
their windows, restrictions), *Apply now*, *Release this device*. Closes by
itself after two minutes without a touch.

### 7.3 Setup

Shown while Shinkan is not device owner: the adb command of §4.1 and what
follows. Once held, the first opening asks for the PIN to be set and for the
allowed apps, with every launchable package listed and none chosen.

## 8. Testing

Core: `./gradlew :core:test`. App: on the emulator only, never on a real
device during development, and never on the `zanshin-test` emulator, whose
snapshot other work restores. The AVD `shinkan-test` (an image without Google
Play, so that no account blocks §4.1) is created and provisioned by
`tools/emulator/` (ROADMAP block 1). The hold is checked by `adb shell dumpsys
device_policy` before the restrictions cut adb, and from then on through the
UI and `logcat` over the emulator console.

## 9. Release

Versions are tags `v<versionName>`; `versionCode` counts up by one. F-Droid
builds from the tag with the recipe in `docs/fdroid/` once submitted (ROADMAP
block 7). Reproducible: `dependenciesInfo` off, no proprietary library.
