#!/usr/bin/env python3
"""The QR code that provisions Shinkan at first boot (SPEC §3.2).

From the address of a release APK and its SHA-256, the JSON of
android.app.extra.PROVISIONING_* for the receiver, rendered as a PNG by the
encoder below: pure Python, no network, nothing beyond the standard library.

    python3 tools/provision_qr.py --url https://…/shinkan.apk --apk app-release.apk out.png
    python3 tools/provision_qr.py --url https://…/shinkan.apk --sha256 <hex> out.png
"""

import argparse
import base64
import hashlib
import json
import struct
import sys
import zlib

PACKAGE = "io.github.iverlein.shinkan"
RECEIVER = "shinkan.app.admin.ShinkanAdmin"
EXTRA = "android.app.extra.PROVISIONING_"

# Per version 1 to 40, at error correction level M: codewords of correction in a
# block, and blocks (ISO/IEC 18004, table 9).
ECC_PER_BLOCK = [0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28]
BLOCKS = [0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49]
LEVEL_M_BITS = 0


def payload(url: str, sha256_hex: str, package: str = PACKAGE) -> str:
    """The provisioning JSON; the checksum is the digest in URL-safe base64 without padding."""
    digest = bytes.fromhex(sha256_hex)
    if len(digest) != 32:
        raise ValueError("a SHA-256 is 64 hex characters")
    return json.dumps(
        {
            EXTRA + "DEVICE_ADMIN_COMPONENT_NAME": f"{package}/{RECEIVER}",
            EXTRA + "DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": url,
            EXTRA + "DEVICE_ADMIN_PACKAGE_CHECKSUM": base64.urlsafe_b64encode(digest).decode().rstrip("="),
            # Without this the setup disables the system apps the open device keeps.
            EXTRA + "LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
            # Without this a failed update of the system's own provisioning app ends the
            # setup at "Can't set up device"; with it the platform provisions by itself.
            EXTRA + "ALLOW_OFFLINE": True,
        },
        separators=(",", ":"),
    )


def raw_modules(version: int) -> int:
    """The modules of a version left for data and correction, the remainder bits included."""
    result = (16 * version + 128) * version + 64
    if version >= 2:
        aligns = version // 7 + 2
        result -= (25 * aligns - 10) * aligns - 55
        if version >= 7:
            result -= 36
    return result


def data_codewords(version: int) -> int:
    return raw_modules(version) // 8 - ECC_PER_BLOCK[version] * BLOCKS[version]


def gf_mul(x: int, y: int) -> int:
    """Multiplication in GF(2^8) modulo the polynomial of the standard, 0x11D."""
    z = 0
    for i in range(7, -1, -1):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def rs_divisor(degree: int) -> list[int]:
    result = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(degree):
            result[j] = gf_mul(result[j], root)
            if j + 1 < degree:
                result[j] ^= result[j + 1]
        root = gf_mul(root, 2)
    return result


def rs_remainder(data: list[int], divisor: list[int]) -> list[int]:
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result.pop(0)
        result.append(0)
        for i, coefficient in enumerate(divisor):
            result[i] ^= gf_mul(coefficient, factor)
    return result


def codewords(text: bytes, version: int) -> list[int]:
    """The data in byte mode, padded, split into blocks, corrected and interleaved."""
    bits = []

    def put(value: int, length: int) -> None:
        bits.extend((value >> i) & 1 for i in range(length - 1, -1, -1))

    put(0b0100, 4)
    put(len(text), 8 if version <= 9 else 16)
    for b in text:
        put(b, 8)
    capacity = data_codewords(version) * 8
    put(0, min(4, capacity - len(bits)))
    put(0, -len(bits) % 8)
    data = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = 0xEC
    while len(data) < capacity // 8:
        data.append(pad)
        pad ^= 0xEC ^ 0x11

    count = BLOCKS[version]
    ecc_length = ECC_PER_BLOCK[version]
    total = raw_modules(version) // 8
    short_blocks = count - total % count
    short_length = total // count - ecc_length
    divisor = rs_divisor(ecc_length)
    blocks, eccs, at = [], [], 0
    for i in range(count):
        length = short_length + (0 if i < short_blocks else 1)
        block = data[at:at + length]
        at += length
        blocks.append(block)
        eccs.append(rs_remainder(block, divisor))
    out = []
    for i in range(short_length + 1):
        out.extend(b[i] for b in blocks if i < len(b))
    for i in range(ecc_length):
        out.extend(e[i] for e in eccs)
    return out


def alignment_positions(version: int) -> list[int]:
    if version == 1:
        return []
    count = version // 7 + 2
    step = (version * 8 + count * 3 + 5) // (count * 4 - 4) * 2
    return [6] + [version * 4 + 10 - i * step for i in reversed(range(count - 1))]


class Matrix:
    def __init__(self, version: int):
        self.version = version
        self.size = version * 4 + 17
        self.dark = [[False] * self.size for _ in range(self.size)]
        self.fixed = [[False] * self.size for _ in range(self.size)]
        self.patterns()

    def put(self, x: int, y: int, dark: bool) -> None:
        self.dark[y][x] = dark
        self.fixed[y][x] = True

    def patterns(self) -> None:
        size = self.size
        for i in range(size):
            self.put(6, i, i % 2 == 0)
            self.put(i, 6, i % 2 == 0)
        for cx, cy in ((3, 3), (size - 4, 3), (3, size - 4)):
            for dy in range(-4, 5):
                for dx in range(-4, 5):
                    x, y = cx + dx, cy + dy
                    if 0 <= x < size and 0 <= y < size:
                        self.put(x, y, max(abs(dx), abs(dy)) not in (2, 4))
        positions = alignment_positions(self.version)
        last = len(positions) - 1
        for i, cy in enumerate(positions):
            for j, cx in enumerate(positions):
                if (i, j) in ((0, 0), (0, last), (last, 0)):
                    continue
                for dy in range(-2, 3):
                    for dx in range(-2, 3):
                        self.put(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)
        self.format_bits(0)
        if self.version >= 7:
            remainder = self.version
            for _ in range(12):
                remainder = (remainder << 1) ^ ((remainder >> 11) * 0x1F25)
            bits = self.version << 12 | remainder
            for i in range(18):
                dark = (bits >> i) & 1 != 0
                a, b = size - 11 + i % 3, i // 3
                self.put(a, b, dark)
                self.put(b, a, dark)

    def format_bits(self, mask: int) -> None:
        data = LEVEL_M_BITS << 3 | mask
        remainder = data
        for _ in range(10):
            remainder = (remainder << 1) ^ ((remainder >> 9) * 0x537)
        bits = (data << 10 | remainder) ^ 0x5412
        size = self.size

        def bit(i: int) -> bool:
            return (bits >> i) & 1 != 0

        for i in range(6):
            self.put(8, i, bit(i))
        self.put(8, 7, bit(6))
        self.put(8, 8, bit(7))
        self.put(7, 8, bit(8))
        for i in range(9, 15):
            self.put(14 - i, 8, bit(i))
        for i in range(8):
            self.put(size - 1 - i, 8, bit(i))
        for i in range(8, 15):
            self.put(8, size - 15 + i, bit(i))
        self.put(8, size - 8, True)

    def data(self, words: list[int]) -> None:
        size, i = self.size, 0
        right = size - 1
        while right >= 1:
            if right == 6:
                right = 5
            for vertical in range(size):
                for j in range(2):
                    x = right - j
                    upward = (right + 1) & 2 == 0
                    y = size - 1 - vertical if upward else vertical
                    if not self.fixed[y][x] and i < len(words) * 8:
                        self.dark[y][x] = (words[i >> 3] >> (7 - (i & 7))) & 1 != 0
                        i += 1
            right -= 2

    def mask(self, mask: int) -> None:
        rules = (
            lambda x, y: (x + y) % 2 == 0,
            lambda x, y: y % 2 == 0,
            lambda x, y: x % 3 == 0,
            lambda x, y: (x + y) % 3 == 0,
            lambda x, y: (x // 3 + y // 2) % 2 == 0,
            lambda x, y: x * y % 2 + x * y % 3 == 0,
            lambda x, y: (x * y % 2 + x * y % 3) % 2 == 0,
            lambda x, y: ((x + y) % 2 + x * y % 3) % 2 == 0,
        )
        rule = rules[mask]
        for y in range(self.size):
            for x in range(self.size):
                if not self.fixed[y][x] and rule(x, y):
                    self.dark[y][x] = not self.dark[y][x]

    def penalty(self) -> int:
        size, score = self.size, 0
        lines = [row[:] for row in self.dark] + [[self.dark[y][x] for y in range(size)] for x in range(size)]
        finder = ([True, False, True, True, True, False, True, False, False, False, False],
                  [False, False, False, False, True, False, True, True, True, False, True])
        for line in lines:
            run, colour = 0, None
            for module in line:
                if module == colour:
                    run += 1
                    if run == 5:
                        score += 3
                    elif run > 5:
                        score += 1
                else:
                    colour, run = module, 1
            for i in range(size - 10):
                if line[i:i + 11] in finder:
                    score += 40
        for y in range(size - 1):
            for x in range(size - 1):
                if self.dark[y][x] == self.dark[y][x + 1] == self.dark[y + 1][x] == self.dark[y + 1][x + 1]:
                    score += 3
        dark = sum(map(sum, self.dark))
        score += abs(dark * 20 - size * size * 10) // (size * size) * 10
        return score


def encode(text: bytes) -> list[list[bool]]:
    """The modules of the smallest code at level M holding [text]."""
    for version in range(1, 41):
        length_bits = 8 if version <= 9 else 16
        if 4 + length_bits + len(text) * 8 <= data_codewords(version) * 8:
            break
    else:
        raise ValueError("too long for a QR code")
    words = codewords(text, version)
    best, best_score = None, None
    for mask in range(8):
        matrix = Matrix(version)
        matrix.data(words)
        matrix.mask(mask)
        matrix.format_bits(mask)
        score = matrix.penalty()
        if best_score is None or score < best_score:
            best, best_score = matrix, score
    return best.dark


def png(modules: list[list[bool]], scale: int = 8, border: int = 4) -> bytes:
    """A black and white PNG of the modules, with the quiet zone."""
    side = (len(modules) + border * 2) * scale
    rows = []
    for y in range(-border, len(modules) + border):
        line = bytearray()
        for x in range(-border, len(modules) + border):
            dark = 0 <= y < len(modules) and 0 <= x < len(modules) and modules[y][x]
            line += (b"\x00" if dark else b"\xff") * scale
        rows += [b"\x00" + bytes(line)] * scale

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    header = struct.pack(">IIBBBBB", side, side, 8, 0, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")


def main() -> int:
    parser = argparse.ArgumentParser(description="The QR code that provisions Shinkan at first boot (SPEC §3.2).")
    parser.add_argument("--url", required=True, help="where the device downloads the release APK; https")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--sha256", help="the APK's SHA-256, in hex")
    source.add_argument("--apk", help="the APK itself, to take its SHA-256")
    parser.add_argument("out", help="the PNG to write")
    args = parser.parse_args()
    if not args.url.startswith("https://"):
        parser.error("the address must be https")
    digest = args.sha256
    if args.apk:
        with open(args.apk, "rb") as apk:
            digest = hashlib.sha256(apk.read()).hexdigest()
    text = payload(args.url, digest)
    with open(args.out, "wb") as out:
        out.write(png(encode(text.encode())))
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
