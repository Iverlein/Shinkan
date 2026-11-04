#!/usr/bin/env python3
"""The Russian strings against the English ones and against docs/russian.md.

Every key translated, the same placeholders, the four plural forms Russian needs,
Russian typography, and the terms the glossary rules out. Prints each fault and
exits 1 when there is one.

    python3 tools/check_strings.py
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/res/values/strings.xml"
RUSSIAN = ROOT / "app/src/main/res/values-ru/strings.xml"
TOOLS = "{http://schemas.android.com/tools}"
NBSP = "\\u00A0"
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sd]")
QUANTITIES = {"one", "few", "many", "other"}  # CLDR for ru; "other" is for fractions
# A term of the left column is written as the right one (docs/russian.md).
BANNED = {
    r"\bлаунчер": "главный экран",
    r"\bPIN(?!-код)": "PIN-код",
    r"частн\w+ DNS": "персональный DNS-сервер",
    r"\bдержит\b": "управляет",
    r"\bOK\b": "ОК",
    r"\bхэш": "хеш",
}
# A string with letters has Russian among them, or it was left in English.
LATIN_OK = re.compile(r"^[\s\W\d]*$|[а-яё]", re.I)


def load(path: Path) -> dict[str, list[tuple[str, str]] | str]:
    """Key to its text, or for plurals to (quantity, text) pairs; what is not translatable is left out."""
    out = {}
    for e in ET.parse(path).getroot():
        if e.get("translatable") == "false":
            continue
        name = e.get("name")
        out[name] = "".join(e.itertext()) if e.tag == "string" else [(i.get("quantity"), i.text or "") for i in e]
    return out


def texts(value) -> list[str]:
    return [value] if isinstance(value, str) else [text for _, text in value]


def typography(name: str, text: str) -> list[str]:
    out = []
    if "..." in text:
        out.append(f"{name}: three dots where … belongs")
    if '"' in text or "'" in text:
        out.append(f"{name}: straight quotes where « » belong")
    if re.search(r"\s-\s", text):
        out.append(f"{name}: a hyphen where — belongs")
    if " —" in text:
        out.append(f"{name}: an ordinary space before —")
    if re.search(r"%\d+\$d (мин|с|%%)(?![а-яё])", text):
        out.append(f"{name}: an ordinary space between a number and its unit")
    short = re.search(r"(?:^|\s)(в|с|к|у|и|из) (?=[«%\w])", text, re.I)
    if short:
        out.append(f"{name}: an ordinary space after the short word {short.group(1)!r}")
    for pattern, right in BANNED.items():
        if re.search(pattern, text):
            out.append(f"{name}: {right!r} is the term (matched {pattern})")
    return out


def faults(base: dict, russian: dict) -> list[str]:
    out = [f"{name}: not translated" for name in base if name not in russian]
    out += [f"{name}: not in the English file" for name in russian if name not in base]
    for name, value in russian.items():
        if name not in base:
            continue
        english = base[name]
        if isinstance(value, str) != isinstance(english, str):
            out.append(f"{name}: a string in one file, plurals in the other")
            continue
        if isinstance(value, list):
            have = {quantity for quantity, _ in value}
            if have != QUANTITIES:
                out.append(f"{name}: plural forms {sorted(have)}, Russian needs {sorted(QUANTITIES)}")
        wanted = set(PLACEHOLDER.findall(" ".join(texts(english))))
        for text in texts(value):
            have = set(PLACEHOLDER.findall(text))
            if have != wanted:
                out.append(f"{name}: placeholders {sorted(have)}, the English has {sorted(wanted)}")
            out += typography(name, text)
            if not LATIN_OK.search(PLACEHOLDER.sub("", text).replace(NBSP, " ").replace("%%", "")):
                out.append(f"{name}: no Russian in it")
    return out


def main() -> int:
    found = faults(load(BASE), load(RUSSIAN))
    for fault in found:
        print(fault)
    print(f"{len(found)} faults" if found else "the Russian strings are whole")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
