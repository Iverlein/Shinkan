# Shinkan 真関

A child lock for Android that holds the whole device: which apps exist for
the child, at which hours, what the device may do, and what it may reach.
Free software, on F-Droid when 1.0 is out.

Shinkan is made the **device owner** once, on a freshly set-up device, by one
`adb` command (or a QR code at first boot). From then on:

- on the **closed device**, Shinkan is the child's whole home: two doors, a
  browser and a YouTube player, both Shinkan's own and filtered, each at the
  hours the parent set, each with its own volume ceiling; everything else is
  suspended. On the **open device** the child keeps the launcher and the
  parent closes the apps named, at the hours named;
- installing, uninstalling, adding accounts, changing the clock, safe boot,
  factory reset from Settings, VPN and DNS settings and USB debugging are
  blocked;
- **the device's traffic goes through Shinkan's own filter**, an always-on
  VPN with lockdown that the child cannot switch off. Ads and trackers are
  blocked from a bundled list, gambling, porn and social sites by the
  parent's choice. Share any link from any app to *Block in Shinkan*: the
  site is blocked device-wide with every subdomain and the platform's other
  hosts, so the app goes dark too;
- **a creator can be blocked**: share a YouTube channel's page or a video and Shinkan's
  built-in player (on NewPipe Extractor) hides them everywhere, while the
  official app and site are held; the player skips sponsor segments with
  SponsorBlock;
- the browser is Shinkan's own page with EasyList and EasyPrivacy applied
  to every request;
- a day plan: free hours, then budgets of minutes per door with rests
  between, say half an hour of YouTube, an hour and a half of rest, two
  hours of the browser; in a rest playback stops and the child sees a ring
  closing until it ends, or until a parent opens the device;
- the parent opens the settings with a PIN that exists only on the device,
  and can release the device at any time.

Shinkan speaks English and Russian. The launcher icon is the parent's choice on the same page: the crest (the
gate in a ring, the default), the gate, the drawn gate or the seal 関.

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
refuses a device with accounts); `tools/emulator/emulator.py` creates,
provisions, drives and restores one.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

The built-in block list in `app/src/main/assets/blocklist/` keeps its
sources' licences: Hagezi's DNS blocklists (GPL-3.0), StevenBlack's hosts
(MIT) and the upstream sources of its extensions (MIT, CC BY 4.0), each
named with its licence in `VERSIONS.md` there, and Shinkan's own list of
Russian state-controlled media in the fake news category
(`tools/blocklist/state-media.txt`). It is refreshed before a
release by `tools/blocklist/refresh.py`.
