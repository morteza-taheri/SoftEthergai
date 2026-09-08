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
import re
import socket
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
    if source and source not in server["sources"]:
        server["sources"].append(source)
        server["sources"].sort()
        server["sourceCount"] = len(server["sources"])


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
                    "port": None,
                    # SoftEther's UDP acceleration channel port is
                    # negotiated dynamically per-session over the
                    # existing TCP/HTTPS control channel -- VPN Gate
                    # never advertises a fixed number for it. True
                    # here means "port is dynamic, not a bug".
                    "dynamicPort": False
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

            for remote in config_info["remotes"]:
                server[
                    "protocols"
                ][
                    "openvpn"
                ][
                    "configs"
                ].append(remote)

                proto = remote.get("proto", "").lower()
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


def parse_html_protocols(
    row_text: str,
    server: Dict[str, Any],
    source: str
) -> None:
    """
    Parse protocol sections from the actual row text.

    The current VPN Gate row is ordered approximately as:
        SSL-VPN
        TCP: xxxx
        UDP: Supported
        L2TP/IPsec
        OpenVPN
        TCP: xxxx
        UDP: xxxx
        MS-SSTP
        SSTP Hostname: host[:port]
    """

    text = clean(row_text)

    # --------------------------------------------------------
    # SoftEther / SSL-VPN
    # --------------------------------------------------------
    #
    # FIX (reported by user): the previous regex
    #     r"SSL-VPN.*?(?:TCP:\s*(\d+))?.{0,120}?(?:UDP:\s*Supported)?"
    # has every component *after* "SSL-VPN" marked optional with no
    # required anchor forcing the lazy ".*?" to actually expand. In
    # Python's backtracking engine this means the whole pattern is
    # already satisfied the instant "SSL-VPN" is found (all following
    # groups happily match zero-width), so the engine never scans
    # forward far enough to reach the real "TCP: xxx" / "UDP:
    # Supported" text -- which normally sits after a "Connect guide"
    # link inside the same table cell. Net effect: the TCP port was
    # almost never captured, and capture was essentially random
    # depending on incidental row layout.
    #
    # Fix: isolate the SSL-VPN cell's own text first (bounded by the
    # next real section label, which IS a required/forced lookahead),
    # then search for TCP/UDP only inside that isolated substring.
    # This mirrors how the OpenVPN section below already does it
    # correctly (its lookahead is not optional, so it truly forces
    # the lazy group to expand).

    softether_section_match = re.search(
        r"SSL-VPN(.*?)(?=L2TP/IPsec|OpenVPN|MS-SSTP|$)",
        text,
        re.IGNORECASE
    )

    if softether_section_match:
        softether_section = softether_section_match.group(1)

        # The "SSL-VPN" label itself appears in a server's own row
        # only when that server actually offers it, so its presence
        # is the supported signal (same convention used for L2TP
        # below).
        mark_supported(
            server,
            "softether",
            source
        )

        tcp_match = re.search(
            r"TCP:\s*(\d+)",
            softether_section,
            re.IGNORECASE
        )

        if tcp_match:
            tcp = to_int(
                tcp_match.group(1)
            )

            if valid_port(tcp):
                set_field(
                    server,
                    "protocols.softether.tcp.supported",
                    True,
                    source
                )
                set_field(
                    server,
                    "protocols.softether.tcp.port",
                    tcp,
                    source
                )

        if re.search(
            r"UDP:\s*Supported",
            softether_section,
            re.IGNORECASE
        ):
            set_field(
                server,
                "protocols.softether.udp.supported",
                True,
                source
            )

            # IMPORTANT: unlike OpenVPN, SoftEther's UDP acceleration
            # channel has no fixed/advertised port in VPN Gate's
            # table (and none in the protocol itself) -- the actual
            # UDP port is negotiated dynamically per session over the
            # already-established TCP/HTTPS control channel during
            # connection setup. We deliberately do NOT invent a
            # number here; we flag it as dynamic instead so consumers
            # of the JSON know why "port" stays null.
            set_field(
                server,
                "protocols.softether.udp.dynamicPort",
                True,
                source
            )

    # --------------------------------------------------------
    # L2TP/IPsec
    # --------------------------------------------------------

    if re.search(
        r"L2TP/IPsec",
        text,
        re.IGNORECASE
    ):
        mark_supported(
            server,
            "l2tpIpsec",
            source
        )

        set_field(
            server,
            "protocols.l2tpIpsec.port",
            1701,
            source
        )

    # --------------------------------------------------------
    # OpenVPN section
    # --------------------------------------------------------

    ovpn_match = re.search(
        r"OpenVPN.*?"
        r"(.*?)(?=MS-SSTP|SSTP Hostname|$)",
        text,
        re.IGNORECASE
    )

    ovpn_section = (
        ovpn_match.group(1)
        if ovpn_match
        else ""
    )

    tcp_values = [
        to_int(v)
        for v in re.findall(
            r"TCP:\s*(\d+)",
            ovpn_section,
            re.IGNORECASE
        )
    ]

    udp_values = [
        to_int(v)
        for v in re.findall(
            r"UDP:\s*(\d+)",
            ovpn_section,
            re.IGNORECASE
        )
    ]

    if tcp_values:
        port = next(
            (p for p in tcp_values if valid_port(p)),
            None
        )

        if port:
            mark_supported(
                server,
                "openvpn",
                source
            )
            set_field(
                server,
                "protocols.openvpn.tcp.supported",
                True,
                source
            )
            set_field(
                server,
                "protocols.openvpn.tcp.port",
                port,
                source
            )

    if udp_values:
        port = next(
            (p for p in udp_values if valid_port(p)),
            None
        )

        if port:
            mark_supported(
                server,
                "openvpn",
                source
            )
            set_field(
                server,
                "protocols.openvpn.udp.supported",
                True,
                source
            )
            set_field(
                server,
                "protocols.openvpn.udp.port",
                port,
                source
            )

    # Presence of the OpenVPN config link is also useful.
    if re.search(
        r"OpenVPN\s*Config\s*file",
        text,
        re.IGNORECASE
    ):
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

    # --------------------------------------------------------
    # SSTP
    # --------------------------------------------------------

    supported, host, port = parse_sstp(
        text
    )

    if supported:
        mark_supported(
            server,
            "sstp",
            source
        )

        set_field(
            server,
            "protocols.sstp.hostname",
            host,
            source
        )

        if valid_port(port):
            set_field(
                server,
                "protocols.sstp.port",
                port,
                source
            )


# ============================================================
# HTML SERVER ROW
# ============================================================

def extract_country_from_row(
    row_text: str,
    hostname: str,
    isp_hostname: str
) -> str:
    """
    Country is the text appearing before the DDNS hostname.
    This avoids depending on flag-image alt text.
    """
    text = clean(row_text)

    if hostname:
        idx = text.lower().find(
            hostname.lower()
        )

        if idx > 0:
            prefix = text[:idx].strip()

            # Usually country is the last meaningful word(s)
            # before hostname and can be one or more words.
            # Remove common image/link artifacts.
            prefix = re.sub(
                r"\b(?:country|physical location)\b",
                "",
                prefix,
                flags=re.IGNORECASE
            ).strip()

            if prefix:
                # Keep a compact country candidate.
                # Country names may contain spaces.
                parts = prefix.split()
                if len(parts) <= 5:
                    return " ".join(parts[-5:])

    return ""


def parse_html(
    html: str,
    source: str
) -> List[Dict[str, Any]]:

    print(
        f"   🔍 Parsing HTML: {len(html):,} chars"
    )

    soup = BeautifulSoup(
        html,
        "html.parser"
    )

    rows = soup.find_all("tr")

    print(
        f"   🔎 HTML rows: {len(rows)}"
    )

    servers = []
    seen = set()

    for row in rows:

        text = clean(
            row.get_text(
                " ",
                strip=True
            )
        )

        if not text:
            continue

        # Need a VPN Gate hostname or at least an IP.
        host_match = re.search(
            r"\b([A-Za-z0-9._-]+\.opengw\.net)\b",
            text,
            re.IGNORECASE
        )

        if not host_match:
            continue

        hostname = normalize_host(
            host_match.group(1)
        )

        ip_matches = re.findall(
            r"\b(?:\d{1,3}\.){3}\d{1,3}\b",
            text
        )

        ips = [
            normalize_ip(x)
            for x in ip_matches
            if valid_ip(x)
        ]

        if not ips:
            continue

        ip = ips[0]

        key = ip

        if key in seen:
            continue

        seen.add(key)

        server = new_server()

        set_field(
            server,
            "identity.hostname",
            hostname,
            source
        )

        set_field(
            server,
            "identity.ip",
            ip,
            source
        )

        # ----------------------------------------------------
        # ISP hostname: usually immediately after IP
        # ----------------------------------------------------

        ip_position = text.find(ip)

        after_ip = (
            text[ip_position + len(ip):]
            if ip_position >= 0
            else ""
        )

        isp_match = re.search(
            r"\(([A-Za-z0-9._-]+\.[A-Za-z]{2,})\)",
            after_ip
        )

        if isp_match:
            set_field(
                server,
                "identity.ispHostname",
                isp_match.group(1),
                source
            )

        # ----------------------------------------------------
        # Country
        # ----------------------------------------------------

        country = extract_country_from_row(
            text,
            hostname,
            server[
                "identity"
            ][
                "ispHostname"
            ]
        )

        if country:
            set_field(
                server,
                "identity.countryLong",
                country,
                source
            )

        # ----------------------------------------------------
        # Sessions
        # ----------------------------------------------------

        sessions = re.search(
            r"(\d[\d,]*)\s+sessions?",
            text,
            re.IGNORECASE
        )

        if sessions:
            set_field(
                server,
                "performance.sessions",
                to_int(sessions.group(1)),
                source
            )

        # ----------------------------------------------------
        # Uptime
        # ----------------------------------------------------

        uptime = re.search(
            r"(\d+(?:\.\d+)?)\s+days?",
            text,
            re.IGNORECASE
        )

        if uptime:
            set_field(
                server,
                "performance.uptimeDays",
                to_float(uptime.group(1)),
                source
            )

        # ----------------------------------------------------
        # Total users
        # ----------------------------------------------------

        users = re.search(
            r"Total\s+([\d,]+)\s+users?",
            text,
            re.IGNORECASE
        )

        if users:
            set_field(
                server,
                "performance.totalUsers",
                to_int(users.group(1)),
                source
            )

        # ----------------------------------------------------
        # Speed / Ping
        # ----------------------------------------------------

        speed = re.search(
            r"([\d,.]+)\s*Mbps",
            text,
            re.IGNORECASE
        )

        if speed:
            set_field(
                server,
                "performance.speedMbps",
                to_float(speed.group(1)),
                source
            )

        ping = re.search(
            r"Ping:\s*([\d,.]+)\s*ms",
            text,
            re.IGNORECASE
        )

        if ping:
            set_field(
                server,
                "performance.pingMs",
                to_float(ping.group(1)),
                source
            )

        # ----------------------------------------------------
        # Logging policy
        # ----------------------------------------------------

        log_match = re.search(
            r"Logging policy:\s*(.+?)"
            r"(?=SSL-VPN|L2TP/IPsec|OpenVPN|MS-SSTP|$)",
            text,
            re.IGNORECASE
        )

        if log_match:
            set_field(
                server,
                "logging.policy",
                clean(log_match.group(1)),
                source
            )

        # ----------------------------------------------------
        # Protocols
        # ----------------------------------------------------

        parse_html_protocols(
            text,
            server,
            source
        )

        # ----------------------------------------------------
        # Operator
        # ----------------------------------------------------

        op_match = re.search(
            r"By\s+(.+?)"
            r"(?=\s+\d[\d,]{3,}\s*$)",
            text,
            re.IGNORECASE
        )

        if op_match:
            operator = clean(
                op_match.group(1)
            )

            # Strip common noise after operator.
            operator = re.sub(
                r"\s+E-mail:.*$",
                "",
                operator,
                flags=re.IGNORECASE
            )

            operator = clean(
                operator
            )

            if operator:
                set_field(
                    server,
                    "operator.name",
                    operator,
                    source
                )

        # ----------------------------------------------------
        # Score: actual trailing large numeric value
        # ----------------------------------------------------

        # The HTML row ends with the quality score.
        trailing_numbers = re.findall(
            r"(\d[\d,]{4,})\s*$",
            text
        )

        if trailing_numbers:
            set_field(
                server,
                "performance.score",
                to_int(
                    trailing_numbers[-1]
                ),
                source
            )

        source_add(
            server,
            source
        )

        servers.append(server)

    print(
        f"   📦 HTML servers: {len(servers)}"
    )

    return servers


# ============================================================
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

def merge_value(
    old: Any,
    new: Any
) -> Any:
    if old in (None, "", False, 0, 0.0, []):
        return deepcopy(new)
    return old


def recursive_merge(
    target: Dict[str, Any],
    incoming: Dict[str, Any]
) -> Dict[str, Any]:

    for key, new_value in incoming.items():

        if key == "fieldSources":
            continue

        if key == "sources":
            for source in new_value or []:
                source_add(
                    target,
                    source
                )
            continue

        if isinstance(
            new_value,
            dict
        ):

            if key not in target:
                target[key] = {}

            recursive_merge(
                target[key],
                new_value
            )

        elif isinstance(
            new_value,
            list
        ):

            if new_value:

                if not isinstance(
                    target.get(key),
                    list
                ):
                    target[key] = []

                for item in new_value:

                    if item not in target[key]:
                        target[key].append(
                            deepcopy(item)
                        )

        else:

            target[key] = merge_value(
                target.get(key),
                new_value
            )

    # Merge fieldSources separately.
    for path, sources in incoming.get(
        "fieldSources",
        {}
    ).items():

        existing = target[
            "fieldSources"
        ].setdefault(
            path,
            []
        )

        for source in sources:

            if source not in existing:
                existing.append(source)

    target[
        "sourceCount"
    ] = len(
        target.get(
            "sources",
            []
        )
    )

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

def save_json(
    filename: str,
    servers: List[Dict[str, Any]]
):

    output = {
        "schemaVersion": "4.0",
        "generatedAtUtc": time.strftime(
            "%Y-%m-%dT%H:%M:%SZ",
            time.gmtime()
        ),
        "count": len(servers),
        "servers": servers
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
        f"💾 JSON saved: {filename}"
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
        "softetherUdpDynamicPort",

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
                "softetherUdpDynamicPort":
                    p["softether"]["udp"].get(
                        "dynamicPort", False
                    ),

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

        udp_note = (
            "dynamic port, negotiated at connect time"
            if p["softether"]["udp"]["supported"]
            else "not supported"
        )

        print(
            f"    🔐 SoftEther: "
            f"TCP={p['softether']['tcp']['port']}"
            f" UDP={udp_note}"
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

    save_json(
        OUT_REPORT,
        [report]
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


if __name__ == "__main__":
    main()