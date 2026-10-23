"""Offline tests: python3 -m unittest test_emergency_csv_publisher -v
No network is used; "servers" are local listening sockets on 127.0.0.x."""
import argparse
import base64
import socket
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import emergency_csv_publisher as pub

API_SAMPLE = (
    "*vpn_servers\n"
    "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,"
    "TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n"
    "vpn111,1.2.3.4,100,10,5000000,Japan,jp,3,86400000,10,999,2weeks,op1,hello,aGVsbG8=\n"
    # unquoted commas in Operator / Message
    "vpn222,5.6.7.8,200,20,6000000,Korea,KR,4,1000,11,888,2weeks,Op,msg, with comma,aGk=\n"
    "vpn333,999.1.1.1,1,1,1,X,XX,1,1,1,1,l,o,m,aGk=\n"   # invalid IP
    "vpn444,9.9.9.9,5\n"                                   # too short
    "*\n"
)


def row(ip, host=None, **kw):
    r = pub.empty_row()
    r.update(HostName=host or f"h-{ip}", IP=ip, SourceTag="api_iphone", FirstSeenAt=1, LastSeenAt=1)
    r.update(kw)
    return r


def default_args(**over):
    base = dict(
        max_servers=500, output="", url_output="", published_raw_url=pub.DEFAULT_RAW_URL,
        previous_url=None, previous_local=None, health_check_timeout=0.5,
        health_check_concurrency=4, health_check_retries=0, mirror_fallbacks=0, api_only=True,
        min_rows=1, max_bytes=900_000, min_previous_for_ratio=20, min_healthy_ratio=0.2, force=False,
        repo_dir=".", no_push=False, verify_retries=0, verify_delay=0, dry_run=True,
        skip_health_check=False, verbose=False, log_file=None)
    base.update(over)
    return argparse.Namespace(**base)


class ParseApi(unittest.TestCase):
    def test_raw_values_and_repair(self):
        rows, bad = pub.parse_api_csv(API_SAMPLE, 123)
        self.assertEqual([r["IP"] for r in rows], ["1.2.3.4", "5.6.7.8"])
        self.assertEqual(bad, 2)  # invalid ip + short row
        a, b = rows
        self.assertEqual((a["Ping"], a["Speed"], a["Uptime"], a["TotalTraffic"]), (10, 5000000, 86400000, 999))
        self.assertEqual(a["CountryShort"], "JP")
        self.assertEqual(b["Message"], "msg, with comma")
        self.assertEqual(b["OpenVPN_ConfigData_Base64"], "aGk=")
        self.assertEqual(a["SE_TCP_Port"], 0)       # never invented
        self.assertEqual(a["SourceTag"], "api_iphone")


class CsvFormat(unittest.TestCase):
    def test_roundtrip_quoting_no_bom_lf(self):
        r = row("10.0.0.1", Message='he said "hi", ok', Operator="a\nb", SE_TCP_Port=443,
                SE_UDP_Supported=True)
        data = pub.render_csv([pub.sanitize_row(r)])
        self.assertFalse(data.startswith(b"\xef\xbb\xbf"))
        self.assertNotIn(b"\r", data)
        self.assertTrue(data.endswith(b"\n"))
        self.assertEqual(data.split(b"\n")[0].decode().split(",")[0], "HostName")
        res = pub.parse_emergency_csv(data.decode())
        self.assertIsNone(res.malformed_reason)
        self.assertEqual(res.rows[0]["Message"], 'he said "hi", ok')
        self.assertEqual(res.rows[0]["Operator"], "a b")
        self.assertTrue(res.rows[0]["SE_UDP_Supported"])

    def test_header_has_29_columns_in_order(self):
        self.assertEqual(len(pub.COLUMNS), 29)
        self.assertEqual(pub.COLUMNS[15], "schema_version")
        self.assertEqual(pub.COLUMNS[-1], "SourceTag")

    def test_parser_fail_closed(self):
        good = pub.render_csv([pub.sanitize_row(row("10.0.0.1"))]).decode()
        self.assertEqual(pub.parse_emergency_csv("\ufeff" + good).malformed_reason,
                         "utf-8 BOM is not part of the format")
        self.assertIn("unsupported schema_version", pub.parse_emergency_csv(good.replace(",1.0,", ",2.0,")).malformed_reason)
        self.assertIsNotNone(pub.parse_emergency_csv("HostName,IP\nx,1.1.1.1\n").malformed_reason)
        self.assertIsNotNone(pub.parse_emergency_csv("").malformed_reason)

    def test_bad_rows_rejected_individually(self):
        good = pub.render_csv([pub.sanitize_row(row("10.0.0.1")), pub.sanitize_row(row("10.0.0.2"))]).decode()
        bad = good.replace("10.0.0.2", "10.0.0.999")
        res = pub.parse_emergency_csv(bad)
        self.assertEqual((len(res.rows), res.rejected), (1, 1))

    def test_xml_resource(self):
        x = pub.render_url_resource("https://e.com/a?b=1&c=2")
        self.assertIn("&amp;", x)
        self.assertIn('name="default_emergency_source_url" translatable="false"', x)


class MergeAndPrune(unittest.TestCase):
    def test_protocol_count(self):
        r = row("1.1.1.1", SE_TCP_Port=443, SE_UDP_Supported=True, OpenVPN_TCP_Port=1,
               OpenVPN_UDP_Port=2, SSTP_Supported=True, L2TP_Supported=True)
        self.assertEqual(pub.protocol_count(r), 6)

    def test_prune_order(self):
        rows = [
            row("1.1.1.1", SE_TCP_Port=1, Score=900, LastSeenAt=100),                 # 1 proto
            row("1.1.1.2", SE_TCP_Port=1, OpenVPN_TCP_Port=2, Score=1, LastSeenAt=1),  # 2 protos, old, low
            row("1.1.1.3", SE_TCP_Port=1, OpenVPN_TCP_Port=2, Score=5, LastSeenAt=50),  # 2 protos
            row("1.1.1.4", SE_TCP_Port=1, OpenVPN_TCP_Port=2, Score=5, LastSeenAt=50),  # tie -> ip
        ]
        kept = pub.prune(rows, 2)
        self.assertEqual(sorted(r["IP"] for r in kept), ["1.1.1.3", "1.1.1.4"])
        # deterministic
        self.assertEqual([r["IP"] for r in pub.prune(list(reversed(rows)), 2)] and
                         sorted(r["IP"] for r in pub.prune(list(reversed(rows)), 2)),
                         ["1.1.1.3", "1.1.1.4"])

    def test_previous_row_missing_from_fresh_is_kept(self):
        prev = [row("2.2.2.2", SE_TCP_Port=443)]
        merged = pub.merge_with_previous(prev, [row("3.3.3.3")])
        self.assertEqual(sorted(r["IP"] for r in merged), ["2.2.2.2", "3.3.3.3"])

    def test_merge_field_rules(self):
        old = row("4.4.4.4", Score=10, Message="old", SE_TCP_Port=443, FirstSeenAt=5, LastSeenAt=6)
        new = row("4.4.4.4", Score=20, Message="", OpenVPN_TCP_Port=1194, SourceTag="html_parse",
                  FirstSeenAt=9, LastSeenAt=9)
        m = pub.merge_with_previous([old], [new])[0]
        self.assertEqual((m["Score"], m["Message"], m["SE_TCP_Port"], m["OpenVPN_TCP_Port"]),
                         (20, "old", 443, 1194))
        self.assertEqual((m["FirstSeenAt"], m["LastSeenAt"], m["SourceTag"]), (5, 9, "merged"))

    def test_hostname_collision(self):
        a = row("5.5.5.5", host="vpn1:995", SE_TCP_Port=1)
        b = row("5.5.5.6", host="VPN1", SE_TCP_Port=1, OpenVPN_TCP_Port=2)
        self.assertEqual([r["IP"] for r in pub.dedupe_hostnames([a, b])], ["5.5.5.6"])


class EndToEnd(unittest.TestCase):
    def setUp(self):
        self.socks = []
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def listener(self, ip):
        s = socket.socket()
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((ip, 0))
        s.listen(5)
        self.socks.append(s)
        self.addCleanup(s.close)
        return s.getsockname()[1]

    def test_full_run(self):
        d = Path(self.tmp.name)
        # previous: A healthy, B dead, C healthy (and missing from fresh)
        pa, pc = self.listener("127.0.0.11"), self.listener("127.0.0.13")
        prev_rows = [
            row("127.0.0.11", SE_TCP_Port=pa, FirstSeenAt=1000, LastSeenAt=1000, SourceTag="merged"),
            row("127.0.0.12", SE_TCP_Port=1, FirstSeenAt=1000, LastSeenAt=1000),   # nothing listens
            row("127.0.0.13", SE_TCP_Port=pc, FirstSeenAt=1000, LastSeenAt=1000),
            row("127.0.0.14", FirstSeenAt=1000, LastSeenAt=1000),                  # no tcp endpoint
        ]
        (d / "prev.csv").write_bytes(pub.render_csv(prev_rows))
        # fresh: A again (updated score), D new healthy, E new dead
        pd = self.listener("127.0.0.21")
        fresh = [row("127.0.0.11", Score=777, SE_TCP_Port=pa, FirstSeenAt=9_999_999_999_999),
                 row("127.0.0.21", SE_TCP_Port=pd, OpenVPN_TCP_Port=pd),
                 row("127.0.0.22", SE_TCP_Port=1)]
        args = default_args(previous_local=str(d / "prev.csv"), output=str(d / "out" / "servers.csv"),
                            url_output=str(d / "res" / "emergency_defaults.xml"))
        with mock.patch.object(pub, "fetch_fresh", return_value=fresh):
            s = pub.run(args)
        res = pub.parse_emergency_csv((d / "out" / "servers.csv").read_text("utf-8"))
        ips = [r["IP"] for r in res.rows]
        self.assertEqual(ips, ["127.0.0.11", "127.0.0.13", "127.0.0.21"])
        self.assertEqual(s.previous_healthy, 2)
        by = {r["IP"]: r for r in res.rows}
        self.assertEqual(by["127.0.0.11"]["Score"], 777)
        self.assertEqual(by["127.0.0.11"]["FirstSeenAt"], 1000)       # preserved
        self.assertGreater(by["127.0.0.11"]["LastSeenAt"], 1000)      # refreshed
        self.assertGreater(by["127.0.0.13"]["LastSeenAt"], 1000)      # kept though not in fresh
        self.assertIn("default_emergency_source_url", (d / "res" / "emergency_defaults.xml").read_text())

    def test_cap_keeps_previous_healthy_and_prefers_more_protocols(self):
        d = Path(self.tmp.name)
        pa, pb, pn = (self.listener(f"127.0.0.{i}") for i in (31, 32, 33))
        prev = [row("127.0.0.31", SE_TCP_Port=pa), row("127.0.0.32", SE_TCP_Port=pb)]
        (d / "prev.csv").write_bytes(pub.render_csv(prev))
        fresh = [row("127.0.0.33", SE_TCP_Port=pn, OpenVPN_TCP_Port=pn, L2TP_Supported=True)]
        args = default_args(previous_local=str(d / "prev.csv"), max_servers=2,
                            output=str(d / "o.csv"), url_output=str(d / "x.xml"))
        with mock.patch.object(pub, "fetch_fresh", return_value=fresh):
            s = pub.run(args)
        ips = [r["IP"] for r in pub.parse_emergency_csv((d / "o.csv").read_text()).rows]
        self.assertEqual(s.pruned, 1)
        self.assertIn("127.0.0.33", ips)          # strongest new server wins a slot
        self.assertEqual(len(ips), 2)

    def test_safety_abort_when_network_is_down(self):
        d = Path(self.tmp.name)
        prev = [row(f"127.0.1.{i}", SE_TCP_Port=1) for i in range(1, 31)]   # all dead
        (d / "prev.csv").write_bytes(pub.render_csv(prev))
        args = default_args(previous_local=str(d / "prev.csv"), output=str(d / "o.csv"), url_output=str(d / "x.xml"))
        with mock.patch.object(pub, "fetch_fresh", return_value=[]):
            with self.assertRaises(pub.PublisherError):
                pub.run(args)
        self.assertFalse((d / "o.csv").exists())

    def test_missing_previous_is_warning_not_abort(self):
        d = Path(self.tmp.name)
        p = self.listener("127.0.0.41")
        args = default_args(previous_local=str(d / "nope.csv"), output=str(d / "o.csv"), url_output=str(d / "x.xml"))
        with mock.patch.object(pub, "fetch_fresh", return_value=[row("127.0.0.41", SE_TCP_Port=p)]):
            s = pub.run(args)
        self.assertEqual(s.written, 1)

    def test_deterministic_output(self):
        d = Path(self.tmp.name)
        p1, p2 = self.listener("127.0.0.51"), self.listener("127.0.0.52")
        fresh = [row("127.0.0.52", SE_TCP_Port=p2, LastSeenAt=5), row("127.0.0.51", SE_TCP_Port=p1, LastSeenAt=5)]
        outs = []
        for name, order in (("a", fresh), ("b", list(reversed(fresh)))):
            args = default_args(previous_local=str(d / "none.csv"), output=str(d / f"{name}.csv"),
                                url_output=str(d / "x.xml"))
            with mock.patch.object(pub, "fetch_fresh", return_value=[dict(r) for r in order]), \
                    mock.patch.object(pub, "now_ms", return_value=42):
                pub.run(args)
            outs.append((d / f"{name}.csv").read_bytes())
        self.assertEqual(outs[0], outs[1])


if __name__ == "__main__":
    unittest.main()


# --- Integration with the real vpngate_collector.parse_html (needs it next to this file) ---
HTML_FIXTURE = """
<html><body><table id="vg_hosts_table_id">
<tr><td class="vg_table_header">Country</td><td class="vg_table_header">DDNS hostname IP address (ISP hostname)</td>
<td class="vg_table_header">VPN sessions Uptime</td><td class="vg_table_header">Line quality Throughput and Ping</td>
<td class="vg_table_header">SSL-VPN</td><td class="vg_table_header">L2TP/IPsec</td>
<td class="vg_table_header">OpenVPN</td><td class="vg_table_header">MS-SSTP</td>
<td class="vg_table_header">Volunteer operator's name</td><td class="vg_table_header">Score</td></tr>
<tr><td><img src="/images/flags/JP.png"><br>Japan</td>
<td><b>vpn123.opengw.net</b><br>1.2.3.4<br>(ocn.ne.jp)</td>
<td>5 sessions 3 days</td><td>10 ms 20 Mbps</td>
<td>SSL-VPN TCP: 443 UDP: Supported</td><td>L2TP/IPsec</td>
<td>OpenVPN TCP: 1194 UDP: 1195</td><td>MS-SSTP SSTP Hostname: vpn123.opengw.net</td>
<td>Someone</td><td>1000</td></tr>
</table></body></html>
"""


class CollectorIntegration(unittest.TestCase):
    def test_parse_html_to_row(self):
        try:
            vc = pub._load_collector()
        except ImportError:
            self.skipTest("vpngate_collector.py not next to the tests")
        import contextlib, io
        with contextlib.redirect_stdout(io.StringIO()):
            recs = vc.parse_html(HTML_FIXTURE, "html")
        rows = [r for r in (pub.html_record_to_row(x, 1) for x in recs) if r]
        self.assertEqual(len(rows), 1, recs)
        r = rows[0]
        self.assertEqual(r["IP"], "1.2.3.4")
        self.assertEqual(r["CountryShort"], "JP")
        self.assertEqual((r["SE_TCP_Port"], r["SE_UDP_Supported"]), (443, True))
        self.assertEqual((r["OpenVPN_TCP_Port"], r["OpenVPN_UDP_Port"]), (1194, 1195))
        self.assertTrue(r["L2TP_Supported"])
        self.assertTrue(r["SSTP_Supported"])
        self.assertEqual(r["SourceTag"], "html_parse")


class RepoResolution(unittest.TestCase):
    def test_not_a_repo_fails_before_any_work(self):
        import os, subprocess
        with tempfile.TemporaryDirectory() as d:
            old = os.getcwd()
            os.chdir(d)
            try:
                with mock.patch.object(pub, "run") as run, \
                        mock.patch.object(pub, "find_repo", return_value=None):
                    self.assertEqual(pub.main([]), 2)
                    run.assert_not_called()
            finally:
                os.chdir(old)

    def test_paths_resolve_against_repo_root(self):
        import os, subprocess
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["git", "init", "-q", d], check=True)
            sub = Path(d) / "tools"
            sub.mkdir()
            old = os.getcwd()
            os.chdir(sub)  # run from a sub-folder, like the user's PyCharm cwd
            captured = {}
            try:
                with mock.patch.object(pub, "run", side_effect=lambda a: captured.setdefault("a", a) and pub.Summary()):
                    self.assertEqual(pub.main(["--dry-run"]), 0)
            finally:
                os.chdir(old)
            a = captured["a"]
            root = Path(d).resolve()
            self.assertEqual(Path(a.repo_dir).resolve(), root)
            self.assertEqual(Path(a.output).resolve(), root / "emergency-sources" / "servers.csv")
            self.assertEqual(Path(a.url_output).resolve(),
                             root / "app" / "src" / "main" / "res" / "values" / "emergency_defaults.xml")


class SizeBudget(unittest.TestCase):
    CFG = "A" * 13000  # realistic OpenVPN Base64 size (~13 KB per row)

    def rows(self, n, cfg=True):
        out = []
        for i in range(n):
            out.append(pub.sanitize_row(row(f"10.1.{i // 250}.{i % 250 + 1}", SE_TCP_Port=443,
                                            Score=i, OpenVPN_ConfigData_Base64=("QUJD" * 3250) if cfg else "")))
        return out

    def test_realistic_batch_fits_under_app_cap(self):
        rows = self.rows(127)  # the size that produced the 1,684,330-byte file
        self.assertGreater(len(pub.render_csv(rows)), 1_048_576)
        kept, ok, stripped = pub.fit_budget(rows, 900_000)
        data = pub.render_csv(kept)
        self.assertLessEqual(len(data), 900_000)
        self.assertLess(len(data), 1_048_576)
        self.assertEqual(len(kept), 127)           # no server is dropped, only configs
        self.assertGreater(ok, 0)
        self.assertEqual(ok + stripped, 127)
        self.assertIsNone(pub.parse_emergency_csv(data.decode()).malformed_reason)

    def test_best_ranked_rows_keep_their_configs(self):
        kept, ok, _ = pub.fit_budget(self.rows(127), 900_000)
        with_cfg = sorted(int(r["IP"].split(".")[-1]) + 250 * int(r["IP"].split(".")[2])
                          for r in kept if r["OpenVPN_ConfigData_Base64"])
        # Score == index, so the highest scores (best rank) must be the ones that kept a config
        self.assertGreaterEqual(min(with_cfg), 127 - ok - 1)

    def test_small_file_untouched(self):
        rows = self.rows(5)
        kept, ok, stripped = pub.fit_budget(rows, 900_000)
        self.assertEqual((ok, stripped), (5, 0))
        self.assertTrue(all(r["OpenVPN_ConfigData_Base64"] for r in kept))

    def test_rows_dropped_only_when_even_stripped_rows_do_not_fit(self):
        rows = self.rows(400, cfg=False)
        stripped_size = len(pub.render_csv(rows))
        kept, ok, _ = pub.fit_budget(rows, stripped_size // 2)
        self.assertLess(len(kept), 400)
        self.assertLessEqual(len(pub.render_csv(kept)), stripped_size // 2)

    def test_deterministic(self):
        a = pub.render_csv(sorted(pub.fit_budget(self.rows(127), 900_000)[0], key=lambda r: r["IP"]))
        b = pub.render_csv(sorted(pub.fit_budget(list(reversed(self.rows(127))), 900_000)[0], key=lambda r: r["IP"]))
        self.assertEqual(a, b)


# --------------------------------------------------------------------------
# v1.0.53 (§1.6.45): config-authoritative OpenVPN ports + SSTP host:port split
# --------------------------------------------------------------------------

# A REDUCED EXCERPT of a real shipped profile — not invented server data. The
# `remote`/`proto` lines are the ones the published row for 123.48.124.169
# carries (config 1558 while its OpenVPN_TCP_Port column said 1534), taken from
# emergency-sources/servers.csv at commit 0387d37. The rest is the standard
# SoftEther-generated preamble, shortened so the fixture stays readable.
REAL_OVPN_EXCERPT = """\
###############################################################################
# OpenVPN 2.0 Sample Configuration File
# for PacketiX VPN / SoftEther VPN Server
# !!! AUTO-GENERATED BY SOFTETHER VPN SERVER MANAGEMENT TOOL !!!
###############################################################################
client
dev tun
proto tcp
remote 123.48.124.169 1558
cipher AES-128-CBC
auth SHA1
"""


def b64(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


class ConfigAuthoritativePorts(unittest.TestCase):
    """TASK B.3: the embedded config's endpoint wins over the link/column."""

    def test_config_port_is_extracted(self):
        self.assertEqual(pub.config_transport_ports(b64(REAL_OVPN_EXCERPT)), {"tcp": 1558})

    def test_inline_proto_selects_the_udp_family(self):
        cfg = "client\nremote 1.2.3.4 1194 udp\n"
        self.assertEqual(pub.config_transport_ports(b64(cfg)), {"udp": 1194})

    def test_global_proto_after_remote_still_applies(self):
        cfg = "client\nremote 1.2.3.4 443\nproto tcp-client\n"
        self.assertEqual(pub.config_transport_ports(b64(cfg)), {"tcp": 443})

    def test_absent_config_yields_no_ports(self):
        self.assertEqual(pub.config_transport_ports(""), {})
        self.assertEqual(pub.config_transport_ports(b64("client\n# no remote\n")), {})

    def test_conflicting_column_is_corrected_to_the_config(self):
        r = row("123.48.124.169", OpenVPN_ConfigData_Base64=b64(REAL_OVPN_EXCERPT),
                OpenVPN_TCP_Port=1534)
        pub.enforce_config_ports([r])
        self.assertEqual(r["OpenVPN_TCP_Port"], 1558)

    def test_zero_column_is_filled_from_the_config(self):
        r = row("123.48.124.169", OpenVPN_ConfigData_Base64=b64(REAL_OVPN_EXCERPT),
                OpenVPN_TCP_Port=0)
        pub.enforce_config_ports([r])
        self.assertEqual(r["OpenVPN_TCP_Port"], 1558)

    def test_a_row_without_a_config_keeps_its_column(self):
        r = row("1.2.3.4", OpenVPN_ConfigData_Base64="", OpenVPN_TCP_Port=1534)
        pub.enforce_config_ports([r])
        self.assertEqual(r["OpenVPN_TCP_Port"], 1534)

    def test_a_matching_column_is_untouched(self):
        r = row("123.48.124.169", OpenVPN_ConfigData_Base64=b64(REAL_OVPN_EXCERPT),
                OpenVPN_TCP_Port=1558)
        pub.enforce_config_ports([r])
        self.assertEqual(r["OpenVPN_TCP_Port"], 1558)

    def test_the_udp_column_is_not_touched_by_a_tcp_only_config(self):
        r = row("123.48.124.169", OpenVPN_ConfigData_Base64=b64(REAL_OVPN_EXCERPT),
                OpenVPN_TCP_Port=1534, OpenVPN_UDP_Port=1278)
        pub.enforce_config_ports([r])
        self.assertEqual((r["OpenVPN_TCP_Port"], r["OpenVPN_UDP_Port"]), (1558, 1278))


class SstpHostPortSplit(unittest.TestCase):
    """TASK B.4: `SSTP_Hostname` may carry `:port`; the app must not receive it."""

    # The real historical row: SSTP_Hostname 'vpn943218848.opengw.net:1956',
    # SSTP_Port 0, SE_TCP_Port 1956 (commit 0387d37).
    def test_real_row_splits_into_host_and_port(self):
        self.assertEqual(
            pub.split_sstp_endpoint("vpn943218848.opengw.net:1956", 0),
            ("vpn943218848.opengw.net", 1956))

    def test_the_explicit_column_wins_over_the_suffix(self):
        self.assertEqual(
            pub.split_sstp_endpoint("vpn943218848.opengw.net:1956", 443),
            ("vpn943218848.opengw.net", 443))

    def test_a_plain_host_is_unchanged(self):
        self.assertEqual(pub.split_sstp_endpoint("vpn1.opengw.net", 0), ("vpn1.opengw.net", 0))
        self.assertEqual(pub.split_sstp_endpoint("vpn1.opengw.net", 443), ("vpn1.opengw.net", 443))

    def test_an_empty_host_is_unchanged(self):
        self.assertEqual(pub.split_sstp_endpoint("", 0), ("", 0))

    def test_a_non_port_suffix_is_not_treated_as_a_port(self):
        # Not a host:port form, so nothing is split and nothing is invented.
        self.assertEqual(pub.split_sstp_endpoint("vpn1.opengw.net:https", 0),
                         ("vpn1.opengw.net:https", 0))

    def test_sanitize_row_applies_the_split(self):
        out = pub.sanitize_row(dict(IP="123.200.94.11", HostName="vpn943218848",
                                    SSTP_Hostname="vpn943218848.opengw.net:1956",
                                    SSTP_Port=0))
        self.assertEqual((out["SSTP_Hostname"], out["SSTP_Port"]),
                         ("vpn943218848.opengw.net", 1956))

    def test_normalize_sstp_rows_is_idempotent(self):
        rows = [row("123.200.94.11", SSTP_Hostname="vpn943218848.opengw.net:1956", SSTP_Port=0)]
        pub.normalize_sstp_rows(rows)
        first = (rows[0]["SSTP_Hostname"], rows[0]["SSTP_Port"])
        pub.normalize_sstp_rows(rows)
        self.assertEqual((rows[0]["SSTP_Hostname"], rows[0]["SSTP_Port"]), first)
        self.assertEqual(first, ("vpn943218848.opengw.net", 1956))
