#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
VPN Gate Intelligent Multi-Protocol Collector V4
=================================================

Sources:
  - VPN Gate HTML
  - VPN Gate iPhone CSV API
  - Official VPN Gate mirrors

Protocols:
  - SoftEther SSL-VPN
  - OpenVPN TCP / UDP
  - L2TP/IPsec
  - MS-SSTP

Outputs:
  servers_all.json
  servers_softether.json
  servers_openvpn.json
  servers_sstp.json
  servers_l2tp.json
  servers_multiprotocol.json
  servers_ranked.json
  servers_softether_ranked.json
  servers_openvpn_ranked.json
  servers_sstp_ranked.json
  servers_l2tp_ranked.json
  servers.csv
  collection_report.json

Dependencies:
  pip install requests beautifulsoup4
"""

import base64
import csv
import io
import json
import os
import re
import socket
import sys
import time
from copy import deepcopy
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlparse

import requests
from bs4 import BeautifulSoup


# ============================================================
# CONFIG
# ============================================================

MAIN_URL = "https://www.vpngate.net/en/"
API_URL = "https://www.vpngate.net/api/iphone/"
MIRRORS_URL = "https://www.vpngate.net/en/sites.aspx"

REQUEST_TIMEOUT = 30
REQUEST_DELAY = 0.7
MAX_MIRRORS = 10

OUT_ALL = "servers_all.json"
OUT_SOFTETHER = "servers_softether.json"
OUT_OPENVPN = "servers_openvpn.json"
OUT_SSTP = "servers_sstp.json"
OUT_L2TP = "servers_l2tp.json"
OUT_MULTI = "servers_multiprotocol.json"
OUT_RANKED = "servers_ranked.json"
OUT_SOFTETHER_RANKED = "servers_softether_ranked.json"
OUT_OPENVPN_RANKED = "servers_openvpn_ranked.json"
OUT_SSTP_RANKED = "servers_sstp_ranked.json"
OUT_L2TP_RANKED = "servers_l2tp_ranked.json"
OUT_CSV = "servers.csv"
OUT_REPORT = "collection_report.json"
OUT_DIAGNOSTICS = "diagnostics_report.json"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/145.0 Safari/537.36"
    ),
    "Accept": (
        "text/html,application/xhtml+xml,application/xml;"
        "q=0.9,*/*;q=0.8"
    ),
    "Accept-Language": "en-US,en;q=0.9",
    "Connection": "keep-alive",
}

session = requests.Session()
session.headers.update(HEADERS)


# ============================================================
# GENERIC HELPERS
# ============================================================

def clean(value: Any) -> str:
    if value is None:
        return ""
    s = str(value)
    s = (
        s.replace("\xa0", " ")
         .replace("\u200b", "")
         .replace("\r", " ")
         .replace("\n", " ")
    )
    return re.sub(r"\s+", " ", s).strip()


def to_int(value: Any, default: int = 0) -> int:
    if value is None:
        return default
    s = clean(value).replace(",", "").replace(" ", "")
    m = re.search(r"-?\d+", s)
    if not m:
        return default
    try:
        return int(m.group(0))
    except Exception:
        return default


def to_float(value: Any, default: float = 0.0) -> float:
    if value is None:
        return default
    s = clean(value).replace(",", "").replace(" ", "")
    m = re.search(r"-?\d+(?:\.\d+)?", s)
    if not m:
        return default
    try:
        return float(m.group(0))
    except Exception:
        return default


def valid_ip(ip: str) -> bool:
    try:
        socket.inet_aton(ip)
        parts = ip.split(".")
        return (
            len(parts) == 4
            and all(p.isdigit() and 0 <= int(p) <= 255 for p in parts)
        )
    except Exception:
        return False


def normalize_ip(value: Any) -> str:
    s = clean(value)
    m = re.search(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", s)
    if not m:
        return ""
    ip = m.group(0)
    return ip if valid_ip(ip) else ""


def normalize_host(value: Any) -> str:
    s = clean(value).lower().rstrip(".")
    # Remove protocol prefix if accidentally present.
    s = re.sub(r"^[a-z]+://", "", s)
    # Host:port -> host, except IPv4.
    if s.count(":") == 1 and not valid_ip(s):
        s = s.rsplit(":", 1)[0]
    return s


def valid_port(port: Any) -> bool:
    p = to_int(port)
    return 1 <= p <= 65535


def source_add(server: Dict[str, Any], source: str) -> None:
    """Record a provenance tag on a server record.

    Tolerant towards stripped/imported records that lack the
    ``sources`` container (mission §16 mirror handling).
    """
    if not source:
        return

    sources = server.setdefault("sources", [])

    if source not in sources:
        sources.append(source)
        sources.sort()

    server["sourceCount"] = len(sources)


def new_server() -> Dict[str, Any]:
    return {
        "schemaVersion": "4.0",

        "identity": {
            "hostname": "",
            "ip": "",
            "ispHostname": "",
            "country": "",
            "countryLong": "",
        },

        "performance": {
            "score": 0,
            "pingMs": 0,
            "speedMbps": 0.0,
            "sessions": 0,
            "uptimeDays": 0.0,
            "totalUsers": 0,
            "totalTrafficGB": 0.0,
        },

        "logging": {
            "policy": ""
        },

        "operator": {
            "name": "",
            "message": ""
        },

        "protocols": {
            "softether": {
                "supported": False,
                "tcp": {
                    "supported": False,
                    "port": None
                },
                "udp": {
                    "supported": False,
                    "port": None
                }
            },

            "openvpn": {
                "supported": False,
                "tcp": {
                    "supported": False,
                    "port": None
                },
                "udp": {
                    "supported": False,
                    "port": None
                },
                "configAvailable": False,
                "configs": []
            },

            "l2tpIpsec": {
                "supported": False,
                "port": None
            },

            "sstp": {
                "supported": False,
                "hostname": "",
                "port": None
            }
        },

        "sources": [],
        "sourceCount": 0,

        "fieldSources": {},

        "quality": {
            "overall": 0,
            "softether": 0,
            "openvpn": 0,
            "sstp": 0,
            "l2tp": 0
        },

        "valid": True
    }


def set_field(
    server: Dict[str, Any],
    path: str,
    value: Any,
    source: str,
    overwrite: bool = False
) -> None:
    """
    Set a nested field and remember which source supplied it.
    Empty values do not overwrite meaningful values.
    """
    if value in (None, "", False, 0, 0.0, []):
        return

    parts = path.split(".")
    obj = server

    for part in parts[:-1]:
        obj = obj[part]

    leaf = parts[-1]

    old = obj.get(leaf)

    if overwrite or old in (None, "", False, 0, 0.0, []):
        obj[leaf] = value

    sources = server["fieldSources"].setdefault(
        path, []
    )

    if source not in sources:
        sources.append(source)


def mark_supported(
    server: Dict[str, Any],
    protocol: str,
    source: str
) -> None:
    path = f"protocols.{protocol}.supported"
    set_field(server, path, True, source)


# ============================================================
# HTTP
# ============================================================

def fetch(url: str) -> Optional[str]:
    print(f"🌐 GET {url}")
    try:
        response = session.get(
            url,
            timeout=REQUEST_TIMEOUT,
            allow_redirects=True
        )
        response.raise_for_status()

        print(
            f"   HTTP {response.status_code} | "
            f"{len(response.content):,} bytes | "
            f"final={response.url}"
        )

        time.sleep(REQUEST_DELAY)
        return response.text

    except requests.RequestException as exc:
        print(f"   ❌ Request failed: {exc}")
        return None


# ============================================================
# API CSV
# ============================================================

def find_api_header(lines: List[str]) -> int:
    """
    VPN Gate /api/iphone/ currently starts with:
        *vpn_servers
        #HostName,IP,...
    """
    for i, line in enumerate(lines):
        if re.match(r"^\s*#?HostName\s*,", line):
            return i
    return -1


def decode_openvpn_config(
    value: str
) -> Dict[str, Any]:
    """
    Decode OpenVPN_ConfigData_Base64 and inspect remote/proto.

    VPN Gate publishes the OpenVPN configuration as Base64.
    We do not assume TCP/UDP ports; we read remote/proto
    directives from the actual configuration when available.
    """
    result = {
        "decoded": False,
        "protocols": [],
        "remotes": [],
        "rawPreview": ""
    }

    if not value:
        return result

    try:
        raw = base64.b64decode(
            value,
            validate=False
        ).decode(
            "utf-8",
            errors="replace"
        )
    except Exception:
        return result

    result["decoded"] = True
    result["rawPreview"] = raw[:500]

    # proto tcp / proto udp
    for proto in re.findall(
        r"^\s*proto\s+(\S+)",
        raw,
        re.IGNORECASE | re.MULTILINE
    ):
        proto = proto.lower()
        if proto not in result["protocols"]:
            result["protocols"].append(proto)

    # remote host [port] [proto]
    for line in raw.splitlines():
        line = line.strip()

        if not line.lower().startswith("remote "):
            continue

        parts = line.split()

        if len(parts) < 2:
            continue

        host = parts[1]
        port = None
        proto = ""

        if len(parts) >= 3 and valid_port(parts[2]):
            port = to_int(parts[2])

        if len(parts) >= 4:
            proto = parts[3].lower()

        result["remotes"].append({
            "host": host,
            "port": port,
            "proto": proto
        })

    return result


def parse_api(
    text: str,
    source: str = "api"
) -> List[Dict[str, Any]]:

    print("   🔍 Parsing VPN Gate API CSV...")

    lines = text.splitlines()

    header_index = find_api_header(lines)

    if header_index < 0:
        print("   ❌ Actual CSV header not found")
        return []

    csv_text = "\n".join(
        lines[header_index:]
    )

    # Remove leading '#' from actual header.
    csv_text = csv_text.lstrip()
    if csv_text.startswith("#"):
        csv_text = csv_text[1:]

    try:
        reader = csv.DictReader(
            io.StringIO(csv_text)
        )
    except Exception as exc:
        print(f"   ❌ CSV initialization error: {exc}")
        return []

    print(
        "   📋 API columns: "
        + ", ".join(reader.fieldnames or [])
    )

    servers = []

    for row in reader:
        if not row:
            continue

        # --------------------------------------------------------
        # Repair mis-split rows. Free-text Operator/Message fields
        # may contain UNQUOTED commas; the Base64 payload never
        # does, so it stays right-anchored. Rebuild positional
        # values and realign them to the canonical 15 columns.
        # --------------------------------------------------------

        ordered_values: List[str] = []

        for key_part, value_part in row.items():

            if key_part is None:
                ordered_values.extend(
                    value_part if isinstance(value_part, list) else [value_part]
                )
            else:
                ordered_values.append(value_part)

        if len(ordered_values) > 15:
            repaired_values = (
                ordered_values[:12]
                + [
                    ordered_values[12],
                    ",".join(ordered_values[13:-1]),
                    ordered_values[-1],
                ]
            )
        elif len(ordered_values) < 15:
            repaired_values = ordered_values + [""] * (
                15 - len(ordered_values)
            )
        else:
            repaired_values = ordered_values

        row = dict(
            zip(reader.fieldnames or [], repaired_values)
        )

        hostname = clean(
            row.get("HostName", "")
        )
        ip = normalize_ip(
            row.get("IP", "")
        )

        if not hostname or not valid_ip(ip):
            continue

        server = new_server()

        set_field(
            server,
            "identity.hostname",
            hostname.lower(),
            source
        )

        set_field(
            server,
            "identity.ip",
            ip,
            source
        )

        set_field(
            server,
            "identity.country",
            clean(row.get("CountryShort")),
            source
        )

        set_field(
            server,
            "identity.countryLong",
            clean(row.get("CountryLong")),
            source
        )

        set_field(
            server,
            "performance.score",
            to_int(row.get("Score")),
            source
        )

        set_field(
            server,
            "performance.pingMs",
            to_float(row.get("Ping")),
            source
        )

        # API Speed is bits/sec -> Mbps.
        speed_bps = to_float(
            row.get("Speed")
        )

        if speed_bps > 0:
            set_field(
                server,
                "performance.speedMbps",
                speed_bps / 1_000_000.0,
                source
            )

        set_field(
            server,
            "performance.sessions",
            to_int(row.get("NumVpnSessions")),
            source
        )

        # API Uptime is milliseconds in the current feed.
        uptime_ms = to_float(
            row.get("Uptime")
        )

        if uptime_ms > 0:
            uptime_days = uptime_ms / 86_400_000.0
            set_field(
                server,
                "performance.uptimeDays",
                uptime_days,
                source
            )

        set_field(
            server,
            "performance.totalUsers",
            to_int(row.get("TotalUsers")),
            source
        )

        total_traffic = to_float(
            row.get("TotalTraffic")
        )

        if total_traffic > 0:
            # Current API unit is bytes.
            set_field(
                server,
                "performance.totalTrafficGB",
                total_traffic / 1024**3,
                source
            )

        set_field(
            server,
            "logging.policy",
            clean(row.get("LogType")),
            source
        )

        set_field(
            server,
            "operator.name",
            clean(row.get("Operator")),
            source
        )

        set_field(
            server,
            "operator.message",
            clean(row.get("Message")),
            source
        )

        config_b64 = clean(
            row.get("OpenVPN_ConfigData_Base64")
        )

        if config_b64:
            mark_supported(
                server,
                "openvpn",
                source
            )

            set_field(
                server,
                "protocols.openvpn.configAvailable",
                True,
                source
            )

            config_info = decode_openvpn_config(
                config_b64
            )

            # Global "proto" directive can carry the transport family
            # for remote lines that omit it (mission §7: ports are
            # attributed only from actual config directives).
            global_families = sorted({
                p.split("-")[0]
                for p in config_info.get("protocols", [])
                if p.lower().startswith(("tcp", "udp"))
            })

            inherited_proto = (
                global_families[0]
                if len(global_families) == 1
                else ""
            )

            for remote in config_info["remotes"]:
                server[
                    "protocols"
                ][
                    "openvpn"
                ][
                    "configs"
                ].append(remote)

                proto = remote.get("proto", "").lower()

                if not proto:
                    proto = inherited_proto

                port = remote.get("port")

                if proto in ("tcp", "tcp-client"):
                    set_field(
                        server,
                        "protocols.openvpn.tcp.supported",
                        True,
                        source
                    )

                    if valid_port(port):
                        set_field(
                            server,
                            "protocols.openvpn.tcp.port",
                            port,
                            source
                        )

                elif proto in ("udp", "udp4", "udp6"):
                    set_field(
                        server,
                        "protocols.openvpn.udp.supported",
                        True,
                        source
                    )

                    if valid_port(port):
                        set_field(
                            server,
                            "protocols.openvpn.udp.port",
                            port,
                            source
                        )

            # Some configs use "proto" globally.
            for proto in config_info["protocols"]:
                if proto in ("tcp", "tcp-client"):
                    set_field(
                        server,
                        "protocols.openvpn.tcp.supported",
                        True,
                        source
                    )
                elif proto in ("udp", "udp4", "udp6"):
                    set_field(
                        server,
                        "protocols.openvpn.udp.supported",
                        True,
                        source
                    )

        source_add(server, source)
        servers.append(server)

    print(
        f"   📦 API servers: {len(servers)}"
    )

    return servers


# ============================================================
# ============================================================
# UPTIME / TABLE HELPERS
# ============================================================

def parse_uptime_days(text: str) -> float:
    """
    Parse VPN Gate uptime snippets such as ``93 days``,
    ``11 hours`` or ``45 mins`` into fractional days.
    Unknown formats yield 0.0.
    """
    m = re.search(
        r"(\d+(?:\.\d+)?)\s*(mins?|minutes?|hours?|hrs?|days?)",
        clean(text),
        re.IGNORECASE,
    )

    if not m:
        return 0.0

    value = float(m.group(1))
    unit = m.group(2).lower()

    if unit.startswith("min"):
        return round(value / 1440.0, 4)

    if unit.startswith("h"):
        return round(value / 24.0, 4)

    return round(value, 4)


HOSTS_TABLE_ID = "vg_hosts_table_id"

COLUMN_HEADER_KEYWORDS = {
    "country": ("country", "physical location"),
    "host": ("ddns hostname", "ip address", "hostname"),
    "sessions": ("vpn sessions", "uptime"),
    "perf": ("line quality", "throughput", "ping"),
    "softether": ("ssl-vpn",),
    "l2tp": ("l2tp/ipsec", "l2tp"),
    "openvpn": ("openvpn",),
    "sstp": ("ms-sstp", "sstp"),
    "operator": ("volunteer operator", "operator's name"),
    "score": ("score", "quality"),
}


def _make_soup(html: str):
    """Build a parse tree from real (malformed) VPN Gate HTML.

    The live page nests the hosts table in ``<td><p><span>`` and each
    header block carries an UNMATCHED ``</td>`` before its ``</tr>``.
    Under ``html.parser`` that stray closing tag unwinds the stack
    all the way to the OUTER ``<td>`` and silently drops every data
    row, so the pattern is collapsed before parsing. ``lxml``
    tolerates the malformation and is preferred when installed.
    """
    sanitized = re.sub(
        r"</td>(?:\s*</td>)+(\s*</tr>)",
        r"</td>\1",
        html,
    )

    try:
        return BeautifulSoup(sanitized, "lxml")
    except Exception:
        return BeautifulSoup(sanitized, "html.parser")


def find_hosts_tables(soup) -> List[Any]:
    """All candidate hosts tables (the live page embeds several
    elements sharing the id)."""
    return soup.find_all("table", id=HOSTS_TABLE_ID)


def build_column_map(table) -> Dict[str, int]:
    """
    Derive column-name -> index mapping from the table header row.
    Derived from the actual DOM labels, never hardcoded positions.
    """
    header_cells: List[Any] = []

    for tr in table.find_all("tr"):
        cells = tr.find_all(["td", "th"])

        if any(
            "vg_table_header" in (c.get("class") or [])
            for c in cells
        ):
            header_cells = cells
            break

    if not header_cells:
        rows = table.find_all("tr")

        if rows:
            header_cells = rows[0].find_all(["td", "th"])

    colmap: Dict[str, int] = {}

    for idx, cell in enumerate(header_cells):
        label = clean(cell.get_text(" ", strip=True)).lower()

        for key, keywords in COLUMN_HEADER_KEYWORDS.items():
            if key in colmap:
                continue

            if any(kw in label for kw in keywords):
                colmap[key] = idx
                break

    return colmap


def select_hosts_table(candidates: List[Any]) -> Optional[Any]:
    """Pick the genuine hosts list out of duplicate-id candidates:
    prefer the one whose header exposes every expected column."""
    required = {"country", "host", "softether", "openvpn"}

    for table in candidates:
        try:
            colmap = build_column_map(table)
        except Exception:
            continue

        if required <= set(colmap):
            return table

    return candidates[0] if candidates else None


# HTML PROTOCOL PARSER
# ============================================================

def parse_sstp(value: str) -> Tuple[bool, str, Optional[int]]:
    m = re.search(
        r"SSTP\s+Hostname\s*:\s*([A-Za-z0-9._-]+)"
        r"(?::(\d+))?",
        value,
        re.IGNORECASE
    )

    if not m:
        return False, "", None

    host = normalize_host(m.group(1))
    port = to_int(m.group(2)) if m.group(2) else None

    return True, host, port


# ============================================================
# HTML SERVER ROW
# ============================================================

def parse_html(
    html: str,
    source: str
) -> List[Dict[str, Any]]:

    print(
        f"   \U0001f50d Parsing HTML: {len(html):,} chars"
    )

    soup = _make_soup(html)

    candidates = find_hosts_tables(soup)
    table = select_hosts_table(candidates)

    rows: List[Any] = []

    if table is not None:
        colmap = build_column_map(table)
        rows = table.find_all("tr")
        print(f"   \U0001f5a9 Hosts table columns: {colmap}")
    else:
        # Fallback: canonical VPN Gate layout.
        colmap = {
            "country": 0,
            "host": 1,
            "sessions": 2,
            "perf": 3,
            "softether": 4,
            "l2tp": 5,
            "openvpn": 6,
            "sstp": 7,
            "operator": 8,
            "score": 9,
        }
        rows = soup.find_all("tr")

    print(
        f"   \U0001f527 HTML rows: {len(rows)}"
    )

    def cell(tds, key):
        idx = colmap.get(key)

        if idx is None or idx >= len(tds):
            return None

        return tds[idx]

    servers: List[Dict[str, Any]] = []
    seen = set()

    for tr in rows:
        tds = tr.find_all("td")

        if not tds:
            continue

        first_classes = tds[0].get("class") or []

        if "vg_table_header" in first_classes:
            continue

        host_cell = cell(tds, "host")

        host_text = clean(
            host_cell.get_text(" ", strip=True)
            if host_cell is not None
            else tr.get_text(" ", strip=True)
        )

        host_match = re.search(
            r"\b([A-Za-z0-9._-]+\.opengw\.net)\b",
            host_text,
            re.IGNORECASE,
        )

        if not host_match:
            continue

        hostname = normalize_host(host_match.group(1))

        ip = normalize_ip(host_text)

        if not ip:
            ip = normalize_ip(tr.get_text(" ", strip=True))

        if not ip:
            continue

        if ip in seen:
            continue

        seen.add(ip)

        server = new_server()

        set_field(server, "identity.hostname", hostname, source)
        set_field(server, "identity.ip", ip, source)

        # ISP hostname lives in parentheses after the IP.
        isp_match = re.search(
            r"\(([^()]+)\)", host_text
        )

        if isp_match:
            isp = normalize_host(isp_match.group(1))

            if isp and not valid_ip(isp):
                set_field(
                    server, "identity.ispHostname", isp, source
                )

        # Country: ISO code from the flag image (authoritative),
        # long name from the cell text next to it.
        country_cell = cell(tds, "country")

        if country_cell is not None:
            img = country_cell.find("img", src=re.compile(r"flags/", re.I))

            if img is not None:
                iso_m = re.search(
                    r"flags/([A-Za-z]{2})\.(?:png|gif|jpg|jpeg)",
                    img.get("src") or "",
                    re.IGNORECASE,
                )

                if iso_m:
                    set_field(
                        server,
                        "identity.country",
                        iso_m.group(1).upper(),
                        source,
                    )

            country_long = clean(
                country_cell.get_text(" ", strip=True)
            )

            country_long = re.sub(
                r"\bphysical location\b", "",
                country_long,
                flags=re.IGNORECASE,
            )

            set_field(
                server,
                "identity.countryLong",
                clean(country_long),
                source,
            )

        # Sessions / uptime / cumulative users.
        sessions_cell = cell(tds, "sessions")

        if sessions_cell is not None:
            s_txt = clean(sessions_cell.get_text(" ", strip=True))

            s_m = re.search(r"([\d,]+)\s*session", s_txt, re.I)

            if s_m:
                set_field(
                    server,
                    "performance.sessions",
                    to_int(s_m.group(1)),
                    source,
                )

            uptime_days = parse_uptime_days(s_txt)

            if uptime_days > 0:
                set_field(
                    server,
                    "performance.uptimeDays",
                    uptime_days,
                    source,
                )

            users_m = re.search(
                r"total\s+([\d,]+)\s+user", s_txt, re.I
            )

            if users_m:
                set_field(
                    server,
                    "performance.totalUsers",
                    to_int(users_m.group(1)),
                    source,
                )

        # Line quality: throughput, ping, transfers, logging policy.
        perf_cell = cell(tds, "perf")

        if perf_cell is not None:
            p_txt = clean(perf_cell.get_text(" ", strip=True))

            speed_m = re.search(r"([\d.,]+)\s*Mbps", p_txt, re.I)

            if speed_m:
                set_field(
                    server,
                    "performance.speedMbps",
                    to_float(speed_m.group(1)),
                    source,
                )

            ping_m = re.search(
                r"Ping:\s*([\d.,]+)\s*ms", p_txt, re.I
            )

            if ping_m:
                set_field(
                    server,
                    "performance.pingMs",
                    to_float(ping_m.group(1)),
                    source,
                )

            traffic_m = re.search(
                r"([\d.,]+)\s*(GB|TB)", p_txt, re.I
            )

            if traffic_m:
                gb = to_float(traffic_m.group(1))

                if traffic_m.group(2).upper() == "TB":
                    gb *= 1024.0

                set_field(
                    server,
                    "performance.totalTrafficGB",
                    gb,
                    source,
                )

            policy_m = re.search(
                r"Logging policy:\s*(.+)$", p_txt, re.I
            )

            if policy_m:
                set_field(
                    server,
                    "logging.policy",
                    clean(policy_m.group(1)),
                    source,
                )

        # ---- SoftEther / SSL-VPN (column-scoped; never borrows
        #      ports from other protocol cells -- mission Bug 1).
        se_cell = cell(tds, "softether")

        if se_cell is not None:
            se_txt = clean(se_cell.get_text(" ", strip=True))

            tcp_m = re.search(r"TCP[:\s]*(\d+)", se_txt, re.I)
            udp_supported = bool(
                re.search(r"UDP[:\s]*Supported", se_txt, re.I)
            )

            udp_port_m = re.search(
                r"UDP[:\s]*(\d+)", se_txt, re.I
            )

            se_tcp_ok = bool(tcp_m and valid_port(to_int(tcp_m.group(1))))

            if se_tcp_ok:
                set_field(
                    server,
                    "protocols.softether.tcp.supported",
                    True,
                    source,
                )
                set_field(
                    server,
                    "protocols.softether.tcp.port",
                    to_int(tcp_m.group(1)),
                    source,
                )

            if udp_supported or (udp_port_m and valid_port(to_int(udp_port_m.group(1)))):
                set_field(
                    server,
                    "protocols.softether.udp.supported",
                    True,
                    source,
                )
                # \u00a76: the UDP port stays null unless printed.
                if udp_port_m and valid_port(to_int(udp_port_m.group(1))):
                    set_field(
                        server,
                        "protocols.softether.udp.port",
                        to_int(udp_port_m.group(1)),
                        source,
                    )

            if se_tcp_ok or udp_supported or (
                udp_port_m and valid_port(to_int(udp_port_m.group(1)))
            ):
                mark_supported(server, "softether", source)

        # ---- L2TP/IPsec
        l2tp_cell = cell(tds, "l2tp")

        if l2tp_cell is not None:
            l2tp_txt = clean(l2tp_cell.get_text(" ", strip=True))

            if re.search(r"L2TP", l2tp_txt, re.I):
                mark_supported(server, "l2tpIpsec", source)
                set_field(
                    server, "protocols.l2tpIpsec.port", 1701, source
                )

        # ---- OpenVPN: URL params on the do_openvpn link are
        #      authoritative; cell text is only a fallback.
        ovpn_cell = cell(tds, "openvpn")

        if ovpn_cell is not None:
            link = ovpn_cell.find(
                "a", href=lambda h: h and "do_openvpn" in h.lower()
            )

            params: Dict[str, str] = {}

            if link is not None:
                href = link["href"]
                query = href.split("?")[-1]

                for chunk in query.split("&"):
                    if "=" in chunk:
                        k, v = chunk.split("=", 1)
                        params[k.lower()] = v

            if params:
                set_field(
                    server,
                    "protocols.openvpn.configAvailable",
                    True,
                    source,
                )
                mark_supported(server, "openvpn", source)

                tcp_q = to_int(params.get("tcp", "0"))
                udp_q = to_int(params.get("udp", "0"))

                if valid_port(tcp_q):
                    set_field(
                        server,
                        "protocols.openvpn.tcp.supported",
                        True,
                        source,
                    )
                    set_field(
                        server,
                        "protocols.openvpn.tcp.port",
                        tcp_q,
                        source,
                    )

                if valid_port(udp_q):
                    set_field(
                        server,
                        "protocols.openvpn.udp.supported",
                        True,
                        source,
                    )
                    set_field(
                        server,
                        "protocols.openvpn.udp.port",
                        udp_q,
                        source,
                    )
            else:
                ovpn_txt = clean(
                    ovpn_cell.get_text(" ", strip=True)
                )

                tcp_m = re.search(r"TCP[:\s]*(\d+)", ovpn_txt, re.I)
                udp_m = re.search(r"UDP[:\s]*(\d+)", ovpn_txt, re.I)

                if tcp_m and valid_port(to_int(tcp_m.group(1))):
                    mark_supported(server, "openvpn", source)
                    set_field(
                        server,
                        "protocols.openvpn.tcp.supported",
                        True,
                        source,
                    )
                    set_field(
                        server,
                        "protocols.openvpn.tcp.port",
                        to_int(tcp_m.group(1)),
                        source,
                    )

                if udp_m and valid_port(to_int(udp_m.group(1))):
                    mark_supported(server, "openvpn", source)
                    set_field(
                        server,
                        "protocols.openvpn.udp.supported",
                        True,
                        source,
                    )
                    set_field(
                        server,
                        "protocols.openvpn.udp.port",
                        to_int(udp_m.group(1)),
                        source,
                    )

                if re.search(
                    r"OpenVPN\s*Config\s*file", ovpn_txt, re.I
                ):
                    mark_supported(server, "openvpn", source)
                    set_field(
                        server,
                        "protocols.openvpn.configAvailable",
                        True,
                        source,
                    )

        # ---- MS-SSTP (port stays null unless printed).
        sstp_cell = cell(tds, "sstp")

        if sstp_cell is not None:
            supported, sstp_host, sstp_port = parse_sstp(
                clean(sstp_cell.get_text(" ", strip=True))
            )

            if supported:
                mark_supported(server, "sstp", source)
                set_field(
                    server,
                    "protocols.sstp.hostname",
                    sstp_host,
                    source,
                )

                if valid_port(sstp_port):
                    set_field(
                        server,
                        "protocols.sstp.port",
                        sstp_port,
                        source,
                    )

        # ---- Operator + score.
        op_cell = cell(tds, "operator")

        if op_cell is not None:
            operator = clean(op_cell.get_text(" ", strip=True))
            operator = re.sub(
                r"^by\s+", "", operator, flags=re.I
            )

            if operator:
                set_field(
                    server, "operator.name", operator, source
                )

        score_cell = cell(tds, "score")

        if score_cell is not None:
            score_txt = clean(score_cell.get_text(" ", strip=True))

            if score_txt:
                set_field(
                    server,
                    "performance.score",
                    to_int(score_txt),
                    source,
                )

        source_add(server, source)
        servers.append(server)

    print(
        f"   \U0001f4e6 HTML servers: {len(servers)}"
    )

    return servers


# ============================================================
# DIAGNOSTICS (plan T2)
# ============================================================

def _diag_cell(tds: List[Any], colmap: Dict[str, int], key: str):
    idx = colmap.get(key)

    if idx is None or idx >= len(tds):
        return None

    return tds[idx]


def _diag_openvpn_expect(cell) -> Dict[str, Any]:
    """What the parser SHOULD derive from one OpenVPN cell:
    ``do_openvpn`` query params are authoritative, cell text is the
    fallback, and param-vs-text drift is reported separately."""
    facts: Dict[str, Any] = {
        "cellPresent": cell is not None,
        "link": False,
        "params": {},
        "textTcp": None,
        "textUdp": None,
        "expectTcp": None,
        "expectUdp": None,
        "paramTextDrift": False,
    }

    if cell is None:
        return facts

    link = cell.find(
        "a", href=lambda h: h and "do_openvpn" in h.lower()
    )

    if link is not None:
        facts["link"] = True
        query = link["href"].split("?")[-1]

        for chunk in query.split("&"):
            if "=" in chunk:
                k, v = chunk.split("=", 1)
                facts["params"][k.lower()] = v

    txt = clean(cell.get_text(" ", strip=True))

    tcp_m = re.search(r"TCP[:\s]*(\d+)", txt, re.I)
    udp_m = re.search(r"UDP[:\s]*(\d+)", txt, re.I)

    if tcp_m and valid_port(to_int(tcp_m.group(1))):
        facts["textTcp"] = to_int(tcp_m.group(1))

    if udp_m and valid_port(to_int(udp_m.group(1))):
        facts["textUdp"] = to_int(udp_m.group(1))

    if facts["params"]:
        tcp_q = to_int(facts["params"].get("tcp", "0"))
        udp_q = to_int(facts["params"].get("udp", "0"))

        facts["expectTcp"] = tcp_q if valid_port(tcp_q) else None
        facts["expectUdp"] = udp_q if valid_port(udp_q) else None

        if (
            facts["textTcp"] is not None
            and facts["expectTcp"] != facts["textTcp"]
        ) or (
            facts["textUdp"] is not None
            and facts["expectUdp"] != facts["textUdp"]
        ):
            facts["paramTextDrift"] = True
    else:
        # No usable params: the parser falls back to the cell text.
        facts["expectTcp"] = facts["textTcp"]
        facts["expectUdp"] = facts["textUdp"]

    return facts


def _diag_row_facts(tds: List[Any], colmap: Dict[str, int]) -> Dict[str, Any]:
    """Raw per-cell ground-truth facts for one hosts-table row,
    extracted independently from the structured parser."""
    facts: Dict[str, Any] = {"cells": len(tds)}

    host_cell = _diag_cell(tds, colmap, "host")
    host_text = clean(
        host_cell.get_text(" ", strip=True)
        if host_cell is not None
        else ""
    )

    host_m = re.search(
        r"\b([A-Za-z0-9._-]+\.opengw\.net)\b", host_text, re.I
    )

    facts["hostname"] = normalize_host(host_m.group(1)) if host_m else ""
    facts["ip"] = normalize_ip(host_text)

    # Country: ISO code from the flag image.
    country_cell = _diag_cell(tds, colmap, "country")
    facts["country"] = ""

    if country_cell is not None:
        img = country_cell.find(
            "img", src=re.compile(r"flags/", re.I)
        )

        if img is not None:
            iso_m = re.search(
                r"flags/([A-Za-z]{2})\.(?:png|gif|jpg|jpeg)",
                img.get("src") or "",
                re.I,
            )

            if iso_m:
                facts["country"] = iso_m.group(1).upper()

    # SoftEther / SSL-VPN cell.
    se_cell = _diag_cell(tds, colmap, "softether")
    se_txt = clean(
        se_cell.get_text(" ", strip=True) if se_cell is not None else ""
    )

    se_tcp_m = re.search(r"TCP[:\s]*(\d+)", se_txt, re.I)
    se_udp_m = re.search(r"UDP[:\s]*(\d+)", se_txt, re.I)

    facts["seTcp"] = (
        to_int(se_tcp_m.group(1))
        if se_tcp_m and valid_port(to_int(se_tcp_m.group(1)))
        else None
    )
    facts["seUdpSupported"] = bool(
        re.search(r"UDP[:\s]*Supported", se_txt, re.I)
    )
    facts["seUdpPort"] = (
        to_int(se_udp_m.group(1))
        if se_udp_m and valid_port(to_int(se_udp_m.group(1)))
        else None
    )

    # L2TP/IPsec cell.
    l2tp_cell = _diag_cell(tds, colmap, "l2tp")
    l2tp_txt = clean(
        l2tp_cell.get_text(" ", strip=True) if l2tp_cell is not None else ""
    )
    facts["l2tp"] = bool(re.search(r"L2TP", l2tp_txt, re.I))

    # OpenVPN cell.
    facts["openvpn"] = _diag_openvpn_expect(
        _diag_cell(tds, colmap, "openvpn")
    )

    # MS-SSTP cell.
    sstp_cell = _diag_cell(tds, colmap, "sstp")
    supported, host, port = parse_sstp(
        clean(
            sstp_cell.get_text(" ", strip=True)
            if sstp_cell is not None
            else ""
        )
    )

    facts["sstpSupported"] = supported
    facts["sstpHost"] = host if supported else ""
    facts["sstpPort"] = port if supported else None

    return facts


def diagnose_html(
    html: str,
    source: str = "html",
    verbose: bool = True,
) -> Dict[str, Any]:
    """Compare structured parse results against raw cell facts for
    EVERY hosts-table row and aggregate mismatch counts (plan T2.1).

    No port is ever invented here: an absent fact stays None, so an
    invented port on the parsed side is reported (§6/§9/§38).
    """
    soup = _make_soup(html)
    table = select_hosts_table(find_hosts_tables(soup))

    if table is None:
        return {
            "rows": 0,
            "compared": 0,
            "mismatchTotal": 0,
            "mismatches": [],
            "mismatchCounts": {},
            "rowAnomalies": {},
        }

    colmap = build_column_map(table)
    expected_cols = max(colmap.values()) + 1 if colmap else 10

    parsed = {
        s["identity"]["hostname"]: s
        for s in parse_html(html, source)
    }

    mismatches: List[Dict[str, Any]] = []
    counts: Dict[str, int] = {}
    anomalies: Dict[str, int] = {}
    rows = 0
    compared = 0
    seen_ips = set()

    def bump(bucket: Dict[str, int], key: str) -> None:
        bucket[key] = bucket.get(key, 0) + 1

    def bad(host: str, cls: str, fact: Any, got: Any) -> None:
        bump(counts, cls)
        mismatches.append({
            "host": host,
            "class": cls,
            "fact": fact,
            "parsed": got,
        })

    for tr in table.find_all("tr"):
        tds = tr.find_all("td")

        if not tds:
            continue

        if "vg_table_header" in (tds[0].get("class") or []):
            continue

        rows += 1

        if len(tds) < expected_cols:
            bump(anomalies, "row_short")

        facts = _diag_row_facts(tds, colmap)

        if not facts["hostname"]:
            bump(anomalies, "row_no_host")
            continue

        if facts["ip"]:
            if facts["ip"] in seen_ips:
                bump(anomalies, "row_duplicate_ip")
                continue
            seen_ips.add(facts["ip"])

        server = parsed.get(facts["hostname"])

        if server is None:
            bad(
                facts["hostname"],
                "missing_parsed_server",
                facts["hostname"],
                None,
            )
            continue

        compared += 1
        host = facts["hostname"]
        p = server["protocols"]

        # ---- SoftEther TCP (strictly from the SSL-VPN cell)
        se_tcp = p["softether"]["tcp"]

        if facts["seTcp"] is not None:
            if (
                not se_tcp["supported"]
                or se_tcp["port"] != facts["seTcp"]
            ):
                bad(host, "softether_tcp", facts["seTcp"], se_tcp["port"])
        elif se_tcp["supported"]:
            bad(host, "softether_tcp_invented", None, se_tcp["port"])

        # ---- SoftEther UDP (§6: port None unless printed)
        se_udp = p["softether"]["udp"]
        udp_fact = (
            facts["seUdpSupported"]
            or facts["seUdpPort"] is not None
        )

        if udp_fact and not se_udp["supported"]:
            bad(host, "softether_udp", True, False)
        elif not udp_fact and se_udp["supported"]:
            bad(host, "softether_udp_invented", False, True)

        if (
            facts["seUdpPort"] is not None
            and se_udp["port"] != facts["seUdpPort"]
        ):
            bad(
                host,
                "softether_udp_port",
                facts["seUdpPort"],
                se_udp["port"],
            )
        elif facts["seUdpPort"] is None and se_udp["port"] is not None:
            bad(host, "softether_udp_port_invented", None, se_udp["port"])

        # ---- OpenVPN (params authoritative, else cell text)
        ov = facts["openvpn"]
        ovpn = p["openvpn"]

        if ov["cellPresent"] and not ov["link"]:
            bump(anomalies, "openvpn_link_missing")

        if ov["paramTextDrift"]:
            bump(anomalies, "openvpn_param_text_drift")

        if ov["expectTcp"] is not None:
            if (
                not ovpn["tcp"]["supported"]
                or ovpn["tcp"]["port"] != ov["expectTcp"]
            ):
                bad(
                    host,
                    "openvpn_tcp",
                    ov["expectTcp"],
                    ovpn["tcp"]["port"],
                )
        elif ovpn["tcp"]["supported"]:
            bad(host, "openvpn_tcp_no_fact", None, ovpn["tcp"]["port"])

        if ov["expectUdp"] is not None:
            if (
                not ovpn["udp"]["supported"]
                or ovpn["udp"]["port"] != ov["expectUdp"]
            ):
                bad(
                    host,
                    "openvpn_udp",
                    ov["expectUdp"],
                    ovpn["udp"]["port"],
                )
        elif ovpn["udp"]["supported"] and ovpn["udp"]["port"] is not None:
            bad(host, "openvpn_udp_no_fact", None, ovpn["udp"]["port"])

        # ---- L2TP/IPsec
        if facts["l2tp"] != bool(p["l2tpIpsec"]["supported"]):
            bad(
                host,
                "l2tp",
                facts["l2tp"],
                p["l2tpIpsec"]["supported"],
            )

        # ---- SSTP (§9: port None unless printed)
        sstp = p["sstp"]

        if facts["sstpSupported"]:
            if (
                not sstp["supported"]
                or sstp["hostname"] != facts["sstpHost"]
            ):
                bad(host, "sstp_host", facts["sstpHost"], sstp["hostname"])

            if facts["sstpPort"] is not None:
                if sstp["port"] != facts["sstpPort"]:
                    bad(host, "sstp_port", facts["sstpPort"], sstp["port"])
            elif sstp["port"] is not None:
                bad(host, "sstp_port_invented", None, sstp["port"])
        elif sstp["supported"]:
            bad(host, "sstp_invented", False, True)

        # ---- Country
        if (
            facts["country"]
            and server["identity"]["country"] != facts["country"]
        ):
            bad(
                host,
                "country",
                facts["country"],
                server["identity"]["country"],
            )

    if verbose:
        print()
        print(
            f"\U0001fa7a Diagnostics: {rows} rows | "
            f"{compared} compared | "
            f"{len(mismatches)} mismatches"
        )

        for item in mismatches:
            print(
                f"   \u274c {item['host']}: {item['class']} "
                f"fact={item['fact']!r} parsed={item['parsed']!r}"
            )

        if anomalies:
            print(f"   Anomalies: {anomalies}")

    return {
        "rows": rows,
        "compared": compared,
        "mismatchTotal": len(mismatches),
        "mismatches": mismatches,
        "mismatchCounts": dict(sorted(counts.items())),
        "rowAnomalies": dict(sorted(anomalies.items())),
    }


# MIRRORS
# ============================================================

def discover_mirrors() -> List[str]:

    html = fetch(
        MIRRORS_URL
    )

    if not html:
        return []

    soup = BeautifulSoup(
        html,
        "html.parser"
    )

    mirrors = []

    for a in soup.find_all(
        "a",
        href=True
    ):

        href = clean(
            a.get("href")
        )

        if not href.startswith(
            ("http://", "https://")
        ):
            continue

        host = urlparse(
            href
        ).netloc.lower()

        if not host:
            continue

        if "vpngate.net" in host:
            continue

        # Ignore unrelated university pages such as
        # www.tsukuba.ac.jp/english/.
        if "tsukuba.ac.jp" in host:
            continue

        # VPN Gate mirror candidates are usually IP:PORT
        # or dedicated mirror hosts.
        if (
            re.match(
                r"^\d+\.\d+\.\d+\.\d+(?::\d+)?$",
                host
            )
            or
            "opengw.net" in host
        ):
            if href not in mirrors:
                mirrors.append(href)

    print(
        f"   🌐 Mirrors discovered: {len(mirrors)}"
    )

    for mirror in mirrors:
        print(
            f"      • {mirror}"
        )

    return mirrors


# ============================================================
# MERGE
# ============================================================

_FALSY_SCALARS = (None, "", False, 0, 0.0)


def _truthy(value: Any) -> bool:
    if isinstance(value, (list, tuple, dict)):
        return len(value) > 0

    return value not in _FALSY_SCALARS


def merge_value(
    old: Any,
    new: Any
) -> Any:
    if old in _FALSY_SCALARS and not isinstance(old, (list, dict)):
        return deepcopy(new)

    return old


def source_priority(source: str) -> int:
    """Lower number == higher authority. api > html > mirror_*.
    (Mission \u00a717/\u00a716 authority ordering.)"""
    return _SOURCE_PRIORITY.get(source_group(source), 20)


def record_conflict(
    target: Dict[str, Any],
    field: str,
    old_value: Any,
    new_value: Any,
    old_owner: str,
    new_owner: str,
) -> None:
    """Record a visible disagreement (mission \u00a738): conflicts are
    never silently overwritten; each attempt is auditable."""
    conflicts = target.setdefault("conflicts", [])

    for existing in conflicts:
        values = existing.get("values", {})

        if (
            existing.get("field") == field
            and values.get(old_owner) == old_value
            and values.get(new_owner) == new_value
        ):
            return

    conflicts.append({
        "field": field,
        "values": {
            old_owner: deepcopy(old_value),
            new_owner: deepcopy(new_value),
        },
    })


def recursive_merge(
    target: Dict[str, Any],
    incoming: Dict[str, Any],
    path: str = "",
    record: Optional[Dict[str, Any]] = None,
    root_target: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:

    if record is None:
        record = incoming

    if root_target is None:
        root_target = target

    for key, new_value in incoming.items():

        cur_path = f"{path}.{key}" if path else key

        if key in ("sourceCount", "_owners", "schemaVersion"):
            continue

        if key == "sources":
            for source_name in new_value or []:
                source_add(target, source_name)
            continue

        if key == "conflicts":
            for item in new_value or []:
                existing_conflict = next(
                    (
                        c
                        for c in root_target.setdefault("conflicts", [])
                        if c.get("field") == item.get("field")
                    ),
                    None,
                )

                if existing_conflict is None:
                    root_target["conflicts"].append(deepcopy(item))
                else:
                    existing_values = existing_conflict.setdefault(
                        "values", {}
                    )

                    for owner, owner_value in item.get(
                        "values", {}
                    ).items():
                        existing_values[owner] = deepcopy(owner_value)
            continue

        tgt_child = target.get(key)

        if isinstance(new_value, dict):
            if not isinstance(tgt_child, dict):
                tgt_child = {}
                target[key] = tgt_child

            recursive_merge(tgt_child, new_value, cur_path, record, root_target)
            continue

        if isinstance(new_value, list):
            if new_value:
                if not isinstance(tgt_child, list):
                    tgt_child = []
                    target[key] = tgt_child

                for item in new_value:

                    if item not in tgt_child:
                        tgt_child.append(deepcopy(item))
            continue

        # ---- scalar leaf with provenance-aware conflict handling.
        old_value = target.get(key)

        if isinstance(old_value, (dict, list)):
            old_truthy = len(old_value) > 0
        else:
            old_truthy = old_value not in _FALSY_SCALARS

        new_scalar_truthy = _truthy(new_value)

        if new_scalar_truthy and old_truthy:

            try:
                differs = bool(old_value != new_value)
            except Exception:
                differs = True

            if differs:
                fs = root_target.setdefault("fieldSources", {})
                old_sources = [
                    s for s in fs.get(cur_path, []) if s
                ]
                incoming_sources = [
                    s
                    for s in record.get("fieldSources", {}).get(
                        cur_path, []
                    )
                    if s
                ]

                old_owner = (
                    old_sources[-1] if old_sources else "previous"
                )
                new_owner = (
                    incoming_sources[-1]
                    if incoming_sources
                    else "incoming"
                )

                if source_priority(new_owner) < source_priority(
                    old_owner
                ):
                    target[key] = deepcopy(new_value)

                record_conflict(
                    root_target,
                    cur_path,
                    old_value,
                    new_value,
                    old_owner,
                    new_owner,
                )

        elif new_scalar_truthy and not old_truthy:
            # Missing on the current side: fill the gap. No conflict
            # is recorded -- nothing was overwritten (\u00a711).
            target[key] = deepcopy(new_value)

        # Falsy incoming values never erase authoritative data.

    # Merge fieldSources separately. Provenance lives at the top
    # level of a record only, so this runs once per merge root.
    if not path:
        for src_path, srcs in record.get("fieldSources", {}).items():

            existing = target[
                "fieldSources"
            ].setdefault(src_path, [])

            for source_name in srcs:

                if source_name not in existing:
                    existing.append(source_name)

        target["sourceCount"] = len(target.get("sources", []))

    return target


def merge_records(
    records: List[Dict[str, Any]]
) -> List[Dict[str, Any]]:

    database = {}

    duplicates = 0

    for record in records:

        ip = record[
            "identity"
        ].get(
            "ip",
            ""
        )

        host = normalize_host(
            record[
                "identity"
            ].get(
                "hostname",
                ""
            )
        )

        key = (
            ip
            if valid_ip(ip)
            else host
        )

        if not key:
            continue

        if key not in database:

            database[key] = deepcopy(
                record
            )

        else:

            duplicates += 1

            recursive_merge(
                database[key],
                record
            )

    result = list(
        database.values()
    )

    print(
        "\n============================================================"
    )

    print(
        f"📥 Input records      : {len(records)}"
    )

    print(
        f"🧹 Unique servers     : {len(result)}"
    )

    print(
        f"♻️ Duplicates merged  : {duplicates}"
    )

    print(
        "============================================================"
    )

    return result


# ============================================================
# ============================================================
# PROVENANCE CONFIDENCE (mission \u00a715 / \u00a716)
# ============================================================

_SOURCE_PRIORITY = {"api": 0, "html": 10}
_MIRROR_GROUP_RE = re.compile(r"^mirror_\d+$", re.IGNORECASE)
_CONFIDENCE_SINGLE_GROUP = {
    "api": 0.75,
    "html": 0.6,
    "mirror": 0.35,
}
_PROTOCOL_NAMES = ("softether", "openvpn", "l2tpIpsec", "sstp")


def source_group(source: str) -> str:
    """Collapse mirror_N aliases into a single independent group."""
    name = (source or "").strip().lower()

    if _MIRROR_GROUP_RE.match(name):
        return "mirror"

    return name


def independent_source_groups(server: Dict[str, Any]) -> List[str]:
    """Independent provenance groups contributing to this record."""
    groups = {
        source_group(s)
        for s in server.get("sources", [])
        if s
    }

    groups.discard("")

    return sorted(groups)


def confidence_for_groups(groups: List[str]) -> float:
    """Confidence policy (mission \u00a715):
      - no sources             -> 0.0
      - one independent group  -> per-group base score
      - two independent groups -> 0.8
      - three or more          -> 1.0
    Mirrors all collapse into ONE group and therefore never raise
    the confidence beyond what html alone already grants."""
    unique = {str(g).lower() for g in groups if str(g)}

    if not unique:
        return 0.0

    if len(unique) >= 3:
        return 1.0

    if len(unique) == 2:
        return 0.8

    return _CONFIDENCE_SINGLE_GROUP.get(next(iter(unique)), 0.3)


def protocol_source_groups(
    server: Dict[str, Any],
    protocol: str,
) -> List[str]:
    prefix = f"protocols.{protocol}."

    groups = set()

    for field_path, path_sources in server.get(
        "fieldSources", {}
    ).items():

        if field_path.startswith(prefix):
            groups.update(
                source_group(s) for s in path_sources if s
            )

    groups.discard("")

    return sorted(groups)


def compute_confidence(
    server: Dict[str, Any],
) -> Dict[str, float]:
    """Per-protocol confidence derived from INDEPENDENT provenance."""
    return {
        name: confidence_for_groups(
            protocol_source_groups(server, name)
        )
        for name in _PROTOCOL_NAMES
    }


# NORMALIZATION / VALIDATION
# ============================================================

def normalize_server(
    server: Dict[str, Any]
) -> bool:

    identity = server["identity"]
    perf = server["performance"]
    p = server["protocols"]

    identity["hostname"] = normalize_host(
        identity["hostname"]
    )
    identity["ip"] = normalize_ip(
        identity["ip"]
    )

    if not valid_ip(
        identity["ip"]
    ):
        return False

    # Normalize protocol booleans from their children.
    p["softether"]["supported"] = bool(
        p["softether"]["tcp"]["supported"]
        or p["softether"]["udp"]["supported"]
    )

    p["openvpn"]["supported"] = bool(
        p["openvpn"]["tcp"]["supported"]
        or p["openvpn"]["udp"]["supported"]
        or p["openvpn"]["configAvailable"]
    )

    # Validate ports.
    for protocol in (
        "softether",
        "openvpn"
    ):

        for transport in (
            "tcp",
            "udp"
        ):

            port = p[
                protocol
            ][
                transport
            ]["port"]

            if not valid_port(port):
                p[
                    protocol
                ][
                    transport
                ]["port"] = None

    if not valid_port(
        p["l2tpIpsec"]["port"]
    ):
        p[
            "l2tpIpsec"
        ][
            "port"
        ] = None

    if not valid_port(
        p["sstp"]["port"]
    ):
        p[
            "sstp"
        ][
            "port"
        ] = None

    # SSTP may still be supported when hostname is present.
    p["sstp"]["supported"] = bool(
        p["sstp"]["supported"]
        or p["sstp"]["hostname"]
    )

    # If L2TP supported and port absent, 1701 is the protocol port.
    if p["l2tpIpsec"]["supported"]:
        p["l2tpIpsec"]["port"] = 1701

    # Convert uptime to reasonable float.
    if perf["uptimeDays"] < 0:
        perf["uptimeDays"] = 0.0

    if perf["speedMbps"] < 0:
        perf["speedMbps"] = 0.0

    return True


def validate_servers(
    servers: List[Dict[str, Any]]
) -> List[Dict[str, Any]]:

    valid = []

    for server in servers:

        if normalize_server(
            server
        ):
            valid.append(
                server
            )

    print(
        f"✅ Valid servers: {len(valid)}"
    )

    print(
        f"❌ Invalid servers: "
        f"{len(servers) - len(valid)}"
    )

    return valid


# ============================================================
# QUALITY SCORING
# ============================================================

def performance_base_score(
    server: Dict[str, Any]
) -> float:

    perf = server["performance"]

    speed = float(
        perf.get(
            "speedMbps",
            0
        ) or 0
    )

    ping = float(
        perf.get(
            "pingMs",
            0
        ) or 0
    )

    sessions = int(
        perf.get(
            "sessions",
            0
        ) or 0
    )

    uptime = float(
        perf.get(
            "uptimeDays",
            0
        ) or 0
    )

    score = 0.0

    # Speed: 0..30
    if speed >= 1000:
        score += 30
    elif speed >= 500:
        score += 27
    elif speed >= 250:
        score += 24
    elif speed >= 100:
        score += 20
    elif speed >= 50:
        score += 15
    elif speed >= 10:
        score += 9
    elif speed > 0:
        score += 4

    # Ping: 0..25
    if 1 <= ping <= 20:
        score += 25
    elif ping <= 40:
        score += 22
    elif ping <= 70:
        score += 19
    elif ping <= 100:
        score += 15
    elif ping <= 150:
        score += 10
    elif ping <= 250:
        score += 5

    # Sessions: lower congestion can be better.
    if sessions <= 5:
        score += 15
    elif sessions <= 20:
        score += 13
    elif sessions <= 50:
        score += 10
    elif sessions <= 100:
        score += 7
    elif sessions <= 200:
        score += 4

    # Uptime: 0..15
    if uptime >= 90:
        score += 15
    elif uptime >= 30:
        score += 12
    elif uptime >= 7:
        score += 9
    elif uptime >= 1:
        score += 5

    # Multi-source confidence: 0..15
    source_count = len(
        server.get(
            "sources",
            []
        )
    )

    if source_count >= 6:
        score += 15
    elif source_count >= 4:
        score += 13
    elif source_count >= 2:
        score += 9
    elif source_count == 1:
        score += 5

    return min(
        100.0,
        score
    )


def score_server(
    server: Dict[str, Any]
) -> None:

    base = performance_base_score(
        server
    )

    p = server["protocols"]

    def protocol_score(
        supported: bool,
        port_score: float,
        config_score: float = 0
    ) -> int:

        if not supported:
            return 0

        return int(
            min(
                100,
                base * 0.75
                + port_score
                + config_score
            )
        )

    # Overall
    overall = base

    if p["softether"]["supported"]:
        overall += 5

    if p["openvpn"]["supported"]:
        overall += 4

    if p["sstp"]["supported"]:
        overall += 3

    if p["l2tpIpsec"]["supported"]:
        overall += 3

    server[
        "quality"
    ][
        "overall"
    ] = int(
        min(
            100,
            overall
        )
    )

    server[
        "quality"
    ][
        "softether"
    ] = protocol_score(
        p["softether"]["supported"],
        15 if p["softether"]["tcp"]["supported"] else 0,
        5 if p["softether"]["udp"]["supported"] else 0
    )

    server[
        "quality"
    ][
        "openvpn"
    ] = protocol_score(
        p["openvpn"]["supported"],
        (
            8
            if p["openvpn"]["tcp"]["supported"]
            else 0
        )
        + (
            6
            if p["openvpn"]["udp"]["supported"]
            else 0
        ),
        5 if p["openvpn"]["configAvailable"] else 0
    )

    server[
        "quality"
    ][
        "sstp"
    ] = protocol_score(
        p["sstp"]["supported"],
        10 if p["sstp"]["port"] else 0
    )

    server[
        "quality"
    ][
        "l2tp"
    ] = protocol_score(
        p["l2tpIpsec"]["supported"],
        10 if p["l2tpIpsec"]["port"] == 1701 else 0
    )


def score_all(
    servers: List[Dict[str, Any]]
) -> None:

    for server in servers:
        score_server(
            server
        )


def has_protocol(
    server: Dict[str, Any],
    name: str
) -> bool:

    return bool(
        server[
            "protocols"
        ][
            name
        ][
            "supported"
        ]
    )


def sort_servers(
    servers: List[Dict[str, Any]],
    protocol: Optional[str] = None
) -> List[Dict[str, Any]]:

    if protocol:
        quality_key = server_quality_key(
            protocol
        )
    else:
        quality_key = lambda s: s[
            "quality"
        ][
            "overall"
        ]

    return sorted(
        servers,
        key=lambda s: (
            quality_key(s),
            s["performance"]["speedMbps"],
            -(
                s["performance"]["pingMs"]
                if s["performance"]["pingMs"] > 0
                else 999999
            )
        ),
        reverse=True
    )


def server_quality_key(
    protocol: str
):
    return lambda s: s[
        "quality"
    ].get(
        protocol,
        0
    )


# ============================================================
# EXPORT
# ============================================================

def _export_server(server: Dict[str, Any]) -> Dict[str, Any]:
    """
    Flatten an internal record into the schema consumed by the
    Android app's ``VPNGateHtmlServer`` model.
    """
    identity = server["identity"]
    perf = server["performance"]
    protocols = server["protocols"]
    quality = server.get("quality", {})

    softether = protocols["softether"]
    openvpn = protocols["openvpn"]
    sstp = protocols["sstp"]

    def port_or_zero(value: Any) -> int:
        return to_int(value) if valid_port(value) else 0

    speed_mbps = to_float(perf.get("speedMbps"))

    return {
        "hostname": clean(identity.get("hostname")),
        "ip": clean(identity.get("ip")),
        "country": clean(identity.get("country")),
        "countryLong": clean(identity.get("countryLong")),
        "sessions": to_int(perf.get("sessions")),
        "uptime": int(round(to_float(perf.get("uptimeDays")))),
        "totalUsers": to_int(perf.get("totalUsers")),
        "score": to_int(perf.get("score")),
        "ping": int(round(to_float(perf.get("pingMs")))),
        "speed": int(speed_mbps * 1_000_000),
        "softEther": {
            "tcp": port_or_zero(softether["tcp"]["port"]),
            "udp": bool(softether["udp"]["supported"]),
        },
        "openVPN": {
            "tcp": port_or_zero(openvpn["tcp"]["port"]),
            "udp": port_or_zero(openvpn["udp"]["port"]),
            "configs": deepcopy(openvpn.get("configs", [])),
        },
        "l2tp": bool(protocols["l2tpIpsec"]["supported"]),
        "sstp": {
            "host": clean(sstp.get("hostname")),
            "port": to_int(sstp.get("port")) if valid_port(sstp.get("port")) else 0,
        },
        "sources": list(server.get("sources", [])),
        "sourceCount": int(len(server.get("sources", []))),
        "valid": bool(server.get("valid", True)),
        "qualityScore": to_int(quality.get("overall")),
        "confidence": compute_confidence(server),
        "conflicts": deepcopy(server.get("conflicts", [])),
    }


def save_json(
    filename: str,
    servers: List[Dict[str, Any]]
):

    exported = [_export_server(server) for server in servers]

    generated_at = time.strftime(
        "%Y-%m-%dT%H:%M:%SZ",
        time.gmtime()
    )

    output = {
        "schemaVersion": "4.0",
        "generatedAt": generated_at,
        "generatedAtUtc": generated_at,
        "source": "VPN Gate multi-source collector",
        "count": len(exported),
        "servers": exported
    }

    with open(
        filename,
        "w",
        encoding="utf-8"
    ) as f:

        json.dump(
            output,
            f,
            ensure_ascii=False,
            indent=2
        )

    print(
        f"\U0001f4be JSON saved: {filename} ({len(exported)} servers)"
    )


def save_report(
    filename: str,
    report: Dict[str, Any]
):
    """Write the collection report as-is via plain ``json.dump``.

    The report has no ``identity``/``protocols`` shape, so it must
    never go through ``_export_server`` / ``save_json``.
    """
    with open(
        filename,
        "w",
        encoding="utf-8"
    ) as f:
        json.dump(
            report,
            f,
            ensure_ascii=False,
            indent=2
        )

    print(
        f"\U0001f4be Report saved: {filename}"
    )


def save_csv(
    filename: str,
    servers: List[Dict[str, Any]]
):

    fields = [
        "hostname",
        "ip",
        "ispHostname",
        "country",
        "countryLong",

        "score",
        "pingMs",
        "speedMbps",
        "sessions",
        "uptimeDays",
        "totalUsers",
        "totalTrafficGB",

        "softetherSupported",
        "softetherTcpPort",
        "softetherUdpSupported",

        "openvpnSupported",
        "openvpnTcpPort",
        "openvpnUdpPort",
        "openvpnConfigAvailable",

        "l2tpSupported",
        "l2tpPort",

        "sstpSupported",
        "sstpHostname",
        "sstpPort",

        "qualityOverall",
        "qualitySoftether",
        "qualityOpenvpn",
        "qualitySstp",
        "qualityL2tp",

        "confidenceSoftether",
        "confidenceOpenvpn",
        "confidenceSstp",
        "confidenceL2tp",
        "conflictCount",

        "sourceCount",
        "sources"
    ]

    with open(
        filename,
        "w",
        newline="",
        encoding="utf-8-sig"
    ) as f:

        writer = csv.DictWriter(
            f,
            fieldnames=fields
        )

        writer.writeheader()

        for s in servers:

            i = s["identity"]
            m = s["performance"]
            p = s["protocols"]
            q = s["quality"]
            conf = compute_confidence(s)

            writer.writerow({
                "hostname": i["hostname"],
                "ip": i["ip"],
                "ispHostname": i["ispHostname"],
                "country": i["country"],
                "countryLong": i["countryLong"],

                "score": m["score"],
                "pingMs": m["pingMs"],
                "speedMbps": m["speedMbps"],
                "sessions": m["sessions"],
                "uptimeDays": m["uptimeDays"],
                "totalUsers": m["totalUsers"],
                "totalTrafficGB": m["totalTrafficGB"],

                "softetherSupported":
                    p["softether"]["supported"],
                "softetherTcpPort":
                    p["softether"]["tcp"]["port"],
                "softetherUdpSupported":
                    p["softether"]["udp"]["supported"],

                "openvpnSupported":
                    p["openvpn"]["supported"],
                "openvpnTcpPort":
                    p["openvpn"]["tcp"]["port"],
                "openvpnUdpPort":
                    p["openvpn"]["udp"]["port"],
                "openvpnConfigAvailable":
                    p["openvpn"]["configAvailable"],

                "l2tpSupported":
                    p["l2tpIpsec"]["supported"],
                "l2tpPort":
                    p["l2tpIpsec"]["port"],

                "sstpSupported":
                    p["sstp"]["supported"],
                "sstpHostname":
                    p["sstp"]["hostname"],
                "sstpPort":
                    p["sstp"]["port"],

                "qualityOverall":
                    q["overall"],
                "qualitySoftether":
                    q["softether"],
                "qualityOpenvpn":
                    q["openvpn"],
                "qualitySstp":
                    q["sstp"],
                "qualityL2tp":
                    q["l2tp"],

                "confidenceSoftether":
                    conf["softether"],
                "confidenceOpenvpn":
                    conf["openvpn"],
                "confidenceSstp":
                    conf["sstp"],
                "confidenceL2tp":
                    conf["l2tpIpsec"],
                "conflictCount":
                    len(s.get("conflicts", [])),

                "sourceCount":
                    s["sourceCount"],
                "sources":
                    "|".join(
                        s["sources"]
                    )
            })

    print(
        f"💾 CSV saved: {filename}"
    )


# ============================================================
# REPORT
# ============================================================

def protocol_counts(
    servers: List[Dict[str, Any]]
) -> Dict[str, int]:

    return {
        "softether":
            sum(
                has_protocol(s, "softether")
                for s in servers
            ),

        "openvpn":
            sum(
                has_protocol(s, "openvpn")
                for s in servers
            ),

        "sstp":
            sum(
                has_protocol(s, "sstp")
                for s in servers
            ),

        "l2tpIpsec":
            sum(
                has_protocol(s, "l2tpIpsec")
                for s in servers
            ),

        "softetherTcp":
            sum(
                s["protocols"]
                ["softether"]
                ["tcp"]
                ["supported"]
                for s in servers
            ),

        "softetherUdp":
            sum(
                s["protocols"]
                ["softether"]
                ["udp"]
                ["supported"]
                for s in servers
            ),

        "openvpnTcp":
            sum(
                s["protocols"]
                ["openvpn"]
                ["tcp"]
                ["supported"]
                for s in servers
            ),

        "openvpnUdp":
            sum(
                s["protocols"]
                ["openvpn"]
                ["udp"]
                ["supported"]
                for s in servers
            )
    }


def build_provenance_summary(
    servers: List[Dict[str, Any]],
) -> Dict[str, Any]:
    """Aggregate §14/§15/§38 visibility: conflicts, per-protocol
    confidence distribution and independent-group statistics."""
    field_conflicts: Dict[str, int] = {}
    total_conflicts = 0
    servers_with_conflicts = 0

    confidence_buckets: Dict[str, Dict[str, int]] = {
        name: {} for name in _PROTOCOL_NAMES
    }

    group_histogram: Dict[int, int] = {}
    group_membership: Dict[str, int] = {}

    for server in servers:
        conflicts = server.get("conflicts") or []

        if conflicts:
            servers_with_conflicts += 1

        total_conflicts += len(conflicts)

        for item in conflicts:
            field = item.get("field", "")
            field_conflicts[field] = (
                field_conflicts.get(field, 0) + 1
            )

        for name, value in compute_confidence(server).items():
            bucket = f"{value:.2f}"
            counts = confidence_buckets[name]
            counts[bucket] = counts.get(bucket, 0) + 1

        groups = independent_source_groups(server)

        group_histogram[len(groups)] = (
            group_histogram.get(len(groups), 0) + 1
        )

        for group in groups:
            group_membership[group] = (
                group_membership.get(group, 0) + 1
            )

    top_fields = sorted(
        field_conflicts.items(),
        key=lambda x: x[1],
        reverse=True,
    )[:10]

    return {
        "conflictCount": total_conflicts,
        "serversWithConflicts": servers_with_conflicts,
        "topConflictingFields": [
            {"field": field, "count": count}
            for field, count in top_fields
        ],
        "confidenceDistribution": {
            name: dict(sorted(counts.items()))
            for name, counts in confidence_buckets.items()
        },
        "independentGroups": {
            "distribution": {
                str(size): count
                for size, count in sorted(group_histogram.items())
            },
            "serversPerGroup": dict(
                sorted(group_membership.items())
            ),
        },
    }


def build_report(
    servers: List[Dict[str, Any]],
    mirrors: List[str],
    raw_count: int
) -> Dict[str, Any]:

    countries: Dict[str, int] = {}

    for s in servers:
        c = (
            s["identity"]["countryLong"]
            or s["identity"]["country"]
            or "Unknown"
        )

        countries[c] = countries.get(
            c,
            0
        ) + 1

    report = {
        "schemaVersion": "4.0",
        "generatedAtUtc": time.strftime(
            "%Y-%m-%dT%H:%M:%SZ",
            time.gmtime()
        ),
        "rawRecords": raw_count,
        "uniqueServers": len(servers),
        "mirrorsDiscovered": len(mirrors),
        "protocolCounts": protocol_counts(
            servers
        ),
        "provenance": build_provenance_summary(
            servers
        ),
        "countries": dict(
            sorted(
                countries.items(),
                key=lambda x: x[1],
                reverse=True
            )[:30]
        )
    }

    return report


# ============================================================
# DISPLAY
# ============================================================

def print_top(
    servers: List[Dict[str, Any]],
    protocol: Optional[str],
    title: str,
    count: int = 20
):

    print()
    print("=" * 72)
    print(title)
    print("=" * 72)

    ranked = sort_servers(
        servers,
        protocol
    )

    for idx, s in enumerate(
        ranked[:count],
        1
    ):

        i = s["identity"]
        m = s["performance"]
        p = s["protocols"]
        q = s["quality"]

        print()
        print(
            f"{idx:02d}. "
            f"{i['hostname']} ({i['ip']})"
        )

        print(
            f"    🌍 "
            f"{i['countryLong'] or i['country'] or 'Unknown'}"
        )

        print(
            f"    ⚡ "
            f"{m['speedMbps']:.2f} Mbps"
            f" | 📶 {m['pingMs']:.0f} ms"
            f" | 👥 {m['sessions']}"
        )

        print(
            f"    🔐 SoftEther: "
            f"TCP={p['softether']['tcp']['port']}"
            f" UDP={p['softether']['udp']['supported']}"
        )

        print(
            f"    🔵 OpenVPN: "
            f"TCP={p['openvpn']['tcp']['port']}"
            f" UDP={p['openvpn']['udp']['port']}"
            f" Config={p['openvpn']['configAvailable']}"
        )

        print(
            f"    🟠 L2TP/IPsec: "
            f"{p['l2tpIpsec']['supported']}"
            f" port={p['l2tpIpsec']['port']}"
        )

        print(
            f"    🟣 SSTP: "
            f"{p['sstp']['supported']}"
            f" {p['sstp']['hostname']}"
            f":{p['sstp']['port']}"
        )

        print(
            f"    ⭐ Quality: "
            f"overall={q['overall']}"
        )

        print(
            f"    🔗 Sources: "
            f"{', '.join(s['sources'])}"
        )


# ============================================================
# MAIN
# ============================================================

def main():

    print()
    print("=" * 72)
    print(
        "       VPN GATE INTELLIGENT MULTI-PROTOCOL COLLECTOR V4"
    )
    print("=" * 72)

    all_records: List[Dict[str, Any]] = []

    # --------------------------------------------------------
    # MAIN HTML
    # --------------------------------------------------------

    print()
    print("[1/3] VPN Gate MAIN HTML")

    html = fetch(
        MAIN_URL
    )

    if html:

        html_servers = parse_html(
            html,
            "html"
        )

        all_records.extend(
            html_servers
        )

    # --------------------------------------------------------
    # API
    # --------------------------------------------------------

    print()
    print("[2/3] VPN Gate API")

    api = fetch(
        API_URL
    )

    if api:

        api_servers = parse_api(
            api,
            "api"
        )

        all_records.extend(
            api_servers
        )

    # --------------------------------------------------------
    # MIRRORS
    # --------------------------------------------------------

    print()
    print("[3/3] VPN Gate MIRRORS")

    mirrors = discover_mirrors()

    mirrors = mirrors[
        :MAX_MIRRORS
    ]

    for index, mirror in enumerate(
        mirrors,
        1
    ):

        print()
        print(
            f"   Mirror {index}/{len(mirrors)}"
        )

        mirror_html = fetch(
            mirror
        )

        if not mirror_html:
            continue

        mirror_servers = parse_html(
            mirror_html,
            f"mirror_{index}"
        )

        all_records.extend(
            mirror_servers
        )

    # --------------------------------------------------------
    # MERGE
    # --------------------------------------------------------

    servers = merge_records(
        all_records
    )

    # --------------------------------------------------------
    # VALIDATE
    # --------------------------------------------------------

    servers = validate_servers(
        servers
    )

    # --------------------------------------------------------
    # SCORE
    # --------------------------------------------------------

    print(
        "\n⭐ QUALITY SCORING"
    )

    score_all(
        servers
    )

    # --------------------------------------------------------
    # FILTERS
    # --------------------------------------------------------

    softether = [
        s for s in servers
        if has_protocol(
            s,
            "softether"
        )
    ]

    openvpn = [
        s for s in servers
        if has_protocol(
            s,
            "openvpn"
        )
    ]

    sstp = [
        s for s in servers
        if has_protocol(
            s,
            "sstp"
        )
    ]

    l2tp = [
        s for s in servers
        if has_protocol(
            s,
            "l2tpIpsec"
        )
    ]

    multiprotocol = [
        s for s in servers
        if sum(
            has_protocol(s, name)
            for name in (
                "softether",
                "openvpn",
                "sstp",
                "l2tpIpsec"
            )
        ) >= 2
    ]

    # --------------------------------------------------------
    # Rankings
    # --------------------------------------------------------

    ranked = sort_servers(
        servers
    )

    ranked_softether = sort_servers(
        softether,
        "softether"
    )

    ranked_openvpn = sort_servers(
        openvpn,
        "openvpn"
    )

    ranked_sstp = sort_servers(
        sstp,
        "sstp"
    )

    ranked_l2tp = sort_servers(
        l2tp,
        "l2tp"
    )

    # --------------------------------------------------------
    # OUTPUT
    # --------------------------------------------------------

    save_json(
        OUT_ALL,
        servers
    )

    save_json(
        OUT_SOFTETHER,
        softether
    )

    save_json(
        OUT_OPENVPN,
        openvpn
    )

    save_json(
        OUT_SSTP,
        sstp
    )

    save_json(
        OUT_L2TP,
        l2tp
    )

    save_json(
        OUT_MULTI,
        multiprotocol
    )

    save_json(
        OUT_RANKED,
        ranked
    )

    save_json(
        OUT_SOFTETHER_RANKED,
        ranked_softether
    )

    save_json(
        OUT_OPENVPN_RANKED,
        ranked_openvpn
    )

    save_json(
        OUT_SSTP_RANKED,
        ranked_sstp
    )

    save_json(
        OUT_L2TP_RANKED,
        ranked_l2tp
    )

    save_csv(
        OUT_CSV,
        ranked
    )

    report = build_report(
        servers,
        mirrors,
        len(all_records)
    )

    # The report is NOT a server record: it must never be routed
    # through save_json/_export_server (that crashed with KeyError
    # on "identity" and the report was never written).
    save_report(
        OUT_REPORT,
        report
    )

    # --------------------------------------------------------
    # SUMMARY
    # --------------------------------------------------------

    print()
    print("=" * 72)
    print("📊 FINAL SUMMARY")
    print("=" * 72)

    print(
        f"🌍 Unique servers       : {len(servers)}"
    )

    print(
        f"🔐 SoftEther            : {len(softether)}"
    )

    print(
        f"🔵 OpenVPN              : {len(openvpn)}"
    )

    print(
        f"🟣 SSTP                 : {len(sstp)}"
    )

    print(
        f"🟠 L2TP/IPsec           : {len(l2tp)}"
    )

    print(
        f"🔀 Multi-protocol       : {len(multiprotocol)}"
    )

    print()
    print(
        "Protocol details:"
    )

    for name, value in report[
        "protocolCounts"
    ].items():

        print(
            f"   {name:<20}: {value}"
        )

    # --------------------------------------------------------
    # TOP LISTS
    # --------------------------------------------------------

    print_top(
        ranked_softether,
        "softether",
        "🏆 TOP SOFTETHER SERVERS"
    )

    print_top(
        ranked_openvpn,
        "openvpn",
        "🏆 TOP OPENVPN SERVERS"
    )

    print_top(
        ranked_sstp,
        "sstp",
        "🏆 TOP SSTP SERVERS"
    )

    print_top(
        ranked_l2tp,
        "l2tp",
        "🏆 TOP L2TP/IPsec SERVERS"
    )

    print()
    print("=" * 72)
    print("✅ COLLECTION COMPLETED")
    print("=" * 72)


def run_diagnose(target: Optional[str]) -> int:
    """CLI: --diagnose [url-or-file]. Compares cell facts vs parsed
    fields for every row and writes ``diagnostics_report.json``."""
    html: Optional[str] = None

    if target and os.path.isfile(target):
        with open(target, encoding="utf-8") as f:
            html = f.read()
    else:
        html = fetch(target or MAIN_URL)

    if not html:
        print("\u274c Diagnostics: no HTML available")
        return 2

    result = diagnose_html(html)

    result["target"] = target or MAIN_URL
    result["generatedAtUtc"] = time.strftime(
        "%Y-%m-%dT%H:%M:%SZ",
        time.gmtime(),
    )

    save_report(OUT_DIAGNOSTICS, result)

    print(
        f"\U0001f4ca Mismatch counts: {result['mismatchCounts'] or '{}'}"
    )

    return 1 if result["mismatchTotal"] else 0


# ============================================================
# DEBUG DUMP (§33 — same layout as the in-app debug panel)
# ============================================================

def format_server_debug(server: Dict[str, Any]) -> str:
    """Per-server provenance dump: identity, per-protocol facts,
    quality/confidence, per-field source owners and conflicts."""
    identity = server["identity"]
    perf = server["performance"]
    protocols = server["protocols"]

    lines: List[str] = []
    lines.append("=" * 72)
    lines.append(
        f"SERVER DUMP: {identity['hostname']} ({identity['ip']})"
    )
    lines.append("=" * 72)

    lines.append("[identity]")
    lines.append(
        f"  hostname={identity['hostname']} ip={identity['ip']} "
        f"isp={identity['ispHostname']} country={identity['country']} "
        f"countryLong={identity['countryLong']}"
    )

    groups = independent_source_groups(server)
    lines.append("[sources]")
    lines.append(
        f"  tags: {', '.join(server.get('sources', [])) or '(none)'}"
    )
    lines.append(
        f"  independent groups: {', '.join(groups) or '(none)'}"
    )

    quality = server.get("quality", {})
    lines.append("[quality]")
    lines.append(
        f"  overall={quality.get('overall')} "
        f"softether={quality.get('softether')} "
        f"openvpn={quality.get('openvpn')} "
        f"sstp={quality.get('sstp')} l2tp={quality.get('l2tp')}"
    )

    confidence = compute_confidence(server)
    lines.append("[confidence]")
    lines.append(
        "  " + " ".join(
            f"{name}={value:.2f}"
            for name, value in confidence.items()
        )
    )

    for name in _PROTOCOL_NAMES:
        proto = protocols[name]
        if name in ("softether", "openvpn"):
            tcp = proto["tcp"]
            udp = proto["udp"]
            extra = ""
            if name == "openvpn":
                configs = [
                    f"{c['host']}:{c['port']}({c['proto'] or '-'})"
                    for c in proto.get("configs", [])
                ]
                extra = (
                    f" configAvailable={proto['configAvailable']} "
                    f"configs=[{', '.join(configs)}]"
                )
            lines.append(
                f"[{name}] supported={proto['supported']} "
                f"tcp(supported={tcp['supported']} port={tcp['port']}) "
                f"udp(supported={udp['supported']} port={udp['port']})"
                f"{extra}"
            )
        elif name == "l2tpIpsec":
            lines.append(
                f"[l2tpIpsec] supported={proto['supported']} "
                f"port={proto['port']}"
            )
        else:
            lines.append(
                f"[{name}] supported={proto['supported']} "
                f"hostname={proto['hostname']} port={proto['port']}"
            )

    lines.append("[performance]")
    lines.append(
        f"  score={perf['score']} pingMs={perf['pingMs']} "
        f"speedMbps={perf['speedMbps']} sessions={perf['sessions']} "
        f"uptimeDays={perf['uptimeDays']} totalUsers={perf['totalUsers']} "
        f"totalTrafficGB={perf['totalTrafficGB']}"
    )
    lines.append(f"[logging] policy={server['logging']['policy']}")
    lines.append(
        f"[operator] name={server['operator']['name']} "
        f"message={server['operator']['message']}"
    )

    lines.append("[field sources]")
    field_sources = server.get("fieldSources", {})
    if field_sources:
        for path in sorted(field_sources):
            lines.append(
                f"  {path:<44} <- {', '.join(field_sources[path])}"
            )
    else:
        lines.append("  (none)")

    lines.append("[conflicts]")
    conflicts = server.get("conflicts", [])
    if conflicts:
        for conflict in conflicts:
            values = conflict.get("values", {})
            rendered = " vs ".join(
                f"{owner}={value}" for owner, value in values.items()
            )
            lines.append(f"  {conflict['field']}: {rendered}")
    else:
        lines.append("  (none)")

    return "\n".join(lines)


def run_debug_ip(target: str) -> int:
    """CLI: --debug-ip <ip-or-hostname>. Live-collects (HTML + API +
    mirrors), merges, then dumps the matching server's provenance."""
    if not target:
        print("\u274c --debug-ip requires an IP or hostname argument")
        return 2

    needle = normalize_host(target)

    all_records: List[Dict[str, Any]] = []

    html = fetch(MAIN_URL)
    if html:
        all_records.extend(parse_html(html, "html"))

    api = fetch(API_URL)
    if api:
        all_records.extend(parse_api(api, "api"))

    mirrors = discover_mirrors()[:MAX_MIRRORS]
    for index, mirror in enumerate(mirrors, 1):
        mirror_html = fetch(mirror)
        if mirror_html:
            all_records.extend(
                parse_html(mirror_html, f"mirror_{index}")
            )

    servers = validate_servers(merge_records(all_records))
    score_all(servers)

    matches = [
        s for s in servers
        if s["identity"]["ip"] == target
        or normalize_host(s["identity"]["hostname"]) == needle
    ]

    if not matches:
        print(f"\u274c No merged server matches '{target}'")
        return 1

    for server in matches:
        print()
        print(format_server_debug(server))

    return 0


if __name__ == "__main__":
    if "--debug-ip" in sys.argv:
        _idx = sys.argv.index("--debug-ip")
        _target = (
            sys.argv[_idx + 1]
            if _idx + 1 < len(sys.argv)
            else None
        )
        sys.exit(run_debug_ip(_target))

    if "--diagnose" in sys.argv:
        _idx = sys.argv.index("--diagnose")
        _target = (
            sys.argv[_idx + 1]
            if _idx + 1 < len(sys.argv)
            else None
        )
        sys.exit(run_diagnose(_target))

    main()
