# Emergency Sources

This folder hosts a test-friendly `servers.csv` for SoftEthergai's emergency-source
feature (§11.9) and the parser that consumes it (roadmap §12.5.2 item C, landed in
v1.0.21).

The file exists so the emergency-source path can be exercised end-to-end against a
**real, URL-accessible** source. Earlier device tests used
`https://example.invalid/test.csv`, which does not resolve, so the fetch could never
succeed and the feature was never actually proven on a device.

## Usage

In the SoftEthergai app: **Settings → Emergency Server Sources → Add source**

- **URL:** `https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/emergency-sources/servers.csv`
- **Label:** any label

Enable it, then run a refresh. The app fetches and parses this CSV per
`SERVER_SOURCE_ARCHITECTURE.md` §10.

## Schema

The CSV follows `SERVER_SOURCE_ARCHITECTURE.md` §10 — the "extended" format that
supersedes the VPN Gate API's 15-column layout by adding HTML-derived
protocol/port columns. It carries all **29** columns of §10.2, in that order.

Parsing is **header-driven, not positional** (§10.9 Q2 leaves column order open), so
only the four required columns are actually demanded:

| Column | Rule |
|---|---|
| `HostName` | non-empty |
| `IP` | valid IPv4 dotted quad (the identity key) |
| `schema_version` | exactly `"1.0"` on **every** row — fail-closed |
| `SourceTag` | one of `api_iphone` \| `html_parse` \| `merged` |

Everything else is optional. Port columns use `0` for "not present / not offered";
optional numeric columns use an empty field for "unknown". Booleans are always
`true`/`false`, never empty (§10.3).

File-level requirements (§10.7): pure CSV — header row plus data rows, **no comment
lines**; **UTF-8 without BOM**; **LF** line endings.

## Content status

**The 3 rows in `servers.csv` are a point-in-time snapshot, not a production list.**

They were captured on **2026-10-17** from the two public VPN Gate sources the design
document names:

1. `https://www.vpngate.net/api/iphone/` — the base 15 columns (hostname, IP, score,
   ping, speed, country, sessions, uptime, users, traffic, log type, operator,
   message, OpenVPN config).
2. `https://www.vpngate.net/en/` — the extended columns (SSL-VPN TCP port, UDP
   support, L2TP, OpenVPN ports, SSTP hostname).

Because each row combines both sources, every row carries `SourceTag=merged`, which
is exactly the §10.6 meaning of that token.

No value in this file is invented. A port the HTML page did not state is written as
`0`, and `SSTP_Port` is `0` because the page publishes the SSTP **hostname** but no
SSTP port (§10.3: a port is never fabricated). `SE_UDP_Supported` is `true` only
where the page literally renders "UDP: Supported"; the page states support but never
a UDP port number, so `SE_UDP_Port` is `0`.

**This file will go stale.** VPN Gate relays come and go, and these three servers may
be offline by the time you read this. That is acceptable for a test fixture and is
not acceptable for a production list — regenerate it (below) if you need one.

## Regeneration

Automated regeneration is not implemented (roadmap items E/F). Until then,
regenerate manually:

1. Fetch `https://www.vpngate.net/api/iphone/` (strip the `*vpn_servers` banner and
   the `#` prefix on the header row).
2. Fetch `https://www.vpngate.net/en/` and read the protocol columns per server.
3. Merge per `SERVER_SOURCE_ARCHITECTURE.md` §10.5 — `IP` is the primary key,
   `HostName` the secondary; the more complete record wins per field.
4. Emit all 29 §10.2 columns with `schema_version=1.0` and `SourceTag=merged`.
5. Replace `servers.csv` and commit.

## Safety

- Do not place credentials, keys, or private data in this file. Everything here is
  already public on `vpngate.net`.
- Do not add entries whose `IP` is not a real public IPv4, and do not use private or
  loopback ranges (`10.x`, `172.16-31.x`, `192.168.x`, `127.x`).
- Do not set `schema_version` to anything other than `"1.0"` — the parser is
  fail-closed and will reject the entire file.
- The embedded `OpenVPN_ConfigData_Base64` values are VPN Gate's own published
  configurations, including its dummy client certificate and private key. They are
  not secrets and are not usable as credentials.
