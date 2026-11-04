#!/usr/bin/env python3
"""The QR encoder against an independent decoder: `zbarimg` must read back what was
encoded, across the versions, and the payload must be what provisioning expects.

    python3 tools/test_provision_qr.py
"""

import base64
import hashlib
import json
import random
import shutil
import string
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import provision_qr  # noqa: E402


def read(path: Path) -> bytes:
    done = subprocess.run(["zbarimg", "-q", "--raw", "--nodbus", "-Sbinary", str(path)], capture_output=True)
    return done.stdout.rstrip(b"\n")


def main() -> int:
    if shutil.which("zbarimg") is None:
        print("zbarimg is needed")
        return 2
    random.seed(7)
    alphabet = string.ascii_letters + string.digits + '{}:",./-_'
    failed = 0
    versions = set()
    with tempfile.TemporaryDirectory() as folder:
        png = Path(folder) / "code.png"
        for length in (1, 2, 5, 10, 14, 15, 16, 17, 30, 42, 60, 84, 100, 106, 134, 154, 180, 214, 250, 251, 279, 321, 367, 400, 500, 600, 700, 800, 1000, 1200, 1500, 1800, 2000):
            text = "".join(random.choice(alphabet) for _ in range(length)).encode()
            modules = provision_qr.encode(text)
            versions.add((len(modules) - 17) // 4)
            png.write_bytes(provision_qr.png(modules, scale=4))
            if read(png) != text:
                failed += 1
                print(f"failed at {length} bytes")
        digest = hashlib.sha256(b"x").hexdigest()
        text = provision_qr.payload("https://example.org/a.apk", digest)
        png.write_bytes(provision_qr.png(provision_qr.encode(text.encode())))
        decoded = json.loads(read(png))
    extra = provision_qr.EXTRA
    checksum = decoded[extra + "DEVICE_ADMIN_PACKAGE_CHECKSUM"]
    assert base64.urlsafe_b64decode(checksum + "=") == hashlib.sha256(b"x").digest()
    assert decoded[extra + "DEVICE_ADMIN_COMPONENT_NAME"] == "io.github.iverlein.shinkan/shinkan.app.admin.ShinkanAdmin"
    assert decoded[extra + "LEAVE_ALL_SYSTEM_APPS_ENABLED"] is True
    assert decoded[extra + "ALLOW_OFFLINE"] is True
    print(f"versions {sorted(versions)}: {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
