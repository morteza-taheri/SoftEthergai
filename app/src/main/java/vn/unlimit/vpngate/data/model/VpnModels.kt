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
 * Pluggable sink so unit tests (JVM) and the app (Logcat) both work.
 *
 * Developer Mode gate (Settings): when disabled, every collector log call
 * becomes a no-op — no string building, no logcat writes, no logcat tap —
 * to keep the app fast. Default is ON for troubleshooting.
 */
object CollectorLog {
    var sink: (String) -> Unit = { println(it) }

    @Volatile
    var enabled: Boolean = true

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
                "tcp" to linkedMapOf<String, Any?>("supported" to false, "port" to null),
                "udp" to linkedMapOf<String, Any?>("supported" to false, "port" to null),
            ),
            VpnProtocolNames.OPENVPN to linkedMapOf<String, Any?>(
                "supported" to false,
                "tcp" to linkedMapOf<String, Any?>("supported" to false, "port" to null),
                "udp" to linkedMapOf<String, Any?>("supported" to false, "port" to null),
                "configAvailable" to false,
                "configs" to mutableListOf<OpenVpnRemote>(),
            ),
            VpnProtocolNames.L2TP_IPSEC to linkedMapOf<String, Any?>(
                "supported" to false,
                "port" to null,
            ),
            VpnProtocolNames.SSTP to linkedMapOf<String, Any?>(
                "supported" to false,
                "hostname" to "",
                "port" to null,
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
     */
    fun setField(
        record: VpnServerRecord,
        path: String,
        value: Any?,
        source: String,
        overwrite: Boolean = false,
    ) {
        val empty = value == null ||
            (value is String && value.isEmpty()) ||
            (value is Boolean && !value) ||
            (value is Number && value.toDouble() == 0.0) ||
            (value is List<*> && value.isEmpty())
        if (empty) return

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
        val oldEmpty = old == null ||
            (old is String && old.isEmpty()) ||
            (old is Boolean && !old) ||
            (old is Number && old.toDouble() == 0.0) ||
            (old is List<*> && old.isEmpty())

        if (overwrite || oldEmpty) {
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
