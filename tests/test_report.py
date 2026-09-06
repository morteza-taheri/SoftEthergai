"""
T1 regression tests — the collection report must be written as plain
JSON. The old path (``save_json`` → ``_export_server``) demanded a
server record shape, crashed ``main()`` with ``KeyError: 'identity'``
and ``collection_report.json`` was never written.
"""
import json

import pytest

import vpngate_collector as vg

from test_api_parser import make_api_row


def _merged_servers():
    api_record = vg.parse_api(
        "*vpn_servers\n" + make_api_row(NumVpnSessions="52"),
        "api",
    )[0]

    html_record = vg.new_server()
    vg.set_field(html_record, "identity.hostname", "public-vpn-206.opengw.net", "html")
    vg.set_field(html_record, "identity.ip", "219.100.37.165", "html")
    vg.set_field(html_record, "performance.sessions", 69, "html")
    vg.set_field(html_record, "protocols.softether.tcp.supported", True, "html")
    vg.set_field(html_record, "protocols.softether.tcp.port", 443, "html")
    vg.source_add(html_record, "html")

    return vg.merge_records([api_record, html_record])


def test_save_report_writes_plain_json(tmp_path):
    servers = _merged_servers()
    report = vg.build_report(servers, ["https://mirror.example/"], 2)

    out = tmp_path / "collection_report.json"
    vg.save_report(str(out), report)

    loaded = json.loads(out.read_text(encoding="utf-8"))

    assert loaded["uniqueServers"] == 1
    assert loaded["rawRecords"] == 2
    assert loaded["mirrorsDiscovered"] == 1
    assert "protocolCounts" in loaded
    assert "provenance" in loaded


def test_report_never_goes_through_export_server():
    """Guard the contract that made the old crash possible: the
    report dict has no server shape and must not survive
    ``_export_server``."""
    report = vg.build_report([], [], 0)

    with pytest.raises(KeyError):
        vg._export_server(report)


def test_provenance_summary_counts_conflicts():
    servers = _merged_servers()
    summary = vg.build_provenance_summary(servers)

    assert summary["conflictCount"] >= 1
    assert summary["serversWithConflicts"] == 1

    fields = {e["field"] for e in summary["topConflictingFields"]}
    assert "performance.sessions" in fields

    dist = summary["confidenceDistribution"]
    assert set(dist) == {"softether", "openvpn", "l2tpIpsec", "sstp"}

    groups = summary["independentGroups"]
    assert groups["distribution"].get("2") == 1
    assert groups["serversPerGroup"] == {"api": 1, "html": 1}


def test_export_surfaces_confidence_conflicts_configs():
    """Defect 5: confidence, conflicts and decoded OpenVPN config
    facts must reach the exported JSON."""
    servers = _merged_servers()
    exported = vg._export_server(servers[0])

    # html-only softether facts -> single 'html' group -> 0.6
    assert exported["confidence"]["softether"] == pytest.approx(0.6)
    # api-only openvpn facts -> single 'api' group -> 0.75
    assert exported["confidence"]["openvpn"] == pytest.approx(0.75)

    assert any(
        c["field"] == "performance.sessions"
        for c in exported["conflicts"]
    )

    assert isinstance(exported["openVPN"]["configs"], list)
    assert exported["openVPN"]["configs"][0]["port"] == 443
