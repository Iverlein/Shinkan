#!/usr/bin/env python3
"""Create, snapshot, provision, drive and restore the `shinkan-test` emulator.

One command, one subcommand per step (ROADMAP block 1, SPEC §10):

  create       the AVD shinkan-test (Pixel 8, Android 37, Google APIs, no Play);
               nothing is done when it exists
  start        boots it with its window, from the snapshot `clean` when there is
               one; quitting never saves a snapshot
  stop         quits it through the console; nothing is saved
  snapshot     saves `clean`; refused unless the device is booted with no app of
               its own installed
  restore      loads `clean` through the console and waits for the boot; works
               when adb is cut; starts the emulator when it is not running
  install      installs the debug APK; refused when Shinkan beta is there
  provision    makes Shinkan beta the device owner, then opens it
  shot PATH    a PNG of the screen: by adb when it is up, else by the console
  tap TEXT     taps the centre of the node whose text is TEXT
  type TEXT    types TEXT (- for stdin) into a text field, lines joined by Enter;
               --field N taps the Nth field first, --clear empties it
  store KEY FILE
               writes FILE (- for stdin) as the string KEY of Shinkan beta's store
               (policy, blocklist, plan), then restarts the app so it reads it

Emulator only, never a real device: a device owner is removed only by the app
itself or a factory reset. Never `zanshin-test`, which belongs to another
project. Standard library only; adb, avdmanager and emulator come from the SDK.
"""
import argparse
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AVD = "shinkan-test"
IMAGE = "system-images;android-37.0;google_apis;x86_64"
DEVICE = "pixel_8"
SNAPSHOT = "clean"
PACKAGE = "io.github.iverlein.shinkan.beta"  # the debug build (applicationIdSuffix in app/build.gradle.kts)
RECEIVER = "shinkan.app.admin.ShinkanAdmin"
ACTIVITY = "shinkan.app.MainActivity"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
TOKEN = Path.home() / ".emulator_console_auth_token"
BOOT_TIMEOUT = 240
SETTLE = 1.5  # seconds for a page transition to finish after a tap or a launch
PNG = b"\x89PNG\r\n\x1a\n"
# No Vulkan in the guest: on this host the emulator renders in software and serves Vulkan from
# lavapipe, where Chromium (the WebView of the browser door, Chrome) draws no page content and
# then crashes the emulator (kvm_user_backed_ram_map, 2026-11-02). Without it Chromium uses GLES.
# The snapshot `clean` records the feature set: it was taken again with these flags.
FLAGS = ["-feature", "-Vulkan"]


class Failure(Exception):
    """A step that cannot go on; the message says why and what to do."""


# --- the SDK ------------------------------------------------------------------


def sdk() -> Path:
    """The SDK: ANDROID_HOME, ANDROID_SDK_ROOT, `sdk.dir` in local.properties, or ~/Android/Sdk."""
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(name):
            return Path(os.environ[name])
    props = ROOT / "local.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip())
    return Path.home() / "Android/Sdk"


def java_env() -> dict:
    """avdmanager needs a JDK it supports: JAVA_HOME, else Gradle's `org.gradle.java.home`."""
    env = dict(os.environ)
    if not env.get("JAVA_HOME"):
        props = Path.home() / ".gradle/gradle.properties"
        if props.exists():
            for line in props.read_text(encoding="utf-8").splitlines():
                if line.startswith("org.gradle.java.home="):
                    env["JAVA_HOME"] = line.split("=", 1)[1].strip()
    return env


def emulator_bin() -> Path:
    return sdk() / "emulator/emulator"


def avds() -> list[str]:
    r = subprocess.run([emulator_bin(), "-list-avds"], capture_output=True, text=True, check=True)
    return [line.strip() for line in r.stdout.splitlines() if line.strip() and " " not in line.strip()]


def avd_dir() -> Path:
    return Path(os.environ.get("ANDROID_AVD_HOME", Path.home() / ".android/avd")) / f"{AVD}.avd"


def has_snapshot() -> bool:
    return (avd_dir() / "snapshots" / SNAPSHOT / "snapshot.pb").exists()


# --- adb ----------------------------------------------------------------------


class Device:
    def __init__(self, serial: str):
        m = re.fullmatch(r"emulator-(\d+)", serial)
        if not m:
            raise Failure(f"{serial} is not an emulator serial (emulator-PORT); this tool never drives a real device")
        self.serial = serial
        self.port = int(m.group(1))

    def adb(self, *args, timeout=60, check=True) -> bytes:
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, timeout=timeout)
        if check and r.returncode != 0:
            raise Failure(f"adb {' '.join(args)}: {(r.stderr or r.stdout).decode(errors='replace').strip()}")
        return r.stdout

    def sh(self, command: str, timeout=60) -> str:
        return self.adb("shell", command, timeout=timeout).decode(errors="replace")

    def adb_up(self) -> bool:
        """adb reaches the device now (false when it is off, offline, or adb was cut)."""
        try:
            r = subprocess.run(["adb", "-s", self.serial, "get-state"], capture_output=True, text=True, timeout=5)
        except subprocess.TimeoutExpired:
            return False
        return r.returncode == 0 and r.stdout.strip() == "device"

    def need_adb(self):
        if not self.adb_up():
            raise Failure(f"adb does not reach {self.serial}: start the emulator, or `restore` it if adb was cut")

    def running(self) -> bool:
        """The emulator's console answers on its port."""
        try:
            socket.create_connection(("127.0.0.1", self.port), timeout=2).close()
            return True
        except OSError:
            return False

    def booted(self) -> bool:
        try:
            out = self.adb("shell", "getprop", "sys.boot_completed", timeout=5, check=False)
        except subprocess.TimeoutExpired:
            return False
        return out.decode(errors="replace").strip() == "1"

    def wait_boot(self, timeout=BOOT_TIMEOUT):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.booted():
                return
            # Never `adb reconnect offline` here: it drops an emulator that is still offline from the adb
            # server for good, and only a new server finds it again (2026-11-01).
            time.sleep(2)
        listed = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
        if self.running() and self.serial not in listed:
            raise Failure(f"the emulator runs but the adb server does not list {self.serial}: "
                          "`adb kill-server`, then the same command again (a new server finds it)")
        raise Failure(f"{self.serial} did not finish booting in {timeout} s")

    def installed(self) -> bool:
        return bool(self.adb("shell", "pm", "path", PACKAGE, check=False).strip())

    def own_packages(self) -> list[str]:
        """Packages installed on top of the image (`pm list packages -3`)."""
        out = self.sh("pm list packages -3")
        return sorted(line.split(":", 1)[1].strip() for line in out.splitlines() if line.startswith("package:"))


# --- the console ----------------------------------------------------------------


class Console:
    """The emulator console on localhost:PORT, authenticated with ~/.emulator_console_auth_token."""

    def __init__(self, port: int):
        try:
            self.sock = socket.create_connection(("127.0.0.1", port), timeout=30)
        except OSError as e:
            raise Failure(f"no emulator console on port {port}: {e}") from e
        self._reply()
        if not TOKEN.exists():
            raise Failure(f"{TOKEN} is missing; the emulator writes it on its first start")
        self.send(f"auth {TOKEN.read_text(encoding='utf-8').strip()}")

    def _reply(self) -> str:
        buf = b""
        while True:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise Failure("the emulator console closed the connection")
            buf += chunk
            text = buf.decode(errors="replace")
            if text.endswith("OK\r\n"):
                return text[: -len("OK\r\n")]
            m = re.search(r"KO:[^\r\n]*\r\n$", text)
            if m:
                raise Failure(f"console: {m.group(0).strip()}")

    def send(self, command: str) -> str:
        self.sock.sendall(command.encode() + b"\n")
        return self._reply()

    def close(self):
        self.sock.close()


def console(device: Device, command: str) -> str:
    c = Console(device.port)
    try:
        return c.send(command)
    finally:
        c.close()


# --- subcommands --------------------------------------------------------------


def cmd_create(device: Device, args):
    if AVD in avds():
        print(f"{AVD} exists; nothing done")
        return
    image = sdk() / Path(*IMAGE.split(";"))
    if not image.is_dir():
        raise Failure(f"the image {IMAGE} is not installed: sdkmanager --install \"{IMAGE}\"")
    avdmanager = sdk() / "cmdline-tools/latest/bin/avdmanager"
    # "Do you wish to create a custom hardware profile?" is asked on stdin.
    subprocess.run([avdmanager, "create", "avd", "-n", AVD, "-k", IMAGE, "-d", DEVICE],
                   input="no\n", text=True, check=True, env=java_env())
    config = avd_dir() / "config.ini"
    text = config.read_text(encoding="utf-8")
    if "PlayStore.enabled=no" not in text:
        text = re.sub(r"(?m)^PlayStore\.enabled=.*\n?", "", text) + "PlayStore.enabled=no\n"
        config.write_text(text, encoding="utf-8")
    print(f"{AVD} created from {IMAGE}, device {DEVICE}, without Play; boot it with `start --cold`, then `snapshot`")


def cmd_start(device: Device, args):
    if device.running():
        print(f"{device.serial} is running already")
        device.wait_boot()
        return
    if AVD not in avds():
        raise Failure(f"no AVD {AVD}: run `create` first")
    # -no-snapshot-save always: `-snapshot NAME` alone saves the state back into NAME on exit.
    cmd = [str(emulator_bin()), "-avd", AVD, "-port", str(device.port), "-no-boot-anim", "-no-snapshot-save", *FLAGS]
    if args.cold or not has_snapshot():
        cmd.append("-no-snapshot-load")
        how = "cold"
    else:
        cmd += ["-snapshot", SNAPSHOT]
        how = f"from the snapshot {SNAPSHOT}"
    log = Path(tempfile.gettempdir()) / f"{AVD}-emulator.log"
    with log.open("wb") as out:
        subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
    print(f"booting {AVD} {how} on {device.serial}; the emulator's own output goes to {log}")
    deadline = time.monotonic() + 60
    while not device.running():
        if time.monotonic() > deadline:
            raise Failure(f"the emulator did not open its console; see {log}")
        time.sleep(1)
    device.wait_boot()
    print("booted")


def cmd_stop(device: Device, args):
    if not device.running():
        print(f"{device.serial} is not running")
        return
    try:
        console(device, "kill")
    except Failure:
        pass  # the console closes as the emulator quits
    deadline = time.monotonic() + 30
    while device.running() and time.monotonic() < deadline:
        time.sleep(1)
    print("stopped" if not device.running() else "still running after 30 s")


def cmd_snapshot(device: Device, args):
    device.need_adb()
    if not device.booted():
        raise Failure("the device has not finished booting")
    own = device.own_packages()
    if own:
        raise Failure(f"not a clean boot, installed on top of the image: {', '.join(own)}; restore or create anew")
    console(device, f"avd snapshot save {SNAPSHOT}")
    print(f"saved the snapshot {SNAPSHOT}")


def cmd_restore(device: Device, args):
    if not has_snapshot():
        raise Failure(f"no snapshot {SNAPSHOT}: boot with `start --cold`, then `snapshot`")
    if not device.running():
        cmd_start(device, argparse.Namespace(cold=False))
    else:
        console(device, f"avd snapshot load {SNAPSHOT}")
        print(f"loaded the snapshot {SNAPSHOT}")
        device.wait_boot()
    own = device.own_packages()
    print("Shinkan beta is installed" if PACKAGE in own else "no Shinkan on the device")
    if own:
        print(f"installed on top of the image: {', '.join(own)}")


def cmd_install(device: Device, args):
    device.need_adb()
    apk = Path(args.apk)
    if not apk.exists():
        raise Failure(f"no APK at {apk}: ./gradlew :app:assembleDebug")
    if device.installed():
        raise Failure(f"{PACKAGE} is installed already; `restore` first. A reinstall over the provisioned app "
                      "fires MY_PACKAGE_REPLACED, the hold is applied, and adb is cut")
    print(device.adb("install", str(apk), timeout=300).decode(errors="replace").strip())


def cmd_provision(device: Device, args):
    device.need_adb()
    if not device.installed():
        raise Failure(f"{PACKAGE} is not installed: run `install` first")
    # Just after a snapshot load, Google Play services briefly hold something dpm counts as
    # an account ("already some accounts on the device") though `dumpsys account` lists none
    # (2026-11-02); it passes within seconds, so the refusal is retried for a minute.
    deadline = time.monotonic() + 60
    while True:
        try:
            out = device.sh(f"dpm set-device-owner {PACKAGE}/{RECEIVER}")
            break
        except Failure as e:
            if "already some accounts" not in str(e) or time.monotonic() > deadline:
                raise
            time.sleep(3)
    print(out.strip())
    if "Success" not in out:
        raise Failure("dpm did not make Shinkan the device owner")
    # -W returns when the activity has drawn; the splash still fades out after it.
    device.adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}")
    deadline = time.monotonic() + 15
    while f'package="{PACKAGE}"' not in dump(device):
        if time.monotonic() > deadline:
            raise Failure("Shinkan beta did not come to the front")
        time.sleep(1)
    time.sleep(SETTLE)
    print("opened Shinkan beta")


def cmd_shot(device: Device, args):
    path = Path(args.path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if device.adb_up():
        data = device.adb("exec-out", "screencap", "-p")
        if not data.startswith(PNG):
            raise Failure("screencap returned no PNG")
        path.write_bytes(data)
        print(f"{path} (by adb)")
        return
    if not device.running():
        raise Failure(f"{device.serial} is not running")
    # The console writes Screenshot_<n>.png into the directory it is given.
    with tempfile.TemporaryDirectory() as tmp:
        console(device, f"screenrecord screenshot {tmp}")
        deadline = time.monotonic() + 15
        while True:
            files = list(Path(tmp).glob("*.png"))
            if files and files[0].stat().st_size > 0:
                time.sleep(0.5)  # let the write finish
                shutil.move(str(files[0]), path)
                break
            if time.monotonic() > deadline:
                raise Failure("the console wrote no screenshot")
            time.sleep(0.5)
    print(f"{path} (by the console; adb is not up)")


def dump(device: Device) -> str:
    """The screen's view tree as uiautomator writes it (`dump /dev/tty` prints only its own message)."""
    device.sh("uiautomator dump /data/local/tmp/ui.xml", timeout=30)
    return device.adb("exec-out", "cat", "/data/local/tmp/ui.xml").decode(errors="replace")


def nodes(device: Device) -> list[tuple[str, str, int, int]]:
    """The screen's nodes as (text, content description, centre x, centre y)."""
    root = ET.fromstring(dump(device))
    out = []
    for n in root.iter("node"):
        m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            out.append((n.get("text", ""), n.get("content-desc", ""), (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def cmd_tap(device: Device, args):
    device.need_adb()
    deadline = time.monotonic() + args.timeout
    while True:
        seen = nodes(device)
        hit = next(((x, y) for t, _, x, y in seen if t == args.text), None)
        if hit is None and args.desc:
            hit = next(((x, y) for _, d, x, y in seen if d == args.text), None)
        if hit:
            device.adb("shell", "input", "tap", str(hit[0]), str(hit[1]))
            time.sleep(SETTLE)  # so that a `shot` right after shows the page the tap opened
            print(f"tapped {args.text!r} at {hit[0]},{hit[1]}")
            return
        if time.monotonic() > deadline:
            texts = sorted({t for t, _, _, _ in seen if t})
            raise Failure(f"no node with the text {args.text!r}; on screen: {texts}")
        time.sleep(1)


def fields(device: Device) -> list[tuple[int, int, str]]:
    """The screen's text fields (EditText nodes), top to bottom: centre x, centre y, text."""
    root = ET.fromstring(dump(device))
    out = []
    for n in root.iter("node"):
        if n.get("class") != "android.widget.EditText":
            continue
        m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            out.append(((x1 + x2) // 2, (y1 + y2) // 2, n.get("text", "")))
    return sorted(out, key=lambda c: (c[1], c[0]))


def typed(line: str) -> str:
    """One line for `input text`, quoted for the device's shell: spaces as %s."""
    return "'" + line.replace(" ", "%s").replace("'", "'\\''") + "'"


def cmd_type(device: Device, args):
    device.need_adb()
    text = sys.stdin.read() if args.text == "-" else args.text
    if args.clear and not args.field:
        raise Failure("--clear needs --field, to know the field's text")
    if args.field:
        found = fields(device)
        if len(found) < args.field:
            raise Failure(f"no text field number {args.field}; {len(found)} on screen")
        x, y, old = found[args.field - 1]
        device.adb("shell", "input", "tap", str(x), str(y))
        time.sleep(0.5)
        if args.clear and old:
            # A Compose field takes no Ctrl+A from `input`: delete the text's length both
            # ways from wherever the tap left the cursor.
            n = len(old)
            for keys in (["KEYCODE_DEL"] * n, ["KEYCODE_FORWARD_DEL"] * n):
                for i in range(0, n, 100):
                    device.adb("shell", "input", "keyevent", *keys[i:i + 100])
    lines = text.rstrip("\n").split("\n")
    for i, line in enumerate(lines):
        if line:
            device.adb("shell", "input", "text", typed(line))
        if i < len(lines) - 1:
            device.adb("shell", "input", "keyevent", "KEYCODE_ENTER")
    print(f"typed {len(lines)} line(s)")


PREFS = f"/data/user_de/0/{PACKAGE}/shared_prefs/shinkan.xml"
STORE_KEYS = ("policy", "blocklist", "plan")


def cmd_store(device: Device, args):
    """Writes one string of the app's device-protected store and restarts the app (SPEC §9.2).

    `am force-stop` does not reach a device owner, and the closed device's home
    comes back the moment its process ends, so the file is written first and
    the process is then ended from its own uid through `run-as`; the restarted
    app reads the new value. Only the debuggable beta allows `run-as`.
    """
    # Read before any adb call: adb inherits stdin and would swallow the text.
    text = sys.stdin.read() if args.file == "-" else Path(args.file).read_text(encoding="utf-8")
    device.need_adb()
    if not device.installed():
        raise Failure(f"{PACKAGE} is not installed")
    current = device.adb("exec-out", "run-as", PACKAGE, "cat", PREFS, check=False).decode(errors="replace")
    root = ET.fromstring(current) if current.lstrip().startswith("<?xml") or current.lstrip().startswith("<map") else ET.Element("map")
    for old in [e for e in root if e.get("name") == args.key]:
        root.remove(old)
    entry = ET.SubElement(root, "string", name=args.key)
    entry.text = text
    body = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" + ET.tostring(root, encoding="unicode")
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False, encoding="utf-8") as f:
        f.write(body)
        local = f.name
    try:
        device.adb("push", local, "/data/local/tmp/shinkan-prefs.xml")
    finally:
        os.unlink(local)
    device.adb("shell", f"run-as {PACKAGE} sh -c 'mkdir -p {PREFS.rsplit('/', 1)[0]} && cat /data/local/tmp/shinkan-prefs.xml > {PREFS}'")
    device.adb("shell", "rm", "/data/local/tmp/shinkan-prefs.xml")
    pid = device.sh(f"pidof {PACKAGE}").strip()
    if pid:
        device.adb("shell", "run-as", PACKAGE, "kill", pid.split()[0])
    time.sleep(3 * SETTLE)
    print(f"stored {args.key} ({len(text.splitlines())} lines); {'restarted' if pid else 'the app was not running'}")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="emulator.py", description=__doc__.split("\n\n")[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog="Commands and traps: tools/emulator/README.md")
    p.add_argument("--serial", default="emulator-5554", help="the emulator, emulator-PORT (default emulator-5554)")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("create", help=f"create the AVD {AVD}; nothing when it exists").set_defaults(run=cmd_create)
    s = sub.add_parser("start", help=f"boot with the window, from {SNAPSHOT} when it exists")
    s.add_argument("--cold", action="store_true", help="a full boot, not from the snapshot")
    s.set_defaults(run=cmd_start)
    sub.add_parser("stop", help="quit the emulator; nothing is saved").set_defaults(run=cmd_stop)
    sub.add_parser("snapshot", help=f"save {SNAPSHOT} after a boot with nothing installed").set_defaults(run=cmd_snapshot)
    sub.add_parser("restore", help=f"load {SNAPSHOT} through the console; works when adb is cut").set_defaults(run=cmd_restore)
    s = sub.add_parser("install", help="install the debug APK; refused when it is there")
    s.add_argument("--apk", default=str(APK), help="default: the debug build's APK")
    s.set_defaults(run=cmd_install)
    sub.add_parser("provision", help="make Shinkan beta the device owner, then open it").set_defaults(run=cmd_provision)
    s = sub.add_parser("shot", help="a screenshot to PATH, by adb or else by the console")
    s.add_argument("path")
    s.set_defaults(run=cmd_shot)
    s = sub.add_parser("tap", help="tap the node whose text is TEXT")
    s.add_argument("text")
    s.add_argument("--desc", action="store_true", help="also match the content description")
    s.add_argument("--timeout", type=float, default=8, help="seconds to wait for the node (default 8)")
    s.set_defaults(run=cmd_tap)
    s = sub.add_parser("type", help="type TEXT (- for stdin; lines joined by Enter) into the focused field")
    s.add_argument("text")
    s.add_argument("--field", type=int, help="first tap the Nth text field on screen, from the top")
    s.add_argument("--clear", action="store_true", help="with --field: first delete the field's text")
    s.set_defaults(run=cmd_type)
    s = sub.add_parser("store", help="write a string of the beta's store (policy, blocklist), then restart it")
    s.add_argument("key", choices=STORE_KEYS)
    s.add_argument("file", help="the text, or - for stdin")
    s.set_defaults(run=cmd_store)
    args = p.parse_args(argv)
    try:
        args.run(Device(args.serial), args)
    except Failure as e:
        print(f"emulator.py {args.command}: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
