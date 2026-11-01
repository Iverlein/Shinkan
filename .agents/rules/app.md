---
description: The app and the engine — platform policies for the hold, nothing of Shinkan's through the tunnel, the emulator and never a real device, how the hold and the filter are checked
trigger: glob
globs: '**/app/**, **/core/**, **/tools/emulator/**'
paths:
- app/**
- core/**
- tools/emulator/**
---

# The app and the engine

- **Decisions in `core/`, calls in `app/`.** Which packages are usable at a
  moment, when that next changes, what a policy line means: pure functions
  with tests. `DeviceHold` only turns the result into `DevicePolicyManager`
  calls; a new control is one documented call, named in SPEC §5.
- **A test for every rule.** A change to `Policy`, `Plan`, `PlanState`, `Window`, `PolicyText`,
  `Blocklist`, `Link`, `DnsQuestion` or `Udp4` comes with a case in
  `core/src/test/`; the overnight window, the window edge, the plan day's edges, the subdomain
  match and the checksums are the ones most easily broken.
- **The tunnel carries the device's traffic, never Shinkan's.** A change to
  `app/.../filter/` is reviewed for any connection the app opens on its own
  account; the only ones allowed are the forward to the network's resolver
  (SPEC §5.3) and the player's requests to the platform (SPEC §7). Every
  socket the service opens is `protect`ed, or it loops into the tunnel.
- **Emulator only, and this project's own.** Never provision a real device
  in development; never use `zanshin-test`. Create `shinkan-test` from an
  image without Google Play (the `dpm` command refuses a device with
  accounts). Snapshot it right after boot, before provisioning, and restore
  the snapshot when done: a provisioned emulator cannot be unprovisioned from
  outside once `no_debugging_features` is on.
- **Install once, then provision; never reinstall over a provisioned
  emulator.** `adb shell dpm set-device-owner` alone applies no policy (the
  hold is applied when the PIN is first set, on QR provisioning and by
  `ReapplyReceiver`; the beta never applies `no_debugging_features`), so the pages
  can be driven by adb after it; but `install -r` over the provisioned app
  fires `MY_PACKAGE_REPLACED`, the receiver applies the hold, and adb is cut
  (2026-11-01). Recovery is the snapshot, loaded through the emulator
  console: `python3 tools/emulator/emulator.py restore`.
- **Check the hold, not the log line.** `adb shell dumpsys device_policy`
  before the restrictions cut adb; after it, the UI and the emulator console.
  `setPackagesSuspended` returns the packages it could *not* suspend; read it.
  The filter is checked by resolving a blocked and an unblocked name from
  an allowed terminal app on the emulator, and by `dumpsys connectivity`
  showing the VPN with lockdown.
- **Strings in `res/values/strings.xml`**, never literals in Compose.
