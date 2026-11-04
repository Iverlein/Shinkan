#!/usr/bin/env python3
"""check_strings against strings written wrong on purpose: each kind of fault is found.

    python3 tools/test_check_strings.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import check_strings  # noqa: E402

FORMS = [("one", "%1$d\\u00A0день"), ("few", "%1$d\\u00A0дня"), ("many", "%1$d\\u00A0дней"), ("other", "%1$d\\u00A0дня")]
BASE = {"a": "Left %1$d min", "days": [("one", "%1$d day"), ("other", "%1$d days")], "share": "%1$d %%"}
GOOD = {"a": "Ещё %1$d\\u00A0мин", "days": FORMS, "share": "%1$d\\u00A0%%"}
WRONG = {
    "not translated": ({"days": FORMS, "share": "%1$d\\u00A0%%"}, "not translated"),
    "a key too many": (GOOD | {"b": "Лишнее"}, "not in the English file"),
    "a lost placeholder": (GOOD | {"a": "Ещё мин"}, "placeholders"),
    "a plural form short": (GOOD | {"days": FORMS[:2]}, "plural forms"),
    "an ordinary space before a unit": (GOOD | {"a": "Ещё %1$d мин"}, "number and its unit"),
    "three dots": (GOOD | {"a": "Ещё %1$d\\u00A0мин..."}, "three dots"),
    "straight quotes": (GOOD | {"a": 'Ещё "%1$d"\\u00A0мин'}, "straight quotes"),
    "a hyphen for a dash": (GOOD | {"a": "Ещё - %1$d\\u00A0мин"}, "hyphen"),
    "an ordinary space before a dash": (GOOD | {"a": "Ещё — %1$d\\u00A0мин"}, "before —"),
    "a short word left hanging": (GOOD | {"a": "Ещё в %1$d\\u00A0мин"}, "short word"),
    "a banned term": (GOOD | {"a": "Лаунчер: лаунчер %1$d\\u00A0мин"}, "главный экран"),
    "PIN alone": (GOOD | {"a": "Ваш PIN: %1$d\\u00A0мин"}, "PIN-код"),
    "left in English": (GOOD | {"a": "Left %1$d\\u00A0min"}, "no Russian"),
}


def main() -> int:
    failed = 0
    if check_strings.faults(BASE, GOOD):
        print(f"good strings faulted: {check_strings.faults(BASE, GOOD)}")
        failed += 1
    for what, (russian, expected) in WRONG.items():
        found = check_strings.faults(BASE, russian)
        if not any(expected in fault for fault in found):
            print(f"{what}: not caught, got {found}")
            failed += 1
    print(f"{len(WRONG) + 1} cases: {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
