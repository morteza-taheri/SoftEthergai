#!/usr/bin/env python3
# -*- coding: utf-8 -*-

# OPERATOR-ONLY TOOL - runs on the developer's computer, NEVER inside the app.
# It produces emergency-sources/servers.csv and
# app/src/main/res/values/emergency_defaults.xml and commits/pushes them to git.
# The XML is compiled into the APK at build time; the app never runs this script.
"""
SoftEthergai emergency-source publisher
=======================================

Builds, validates and publishes `emergency-sources/servers.csv` (the app's
emergency fallback server list) and registers its public raw URL in
`app/src/main/res/values/emergency_defaults.xml`.

Schema: SERVER_SOURCE_ARCHITECTURE.md section 10 (29 columns, schema_version
"1.0"). The output is round-tripped through a Python port of the checks done by
`EmergencyCsvParser.kt` before anything is committed.

Pipeline
  1. fetch the previously published CSV (URL or local file)
  2. validate + TCP health-check its rows
  3. fetch VPN Gate API CSV + HTML (HTML protocol columns via the existing
     `vpngate_collector.parse_html`, which must sit next to this script)
  4. merge by IPv4 (healthy previous rows are kept even when the fresh
     response omits them), health-check new/changed endpoints
  5. drop defective rows, prune to --max-servers
  6. write CSV (UTF-8, no BOM, LF) and self-check it
  7. write the Android default-URL resource
  8. git commit + push, then verify the raw URL (unless --dry-run)

Nothing is fabricated: a port that the source did not publish is written as 0,
an unknown numeric metric as an empty field.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import concurrent.futures
import contextlib
import csv
import hashlib
import io
import ipaddress
import json
import logging
import os
import re
import socket
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

try:  # Python 3.11+
    import tomllib  # type: ignore
except ImportError:  # pragma: no cover
    tomllib = None  # type: ignore

import requests

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

SCHEMA_VERSION = "1.0"
API_URL = "https://www.vpngate.net/api/iphone/"
HTML_URL = "https://www.vpngate.net/en/"
DEFAULT_RAW_URL = (
    "https://raw.githubusercontent.com/morteza-taheri/SoftEthergai/"
    "main/emergency-sources/servers.csv"
)

# Section 10.2 order. The Kotlin parser is header driven, but we keep the
# documented order so the file is also readable by a positional consumer.
COLUMNS: List[str] = [
    "HostName", "IP", "Score", "Ping", "Speed", "CountryLong", "CountryShort",
    "NumVpnSessions", "Uptime", "TotalUsers", "TotalTraffic", "LogType",
    "Operator", "Message", "OpenVPN_ConfigData_Base64", "schema_version",
    "SE_TCP_Port", "SE_UDP_Supported", "SE_UDP_Port", "OpenVPN_TCP_Port",
    "OpenVPN_UDP_Port", "L2TP_Supported", "SSTP_Supported", "SSTP_Hostname",
    "SSTP_Port", "Has_IPv6", "FirstSeenAt", "LastSeenAt", "SourceTag",
]
REQUIRED_COLUMNS = ["HostName", "IP", "schema_version", "SourceTag"]
SOURCE_TAGS = ("api_iphone", "html_parse", "merged")

INT_COLUMNS = ("Score", "Ping", "Speed", "NumVpnSessions", "Uptime",
               "TotalUsers", "TotalTraffic", "FirstSeenAt", "LastSeenAt")
PORT_COLUMNS = ("SE_TCP_Port", "SE_UDP_Port", "OpenVPN_TCP_Port",
                "OpenVPN_UDP_Port", "SSTP_Port")
BOOL_COLUMNS = ("SE_UDP_Supported", "L2TP_Supported", "SSTP_Supported",
                "Has_IPv6")
TEXT_COLUMNS = ("HostName", "IP", "CountryLong", "CountryShort", "LogType",
                "Operator", "Message", "OpenVPN_ConfigData_Base64",
                "SSTP_Hostname")

log = logging.getLogger("emergency_publisher")


class PublisherError(RuntimeError):
    """Fatal, operator-visible error. Nothing is published."""


# --------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------

def now_ms() -> int:
    return int(time.time() * 1000)


def clean_text(value: Any) -> str:
    if value is None:
        return ""
    s = str(value).replace("\xa0", " ").replace("\u200b", "")
    s = s.replace("\r", " ").replace("\n", " ")
    return re.sub(r"\s+", " ", s).strip()


def normalize_ip(value: Any) -> str:
    """Canonical dotted-quad IPv4 or ''. Rejects leading-zero tricks."""
    s = clean_text(value)
    if not re.fullmatch(r"\d{1,3}(?:\.\d{1,3}){3}", s):
        return ""
    parts = s.split(".")
    if any(int(p) > 255 for p in parts):
        return ""
    try:
        return str(ipaddress.IPv4Address(".".join(str(int(p)) for p in parts)))
    except ValueError:
        return ""


def normalize_host(value: Any) -> str:
    s = clean_text(value).lower().rstrip(".")
    s = re.sub(r"^[a-z]+://", "", s)
    if s.count(":") == 1:
        s = s.rsplit(":", 1)[0]
    return s


def to_int_or_none(value: Any) -> Optional[int]:
    s = clean_text(value).replace(",", "")
    if not re.fullmatch(r"-?\d+", s):
        return None
    return int(s)


def to_port(value: Any) -> int:
    n = to_int_or_none(value)
    return n if n is not None and 0 <= n <= 65535 else 0


def to_bool(value: Any) -> bool:
    return clean_text(value).lower() in ("true", "1", "yes")


def ip_sort_key(ip: str) -> Tuple[int, ...]:
    return tuple(int(p) for p in ip.split("."))


# --------------------------------------------------------------------------
# Row model
# --------------------------------------------------------------------------

def empty_row() -> Dict[str, Any]:
    row: Dict[str, Any] = {}
    for c in COLUMNS:
        if c in INT_COLUMNS:
            row[c] = None
        elif c in PORT_COLUMNS:
            row[c] = 0
        elif c in BOOL_COLUMNS:
            row[c] = False
        else:
            row[c] = ""
    row["schema_version"] = SCHEMA_VERSION
    return row


def valid_base64(value: str) -> bool:
    if not value:
        return False
    try:
        base64.b64decode(value, validate=True)
        return True
    except (binascii.Error, ValueError):
        return False


# `host:port` with a hostname (not an IPv4 literal, whose dots-and-digits form
# cannot contain a colon anyway, and not IPv6, which this schema never carries).
SSTP_HOST_PORT_RE = re.compile(r"^([A-Za-z0-9._-]+):(\d{1,5})$")


def split_sstp_endpoint(host_text: str, port_value: int) -> Tuple[str, int]:
    """Split a `host:port` suffix out of `SSTP_Hostname` (section 10.3, v1.0.53 B.4).

    The VPN Gate page publishes ``SSTP Hostname: vpn943218848.opengw.net:1956``
    when the port is not 443, and the separate ``SSTP_Port`` column then holds
    ``0``. Writing the suffix straight back into the hostname column makes the
    app dial the literal host ``vpn943218848.opengw.net:1956`` — it appends the
    port again, so the endpoint becomes unparseable.

    The explicit column wins when it is set; otherwise the suffix is the port.
    Neither value is invented: if there is no suffix and no column, the port
    stays 0 and the app's documented 443 default applies.
    """
    m = SSTP_HOST_PORT_RE.match(host_text or "")
    if not m:
        return host_text, port_value
    host, suffix = m.group(1), int(m.group(2))
    if port_value > 0:
        return host, port_value
    if 1 <= suffix <= 65535:
        return host, suffix
    return host, port_value


def normalize_sstp_rows(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Apply [split_sstp_endpoint] to every row in place (v1.0.53 B.4).

    Needed as its own pass because the merge stage keeps the *original* row
    objects (`sanitize_row` is used there only as a validity filter), so a split
    performed inside `sanitize_row` would not reach the merged set.
    """
    changed = 0
    for row in rows:
        host, port = split_sstp_endpoint(
            row.get("SSTP_Hostname") or "", row.get("SSTP_Port") or 0)
        if host != (row.get("SSTP_Hostname") or "") or port != (row.get("SSTP_Port") or 0):
            log.info(
                "SSTP host:port split on %s: %r/%s -> %r/%s",
                row.get("IP"), row.get("SSTP_Hostname"), row.get("SSTP_Port"), host, port)
            row["SSTP_Hostname"] = host
            row["SSTP_Port"] = port
            changed += 1
    if changed:
        log.info("SSTP host:port splits applied: %d row(s)", changed)
    return rows


def decode_config_text(b64: str) -> str:
    """The embedded profile as text, or "" when it is absent or undecodable."""
    if not b64:
        return ""
    try:
        return base64.b64decode(b64).decode("utf-8", "replace")
    except (binascii.Error, ValueError):
        return ""


def config_transport_ports(b64: str) -> Dict[str, int]:
    """The port each transport's `remote` line actually dials (v1.0.53 B.3).

    Only the profile's own endpoints are returned. `proto` may appear before or
    after a `remote` line and may also be given inline as a fourth token, so the
    global proto is collected first and used as the fallback.
    """
    text = decode_config_text(b64)
    if not text:
        return {}

    protos: List[str] = []
    remotes: List[Tuple[int, str]] = []
    for line in text.splitlines():
        s = line.strip()
        if not s or s[0] in "#;":
            continue
        parts = s.split()
        key = parts[0].lower()
        if key == "proto" and len(parts) > 1:
            protos.append(parts[1].lower())
        elif key == "remote" and len(parts) >= 3:
            try:
                port = int(parts[2])
            except ValueError:
                continue
            inline = parts[3].lower() if len(parts) > 3 else ""
            remotes.append((port, inline))

    global_proto = protos[0] if protos and len(set(protos)) == 1 else ""
    out: Dict[str, int] = {}
    for port, inline in remotes:
        proto = inline or global_proto
        fam = "tcp" if proto.startswith("tcp") else ("udp" if proto.startswith("udp") else "")
        if fam and 1 <= port <= 65535 and fam not in out:
            out[fam] = port
    return out


def enforce_config_ports(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Make the embedded config's endpoint authoritative over the HTML port (v1.0.53 B.3).

    `merge_fresh` lets the HTML `do_openvpn` URL parameters own the protocol
    columns, while the embedded profile comes from the API. When the two
    disagree the published row advertises a port its own config does not dial —
    the reproduced case is `123.48.124.169` (link 1534, config 1558).

    The rule, per the operator: the endpoint the embedded config dials wins; the
    link is used only when the config declares no port for that transport. Every
    correction is logged, never applied silently.
    """
    corrected = 0
    for row in rows:
        ports = config_transport_ports(row.get("OpenVPN_ConfigData_Base64") or "")
        for fam, col in (("tcp", "OpenVPN_TCP_Port"), ("udp", "OpenVPN_UDP_Port")):
            cfg = ports.get(fam)
            if not cfg or row[col] == cfg:
                continue
            if row[col]:
                log.info(
                    "OpenVPN %s port conflict on %s: link/column %s, config %s -> keeping config",
                    fam, row["IP"], row[col], cfg)
                corrected += 1
            row[col] = cfg
    if corrected:
        log.info("Config-authoritative OpenVPN ports: corrected %d row(s)", corrected)
    return rows


def sanitize_row(row: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Apply the section 10.4 rules. Returns None if the row must be rejected."""
    out = empty_row()
    out.update({k: v for k, v in row.items() if k.startswith("_")})
    ip = normalize_ip(row.get("IP"))
    host = clean_text(row.get("HostName"))
    if not ip or not normalize_host(host):
        return None
    out["IP"] = ip
    out["HostName"] = host
    for c in INT_COLUMNS:
        out[c] = to_int_or_none(row.get(c))
    for c in PORT_COLUMNS:
        out[c] = to_port(row.get(c))
    for c in BOOL_COLUMNS:
        out[c] = bool(row.get(c)) if isinstance(row.get(c), bool) else to_bool(row.get(c))
    for c in ("CountryLong", "LogType", "Operator", "Message", "SSTP_Hostname"):
        out[c] = clean_text(row.get(c))
    # v1.0.53 (B.4): a `host:port` SSTP hostname becomes host + port. Done here
    # so every path that builds a row (API, HTML, previous-file carry-forward)
    # produces the same shape.
    out["SSTP_Hostname"], out["SSTP_Port"] = split_sstp_endpoint(
        out["SSTP_Hostname"], out["SSTP_Port"])
    short = clean_text(row.get("CountryShort")).upper()
    out["CountryShort"] = short if re.fullmatch(r"[A-Z]{2}", short) else ""
    b64 = clean_text(row.get("OpenVPN_ConfigData_Base64")).replace(" ", "")
    out["OpenVPN_ConfigData_Base64"] = b64 if valid_base64(b64) else ""
    tag = clean_text(row.get("SourceTag"))
    out["SourceTag"] = tag if tag in SOURCE_TAGS else "html_parse"
    out["schema_version"] = SCHEMA_VERSION
    return out


# --------------------------------------------------------------------------
# CSV reading (Python port of EmergencyCsvParser.kt checks) and writing
# --------------------------------------------------------------------------

@dataclass
class ParseResult:
    rows: List[Dict[str, Any]] = field(default_factory=list)
    rejected: int = 0
    malformed_reason: Optional[str] = None


def parse_emergency_csv(text: str) -> ParseResult:
    """Mirror of EmergencyCsvParser.parse(): file-level fail-closed checks,
    header-driven columns, per-row rejection."""
    if not text.strip():
        return ParseResult(malformed_reason="empty body")
    if text[0] == "\ufeff":
        return ParseResult(malformed_reason="utf-8 BOM is not part of the format")
    if "\ufffd" in text:
        return ParseResult(malformed_reason="body is not valid UTF-8")
    records = list(csv.reader(io.StringIO(text, newline="")))
    records = [r for r in records if r]  # blank lines
    if not records:
        return ParseResult(malformed_reason="no CSV records")
    columns = [c.strip() for c in records[0]]
    missing = [c for c in REQUIRED_COLUMNS if c not in columns]
    if missing:
        return ParseResult(malformed_reason="missing required column(s): " + ",".join(missing))
    data = records[1:]
    if not data:
        return ParseResult(malformed_reason="header only: no data rows")
    idx = {name: i for i, name in enumerate(columns)}
    for r in data:
        if len(r) != len(columns):
            continue
        declared = r[idx["schema_version"]].strip()
        if declared != SCHEMA_VERSION:
            return ParseResult(
                malformed_reason=f'unsupported schema_version "{declared}" (supported: {SCHEMA_VERSION})')
    res = ParseResult()
    for r in data:
        if len(r) != len(columns):
            res.rejected += 1
            continue
        raw = {name: r[i] for name, i in idx.items() if name in COLUMNS}
        row = sanitize_row(raw)
        if row is None:
            res.rejected += 1
            continue
        res.rows.append(row)
    if not res.rows:
        return ParseResult(rejected=res.rejected,
                           malformed_reason="no record passed required-field validation")
    return res


def _cell(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def render_csv(rows: Sequence[Dict[str, Any]]) -> bytes:
    buf = io.StringIO(newline="")
    w = csv.writer(buf, lineterminator="\n", quoting=csv.QUOTE_MINIMAL)
    w.writerow(COLUMNS)
    for row in rows:
        w.writerow([_cell(row[c]) for c in COLUMNS])
    return buf.getvalue().encode("utf-8")  # no BOM


def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=".tmp_", suffix=path.suffix)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    except BaseException:
        with contextlib.suppress(OSError):
            os.unlink(tmp)
        raise


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------

HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                   "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0 Safari/537.36"),
    "Accept-Language": "en-US,en;q=0.9",
}


def http_get(url: str, *, retries: int = 0, backoff: float = 5.0,
             connect_timeout: float = 10.0, read_timeout: float = 30.0) -> Optional[bytes]:
    """Bounded GET. Returns the body or None; never raises on network errors."""
    for attempt in range(retries + 1):
        try:
            resp = requests.get(url, headers=HEADERS, timeout=(connect_timeout, read_timeout))
            if resp.status_code == 404:
                log.warning("GET %s -> 404", url)
                return None
            resp.raise_for_status()
            return resp.content
        except requests.RequestException as exc:
            log.warning("GET %s failed (attempt %d/%d): %s", url, attempt + 1, retries + 1, exc)
            if attempt < retries:
                time.sleep(backoff)
    return None


# --------------------------------------------------------------------------
# Stage 1 - previous CSV
# --------------------------------------------------------------------------

def load_previous(args: argparse.Namespace) -> Tuple[List[Dict[str, Any]], str]:
    body: Optional[bytes]
    origin: str
    if args.previous_local:
        origin = f"file {args.previous_local}"
        p = Path(args.previous_local)
        body = p.read_bytes() if p.is_file() else None
    else:
        origin = args.previous_url or args.published_raw_url or ""
        if not origin.lower().startswith("https://"):
            log.warning("No HTTPS previous URL configured; starting from an empty set")
            return [], "none"
        body = http_get(origin)
    if body is None:
        log.warning("Previous CSV not available (%s): treating previous set as empty", origin)
        return [], f"missing ({origin})"
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError:
        log.warning("Previous CSV is not valid UTF-8: treating previous set as empty")
        return [], f"unparseable ({origin})"
    res = parse_emergency_csv(text)
    if res.malformed_reason:
        log.warning("Previous CSV rejected (%s): treating previous set as empty", res.malformed_reason)
        return [], f"unparseable ({origin}: {res.malformed_reason})"
    log.info("Previous CSV: %d rows (%d rejected)", len(res.rows), res.rejected)
    return res.rows, origin


# --------------------------------------------------------------------------
# Stage 2/4 - health probing
# --------------------------------------------------------------------------

def tcp_endpoint(row: Dict[str, Any]) -> Optional[Tuple[str, int]]:
    """First usable TCP listener, in the documented priority."""
    ip = row["IP"]
    for col in ("SE_TCP_Port", "OpenVPN_TCP_Port", "SSTP_Port"):
        if row[col] > 0:
            return ip, row[col]
    if row["SSTP_Supported"]:
        return ip, 443
    return None


def probe_tcp(host: str, port: int, timeout: float, retries: int) -> bool:
    for attempt in range(retries + 1):
        try:
            with socket.create_connection((host, port), timeout=timeout):
                return True
        except OSError:
            if attempt < retries:
                time.sleep(0.5)
    return False


def health_check(rows: List[Dict[str, Any]], args: argparse.Namespace) -> None:
    """Sets row['_healthy'] / row['_reason'] / row['_probed']. Rows whose
    endpoint is unchanged since the last probe are not probed again."""
    todo: List[Tuple[Dict[str, Any], Tuple[str, int]]] = []
    for row in rows:
        ep = tcp_endpoint(row)
        if ep is None:
            row["_healthy"], row["_reason"] = False, "no_tcp_endpoint"
            continue
        if row.get("_probed") == ep and "_healthy" in row:
            continue
        todo.append((row, ep))
    if args.skip_health_check:
        for row, ep in todo:
            row["_healthy"], row["_reason"], row["_probed"] = True, "skipped", ep
        return
    log.info("Health-checking %d endpoint(s) with %d worker(s), timeout %.1fs",
             len(todo), args.health_check_concurrency, args.health_check_timeout)
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.health_check_concurrency)) as pool:
        futures = {
            pool.submit(probe_tcp, ep[0], ep[1], args.health_check_timeout,
                        args.health_check_retries): (row, ep)
            for row, ep in todo
        }
        for fut in concurrent.futures.as_completed(futures):
            row, ep = futures[fut]
            ok = False
            try:
                ok = fut.result()
            except Exception as exc:  # defensive: a probe must never abort the run
                log.warning("probe error for %s: %s", ep[0], exc)
            row["_healthy"] = ok
            row["_reason"] = "ok" if ok else "tcp_connect_failed"
            row["_probed"] = ep
            if args.verbose:
                log.info("probe %s:%d -> %s", ep[0], ep[1], "ok" if ok else "FAIL")


# --------------------------------------------------------------------------
# Stage 3 - fetch from VPN Gate
# --------------------------------------------------------------------------

def parse_api_csv(text: str, seen_at: int) -> Tuple[List[Dict[str, Any]], int]:
    """Parse the iPhone API CSV keeping the raw values (ms / bps / bytes).

    Free-text Operator/Message may contain unquoted commas while the Base64
    payload never does, so extra fields are folded back into Message
    (right-anchored), exactly like the app collector does."""
    lines = text.splitlines()
    start = next((i for i, ln in enumerate(lines)
                  if ln.lstrip("#").strip().lower().startswith("hostname,ip,")), -1)
    if start < 0:
        return [], 0
    body = "\n".join(lines[start:]).lstrip()
    if body.startswith("#"):
        body = body[1:]
    rows: List[Dict[str, Any]] = []
    bad = 0
    reader = csv.reader(io.StringIO(body, newline=""))
    header = next(reader, None)
    if not header:
        return [], 0
    for vals in reader:
        if not vals or (len(vals) == 1 and not vals[0].strip()):
            continue
        if vals[0].startswith("*"):  # trailer line
            continue
        if len(vals) > 15:
            vals = vals[:12] + [vals[12], ",".join(vals[13:-1]), vals[-1]]
        if len(vals) != 15:
            bad += 1
            continue
        raw = dict(zip(COLUMNS[:15], vals))
        raw["FirstSeenAt"] = seen_at
        raw["SourceTag"] = "api_iphone"
        row = sanitize_row(raw)
        if row is None:
            bad += 1
            continue
        rows.append(row)
    return rows, bad


def _load_collector():
    here = Path(__file__).resolve().parent
    for p in (here, here.parent):
        if str(p) not in sys.path:
            sys.path.insert(0, str(p))
    import vpngate_collector as vc  # type: ignore
    return vc


def html_record_to_row(rec: Dict[str, Any], seen_at: int) -> Optional[Dict[str, Any]]:
    """Map one nested vpngate_collector record to section 10.2 columns.
    Only values the page actually published are carried over."""
    ident = rec.get("identity", {})
    prot = rec.get("protocols", {})
    se, ov = prot.get("softether", {}), prot.get("openvpn", {})
    l2, st = prot.get("l2tpIpsec", {}), prot.get("sstp", {})
    raw: Dict[str, Any] = {
        "HostName": ident.get("hostname", ""),
        "IP": ident.get("ip", ""),
        "CountryLong": ident.get("countryLong", ""),
        "CountryShort": ident.get("country", ""),
        "SE_TCP_Port": (se.get("tcp") or {}).get("port") or 0,
        "SE_UDP_Supported": bool((se.get("udp") or {}).get("supported")),
        "SE_UDP_Port": (se.get("udp") or {}).get("port") or 0,
        "OpenVPN_TCP_Port": (ov.get("tcp") or {}).get("port") or 0,
        "OpenVPN_UDP_Port": (ov.get("udp") or {}).get("port") or 0,
        "L2TP_Supported": bool(l2.get("supported")),
        "SSTP_Supported": bool(st.get("supported")),
        "SSTP_Hostname": st.get("hostname", ""),
        "SSTP_Port": st.get("port") or 0,
        # The collector does not track IPv6 observations; "false" = not observed.
        "Has_IPv6": False,
        "FirstSeenAt": seen_at,
        "SourceTag": "html_parse",
    }
    row = sanitize_row(raw)
    if row is not None:
        row["_html"] = True
    return row


def fetch_html_rows(args: argparse.Namespace, seen_at: int) -> List[Dict[str, Any]]:
    try:
        vc = _load_collector()
    except ImportError as exc:
        raise PublisherError(
            "vpngate_collector.py must be next to this script (needed for HTML parsing): "
            f"{exc}. Use --api-only to skip the HTML source.") from exc

    sink = sys.stdout if args.verbose else io.StringIO()
    urls = [HTML_URL]
    try:
        with contextlib.redirect_stdout(sink):
            mirrors = vc.discover_mirrors()
        urls += list(mirrors)[: max(0, args.mirror_fallbacks)]
    except Exception as exc:  # mirror discovery is optional
        log.warning("Mirror discovery failed: %s", exc)

    for i, url in enumerate(urls):
        body = http_get(url, retries=2, backoff=5.0)
        if not body:
            continue
        try:
            with contextlib.redirect_stdout(sink):
                recs = vc.parse_html(body.decode("utf-8", errors="replace"), "html")
        except Exception as exc:
            log.warning("HTML parse failed for %s: %s", url, exc)
            continue
        rows = [r for r in (html_record_to_row(x, seen_at) for x in recs) if r]
        if rows:
            log.info("HTML source %s%s: %d rows", url, "" if i == 0 else " (mirror)", len(rows))
            return rows
        log.warning("HTML source %s produced no rows", url)
    log.warning("No HTML source produced rows")
    return []


def fetch_fresh(args: argparse.Namespace, seen_at: int) -> List[Dict[str, Any]]:
    api_rows: List[Dict[str, Any]] = []
    body = http_get(API_URL, retries=2, backoff=5.0)
    if body:
        api_rows, bad = parse_api_csv(body.decode("utf-8", errors="replace"), seen_at)
        log.info("API source: %d rows (%d malformed skipped)", len(api_rows), bad)
    else:
        log.warning("API source unavailable")
    html_rows = [] if args.api_only else fetch_html_rows(args, seen_at)
    return merge_fresh(api_rows, html_rows)


# --------------------------------------------------------------------------
# Merge
# --------------------------------------------------------------------------

def _empty(col: str, value: Any) -> bool:
    if col in BOOL_COLUMNS:
        return value is False
    if col in PORT_COLUMNS:
        return value == 0
    if col in INT_COLUMNS:
        return value is None
    return value in ("", None)


def _combine_tags(a: str, b: str) -> str:
    return a if a == b else "merged"


def merge_two(base: Dict[str, Any], other: Dict[str, Any], *,
              prefer_other: bool, api_wins_base: bool = False) -> Dict[str, Any]:
    """Field-by-field merge. A non-empty value beats an empty one; between two
    non-empty values `other` wins when prefer_other, otherwise `base` does.
    First/LastSeenAt use min/max (section 10.6)."""
    out = dict(base)
    for c in COLUMNS:
        if c in ("schema_version", "SourceTag", "FirstSeenAt", "LastSeenAt"):
            continue
        b, o = base[c], other[c]
        if _empty(c, o):
            continue
        if _empty(c, b) or prefer_other:
            out[c] = o
    firsts = [v for v in (base["FirstSeenAt"], other["FirstSeenAt"]) if v is not None]
    lasts = [v for v in (base["LastSeenAt"], other["LastSeenAt"]) if v is not None]
    out["FirstSeenAt"] = min(firsts) if firsts else None
    out["LastSeenAt"] = max(lasts) if lasts else None
    out["SourceTag"] = _combine_tags(base["SourceTag"], other["SourceTag"])
    for k in ("_html",):
        if base.get(k) or other.get(k):
            out[k] = True
    return out


BASE15 = COLUMNS[:15]


def merge_fresh(api_rows: List[Dict[str, Any]], html_rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """API owns the base-15 columns, HTML owns the protocol columns (10.2)."""
    by_ip: Dict[str, Dict[str, Any]] = {r["IP"]: r for r in api_rows}
    for h in html_rows:
        cur = by_ip.get(h["IP"])
        if cur is None:
            by_ip[h["IP"]] = h
            continue
        merged = merge_two(cur, h, prefer_other=False)
        for c in COLUMNS:  # protocol columns: the HTML value wins
            if c not in BASE15 and c not in ("schema_version", "SourceTag", "FirstSeenAt", "LastSeenAt"):
                if not _empty(c, h[c]):
                    merged[c] = h[c]
        merged["SourceTag"] = "merged"
        by_ip[h["IP"]] = merged
    return list(by_ip.values())


def merge_with_previous(previous: List[Dict[str, Any]], fresh: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """A previous row missing from the fresh response is kept as-is."""
    by_ip: Dict[str, Dict[str, Any]] = {r["IP"]: r for r in previous}
    for f in fresh:
        old = by_ip.get(f["IP"])
        if old is None:
            by_ip[f["IP"]] = f
            continue
        merged = merge_two(old, f, prefer_other=True)
        for k in ("_healthy", "_reason", "_probed"):  # Stage 2 health survives
            if k in old:
                merged[k] = old[k]
        by_ip[f["IP"]] = merged
    return list(by_ip.values())


def dedupe_hostnames(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Secondary key (10.5): same normalized HostName on different IPs would be
    collapsed by the app, so keep the stronger row here."""
    best: Dict[str, Dict[str, Any]] = {}
    for r in rows:
        key = normalize_host(r["HostName"])
        cur = best.get(key)
        if cur is None or (protocol_count(r), r["Score"] or -1, r["IP"]) > \
                (protocol_count(cur), cur["Score"] or -1, cur["IP"]):
            if cur is not None:
                log.info("hostname collision %s: keeping %s over %s", key, r["IP"], cur["IP"])
            best[key] = r
        else:
            log.info("hostname collision %s: keeping %s over %s", key, cur["IP"], r["IP"])
    return list(best.values())


# --------------------------------------------------------------------------
# Prune
# --------------------------------------------------------------------------

def protocol_count(row: Dict[str, Any]) -> int:
    n = 0
    n += row["SE_TCP_Port"] > 0
    n += bool(row["SE_UDP_Supported"])
    n += row["OpenVPN_TCP_Port"] > 0
    n += row["OpenVPN_UDP_Port"] > 0
    n += bool(row["SSTP_Supported"])  # port is published or defaults to 443
    n += bool(row["L2TP_Supported"])
    return int(n)


def prune(rows: List[Dict[str, Any]], cap: int) -> List[Dict[str, Any]]:
    """Remove first: fewer protocols, older LastSeenAt (or FirstSeenAt),
    lower Score, lexicographically smaller IP. Deterministic."""
    if len(rows) <= cap:
        return rows

    def key(r: Dict[str, Any]) -> Tuple[Any, ...]:
        seen = r["LastSeenAt"] if r["LastSeenAt"] is not None else (r["FirstSeenAt"] or 0)
        return (protocol_count(r), seen, r["Score"] if r["Score"] is not None else -1, r["IP"])

    ordered = sorted(rows, key=key)  # weakest first
    kept = ordered[len(rows) - cap:]
    log.info("Pruned %d row(s) to reach the cap of %d", len(rows) - cap, cap)
    return kept


def rank_key(r: Dict[str, Any]) -> Tuple[Any, ...]:
    """Higher = better. Same ordering that prune() uses (weakest sorts first)."""
    seen = r["LastSeenAt"] if r["LastSeenAt"] is not None else (r["FirstSeenAt"] or 0)
    return (protocol_count(r), seen, r["Score"] if r["Score"] is not None else -1, r["IP"])


def fit_budget(rows: List[Dict[str, Any]], max_bytes: int) -> Tuple[List[Dict[str, Any]], int, int]:
    """Keep the file under the app's body-size cap (EmergencySourceFetcher.MAX_BODY_CHARS = 1 MiB).

    The embedded OpenVPN config (~13 KB of Base64 per row) is ~98% of the file, so:
      1. every row is first written WITHOUT its config (a row without one is valid: it keeps its
         SoftEther / SSTP / L2TP / port facts and OpenVPN is simply not offered for it);
      2. if even that exceeds the budget, the weakest rows are dropped;
      3. configs are then restored greedily, best-ranked row first, while the budget allows.
    Returns (rows, configs_kept, configs_stripped)."""
    ranked = sorted((dict(r) for r in rows), key=rank_key, reverse=True)  # best first
    configs = [r["OpenVPN_ConfigData_Base64"] for r in ranked]
    for r in ranked:
        r["OpenVPN_ConfigData_Base64"] = ""
    lo, hi = 0, len(ranked)
    while lo < hi:  # largest n whose stripped rendering fits
        mid = (lo + hi + 1) // 2
        if len(render_csv(ranked[:mid])) <= max_bytes:
            lo = mid
        else:
            hi = mid - 1
    kept = ranked[:lo]
    if lo < len(ranked):
        log.warning("Size budget %d bytes: dropped %d lowest-ranked row(s) even without OpenVPN configs",
                    max_bytes, len(ranked) - lo)
    remaining = max_bytes - len(render_csv(kept))
    restored = 0
    for r, cfg in zip(kept, configs[:lo]):
        if cfg and len(cfg) <= remaining:  # Base64 is ASCII and needs no CSV quoting
            r["OpenVPN_ConfigData_Base64"] = cfg
            remaining -= len(cfg)
            restored += 1
    with_cfg = sum(1 for c in configs[:lo] if c)
    return kept, restored, with_cfg - restored


# --------------------------------------------------------------------------
# Android resource, git, verification
# --------------------------------------------------------------------------

def xml_escape(s: str) -> str:
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("'", "\\'").replace('"', '\\"'))


def render_url_resource(url: str) -> str:
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            '    <string name="default_emergency_source_url" translatable="false">'
            f"{xml_escape(url)}</string>\n"
            "</resources>\n")


def run_git(repo: Path, *argv: str, check: bool = True) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", "-C", str(repo), *argv], capture_output=True, text=True)
    if check and proc.returncode != 0:
        raise PublisherError(f"git {' '.join(argv)} failed: {proc.stderr.strip() or proc.stdout.strip()}")
    return proc


def git_publish(repo: Path, paths: Iterable[Path], message: str, push: bool) -> bool:
    rels = [str(p.resolve().relative_to(repo.resolve())) for p in paths]
    run_git(repo, "add", "--", *rels)
    if run_git(repo, "diff", "--cached", "--quiet", "--", *rels, check=False).returncode == 0:
        log.info("git: nothing to commit")
        return False
    run_git(repo, "commit", "-m", message, "--", *rels)
    if push:
        run_git(repo, "push")
    return True


def verify_published(url: str, expected: bytes, retries: int, delay: float) -> bool:
    want = hashlib.sha256(expected).hexdigest()
    for attempt in range(retries):
        body = http_get(f"{url}?nocache={int(time.time())}", retries=0)
        if body is not None and hashlib.sha256(body).hexdigest() == want:
            return True
        if attempt < retries - 1:
            time.sleep(delay)
    return False


# --------------------------------------------------------------------------
# Orchestration
# --------------------------------------------------------------------------

@dataclass
class Summary:
    previous_origin: str = ""
    previous_rows: int = 0
    previous_healthy: int = 0
    fresh_rows: int = 0
    dropped_defective: int = 0
    pruned: int = 0
    written: int = 0
    configs_kept: int = 0
    configs_stripped: int = 0
    pushed: bool = False
    verified: Optional[bool] = None
    sha256: str = ""


def run(args: argparse.Namespace) -> Summary:
    summary = Summary()
    started = now_ms()

    # Stage 1
    previous, summary.previous_origin = load_previous(args)
    summary.previous_rows = len(previous)

    # Stage 2
    for r in previous:
        r["_html"] = False
    health_check(previous, args)
    summary.previous_healthy = sum(1 for r in previous if r.get("_healthy"))
    if (len(previous) >= args.min_previous_for_ratio and not args.skip_health_check and
            summary.previous_healthy / len(previous) < args.min_healthy_ratio and not args.force):
        raise PublisherError(
            f"Only {summary.previous_healthy}/{len(previous)} previous servers passed the health "
            "check - this runner's network is probably the problem. Nothing was changed. "
            "Re-run elsewhere or pass --force.")
    for r in previous:
        if r.get("_healthy"):
            r["LastSeenAt"] = started

    # Stage 3
    fresh = fetch_fresh(args, started)
    summary.fresh_rows = len(fresh)
    if not fresh and not previous:
        raise PublisherError("No data from any source and no previous file: nothing to publish.")

    # Stage 4
    merged = merge_with_previous(previous, fresh)
    merged = [r for r in merged if sanitize_row(r) is not None]
    normalize_sstp_rows(merged)   # v1.0.53 (B.4): host:port -> host + port
    enforce_config_ports(merged)  # v1.0.53 (B.3): embedded config owns the endpoint
    merged = dedupe_hostnames(merged)
    health_check(merged, args)  # only new / changed endpoints are probed
    for r in merged:
        if r.get("_healthy"):
            r["LastSeenAt"] = started
    healthy = [r for r in merged if r.get("_healthy")]
    summary.dropped_defective = len(merged) - len(healthy)

    # Stage 5
    kept = prune(healthy, args.max_servers)
    summary.pruned = len(healthy) - len(kept)
    if len(kept) < args.min_rows:
        raise PublisherError(
            f"Only {len(kept)} healthy row(s) (< --min-rows {args.min_rows}); refusing to publish.")
    kept, summary.configs_kept, summary.configs_stripped = fit_budget(kept, args.max_bytes)
    if len(kept) < args.min_rows:
        raise PublisherError(
            f"Only {len(kept)} row(s) fit in --max-bytes {args.max_bytes}; refusing to publish.")
    kept.sort(key=lambda r: ip_sort_key(r["IP"]))

    # Stage 6
    data = render_csv(kept)
    if len(data) > args.max_bytes:
        raise PublisherError(f"Generated file is {len(data)} bytes > --max-bytes {args.max_bytes}.")
    check = parse_emergency_csv(data.decode("utf-8"))
    if check.malformed_reason or check.rejected or \
            sorted(r["IP"] for r in check.rows) != sorted(r["IP"] for r in kept):
        raise PublisherError(
            "Self-check failed: the generated CSV does not round-trip through the parser rules "
            f"(reason={check.malformed_reason}, rejected={check.rejected}).")
    out = Path(args.output)
    write_atomic(out, data)
    summary.written = len(kept)
    summary.sha256 = hashlib.sha256(data).hexdigest()

    # Stage 7
    url_out = Path(args.url_output)
    if args.published_raw_url:
        res = render_url_resource(args.published_raw_url)
        if not url_out.is_file() or url_out.read_text(encoding="utf-8") != res:
            write_atomic(url_out, res.encode("utf-8"))
            log.info("Wrote %s", url_out)
    else:
        log.warning("--published-raw-url not set: default URL resource not written")

    # Stage 8
    if args.dry_run:
        log.info("--dry-run: git step skipped")
        return summary
    repo = Path(args.repo_dir).resolve()
    paths = [out] + ([url_out] if args.published_raw_url and url_out.is_file() else [])
    summary.pushed = git_publish(
        repo, paths, f"chore(emergency): update servers.csv ({summary.written} servers)",
        push=not args.no_push)
    if summary.pushed and not args.no_push and args.published_raw_url and args.verify_retries > 0:
        summary.verified = verify_published(
            args.published_raw_url, data, args.verify_retries, args.verify_delay)
    return summary


def find_repo(candidate: Optional[str]) -> Optional[Path]:
    """Git top-level for --repo-dir, else for the current directory, else for the
    script's own directory. None when no git repository is found."""
    places: List[Path] = [Path(candidate)] if candidate else [Path.cwd(), Path(__file__).resolve().parent]
    for place in places:
        try:
            proc = subprocess.run(["git", "-C", str(place), "rev-parse", "--show-toplevel"],
                                  capture_output=True, text=True)
        except (OSError, FileNotFoundError):
            return None  # git not installed
        if proc.returncode == 0 and proc.stdout.strip():
            return Path(proc.stdout.strip()).resolve()
    return None


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--config", default="collector.toml", help="optional TOML file; CLI flags override it")
    p.add_argument("--max-servers", type=int, default=500)
    p.add_argument("--output", default="emergency-sources/servers.csv")
    p.add_argument("--url-output", default="app/src/main/res/values/emergency_defaults.xml")
    p.add_argument("--published-raw-url", default=DEFAULT_RAW_URL,
                   help="public raw URL registered as the app default (default assumes branch 'main')")
    p.add_argument("--previous-url", default=None, help="defaults to --published-raw-url")
    p.add_argument("--previous-local", default=None)
    p.add_argument("--health-check-timeout", type=float, default=3.0)
    p.add_argument("--health-check-concurrency", type=int, default=20)
    p.add_argument("--health-check-retries", type=int, default=1,
                   help="extra probe attempts before a server counts as defective")
    p.add_argument("--mirror-fallbacks", type=int, default=3)
    p.add_argument("--api-only", action="store_true", help="skip the HTML source (protocol columns stay 0)")
    p.add_argument("--min-rows", type=int, default=10, help="refuse to publish fewer healthy rows")
    p.add_argument("--max-bytes", type=int, default=900_000,
                   help="hard size budget of servers.csv; the app rejects bodies over 1,048,576 chars")
    p.add_argument("--min-previous-for-ratio", type=int, default=20)
    p.add_argument("--min-healthy-ratio", type=float, default=0.2,
                   help="abort if fewer of the previous servers pass (network likely broken)")
    p.add_argument("--force", action="store_true")
    p.add_argument("--repo-dir", default=None,
                   help="git repository root (default: auto-detected from the current or script directory); "
                        "relative --output/--url-output are resolved against it")
    p.add_argument("--no-push", action="store_true", help="commit locally but do not push")
    p.add_argument("--verify-retries", type=int, default=3)
    p.add_argument("--verify-delay", type=float, default=20.0)
    p.add_argument("--dry-run", action="store_true", help="write files locally, skip git")
    p.add_argument("--skip-health-check", action="store_true", help="dev only; asks for confirmation to publish")
    p.add_argument("--yes-publish-unverified", action="store_true", help=argparse.SUPPRESS)
    p.add_argument("--verbose", action="store_true")
    p.add_argument("--log-file", default=None)
    return p


def load_config_defaults(parser: argparse.ArgumentParser, argv: Sequence[str]) -> None:
    pre = argparse.ArgumentParser(add_help=False)
    pre.add_argument("--config", default="collector.toml")
    known, _ = pre.parse_known_args(list(argv))
    path = Path(known.config)
    if not path.is_file():
        return
    if tomllib is None:
        log.warning("tomllib unavailable (Python < 3.11): ignoring %s", path)
        return
    with path.open("rb") as f:
        cfg = tomllib.load(f)
    valid = {a.dest for a in parser._actions}
    parser.set_defaults(**{k.replace("-", "_"): v for k, v in cfg.items()
                           if k.replace("-", "_") in valid})


class _JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        return json.dumps({"t": self.formatTime(record), "level": record.levelname,
                           "msg": record.getMessage()}, ensure_ascii=False)


def setup_logging(args: argparse.Namespace) -> None:
    log.setLevel(logging.INFO)
    log.handlers.clear()
    h = logging.StreamHandler(sys.stdout)
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    log.addHandler(h)
    if args.log_file:
        fh = logging.FileHandler(args.log_file, encoding="utf-8")
        fh.setFormatter(_JsonFormatter())
        log.addHandler(fh)


def main(argv: Optional[Sequence[str]] = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    parser = build_parser()
    load_config_defaults(parser, argv)
    args = parser.parse_args(argv)
    setup_logging(args)

    if args.max_servers < 1:
        parser.error("--max-servers must be >= 1")
    if args.skip_health_check and not args.dry_run and not args.yes_publish_unverified:
        if not sys.stdin.isatty():
            log.error("--skip-health-check would publish unverified servers; refusing (non-interactive)")
            return 2
        if input("Publish WITHOUT health checks? Type 'yes' to continue: ").strip().lower() != "yes":
            return 2

    repo = find_repo(args.repo_dir)
    if repo is None and not args.dry_run:
        log.error("No git repository found (current dir: %s). Run from inside your SoftEthergai "
                  "clone or pass --repo-dir C:\\path\\to\\SoftEthergai. Nothing was fetched or written.",
                  Path.cwd())
        return 2
    if repo is not None:
        args.repo_dir = str(repo)
        for name in ("output", "url_output", "previous_local"):
            val = getattr(args, name)
            if val and not Path(val).is_absolute():
                setattr(args, name, str(repo / val))
        for name in ("output", "url_output"):
            try:
                Path(getattr(args, name)).resolve().relative_to(repo)
            except ValueError:
                if not args.dry_run:
                    log.error("--%s must be inside the repository %s", name.replace("_", "-"), repo)
                    return 2
        log.info("Repository: %s", repo)
    else:
        log.warning("No git repository found; --dry-run writes relative to %s", Path.cwd())

    try:
        s = run(args)
    except PublisherError as exc:
        log.error("%s", exc)
        return 1

    log.info("=" * 60)
    log.info("previous: %s | %d rows, %d healthy", s.previous_origin, s.previous_rows, s.previous_healthy)
    log.info("fresh rows: %d | defective dropped: %d | pruned: %d", s.fresh_rows, s.dropped_defective, s.pruned)
    log.info("written: %d rows -> %s (sha256 %s)", s.written, args.output, s.sha256[:16])
    log.info("OpenVPN configs: %d kept, %d stripped to fit %d bytes", s.configs_kept, s.configs_stripped, args.max_bytes)
    if args.dry_run:
        log.info("dry-run: nothing committed")
    elif not s.pushed:
        log.info("git: no change to publish" if not args.no_push else "git: committed locally")
    elif args.no_push:
        log.info("git: committed locally (not pushed)")
    else:
        log.info("git: pushed")
        if s.verified is True:
            log.info("raw URL VERIFIED: %s", args.published_raw_url)
        elif s.verified is False:
            log.warning("raw URL NOT VERIFIED (CDN cache can take ~5 min): %s", args.published_raw_url)
    return 0


if __name__ == "__main__":
    sys.exit(main())
