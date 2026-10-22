package vn.unlimit.vpngate.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.automode.AutoModeEngine
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.data.remote.SourceIdentity
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.parser.EmergencyCsvParser
import vn.unlimit.vpngate.parser.EmergencyCsvParser.Issue
import vn.unlimit.vpngate.parser.EmergencyCsvParser.Result
import vn.unlimit.vpngate.parser.VpnGateApiParser
import vn.unlimit.vpngate.repository.VpnConnectionMapper

/**
 * `EmergencyCsvParser` against `SERVER_SOURCE_ARCHITECTURE.md` §10 — the
 * emergency CSV schema approved as the design basis in spec v1.0.20
 * (§11.9.3).
 *
 * Pure JVM throughout: an emergency body is a String in, a parse result
 * out, so every case below is deterministic and no network, Room device or
 * Android framework is involved.
 *
 * The fixture is deliberately the same server the API-oracle fixture uses
 * (`CollectorTestFixtures.makeApiRow`) with the §10.2 extended columns
 * added, so a reader can compare the emergency mapping against the live
 * mapping of the same facts.
 */
class EmergencyCsvParserTest {

    private fun b64(body: String): String =
        java.util.Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))

    /**
     * A conforming §10.2 row. Optional columns carry real values so the
     * mapping has something to prove; `Message` is empty on purpose, since
     * an empty optional column must stay unset rather than become `""`.
     */
    private fun row(vararg overrides: Pair<String, String>): Map<String, String> =
        linkedMapOf<String, String>().apply {
            put("HostName", "public-vpn-206")
            put("IP", "219.100.37.165")
            put("Score", "2785379")
            put("Ping", "21")
            put("Speed", "659456803")
            put("CountryLong", "Japan")
            put("CountryShort", "JP")
            put("NumVpnSessions", "52")
            put("Uptime", "8035200000")
            put("TotalUsers", "15699569")
            put("TotalTraffic", "633908788074369")
            put("LogType", "2weeks")
            put("Operator", "Daiyuu Nobori")
            put("Message", "")
            put("OpenVPN_ConfigData_Base64", b64(CollectorTestFixtures.OPENVPN_CONFIG_BODY))
            put("schema_version", "1.0")
            put("SE_TCP_Port", "443")
            put("SE_UDP_Supported", "false")
            put("SE_UDP_Port", "0")
            put("OpenVPN_TCP_Port", "443")
            put("OpenVPN_UDP_Port", "0")
            put("L2TP_Supported", "false")
            put("SSTP_Supported", "true")
            put("SSTP_Hostname", "sstp.example.com")
            put("SSTP_Port", "443")
            put("Has_IPv6", "false")
            put("FirstSeenAt", "")
            put("LastSeenAt", "")
            put("SourceTag", "api_iphone")
            putAll(overrides)
        }

    private fun csv(
        rows: List<Map<String, String>>,
        columns: List<String> = EmergencyCsvParser.ALL_COLUMNS,
    ): String {
        val header = columns.joinToString(",")
        val body = rows.joinToString("\n") { r -> columns.joinToString(",") { r[it].orEmpty() } }
        return "$header\n$body\n"
    }

    private fun body(
        vararg rows: Map<String, String>,
        columns: List<String> = EmergencyCsvParser.ALL_COLUMNS,
    ): String = csv(rows.toList(), columns)

    /** Stable, order-independent rendering of a dataset. */
    private fun shape(records: List<VpnServerRecord>): List<String> =
        records.map { flatten(it).sorted().joinToString(";") }

    private fun flatten(value: Any?, path: String = ""): List<String> = when (value) {
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .flatMap { flatten(it.value, if (path.isEmpty()) it.key.toString() else "$path.${it.key}") }

        is List<*> -> value.flatMapIndexed { i, v -> flatten(v, "$path[$i]") }
        else -> listOf("$path=$value")
    }

    private fun issuesOf(result: Result): Map<Issue, Int> = when (result) {
        is Result.Valid -> result.issues
        is Result.Partial -> result.issues
        is Result.Malformed -> emptyMap()
    }

    private fun validRecords(result: Result): List<VpnServerRecord> = when (result) {
        is Result.Valid -> result.records
        is Result.Partial -> result.records
        is Result.Malformed -> throw IllegalStateException(
            "expected a usable parse, got ${result.reason}",
        )
    }

    // ── the whole §10.2 mapping ───────────────────────────────────────────

    @Test
    fun `the parser tag is the identity tag`() {
        // Two constants for the same rule, in two layers. The parser has its
        // own so it does not depend on the remote layer; this keeps them
        // from drifting apart, because a parser tag that disagrees with the
        // identity tag would split emergency records into two provenance
        // groups.
        assertEquals(SourceIdentity.EMERGENCY_TAG, EmergencyCsvParser.SOURCE_TAG)
    }

    @Test
    fun `full conforming file parses as valid and maps every column`() {
        val result = EmergencyCsvParser.parse(body(row()))

        assertTrue("expected Valid, got $result", result is Result.Valid)
        val valid = result as Result.Valid
        assertEquals(0, valid.rejectedCount)
        assertEquals(1, valid.records.size)

        val server = valid.records[0]
        val identity = VpnRecords.identity(server)
        assertEquals("public-vpn-206", identity["hostname"])
        assertEquals("219.100.37.165", identity["ip"])
        assertEquals("JP", identity["country"])
        assertEquals("Japan", identity["countryLong"])

        val perf = VpnRecords.performance(server)
        assertEquals(2785379, VpnRecords.int(perf["score"]))
        assertEquals(21.0, VpnRecords.num(perf["pingMs"]), 1e-9)
        assertEquals(659.456803, VpnRecords.num(perf["speedMbps"]), 1e-9) // bps -> Mbps
        assertEquals(52, VpnRecords.int(perf["sessions"]))
        assertEquals(93.0, VpnRecords.num(perf["uptimeDays"]), 1e-9) // ms -> days
        assertEquals(15699569, VpnRecords.int(perf["totalUsers"]))
        assertEquals(
            633908788074369.0 / (1024.0 * 1024.0 * 1024.0),
            VpnRecords.num(perf["totalTrafficGB"]),
            1e-6 * (633908788074369.0 / (1024.0 * 1024.0 * 1024.0)),
        ) // bytes -> GB

        assertEquals("2weeks", VpnRecords.map(server["logging"])["policy"])
        assertEquals("Daiyuu Nobori", VpnRecords.map(server["operator"])["name"])
        // Empty optional column: not set, never an empty string.
        assertEquals("", VpnRecords.map(server["operator"])["message"])

        val openvpn = VpnRecords.protocol(server, "openvpn")
        assertTrue(VpnRecords.bool(openvpn["supported"]))
        assertTrue(VpnRecords.bool(openvpn["configAvailable"]))
        assertEquals(1, (openvpn["configs"] as List<*>).size)
        assertTrue(VpnRecords.bool(VpnRecords.transport(openvpn, "tcp")["supported"]))
        assertEquals(443, VpnUtil.toInt(VpnRecords.transport(openvpn, "tcp")["port"]))
        // OpenVPN_UDP_Port is 0 -> no UDP evidence, and 1194 is never guessed.
        assertFalse(VpnRecords.bool(VpnRecords.transport(openvpn, "udp")["supported"]))
        assertNull(VpnRecords.transport(openvpn, "udp")["port"])

        val softether = VpnRecords.protocol(server, "softether")
        assertTrue(VpnRecords.bool(VpnRecords.transport(softether, "tcp")["supported"]))
        assertEquals(443, VpnUtil.toInt(VpnRecords.transport(softether, "tcp")["port"]))
        // SE_UDP_Supported is false, so the SE_UDP_Port of 0 stays invisible.
        assertFalse(VpnRecords.bool(VpnRecords.transport(softether, "udp")["supported"]))
        assertNull(VpnRecords.transport(softether, "udp")["port"])

        val sstp = VpnRecords.protocol(server, "sstp")
        assertTrue(VpnRecords.bool(sstp["supported"]))
        assertEquals("sstp.example.com", sstp["hostname"])
        assertEquals(443, VpnUtil.toInt(sstp["port"]))

        assertFalse(VpnRecords.bool(VpnRecords.protocol(server, "l2tpIpsec")["supported"]))

        // Provenance: the emergency tag, never api/html (§3).
        assertEquals(listOf("emergency"), VpnRecords.sources(server))
        assertEquals(listOf("emergency"), VpnRecords.fieldSources(server)["identity.ip"])
        assertEquals(
            "api_iphone",
            (server["emergency"] as Map<*, *>)["csvSourceTag"],
        )
    }

    @Test
    fun `header order is authoritative, not positional`() {
        // §10.9 Q2 leaves column order open; the header row decides.
        val columns = listOf("IP", "HostName", "SourceTag", "schema_version", "SE_TCP_Port")
        val values = mapOf(
            "IP" to "1.2.3.4",
            "HostName" to "h.example.com",
            "SourceTag" to "html_parse",
            "schema_version" to "1.0",
            "SE_TCP_Port" to "5555",
        )
        val text = columns.joinToString(",") + "\n" +
            columns.joinToString(",") { values.getValue(it) } + "\n"

        val records = validRecords(EmergencyCsvParser.parse(text))

        assertEquals(1, records.size)
        val identity = VpnRecords.identity(records[0])
        assertEquals("1.2.3.4", identity["ip"])
        assertEquals("h.example.com", identity["hostname"])
        assertEquals(5555, VpnUtil.toInt(VpnRecords.transport(VpnRecords.protocol(records[0], "softether"), "tcp")["port"]))
        assertEquals("html_parse", (records[0]["emergency"] as Map<*, *>)["csvSourceTag"])
    }

    @Test
    fun `unknown header column is carried and ignored`() {
        val columns = EmergencyCsvParser.ALL_COLUMNS + "PublisherNote"
        val text = csv(listOf(row("PublisherNote" to "hello")), columns)

        val result = EmergencyCsvParser.parse(text)

        assertTrue(result is Result.Valid)
        assertEquals(1, issuesOf(result)[Issue.UNKNOWN_COLUMN])
    }

    // ── fail-closed structure ─────────────────────────────────────────────

    @Test
    fun `empty body is malformed`() {
        assertTrue(EmergencyCsvParser.parse("") is Result.Malformed)
        assertTrue(EmergencyCsvParser.parse("   \n\t\n") is Result.Malformed)
    }

    @Test
    fun `header only file is malformed`() {
        val result = EmergencyCsvParser.parse(csv(emptyList()))

        assertTrue("expected Malformed, got $result", result is Result.Malformed)
        assertEquals("header only: no data rows", (result as Result.Malformed).reason)
    }

    @Test
    fun `malformed result carries no records`() {
        val result = EmergencyCsvParser.parse("")

        assertEquals(emptyList<VpnServerRecord>(), EmergencyCsvParser.recordsOrEmpty(result))
    }

    @Test
    fun `missing required column is malformed`() {
        for (required in EmergencyCsvParser.REQUIRED_COLUMNS) {
            val columns = EmergencyCsvParser.ALL_COLUMNS.filter { it != required }
            val result = EmergencyCsvParser.parse(body(row(), columns = columns))

            assertTrue("$required must be required", result is Result.Malformed)
            assertTrue((result as Result.Malformed).reason.contains(required))
        }
    }

    @Test
    fun `utf-8 BOM is malformed`() {
        // §10.7: UTF-8 without BOM. A BOM would otherwise silently corrupt
        // the first header name.
        val result = EmergencyCsvParser.parse("\uFEFF" + body(row()))

        assertTrue(result is Result.Malformed)
        assertTrue((result as Result.Malformed).reason.contains("BOM"))
    }

    @Test
    fun `non utf-8 body is malformed`() {
        // The fetcher hands over a decoded String, so a broken byte stream
        // is observable as the decoder's substitution character.
        val text = body(row()).replaceFirst("public-vpn-206", "public-vpn-\uFFFD206")
        val result = EmergencyCsvParser.parse(text)

        assertTrue(result is Result.Malformed)
        assertTrue((result as Result.Malformed).reason.contains("UTF-8"))
    }

    @Test
    fun `unsupported schema version fails closed`() {
        for (declared in listOf("1.1", "2.0", "0.9", "1.00", "")) {
            val result = EmergencyCsvParser.parse(body(row("schema_version" to declared)))

            assertTrue("\"$declared\" must fail closed", result is Result.Malformed)
            assertTrue((result as Result.Malformed).reason.contains("schema_version"))
        }
    }

    @Test
    fun `every row is checked for the schema token`() {
        // A single non-conforming row rejects the whole body, even when the
        // other rows are fine — §3.1 fail-closed, not row-dropping.
        val text = body(row(), row("IP" to "8.8.8.8", "schema_version" to "9.9"))

        assertTrue(EmergencyCsvParser.parse(text) is Result.Malformed)
    }

    // ── row-level rejection and partial import ────────────────────────────

    @Test
    fun `row with wrong column count is rejected while the rest import`() {
        val header = EmergencyCsvParser.ALL_COLUMNS.joinToString(",")
        val good = EmergencyCsvParser.ALL_COLUMNS.joinToString(",") { row().getValue(it) }
        val short = good.substringBeforeLast(",")
        val long = "$good,stray"
        val text = "$header\n$short\n$good\n$long\n"

        val result = EmergencyCsvParser.parse(text)

        assertTrue("expected Partial, got $result", result is Result.Partial)
        val partial = result as Result.Partial
        assertEquals(1, partial.records.size)
        assertEquals(2, partial.rejectedCount)
        assertEquals(2, partial.issues[Issue.WRONG_COLUMN_COUNT])
    }

    @Test
    fun `invalid ipv4 rejects the row`() {
        val result = EmergencyCsvParser.parse(body(row("IP" to "219.100.37")))

        assertTrue(result is Result.Malformed) // the only row failed
        assertEquals("no record passed required-field validation", (result as Result.Malformed).reason)
    }

    @Test
    fun `empty hostname rejects the row`() {
        // A row that fails on HostName AND a row that fails on IP: both are
        // counted, and neither survives.
        val result = EmergencyCsvParser.parse(
            body(row("HostName" to "  "), row("IP" to "10.0.0.1", "HostName" to "")),
        )

        assertTrue(result is Result.Malformed)
    }

    @Test
    fun `invalid rows are dropped and valid rows survive`() {
        val result = EmergencyCsvParser.parse(
            body(
                row("IP" to "219.100.37"),
                row("IP" to "8.8.8.8"),
            ),
        )

        assertTrue("expected Partial, got $result", result is Result.Partial)
        val partial = result as Result.Partial
        assertEquals(1, partial.records.size)
        assertEquals("8.8.8.8", VpnRecords.str(VpnRecords.identity(partial.records[0])["ip"]))
        assertEquals(1, partial.rejectedCount)
        assertEquals(1, partial.issues[Issue.INVALID_IPV4])
    }

    // ── optional-column degradation (§10.4) ───────────────────────────────

    @Test
    fun `out of range port becomes not present and is noted`() {
        val result = EmergencyCsvParser.parse(
            body(row("SE_TCP_Port" to "70000", "SSTP_Port" to "abc")),
        )

        assertTrue(result is Result.Valid)
        val records = validRecords(result)
        val softether = VpnRecords.protocol(records[0], "softether")
        assertFalse(VpnRecords.bool(VpnRecords.transport(softether, "tcp")["supported"]))
        assertNull(VpnRecords.transport(softether, "tcp")["port"])
        assertNull(VpnRecords.protocol(records[0], "sstp")["port"])
        assertEquals(2, issuesOf(result)[Issue.INVALID_PORT])
    }

    @Test
    fun `declared softether udp support with a port is a transport`() {
        val result = EmergencyCsvParser.parse(
            body(row("SE_UDP_Supported" to "true", "SE_UDP_Port" to "1194")),
        )

        val softether = VpnRecords.protocol(validRecords(result)[0], "softether")
        assertTrue(VpnRecords.bool(VpnRecords.transport(softether, "udp")["supported"]))
        assertEquals(1194, VpnUtil.toInt(VpnRecords.transport(softether, "udp")["port"]))
        assertEquals(
            "EXPLICIT",
            VpnRecords.transport(softether, "udp")["portSource"],
        )
    }

    @Test
    fun `published SE UDP port without declared support is not a transport`() {
        // The RUDP warning at §10.3, verbatim from RUDP_IMPLEMENTATION_PLAN:
        // SE_UDP_Port must never be read as proof of a usable SoftEther
        // UDP/R-UDP path, and it must not collapse OpenVPN_UDP_Port either.
        val result = EmergencyCsvParser.parse(
            body(row("SE_UDP_Port" to "1194", "SE_UDP_Supported" to "false")),
        )

        assertTrue(result is Result.Valid)
        val softether = VpnRecords.protocol(validRecords(result)[0], "softether")
        assertFalse(VpnRecords.bool(VpnRecords.transport(softether, "udp")["supported"]))
        assertNull(VpnRecords.transport(softether, "udp")["port"])
        assertEquals(1, issuesOf(result)[Issue.UDP_PORT_WITHOUT_DECLARED_SUPPORT])
    }

    @Test
    fun `undecodable openvpn config keeps the record without openvpn`() {
        // §10.4: an optional-decode failure retains the record and marks the
        // capability unsupported — the row's other protocols still count.
        val result = EmergencyCsvParser.parse(
            body(
                row(
                    "OpenVPN_ConfigData_Base64" to "@@@@",
                    "OpenVPN_TCP_Port" to "0",
                ),
            ),
        )

        assertTrue("expected Valid, got $result", result is Result.Valid)
        val server = validRecords(result)[0]
        val openvpn = VpnRecords.protocol(server, "openvpn")
        assertFalse(VpnRecords.bool(openvpn["supported"]))
        assertFalse(VpnRecords.bool(openvpn["configAvailable"]))
        assertTrue((openvpn["configs"] as List<*>).isEmpty())
        assertNull(openvpn["rawConfig"])
        // The row itself survived, and its SoftEther facts with it.
        assertTrue(
            VpnRecords.bool(
                VpnRecords.transport(VpnRecords.protocol(server, "softether"), "tcp")["supported"],
            ),
        )
        assertEquals(1, issuesOf(result)[Issue.OPENVPN_CONFIG_UNDECODABLE])
    }

    @Test
    fun `country short that is not two letters is dropped`() {
        val result = EmergencyCsvParser.parse(body(row("CountryShort" to "JPN")))

        assertTrue(result is Result.Valid)
        assertEquals("", VpnRecords.str(VpnRecords.identity(validRecords(result)[0])["country"]))
        assertEquals(1, issuesOf(result)[Issue.COUNTRY_SHORT_NOT_ISO])
    }

    @Test
    fun `unknown source tag defaults to html_parse`() {
        val result = EmergencyCsvParser.parse(body(row("SourceTag" to "trust_me")))

        assertTrue(result is Result.Valid)
        assertEquals(
            "html_parse",
            (validRecords(result)[0]["emergency"] as Map<*, *>)["csvSourceTag"],
        )
        assertEquals(1, issuesOf(result)[Issue.UNKNOWN_SOURCE_TAG])
    }

    @Test
    fun `non boolean token is treated as false and noted`() {
        val result = EmergencyCsvParser.parse(body(row("SSTP_Supported" to "yes")))

        assertTrue(result is Result.Valid)
        assertFalse(VpnRecords.bool(VpnRecords.protocol(validRecords(result)[0], "sstp")["supported"]))
        assertEquals(1, issuesOf(result)[Issue.NON_BOOLEAN_TOKEN])
    }

    @Test
    fun `non numeric optional columns are left unset`() {
        val result = EmergencyCsvParser.parse(body(row("Score" to "high", "Ping" to "n/a")))

        assertTrue(result is Result.Valid)
        val perf = VpnRecords.performance(validRecords(result)[0])
        assertEquals(0, VpnRecords.int(perf["score"]))
        assertEquals(0.0, VpnRecords.num(perf["pingMs"]), 1e-9)
        assertEquals(2, issuesOf(result)[Issue.NON_NUMERIC_VALUE])
    }

    @Test
    fun `sstp hostname port suffix is reconciled with the port column`() {
        // Explicit column wins over a conflicting suffix (§10.3).
        val withColumn = validRecords(EmergencyCsvParser.parse(body(row("SSTP_Hostname" to "vpn.example.com:8443"))))
        assertEquals("vpn.example.com", VpnRecords.protocol(withColumn[0], "sstp")["hostname"])
        assertEquals(443, VpnUtil.toInt(VpnRecords.protocol(withColumn[0], "sstp")["port"]))

        // No column: the suffix is the port.
        val withSuffix = validRecords(
            EmergencyCsvParser.parse(body(row("SSTP_Hostname" to "vpn.example.com:8443", "SSTP_Port" to "0"))),
        )
        assertEquals("vpn.example.com", VpnRecords.protocol(withSuffix[0], "sstp")["hostname"])
        assertEquals(8443, VpnUtil.toInt(VpnRecords.protocol(withSuffix[0], "sstp")["port"]))
    }

    // ── identity, dedup and merge (§10.5) ─────────────────────────────────

    @Test
    fun `duplicate identity is merged and tagged merged`() {
        val first = row("SourceTag" to "api_iphone")
        val second = row(
            "SourceTag" to "html_parse",
            "L2TP_Supported" to "true",
            "Message" to "banner",
        )

        val result = EmergencyCsvParser.parse(body(first, second))

        assertTrue("expected Valid, got $result", result is Result.Valid)
        val records = (result as Result.Valid).records
        assertEquals(1, records.size)

        val server = records[0]
        assertEquals("merged", (server["emergency"] as Map<*, *>)["csvSourceTag"])
        // Gap filled from the second row.
        assertTrue(VpnRecords.bool(VpnRecords.protocol(server, "l2tpIpsec")["supported"]))
        assertEquals("banner", VpnRecords.map(server["operator"])["message"])
        // First row's facts survive.
        assertEquals(659.456803, VpnRecords.num(VpnRecords.performance(server)["speedMbps"]), 1e-9)
    }

    @Test
    fun `duplicate hostname on a different ip is not merged`() {
        val result = EmergencyCsvParser.parse(body(row(), row("IP" to "8.8.8.8")))

        assertEquals(2, validRecords(result).size)
    }

    @Test
    fun `hostname port suffix does not defeat dedup`() {
        val result = EmergencyCsvParser.parse(
            body(row(), row("HostName" to "public-vpn-206:443", "SourceTag" to "html_parse")),
        )

        val records = validRecords(result)
        assertEquals(1, records.size)
        assertEquals("merged", (records[0]["emergency"] as Map<*, *>)["csvSourceTag"])
    }

    @Test
    fun `emergency records never outrank canonical in a merge`() {
        // The merger's api > html > … policy must hold for the emergency
        // group too, which is why SourceIdentity gives it its own tag.
        val apiRecords = VpnGateApiParser.parseApi(CollectorTestFixtures.makeApiRow(), "api")
        val emergencyRecords = validRecords(
            EmergencyCsvParser.parse(body(row("Score" to "999999"))),
        )

        val merged = VpnServerMerger.mergeRecords(apiRecords + emergencyRecords)

        assertEquals(1, merged.size)
        val server = merged[0]
        assertEquals(2785379, VpnRecords.int(VpnRecords.performance(server)["score"]))
        assertEquals(listOf("api", "emergency"), VpnRecords.sources(server))
        // The emergency value is not silently dropped: it is a visible
        // disagreement, recorded by the merger.
        assertTrue(VpnRecords.conflicts(server).isNotEmpty())
    }

    // ── line endings and quoting (§10.7) ──────────────────────────────────

    @Test
    fun `crlf and lf bodies parse identically`() {
        val lf = body(row())
        val crlf = lf.replace("\n", "\r\n")

        assertEquals(
            shape(validRecords(EmergencyCsvParser.parse(lf))),
            shape(validRecords(EmergencyCsvParser.parse(crlf))),
        )
    }

    @Test
    fun `trailing newline does not change the result`() {
        val withNewline = body(row())
        val without = withNewline.trimEnd('\n')

        assertEquals(
            shape(validRecords(EmergencyCsvParser.parse(withNewline))),
            shape(validRecords(EmergencyCsvParser.parse(without))),
        )
    }

    @Test
    fun `rfc4180 quoted comma field is a single field`() {
        val columns = EmergencyCsvParser.ALL_COLUMNS
        val values = columns.map { row().getValue(it) }.toMutableList()
        values[columns.indexOf("Operator")] = "\"Daiyuu, Nobori and Co\""
        val text = columns.joinToString(",") + "\n" + values.joinToString(",") + "\n"

        val records = validRecords(EmergencyCsvParser.parse(text))

        assertEquals(1, records.size)
        assertEquals("Daiyuu, Nobori and Co", VpnRecords.map(records[0]["operator"])["name"])
    }

    @Test
    fun `a body with no rows at all is malformed`() {
        // Header only has already been covered; this is the degenerate case
        // where the file is nothing but separators.
        val text = EmergencyCsvParser.ALL_COLUMNS.joinToString(",") + "\n,,,,\n\n"

        assertTrue(EmergencyCsvParser.parse(text) is Result.Malformed)
    }

    // ── v1.0.53 (§1.6.45): OpenVPN port authority + SSTP host:port ───────

    /**
     * TASK B.3: the endpoint the embedded config dials is authoritative.
     *
     * The reproduced case is `123.48.124.169`: the CSV column said 1534 while
     * the embedded profile dials 1558. Here the same disagreement is built from
     * the conforming fixture (config dials 443) with the column overridden.
     */
    @Test
    fun `an openvpn column that disagrees with the config loses to the config`() {
        val result = EmergencyCsvParser.parse(
            body(row("OpenVPN_TCP_Port" to "1534")),
        )
        val record = validRecords(result).single()
        val openvpn = VpnRecords.protocol(record, "openvpn")

        assertEquals(443, VpnUtil.toInt(VpnRecords.transport(openvpn, "tcp")["port"]))
        assertTrue(VpnRecords.bool(VpnRecords.transport(openvpn, "tcp")["supported"]))
        assertEquals(1, issuesOf(result)[Issue.OPENVPN_PORT_CONFLICT_CONFIG_WINS])
    }

    /**
     * TASK B.3 + TASK A: a port with no config bytes is kept as a fact but
     * never claimed as a usable transport.
     *
     * This is the row shape Auto Connect used to select and then wait out: the
     * publisher strips configs to stay under the 1 MiB body cap, which is true
     * of 209 of the 271 rows in the shipped snapshot (77%).
     */
    @Test
    fun `an openvpn port without a config keeps the port but claims no support`() {
        val result = EmergencyCsvParser.parse(
            body(
                row(
                    "OpenVPN_ConfigData_Base64" to "",
                    "OpenVPN_TCP_Port" to "1534",
                    "OpenVPN_UDP_Port" to "1278",
                ),
            ),
        )
        val record = validRecords(result).single()
        val openvpn = VpnRecords.protocol(record, "openvpn")

        // The facts survive verbatim...
        assertEquals(1534, VpnUtil.toInt(VpnRecords.transport(openvpn, "tcp")["port"]))
        assertEquals(1278, VpnUtil.toInt(VpnRecords.transport(openvpn, "udp")["port"]))
        // ...but nothing is dialable without the profile bytes.
        assertFalse(VpnRecords.bool(VpnRecords.transport(openvpn, "tcp")["supported"]))
        assertFalse(VpnRecords.bool(VpnRecords.transport(openvpn, "udp")["supported"]))
        assertFalse(VpnRecords.bool(openvpn["configAvailable"]))
        assertEquals(2, issuesOf(result)[Issue.OPENVPN_PORT_WITHOUT_CONFIG])
    }

    /**
     * TASK B.4: `SSTP_Hostname` may carry a `:port` suffix when the port is not
     * 443, with the `SSTP_Port` column left at 0.
     *
     * The values are the real historical row (`123.200.94.11`,
     * `vpn943218848.opengw.net:1956`, `SSTP_Port` 0, `SE_TCP_Port` 1956) taken
     * from `emergency-sources/servers.csv` at commit 0387d37. It is no longer
     * present in the shipped snapshot, which is exactly why it is pinned here.
     */
    @Test
    fun `an sstp hostname carrying a port is split into host and port`() {
        val result = EmergencyCsvParser.parse(
            body(
                row(
                    "HostName" to "vpn943218848",
                    "IP" to "123.200.94.11",
                    "SSTP_Hostname" to "vpn943218848.opengw.net:1956",
                    "SSTP_Port" to "0",
                    "SE_TCP_Port" to "1956",
                ),
            ),
        )
        val record = validRecords(result).single()
        val sstp = VpnRecords.protocol(record, "sstp")

        assertEquals("vpn943218848.opengw.net", sstp["hostname"])
        assertEquals(1956, VpnUtil.toInt(sstp["port"]))
        assertEquals(VpnPortSource.EXPLICIT, sstp["portSource"])
    }

    /**
     * TASK B.4: the explicit column wins when both forms are present.
     */
    @Test
    fun `an explicit sstp port column wins over the hostname suffix`() {
        val result = EmergencyCsvParser.parse(
            body(
                row(
                    "SSTP_Hostname" to "vpn943218848.opengw.net:1956",
                    "SSTP_Port" to "443",
                ),
            ),
        )
        val record = validRecords(result).single()
        val sstp = VpnRecords.protocol(record, "sstp")

        assertEquals("vpn943218848.opengw.net", sstp["hostname"])
        assertEquals(443, VpnUtil.toInt(sstp["port"]))
    }

    /**
     * TASK B.4: a plain hostname is untouched, and the documented 443 default
     * still applies rather than a port being invented here.
     */
    @Test
    fun `a plain sstp hostname is left alone`() {
        val result = EmergencyCsvParser.parse(
            body(row("SSTP_Hostname" to "vpn1.opengw.net", "SSTP_Port" to "0")),
        )
        val record = validRecords(result).single()
        val sstp = VpnRecords.protocol(record, "sstp")

        assertEquals("vpn1.opengw.net", sstp["hostname"])
        assertNull(sstp["port"])
    }

    /**
     * TASK A, the operator's "merged API + emergency rows" case.
     *
     * The emergency row is the stripped shape (port column, no config); the API
     * row carries the profile, whose `remote` line dials 1558 while the
     * emergency column said 1534. After the merge the connection must have BOTH
     * the bytes and the config's port, and the candidate built from it must be
     * usable — that is the whole chain the gate depends on.
     */
    @Test
    fun `a merged api and emergency row is usable for auto connect`() {
        val emergency = validRecords(
            EmergencyCsvParser.parse(
                body(row("OpenVPN_ConfigData_Base64" to "", "OpenVPN_TCP_Port" to "1534")),
            ),
        ).single()

        val cfg = "# OpenVPN\nclient\nproto tcp\nremote 219.100.37.165 1558\n"
        val api = VpnGateApiParser.parseApi(
            "*vpn_servers\n" + CollectorTestFixtures.makeApiRow(
                mapOf("OpenVPN_ConfigData_Base64" to b64(cfg)),
            ),
            "api",
        )[0]

        val merged = VpnServerMerger.validateServers(
            VpnServerMerger.mergeRecords(listOf(emergency, api)),
        ).single()

        val conn = VpnConnectionMapper.toConnection(merged)!!
        assertEquals(1558, conn.tcpPort)          // the config's endpoint won
        assertNotNull(conn.openVpnConfigData)     // and the bytes survived

        val candidate = with(AutoModeEngine) { conn.toCandidate() }
        assertTrue(candidate.openVpnConfigAvailable)
        assertEquals(1558, candidate.openVpnTcpPort)
    }
}
