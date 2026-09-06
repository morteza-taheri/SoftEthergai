"""
§33 — developer diagnostics: the per-server provenance dump used by
``--debug-ip`` must expose per-field sources, conflicts and
confidence for every merged server.
"""
import vpngate_collector as vg

from test_api_parser import make_api_row


def _fixture_html() -> str:
    with open(
        "tests/fixtures/vpngate_table_sample.html",
        encoding="utf-8",
    ) as f:
        return f.read()


def _merged_server():
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

    merged = vg.merge_records([api_record, html_record])
    assert len(merged) == 1
    return merged[0]


def test_debug_dump_contains_identity_and_sources():
    dump = vg.format_server_debug(_merged_server())

    # API record was merged first: its hostname ("public-vpn-206")
    # wins over the HTML fqdn per §17 priority.
    assert "SERVER DUMP: public-vpn-206 (219.100.37.165)" in dump
    assert "[sources]" in dump
    assert "api" in dump and "html" in dump
    assert "independent groups: api, html" in dump


def test_debug_dump_lists_field_sources():
    dump = vg.format_server_debug(_merged_server())

    assert "[field sources]" in dump
    assert "identity.ip" in dump
    assert "protocols.softether.tcp.port" in dump
    assert "protocols.openvpn.tcp.port" in dump


def test_debug_dump_surfaces_conflicts():
    dump = vg.format_server_debug(_merged_server())

    assert "[conflicts]" in dump
    assert "performance.sessions" in dump
    # Both owners and their values must be visible (§38).
    assert "52" in dump and "69" in dump


def test_debug_dump_shows_confidence():
    dump = vg.format_server_debug(_merged_server())

    assert "[confidence]" in dump
    # Provenance-specific confidence (§15): SoftEther facts came
    # from html only (0.60), OpenVPN facts from api only (0.75).
    assert "softether=0.60" in dump
    assert "openvpn=0.75" in dump


def test_debug_dump_fixture_records():
    """Dump must work for every server parsed from the real HTML
    fixture without raising."""
    servers = vg.merge_records(vg.parse_html(_fixture_html(), "html"))
    servers = vg.validate_servers(servers)
    vg.score_all(servers)

    assert servers
    for server in servers:
        dump = vg.format_server_debug(server)
        assert dump.startswith("=" * 72)
        assert "[conflicts]" in dump
        assert "[field sources]" in dump
