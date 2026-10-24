package vn.unlimit.vpngate.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.parser.EmergencyCsvParser

/**
 * v1.0.54 (§1.6.49) PART 4 — [ManualCsvImport], the CSV-import parser that
 * replaced the manual add-server form.
 *
 * Pure JVM: a String in, a [ManualCsvImport.Result] out. No file IO, no
 * network, no Android framework, no clock — `now` is a parameter precisely so
 * these assertions can be exact.
 *
 * The two dialects under test are the ones the app already owns
 * (`SERVER_SOURCE_ARCHITECTURE.md` §10 extended CSV, and the VPN Gate
 * `/api/iphone/` CSV). This class deliberately does not re-assert their column
 * semantics — `EmergencyCsvParserTest` and the API oracle tests own that. What
 * it asserts is the *new* contract: detection, transport repair, the two hard
 * caps, duplicate collapsing, and the failure taxonomy the Settings screen
 * renders.
 */
class ManualCsvImportTest {

    private val now = 1_700_000_000_000L

    private fun b64(body: String): String =
        java.util.Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))

    private val configBody = "# sample\ndev tun\nproto tcp\nremote 219.100.37.165 443\n"

    // ── fixtures ─────────────────────────────────────────────────────────

    /**
     * One §10.2 row as a name→value map, so a test can override a single
     * column without restating 29 of them.
     */
    private fun appRow(vararg overrides: Pair<String, String>): Map<String, String> =
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
            put("OpenVPN_ConfigData_Base64", b64(configBody))
            put("schema_version", "1.0")
            put("SE_TCP_Port", "443")
            put("SE_UDP_Supported", "true")
            put("SE_UDP_Port", "500")
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

    private fun appCsv(vararg rows: Map<String, String>): String {
        val columns = EmergencyCsvParser.ALL_COLUMNS
        val header = columns.joinToString(",")
        val body = rows.joinToString("\n") { r -> columns.joinToString(",") { r[it].orEmpty() } }
        return "$header\n$body\n"
    }

    /** One API row, positional, matching the feed's canonical 15 columns. */
    private fun apiRow(
        host: String = "public-vpn-219",
        ip: String = "219.100.37.206",
    ): String = listOf(
        host, ip, "3036875", "13", "165728545", "Japan", "JP", "251",
        "8126753049", "2423320", "205069145915732", "2weeks",
        "Daiyuu Nobori_ Japan. Academic Use Only.", "", b64(configBody),
    ).joinToString(",")

    private val apiHeader =
        "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions," +
            "Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64"

    private fun apiCsv(vararg rows: String): String =
        "$apiHeader\n" + rows.joinToString("\n") + "\n"

    private fun success(result: ManualCsvImport.Result): ManualCsvImport.Result.Success {
        assertTrue("expected Success, got $result", result is ManualCsvImport.Result.Success)
        return result as ManualCsvImport.Result.Success
    }

    private fun failure(result: ManualCsvImport.Result): ManualCsvImport.Result.Failure {
        assertTrue("expected Failure, got $result", result is ManualCsvImport.Result.Failure)
        return result as ManualCsvImport.Result.Failure
    }

    // ── normalisation ────────────────────────────────────────────────────

    @Test
    fun `normalize strips a leading BOM and unifies line endings`() {
        val raw = "\uFEFFHostName,IP\r\na,b\rc,d\r\n"
        assertEquals("HostName,IP\na,b\nc,d", ManualCsvImport.normalize(raw))
    }

    @Test
    fun `normalize trims trailing horizontal whitespace but not leading`() {
        // Leading whitespace is preserved: a quoted field may legitimately
        // begin with a space, and trimming it would silently alter data.
        assertEquals("  a,b\nc,d", ManualCsvImport.normalize("  a,b  \nc,d\t\n"))
    }

    @Test
    fun `normalize leaves an already clean body byte-identical`() {
        val clean = appCsv(appRow())
        assertEquals(clean.trim('\n'), ManualCsvImport.normalize(clean))
    }

    // ── detection ────────────────────────────────────────────────────────

    @Test
    fun `detect names the extended app dialect`() {
        assertEquals(ManualCsvImport.Format.APP_CSV, ManualCsvImport.detect(appCsv(appRow())))
    }

    @Test
    fun `detect names the vpn gate api dialect`() {
        assertEquals(ManualCsvImport.Format.VPN_GATE_API, ManualCsvImport.detect(apiCsv(apiRow())))
    }

    @Test
    fun `detect sees past the api section marker`() {
        // The live feed opens with `*vpn_servers` before the `#HostName,...`
        // header. Treating the first non-blank line as the header would call
        // this UNKNOWN and refuse a perfectly good file.
        val withMarker = "*vpn_servers\n" + apiCsv(apiRow())
        assertEquals(ManualCsvImport.Format.VPN_GATE_API, ManualCsvImport.detect(withMarker))
    }

    @Test
    fun `detect refuses a headerless blob`() {
        assertEquals(ManualCsvImport.Format.UNKNOWN, ManualCsvImport.detect("hello world"))
    }

    @Test
    fun `detect refuses an empty body`() {
        assertEquals(ManualCsvImport.Format.UNKNOWN, ManualCsvImport.detect(""))
    }

    @Test
    fun `an extended file is not mistaken for a plain api file`() {
        // Both headers start with HostName and carry the API's 15 columns;
        // `schema_version` is the only discriminator, and it must win.
        val extended = appCsv(appRow())
        assertTrue(extended.contains("schema_version"))
        assertEquals(ManualCsvImport.Format.APP_CSV, ManualCsvImport.detect(extended))
    }

    // ── happy paths ──────────────────────────────────────────────────────

    @Test
    fun `a conforming extended file yields one row with its ports`() {
        val result = success(ManualCsvImport.parse(appCsv(appRow()), now))

        assertEquals(ManualCsvImport.Format.APP_CSV, result.format)
        assertEquals(1, result.items.size)
        assertEquals(0, result.rejectedCount)

        val item = result.items.single()
        assertEquals("public-vpn-206", item.hostName)
        assertEquals("219.100.37.165", item.ip)
        assertEquals(443, item.seTcpPort)
        assertEquals(500, item.seUdpPort)
        assertTrue(item.seUdpSupported)
        assertTrue(item.isSSTPSupport)
    }

    @Test
    fun `a conforming api file yields one row`() {
        val result = success(ManualCsvImport.parse(apiCsv(apiRow()), now))

        assertEquals(ManualCsvImport.Format.VPN_GATE_API, result.format)
        assertEquals(1, result.items.size)
        assertEquals("public-vpn-219", result.items.single().hostName)
        assertEquals("219.100.37.206", result.items.single().ip)
    }

    @Test
    fun `an api file with its section marker parses instead of being refused`() {
        val result = success(ManualCsvImport.parse("*vpn_servers\n" + apiCsv(apiRow()), now))
        assertEquals(1, result.items.size)
    }

    @Test
    fun `every accepted row carries the injected timestamp`() {
        val result = success(ManualCsvImport.parse(appCsv(appRow()), now))
        val item = result.items.single()
        assertEquals(now, item.firstSeenAt)
        assertEquals(now, item.lastSeenAt)
        assertEquals(0, item.healthScore)
    }

    @Test
    fun `parsing the same text twice yields the same rows`() {
        val text = appCsv(appRow())
        assertEquals(
            success(ManualCsvImport.parse(text, now)).items,
            success(ManualCsvImport.parse(text, now)).items,
        )
    }

    @Test
    fun `a BOM and CRLF pasted from a messenger still parse`() {
        // This is the actual transport shape: a messenger copy adds CRLF and
        // some editors re-add the BOM. Neither may cost the user their file.
        val text = "\uFEFF" + appCsv(appRow()).replace("\n", "\r\n")
        val result = success(ManualCsvImport.parse(text, now))
        assertEquals(1, result.items.size)
    }

    // ── transport repair ─────────────────────────────────────────────────

    @Test
    fun `a hard wrapped api row is re-joined`() {
        // SMS splits a long row mid-field. Without repair the second half is
        // a short, unparseable line and the server is lost.
        val whole = apiRow()
        val cut = whole.length / 2
        val wrapped = apiHeader + "\n" + whole.substring(0, cut) + "\n" + whole.substring(cut) + "\n"

        val result = success(ManualCsvImport.parse(wrapped, now))
        assertEquals(1, result.items.size)
        assertEquals("public-vpn-219", result.items.single().hostName)
    }

    @Test
    fun `two complete rows are never glued together`() {
        val result = success(
            ManualCsvImport.parse(
                apiCsv(apiRow(host = "a.example", ip = "203.0.113.1"), apiRow(host = "b.example", ip = "203.0.113.2")),
                now,
            ),
        )
        assertEquals(2, result.items.size)
    }

    @Test
    fun `joinWrappedRows leaves a single complete row alone`() {
        val one = apiHeader + "\n" + apiRow()
        assertEquals(one, ManualCsvImport.joinWrappedRows(one))
    }

    @Test
    fun `joinWrappedRows preserves the section marker`() {
        val marked = "*vpn_servers\n" + apiHeader + "\n" + apiRow()
        assertTrue(ManualCsvImport.joinWrappedRows(marked).startsWith("*vpn_servers"))
    }

    // ── duplicates ───────────────────────────────────────────────────────

    @Test
    fun `a repeated host collapses to one row`() {
        val result = success(
            ManualCsvImport.parse(
                appCsv(appRow("IP" to "203.0.113.1"), appRow("IP" to "203.0.113.2")),
                now,
            ),
        )
        assertEquals(1, result.items.size)
        // Last write wins, which is also what ManualImportStore does — one
        // rule for the same collision, in both layers.
        assertEquals("203.0.113.2", result.items.single().ip)
    }

    @Test
    fun `re-importing the same text does not grow the row set`() {
        val text = appCsv(appRow())
        val first = success(ManualCsvImport.parse(text, now)).items.size
        val second = success(ManualCsvImport.parse(text, now)).items.size
        assertEquals(first, second)
    }

    // ── failure taxonomy ─────────────────────────────────────────────────

    @Test
    fun `an empty body fails as EMPTY`() {
        assertEquals(ManualCsvImport.Reason.EMPTY, failure(ManualCsvImport.parse("", now)).reason)
    }

    @Test
    fun `a whitespace-only body fails as EMPTY`() {
        assertEquals(
            ManualCsvImport.Reason.EMPTY,
            failure(ManualCsvImport.parse("  \n\n\t\n ", now)).reason,
        )
    }

    @Test
    fun `an unrecognised body fails as UNRECOGNIZED`() {
        assertEquals(
            ManualCsvImport.Reason.UNRECOGNIZED,
            failure(ManualCsvImport.parse("this is not a server list at all", now)).reason,
        )
    }

    @Test
    fun `binary garbage fails without throwing`() {
        // A renamed .jpg reaches here as decoded text with replacement
        // characters. It must produce a Failure, never an exception: this runs
        // inside a coroutine on the Settings screen.
        val binary = String(ByteArray(512) { (it % 251).toByte() }, Charsets.ISO_8859_1)
        val result = ManualCsvImport.parse(binary, now)
        assertTrue(result is ManualCsvImport.Result.Failure)
    }

    @Test
    fun `an input over the byte cap fails as TOO_LARGE`() {
        val padding = "x".repeat(ManualCsvImport.MAX_BYTES + 1)
        val result = failure(ManualCsvImport.parse(padding, now))
        assertEquals(ManualCsvImport.Reason.TOO_LARGE, result.reason)
    }

    @Test
    fun `an input exactly at the byte cap is not refused for size`() {
        // The cap is inclusive; an off-by-one here would reject a file that
        // the UI's own help text says is acceptable.
        val header = EmergencyCsvParser.ALL_COLUMNS.joinToString(",")
        val base = appCsv(appRow())
        val padding = " ".repeat(ManualCsvImport.MAX_BYTES - base.toByteArray().size)
        val text = base + padding
        assertEquals(ManualCsvImport.MAX_BYTES, text.toByteArray(Charsets.UTF_8).size)
        val result = ManualCsvImport.parse(text, now)
        assertTrue("expected a size-accepted result, got $result", result !is ManualCsvImport.Result.Failure ||
            result.reason != ManualCsvImport.Reason.TOO_LARGE)
        assertTrue(header.isNotEmpty())
    }

    @Test
    fun `more than MAX_ROWS data rows fails as TOO_MANY_ROWS`() {
        val rows = (1..(ManualCsvImport.MAX_ROWS + 1)).map { i ->
            appRow("HostName" to "host-$i", "IP" to "203.0.113.${(i % 254) + 1}")
        }
        val result = failure(ManualCsvImport.parse(appCsv(*rows.toTypedArray()), now))
        assertEquals(ManualCsvImport.Reason.TOO_MANY_ROWS, result.reason)
    }

    @Test
    fun `exactly MAX_ROWS data rows is accepted`() {
        val rows = (1..ManualCsvImport.MAX_ROWS).map { i ->
            appRow("HostName" to "host-$i", "IP" to "203.0.113.${(i % 254) + 1}")
        }
        val result = success(ManualCsvImport.parse(appCsv(*rows.toTypedArray()), now))
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `a header with no data rows fails rather than succeeding empty`() {
        val result = ManualCsvImport.parse(apiHeader + "\n", now)
        val f = failure(result)
        assertEquals(ManualCsvImport.Reason.NO_VALID_ROWS, f.reason)
    }

    @Test
    fun `a row with no address is reported, not silently dropped`() {
        // A row with an empty IP column cannot be dialled. The valid row must
        // survive and the loss must be counted, because a silent drop reads as
        // "the import worked".
        //
        // Note: `0.0.0.0` is NOT a usable stand-in here — it is syntactically
        // valid IPv4 and the record layer accepts it, so it would import as a
        // second row. An empty column is the case that actually loses a row.
        val result = success(
            ManualCsvImport.parse(
                appCsv(appRow("HostName" to "bad", "IP" to ""), appRow("HostName" to "good")),
                now,
            ),
        )
        assertEquals(1, result.items.size)
        assertEquals("good", result.items.single().hostName)
        assertTrue("expected a rejected count, got ${result.rejectedCount}", result.rejectedCount >= 1)
    }

    @Test
    fun `a row whose address is not ipv4 at all is rejected`() {
        val result = ManualCsvImport.parse(
            appCsv(appRow("HostName" to "bad", "IP" to "not-an-address"), appRow("HostName" to "good")),
            now,
        )
        // Either the parser rejects the row and reports a partial parse, or it
        // drops it while reporting the reason; what must never happen is a
        // silent two-row success that later fails to connect.
        when (result) {
            is ManualCsvImport.Result.Success -> {
                assertEquals(listOf("good"), result.items.map { it.hostName })
                assertTrue(result.rejectedCount >= 1)
            }
            is ManualCsvImport.Result.Failure ->
                assertEquals(ManualCsvImport.Reason.PARSE_ERROR, result.reason)
        }
    }

    @Test
    fun `reject reasons are capped at the documented maximum`() {
        val result = success(
            ManualCsvImport.parse(
                appCsv(
                    appRow("HostName" to "bad1", "IP" to ""),
                    appRow("HostName" to "bad2", "IP" to "nope"),
                    appRow("HostName" to "bad3", "IP" to "999.1.1.1"),
                    appRow("HostName" to "good"),
                ),
                now,
            ),
        )
        assertTrue(
            "expected at most ${ManualCsvImport.MAX_REPORTED_REASONS} reasons, got ${result.rejectedReasons}",
            result.rejectedReasons.size <= ManualCsvImport.MAX_REPORTED_REASONS,
        )
    }

    @Test
    fun `an unsupported schema version is refused whole`() {
        val result = ManualCsvImport.parse(appCsv(appRow("schema_version" to "9.9")), now)
        val f = failure(result)
        assertEquals(ManualCsvImport.Reason.PARSE_ERROR, f.reason)
        assertNotNull(f.detail)
    }

    // ── provenance ───────────────────────────────────────────────────────

    @Test
    fun `the import tag can never speak with canonical authority`() {
        // `api` and `html` outrank everything in the merger; an imported file
        // is user data and must lose every precedence contest.
        val tag = ManualCsvImport.ManualImportSourceTag
        assertFalse(tag == "api")
        assertFalse(tag == "html")
        assertEquals("manual", tag)
    }

    @Test
    fun `the caps are the ones the settings help text promises`() {
        assertEquals(1_048_576, ManualCsvImport.MAX_BYTES)
        assertEquals(2000, ManualCsvImport.MAX_ROWS)
    }

    // ── v1.0.59 PART B2: whitespace / control characters inside a field ──

    @Test
    fun `leading and trailing whitespace is trimmed and accepted`() {
        val result = success(
            ManualCsvImport.parse(
                appCsv(appRow("HostName" to "  public-vpn-206  ")),
                now,
            ),
        )

        assertEquals(1, result.items.size)
        assertEquals("public-vpn-206", result.items.first().hostName)
    }

    @Test
    fun `a host name with an inner space is rejected with a per-row reason`() {
        // The device stored `test-a. example` — an input method that inserts a
        // space after `.` produced a server that no DNS can resolve and that the
        // dedupe can never match.
        val result = success(
            ManualCsvImport.parse(
                appCsv(
                    appRow("HostName" to "good.example", "IP" to "203.0.113.10"),
                    appRow("HostName" to "test-a. example", "IP" to "203.0.113.11"),
                ),
                now,
            ),
        )

        assertEquals("only the clean row may be stored", 1, result.items.size)
        assertEquals("good.example", result.items.first().hostName)
        assertEquals(1, result.rejectedCount)
        assertTrue(
            "the summary must name the reason; got ${result.rejectedReasons}",
            result.rejectedReasons.any { it.contains("whitespace or control characters") },
        )
        assertTrue(
            "the summary must identify the offending row; got ${result.rejectedReasons}",
            result.rejectedReasons.any { it.contains("test-a. example") },
        )
    }

    @Test
    fun `an IP with an inner space is rejected`() {
        val result = success(
            ManualCsvImport.parse(
                appCsv(
                    appRow("HostName" to "good.example", "IP" to "203.0.113.10"),
                    appRow("HostName" to "spaced.example", "IP" to "203.0.113. 11"),
                ),
                now,
            ),
        )

        assertEquals(1, result.items.size)
        assertEquals(1, result.rejectedCount)
    }

    @Test
    fun `a control character in the host name is rejected and not echoed raw`() {
        val result = success(
            ManualCsvImport.parse(
                appCsv(
                    appRow("HostName" to "good.example", "IP" to "203.0.113.10"),
                    appRow("HostName" to "bad\u0007.example", "IP" to "203.0.113.12"),
                ),
                now,
            ),
        )

        assertEquals(1, result.items.size)
        assertEquals(1, result.rejectedCount)
        assertTrue(
            "a control character must never reach the summary verbatim",
            result.rejectedReasons.none { it.contains('\u0007') },
        )
    }

    @Test
    fun `the inner-whitespace predicate matches exactly the inner cases`() {
        assertFalse(ManualCsvImport.hasInnerWhitespaceOrControl("public-vpn-206"))
        assertFalse(ManualCsvImport.hasInnerWhitespaceOrControl("219.100.37.165"))
        assertTrue(ManualCsvImport.hasInnerWhitespaceOrControl("test-a. example"))
        assertTrue(ManualCsvImport.hasInnerWhitespaceOrControl("a\tb"))
        assertTrue(ManualCsvImport.hasInnerWhitespaceOrControl("a\u0007b"))
    }
}
