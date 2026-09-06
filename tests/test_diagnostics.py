"""
Plan T2 — diagnostics regression: on the REAL snapshot fixture the
structured parser must agree with the raw per-cell facts for every
row. A mismatch here means a parser gap (or an invented port).
"""
import vpngate_collector as vg


def _fixture_html() -> str:
    with open(
        "tests/fixtures/vpngate_table_sample.html",
        encoding="utf-8",
    ) as f:
        return f.read()


def test_fixture_diagnosis_zero_mismatches():
    result = vg.diagnose_html(_fixture_html(), verbose=False)

    assert result["rows"] > 0
    assert result["compared"] == result["rows"]
    assert result["mismatches"] == []
    assert result["mismatchTotal"] == 0
    assert result["mismatchCounts"] == {}


def test_diagnosis_flags_invented_port(monkeypatch):
    """The comparator must catch a parser that invents ports: force
    an invented SoftEther UDP port into the parsed records and expect
    a 'softether_udp_port_invented' mismatch (§6/§9)."""
    html = _fixture_html()
    real_parse = vg.parse_html

    def corrupt(html_arg, source_arg):
        servers = real_parse(html_arg, source_arg)

        for s in servers:
            se = s["protocols"]["softether"]

            if se["udp"]["supported"] and se["udp"]["port"] is None:
                se["udp"]["port"] = 1194      # invented

        return servers

    monkeypatch.setattr(vg, "parse_html", corrupt)

    result = vg.diagnose_html(html, verbose=False)

    assert result["mismatchCounts"].get(
        "softether_udp_port_invented", 0
    ) >= 1


def test_diagnosis_empty_or_tableless_html():
    result = vg.diagnose_html("<html><body>no table</body></html>")

    assert result["rows"] == 0
    assert result["compared"] == 0
    assert result["mismatchTotal"] == 0
