# The emulator tool

`emulator.py` creates, boots, snapshots, provisions, drives and restores
`shinkan-test`, the one emulator this project is tested on (SPEC §10).
Python 3, standard library only; `adb` on `PATH`, `avdmanager` and
`emulator` from the SDK (`ANDROID_HOME`, else `sdk.dir` in
`local.properties`, else `~/Android/Sdk`). Run it from the repository root:

```bash
python3 tools/emulator/emulator.py create        # the AVD shinkan-test; nothing when it exists
python3 tools/emulator/emulator.py start         # boot with the window, from the snapshot clean (--cold: a full boot)
python3 tools/emulator/emulator.py snapshot      # save clean; refused unless nothing is installed on top of the image
python3 tools/emulator/emulator.py restore       # load clean through the console; works when adb is cut
python3 tools/emulator/emulator.py install       # the debug APK; refused when Shinkan beta is there
python3 tools/emulator/emulator.py provision     # dpm set-device-owner of the beta's receiver, then open it
python3 tools/emulator/emulator.py shot out.png  # a PNG: by adb when up, else by the console
python3 tools/emulator/emulator.py tap Parent    # tap the centre of the node whose text is "Parent" (--desc: or its description)
python3 tools/emulator/emulator.py store policy plan.txt   # write the beta's policy (or blocklist; - for stdin), then restart it
python3 tools/emulator/emulator.py stop          # quit; nothing is saved
```

`--serial` (before the subcommand) defaults to `emulator-5554`; the console
port is the serial's number. A serial that is not `emulator-PORT` is refused:
the tool never drives a real device.

A session's cycle: `start` (or `restore` when it runs), `install`,
`provision`, then `tap` and `shot` as the block needs, and `restore` at the
end. Build the APK first: `./gradlew :app:assembleDebug`.

## Traps

- **Never `install -r` over the provisioned app.** It fires
  `MY_PACKAGE_REPLACED`, `ReapplyReceiver` applies the hold, and the hold's
  `no_debugging_features` cuts adb. `install` refuses when the package is
  there; `restore` first.
- **`dpm set-device-owner` alone applies no policy**: the hold is applied
  when the PIN is set at the first opening, by QR provisioning and by
  `ReapplyReceiver`. After `provision` the app asks for the PIN to be set
  (`isDeviceOwnerApp` is true) and adb still works.
- **`-snapshot clean` alone saves the state back into `clean` on exit.**
  `start` always adds `-no-snapshot-save`; never boot the AVD by hand without
  it.
- **Never `adb reconnect offline` while the emulator boots**: it drops an
  emulator that is still offline from the adb server for good, and only a new
  server finds it (`adb kill-server`, then the command again).
- **adb cut, console up.** `adb shell settings put global adb_enabled 0`
  takes the device offline within a second; `restore` and `shot` go through
  the console (port 5554, `auth` with `~/.emulator_console_auth_token`). The
  console's screenshot shows the display cutout and the round corners, which
  `screencap` leaves out.
- **`pm enable` / `pm disable`** of a third-party component need `adb root`.
- **The window is placed by sway**, not by the tool (machine rule 9, the
  emulator at the left of workspace 11); never move or resize it. On a
  snapshot boot the emulator re-fits its window once the snapshot has loaded,
  some 12 s after it maps; the machine's placer re-applies the geometry for
  that.
- **`dpm` refuses a device with accounts**, which is why the image has no
  Play.
- **`am force-stop` does not reach a device owner**, and on the closed
  device the home comes back the moment its process ends. To make the app
  read a store written behind its back, `store` writes the file first and
  then kills the process from its own uid (`run-as … kill`).
- **Setting the PIN applies the hold**: lock task, the restrictions, the
  suspensions and the filter with lockdown. The beta skips
  `no_debugging_features`, so adb stays; under lockdown nothing but DNS
  leaves the device (SPEC §9.4).
- **The parent door closes after two minutes without a touch.** For the
  check of it, the beta reads a shorter idle time from a system property:
  `adb shell setprop debug.shinkan.door_idle 15` (seconds; `0` or unset is
  the two minutes). The PIN page's keypad is driven by `tap 4`, `tap OK`.

## The checks of SPEC §10

A block's emulator check, in order. Each line is a thing to see, not a log
line to trust.

1. `./gradlew :core:test` passes.
2. `restore` prints `no Shinkan on the device`.
3. `install`, then `provision`: `Success: Device owner set to package
   io.github.iverlein.shinkan.beta/...`; the app opens on the PIN to be
   set, not the setup page (`shot`); the PIN typed twice (`tap 4` …
   `tap OK`) applies the hold and shows the home.
4. The hold: `adb -s emulator-5554 shell dumpsys device_policy` names
   `ShinkanAdmin` as the device owner, and once the hold is applied, the
   user restrictions of the policy and the suspended packages
   (`setPackagesSuspended` returns the packages it could not suspend; read
   it).
5. The filter: `adb shell dumpsys connectivity` shows Shinkan's VPN with
   lockdown; `adb shell ping -c 1` of a blocked name fails to resolve and of
   an unblocked name resolves; `adb logcat` shows no error from
   `FilterService`.
6. The debug build keeps adb after the hold: `no_debugging_features` is
   skipped under `BuildConfig.DEBUG` (since block 3b); `adb get-state` says
   `device` after the PIN is set.
7. The pages: `tap` and `shot` through what the block built.
8. `restore` prints `no Shinkan on the device`.
