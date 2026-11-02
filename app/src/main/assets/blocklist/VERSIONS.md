# The built-in block list

Fetched 2026-11-02 by `tools/blocklist/refresh.py` (SPEC §6.5), never by the app.

- Hagezi dns-blocklists at commit `5ca6caa2cd85ee3e21b61eeb410eae021f6ec098`
- StevenBlack hosts at commit `6d56ab44f2b3d27de062b5194eb51bcd8115a773`

Each list's names are normalised: lowercase ASCII, sorted by bytes, a name
already covered by a shorter one in the same list dropped. A name blocks
itself and every subdomain.

| List | Source | Version | Licence | Home |
| --- | --- | --- | --- | --- |
| ads | Hagezi Multi NORMAL | 2026.1101.0942.15 | GPL-3.0 | https://github.com/hagezi/dns-blocklists |
| gambling | BigDargon's gambling domain blocking hosts file | | MIT | https://github.com/bigdargon/hostsVN/ |
| gambling | Sinfonietta's gambling blocking hosts file | | MIT | https://github.com/Sinfonietta/hostfiles |
| porn | BigDargon's adult domain blocking hosts file | | MIT | https://github.com/bigdargon/hostsVN/ |
| porn | brijrajparmar27 - pornography | | CC BY 4.0 | https://github.com/brijrajparmar27/host-sources |
| porn | pornhosts -- a consolidated anti porn hosts file | | MIT | https://github.com/Clefspeare13/pornhosts |
| porn | Sinfonietta's snuff-site blocking hosts file | | MIT | https://github.com/Sinfonietta/hostfiles |
| porn | Sinfonietta's porn blocking hosts file | | MIT | https://github.com/Sinfonietta/hostfiles |
| porn | Tiuxo hostlist - pornography | | CC BY 4.0 | https://github.com/tiuxo/hosts |
| fakenews | Fake News | | MIT | https://github.com/marktron/fakenews |
| social | Sinfonietta's social media blocking hosts file | | MIT | https://github.com/Sinfonietta/hostfiles |
| fakenews | Shinkan: Russian state-controlled media | | GPL-3.0-or-later | tools/blocklist/state-media.txt |
