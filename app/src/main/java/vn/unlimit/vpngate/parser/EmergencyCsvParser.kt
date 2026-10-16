package vn.unlimit.vpngate.parser

import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.merger.VpnServerMerger

/**
 * Emergency CSV parser — `SERVER_SOURCE_ARCHITECTURE.md` §10 (approved as
 * the design basis in spec v1.0.20, §11.9.3).
 *
 * The emergency file is a **pure CSV**: a header row plus data rows, no
 * banner or comment lines (§10.7). That is the one structural difference
 * from the canonical `/api/iphone/` feed, which `VpnGateApiParser` has to
 * special-case; here the header row is simply the first record.
 *
 * Every emitted record is a normal [VpnServerRecord] built with the same
 * [VpnRecords] helpers the live parser uses, so an emergency record is
 * indistinguishable at the model layer from an api/html one except for its
 * provenance. There is deliberately **no parallel record type** (§3.12).
 *
 * Four properties of this parser are load-bearing:
 *
 *  1. **Header-driven, not positional.** §10.9 Q2 leaves column order open,
 *     so the header row is authoritative and only the four required columns
 *     are demanded (§10.4). A data row whose field count differs from the
 *     header's is rejected as a row, never as a file.
 *  2. **Fail-closed on the schema token.** Any `schema_version` other than
 *     [SUPPORTED_SCHEMA_VERSION] rejects the whole body (§10.4, §7 Q7) — a
 *     parser that mis-read a newer schema would silently produce wrong
 *     servers, which is worse than producing nothing.
 *  3. **Partial is the normal good outcome.** A row that fails a required
 *     rule is rejected on its own; the rest still imports (§3.9). An
 *     optional-decode failure never drops a row: the capability is marked
 *     unsupported instead (§10.4).
 *  4. **Dedup is the live path's dedup.** Identity and the
 *     more-complete-wins merge are delegated to [VpnServerMerger], so
 *     `servers.csv` collapses duplicates exactly as the collector does
 *     (§10.5).
 *
 * Pure JVM: no Android, no Room, no network. `text` arrives already
 * decoded by the fetcher, so a non-UTF-8 body is observed here as the
 * replacement character the decoder substituted — the parser treats that
 * as a structural defect rather than trying to recover from it.
 */
object EmergencyCsvParser {

    /** The only `schema_version` this build understands (§10.4). */
    const val SUPPORTED_SCHEMA_VERSION = "1.0"

    /**
     * The record-layer provenance tag for every emergency record.
     *
     * Never `"api"`/`"html"` (§3, line 216 of the design doc): an emergency
     * source must not be able to speak with canonical authority. It also
     * does not look like a mirror tag, so it forms its own provenance group
     * and lands on the merger's lowest priority.
     */
    const val SOURCE_TAG = "emergency"

    /** Columns without which a row cannot be identified or versioned (§10.4). */
    val REQUIRED_COLUMNS = listOf("HostName", "IP", "schema_version", "SourceTag")

    /** The §10.6 `SourceTag` enum. */
    val SOURCE_TAG_ENUM = listOf("api_iphone", "html_parse", "merged")

    /** Default `SourceTag` for a hand-authored emergency file (§10.4). */
    private const val DEFAULT_SOURCE_TAG = "html_parse"

    /** All 29 §10.2 columns in the documented order. Reference/tests only. */
    val ALL_COLUMNS = listOf(
        "HostName", "IP", "Score", "Ping", "Speed", "CountryLong", "CountryShort",
        "NumVpnSessions", "Uptime", "TotalUsers", "TotalTraffic", "LogType",
        "Operator", "Message", "OpenVPN_ConfigData_Base64", "schema_version",
        "SE_TCP_Port", "SE_UDP_Supported", "SE_UDP_Port", "OpenVPN_TCP_Port",
        "OpenVPN_UDP_Port", "L2TP_Supported", "SSTP_Supported", "SSTP_Hostname",
        "SSTP_Port", "Has_IPv6", "FirstSeenAt", "LastSeenAt", "SourceTag",
    )

    private const val BOM = '\uFEFF'
    private const val REPLACEMENT_CHAR = '\uFFFD'

    /**
     * Anything that went wrong while reading one file.
     *
     * [rejectsRecord] separates the two §10.4 outcomes, which are not
     * interchangeable: a reject drops the row and is counted against the
     * dataset's integrity report, while a note keeps the row and only
     * degrades one field. Both are reported because §3.11 requires a
     * reason for every dropped or downgraded value.
     */
    enum class Issue(val rejectsRecord: Boolean) {
        /** `HostName` absent after normalization (§10.4). */
        EMPTY_HOSTNAME(true),

        /** `IP` is not a valid IPv4 dotted quad (§10.4). */
        INVALID_IPV4(true),

        /** Neither identity key resolved, so the row cannot be deduplicated (§10.5). */
        UNRESOLVABLE_IDENTITY(true),

        /** The row's field count differs from the header's (§10.7). */
        WRONG_COLUMN_COUNT(true),

        /** `SourceTag` outside the §10.6 enum; defaulted to `html_parse` (§10.4). */
        UNKNOWN_SOURCE_TAG(false),

        /** `CountryShort` present but not two letters; dropped to empty (§10.4). */
        COUNTRY_SHORT_NOT_ISO(false),

        /** A port column was non-numeric or outside 0..65535 (§10.4). */
        INVALID_PORT(false),

        /** An optional numeric column held a non-numeric token; left unset (§10.3). */
        NON_NUMERIC_VALUE(false),

        /**
         * `SE_UDP_Port` published without `SE_UDP_Supported` (§10.3).
         *
         * The RUDP warning at §10.3 is the reason this is a note and not a
         * port write: `SE_UDP_Port` never proves a usable SoftEther UDP/R-UDP
         * transport, and recording it as one would advertise a path the
         * record cannot actually take.
         */
        UDP_PORT_WITHOUT_DECLARED_SUPPORT(false),

        /** `OpenVPN_ConfigData_Base64` did not decode; OpenVPN marked unsupported (§10.4). */
        OPENVPN_CONFIG_UNDECODABLE(false),

        /** A boolean column held a token other than `true`/`false` (§10.3). */
        NON_BOOLEAN_TOKEN(false),

        /** A header column outside §10.2; carried but ignored. */
        UNKNOWN_COLUMN(false),
    }

    /**
     * The three §9.6 outcomes, at the granularity the parser can decide.
     *
     * `DatasetIntegrity` still has the final say on whether the dataset may
     * replace a stored one (§10.8) — this classification says only whether
     * the *file* was readable and whether every row of it survived.
     */
    sealed interface Result {

        /** Every data row parsed; nothing was dropped. */
        data class Valid(
            val records: List<VpnServerRecord>,
            val rejectedCount: Int = 0,
            val issues: Map<Issue, Int> = emptyMap(),
        ) : Result

        /**
         * The file was readable and at least one row survived, but some rows
         * were dropped or downgraded (§3.9/§10.4).
         */
        data class Partial(
            val records: List<VpnServerRecord>,
            val rejectedCount: Int,
            val issues: Map<Issue, Int> = emptyMap(),
        ) : Result

        /**
         * The body is unusable as a whole: unreadable, unversioned, wrongly
         * versioned, headerless, or with no surviving row (§10.8
         * `REJECT_DEGRADED`). The caller must fall through, never store.
         */
        data class Malformed(val reason: String) : Result
    }

    /** Records, or an empty list when the body was rejected whole. */
    fun recordsOrEmpty(result: Result): List<VpnServerRecord> = when (result) {
        is Result.Valid -> result.records
        is Result.Partial -> result.records
        is Result.Malformed -> emptyList()
    }

    /**
     * Parse one emergency CSV body.
     *
     * @param text the decoded body. Must already be a whole file; this
     *   function never performs IO and never mutates its input.
     * @param source the record-layer provenance tag. Defaults to
     *   [SOURCE_TAG]; a caller that wants per-source attribution may pass
     *   something more specific, but it must never be `"api"` or `"html"`.
     */
    fun parse(text: String, source: String = SOURCE_TAG): Result {
        CollectorLog.d("Parsing emergency CSV...")

        // ── file-level structure ────────────────────────────────────────
        if (text.isBlank()) return Result.Malformed("empty body")

        // §10.7 requires UTF-8 without BOM. A BOM means the file was
        // written by a tool that disagrees with the format, and it would
        // otherwise silently corrupt the first header name.
        if (text.first() == BOM) {
            return Result.Malformed("utf-8 BOM is not part of the format (§10.7)")
        }

        // The fetcher hands over a decoded String, so malformed UTF-8 is
        // observable as the decoder's substitution character. Treating that
        // as data would let a broken byte stream masquerade as a valid file.
        if (text.indexOf(REPLACEMENT_CHAR) >= 0) {
            return Result.Malformed("body is not valid UTF-8 (§10.7)")
        }

        // §10.7: pure CSV, so the header row is simply the first record.
        // The RFC-4180 splitter is the one the canonical parser uses, and it
        // already ignores `\r`, so an LF/CRLF mix does not change the result.
        val records = VpnGateApiParser.splitCsvRecords(text)
        if (records.isEmpty()) return Result.Malformed("no CSV records")

        val header = records.first()
        val columns = header.map { it.trim() }
        val missing = REQUIRED_COLUMNS.filter { it !in columns }
        if (missing.isNotEmpty()) {
            return Result.Malformed("missing required column(s): ${missing.joinToString(",")}")
        }

        val dataRows = records.drop(1)
        if (dataRows.isEmpty()) return Result.Malformed("header only: no data rows")

        val issues = IssueCounter()
        val indexByName = columns.withIndex().associate { (i, name) -> name to i }
        columns.filter { it !in ALL_COLUMNS }.forEach { issues.add(Issue.UNKNOWN_COLUMN) }

        // ── fail-closed schema check, across the whole file ─────────────
        // §3.1: a declared version above the supported one is rejected
        // rather than mis-parsed. Every row carries the token, so every row
        // is checked; a single non-conforming row rejects the file.
        val schemaIndex = indexByName.getValue("schema_version")
        for (row in dataRows) {
            if (row.size != columns.size) continue // accounted for below
            val declared = row[schemaIndex].trim()
            if (declared != SUPPORTED_SCHEMA_VERSION) {
                return Result.Malformed(
                    "unsupported schema_version \"$declared\" (supported: $SUPPORTED_SCHEMA_VERSION)",
                )
            }
        }

        // ── per-row parsing ────────────────────────────────────────────
        val parsed = mutableListOf<VpnServerRecord>()
        var rejected = 0

        for (row in dataRows) {
            if (row.size != columns.size) {
                issues.add(Issue.WRONG_COLUMN_COUNT)
                rejected++
                continue
            }

            val record = buildRecord(row, indexByName, issues)
            if (record == null) {
                rejected++
                continue
            }
            VpnRecords.sourceAdd(record, source)
            parsed.add(record)
        }

        if (parsed.isEmpty()) {
            // §3.10: at least one record must pass required-field
            // validation. Zero survivors is not "an empty import", it is a
            // file that describes no server at all.
            return Result.Malformed("no record passed required-field validation")
        }

        // ── identity + merge (§10.5) ───────────────────────────────────
        val deduped = deduplicate(parsed, issues)

        CollectorLog.d(
            "Emergency CSV: ${deduped.size} record(s), $rejected row(s) rejected, " +
                "issues=${issues.snapshot()}",
        )

        return if (rejected == 0) {
            Result.Valid(deduped, 0, issues.snapshot())
        } else {
            Result.Partial(deduped, rejected, issues.snapshot())
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Row construction
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build one record, or `null` when the row must be rejected.
     *
     * A row is rejected only for the §10.4 required rules. Everything else
     * is a downgrade recorded through [issues] and the row survives.
     */
    private fun buildRecord(
        row: List<String>,
        indexByName: Map<String, Int>,
        issues: IssueCounter,
    ): VpnServerRecord? {
        fun field(name: String): String {
            val index = indexByName[name] ?: return ""
            return VpnUtil.clean(row[index])
        }

        // §10.4: HostName must be non-empty. normalizeHost also strips the
        // `:port` suffix and lower-cases, which is exactly the secondary
        // identity key §10.5 asks for — so the stored value is already in
        // dedup form.
        val hostname = VpnUtil.normalizeHost(field("HostName"))
        if (hostname.isEmpty()) {
            issues.add(Issue.EMPTY_HOSTNAME)
            return null
        }

        // §10.4: IP must be a valid IPv4 dotted quad. Has_IPv6 is metadata
        // only and never an identity key (§10.5).
        val ip = VpnUtil.normalizeIp(field("IP"))
        if (!VpnUtil.validIp(ip)) {
            issues.add(Issue.INVALID_IPV4)
            return null
        }

        val server = VpnRecords.newServer()
        VpnRecords.setField(server, "identity.hostname", hostname, SOURCE_TAG)
        VpnRecords.setField(server, "identity.ip", ip, SOURCE_TAG)

        // ---- base-15 descriptive columns (§10.2) ──────────────────────
        applyInt(field("Score"), issues) { VpnRecords.setField(server, "performance.score", it, SOURCE_TAG) }
        applyDouble(field("Ping"), issues) { VpnRecords.setField(server, "performance.pingMs", it, SOURCE_TAG) }

        // §18 unit normalization, identical to the canonical parser: the
        // feed publishes bps, the model stores Mbps.
        val speedBps = VpnUtil.toFloat(field("Speed"))
        if (speedBps > 0) {
            VpnRecords.setField(server, "performance.speedMbps", speedBps / 1_000_000.0, SOURCE_TAG)
        }

        VpnRecords.setField(server, "identity.countryLong", field("CountryLong"), SOURCE_TAG)

        // §10.4: CountryShort must be exactly two letters when present.
        val countryShort = field("CountryShort")
        if (countryShort.isNotEmpty()) {
            if (countryShort.length == 2 && countryShort.all { it.isLetter() }) {
                VpnRecords.setField(server, "identity.country", countryShort.uppercase(), SOURCE_TAG)
            } else {
                issues.add(Issue.COUNTRY_SHORT_NOT_ISO)
            }
        }

        applyInt(field("NumVpnSessions"), issues) {
            VpnRecords.setField(server, "performance.sessions", it, SOURCE_TAG)
        }

        // API Uptime is milliseconds in the current feed (§18).
        val uptimeMs = VpnUtil.toFloat(field("Uptime"))
        if (uptimeMs > 0) {
            VpnRecords.setField(server, "performance.uptimeDays", uptimeMs / 86_400_000.0, SOURCE_TAG)
        }

        applyInt(field("TotalUsers"), issues) {
            VpnRecords.setField(server, "performance.totalUsers", it, SOURCE_TAG)
        }

        val totalTraffic = VpnUtil.toFloat(field("TotalTraffic"))
        if (totalTraffic > 0) {
            VpnRecords.setField(
                server,
                "performance.totalTrafficGB",
                totalTraffic / (1024.0 * 1024.0 * 1024.0),
                SOURCE_TAG,
            )
        }

        VpnRecords.setField(server, "logging.policy", field("LogType"), SOURCE_TAG)
        VpnRecords.setField(server, "operator.name", field("Operator"), SOURCE_TAG)
        VpnRecords.setField(server, "operator.message", field("Message"), SOURCE_TAG)

        // ---- embedded OpenVPN config (§10.4) ─────────────────────────
        val configB64 = field("OpenVPN_ConfigData_Base64")
        if (configB64.isNotEmpty()) {
            applyOpenVpnConfig(server, configB64, issues)
        }

        // ---- protocol / port columns (§10.2, §10.3) ───────────────────
        applyPort(field("SE_TCP_Port"), issues) { port ->
            VpnRecords.setField(server, "protocols.softether.tcp.supported", true, SOURCE_TAG)
            VpnRecords.setField(server, "protocols.softether.tcp.port", port, SOURCE_TAG)
            VpnRecords.setField(
                server,
                "protocols.softether.tcp.portSource",
                VpnPortSource.EXPLICIT,
                SOURCE_TAG,
            )
        }

        // §10.3: UDP support is declared, never inferred from a port. The
        // RUDP warning at §10.3 (verbatim from RUDP_IMPLEMENTATION_PLAN.md)
        // is why a published `SE_UDP_Port` on its own proves nothing.
        val udpDeclared = boolOrNull(field("SE_UDP_Supported"))
        if (field("SE_UDP_Supported").isNotEmpty() && udpDeclared == null) {
            issues.add(Issue.NON_BOOLEAN_TOKEN)
        }
        if (udpDeclared == true) {
            applyPort(field("SE_UDP_Port"), issues) { port ->
                VpnRecords.setField(server, "protocols.softether.udp.supported", true, SOURCE_TAG)
                VpnRecords.setField(server, "protocols.softether.udp.port", port, SOURCE_TAG)
                VpnRecords.setField(
                    server,
                    "protocols.softether.udp.portSource",
                    VpnPortSource.EXPLICIT,
                    SOURCE_TAG,
                )
            }
        } else if (VpnUtil.toInt(field("SE_UDP_Port")) > 0) {
            // Support was not declared, so the port is not a usable
            // transport and is deliberately not written to the record.
            issues.add(Issue.UDP_PORT_WITHOUT_DECLARED_SUPPORT)
        }

        applyPort(field("OpenVPN_TCP_Port"), issues) { port ->
            VpnRecords.setField(server, "protocols.openvpn.tcp.supported", true, SOURCE_TAG)
            VpnRecords.setField(server, "protocols.openvpn.tcp.port", port, SOURCE_TAG)
            VpnRecords.setField(
                server,
                "protocols.openvpn.tcp.portSource",
                VpnPortSource.EXPLICIT,
                SOURCE_TAG,
            )
        }

        applyPort(field("OpenVPN_UDP_Port"), issues) { port ->
            VpnRecords.setField(server, "protocols.openvpn.udp.supported", true, SOURCE_TAG)
            VpnRecords.setField(server, "protocols.openvpn.udp.port", port, SOURCE_TAG)
            VpnRecords.setField(
                server,
                "protocols.openvpn.udp.portSource",
                VpnPortSource.EXPLICIT,
                SOURCE_TAG,
            )
        }

        applyBool(field("L2TP_Supported"), issues) { supported ->
            if (supported) {
                VpnRecords.markSupported(server, "l2tpIpsec", SOURCE_TAG)
            }
        }
        // No L2TP port column exists in §10.2, and a port is never
        // fabricated: normalizeServer attaches the protocol default 1701
        // under DEFAULT provenance, which is the model's own rule.

        applyBool(field("SSTP_Supported"), issues) { supported ->
            if (supported) {
                VpnRecords.markSupported(server, "sstp", SOURCE_TAG)
            }
        }
        applySstpEndpoint(server, field("SSTP_Hostname"), field("SSTP_Port"), issues)

        // ---- record-level provenance (§10.6, §10.8) ───────────────────
        // §10.8: the file's own SourceTag is per-record provenance and does
        // not change the file-level accept/merge/reject decision. It is kept
        // on the record so the §33 debug dump can show it.
        val declaredTag = field("SourceTag")
        val csvSourceTag = when {
            declaredTag.isEmpty() -> DEFAULT_SOURCE_TAG
            declaredTag in SOURCE_TAG_ENUM -> declaredTag
            else -> {
                // §10.4: an unknown token defaults to html_parse for a
                // hand-authored emergency file; the reason is recorded.
                issues.add(Issue.UNKNOWN_SOURCE_TAG)
                DEFAULT_SOURCE_TAG
            }
        }
        server["emergency"] = linkedMapOf(
            "sourceTag" to SOURCE_TAG,
            "csvSourceTag" to csvSourceTag,
        )

        return server
    }

    /**
     * Decode the embedded OpenVPN config, mirroring the canonical parser.
     *
     * §10.4 is explicit that a decode failure must NOT drop the row: the
     * record is kept and OpenVPN is simply marked unsupported, so the row's
     * SoftEther/L2TP/SSTP facts survive.
     */
    private fun applyOpenVpnConfig(server: VpnServerRecord, configB64: String, issues: IssueCounter) {
        val configInfo = OpenVpnConfigParser.decode(configB64)
        if (configInfo.raw.isEmpty()) {
            issues.add(Issue.OPENVPN_CONFIG_UNDECODABLE)
            return
        }

        VpnRecords.markSupported(server, "openvpn", SOURCE_TAG)
        VpnRecords.setField(server, "protocols.openvpn.configAvailable", true, SOURCE_TAG)
        VpnRecords.setField(server, "protocols.openvpn.rawConfig", configInfo.raw, SOURCE_TAG)

        // Global "proto" carries the transport family for remotes that omit
        // it (§7), exactly as in the canonical parser.
        val globalFamilies = configInfo.protocols
            .filter { it.startsWith("tcp") || it.startsWith("udp") }
            .map { it.split("-")[0] }
            .toSortedSet()
            .toList()
        val inheritedProto = if (globalFamilies.size == 1) globalFamilies[0] else ""

        @Suppress("UNCHECKED_CAST")
        val configs = VpnRecords.protocol(server, "openvpn")["configs"] as MutableList<Any?>
        for (remote in configInfo.remotes) {
            configs.add(remote)

            var proto = remote.proto.lowercase()
            if (proto.isEmpty()) proto = inheritedProto

            val port = remote.port
            val tcp = OpenVpnConfigParser.isTcpFamily(proto)
            val udp = OpenVpnConfigParser.isUdpFamily(proto)
            if (!tcp && !udp) continue

            val transport = if (tcp) "tcp" else "udp"
            VpnRecords.setField(server, "protocols.openvpn.$transport.supported", true, SOURCE_TAG)
            if (VpnUtil.validPort(port)) {
                VpnRecords.setField(server, "protocols.openvpn.$transport.port", port, SOURCE_TAG)
                VpnRecords.setField(
                    server,
                    "protocols.openvpn.$transport.portSource",
                    VpnPortSource.EXPLICIT,
                    SOURCE_TAG,
                )
            }
        }

        for (proto in configInfo.protocols) {
            if (OpenVpnConfigParser.isTcpFamily(proto)) {
                VpnRecords.setField(server, "protocols.openvpn.tcp.supported", true, SOURCE_TAG)
            } else if (OpenVpnConfigParser.isUdpFamily(proto)) {
                VpnRecords.setField(server, "protocols.openvpn.udp.supported", true, SOURCE_TAG)
            }
        }
    }

    /**
     * SSTP host/port, reconciled per §10.3.
     *
     * `SSTP_Hostname` may carry a `:port` suffix when the port is not 443.
     * The explicit `SSTP_Port` column wins when it is present and valid;
     * otherwise the suffix — if any — is the port. Both forms are accepted
     * and neither is invented.
     */
    private fun applySstpEndpoint(
        server: VpnServerRecord,
        rawHost: String,
        rawPort: String,
        issues: IssueCounter,
    ) {
        if (rawHost.isEmpty()) return

        var host = VpnUtil.normalizeHost(rawHost)
        var suffixPort = 0
        if (rawHost.count { it == ':' } == 1 && !VpnUtil.validIp(rawHost)) {
            suffixPort = VpnUtil.toInt(rawHost.substringAfterLast(':'))
        }
        if (host.isEmpty()) {
            // e.g. the field was only ":443"
            host = rawHost.trim()
            if (host.isEmpty()) return
        }

        VpnRecords.setField(server, "protocols.sstp.hostname", host, SOURCE_TAG)

        val declaredPort = portValue(rawPort, issues)
        val effectivePort = if (declaredPort > 0) declaredPort else suffixPort
        if (VpnUtil.validPort(effectivePort)) {
            VpnRecords.setField(server, "protocols.sstp.port", effectivePort, SOURCE_TAG)
            VpnRecords.setField(
                server,
                "protocols.sstp.portSource",
                VpnPortSource.EXPLICIT,
                SOURCE_TAG,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Identity + merge (§10.5)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Collapse rows that describe the same server.
     *
     * The identity and the merge itself are [VpnServerMerger]'s, so the
     * result is identical to what the live collector produces — which is the
     * point of §10.5's "mirrors the record identity/dedup rules already used
     * by the in-app collector". What is added here is the one rule the
     * record model has no opinion about: a merged record's file-level
     * `SourceTag` becomes `merged` (§10.5, §10.6).
     */
    private fun deduplicate(parsed: List<VpnServerRecord>, issues: IssueCounter): List<VpnServerRecord> {
        val groups = LinkedHashMap<String, MutableList<VpnServerRecord>>()

        for (record in parsed) {
            val identity = VpnRecords.identity(record)
            val ip = VpnRecords.str(identity["ip"])
            val host = VpnUtil.normalizeHost(identity["hostname"])

            // §10.5: IPv4 IP is the primary key, HostName the secondary.
            val key = if (VpnUtil.validIp(ip)) ip else host
            if (key.isEmpty()) {
                // Unreachable for a record that survived required-field
                // validation; kept so the identity contract is explicit.
                issues.add(Issue.UNRESOLVABLE_IDENTITY)
                continue
            }
            groups.getOrPut(key) { mutableListOf() }.add(record)
        }

        if (groups.size == parsed.size) return parsed

        val merged = mutableListOf<VpnServerRecord>()
        for (group in groups.values) {
            if (group.size == 1) {
                merged.add(group[0])
                continue
            }

            // More-complete-record-wins is the merger's recursive merge, so
            // it is not re-implemented here.
            var target = group[0]
            for (i in 1 until group.size) {
                target = VpnServerMerger.recursiveMerge(target, group[i])
            }
            @Suppress("UNCHECKED_CAST")
            val emergency = target["emergency"] as? MutableMap<String, Any?>
            emergency?.set("csvSourceTag", "merged")
            merged.add(target)
        }
        return merged
    }

    // ─────────────────────────────────────────────────────────────────────
    // Small typed readers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * `0` means "not present / not offered" (§10.3) and is a perfectly
     * conforming value, so it is never reported as a defect. Anything else
     * that is not a usable port is not-present plus a recorded reason.
     */
    private fun portValue(raw: String, issues: IssueCounter): Int {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "0") return 0
        val parsed = trimmed.toIntOrNull()
        if (parsed == null || parsed !in 1..65535) {
            issues.add(Issue.INVALID_PORT)
            return 0
        }
        return parsed
    }

    private fun applyPort(raw: String, issues: IssueCounter, write: (Int) -> Unit) {
        val port = portValue(raw, issues)
        if (port > 0) write(port)
    }

    private fun applyInt(raw: String, issues: IssueCounter, write: (Int) -> Unit) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val parsed = trimmed.toIntOrNull()
        if (parsed == null) {
            issues.add(Issue.NON_NUMERIC_VALUE)
            return
        }
        write(parsed)
    }

    private fun applyDouble(raw: String, issues: IssueCounter, write: (Double) -> Unit) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val parsed = trimmed.toDoubleOrNull()
        if (parsed == null) {
            issues.add(Issue.NON_NUMERIC_VALUE)
            return
        }
        write(parsed)
    }

    /** `null` when the token is absent or is not `true`/`false` (§10.3). */
    private fun boolOrNull(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun applyBool(raw: String, issues: IssueCounter, write: (Boolean) -> Unit) {
        val value = boolOrNull(raw)
        if (raw.isNotEmpty() && value == null) {
            issues.add(Issue.NON_BOOLEAN_TOKEN)
        }
        write(value == true)
    }

    private class IssueCounter {
        private val counts = LinkedHashMap<Issue, Int>()
        fun add(issue: Issue) {
            counts[issue] = (counts[issue] ?: 0) + 1
        }

        fun snapshot(): Map<Issue, Int> = counts.toMap()
    }
}
