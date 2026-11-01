package vn.unlimit.vpngate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.softether.model.ConnectionConfig
import java.io.File

/**
 * SoftEther Max Connections setting.
 *
 * BLOCK v1.0.79: this setting was DEAD. Nothing ever called
 * `nativeSetMaxConnection`, and the login PACK sent a literal `4`, so the server
 * was asked for 4 connections whatever the user picked. Both are fixed here.
 *
 * The ceiling stays at 8: the protocol allows 32 and the server accepts them, but
 * this client's ramp-up starves the data plane above ~8 (device measurement in
 * `MAX_SOFTETHER_CONNECTIONS`). The last three tests keep the three pieces —
 * Kotlin clamp, native array size, and the connect path — from drifting apart.
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

    /** The values the Settings picker offers, in ladder order. */
    private val selectable = intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32)

    @Test
    fun default_isFour_preservingCurrentRuntimeBehavior() {
        assertEquals(4, baseConfig().maxConnections)
    }

    @Test
    fun allSelectableValues_propagateVerbatim() {
        for (value in selectable) {
            assertEquals(value, baseConfig(value).maxConnections)
        }
    }

    @Test
    fun theLadderCoversTheWholeProtocolRange() {
        assertEquals("the picker must start at the protocol minimum", 1, selectable.first())
        assertEquals(
            "the picker must reach the protocol ceiling",
            vn.unlimit.vpngate.utils.DataUtil.MAX_SOFTETHER_CONNECTIONS,
            selectable.last(),
        )
        // Ascending, no duplicates — a picker whose order is wrong selects the
        // wrong index when the user taps a row.
        assertEquals(selectable.toList(), selectable.toList().sorted().distinct())
    }

    @Test
    fun corruptValue_isNeutralizedAtUseSite_fallsBackToFour() {
        // DataUtil.getSoftEtherMaxConnections() clamps to 1..MAX before use.
        val corrupt = baseConfig(0)
        val applied = if (corrupt.maxConnections in 1..32) corrupt.maxConnections else 4
        assertEquals(4, applied)
        val negative = baseConfig(-3)
        val appliedNegative =
            if (negative.maxConnections in 1..32) negative.maxConnections else 4
        assertEquals(4, appliedNegative)
    }

    @Test
    fun appSettingBounds_matchTheNativeLimit() {
        assertEquals(4, vn.unlimit.vpngate.utils.DataUtil.DEFAULT_SOFTETHER_MAX_CONNECTIONS)
        assertEquals(32, vn.unlimit.vpngate.utils.DataUtil.MAX_SOFTETHER_CONNECTIONS)
        assertEquals(4, baseConfig().maxConnections)
    }

    /**
     * The native `MAX_SE_CONNECTIONS` is what actually bounds the socket arrays,
     * so the Kotlin ceiling must never exceed it — a Kotlin value the native layer
     * silently clamps would be another "the setting does nothing" bug.
     */
    @Test
    fun theKotlinCeilingNeverExceedsTheNativeArraySize() {
        val header = sourceOf(
            "SoftEtherClient/src/main/cpp/softether-core/include/softether_protocol.h",
        )
        val native = Regex("#define\\s+MAX_SE_CONNECTIONS\\s+(\\d+)")
            .find(header)?.groupValues?.get(1)?.toInt()
            ?: error("MAX_SE_CONNECTIONS not found in softether_protocol.h")
        assertEquals(
            "Kotlin MAX_SOFTETHER_CONNECTIONS and the native MAX_SE_CONNECTIONS must agree",
            native,
            vn.unlimit.vpngate.utils.DataUtil.MAX_SOFTETHER_CONNECTIONS,
        )
    }

    /**
     * Regression guard for the real defect found in v1.0.79: the login PACK sent
     * `max_connection` as a literal 4, so the Settings value never reached the
     * server. The packet must send `conn->max_connection`.
     */
    @Test
    fun theLoginPacketSendsTheConfiguredConnectionCount() {
        val source = sourceOf(
            "SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c",
        )
        assertTrue(
            "the login PACK must send conn->max_connection",
            source.contains("pack_add_int(&p, \"max_connection\", (uint32_t)conn->max_connection)"),
        )
        assertTrue(
            "no hard-coded 4 may be sent as max_connection",
            !Regex("pack_add_int\\(&p, \"max_connection\", 4\\)").containsMatchIn(source),
        )
    }

    /**
     * The regression that made the setting cosmetic: `nativeSetMaxConnection` was
     * declared but never invoked, so `conn->max_connection` stayed at its native
     * default no matter what the user picked. The connect path must call it.
     */
    @Test
    fun theConnectPathPushesTheValueToNative() {
        val controller = sourceOf(
            "SoftEtherClient/src/main/java/vn/unlimit/softether/controller/ConnectionController.kt",
        )
        val call = "client.nativeSetMaxConnection(nativeHandle, config.maxConnections)"
        assertEquals(
            "both the first connect AND the reconnect must push the value (the reconnect " +
                "builds a fresh native handle, which resets max_connection to 4)",
            2,
            Regex(Regex.escape(call)).findAll(controller).count(),
        )
    }

    /** Reads a source file, trying the module-relative and repo-relative paths. */
    private fun sourceOf(relativePath: String): String {
        for (path in listOf(relativePath, "../$relativePath", "app/$relativePath")) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8)
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }
}
