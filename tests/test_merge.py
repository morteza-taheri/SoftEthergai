"""
Mission §31 Tests 3/4 — multi-source merge, dedup, provenance,
conflict recording (§38) and mirror-independence rules (§16).
"""
import pytest

import vpngate_collector as vg

from test_api_parser import make_api_row


def test_3_html_plus_api_merge_to_one_record():
    """§31 Test 3: same server from HTML + API must merge into ONE
    record with API winning metadata conflicts — recorded (§38)."""
    api_record = vg.parse_api(
        "*vpn_servers\n" + make_api_row(NumVpnSessions="52"),
        "api",
    )[0]

    # Simulate an HTML record for the same IP carrying a stale
    # sessions value and SoftEther info only present in HTML.
    html_record = vg.new_server()
    vg.set_field(html_record, "identity.hostname", "public-vpn-206.opengw.net", "html")
    vg.set_field(html_record, "identity.ip", "219.100.37.165", "html")
    vg.set_field(html_record, "identity.countryLong", "Japan", "html")
    vg.set_field(html_record, "performance.sessions", 69, "html")
    vg.set_field(html_record, "protocols.softether.tcp.supported", True, "html")
    vg.set_field(html_record, "protocols.softether.tcp.port", 443, "html")
    vg.set_field(html_record, "protocols.softether.udp.supported", True, "html")
    vg.source_add(html_record, "html")

    merged = vg.merge_records([api_record, html_record])

    assert len(merged) == 1                      # ONE record, not two

    server = merged[0]
    p = server["protocols"]

    # Union of protocol knowledge from BOTH sources:
    assert p["softether"]["tcp"]["port"] == 443      # html-only field kept
    assert p["openvpn"]["tcp"]["port"] == 443        # api-only field kept
    assert p["openvpn"]["configAvailable"] is True
    assert p["softether"]["udp"]["supported"] is True
    assert p["softether"]["udp"]["port"] is None     # never invented

    assert sorted(server["sources"]) == ["api", "html"]

    # Sessions disagreed (html=69 vs api=52): API wins (§17) and the
    # conflict is RECORDED, not silently overwritten.
    assert server["performance"]["sessions"] == 52

    conflict = next(
        (
            c
            for c in server.get("conflicts", [])
            if c["field"] == "performance.sessions"
        ),
        None,
    )
    assert conflict is not None
    assert conflict["values"]["api"] == 52
    assert conflict["values"]["html"] == 69

    fs = server["fieldSources"]
    assert sorted(fs["performance.sessions"]) == ["api", "html"]


def test_3_api_country_beats_partial_html():
    """§11: HTML's partial country must not replace API's full value;
    missing API fields get filled FROM HTML without conflicts."""
    api_record = vg.parse_api(
        "*vpn_servers\n" + make_api_row(CountryShort="", CountryLong=""),
        "api",
    )[0]

    assert api_record["identity"]["countryLong"] == ""

    html_record = vg.new_server()
    vg.set_field(html_record, "identity.ip", "219.100.37.165", "html")
    vg.set_field(html_record, "identity.hostname", "public-vpn-206.opengw.net", "html")
    vg.set_field(html_record, "identity.countryLong", "Japan", "html")
    vg.set_field(html_record, "identity.country", "JP", "html")
    vg.source_add(html_record, "html")

    merged = vg.merge_records([html_record, api_record])

    assert len(merged) == 1

    identity = merged[0]["identity"]

    # HTML filled the gap; nothing was overwritten because the API
    # had no country data here.
    assert identity["country"] == "JP"
    assert identity["countryLong"] == "Japan"

    assert not any(
        c["field"].startswith("identity.country")
        for c in merged[0].get("conflicts", [])
    )


def test_4_same_ip_in_six_mirrors_is_one_record():
    """§31 Test 4: an IP seen across html + 6 mirrors stays ONE
    record; mirrors collapse into a single independent group (§16);
    confidence never exceeds the policy ceiling."""
    import copy

    main = vg.new_server()
    vg.set_field(main, "identity.hostname", "public-vpn-206.opengw.net", "html")
    vg.set_field(main, "identity.ip", "219.100.37.165", "html")
    vg.set_field(main, "protocols.softether.tcp.supported", True, "html")
    vg.set_field(main, "protocols.softether.tcp.port", 443, "html")
    vg.source_add(main, "html")

    template = copy.deepcopy(main)

    records = [main]

    for i in range(1, 7):                       # six mirrors
        mirror = copy.deepcopy(template)

        for key in ("sources", "sourceCount"):
            mirror.pop(key, None)

        mirror["fieldSources"] = {}
        mirror["_owners"] = {}

        vg.set_field(mirror, "protocols.softether.tcp.port", 443, f"mirror_{i}")
        vg.set_field(mirror, "protocols.softether.tcp.supported", True, f"mirror_{i}")
        vg.source_add(mirror, f"mirror_{i}")
        records.append(mirror)

    merged = vg.merge_records(records)

    assert len(merged) == 1                     # ONE record

    server = merged[0]

    assert len(server["sources"]) >= 7          # raw source tags unioned

    # §16: {api/html/mirror} — html + one shared mirror group = 2.
    groups = vg.independent_source_groups(server)
    assert groups == ["html", "mirror"]

    conf = vg.compute_confidence(server)

    # §15: two INDEPENDENT groups => 0.80; six mirrors add NOTHING.
    assert conf["softether"] == pytest.approx(0.8)


def test_confidence_policy_matches_mission_15():
    assert vg.confidence_for_groups(["html"]) == 0.6
    assert vg.confidence_for_groups(["api", "html"]) == 0.8
    assert vg.confidence_for_groups(["api", "html", "mirror"]) == 1.0
    assert vg.confidence_for_groups([]) == 0.0


def test_priority_ordering():
    assert vg.source_priority("api") < vg.source_priority("html")
    assert vg.source_priority("html") < vg.source_priority("mirror_9")


def test_mirror_speed_never_overwrites_api():
    """A lower-authority source (mirror) disagreeing on speed must
    NOT overwrite the API value, and the attempt IS visible (§38)."""
    api_record = vg.parse_api(
        "*vpn_servers\n" + make_api_row(Speed="659456803"),
        "api",
    )[0]

    mirror = vg.new_server()
    vg.set_field(mirror, "identity.ip", "219.100.37.165", "mirror_1")
    vg.set_field(mirror, "identity.hostname", "public-vpn-206.opengw.net", "mirror_1")
    vg.set_field(mirror, "performance.speedMbps", 111.0, "mirror_1")
    vg.source_add(mirror, "mirror_1")

    merged = vg.merge_records([api_record, mirror])

    assert len(merged) == 1

    # API wins:
    assert merged[0]["performance"]["speedMbps"] == pytest.approx(659.456803)

    # Disagreement was not silent:
    speed_conflict = next(
        (
            c for c in merged[0].get("conflicts", [])
            if c["field"] == "performance.speedMbps"
        ),
        None,
    )
    assert speed_conflict is not None
    assert speed_conflict["values"]["api"] == pytest.approx(659.456803)
    assert speed_conflict["values"]["mirror_1"] == 111.0