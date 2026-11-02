#!/usr/bin/env python3
"""Refresh the built-in block list that ships inside the APK (SPEC §6.5, ROADMAP block 4).

Run by the maintainer before a release, never by the app (SPEC §2.1):

    python3 tools/blocklist/refresh.py            # fetch, normalise, write the assets
    python3 tools/blocklist/refresh.py --check    # only say whether the assets are complete

Sources:
  ads       Hagezi Multi NORMAL, wildcard domains (GPL-3.0): a name blocks itself
            and every subdomain, which is the match of SPEC §6.1
  gambling, porn, fakenews, social
            StevenBlack's hosts, the extension alone (alternates/<name>-only/hosts,
            MIT), itself merged from upstream sources with licences of their own,
            each recorded from its extensions/<name>/.../update.json

Writes app/src/main/assets/blocklist/: one <list>.txt per list (a header of #
lines, then the names, lowercase, ASCII, sorted by bytes, without a name that a
shorter one in the same list already covers: the app searches them by halving),
the licence texts, and VERSIONS.md with the date, the commits fetched and every
source with its licence. Standard library only.
"""
import argparse
import datetime as dt
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/blocklist"
HAGEZI = "hagezi/dns-blocklists"
STEVENBLACK = "StevenBlack/hosts"
CATEGORIES = ("gambling", "porn", "fakenews", "social")
NAME = re.compile(r"^(?=.{1,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\.)+[a-z0-9-]{2,63}$")
NOT_HOSTS = {"localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0"}


class Failure(Exception):
    """A step that cannot go on; the message says why."""


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "shinkan-blocklist-refresh"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def raw(repo: str, branch: str, path: str) -> str:
    return fetch(f"https://raw.githubusercontent.com/{repo}/{branch}/{path}").decode("utf-8")


def api(path: str):
    return json.loads(fetch(f"https://api.github.com/{path}"))


def head_commit(repo: str, branch: str) -> str:
    return api(f"repos/{repo}/commits/{branch}")["sha"]


def names_from_domains(text: str) -> list[str]:
    """A list of one domain a line; # comments skipped."""
    return [line.strip().lower() for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]


def names_from_hosts(text: str) -> list[str]:
    """A hosts file: the names after the address of each line; comments and local names skipped."""
    out = []
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        parts = line.split()
        if len(parts) >= 2:
            out += [p.lower() for p in parts[1:]]
    return out


def normalise(names: list[str]) -> list[str]:
    """Valid ASCII names, no trailing dot, deduplicated, none under another kept one, sorted by bytes."""
    clean = {n.rstrip(".") for n in names}
    clean = {n for n in clean if n not in NOT_HOSTS and n.isascii() and NAME.match(n)}
    kept: list[str] = []
    # Ordered by reversed labels, a parent comes right before its subdomains.
    last = None
    for n in sorted(clean, key=lambda n: n.split(".")[::-1]):
        if last is not None and (n == last or n.endswith("." + last)):
            continue
        kept.append(n)
        last = n
    return sorted(kept)


def header_value(text: str, key: str) -> str:
    m = re.search(rf"^#\s*{re.escape(key)}:\s*(.+)$", text, re.MULTILINE)
    return m.group(1).strip() if m else ""


def stevenblack_sources(category: str) -> list[dict]:
    """The upstream sources merged into a StevenBlack extension, from their update.json."""
    found = []

    def walk(path: str):
        for entry in api(f"repos/{STEVENBLACK}/contents/{path}"):
            if entry["type"] == "dir":
                walk(entry["path"])
            elif entry["name"] == "update.json":
                info = json.loads(raw(STEVENBLACK, "master", entry["path"]))
                found.append({
                    "name": info.get("name", entry["path"]),
                    "url": info.get("homeurl") or info.get("url", ""),
                    "license": info.get("license", "unstated"),
                })

    walk(f"extensions/{category}")
    return found


def write_list(name: str, names: list[str], comment: list[str]):
    lines = [f"# {c}" for c in comment] + names
    (OUT / f"{name}.txt").write_text("\n".join(lines) + "\n", encoding="ascii")
    print(f"{name}: {len(names)} names")


def refresh():
    OUT.mkdir(parents=True, exist_ok=True)
    today = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")
    hagezi_sha = head_commit(HAGEZI, "main")
    stevenblack_sha = head_commit(STEVENBLACK, "master")

    text = raw(HAGEZI, hagezi_sha, "wildcard/multi-onlydomains.txt")
    ads = normalise(names_from_domains(text))
    if len(ads) < 50_000:
        raise Failure(f"Hagezi Multi has {len(ads)} names; expected over 50 000, so the download is wrong")
    write_list("ads", ads, [
        "Hagezi Multi NORMAL, wildcard domains (GPL-3.0): https://github.com/hagezi/dns-blocklists",
        f"version {header_value(text, 'Version')}, commit {hagezi_sha}",
    ])
    (OUT / "LICENSE-hagezi.txt").write_text(raw(HAGEZI, hagezi_sha, "LICENSE"), encoding="utf-8")

    rows = [f"| ads | Hagezi Multi NORMAL | {header_value(text, 'Version')} | GPL-3.0 | https://github.com/hagezi/dns-blocklists |"]
    for category in CATEGORIES:
        hosts = raw(STEVENBLACK, stevenblack_sha, f"alternates/{category}-only/hosts")
        names = normalise(names_from_hosts(hosts))
        if len(names) < 100:
            raise Failure(f"StevenBlack {category} has {len(names)} names; the download is wrong")
        write_list(category, names, [
            f"StevenBlack hosts, {category} extension alone (MIT): https://github.com/StevenBlack/hosts",
            f"commit {stevenblack_sha}; the upstream sources and their licences are in VERSIONS.md",
        ])
        for source in stevenblack_sources(category):
            rows.append(f"| {category} | {source['name']} | | {source['license']} | {source['url']} |")
    (OUT / "LICENSE-stevenblack.txt").write_text(raw(STEVENBLACK, stevenblack_sha, "license.txt"), encoding="utf-8")

    (OUT / "VERSIONS.md").write_text(
        "# The built-in block list\n\n"
        f"Fetched {today} by `tools/blocklist/refresh.py` (SPEC §6.5), never by the app.\n\n"
        f"- Hagezi dns-blocklists at commit `{hagezi_sha}`\n"
        f"- StevenBlack hosts at commit `{stevenblack_sha}`\n\n"
        "Each list's names are normalised: lowercase ASCII, sorted by bytes, a name\n"
        "already covered by a shorter one in the same list dropped. A name blocks\n"
        "itself and every subdomain.\n\n"
        "| List | Source | Version | Licence | Home |\n| --- | --- | --- | --- | --- |\n"
        + "\n".join(rows) + "\n",
        encoding="utf-8",
    )


def check() -> int:
    missing = [f for f in ["ads.txt", *(f"{c}.txt" for c in CATEGORIES), "VERSIONS.md", "LICENSE-hagezi.txt", "LICENSE-stevenblack.txt"]
               if not (OUT / f).exists()]
    if missing:
        print("missing: " + ", ".join(missing))
        return 1
    print("complete; " + (OUT / "VERSIONS.md").read_text(encoding="utf-8").splitlines()[2])
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="only say whether the assets are complete")
    args = parser.parse_args(argv)
    try:
        if args.check:
            return check()
        refresh()
        return 0
    except Failure as e:
        print(f"refresh.py: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
