---
description: The app and the engine — platform policies only, the emulator and never a real device, how the hold is checked
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
- **A test for every policy rule.** A change to `Policy`, `Window` or
  `PolicyText` comes with a case in `core/src/test/`; the overnight window
  and the window edge are the ones most easily broken.
- **Emulator only, and this project's own.** Never provision a real device
  in development; never use `zanshin-test`. Create `shinkan-test` from an
  image without Google Play (the `dpm` command refuses a device with
  accounts). Snapshot it right after boot, before provisioning, and restore
  the snapshot when done: a provisioned emulator cannot be unprovisioned from
  outside once `no_debugging_features` is on.
- **Check the hold, not the log line.** `adb shell dumpsys device_policy`
  before the restrictions cut adb; after it, the UI and the emulator console.
  `setPackagesSuspended` returns the packages it could *not* suspend; read it.
- **Strings in `res/values/strings.xml`**, never literals in Compose.
