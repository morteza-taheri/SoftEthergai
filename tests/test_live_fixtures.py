"""
Plan T2.3 — regression fixtures captured from the LIVE site.

* ``vpngate_table_nested_sample.html``: the hosts table exactly as
  embedded on www.vpngate.net/en/ today — wrapped in
  ``<td><p><span id="Label_Table">`` and carrying the UNMATCHED
  ``</td>`` before every ``</tr>`` of a header block. On that real
  layout ``html.parser`` unwinds its stack to the outer ``<td>`` and
  silently drops EVERY data row (the collector came back with 0
  servers from the live page). ``_make_soup`` must recover all rows.

* ``vpngate_api_sample.csv``: the real ``/api/iphone/`` header plus
  five live rows — anchors the true column layout and the
  bps/ms/bytes unit conversions.
"""
from bs4 import BeautifulSoup

import vpngate_collector as vg

NESTED = "tests/fixtures/vpngate_table_nested_sample.html"
API = "tests/fixtures/vpngate_api_sample.csv"


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def test_nested_fixture_reproduces_live_malformation():
    """Sanity: the captured fixture really contains the malformed
    structure it is supposed to guard against."""
    import re

    html = _read(NESTED)

    assert 'Label_Table' in html
    assert "vg_hosts_table_id" in html
    assert re.search(r"</td>\s*</td>\s*</tr>", html), (
        "fixture lost its unmatched </td>"
    )


def test_unsanitized_html_parser_loses_rows():
    """Proof that the fixture exercises the actual failure mode:
    raw html.parser keeps only the header row."""
    html = _read(NESTED)

    raw = vg.find_hosts_tables(BeautifulSoup(html, "html.parser"))
    raw_table = vg.select_hosts_table(raw)

    kept = [
        tr
        for tr in raw_table.find_all("tr")
        if any(
            "vg_table_row" in " ".join(td.get("class") or [])
            for td in tr.find_all("td")
        )
    ]

    assert kept == [], (
        "html.parser unexpectedly survived — fixture no longer "
        "reproduces the live regression"
    )


def test_nested_fixture_parses_all_rows():
    html = _read(NESTED)
    servers = vg.parse_html(html, "html")

    # header + 15 captured data rows; two share an IP region pattern,
    # so at least 12 unique servers must survive.
    assert len(servers) >= 12

    for s in servers:
        assert s["identity"]["ip"]
        assert s["identity"]["hostname"].endswith(".opengw.net")


def test_nested_fixture_diagnosis_clean():
    result = vg.diagnose_html(_read(NESTED), verbose=False)

    assert result["rows"] >= 12
    assert result["compared"] == result["rows"]
    assert result["mismatchTotal"] == 0


def test_api_fixture_real_header_parses():
    servers = vg.parse_api(_read(API), "api")

    assert len(servers) == 5

    for s in servers:
        assert vg.valid_ip(s["identity"]["ip"])
        assert s["identity"]["hostname"]
        # bps -> Mbps and ms -> days actually happened.
        assert s["performance"]["speedMbps"] > 1
        assert s["performance"]["uptimeDays"] >= 0.5


def test_api_fixture_openvpn_config_decoded():
    servers = vg.parse_api(_read(API), "api")

    with_config = [
        s for s in servers if s["protocols"]["openvpn"]["configs"]
    ]

    assert with_config, "no decoded OpenVPN configs in live rows"

    for s in with_config:
        ovpn = s["protocols"]["openvpn"]

        assert ovpn["configAvailable"] is True

        for remote in ovpn["configs"]:
            assert remote["host"]
            # §7: port only from a real `remote ... <port>` directive.
            assert remote["port"] is None or vg.valid_port(remote["port"])
