package vn.unlimit.vpngate.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.persistence.VpnLastConnectionStore
import vn.unlimit.vpngate.data.persistence.VpnLastConnectionStore.DecodeResult
import vn.unlimit.vpngate.data.persistence.VpnLastConnectionStore.Format
import vn.unlimit.vpngate.models.VPNGateConnection

/**
 * Stage 2F-D: the `vpn_last_connection` preference must survive an R8 rename.
 *
 * The regression these lock down: the preference used to be Gson-serialized
 * straight off [VPNGateConnection], whose field names R8 rewrites. Stage 2F-A
 * added `sstpPort` / `sstpPortSource`, which shifted R8's assignment so the
 * stored `"O": true` began to bind to `seTcpPort: Int` and threw
 * `JsonSyntaxException: Expected an int but was BOOLEAN`; the non-minified
 * debug build instead silently produced `hostName == null`. Both ended in an
 * NPE on the Status screen.
 */
class VpnLastConnectionStoreTest {

    /**
     * The payload read verbatim from the device (package vn.unlimit.vpngatepro,
     * shared_prefs/vpn_setting_data_pro.xml) via `adb exec-out run-as`. These are
     * the exact bytes the 2.4.9 release wrote.
     */
    private val deviceLegacyJson =
        """{"A":11,"B":10800000,"C":180724,"D":37504620796314,"E":"2 Weeks",""" +
            """"F":"hinata43-PC's owner","G":"","I":0,"J":1629,"K":0,"L":0,"M":0,"N":0,"O":true,""" +
            """"t":"vpn320067811","u":"60.98.34.211","v":646984,"w":5,"x":314970000,""" +
            """"y":"Japan","z":"JP"}"""

    private val canonicalKeys = setOf(
        "schemaVersion", "hostName", "ip", "score", "ping", "speed", "countryLong",
        "countryShort", "numVpnSession", "uptime", "totalUser", "totalTraffic",
        "logType", "operator", "message", "openVpnConfigData", "tcpPort", "udpPort",
        "isL2TPSupport", "isSSTPSupport", "sstpPort", "sstpPortSource",
        "seTcpPort", "seUdpPort", "seUdpSupported",
    )

    private val legacyKeys = setOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O",
        "t", "u", "v", "w", "x", "y", "z",
    )

    private fun decoded(json: String): VPNGateConnection {
        val result = VpnLastConnectionStore.decode(json)
        assertTrue("expected Decoded but was $result", result is DecodeResult.Decoded)
        return (result as DecodeResult.Decoded).connection
    }

    private fun invalid(json: String): DecodeResult.Invalid {
        val result = VpnLastConnectionStore.decode(json)
        assertTrue("expected Invalid but was $result", result is DecodeResult.Invalid)
        return result as DecodeResult.Invalid
    }

    private fun formatOf(json: String): Format =
        (VpnLastConnectionStore.decode(json) as DecodeResult.Decoded).format

    /** Every state field populated with a distinguishable value. */
    private fun fullConnection(): VPNGateConnection = VPNGateConnection().apply {
        hostName = "vpn320067811"
        ip = "60.98.34.211"
        score = 646984
        ping = 5
        speed = 314970000
        countryLong = "Japan"
        countryShort = "JP"
        numVpnSession = 11
        uptime = 10800000
        totalUser = 180724
        totalTraffic = 37504620796314L
        logType = "2 Weeks"
        operator = "hinata43-PC's owner"
        message = "operator message"
        openVpnConfigData = "client\r\ndev tun"
        tcpPort = 1447
        udpPort = 1629
        isL2TPSupport = 1
        isSSTPSupport = 1
        sstpPort = 8443
        sstpPortSource = VpnPortSource.EXPLICIT
        seTcpPort = 4430
        seUdpPort = 4431
        seUdpSupported = true
    }

    private fun assertSameConnection(expected: VPNGateConnection, actual: VPNGateConnection) {
        assertEquals(expected.hostName, actual.hostName)
        assertEquals(expected.ip, actual.ip)
        assertEquals(expected.score, actual.score)
        assertEquals(expected.ping, actual.ping)
        assertEquals(expected.speed, actual.speed)
        assertEquals(expected.countryLong, actual.countryLong)
        assertEquals(expected.countryShort, actual.countryShort)
        assertEquals(expected.numVpnSession, actual.numVpnSession)
        assertEquals(expected.uptime, actual.uptime)
        assertEquals(expected.totalUser, actual.totalUser)
        assertEquals(expected.totalTraffic, actual.totalTraffic)
        assertEquals(expected.logType, actual.logType)
        assertEquals(expected.operator, actual.operator)
        assertEquals(expected.message, actual.message)
        assertEquals(expected.openVpnConfigData, actual.openVpnConfigData)
        assertEquals(expected.tcpPort, actual.tcpPort)
        assertEquals(expected.udpPort, actual.udpPort)
        assertEquals(expected.isL2TPSupport, actual.isL2TPSupport)
        assertEquals(expected.isSSTPSupport, actual.isSSTPSupport)
        assertEquals(expected.sstpPort, actual.sstpPort)
        assertEquals(expected.sstpPortSource, actual.sstpPortSource)
        assertEquals(expected.seTcpPort, actual.seTcpPort)
        assertEquals(expected.seUdpPort, actual.seUdpPort)
        assertEquals(expected.seUdpSupported, actual.seUdpSupported)
    }

    // ---------------------------------------------------------------- A

    @Test
    fun canonicalRoundTrip() {
        val original = fullConnection()
        val json = VpnLastConnectionStore.encode(original)

        assertEquals(Format.CANONICAL, formatOf(json))
        assertSameConnection(original, decoded(json))
    }

    @Test
    fun canonicalJsonUsesOnlyStableExplicitKeys() {
        val json = VpnLastConnectionStore.encode(fullConnection())
        val keys = Gson().fromJson(json, Map::class.java).keys

        assertEquals(canonicalKeys, keys)
        // The whole point: no R8 name ever appears in a payload we write.
        assertTrue("legacy keys leaked into canonical JSON", keys.none { it in legacyKeys })
    }

    @Test
    fun canonicalJsonCarriesSchemaVersionOne() {
        val json = VpnLastConnectionStore.encode(fullConnection())
        val map = Gson().fromJson(json, Map::class.java)
        assertEquals(1.0, map["schemaVersion"])
    }

    // ---------------------------------------------------------------- B

    @Test
    fun legacyDecodesThenSerializesToCanonical() {
        val legacy = decoded(deviceLegacyJson)
        assertEquals(Format.LEGACY_R8, formatOf(deviceLegacyJson))

        val canonical = VpnLastConnectionStore.encode(legacy)
        assertEquals(Format.CANONICAL, formatOf(canonical))
        assertSameConnection(legacy, decoded(canonical))

        // Identity must survive the migration untouched.
        assertEquals("vpn320067811", decoded(canonical).hostName)
        assertEquals("60.98.34.211", decoded(canonical).ip)
        assertEquals("Japan", decoded(canonical).countryLong)
        assertEquals("JP", decoded(canonical).countryShort)
        assertTrue(decoded(canonical).seUdpSupported)
    }

    // ---------------------------------------------------------------- C

    /**
     * Stands in for a minified build: same logical fields, R8-style names,
     * correct `@SerializedName`s. Renaming the fields must not change what the
     * canonical JSON means.
     */
    class ObfuscatedReadModel {
        @SerializedName("hostName") var t: String? = null
        @SerializedName("ip") var u: String? = null
        @SerializedName("countryLong") var y: String? = null
        @SerializedName("countryShort") var z: String? = null
        @SerializedName("seUdpSupported") var q: Boolean = false
        @SerializedName("seTcpPort") var o: Int = 0
        @SerializedName("sstpPort") var m: Int = 0
        @SerializedName("sstpPortSource") var n: String? = null
        @SerializedName("numVpnSession") var a: Int = 0
        @SerializedName("totalTraffic") var d: Long = 0L
    }

    @Test
    fun canonicalSurvivesRenamedFields() {
        val json = VpnLastConnectionStore.encode(fullConnection())
        val read = Gson().fromJson(json, ObfuscatedReadModel::class.java)

        assertEquals("vpn320067811", read.t)
        assertEquals("60.98.34.211", read.u)
        assertEquals("Japan", read.y)
        assertEquals("JP", read.z)
        assertTrue(read.q)
        assertEquals(4430, read.o)
        assertEquals(8443, read.m)
        assertEquals(VpnPortSource.EXPLICIT, read.n)
        assertEquals(11, read.a)
        assertEquals(37504620796314L, read.d)
    }

    @Test
    fun canonicalEncodeDoesNotDependOnConnectionFieldNames() {
        // Encoding a connection whose Kotlin property names are irrelevant to
        // the output: only the DTO's @SerializedName values appear.
        val json = VpnLastConnectionStore.encode(fullConnection())
        assertTrue(json.contains("\"hostName\""))
        assertTrue(json.contains("\"seUdpSupported\""))
        assertFalse("R8-style single-letter key present", json.contains("\"t\":"))
        assertFalse("R8-style single-letter key present", json.contains("\"O\":"))
    }

    // ---------------------------------------------------------------- D

    @Test
    fun legacyUsesControlMappingExactly() {
        val c = decoded(deviceLegacyJson)

        // A-O and t-z, as assigned by the CONTROL (baseline) R8 mapping.
        assertEquals(11, c.numVpnSession)          // A
        assertEquals(10800000, c.uptime)           // B
        assertEquals(180724, c.totalUser)          // C
        assertEquals(37504620796314L, c.totalTraffic) // D
        assertEquals("2 Weeks", c.logType)         // E
        assertEquals("hinata43-PC's owner", c.operator) // F
        assertEquals("", c.message)                // G
        assertNull(c.openVpnConfigData)            // H (absent == null)
        assertEquals(0, c.tcpPort)                 // I
        assertEquals(1629, c.udpPort)               // J
        assertEquals(0, c.isL2TPSupport)           // K
        assertEquals(0, c.isSSTPSupport)           // L
        assertEquals(0, c.seTcpPort)               // M
        assertEquals(0, c.seUdpPort)               // N
        assertTrue(c.seUdpSupported)               // O
        assertEquals("vpn320067811", c.hostName)   // t
        assertEquals("60.98.34.211", c.ip)         // u
        assertEquals(646984, c.score)              // v
        assertEquals(5, c.ping)                    // w
        assertEquals(314970000, c.speed)           // x
        assertEquals("Japan", c.countryLong)       // y
        assertEquals("JP", c.countryShort)         // z
    }

    @Test
    fun legacySstpCapableRowKeepsADefaultPort() {
        // L == 1 in the legacy payload. sstpPort/sstpPortSource did not exist
        // then, so the row falls back to the protocol default rather than
        // becoming unconnectable.
        val json = deviceLegacyJson.replace("\"L\":0", "\"L\":1")
        val c = decoded(json)
        assertEquals(1, c.isSSTPSupport)
        assertEquals(VpnPortSource.DEFAULT, c.sstpPortSource)
        assertEquals(443, c.sstpConnectPort)
    }

    // ---------------------------------------------------------------- I

    @Test
    fun legacyBooleanOBindsToSeUdpSupportedAndNotSeTcpPort() {
        val c = decoded(deviceLegacyJson)
        assertTrue("\"O\": true must be seUdpSupported", c.seUdpSupported)
        assertEquals("\"O\": true must NOT bind to seTcpPort", 0, c.seTcpPort)
    }

    @Test
    fun legacyBooleanOBindsToSeUdpSupportedInIsolation() {
        val c = decoded("""{"O":true,"t":"vpn1","u":"1.2.3.4"}""")
        assertTrue(c.seUdpSupported)
        assertEquals(0, c.seTcpPort)
    }

    // ---------------------------------------------------------------- E

    @Test
    fun malformedJsonIsRejected() {
        invalid("""{"hostName": """)
        invalid("not json at all")
        invalid("")
        invalid("[]")
        invalid("\"just a string\"")
        invalid("123")
    }

    // ---------------------------------------------------------------- F

    @Test
    fun canonicalWithoutHostNameIsRejected() {
        val reason = invalid("""{"schemaVersion":1,"ip":"1.2.3.4"}""").reason
        assertTrue(reason, reason.contains("hostName"))
    }

    @Test
    fun canonicalWithBlankHostNameIsRejected() {
        invalid("""{"schemaVersion":1,"hostName":"   "}""")
    }

    @Test
    fun canonicalWithWrongSchemaVersionIsRejected() {
        val reason = invalid("""{"schemaVersion":2,"hostName":"vpn1"}""").reason
        assertTrue(reason, reason.contains("schemaVersion"))
    }

    @Test
    fun canonicalWithNonNumericSchemaVersionIsRejected() {
        invalid("""{"schemaVersion":"one","hostName":"vpn1"}""")
    }

    @Test
    fun aCanonicalPayloadIsNeverRetriedAsLegacy() {
        // Fails on its own terms: it is judged canonical (it carries
        // schemaVersion) and is never re-read as an R8-named payload.
        val reason = invalid("""{"schemaVersion":1,"ip":"1.2.3.4","t":"looks-legacy"}""").reason
        assertTrue(reason, reason.contains("hostName"))
        assertFalse(reason, reason.contains("legacy"))
    }

    // ---------------------------------------------------------------- G

    @Test
    fun canonicalIgnoresUnknownFutureFields() {
        val json = """{"schemaVersion":1,"hostName":"vpn1","ip":"1.2.3.4",""" +
            """"somethingFromTheFuture":{"nested":[1,2,3]},"extraFlag":true}"""
        val c = decoded(json)
        assertEquals("vpn1", c.hostName)
        assertEquals("1.2.3.4", c.ip)
    }

    @Test
    fun canonicalSurvivesAFutureSchemaFieldAlongsideKnownOnes() {
        val json = VpnLastConnectionStore.encode(fullConnection())
        val withExtra = json.dropLast(1) + ""","quotaBytes":1024}"""
        assertSameConnection(fullConnection(), decoded(withExtra))
    }

    // ---------------------------------------------------------------- H

    @Test
    fun legacyWithMissingOptionalFieldsStillDecodes() {
        val c = decoded("""{"t":"vpn-min","u":"10.0.0.1"}""")
        assertEquals("vpn-min", c.hostName)
        assertEquals("10.0.0.1", c.ip)
        assertEquals(0, c.numVpnSession)
        assertEquals(0L, c.totalTraffic)
        assertNull(c.countryLong)
        assertFalse(c.seUdpSupported)
        assertEquals(VpnPortSource.UNKNOWN, c.sstpPortSource)
    }

    @Test
    fun legacyIgnoresUnknownKeys() {
        val c = decoded("""{"t":"vpn-min","u":"10.0.0.1","ZZ":"junk","aNewField":7}""")
        assertEquals("vpn-min", c.hostName)
    }

    @Test
    fun legacyWithoutIdentityIsRejected() {
        val reason = invalid("""{"A":11,"B":10800000,"O":true}""").reason
        assertTrue(reason, reason.contains("hostName"))
    }

    @Test
    fun unrelatedJsonObjectIsRejected() {
        val reason = invalid("""{"somethingElse":1,"another":2}""").reason
        assertTrue(reason, reason.contains("legacy"))
    }

    // -------------------------------------------------- data preservation

    @Test
    fun everyLegacyPayloadFieldSurvivesTheMigration() {
        val legacy = decoded(deviceLegacyJson)
        val canonical = decoded(VpnLastConnectionStore.encode(legacy))

        // The legacy payload carried 21 keys. Every one must still be readable,
        // and nothing may be invented for the two fields it predates.
        assertEquals("vpn320067811", canonical.hostName)
        assertEquals("60.98.34.211", canonical.ip)
        assertEquals("2 Weeks", canonical.logType)
        assertEquals("hinata43-PC's owner", canonical.operator)
        assertEquals("", canonical.message)
        assertNull(canonical.openVpnConfigData)
        assertEquals(11, canonical.numVpnSession)
        assertEquals(10800000, canonical.uptime)
        assertEquals(180724, canonical.totalUser)
        assertEquals(37504620796314L, canonical.totalTraffic)
        assertEquals(0, canonical.tcpPort)
        assertEquals(1629, canonical.udpPort)
        assertEquals(0, canonical.isL2TPSupport)
        assertEquals(0, canonical.isSSTPSupport)
        assertEquals(0, canonical.seTcpPort)
        assertEquals(0, canonical.seUdpPort)
        assertTrue(canonical.seUdpSupported)
        assertEquals(646984, canonical.score)
        assertEquals(5, canonical.ping)
        assertEquals(314970000, canonical.speed)
        assertEquals("Japan", canonical.countryLong)
        assertEquals("JP", canonical.countryShort)

        // Fields the legacy format predates: defaulted, never fabricated.
        assertEquals(0, canonical.sstpPort)
        assertEquals(VpnPortSource.UNKNOWN, canonical.sstpPortSource)
    }

    @Test
    fun decodeIsDeterministic() {
        val first = decoded(deviceLegacyJson)
        val second = decoded(deviceLegacyJson)
        assertEquals(VpnLastConnectionStore.encode(first), VpnLastConnectionStore.encode(second))
    }

    @Test
    fun encodingIsStableAcrossCalls() {
        val c = fullConnection()
        assertEquals(VpnLastConnectionStore.encode(c), VpnLastConnectionStore.encode(c))
    }

    @Test
    fun storeExposesItsOwnPreferenceKey() {
        assertEquals("vpn_last_connection", VpnLastConnectionStore.PREF_KEY)
        assertNotNull(VpnLastConnectionStore.SCHEMA_VERSION)
    }
}
