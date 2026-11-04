package vn.unlimit.vpngate.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.utils.DataUtil

/**
 * BLOCK v1.0.86 — the operator-configurable server-list ceiling.
 *
 * The ceiling is 300 by default, lives in [10, 2000], and exactly half of it is
 * the manual/import quota. [ServerListCap] is injected with a resolver so these
 * rules are testable without Android/SharedPreferences.
 */
class ServerListCapTest {

    @After
    fun release() {
        ServerListCap.resolver = null
    }

    @Test
    fun defaults_and_bounds_match_the_operator_decision() {
        assertEquals(300, ServerListCap.DEFAULT)
        assertEquals(10, ServerListCap.MIN)
        assertEquals(2000, ServerListCap.MAX)
        assertEquals(300, DataUtil.DEFAULT_SERVER_LIST_CAP)
        assertEquals(10, DataUtil.MIN_SERVER_LIST_CAP)
        assertEquals(2000, DataUtil.MAX_SERVER_LIST_CAP)
        // No Android instance in a JVM test -> the resolver falls back to DEFAULT.
        assertEquals(300, ServerListCap.current())
        assertEquals(150, ServerListCap.manualQuota())
    }

    @Test
    fun any_configured_value_is_clamped_to_the_allowed_range() {
        ServerListCap.resolver = { -5 }
        assertEquals(10, ServerListCap.current())
        ServerListCap.resolver = { 50_000 }
        assertEquals(2000, ServerListCap.current())
        ServerListCap.resolver = { 17 }
        assertEquals(17, ServerListCap.current())
    }

    @Test
    fun the_manual_import_quota_is_exactly_half() {
        for (cap in listOf(10, 17, 100, 300, 500, 1000, 2000)) {
            ServerListCap.resolver = { cap }
            assertEquals("manual quota for cap $cap", cap / 2, ServerListCap.manualQuota())
            // An odd capacity rounds the reserved share UP, so the manual
            // quota is floor(cap / 2) in both paths.
            assertEquals(cap / 2, manualServerQuota(cap))
            assertEquals((cap + 1) / 2, reservedCapacity(cap))
        }
    }

    @Test
    fun the_retention_cap_follows_the_setting() {
        ServerListCap.resolver = { 30 }
        val rows = (1..80).map { i ->
            vn.unlimit.vpngate.models.VPNGateItem(
                hostName = "h$i",
                ip = "203.0.113.1",
                score = 1,
                ping = 1,
                speed = 1,
                countryLong = "Japan",
                countryShort = "JP",
                numVpnSession = 1,
                uptime = 1,
                totalUser = 1,
                totalTraffic = 1L,
                logType = "dummy",
                operator = "dummy",
                message = null,
                openVpnConfigData = null,
                tcpPort = 0,
                udpPort = 0,
                isL2TPSupport = false,
                isSSTPSupport = false,
                sstpPort = 0,
                sstpPortSource = vn.unlimit.vpngate.data.model.VpnPortSource.UNKNOWN,
                seTcpPort = 0,
                seUdpPort = 0,
                seUdpSupported = false,
                firstSeenAt = 0L,
                lastSeenAt = 0L,
                healthScore = i,
            )
        }
        val capped = capRetainedServers(rows, emptySet())
        assertEquals("the configured ceiling is the hard limit", 30, capped.size)
        assertTrue(
            "the highest-quality rows must survive",
            capped.all { it.healthScore >= 51 },
        )
    }
}
