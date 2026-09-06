"""
Mission §31 Test 2 — API CSV parsing with unit normalization (§18).
Uses the REAL header captured live from /api/iphone/.
"""
import base64

import pytest

import vpngate_collector as vg


OPENVPN_CONFIG_BODY = """\
# OpenVPN 2.0 Sample Configuration File
dev tun
proto tcp
remote 219.100.37.165 443
"""


def make_api_row(**overrides) -> str:
    fields = [
        "HostName", "IP", "Score", "Ping", "Speed",
        "CountryLong", "CountryShort", "NumVpnSessions",
        "Uptime", "TotalUsers", "TotalTraffic", "LogType",
        "Operator", "Message", "OpenVPN_ConfigData_Base64",
    ]

    row = {
        "HostName": "public-vpn-206",
        "IP": "219.100.37.165",
        "Score": "2785379",
        "Ping": "21",
        "Speed": "659456803",
        "CountryLong": "Japan",
        "CountryShort": "JP",
        "NumVpnSessions": "52",
        # ~93 days in ms
        "Uptime": "8035200000",
        "TotalUsers": "15699569",
        "TotalTraffic": "633908788074369",
        "LogType": "2weeks",
        "Operator": "Daiyuu Nobori, Japan. Academic Use Only.",
        "Message": "",
        "OpenVPN_ConfigData_Base64": base64.b64encode(
            OPENVPN_CONFIG_BODY.encode()
        ).decode(),
    }

    row.update(overrides)

    csv_text = (
        "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,"
        "NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,"
        "Operator,Message,OpenVPN_ConfigData_Base64\n"
        + ",".join(row[f] for f in fields)
    )

    return csv_text


def test_2_same_server_from_api():
    text = (
        "*vpn_servers\n" + make_api_row()
    )

    servers = vg.parse_api(text, "api")

    assert len(servers) == 1

    server = servers[0]
    identity = server["identity"]

    assert identity["hostname"] == "public-vpn-206"
    assert identity["ip"] == "219.100.37.165"
    assert identity["country"] == "JP"
    assert identity["countryLong"] == "Japan"

    perf = server["performance"]
    assert perf["speedMbps"] == pytest.approx(659.456803)   # bps -> Mbps
    assert perf["pingMs"] == 21
    assert perf["sessions"] == 52
    assert perf["uptimeDays"] == pytest.approx(93.0)        # ms -> days
    assert perf["totalUsers"] == 15699569
    assert perf["totalTrafficGB"] == pytest.approx(
        633908788074369 / 1024 ** 3,
        rel=1e-6,
    )
    assert server["logging"]["policy"] == "2weeks"

    # Protocol truth from the decoded config itself (§7):
    p = server["protocols"]["openvpn"]
    assert p["supported"] is True
    assert p["configAvailable"] is True
    assert p["tcp"]["supported"] is True
    assert p["tcp"]["port"] == 443          # from `remote ... 443`
    assert p["udp"]["supported"] is False   # no udp directive anywhere
    assert p["udp"]["port"] is None         # NEVER guessed 1194
    assert p["configs"][0] == {
        "host": "219.100.37.165",
        "port": 443,
        "proto": "",
    }

    # Provenance (§14/§17): metadata marked as coming from api.
    fs = server["fieldSources"]
    assert fs["performance.speedMbps"] == ["api"]
    assert fs["performance.score"] == ["api"]
    assert fs["identity.country"] == ["api"]
