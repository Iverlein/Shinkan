#!/usr/bin/env python3
"""The stand of SPEC §3.2: the QR flow run on a device as a factory reset leaves it.

No emulator image carries Google's setup wizard, the Play images included, so
the stand is its own AVD, shinkan-stand, and plays the wizard's two parts: it
reads the code and hands the payload on, and it finalizes. All between is the
system's own provisioning: the download, the checksum, the install, the owner,
the two calls into the app.

  create   the AVD shinkan-stand; nothing when it exists
  reset    wipe it, boot it, and put it back to "setup not finished"
  flow     the QR code from the APK, read back, run to the end, and checked
  stop     quit it

Taps and screenshots are emulator.py's, with --serial emulator-5560. Never a
real device: the serial is an emulator's or the tool refuses. Standard library
and zbarimg only.
"""

import argparse
import functools
import hashlib
import http.server
import json
import re
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import emulator  # noqa: E402
import provision_qr  # noqa: E402
from emulator import Device, Failure  # noqa: E402

AVD = "shinkan-stand"
SERIAL = "emulator-5560"
POLICIES = "/data/system/device_policies.xml"
PROVISION = "android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE"
FINALIZE = "android.app.action.PROVISION_FINALIZATION"
TRIGGER_QR = 2  # DevicePolicyManager.PROVISIONING_TRIGGER_QR_CODE, which a wizard adds to what it read
LAUNCHER = "com.google.android.apps.nexuslauncher"
SUSPENDED = ("com.android.chrome", LAUNCHER)
STEP_TIMEOUT = 90
FLUSH = 5  # seconds; the settings provider writes to disk within two


def cmd_create(device: Device, args):
    if AVD in emulator.avds():
        print(f"{AVD} exists; nothing done")
        return
    avdmanager = emulator.sdk() / "cmdline-tools/latest/bin/avdmanager"
    subprocess.run([avdmanager, "create", "avd", "-n", AVD, "-k", emulator.IMAGE, "-d", emulator.DEVICE],
                   input=b"no\n", env=emulator.java_env(), check=True, stdout=subprocess.DEVNULL)
    print(f"{AVD} created from {emulator.IMAGE}; `reset` boots it")


def settled(device: Device, timeout=STEP_TIMEOUT):
    """The framework is back: an activity is on top, whatever the boot property still says."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if "topResumedActivity" in device.sh("dumpsys activity activities", timeout=10):
                return
        except (Failure, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    raise Failure(f"the framework did not come back in {timeout} s")


def recorded(device: Device) -> bool:
    return "setup-complete" in device.sh(f"abx2xml {POLICIES} -")


def rooted(device: Device):
    """adbd restarts as root some time after `adb root` returns."""
    device.adb("root", check=False)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            if device.adb("shell", "id -u", timeout=5, check=False).strip() == b"0":
                return
        except subprocess.TimeoutExpired:
            pass
        time.sleep(1)
    raise Failure("adbd did not come back as root")


def unfinished(device: Device):
    """Setup not finished, as after a factory reset. The two settings alone do not do it:
    the system keeps its own record of a finished setup in POLICIES and never clears it.
    The image's first-boot setup ends some time after boot_completed, and a record cleared
    before that is written back, so the record is waited for first. So is the settings'
    write to disk, which trails `settings put`: a framework stopped before it comes back
    with the old values and writes the record again."""
    rooted(device)
    deadline = time.monotonic() + STEP_TIMEOUT
    while not recorded(device):
        if time.monotonic() > deadline:
            raise Failure(f"the image's first-boot setup did not end in {STEP_TIMEOUT} s")
        time.sleep(2)
    device.sh("settings put global device_provisioned 0")
    device.sh("settings put secure user_setup_complete 0")
    time.sleep(FLUSH)
    device.sh("stop")
    time.sleep(2)
    plain = "/data/local/tmp/policies.xml"
    device.sh(f"abx2xml {POLICIES} {plain}")
    device.sh(f"sed -i 's/ setup-complete=\"true\"//' {plain}")
    device.sh(f"xml2abx {plain} {POLICIES}")
    device.sh(f"chown system:system {POLICIES}; chmod 600 {POLICIES}; rm {plain}")
    device.sh("start")
    settled(device)
    time.sleep(10)  # a record written back in the first seconds would show here
    state = (device.sh("settings get global device_provisioned").strip(), device.sh("settings get secure user_setup_complete").strip())
    if recorded(device) or state != ("0", "0"):
        raise Failure(f"setup still reads as finished: record {recorded(device)}, settings {state}")


def cmd_reset(device: Device, args):
    if AVD not in emulator.avds():
        raise Failure(f"no AVD {AVD}: run `create` first")
    if device.running():
        emulator.cmd_stop(device, args)
    log = Path(tempfile.gettempdir()) / f"{AVD}-emulator.log"
    cmd = [str(emulator.emulator_bin()), "-avd", AVD, "-port", str(device.port), "-no-boot-anim", "-no-snapshot",
           "-wipe-data", *emulator.FLAGS]
    with log.open("wb") as out:
        subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
    print(f"wiping and booting {AVD} on {device.serial}; the emulator's own output goes to {log}")
    deadline = time.monotonic() + 60
    while not device.running():
        if time.monotonic() > deadline:
            raise Failure(f"the emulator did not open its console; see {log}")
        time.sleep(1)
    device.wait_boot()
    settled(device)
    unfinished(device)
    print("setup not finished: ready for `flow`")


def lan_address() -> str:
    """The host as an app on the emulator's Wi-Fi reaches it: 10.0.2.2 answers root's shell and no app."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("1.1.1.1", 53))  # no packet leaves: this only picks the route
        return s.getsockname()[0]


class Quiet(http.server.SimpleHTTPRequestHandler):
    fetched = 0

    def do_GET(self):
        super().do_GET()
        Quiet.fetched += 1

    def log_message(self, *args):
        pass


def extras(read: dict) -> list[str]:
    """What the code said as `am` arguments, typed as a wizard types them: the component is a
    ComponentName, and a plain string is refused as an invalid parameter."""
    out = []
    for key, value in read.items():
        if key.endswith("_COMPONENT_NAME"):
            out += ["--ecn", key, value]
        elif isinstance(value, bool):
            out += ["--ez", key, str(value).lower()]
        else:
            out += ["--es", key, value]
    return out + ["--ei", "android.app.extra.PROVISIONING_TRIGGER", str(TRIGGER_QR)]


def tap(device: Device, text: str):
    deadline = time.monotonic() + STEP_TIMEOUT
    seen = []
    while time.monotonic() < deadline:
        try:
            seen = emulator.nodes(device)
        except (Failure, subprocess.TimeoutExpired, emulator.ET.ParseError):
            seen = []  # uiautomator fails while a page is changing
        hit = next(((x, y) for t, _, x, y in seen if t == text), None)
        if hit:
            device.adb("shell", "input", "tap", str(hit[0]), str(hit[1]))
            print(f"tapped {text!r}")
            time.sleep(emulator.SETTLE)
            return
        time.sleep(1)
    raise Failure(f"no {text!r} in {STEP_TIMEOUT} s; on screen: {sorted({t for t, _, _, _ in seen if t})}")


def log_of(device: Device) -> str:
    return device.sh("logcat -d", timeout=30)


def waited(device: Device, line: str):
    deadline = time.monotonic() + STEP_TIMEOUT
    while time.monotonic() < deadline:
        if line in log_of(device):
            return
        time.sleep(2)
    raise Failure(f"the log never said {line!r}")


def checks(device: Device, package: str, log: str) -> list[tuple[str, bool]]:
    policy = device.sh("dumpsys device_policy", timeout=30)
    top = next((l for l in device.sh("dumpsys activity activities").splitlines() if "topResumedActivity" in l), "")
    vpn = device.sh("dumpsys connectivity", timeout=30)
    out = [
        ("the APK was fetched from the code's address", Quiet.fetched >= 1),
        ("the system verified its checksum", "Checking file hash of entire apk file" in log),
        ("the system asked the app for the mode", "android.app.action.GET_PROVISIONING_MODE" in log),
        ("the system asked the app for compliance", "android.app.action.ADMIN_POLICY_COMPLIANCE" in log),
        ("the app is the device owner", f"admin=ComponentInfo{{{package}/{emulator.RECEIVER}}}" in policy),
        ("the app's page is on top", package in top),
        ("the tunnel is up", f"VPN:{package}" in vpn),
        ("restrictions are set", "userRestriction_no_factory_reset" in policy),
    ]
    for name in SUSPENDED:
        state = re.search(r" suspended=(\w+)", device.sh(f"dumpsys package {name}"))
        out.append((f"{name} is suspended", bool(state) and state.group(1) == "true"))
    return out


def cmd_flow(device: Device, args):
    device.need_adb()
    if shutil.which("zbarimg") is None:
        raise Failure("zbarimg is needed")
    apk = Path(args.apk)
    if not apk.exists():
        raise Failure(f"no APK at {apk}")
    if device.sh("settings get global device_provisioned").strip() != "0":
        raise Failure("setup is finished on the stand: `reset` first")
    with tempfile.TemporaryDirectory() as folder:
        shutil.copy(apk, Path(folder) / "shinkan.apk")
        handler = functools.partial(Quiet, directory=folder)
        server = http.server.ThreadingHTTPServer(("0.0.0.0", 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        url = f"http://{lan_address()}:{server.server_address[1]}/shinkan.apk"
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        code = Path(args.code or Path(folder) / "code.png")
        text = provision_qr.payload(url, digest, args.package)
        code.write_bytes(provision_qr.png(provision_qr.encode(text.encode())))
        read = subprocess.run(["zbarimg", "-q", "--raw", "--nodbus", "-Sbinary", str(code)], capture_output=True, check=True)
        print(f"the code reads back: {len(read.stdout.strip())} bytes")
        device.sh("logcat -G 16M; logcat -c")
        start = ["am", "start", "-a", PROVISION, *extras(json.loads(read.stdout))]
        device.sh(shlex.join(start))
        tap(device, "Next")
        tap(device, "Accept & continue")
        tap(device, "Next")
        waited(device, "Setting userProvisioningState for user 0 to: 2")
        device.sh(f"am start -a {FINALIZE}")
        waited(device, "Setting userProvisioningState for user 0 to: 3")
        # The wizard's last act; until then the system holds back what follows a finished setup.
        device.sh("settings put global device_provisioned 1")
        device.sh("settings put secure user_setup_complete 1")
        time.sleep(5)
        server.shutdown()
    results = checks(device, args.package, log_of(device))
    for what, good in results:
        print(f"{'ok ' if good else 'NO '} {what}")
    failed = [what for what, good in results if not good]
    if failed:
        raise Failure(f"{len(failed)} of {len(results)} checks failed")
    print(f"the flow ran to the end: {len(results)} checks")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="stand.py", description=__doc__.split("\n\n")[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog="Traps: tools/emulator/README.md")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("create", help=f"create the AVD {AVD}; nothing when it exists").set_defaults(run=cmd_create)
    sub.add_parser("reset", help="wipe, boot, and mark setup not finished").set_defaults(run=cmd_reset)
    s = sub.add_parser("flow", help="the QR flow from the code to the app's page, checked")
    s.add_argument("--apk", default=str(emulator.APK), help="the APK to serve (default: the debug APK)")
    s.add_argument("--package", default=emulator.PACKAGE, help="its application id (default: the beta's)")
    s.add_argument("--code", help="keep the code as this PNG")
    s.set_defaults(run=cmd_flow)
    sub.add_parser("stop", help="quit the stand").set_defaults(run=emulator.cmd_stop)
    args = p.parse_args(argv)
    try:
        args.run(Device(SERIAL), args)
    except Failure as e:
        print(f"stand.py {args.command}: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
