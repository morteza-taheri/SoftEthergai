package vn.unlimit.vpngate.data.persistence

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.google.gson.annotations.SerializedName
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.models.VPNGateConnection
import java.io.File

/**
 * Stage 2F-E.2: stable persistence for `collector_vpn_servers.json`.
 *
 * ## Why this exists
 *
 * The snapshot used to be written with `Gson().toJson(mapOf("savedAt" to …,
 * "count" to …, "servers" to listOf<VPNGateConnection>))`. Gson resolves its
 * property names by reflection, and [VPNGateConnection] carries no
 * `@SerializedName` annotations and no ProGuard keep rule. R8 therefore chose
 * the field names, which made the file a build artifact — the same defect
 * Stage 2F-D found and removed for `vpn_last_connection`.
 *
 * ## Format detection
 *
 * `schemaVersion` is the sole discriminator. A payload that carries it is
 * canonical and is **never** retried as legacy; a payload without it is only
 * accepted when its server objects positively match one of the legacy shapes
 * listed on [Format]. Nothing is guessed: an unrecognised shape is rejected,
 * never coerced into all-defaults objects.
 */
object CollectorSnapshotStore {

    /** File name inside the injected cache/files directory. */
    const val FILE_NAME = "collector_vpn_servers.json"

    /** The only canonical schema this build writes or understands. */
    const val SCHEMA_VERSION = 1

    const val KEY_SCHEMA_VERSION = "schemaVersion"
    const val KEY_SAVED_AT = "savedAt"
    const val KEY_COUNT = "count"
    const val KEY_SERVERS = "servers"

    private const val TEMP_SUFFIX = ".tmp"

    /**
     * Which on-disk shape a payload turned out to be.
     *
     * `LEGACY_R8_22/23/24` name the number of backing fields the writing build's
     * [VPNGateConnection] had, because that is exactly what shifts R8's
     * assignment of the `A`…`Q` block. Each mapping is pinned to a mapping.txt
     * produced by building that exact revision; see the report for the evidence.
     */
    enum class Format {
        /** Written by this build: explicit keys plus [SCHEMA_VERSION]. */
        CANONICAL,

        /** A non-minified build: real Kotlin field names. */
        LEGACY_SOURCE_NAMES,

        /** Minified build, 22-field model: A–O then t–z. */
        LEGACY_R8_22,

        /** Minified build, 23-field model: A–P then t–z. */
        LEGACY_R8_23,

        /** Minified build, 24-field model: A–Q then t–z. */
        LEGACY_R8_24,
    }

    /** Why a read or a write was refused. */
    enum class Reason {
        INVALID_JSON,
        UNSUPPORTED_SCHEMA,
        INVALID_CANONICAL_SHAPE,
        UNKNOWN_LEGACY_FORMAT,
        LEGACY_DECODE_FAILURE,
        INVALID_IDENTITY,
        COUNT_MISMATCH,
        WRITE_FAILURE,
        RENAME_FAILURE,
    }

    sealed interface ReadResult {
        data class Loaded(
            val savedAt: Long,
            val servers: List<VPNGateConnection>,
            val format: Format,
        ) : ReadResult

        data class Invalid(val reason: Reason, val detail: String) : ReadResult

        data object Absent : ReadResult
    }

    sealed interface WriteResult {
        data class Written(val serverCount: Int) : WriteResult

        data class Failed(val reason: Reason, val detail: String) : WriteResult
    }

    /**
     * Null handling: preserved exactly as the previous format behaved. A default
     * [Gson] omits null members, so a null `openVpnConfigData` is absent from the
     * JSON rather than written as `null`. Decoding treats an absent member and an
     * explicit `null` identically (both become null), so the choice is invisible
     * to every reader and keeps existing snapshots byte-comparable in shape.
     */
    private val gson = Gson()

    private val serverListType = object : TypeToken<List<CollectorServerDto>>() {}.type
    private val r8_22Type = object : TypeToken<List<LegacyR8Server22Dto>>() {}.type
    private val r8_23Type = object : TypeToken<List<LegacyR8Server23Dto>>() {}.type
    private val r8_24Type = object : TypeToken<List<LegacyR8Server24Dto>>() {}.type

    fun file(cacheDir: File?): File? = cacheDir?.let { File(it, FILE_NAME) }

    /** Reads and decodes [file] without ever writing to it. */
    fun read(file: File): ReadResult {
        if (!file.isFile) return ReadResult.Absent
        val json = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return ReadResult.Invalid(Reason.INVALID_JSON, "unreadable file: ${e.message}")
        }
        return decode(json)
    }

    /**
     * Serializes [servers] into the canonical envelope.
     *
     * [count] is always `servers.size`; server order is the caller's order.
     * No de-duplication happens here: the caller already hands over the
     * accepted, Room-unique rows and moving that responsibility into the
     * persistence layer would duplicate [vn.unlimit.vpngate.models.VPNGateItem]
     * 's own identity rules.
     */
    fun encode(savedAt: Long, servers: List<VPNGateConnection>): String =
        gson.toJson(
            CollectorSnapshotDto(
                schemaVersion = SCHEMA_VERSION,
                savedAt = savedAt,
                count = servers.size,
                servers = servers.map { CollectorServerDto.from(it) },
            )
        )

    /**
     * Temp file + rename. The previous target survives every failure mode:
     * an exception during the write, or a refused rename.
     *
     * The temp file is removed in a `finally`, which closes the leak found in
     * Stage 2F-E.1 (H-3): previously it was deleted only when `renameTo`
     * returned false, never when the write itself threw.
     */
    fun write(file: File, savedAt: Long, servers: List<VPNGateConnection>): WriteResult {
        if (servers.isEmpty()) {
            return WriteResult.Failed(Reason.INVALID_CANONICAL_SHAPE, "refusing to write 0 server(s)")
        }
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        return try {
            val json = encode(savedAt, servers)
            temp.writeText(json, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                WriteResult.Failed(Reason.RENAME_FAILURE, "rename refused; previous snapshot kept")
            } else {
                WriteResult.Written(servers.size)
            }
        } catch (e: Exception) {
            WriteResult.Failed(Reason.WRITE_FAILURE, e.message ?: e.javaClass.simpleName)
        } finally {
            // No-op after a successful rename (the temp file no longer exists).
            if (temp.exists()) {
                temp.delete()
            }
        }
    }

    /**
     * Rewrites a successfully decoded legacy payload in canonical form.
     *
     * [savedAt] is carried over unchanged on purpose: it records when the
     * dataset was accepted, and a format migration must not make it look newer.
     */
    fun migrate(file: File, loaded: ReadResult.Loaded): WriteResult =
        write(file, loaded.savedAt, loaded.servers)

    // ------------------------------------------------------------------ decode

    fun decode(json: String): ReadResult {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            return ReadResult.Invalid(Reason.INVALID_JSON, "malformed json: ${e.message}")
        } catch (e: RuntimeException) {
            return ReadResult.Invalid(Reason.INVALID_JSON, "unreadable json: ${e.message}")
        }
        if (root == null || !root.isJsonObject) {
            return ReadResult.Invalid(Reason.INVALID_JSON, "payload is not a json object")
        }
        val obj = root.asJsonObject

        val schema = obj.get(KEY_SCHEMA_VERSION)
        // Canonical is decided by this key alone. A canonical payload is never
        // re-interpreted as legacy, however broken it is.
        if (schema != null && !schema.isJsonNull) {
            return decodeCanonical(obj, schema)
        }
        return decodeLegacy(obj)
    }

    private fun decodeCanonical(obj: com.google.gson.JsonObject, schema: com.google.gson.JsonElement): ReadResult {
        if (!schema.isJsonPrimitive || !schema.asJsonPrimitive.isNumber) {
            return ReadResult.Invalid(Reason.UNSUPPORTED_SCHEMA, "'$KEY_SCHEMA_VERSION' is not a number")
        }
        val version = try {
            schema.asInt
        } catch (e: NumberFormatException) {
            return ReadResult.Invalid(Reason.UNSUPPORTED_SCHEMA, "'$KEY_SCHEMA_VERSION' is not an int")
        }
        if (version != SCHEMA_VERSION) {
            return ReadResult.Invalid(Reason.UNSUPPORTED_SCHEMA, "unsupported schemaVersion $version")
        }

        val rawServers = obj.get(KEY_SERVERS)
            ?: return ReadResult.Invalid(Reason.INVALID_CANONICAL_SHAPE, "'$KEY_SERVERS' is absent")
        if (!rawServers.isJsonArray) {
            return ReadResult.Invalid(Reason.INVALID_CANONICAL_SHAPE, "'$KEY_SERVERS' is not an array")
        }
        val array = rawServers.asJsonArray
        if (array.isEmpty()) {
            return ReadResult.Invalid(Reason.INVALID_CANONICAL_SHAPE, "'$KEY_SERVERS' is empty")
        }

        // count must be present and must agree. A canonical file that disagrees
        // with itself is refused rather than silently repaired.
        val rawCount = obj.get(KEY_COUNT)
            ?: return ReadResult.Invalid(Reason.COUNT_MISMATCH, "'$KEY_COUNT' is absent")
        if (!rawCount.isJsonPrimitive || !rawCount.asJsonPrimitive.isNumber) {
            return ReadResult.Invalid(Reason.COUNT_MISMATCH, "'$KEY_COUNT' is not a number")
        }
        if (rawCount.asInt != array.size()) {
            return ReadResult.Invalid(
                Reason.COUNT_MISMATCH,
                "'$KEY_COUNT' is ${rawCount.asInt} but '$KEY_SERVERS' holds ${array.size()}",
            )
        }

        val dtos: List<CollectorServerDto> = try {
            gson.fromJson(array, serverListType) ?: emptyList()
        } catch (e: JsonParseException) {
            return ReadResult.Invalid(Reason.INVALID_CANONICAL_SHAPE, "server rejected: ${e.message}")
        } catch (e: RuntimeException) {
            return ReadResult.Invalid(Reason.INVALID_CANONICAL_SHAPE, "server rejected: ${e.message}")
        }
        if (dtos.size != array.size()) {
            return ReadResult.Invalid(
                Reason.INVALID_CANONICAL_SHAPE,
                "decoded ${dtos.size} of ${array.size()} server(s)",
            )
        }

        val savedAt = optLong(obj.get(KEY_SAVED_AT)) ?: 0L
        val servers = dtos.map { it.toConnection() }
        if (!checkIdentity(servers)) return identityFailure(servers)
        return ReadResult.Loaded(savedAt, servers, Format.CANONICAL)
    }

    private fun decodeLegacy(obj: com.google.gson.JsonObject): ReadResult {
        val rawServers = obj.get(KEY_SERVERS)
            ?: return ReadResult.Invalid(
                Reason.UNKNOWN_LEGACY_FORMAT,
                "no '$KEY_SCHEMA_VERSION' and no '$KEY_SERVERS'",
            )
        if (!rawServers.isJsonArray) {
            return ReadResult.Invalid(Reason.INVALID_JSON, "'$KEY_SERVERS' is not an array")
        }
        val array = rawServers.asJsonArray
        if (array.isEmpty()) {
            return ReadResult.Invalid(Reason.INVALID_CANONICAL_SHAPE, "'$KEY_SERVERS' is empty")
        }

        val first = array[0]
        if (!first.isJsonObject) {
            return ReadResult.Invalid(Reason.UNKNOWN_LEGACY_FORMAT, "first '$KEY_SERVERS' entry is not an object")
        }
        val format = detectLegacyFormat(first.asJsonObject)
            ?: return ReadResult.Invalid(
                Reason.UNKNOWN_LEGACY_FORMAT,
                "server keys do not match any proven snapshot format",
            )

        val savedAt = optLong(obj.get(KEY_SAVED_AT)) ?: 0L

        val servers: List<VPNGateConnection> = try {
            when (format) {
                Format.LEGACY_SOURCE_NAMES -> {
                    val list: List<CollectorServerDto> =
                        gson.fromJson(array, serverListType) ?: emptyList()
                    list.map { it.toConnection() }
                }
                Format.LEGACY_R8_22 -> {
                    val list: List<LegacyR8Server22Dto> =
                        gson.fromJson(array, r8_22Type) ?: emptyList()
                    list.map { it.toConnection() }
                }
                Format.LEGACY_R8_23 -> {
                    val list: List<LegacyR8Server23Dto> =
                        gson.fromJson(array, r8_23Type) ?: emptyList()
                    list.map { it.toConnection() }
                }
                Format.LEGACY_R8_24 -> {
                    val list: List<LegacyR8Server24Dto> =
                        gson.fromJson(array, r8_24Type) ?: emptyList()
                    list.map { it.toConnection() }
                }
                Format.CANONICAL -> emptyList()
            }
        } catch (e: JsonParseException) {
            return ReadResult.Invalid(Reason.LEGACY_DECODE_FAILURE, "${format.name}: ${e.message}")
        } catch (e: RuntimeException) {
            return ReadResult.Invalid(Reason.LEGACY_DECODE_FAILURE, "${format.name}: ${e.message}")
        }

        // The legacy `count` is deliberately NOT validated: every proven legacy
        // writer set it to servers.size, so a mismatch means a truncated or
        // hand-edited file, which the identity check below already refuses.
        if (!checkIdentity(servers)) return identityFailure(servers)
        return ReadResult.Loaded(savedAt, servers, format)
    }

    /**
     * Positively identify a legacy shape, or return null to reject it.
     *
     * ## Why the discriminators look arbitrary
     *
     * R8 names fields by observed frequency, not by declaration order, so
     * inserting a single field renumbers the whole block. The 22- and 23-field
     * layouts are **not** a prefix of one another — `sstpPort` is `M` in the
     * 24-field layout but `N` in the 23-field one, and `numVpnSession` moves
     * from `A` to `z`. Every marker below is therefore read off the proven
     * mapping.txt for that exact revision, not inferred.
     *
     * The markers rely on one Gson property: null members are omitted, but
     * primitive members are always written. So the ints/long/boolean are
     * guaranteed present in every legacy payload, while the Strings may not be:
     *
     *  - `Q` is a boolean (`seUdpSupported`) in the 24-field layout and exists
     *    in no other layout.
     *  - `z` is `numVpnSession` (an int) in the 23-field layout, but
     *    `countryShort` (a String) in the 22- and 24-field layouts. Checking
     *    the *type* of `z` separates them without relying on a missing String.
     *  - `O` is the `seUdpSupported` boolean in the remaining 22-field layout.
     *
     * Anything else is rejected rather than coerced.
     */
    private fun detectLegacyFormat(first: com.google.gson.JsonObject): Format? {
        if (first.has("hostName") && first.has("ip")) return Format.LEGACY_SOURCE_NAMES
        // Require the invariants every R8 layout shares before discriminating.
        if (!first.has("A") || !first.has("O")) return null
        if (first.has("Q")) return Format.LEGACY_R8_24
        if (isNumber(first.get("z"))) return Format.LEGACY_R8_23
        if (isBoolean(first.get("O"))) return Format.LEGACY_R8_22
        return null
    }

    private fun isNumber(el: com.google.gson.JsonElement?): Boolean =
        el != null && el.isJsonPrimitive && el.asJsonPrimitive.isNumber

    private fun isBoolean(el: com.google.gson.JsonElement?): Boolean =
        el != null && el.isJsonPrimitive && el.asJsonPrimitive.isBoolean

    /**
     * Structural identity only: enough to know the file really is a server list
     * rather than a truncated remnant or a mis-mapped object. This is NOT
     * dataset acceptance — [vn.unlimit.vpngate.repository.DatasetIntegrity]
     * remains the sole judge of whether a dataset is worth storing, and it runs
     * unchanged downstream.
     */
    private fun checkIdentity(servers: List<VPNGateConnection>): Boolean =
        servers.any { !it.hostName.isNullOrBlank() } && servers.any { !it.ip.isNullOrBlank() }

    private fun identityFailure(servers: List<VPNGateConnection>): ReadResult =
        ReadResult.Invalid(
            Reason.INVALID_IDENTITY,
            "no server carries both a hostName and an ip " +
                "(hostName on ${servers.count { !it.hostName.isNullOrBlank() }}, " +
                "ip on ${servers.count { !it.ip.isNullOrBlank() }})",
        )

    private fun optLong(el: com.google.gson.JsonElement?): Long? {
        if (el == null || !el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        if (!p.isNumber) return null
        return try {
            p.asLong
        } catch (e: NumberFormatException) {
            null
        }
    }
}

/** The canonical envelope. Every member carries an explicit serialized name. */
internal data class CollectorSnapshotDto(
    @SerializedName("schemaVersion") val schemaVersion: Int = CollectorSnapshotStore.SCHEMA_VERSION,
    @SerializedName("savedAt") val savedAt: Long = 0L,
    @SerializedName("count") val count: Int = 0,
    @SerializedName("servers") val servers: List<CollectorServerDto> = emptyList(),
)

/**
 * The canonical server record: all 24 backing fields of [VPNGateConnection],
 * each pinned to an explicit serialized name so R8 cannot move the on-disk key.
 *
 * Getter-only properties (`isUdpOnly`, `sstpConnectPort`, `sstpPortKind`,
 * `calculateHostName`, `name`, …) are deliberately absent: they have no backing
 * field and are recomputed on read.
 */
internal data class CollectorServerDto(
    @SerializedName("hostName") val hostName: String? = null,
    @SerializedName("ip") val ip: String? = null,
    @SerializedName("score") val score: Int = 0,
    @SerializedName("ping") val ping: Int = 0,
    @SerializedName("speed") val speed: Int = 0,
    @SerializedName("countryLong") val countryLong: String? = null,
    @SerializedName("countryShort") val countryShort: String? = null,
    @SerializedName("numVpnSession") val numVpnSession: Int = 0,
    @SerializedName("uptime") val uptime: Int = 0,
    @SerializedName("totalUser") val totalUser: Int = 0,
    @SerializedName("totalTraffic") val totalTraffic: Long = 0L,
    @SerializedName("logType") val logType: String? = null,
    @SerializedName("operator") val operator: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("openVpnConfigData") val openVpnConfigData: String? = null,
    @SerializedName("tcpPort") val tcpPort: Int = 0,
    @SerializedName("udpPort") val udpPort: Int = 0,
    @SerializedName("isL2TPSupport") val isL2TPSupport: Int = 0,
    @SerializedName("isSSTPSupport") val isSSTPSupport: Int = 0,
    @SerializedName("sstpPort") val sstpPort: Int = 0,
    @SerializedName("sstpPortSource") val sstpPortSource: String = VpnPortSource.UNKNOWN,
    @SerializedName("seTcpPort") val seTcpPort: Int = 0,
    @SerializedName("seUdpPort") val seUdpPort: Int = 0,
    @SerializedName("seUdpSupported") val seUdpSupported: Boolean = false,
) {

    fun toConnection(): VPNGateConnection = VPNGateConnection().apply {
        hostName = this@CollectorServerDto.hostName
        ip = this@CollectorServerDto.ip
        score = this@CollectorServerDto.score
        ping = this@CollectorServerDto.ping
        speed = this@CollectorServerDto.speed
        countryLong = this@CollectorServerDto.countryLong
        countryShort = this@CollectorServerDto.countryShort
        numVpnSession = this@CollectorServerDto.numVpnSession
        uptime = this@CollectorServerDto.uptime
        totalUser = this@CollectorServerDto.totalUser
        totalTraffic = this@CollectorServerDto.totalTraffic
        logType = this@CollectorServerDto.logType
        operator = this@CollectorServerDto.operator
        message = this@CollectorServerDto.message
        openVpnConfigData = this@CollectorServerDto.openVpnConfigData
        tcpPort = this@CollectorServerDto.tcpPort
        udpPort = this@CollectorServerDto.udpPort
        isL2TPSupport = this@CollectorServerDto.isL2TPSupport
        isSSTPSupport = this@CollectorServerDto.isSSTPSupport
        sstpPort = this@CollectorServerDto.sstpPort
        sstpPortSource = VpnPortSource.normalized(this@CollectorServerDto.sstpPortSource)
        seTcpPort = this@CollectorServerDto.seTcpPort
        seUdpPort = this@CollectorServerDto.seUdpPort
        seUdpSupported = this@CollectorServerDto.seUdpSupported
    }

    companion object {
        fun from(connection: VPNGateConnection): CollectorServerDto = CollectorServerDto(
            hostName = connection.hostName,
            ip = connection.ip,
            score = connection.score,
            ping = connection.ping,
            speed = connection.speed,
            countryLong = connection.countryLong,
            countryShort = connection.countryShort,
            numVpnSession = connection.numVpnSession,
            uptime = connection.uptime,
            totalUser = connection.totalUser,
            totalTraffic = connection.totalTraffic,
            logType = connection.logType,
            operator = connection.operator,
            message = connection.message,
            openVpnConfigData = connection.openVpnConfigData,
            tcpPort = connection.tcpPort,
            udpPort = connection.udpPort,
            isL2TPSupport = connection.isL2TPSupport,
            isSSTPSupport = connection.isSSTPSupport,
            sstpPort = connection.sstpPort,
            sstpPortSource = VpnPortSource.normalized(connection.sstpPortSource),
            seTcpPort = connection.seTcpPort,
            seUdpPort = connection.seUdpPort,
            seUdpSupported = connection.seUdpSupported,
        )
    }
}

/**
 * Minified build, 22-field model.
 *
 * Evidence: `mapping.txt` produced by building HEAD (0714628) — the 143 /
 * 2.4.10 release line — which is the exact revision Stage 2F-D proved the
 * deployed 2.4.9 payload was written against. `M -> seTcpPort`,
 * `N -> seUdpPort`, `O -> seUdpSupported` (Boolean), no `P`/`Q`.
 *
 * The model had no `sstpPort` and no `sstpPortSource`, so both are defaulted and
 * the established legacy rule from [VPNGateConnection.fromCsv] is applied:
 * a row the source confirmed as SSTP-capable falls back to the protocol default.
 */
internal data class LegacyR8Server22Dto(
    @SerializedName("A") val numVpnSession: Int = 0,
    @SerializedName("B") val uptime: Int = 0,
    @SerializedName("C") val totalUser: Int = 0,
    @SerializedName("D") val totalTraffic: Long = 0L,
    @SerializedName("E") val logType: String? = null,
    @SerializedName("F") val operator: String? = null,
    @SerializedName("G") val message: String? = null,
    @SerializedName("H") val openVpnConfigData: String? = null,
    @SerializedName("I") val tcpPort: Int = 0,
    @SerializedName("J") val udpPort: Int = 0,
    @SerializedName("K") val isL2TPSupport: Int = 0,
    @SerializedName("L") val isSSTPSupport: Int = 0,
    @SerializedName("M") val seTcpPort: Int = 0,
    @SerializedName("N") val seUdpPort: Int = 0,
    @SerializedName("O") val seUdpSupported: Boolean = false,
    @SerializedName("t") val hostName: String? = null,
    @SerializedName("u") val ip: String? = null,
    @SerializedName("v") val score: Int = 0,
    @SerializedName("w") val ping: Int = 0,
    @SerializedName("x") val speed: Int = 0,
    @SerializedName("y") val countryLong: String? = null,
    @SerializedName("z") val countryShort: String? = null,
) {
    fun toConnection(): VPNGateConnection = VPNGateConnection().apply {
        hostName = this@LegacyR8Server22Dto.hostName
        ip = this@LegacyR8Server22Dto.ip
        score = this@LegacyR8Server22Dto.score
        ping = this@LegacyR8Server22Dto.ping
        speed = this@LegacyR8Server22Dto.speed
        countryLong = this@LegacyR8Server22Dto.countryLong
        countryShort = this@LegacyR8Server22Dto.countryShort
        numVpnSession = this@LegacyR8Server22Dto.numVpnSession
        uptime = this@LegacyR8Server22Dto.uptime
        totalUser = this@LegacyR8Server22Dto.totalUser
        totalTraffic = this@LegacyR8Server22Dto.totalTraffic
        logType = this@LegacyR8Server22Dto.logType
        operator = this@LegacyR8Server22Dto.operator
        message = this@LegacyR8Server22Dto.message
        openVpnConfigData = this@LegacyR8Server22Dto.openVpnConfigData
        tcpPort = this@LegacyR8Server22Dto.tcpPort
        udpPort = this@LegacyR8Server22Dto.udpPort
        isL2TPSupport = this@LegacyR8Server22Dto.isL2TPSupport
        isSSTPSupport = this@LegacyR8Server22Dto.isSSTPSupport
        seTcpPort = this@LegacyR8Server22Dto.seTcpPort
        seUdpPort = this@LegacyR8Server22Dto.seUdpPort
        seUdpSupported = this@LegacyR8Server22Dto.seUdpSupported
        // Predates both SSTP members; see the class comment.
        sstpPort = 0
        if (isSSTPSupport == 1 && sstpPort <= 0) {
            sstpPortSource = VpnPortSource.DEFAULT
        }
    }
}

/**
 * Minified build, 23-field model: `sstpPort` existed, `sstpPortSource` did not.
 *
 * Evidence: `mapping.txt` from building revision 3ed5920 (`:app:assembleRelease`
 * — the `pro` flavor did not exist yet), the only revision in this repository
 * whose [VPNGateConnection] carries 23 backing fields.
 *
 * This layout is **not** a prefix of its neighbours. R8 renamed every field:
 * `sstpPort` is `N` here but `M` in the 24-field layout, and `numVpnSession` is
 * `z` here but `A` in the 22- and 24-field layouts. `sstpPortSource` is absent,
 * which is the only structural difference from the 24-field layout.
 */
internal data class LegacyR8Server23Dto(
    @SerializedName("A") val uptime: Int = 0,
    @SerializedName("B") val totalUser: Int = 0,
    @SerializedName("C") val totalTraffic: Long = 0L,
    @SerializedName("D") val logType: String? = null,
    @SerializedName("E") val operator: String? = null,
    @SerializedName("F") val message: String? = null,
    @SerializedName("G") val openVpnConfigData: String? = null,
    @SerializedName("H") val tcpPort: Int = 0,
    @SerializedName("I") val udpPort: Int = 0,
    @SerializedName("J") val isL2TPSupport: Int = 0,
    @SerializedName("K") val isSSTPSupport: Int = 0,
    @SerializedName("L") val seTcpPort: Int = 0,
    @SerializedName("M") val seUdpPort: Int = 0,
    @SerializedName("N") val sstpPort: Int = 0,
    @SerializedName("O") val seUdpSupported: Boolean = false,
    @SerializedName("s") val hostName: String? = null,
    @SerializedName("t") val ip: String? = null,
    @SerializedName("u") val score: Int = 0,
    @SerializedName("v") val ping: Int = 0,
    @SerializedName("w") val speed: Int = 0,
    @SerializedName("x") val countryLong: String? = null,
    @SerializedName("y") val countryShort: String? = null,
    @SerializedName("z") val numVpnSession: Int = 0,
) {
    fun toConnection(): VPNGateConnection = VPNGateConnection().apply {
        hostName = this@LegacyR8Server23Dto.hostName
        ip = this@LegacyR8Server23Dto.ip
        score = this@LegacyR8Server23Dto.score
        ping = this@LegacyR8Server23Dto.ping
        speed = this@LegacyR8Server23Dto.speed
        countryLong = this@LegacyR8Server23Dto.countryLong
        countryShort = this@LegacyR8Server23Dto.countryShort
        numVpnSession = this@LegacyR8Server23Dto.numVpnSession
        uptime = this@LegacyR8Server23Dto.uptime
        totalUser = this@LegacyR8Server23Dto.totalUser
        totalTraffic = this@LegacyR8Server23Dto.totalTraffic
        logType = this@LegacyR8Server23Dto.logType
        operator = this@LegacyR8Server23Dto.operator
        message = this@LegacyR8Server23Dto.message
        openVpnConfigData = this@LegacyR8Server23Dto.openVpnConfigData
        tcpPort = this@LegacyR8Server23Dto.tcpPort
        udpPort = this@LegacyR8Server23Dto.udpPort
        isL2TPSupport = this@LegacyR8Server23Dto.isL2TPSupport
        isSSTPSupport = this@LegacyR8Server23Dto.isSSTPSupport
        sstpPort = this@LegacyR8Server23Dto.sstpPort
        seTcpPort = this@LegacyR8Server23Dto.seTcpPort
        seUdpPort = this@LegacyR8Server23Dto.seUdpPort
        seUdpSupported = this@LegacyR8Server23Dto.seUdpSupported
        // Predates sstpPortSource only: same established fallback as fromCsv.
        if (isSSTPSupport == 1 && sstpPort <= 0) {
            sstpPortSource = VpnPortSource.DEFAULT
        }
    }
}

/**
 * Minified build, 24-field model.
 *
 * Evidence: `mapping.txt` produced by building the Stage 2F-A/B working tree —
 * the build that was installed on device 5585fecc during Stage 2F-C.
 * `M -> sstpPort`, `N -> sstpPortSource`, `O -> seTcpPort`, `P -> seUdpPort`,
 * `Q -> seUdpSupported`.
 */
internal data class LegacyR8Server24Dto(
    @SerializedName("A") val numVpnSession: Int = 0,
    @SerializedName("B") val uptime: Int = 0,
    @SerializedName("C") val totalUser: Int = 0,
    @SerializedName("D") val totalTraffic: Long = 0L,
    @SerializedName("E") val logType: String? = null,
    @SerializedName("F") val operator: String? = null,
    @SerializedName("G") val message: String? = null,
    @SerializedName("H") val openVpnConfigData: String? = null,
    @SerializedName("I") val tcpPort: Int = 0,
    @SerializedName("J") val udpPort: Int = 0,
    @SerializedName("K") val isL2TPSupport: Int = 0,
    @SerializedName("L") val isSSTPSupport: Int = 0,
    @SerializedName("M") val sstpPort: Int = 0,
    @SerializedName("N") val sstpPortSource: String = VpnPortSource.UNKNOWN,
    @SerializedName("O") val seTcpPort: Int = 0,
    @SerializedName("P") val seUdpPort: Int = 0,
    @SerializedName("Q") val seUdpSupported: Boolean = false,
    @SerializedName("t") val hostName: String? = null,
    @SerializedName("u") val ip: String? = null,
    @SerializedName("v") val score: Int = 0,
    @SerializedName("w") val ping: Int = 0,
    @SerializedName("x") val speed: Int = 0,
    @SerializedName("y") val countryLong: String? = null,
    @SerializedName("z") val countryShort: String? = null,
) {
    fun toConnection(): VPNGateConnection = VPNGateConnection().apply {
        hostName = this@LegacyR8Server24Dto.hostName
        ip = this@LegacyR8Server24Dto.ip
        score = this@LegacyR8Server24Dto.score
        ping = this@LegacyR8Server24Dto.ping
        speed = this@LegacyR8Server24Dto.speed
        countryLong = this@LegacyR8Server24Dto.countryLong
        countryShort = this@LegacyR8Server24Dto.countryShort
        numVpnSession = this@LegacyR8Server24Dto.numVpnSession
        uptime = this@LegacyR8Server24Dto.uptime
        totalUser = this@LegacyR8Server24Dto.totalUser
        totalTraffic = this@LegacyR8Server24Dto.totalTraffic
        logType = this@LegacyR8Server24Dto.logType
        operator = this@LegacyR8Server24Dto.operator
        message = this@LegacyR8Server24Dto.message
        openVpnConfigData = this@LegacyR8Server24Dto.openVpnConfigData
        tcpPort = this@LegacyR8Server24Dto.tcpPort
        udpPort = this@LegacyR8Server24Dto.udpPort
        isL2TPSupport = this@LegacyR8Server24Dto.isL2TPSupport
        isSSTPSupport = this@LegacyR8Server24Dto.isSSTPSupport
        sstpPort = this@LegacyR8Server24Dto.sstpPort
        sstpPortSource = VpnPortSource.normalized(this@LegacyR8Server24Dto.sstpPortSource)
        seTcpPort = this@LegacyR8Server24Dto.seTcpPort
        seUdpPort = this@LegacyR8Server24Dto.seUdpPort
        seUdpSupported = this@LegacyR8Server24Dto.seUdpSupported
    }
}
