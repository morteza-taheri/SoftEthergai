package vn.unlimit.vpngate.data.persistence

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.models.VPNGateConnection

/**
 * Stage 2F-D: stable persistence for the "vpn_last_connection" preference.
 *
 * ## Why this exists
 *
 * That preference used to be written with `Gson().toJson(connection)` over
 * [VPNGateConnection] itself. Gson resolves its property names by reflection,
 * and [VPNGateConnection] carries **no `@SerializedName` annotations and no
 * ProGuard keep rule**. R8 therefore renamed its fields freely, which made the
 * on-disk format a build artifact: the shipped 2.4.9 release stored
 *
 * ```
 * {"A":11,...,"O":true,"t":"vpn320067811","u":"60.98.34.211",...,"z":"JP"}
 * ```
 *
 * Adding two fields to [VPNGateConnection] in Stage 2F-A (`sstpPort`,
 * `sstpPortSource`) shifted R8's assignment by two slots, so `M`/`N`/`O` began
 * to name different properties. `"O": true` then bound to `seTcpPort: Int` and
 * Gson threw `JsonSyntaxException: Expected an int but was BOOLEAN`; a
 * non-minified debug build failed differently but just as fatally, because
 * *no* obfuscated key matches the real Kotlin field names, leaving `hostName`
 * null. Either way `DataUtil.lastVPNConnection` returned null and the Status
 * screen dereferenced it with `!!`.
 *
 * ## The contract
 *
 * Two formats are read, one is written.
 *
 *  - **LEGACY** — the accidental R8-named payload described above. It is decoded
 *    by [LegacyVpnLastConnectionDto], whose property names are pinned to the
 *    *control* (baseline 0714628) mapping, verbatim and by hand. The current R8
 *    mapping is never consulted, so this keeps working no matter how the next
 *    build renames anything.
 *  - **CANONICAL** — [VpnLastConnectionDto], every property carrying an explicit
 *    `@SerializedName` plus a `schemaVersion` discriminator. Gson reads the
 *    annotation, not the field name, so obfuscating, renaming, adding or
 *    removing fields on [VPNGateConnection] cannot change these keys.
 *
 * [VPNGateConnection] is deliberately never handed to Gson any more: it is the
 * only thing standing between an R8 decision and a user's saved server.
 */
object VpnLastConnectionStore {

    /** SharedPreferences key. Unchanged, so existing installs are found. */
    const val PREF_KEY = "vpn_last_connection"

    /** The only canonical schema this build writes or understands. */
    const val SCHEMA_VERSION = 1

    /** The canonical discriminator. Its absence is what marks a payload legacy. */
    const val KEY_SCHEMA_VERSION = "schemaVersion"

    /**
     * Legacy format: which of the two on-disk shapes a payload turned out to be.
     */
    enum class Format {
        /** Written by this build. Stable keys, [SCHEMA_VERSION] present. */
        CANONICAL,

        /** The pre-2F-D R8-named payload. Decoded, then rewritten as canonical. */
        LEGACY_R8,
    }

    /**
     * Outcome of reading the preference.
     *
     * [Invalid] is a real, reported state rather than a bare null, so a corrupt
     * payload is distinguishable from "no connection saved yet" in the log
     * without changing the public null-returning contract.
     */
    sealed interface DecodeResult {
        data class Decoded(
            val connection: VPNGateConnection,
            val format: Format,
        ) : DecodeResult

        data class Invalid(val reason: String) : DecodeResult
    }

    private val gson = Gson()

    /**
     * Every key the legacy payload can legally contain, from the control build's
     * mapping. Used only to recognise the shape — decoding itself is driven by
     * [LegacyVpnLastConnectionDto]'s `@SerializedName`s.
     */
    private const val LEGACY_KEYS = "ABCDEFGHIJKLMNOPtuvwxyz"

    /** Serializes [connection] to the canonical, R8-independent JSON form. */
    fun encode(connection: VPNGateConnection): String =
        gson.toJson(VpnLastConnectionDto.from(connection))

    /**
     * Reads either stored format.
     *
     * Canonical is decided by the presence of [KEY_SCHEMA_VERSION] and nothing
     * else: a payload carrying it is never retried as legacy, and a payload
     * without it is only tried as legacy when it actually contains legacy keys.
     * A random or truncated object therefore fails as invalid instead of being
     * silently reinterpreted into an all-defaults connection.
     */
    fun decode(json: String): DecodeResult {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            return DecodeResult.Invalid("malformed json: ${e.message}")
        } catch (e: RuntimeException) {
            return DecodeResult.Invalid("unreadable json: ${e.message}")
        }
        if (root == null || !root.isJsonObject) {
            return DecodeResult.Invalid("payload is not a json object")
        }
        val obj = root.asJsonObject

        val schema = obj.get(KEY_SCHEMA_VERSION)
        if (schema != null && !schema.isJsonNull) {
            return decodeCanonical(obj)
        }

        val legacyKeyCount = LEGACY_KEYS.count { obj.has(it.toString()) }
        if (legacyKeyCount == 0) {
            return DecodeResult.Invalid(
                "no '$KEY_SCHEMA_VERSION' and no legacy R8 keys ($legacyKeyCount found)"
            )
        }
        return decodeLegacy(obj)
    }

    private fun decodeCanonical(obj: com.google.gson.JsonObject): DecodeResult {
        val schema = obj.get(KEY_SCHEMA_VERSION)
        if (!schema.isJsonPrimitive || !schema.asJsonPrimitive.isNumber) {
            return DecodeResult.Invalid("'$KEY_SCHEMA_VERSION' is not a number")
        }
        val version = try {
            schema.asInt
        } catch (e: NumberFormatException) {
            return DecodeResult.Invalid("'$KEY_SCHEMA_VERSION' is not an int: ${e.message}")
        }
        if (version != SCHEMA_VERSION) {
            return DecodeResult.Invalid("unsupported schemaVersion $version")
        }
        val dto = try {
            gson.fromJson(obj, VpnLastConnectionDto::class.java)
        } catch (e: JsonParseException) {
            return DecodeResult.Invalid("canonical payload rejected: ${e.message}")
        } catch (e: RuntimeException) {
            return DecodeResult.Invalid("canonical payload rejected: ${e.message}")
        } ?: return DecodeResult.Invalid("canonical payload decoded to null")

        val connection = dto.toConnection()
            ?: return DecodeResult.Invalid("canonical payload has no hostName")
        return DecodeResult.Decoded(connection, Format.CANONICAL)
    }

    private fun decodeLegacy(obj: com.google.gson.JsonObject): DecodeResult {
        val dto = try {
            gson.fromJson(obj, LegacyVpnLastConnectionDto::class.java)
        } catch (e: JsonParseException) {
            return DecodeResult.Invalid("legacy payload rejected: ${e.message}")
        } catch (e: RuntimeException) {
            return DecodeResult.Invalid("legacy payload rejected: ${e.message}")
        } ?: return DecodeResult.Invalid("legacy payload decoded to null")

        val connection = dto.toConnection()
            ?: return DecodeResult.Invalid("legacy payload has no hostName")
        return DecodeResult.Decoded(connection, Format.LEGACY_R8)
    }
}

/**
 * The canonical stored form of the last used VPN connection.
 *
 * Every property carries an explicit `@SerializedName`, and that — not the
 * Kotlin name — is the on-disk key. The `schemaVersion` property is the
 * discriminator [VpnLastConnectionStore.decode] branches on.
 *
 * This covers all 24 state fields of [VPNGateConnection]. Nothing is dropped:
 * the class's remaining members (`sstpConnectPort`, `sstpPortKind`, `name`,
 * `calculateSpeed`, `openVpnConfigDataUdp`, ...) are computed properties with
 * no backing field, so they are derived on read and cannot be persisted.
 */
data class VpnLastConnectionDto(
    @SerializedName("schemaVersion") val schemaVersion: Int = VpnLastConnectionStore.SCHEMA_VERSION,
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

    fun toConnection(): VPNGateConnection? {
        val identity = hostName?.takeIf { it.isNotBlank() } ?: return null
        return VPNGateConnection().apply {
            hostName = identity
            ip = this@VpnLastConnectionDto.ip
            score = this@VpnLastConnectionDto.score
            ping = this@VpnLastConnectionDto.ping
            speed = this@VpnLastConnectionDto.speed
            countryLong = this@VpnLastConnectionDto.countryLong
            countryShort = this@VpnLastConnectionDto.countryShort
            numVpnSession = this@VpnLastConnectionDto.numVpnSession
            uptime = this@VpnLastConnectionDto.uptime
            totalUser = this@VpnLastConnectionDto.totalUser
            totalTraffic = this@VpnLastConnectionDto.totalTraffic
            logType = this@VpnLastConnectionDto.logType
            operator = this@VpnLastConnectionDto.operator
            message = this@VpnLastConnectionDto.message
            openVpnConfigData = this@VpnLastConnectionDto.openVpnConfigData
            tcpPort = this@VpnLastConnectionDto.tcpPort
            udpPort = this@VpnLastConnectionDto.udpPort
            isL2TPSupport = this@VpnLastConnectionDto.isL2TPSupport
            isSSTPSupport = this@VpnLastConnectionDto.isSSTPSupport
            sstpPort = this@VpnLastConnectionDto.sstpPort
            sstpPortSource = VpnPortSource.normalized(this@VpnLastConnectionDto.sstpPortSource)
            seTcpPort = this@VpnLastConnectionDto.seTcpPort
            seUdpPort = this@VpnLastConnectionDto.seUdpPort
            seUdpSupported = this@VpnLastConnectionDto.seUdpSupported
        }
    }

    companion object {
        fun from(connection: VPNGateConnection): VpnLastConnectionDto = VpnLastConnectionDto(
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
 * The pre-2F-D on-disk form, pinned to the **control (baseline 0714628) R8
 * mapping**. Transcribed from that build's `mapping.txt`, not inferred from
 * the current build, because the current build assigns `M`/`N`/`O` to
 * `sstpPort`/`sstpPortSource`/`seTcpPort` and would misread this payload.
 *
 * Note `O` is a `Boolean` here and not an `Int`: that is precisely the
 * distinction the crash turned on.
 *
 * This type is the only place the accidental format is described. It is read,
 * never written.
 */
internal data class LegacyVpnLastConnectionDto(
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

    fun toConnection(): VPNGateConnection? {
        val identity = hostName?.takeIf { it.isNotBlank() } ?: return null
        val sstpSupported = isSSTPSupport == 1
        return VPNGateConnection().apply {
            hostName = identity
            ip = this@LegacyVpnLastConnectionDto.ip
            score = this@LegacyVpnLastConnectionDto.score
            ping = this@LegacyVpnLastConnectionDto.ping
            speed = this@LegacyVpnLastConnectionDto.speed
            countryLong = this@LegacyVpnLastConnectionDto.countryLong
            countryShort = this@LegacyVpnLastConnectionDto.countryShort
            numVpnSession = this@LegacyVpnLastConnectionDto.numVpnSession
            uptime = this@LegacyVpnLastConnectionDto.uptime
            totalUser = this@LegacyVpnLastConnectionDto.totalUser
            totalTraffic = this@LegacyVpnLastConnectionDto.totalTraffic
            logType = this@LegacyVpnLastConnectionDto.logType
            operator = this@LegacyVpnLastConnectionDto.operator
            message = this@LegacyVpnLastConnectionDto.message
            openVpnConfigData = this@LegacyVpnLastConnectionDto.openVpnConfigData
            tcpPort = this@LegacyVpnLastConnectionDto.tcpPort
            udpPort = this@LegacyVpnLastConnectionDto.udpPort
            isL2TPSupport = this@LegacyVpnLastConnectionDto.isL2TPSupport
            isSSTPSupport = this@LegacyVpnLastConnectionDto.isSSTPSupport
            // These two properties did not exist when the payload was written,
            // so nothing can be recovered for them. A row the source confirmed
            // as SSTP-capable therefore falls back to the protocol default,
            // exactly as the legacy CSV path already does for the same reason
            // (VPNGateConnection.fromCsv) — so a legacy connection never
            // silently loses the ability to connect.
            sstpPort = 0
            sstpPortSource =
                if (sstpSupported) VpnPortSource.DEFAULT else VpnPortSource.UNKNOWN
            seTcpPort = this@LegacyVpnLastConnectionDto.seTcpPort
            seUdpPort = this@LegacyVpnLastConnectionDto.seUdpPort
            seUdpSupported = this@LegacyVpnLastConnectionDto.seUdpSupported
        }
    }
}
