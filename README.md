# Shinkan 真関

A child lock for Android that holds the whole device: which apps exist for
the child, at which hours, what the device may do, and what it may reach.
Free software, on F-Droid when 1.0 is out.

Shinkan is made the **device owner** once, on a freshly set-up device, by one
`adb` command (or a QR code at first boot). From then on:

- only the apps the parent allowed can be opened, each within the hours the
  parent set; everything else is suspended;
- installing, uninstalling, adding accounts, changing the clock, safe boot,
  factory reset from Settings, VPN and DNS settings and USB debugging are
  blocked;
- **the device's traffic goes through Shinkan's own filter**, an always-on
  VPN with lockdown that the child cannot switch off. Share any link from
  any app to *Block in Shinkan*: the site is blocked device-wide with every
  subdomain and the platform's other hosts, so the app goes dark too;
- **a creator can be blocked**: share a YouTube channel's page and Shinkan's
  built-in player (on NewPipe Extractor) hides them everywhere, while the
  official app and site are held;
- the parent opens the settings with a PIN that exists only on the device,
  and can release the device at any time.

No account, no server, no companion app. The filter forwards the device's
own traffic and nothing of Shinkan's: no endpoint, no update check, no list
download. Nothing about the child leaves the device. The specification is
[docs/SPEC.md](docs/SPEC.md); what is built and what is next,
[docs/ROADMAP.md](docs/ROADMAP.md).

## Taking a device

On a device just after setup, before any account is added, with USB debugging
on:

```
adb shell dpm set-device-owner io.github.iverlein.shinkan/shinkan.app.admin.ShinkanAdmin
```

Then open Shinkan, set the PIN and choose the apps. Releasing the device is a
button behind the PIN; a lost PIN means a factory reset from recovery.

## Building

```
./gradlew :core:test            # the engines against their tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/
```

JDK 21, Android SDK 37. The debug build installs beside the release as
"Shinkan beta". Test on an emulator without Google Play (the `dpm` command
refuses a device with accounts).

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).
