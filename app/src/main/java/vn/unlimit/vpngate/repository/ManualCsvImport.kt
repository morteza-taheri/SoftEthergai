package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.models.VPNGateItem
import vn.unlimit.vpngate.parser.EmergencyCsvParser
import vn.unlimit.vpngate.parser.VpnGateApiParser

/**
 * v1.0.54 (§1.6.49) PART 4 — the manual CSV import parser.
 *
 * ## Why this is not a third parser
 *
 * Two CSV dialects already exist in this app and both already have an owner:
 *
 *  - the app's own extended CSV (`SERVER_SOURCE_ARCHITECTURE.md` §10), owned by
 *    [EmergencyCsvParser];
 *  - the original VPN Gate API CSV, owned by [VpnGateApiParser].
 *
 * This class does only what neither of them should do for a *pasted* file:
 * detect which dialect arrived, tolerate the transport artefacts a
 * messenger/SMS paste brings (BOM, CRLF, trailing whitespace, hard-wrapped
 * lines), enforce the size/row caps, and hand the text to the owning parser.
 * It never re-implements column semantics — that would be a second, drifting
 * definition of the format.
 *
 * ## Limits (documented in the UI)
 *
 *  - 1 MiB of input text, measured in UTF-8 bytes;
 *  - 2000 data rows.
 *
 * Both are hard: an input over either cap is refused whole rather than
 * silently truncated, because a truncated server list is worse than a clear
 * error.
 *
 * ## Safety
 *
 * Pure JVM: no file IO, no network, no code execution. The caller owns the
 * text, and nothing here logs it — a pasted profile can contain a private
 * server address, so the content never reaches a log line.
 */
object ManualCsvImport {

    /** Hard cap on the input, in UTF-8 bytes. */
    const val MAX_BYTES: Int = 1_048_576

    /** Hard cap on data rows (header excluded). */
    const val MAX_ROWS: Int = 2000

    enum class Format { APP_CSV, VPN_GATE_API, UNKNOWN }

    enum class Reason { EMPTY, TOO_LARGE, TOO_MANY_ROWS, UNRECOGNIZED, NO_VALID_ROWS, PARSE_ERROR }

    sealed interface Result {
        data class Success(
            val format: Format,
            val items: List<VPNGateItem>,
            /** Human-readable reasons, at most [MAX_REPORTED_REASONS]. */
            val rejectedReasons: List<String>,
            val rejectedCount: Int,
        ) : Result

        data class Failure(val reason: Reason, val detail: String? = null) : Result
    }

    /** How many distinct reject reasons are shown to the user. */
    const val MAX_REPORTED_REASONS: Int = 3

    private const val BOM = '\uFEFF'

    /**
     * Normalises a pasted body: drops a leading BOM, unifies line endings and
     * trims trailing horizontal whitespace on each line.
     *
     * Only import uses this. The collector's parsers stay strict — a feed that
     * violates §10.7 must still be rejected there.
     */
    fun normalize(raw: String): String {
        var text = raw
        if (text.startsWith(BOM)) text = text.substring(1)
        text = text.replace("\r\n", "\n").replace('\r', '\n')
        return text.lines()
            .joinToString("\n") { line -> line.trimEnd(' ', '\t') }
            .trim('\n')
    }

    /**
     * Index of the dialect header line, or -1.
     *
     * The VPN Gate API body opens with a `*vpn_servers` section marker before
     * the real `#HostName,IP,...` header, so "the first non-blank line" is not
     * the header for that dialect. [VpnGateApiParser.findApiHeader] already
     * knows the rule (and both dialects start their header with `HostName`), so
     * this reuses it rather than defining a second, drifting notion of where a
     * header begins.
     */
    private fun headerIndex(lines: List<String>): Int =
        VpnGateApiParser.findApiHeader(lines)

    /** Which dialect the header names, or [Format.UNKNOWN]. */
    fun detect(text: String): Format {
        val lines = text.lines()
        val index = headerIndex(lines)
        if (index < 0) return Format.UNKNOWN
        val columns = lines[index].trim().removePrefix("#").split(',').map { it.trim() }
        // `schema_version` is the §10.3 marker for the extended app dialect and
        // is checked first: an extended file also carries the API's 15 columns,
        // so testing the API columns first would misclassify it.
        if (columns.contains("schema_version")) return Format.APP_CSV
        if (columns.contains("HostName") && columns.contains("IP") &&
            columns.contains("OpenVPN_ConfigData_Base64")
        ) {
            return Format.VPN_GATE_API
        }
        return Format.UNKNOWN
    }

    /**
     * Parses [raw] (pasted text or file content) into server rows.
     *
     * @param now timestamp stamped on every accepted row, injected so tests and
     *   production share one clock.
     */
    fun parse(raw: String, now: Long): Result {
        if (raw.isBlank()) return Result.Failure(Reason.EMPTY)
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return Result.Failure(Reason.TOO_LARGE)
        }
        val text = normalize(raw)
        if (text.isBlank()) return Result.Failure(Reason.EMPTY)

        val format = detect(text)
        if (format == Format.UNKNOWN) return Result.Failure(Reason.UNRECOGNIZED)

        val joined = joinWrappedRows(text)
        val lines = joined.lines()
        val start = headerIndex(lines)
        if (start < 0) return Result.Failure(Reason.UNRECOGNIZED)
        // Anything before the header (`*vpn_servers`, stray blank lines, a
        // quoted "here is the file I promised" line from a messenger) is
        // transport noise, not data; both parsers expect the body to begin at
        // the header, so the slice happens here once rather than in each.
        val body = lines.subList(start, lines.size).joinToString("\n")

        val dataRows = body.lines().count { it.isNotBlank() } - 1
        if (dataRows > MAX_ROWS) return Result.Failure(Reason.TOO_MANY_ROWS)

        return when (format) {
            Format.APP_CSV -> parseAppCsv(body, now)
            Format.VPN_GATE_API -> parseApiCsv(body, dataRows, now)
            Format.UNKNOWN -> Result.Failure(Reason.UNRECOGNIZED)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Dialect adapters
    // ─────────────────────────────────────────────────────────────────────

    private fun parseAppCsv(text: String, now: Long): Result {
        val parsed = EmergencyCsvParser.parse(text, ManualImportSourceTag)
        if (parsed is EmergencyCsvParser.Result.Malformed) {
            return Result.Failure(Reason.PARSE_ERROR, parsed.reason)
        }
        val records = EmergencyCsvParser.recordsOrEmpty(parsed)
        val rejected = when (parsed) {
            is EmergencyCsvParser.Result.Partial -> parsed.rejectedCount
            else -> 0
        }
        val reasons = when (parsed) {
            is EmergencyCsvParser.Result.Partial ->
                parsed.issues.entries
                    .filter { it.value > 0 }
                    .sortedByDescending { it.value }
                    .take(MAX_REPORTED_REASONS)
                    .map { "${it.key.name} x${it.value}" }
            else -> emptyList()
        }
        return toItems(Format.APP_CSV, records, rejected, reasons, now)
    }

    private fun parseApiCsv(text: String, dataRows: Int, now: Long): Result {
        val records = try {
            VpnGateApiParser.parseApi(text, ManualImportSourceTag)
        } catch (e: Exception) {
            return Result.Failure(Reason.PARSE_ERROR, e.javaClass.simpleName)
        }
        if (records.isEmpty()) return Result.Failure(Reason.NO_VALID_ROWS)
        val rejected = (dataRows - records.size).coerceAtLeast(0)
        val reasons = if (rejected > 0) listOf("$rejected row(s) could not be parsed") else emptyList()
        return toItems(Format.VPN_GATE_API, records, rejected, reasons, now)
    }

    /**
     * Maps records to storage rows, dropping anything the record layer cannot
     * dial (no valid IPv4) and collapsing duplicates by the Room identity
     * (`hostName`) — a re-import of the same file must not create a second row.
     */
    private fun toItems(
        format: Format,
        records: List<vn.unlimit.vpngate.data.model.VpnServerRecord>,
        rejectedCount: Int,
        reasons: List<String>,
        now: Long,
    ): Result {
        if (records.isEmpty()) return Result.Failure(Reason.NO_VALID_ROWS)

        val byHost = LinkedHashMap<String, VPNGateItem>()
        var unusable = 0
        for (record in records) {
            val connection = try {
                VpnConnectionMapper.toConnection(record)
            } catch (e: Exception) {
                null
            }
            val item = try {
                connection?.toVPNGateItem()
            } catch (e: Exception) {
                null
            }
            if (item == null || item.hostName.isBlank()) {
                unusable++
                continue
            }
            byHost[item.hostName.trim()] = item.copy(
                firstSeenAt = now,
                lastSeenAt = now,
                healthScore = 0,
            )
        }
        if (byHost.isEmpty()) return Result.Failure(Reason.NO_VALID_ROWS)

        val mergedReasons = ArrayList<String>(reasons)
        if (unusable > 0) mergedReasons.add(0, "$unusable row(s) had no usable address")
        return Result.Success(
            format = format,
            items = byHost.values.toList(),
            rejectedReasons = mergedReasons.take(MAX_REPORTED_REASONS),
            rejectedCount = rejectedCount + unusable,
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Wrapped-line repair (SMS / messenger paste)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Re-joins hard-wrapped rows.
     *
     * A messenger or SMS copy breaks a long row — typically the base64 OpenVPN
     * profile — across lines. The repair is conservative: a line is appended to
     * the current buffer only when BOTH the buffer and the line have fewer
     * fields than the header. A complete row therefore always starts a new
     * buffer, and a genuinely malformed short line is left standing so the
     * owning parser can reject it with a real reason instead of being glued to
     * the next server.
     */
    internal fun joinWrappedRows(text: String): String {
        val lines = text.lines()
        val headerAt = headerIndex(lines)
        if (headerAt < 0) return text
        val header = lines[headerAt]
        val expected = VpnGateApiParser.splitCsvRecords(header).firstOrNull()?.size ?: return text
        if (expected < 2) return text

        fun fields(line: String): Int =
            VpnGateApiParser.splitCsvRecords(line).firstOrNull()?.size ?: 0

        // Lines before the header (`*vpn_servers`, a messenger's greeting) are
        // copied through untouched; the caller slices them off afterwards.
        val out = ArrayList<String>(lines.size)
        out.addAll(lines.subList(0, headerAt))
        out.add(header)
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                out.add(buffer.toString())
                buffer.setLength(0)
            }
        }
        for (line in lines.drop(headerAt + 1)) {
            if (line.isBlank()) continue
            if (buffer.isEmpty()) {
                buffer.append(line)
                continue
            }
            if (fields(buffer.toString()) < expected && fields(line) < expected) {
                buffer.append(line)
            } else {
                flush()
                buffer.append(line)
            }
        }
        flush()
        return out.joinToString("\n")
    }

    /**
     * The provenance tag written for an imported row.
     *
     * Deliberately NOT `api`/`html`/`emergency`: an imported file is user data
     * and must never be able to speak with canonical authority in the merger.
     */
    const val ManualImportSourceTag: String = "manual"
}
