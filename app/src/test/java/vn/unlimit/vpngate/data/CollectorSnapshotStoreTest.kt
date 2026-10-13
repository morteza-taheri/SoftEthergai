package vn.unlimit.vpngate.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.persistence.CollectorSnapshotStore
import vn.unlimit.vpngate.data.persistence.CollectorSnapshotStore.Format
import vn.unlimit.vpngate.data.persistence.CollectorSnapshotStore.ReadResult
import vn.unlimit.vpngate.data.persistence.CollectorSnapshotStore.Reason
import vn.unlimit.vpngate.data.persistence.CollectorSnapshotStore.WriteResult
import vn.unlimit.vpngate.models.VPNGateConnection
import java.io.File

/**
 * Stage 2F-E.2: `collector_vpn_servers.json` must survive an R8 rename.
 *
 * The defect: the snapshot was `Gson().toJson(mapOf("servers" to listOf<VPNGateConnection>))`.
 * [VPNGateConnection] has no `@SerializedName` and no keep rule, so R8 chose the
 * field names and the file became a build artifact. Stage 2F-A then added
 * `sstpPort` / `sstpPortSource`, which renumbered the block.
 *
 * Each legacy layout below is pinned to a real `mapping.txt`, and the sentinels
 * are deliberately non-sequential so that a field bound to the wrong name fails
 * loudly instead of coincidentally matching.
 */
class CollectorSnapshotStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sample(
        hostName: String = "vpn-host",
        ip: String = "10.0.0.1",
    ): VPNGateConnection = VPNGateConnection().apply {
        this.hostName = hostName
        this.ip = ip
        score = 11
        ping = 22
        speed = 33
        countryLong = "Land Long"
        countryShort = "LL"
        numVpnSession = 44
        uptime = 55
        totalUser = 66
        totalTraffic = 77L
        logType = "log-type"
        operator = "operator"
        message = "message"
        openVpnConfigData = "config"
        tcpPort = 1301
        udpPort = 1302
        isL2TPSupport = 1
        isSSTPSupport = 1
        sstpPort = 1443
        sstpPortSource = VpnPortSource.EXPLICIT
        seTcpPort = 1554
        seUdpPort = 1555
        seUdpSupported = true
    }

    private fun loaded(json: String): ReadResult.Loaded {
        val r = CollectorSnapshotStore.decode(json)
        assertTrue("expected Loaded but was $r", r is ReadResult.Loaded)
        return r as ReadResult.Loaded
    }

    private fun invalid(json: String): ReadResult.Invalid {
        val r = CollectorSnapshotStore.decode(json)
        assertTrue("expected Invalid but was $r", r is ReadResult.Invalid)
        return r as ReadResult.Invalid
    }

    // ------------------------------------------------------------ canonical

    @Test
    fun `canonical round trip preserves every field`() {
        val original = sample()
        val decoded = loaded(CollectorSnapshotStore.encode(1_700_000_000_000L, listOf(original)))

        assertEquals(Format.CANONICAL, decoded.format)
        assertEquals(1_700_000_000_000L, decoded.savedAt)
        assertEquals(1, decoded.servers.size)

        val back = decoded.servers[0]
        assertEquals(original.hostName, back.hostName)
        assertEquals(original.ip, back.ip)
        assertEquals(original.score, back.score)
        assertEquals(original.ping, back.ping)
        assertEquals(original.speed, back.speed)
        assertEquals(original.countryLong, back.countryLong)
        assertEquals(original.countryShort, back.countryShort)
        assertEquals(original.numVpnSession, back.numVpnSession)
        assertEquals(original.uptime, back.uptime)
        assertEquals(original.totalUser, back.totalUser)
        assertEquals(original.totalTraffic, back.totalTraffic)
        assertEquals(original.logType, back.logType)
        assertEquals(original.operator, back.operator)
        assertEquals(original.message, back.message)
        assertEquals(original.openVpnConfigData, back.openVpnConfigData)
        assertEquals(original.tcpPort, back.tcpPort)
        assertEquals(original.udpPort, back.udpPort)
        assertEquals(original.isL2TPSupport, back.isL2TPSupport)
        assertEquals(original.isSSTPSupport, back.isSSTPSupport)
        assertEquals(original.sstpPort, back.sstpPort)
        assertEquals(original.sstpPortSource, back.sstpPortSource)
        assertEquals(original.seTcpPort, back.seTcpPort)
        assertEquals(original.seUdpPort, back.seUdpPort)
        assertEquals(original.seUdpSupported, back.seUdpSupported)
    }

    @Test
    fun `canonical payload uses source names so R8 cannot move the keys`() {
        val json = CollectorSnapshotStore.encode(1L, listOf(sample()))
        val server = JsonParser.parseString(json).asJsonObject
            .getAsJsonArray("servers")[0].asJsonObject

        listOf(
            "hostName", "ip", "score", "ping", "speed", "countryLong", "countryShort",
            "numVpnSession", "uptime", "totalUser", "totalTraffic", "logType",
            "operator", "message", "openVpnConfigData", "tcpPort", "udpPort",
            "isL2TPSupport", "isSSTPSupport", "sstpPort", "sstpPortSource",
            "seTcpPort", "seUdpPort", "seUdpSupported",
        ).forEach { assertTrue("missing '$it'", server.has(it)) }

        // No R8 block may appear: these are the exact names a rename would move.
        listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q")
            .forEach { assertFalse("R8 key '$it' leaked into canonical", server.has(it)) }
    }

    @Test
    fun `canonical count always equals server count`() {
        val json = CollectorSnapshotStore.encode(1L, listOf(sample(), sample("b", "10.0.0.2")))
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(CollectorSnapshotStore.SCHEMA_VERSION, root.get("schemaVersion").asInt)
        assertEquals(2, root.get("count").asInt)
        assertEquals(2, root.getAsJsonArray("servers").size())
    }

    @Test
    fun `canonical rejects a future schema version instead of guessing`() {
        val json = """{"schemaVersion":2,"savedAt":1,"count":1,"servers":[{"hostName":"h","ip":"i"}]}"""
        assertEquals(Reason.UNSUPPORTED_SCHEMA, invalid(json).reason)
    }

    @Test
    fun `canonical rejects a count that disagrees with the server array`() {
        val json = """{"schemaVersion":1,"savedAt":1,"count":7,"servers":[{"hostName":"h","ip":"i"}]}"""
        assertEquals(Reason.COUNT_MISMATCH, invalid(json).reason)
    }

    @Test
    fun `canonical rejects an absent count`() {
        val json = """{"schemaVersion":1,"savedAt":1,"servers":[{"hostName":"h","ip":"i"}]}"""
        assertEquals(Reason.COUNT_MISMATCH, invalid(json).reason)
    }

    @Test
    fun `canonical rejects an empty server array`() {
        val json = """{"schemaVersion":1,"savedAt":1,"count":0,"servers":[]}"""
        assertEquals(Reason.INVALID_CANONICAL_SHAPE, invalid(json).reason)
    }

    @Test
    fun `a canonical payload is never retried as legacy`() {
        // R8 keys plus schemaVersion: must fail as canonical, not be re-read.
        val json = """{"schemaVersion":1,"savedAt":1,"count":1,"servers":[{"t":"h","u":"i","z":3}]}"""
        val r = invalid(json)
        assertEquals(Reason.INVALID_IDENTITY, r.reason)
    }

    @Test
    fun `canonical rejects servers with no hostName or ip`() {
        val json = """{"schemaVersion":1,"savedAt":1,"count":1,"servers":[{"countryLong":"x"}]}"""
        assertEquals(Reason.INVALID_IDENTITY, invalid(json).reason)
    }

    // -------------------------------------------------------- legacy layouts

    /**
     * The shape actually present on device 5585fecc (package vn.unlimit.vpngatepro,
     * files/collector_vpn_servers.json, 103 records) — written by a non-minified
     * build, so the keys are real Kotlin field names.
     */
    @Test
    fun `legacy source names decode`() {
        val json = """{"savedAt":1700000000000,"count":1,"servers":[{"hostName":"vpn-host",""" +
            """"ip":"10.0.0.1","score":11,"ping":22,"speed":33,"countryLong":"Land Long",""" +
            """"countryShort":"LL","numVpnSession":44,"uptime":55,"totalUser":66,""" +
            """"totalTraffic":77,"logType":"log-type","operator":"operator","message":"message",""" +
            """"openVpnConfigData":"config","tcpPort":1301,"udpPort":1302,"isL2TPSupport":1,""" +
            """"isSSTPSupport":1,"sstpPort":1443,"sstpPortSource":"EXPLICIT","seTcpPort":1554,""" +
            """"seUdpPort":1555,"seUdpSupported":true}]}"""

        val decoded = loaded(json)
        assertEquals(Format.LEGACY_SOURCE_NAMES, decoded.format)
        assertEquals(1700000000000L, decoded.savedAt)
        val s = decoded.servers[0]
        assertEquals("vpn-host", s.hostName)
        assertEquals("10.0.0.1", s.ip)
        assertEquals(1443, s.sstpPort)
        assertEquals(VpnPortSource.EXPLICIT, s.sstpPortSource)
        assertTrue(s.seUdpSupported)
    }

    /**
     * 22 fields. Proven by building HEAD 0714628 (`:app:assembleProRelease`).
     * Note `z` is `countryShort` here — a String.
     */
    @Test
    fun `legacy 22 field layout decodes`() {
        val json = """{"savedAt":5,"count":1,"servers":[{"A":44,"B":55,"C":66,"D":77,""" +
            """"E":"log-type","F":"operator","G":"message","H":"config","I":1301,"J":1302,""" +
            """"K":1,"L":1,"M":1554,"N":1555,"O":true,"t":"vpn-host","u":"10.0.0.1",""" +
            """"v":11,"w":22,"x":33,"y":"Land Long","z":"LL"}]}"""

        val decoded = loaded(json)
        assertEquals(Format.LEGACY_R8_22, decoded.format)
        val s = decoded.servers[0]
        assertEquals("vpn-host", s.hostName)
        assertEquals("10.0.0.1", s.ip)
        assertEquals(44, s.numVpnSession)
        assertEquals(55, s.uptime)
        assertEquals(66, s.totalUser)
        assertEquals(77L, s.totalTraffic)
        assertEquals("log-type", s.logType)
        assertEquals("operator", s.operator)
        assertEquals("message", s.message)
        assertEquals("config", s.openVpnConfigData)
        assertEquals(1301, s.tcpPort)
        assertEquals(1302, s.udpPort)
        assertEquals(1, s.isL2TPSupport)
        assertEquals(1, s.isSSTPSupport)
        assertEquals(1554, s.seTcpPort)
        assertEquals(1555, s.seUdpPort)
        assertTrue(s.seUdpSupported)
        assertEquals(11, s.score)
        assertEquals(22, s.ping)
        assertEquals(33, s.speed)
        assertEquals("Land Long", s.countryLong)
        assertEquals("LL", s.countryShort)
    }

    /**
     * 23 fields. Proven by building 3ed5920. This layout renumbers everything:
     * `sstpPort` is `N` (not `M`), `seUdpSupported` is `O`, and `numVpnSession`
     * is `z` while `countryShort` is `y`.
     */
    @Test
    fun `legacy 23 field layout decodes`() {
        val json = """{"savedAt":5,"count":1,"servers":[{"A":55,"B":66,"C":77,""" +
            """"D":"log-type","E":"operator","F":"message","G":"config","H":1301,"I":1302,""" +
            """"J":1,"K":1,"L":1554,"M":1555,"N":1443,"O":true,"s":"vpn-host","t":"10.0.0.1",""" +
            """"u":11,"v":22,"w":33,"x":"Land Long","y":"LL","z":44}]}"""

        val decoded = loaded(json)
        assertEquals(Format.LEGACY_R8_23, decoded.format)
        val s = decoded.servers[0]
        assertEquals("vpn-host", s.hostName)
        assertEquals("10.0.0.1", s.ip)
        assertEquals(55, s.uptime)
        assertEquals(66, s.totalUser)
        assertEquals(77L, s.totalTraffic)
        assertEquals("log-type", s.logType)
        assertEquals(1301, s.tcpPort)
        assertEquals(1302, s.udpPort)
        assertEquals(1, s.isL2TPSupport)
        assertEquals(1, s.isSSTPSupport)
        assertEquals(1554, s.seTcpPort)
        assertEquals(1555, s.seUdpPort)
        assertEquals(1443, s.sstpPort)
        assertTrue(s.seUdpSupported)
        assertEquals(11, s.score)
        assertEquals(22, s.ping)
        assertEquals(33, s.speed)
        assertEquals("Land Long", s.countryLong)
        assertEquals("LL", s.countryShort)
        assertEquals(44, s.numVpnSession)
    }

    /** 24 fields. Proven by the pre-Stage-2F-E.2 release mapping. */
    @Test
    fun `legacy 24 field layout decodes`() {
        val json = """{"savedAt":5,"count":1,"servers":[{"A":44,"B":55,"C":66,"D":77,""" +
            """"E":"log-type","F":"operator","G":"message","H":"config","I":1301,"J":1302,""" +
            """"K":1,"L":1,"M":1443,"N":"EXPLICIT","O":1554,"P":1555,"Q":true,""" +
            """"t":"vpn-host","u":"10.0.0.1","v":11,"w":22,"x":33,"y":"Land Long","z":"LL"}]}"""

        val decoded = loaded(json)
        assertEquals(Format.LEGACY_R8_24, decoded.format)
        val s = decoded.servers[0]
        assertEquals("vpn-host", s.hostName)
        assertEquals(1443, s.sstpPort)
        assertEquals(VpnPortSource.EXPLICIT, s.sstpPortSource)
        assertEquals(1554, s.seTcpPort)
        assertEquals(1555, s.seUdpPort)
        assertTrue(s.seUdpSupported)
        assertEquals("LL", s.countryShort)
    }

    @Test
    fun `unknown legacy layout is rejected rather than coerced`() {
        val json = """{"savedAt":1,"count":1,"servers":[{"alpha":1,"beta":"x"}]}"""
        assertEquals(Reason.UNKNOWN_LEGACY_FORMAT, invalid(json).reason)
    }

    @Test
    fun `r8 keys without the shared invariants are rejected`() {
        // Has R8-looking keys but no `A`/`O`, so no proven layout matches.
        val json = """{"savedAt":1,"count":1,"servers":[{"t":"h","u":"i","z":"c"}]}"""
        assertEquals(Reason.UNKNOWN_LEGACY_FORMAT, invalid(json).reason)
    }

    @Test
    fun `legacy payload with an empty server array is rejected`() {
        val json = """{"savedAt":1,"count":0,"servers":[]}"""
        assertEquals(Reason.INVALID_CANONICAL_SHAPE, invalid(json).reason)
    }

    @Test
    fun `legacy payload with no identity is rejected`() {
        val json = """{"savedAt":1,"count":1,"servers":[{"A":1,"O":true,"z":5}]}"""
        assertEquals(Reason.INVALID_IDENTITY, invalid(json).reason)
    }

    @Test
    fun `malformed json is rejected`() {
        assertEquals(Reason.INVALID_JSON, invalid("{not json").reason)
    }

    // ------------------------------------------------- SSTP legacy semantics

    @Test
    fun `22 field layout had no sstp members so a supported row falls back to the default`() {
        val json = """{"savedAt":1,"count":1,"servers":[{"A":1,"B":1,"C":1,"D":1,"E":"e",""" +
            """"F":"f","G":"g","H":"h","I":1,"J":1,"K":1,"L":1,"M":1,"N":1,"O":false,""" +
            """"t":"host","u":"ip","v":1,"w":1,"x":1,"y":"y","z":"z"}]}"""

        val s = loaded(json).servers[0]
        assertEquals(0, s.sstpPort)
        assertEquals(VpnPortSource.DEFAULT, s.sstpPortSource)
    }

    @Test
    fun `an unsupported row never gains fabricated sstp identity`() {
        val json = """{"savedAt":1,"count":1,"servers":[{"A":1,"B":1,"C":1,"D":1,"E":"e",""" +
            """"F":"f","G":"g","H":"h","I":1,"J":1,"K":1,"L":0,"M":1,"N":1,"O":false,""" +
            """"t":"host","u":"ip","v":1,"w":1,"x":1,"y":"y","z":"z"}]}"""

        val s = loaded(json).servers[0]
        assertEquals(0, s.sstpPort)
        assertEquals(VpnPortSource.UNKNOWN, s.sstpPortSource)
    }

    @Test
    fun `23 field layout keeps an explicit sstp port and defaults the source`() {
        val json = """{"savedAt":1,"count":1,"servers":[{"A":1,"B":1,"C":1,"D":"e","E":"f",""" +
            """"F":"g","G":"h","H":1,"I":1,"J":1,"K":1,"L":1,"M":1,"N":8443,"O":false,""" +
            """"s":"host","t":"ip","u":1,"v":1,"w":1,"x":"y","y":"z","z":1}]}"""

        val s = loaded(json).servers[0]
        assertEquals(8443, s.sstpPort)
        assertEquals(VpnPortSource.UNKNOWN, s.sstpPortSource)
    }

    @Test
    fun `24 field layout preserves its recorded sstp provenance`() {
        val json = """{"savedAt":1,"count":1,"servers":[{"A":1,"B":1,"C":1,"D":1,"E":"e",""" +
            """"F":"f","G":"g","H":"h","I":1,"J":1,"K":1,"L":1,"M":8443,"N":"EXPLICIT",""" +
            """"O":1,"P":1,"Q":false,"t":"host","u":"ip","v":1,"w":1,"x":1,"y":"y","z":"z"}]}"""

        val s = loaded(json).servers[0]
        assertEquals(8443, s.sstpPort)
        assertEquals(VpnPortSource.EXPLICIT, s.sstpPortSource)
    }

    @Test
    fun `an unrecognised sstp source normalizes to UNKNOWN`() {
        val json = """{"savedAt":1,"count":1,"servers":[{"hostName":"h","ip":"i",""" +
            """"sstpPort":443,"sstpPortSource":"TOTALLY_MADE_UP"}]}"""
        assertEquals(VpnPortSource.UNKNOWN, loaded(json).servers[0].sstpPortSource)
    }

    // ------------------------------------------------------------ migration

    @Test
    fun `migration preserves the original savedAt`() {
        val legacy = """{"savedAt":111,"count":1,"servers":[{"hostName":"h","ip":"i"}]}"""
        val file = temp.newFile("collector_vpn_servers.json")
        file.writeText(legacy)

        val read = CollectorSnapshotStore.read(file)
        assertTrue(read is ReadResult.Loaded)
        assertTrue(CollectorSnapshotStore.migrate(file, read as ReadResult.Loaded) is WriteResult.Written)

        val after = CollectorSnapshotStore.read(file)
        assertTrue(after is ReadResult.Loaded)
        assertEquals(Format.CANONICAL, (after as ReadResult.Loaded).format)
        assertEquals(111L, after.savedAt)
    }

    @Test
    fun `a migrated file is byte identical on a second write`() {
        val legacy = """{"savedAt":111,"count":1,"servers":[{"hostName":"h","ip":"i"}]}"""
        val file = temp.newFile("collector_vpn_servers.json")
        file.writeText(legacy)

        val read = CollectorSnapshotStore.read(file) as ReadResult.Loaded
        CollectorSnapshotStore.migrate(file, read)

        val firstPass = file.readText()
        val second = CollectorSnapshotStore.read(file) as ReadResult.Loaded
        assertEquals(Format.CANONICAL, second.format)
        CollectorSnapshotStore.write(file, second.savedAt, second.servers)
        assertEquals(firstPass, file.readText())
    }

    // -------------------------------------------------------------- writing

    @Test
    fun `write refuses an empty server list`() {
        val file = temp.newFile("collector_vpn_servers.json")
        val r = CollectorSnapshotStore.write(file, 1L, emptyList())
        assertTrue(r is WriteResult.Failed)
        assertEquals(Reason.INVALID_CANONICAL_SHAPE, (r as WriteResult.Failed).reason)
    }

    @Test
    fun `a successful write leaves no temp file behind`() {
        val file = temp.newFile("collector_vpn_servers.json")
        assertTrue(CollectorSnapshotStore.write(file, 1L, listOf(sample())) is WriteResult.Written)
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun `a refused rename keeps the previous snapshot and reports rename failure`() {
        // A non-empty directory in the target's place: MoveFileEx/MOVEFILE_REPLACE_EXISTING
        // cannot replace a directory with a file, so renameTo returns false without
        // touching the destination. (An *empty* directory is not a reliable way to
        // force this on every filesystem.)
        val target = File(temp.newFolder("holder"), "collector_vpn_servers.json")
        assertTrue(target.mkdirs())
        val blocker = File(target, "blocker")
        blocker.writeText("keep me")

        val r = CollectorSnapshotStore.write(target, 1L, listOf(sample()))

        assertTrue("expected Failed but was $r", r is WriteResult.Failed)
        assertEquals(Reason.RENAME_FAILURE, (r as WriteResult.Failed).reason)
        assertTrue("previous snapshot must survive", target.isDirectory)
        assertEquals("keep me", blocker.readText())
        assertFalse("temp file must be cleaned up", File(target.parentFile, "${target.name}.tmp").exists())
    }

    @Test
    fun `a write failure keeps the previous snapshot intact`() {
        val file = temp.newFile("collector_vpn_servers.json")
        val good = CollectorSnapshotStore.encode(1L, listOf(sample()))
        file.writeText(good)

        // Make the temp path un-creatable by parking a directory there.
        File(file.parentFile, "${file.name}.tmp").mkdirs()

        val r = CollectorSnapshotStore.write(file, 2L, listOf(sample("b", "10.0.0.2")))
        assertTrue(r is WriteResult.Failed)
        assertEquals(Reason.WRITE_FAILURE, (r as WriteResult.Failed).reason)
        assertEquals("previous snapshot must be untouched", good, file.readText())
    }

    @Test
    fun `write then read returns the canonical format`() {
        val file = temp.newFile("collector_vpn_servers.json")
        CollectorSnapshotStore.write(file, 42L, listOf(sample()))
        val read = CollectorSnapshotStore.read(file)
        assertTrue(read is ReadResult.Loaded)
        assertEquals(Format.CANONICAL, (read as ReadResult.Loaded).format)
        assertEquals(42L, read.savedAt)
    }

    @Test
    fun `an absent file reports Absent rather than failing`() {
        val file = File(temp.root, "not-there.json")
        assertEquals(ReadResult.Absent, CollectorSnapshotStore.read(file))
    }

    @Test
    fun `file helper names the snapshot inside the cache dir`() {
        val f = CollectorSnapshotStore.file(temp.newFolder("cache"))
        assertEquals("collector_vpn_servers.json", f?.name)
        assertEquals(null, CollectorSnapshotStore.file(null))
    }
}
