package vn.unlimit.vpngate.data.model

/**
 * Protocol-first server record schema — a mechanical port of the
 * Python reference collector's internal model (vpngate_collector.py,
 * V5 contract). A record IS a nested map so the merger can stay a
 * 1:1 port of the oracle's recursive merge; typed accessors live in
 * [VpnRecords].
 */
typealias VpnServerRecord = MutableMap<String, Any?>

/** §38: a visible disagreement recorded during merge. */
data class Conflict(val field: String, val values: Map<String, Any?>)

/** §8: one `remote` directive decoded from an OpenVPN config. */
data class OpenVpnRemote(val host: String, val port: Int?, val proto: String)

object VpnProtocolNames {
    const val SOFTETHER = "softether"
    const val OPENVPN = "openvpn"
    const val L2TP_IPSEC = "l2tpIpsec"
    const val SSTP = "sstp"
    val ALL = listOf(SOFTETHER, OPENVPN, L2TP_IPSEC, SSTP)
}

/**
 * Where a port value came from.
 *
 * A port the source published is EXPLICIT evidence. A port the protocol
 * defines for itself is a DEFAULT, used only when the source confirms the
 * capability but publishes no port. UNKNOWN means the source supplied
 * nothing — and must never be turned into a value.
 *
 * Stage 2F-A (L-1): UNKNOWN is ONE canonical literal. It used to be the
 * empty string while the database default and several comments spelled it
 * "UNKNOWN", so the same state had two representations and any code that
 * asked "is this provenance known?" was reading one of them by accident.
 * [UNKNOWN] is now the only spelling, [normalized] folds every defensive
 * variant onto it, and nothing may infer a port from it.
 */
object VpnPortSource {
    const val EXPLICIT = "EXPLICIT"
    const val DEFAULT = "DEFAULT"
    const val UNKNOWN = "UNKNOWN"

    /**
     * Canonicalize any provenance value that reaches the model.
     *
     * Reads a stored column, a parceled field or a record-layer entry, all of
     * which may predate [UNKNOWN] becoming a literal and may therefore be
     * null, empty or unrecognised. Only the two values that actually carry
     * meaning survive; everything else is [UNKNOWN], which is the safe
     * direction: an unknown provenance can never become a port.
     */
    fun normalized(value: Any?): String = when (value?.toString()?.trim()?.uppercase()) {
        EXPLICIT -> EXPLICIT
        DEFAULT -> DEFAULT
        else -> UNKNOWN
    }
}

/**
 * Protocol-level connection defaults.
 *
 * These are NOT source data. They may only be used for a capability the
 * source has already confirmed, and they are always recorded as DEFAULT so
 * they can never be mistaken for a published port.
 */
object VpnProtocolDefaults {
    /** MS-SSTP's protocol-standard TCP listener (RFC 8245 / VPN Gate). */
    const val SSTP_PORT = 443

    /** L2TP's IANA-assigned UDP listener (RFC 3931). */
    const val L2TP_PORT = 1701
}

/**
 * Pluggable sink so unit tests (JVM) and the app (Logcat) both work.
 *
 * Developer Mode gate (Settings): when disabled, every collector log call
 * becomes a no-op — no string building, no logcat writes, no logcat tap —
 * to keep the app fast. Ships OFF (a fresh install has no verbose logging);
 * App.onCreate then syncs it with the persisted user choice.
 */
object CollectorLog {
    var sink: (String) -> Unit = { println(it) }

    @Volatile
    var enabled: Boolean = false

    fun d(message: String) {
        if (enabled) sink(message)
    }
}

/**
 * Value/text helpers ported 1:1 from the Python oracle
 * (clean / to_int / to_float / valid_ip / normalize_ip /
 * normalize_host / valid_port).
 */
object VpnUtil {
    fun clean(value: Any?): String {
        if (value == null) return ""
        val s = value.toString()
            .replace("\u00a0", " ")
            .replace("\u200b", "")
            .replace("\r", " ")
            .replace("\n", " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun toInt(value: Any?, default: Int = 0): Int {
        if (value == null) return default
        val s = clean(value).replace(",", "").replace(" ", "")
        val m = Regex("-?\\d+").find(s) ?: return default
        return m.value.toIntOrNull() ?: default
    }

    fun toFloat(value: Any?, default: Double = 0.0): Double {
        if (value == null) return default
        val s = clean(value).replace(",", "").replace(" ", "")
        val m = Regex("-?\\d+(?:\\.\\d+)?").find(s) ?: return default
        return m.value.toDoubleOrNull() ?: default
    }

    fun validIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } && p.toInt() in 0..255 }
    }

    fun normalizeIp(value: Any?): String {
        val s = clean(value)
        val m = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").find(s) ?: return ""
        val ip = m.value
        return if (validIp(ip)) ip else ""
    }

    fun normalizeHost(value: Any?): String {
        var s = clean(value).lowercase().trimEnd('.')
        s = s.replace(Regex("^[a-z]+://"), "")
        if (s.count { it == ':' } == 1 && !validIp(s)) {
            s = s.substringBeforeLast(':')
        }
        return s
    }

    fun validPort(port: Any?): Boolean {
        val p = toInt(port)
        return p in 1..65535
    }

    /** Oracle falsy scalars: None, "", False, 0, 0.0. */
    fun isFalsyScalar(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isEmpty()
        is Boolean -> !value
        is Number -> value.toDouble() == 0.0
        else -> false
    }
}

/**
 * Record construction + provenance-tagged field writes
 * (new_server / set_field / mark_supported / source_add).
 */
object VpnRecords {
    const val SCHEMA_VERSION = "4.0"

    /**
     * Stage 2F-A (L-1): is this value "nothing is set here yet" for [path]?
     *
     * The oracle's rule is that a falsy value never overwrites a meaningful
     * one. That rule used to cover port provenance for free, because UNKNOWN
     * was the empty string. UNKNOWN is now a real literal, so a freshly
     * constructed record holds a non-empty sentinel in every `portSource`
     * leaf — and a plain falsy check would read that sentinel as a meaningful
     * value and refuse every later EXPLICIT/DEFAULT write from a parser.
     *
     * The rule is therefore applied to provenance leaves only, so a data string
     * that happens to read "UNKNOWN" (an operator name, a message) is still
     * treated as real data.
     */
    private fun isUnset(path: String, value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isEmpty() ||
            (path.endsWith(PORT_SOURCE_FIELD) && value == VpnPortSource.UNKNOWN)
        is Boolean -> !value
        is Number -> value.toDouble() == 0.0
        is List<*> -> value.isEmpty()
        else -> false
    }

    private const val PORT_SOURCE_FIELD = ".portSource"

    fun newServer(): VpnServerRecord {
        val rec = LinkedHashMap<String, Any?>()
        rec["schemaVersion"] = SCHEMA_VERSION
        rec["identity"] = linkedMapOf<String, Any?>(
            "hostname" to "",
            "ip" to "",
            "ispHostname" to "",
            "country" to "",
            "countryLong" to "",
        )
        rec["performance"] = linkedMapOf<String, Any?>(
            "score" to 0,
            "pingMs" to 0.0,
            "speedMbps" to 0.0,
            "sessions" to 0,
            "uptimeDays" to 0.0,
            "totalUsers" to 0,
            "totalTrafficGB" to 0.0,
        )
        rec["logging"] = linkedMapOf<String, Any?>("policy" to "")
        rec["operator"] = linkedMapOf<String, Any?>(
            "name" to "",
            "message" to "",
        )
        rec["protocols"] = linkedMapOf<String, Any?>(
            VpnProtocolNames.SOFTETHER to linkedMapOf<String, Any?>(
                "supported" to false,
                "tcp" to linkedMapOf<String, Any?>(
                    "supported" to false,
                    "port" to null,
                    "portSource" to VpnPortSource.UNKNOWN,
                ),
                "udp" to linkedMapOf<String, Any?>(
                    "supported" to false,
                    "port" to null,
                    "portSource" to VpnPortSource.UNKNOWN,
                    "dynamicPort" to false,
                ),
            ),
            VpnProtocolNames.OPENVPN to linkedMapOf<String, Any?>(
                "supported" to false,
                "tcp" to linkedMapOf<String, Any?>(
                    "supported" to false,
                    "port" to null,
                    "portSource" to VpnPortSource.UNKNOWN,
                ),
                "udp" to linkedMapOf<String, Any?>(
                    "supported" to false,
                    "port" to null,
                    "portSource" to VpnPortSource.UNKNOWN,
                ),
                "configAvailable" to false,
                "configs" to mutableListOf<OpenVpnRemote>(),
            ),
            VpnProtocolNames.L2TP_IPSEC to linkedMapOf<String, Any?>(
                "supported" to false,
                "port" to null,
                "portSource" to VpnPortSource.UNKNOWN,
            ),
            VpnProtocolNames.SSTP to linkedMapOf<String, Any?>(
                "supported" to false,
                "hostname" to "",
                "port" to null,
                "portSource" to VpnPortSource.UNKNOWN,
            ),
        )
        rec["sources"] = mutableListOf<String>()
        rec["sourceCount"] = 0
        rec["fieldSources"] = LinkedHashMap<String, MutableList<String>>()
        rec["quality"] = linkedMapOf<String, Any?>(
            "overall" to 0,
            "softether" to 0,
            "openvpn" to 0,
            "sstp" to 0,
            "l2tp" to 0,
        )
        rec["valid"] = true
        return rec
    }

    /**
     * Set a nested field and remember which source supplied it.
     * Empty values do not overwrite meaningful values.
     *
     * Stage 2F-A (L-1): "empty" is [isUnset], which also treats the canonical
     * UNKNOWN provenance as unset. UNKNOWN used to be the empty string, so
     * every freshly constructed record already carried a non-empty sentinel in
     * each `portSource` leaf and this check would have read it as a meaningful
     * value, refusing every later EXPLICIT/DEFAULT write from a parser.
     */
    fun setField(
        record: VpnServerRecord,
        path: String,
        value: Any?,
        source: String,
        overwrite: Boolean = false,
    ) {
        if (isUnset(path, value)) return

        val parts = path.split(".")
        var obj: MutableMap<String, Any?> = record
        for (part in parts.dropLast(1)) {
            @Suppress("UNCHECKED_CAST")
            val child = obj[part] as? MutableMap<String, Any?> ?: run {
                val created = LinkedHashMap<String, Any?>()
                obj[part] = created
                created
            }
            obj = child
        }
        val leaf = parts.last()
        val old = obj[leaf]

        if (overwrite || isUnset(path, old)) {
            obj[leaf] = value
        }

        fieldSources(record).getOrPut(path) { mutableListOf() }.let { sources ->
            if (source !in sources) sources.add(source)
        }
    }

    fun markSupported(record: VpnServerRecord, protocol: String, source: String) {
        setField(record, "protocols.$protocol.supported", true, source)
    }

    /** Record a provenance tag on a server record (§16). */
    fun sourceAdd(record: VpnServerRecord, source: String) {
        if (source.isEmpty()) return
        val sources = sources(record)
        if (source !in sources) {
            sources.add(source)
            sources.sort()
        }
        record["sourceCount"] = sources.size
    }

    @Suppress("UNCHECKED_CAST")
    fun map(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun sources(record: VpnServerRecord): MutableList<String> =
        record["sources"] as MutableList<String>

    @Suppress("UNCHECKED_CAST")
    fun fieldSources(record: VpnServerRecord): MutableMap<String, MutableList<String>> =
        record["fieldSources"] as MutableMap<String, MutableList<String>>

    @Suppress("UNCHECKED_CAST")
    fun conflicts(record: VpnServerRecord): List<Conflict> =
        (record["conflicts"] as? List<Map<String, Any?>>).orEmpty().map { item ->
            Conflict(
                field = item["field"] as? String ?: "",
                values = item["values"] as? Map<String, Any?> ?: emptyMap(),
            )
        }

    fun identity(record: VpnServerRecord): MutableMap<String, Any?> = map(record["identity"])
    fun performance(record: VpnServerRecord): MutableMap<String, Any?> = map(record["performance"])
    fun protocols(record: VpnServerRecord): MutableMap<String, Any?> = map(record["protocols"])
    fun protocol(record: VpnServerRecord, name: String): MutableMap<String, Any?> =
        map(protocols(record)[name])

    fun transport(protocolMap: MutableMap<String, Any?>, name: String): MutableMap<String, Any?> =
        map(protocolMap[name])

    fun bool(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        null -> false
        else -> true
    }

    fun num(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        null -> 0.0
        else -> VpnUtil.toFloat(value)
    }

    fun int(value: Any?): Int = when (value) {
        is Number -> value.toInt()
        null -> 0
        else -> VpnUtil.toInt(value)
    }

    fun str(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> value.toString()
    }
}
