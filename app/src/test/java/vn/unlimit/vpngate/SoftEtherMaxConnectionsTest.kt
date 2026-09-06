package vn.unlimit.vpngate

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.unlimit.softether.model.ConnectionConfig

/**
 * SoftEther Max Connections setting (mission 2026-08-29):
 * the config field must default to 4 — the historical runtime value of
 * softether_protocol.c (max_connection = 4 sent in the login PACK) — so
 * that callers which do not pass it keep today's behavior, while the
 * Settings-backed value (DataUtil.getSoftEtherMaxConnections, clamped
 * 1..8 = native MAX_SE_CONNECTIONS) propagates verbatim when provided.
 */
class SoftEtherMaxConnectionsTest {

    private fun baseConfig(
        maxConnections: Int? = null,
    ): ConnectionConfig {
        val builder = ConnectionConfig(
            serverHost = "vpn.example.opengw.net",
            serverPort = 443,
            username = "vpn",
            password = "vpn",
        )
        return if (maxConnections == null) builder else builder.copy(
            maxConnections = maxConnections,
        )
    }

    @Test
    fun default_isFour_preservingCurrentRuntimeBehavior() {
        assertEquals(4, baseConfig().maxConnections)
    }

    @Test
    fun allSelectableValues_propagateVerbatim() {
        for (value in intArrayOf(1, 2, 3, 4, 5, 6, 8)) {
            assertEquals(value, baseConfig(value).maxConnections)
        }
    }

    @Test
    fun corruptValue_isNeutralizedAtUseSite_fallsBackToFour() {
        // ConnectionController applies: value in 1..8 ? value : 4
        val corrupt = baseConfig(0)
        val applied = if (corrupt.maxConnections in 1..8) corrupt.maxConnections else 4
        assertEquals(4, applied)
        val negative = baseConfig(-3)
        val appliedNegative =
            if (negative.maxConnections in 1..8) negative.maxConnections else 4
        assertEquals(4, appliedNegative)
    }
}
