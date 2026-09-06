package vn.unlimit.vpngate.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Log window store (user request 2026-08-30): bounded buffer + the
 * per-protocol filter used by the Auto Mode log panel — while driving
 * SoftEther only SoftEther module output + AUTO lines are shown, etc.
 */
class AutoModeLogStoreTest {

    @Before
    fun setUp() {
        AutoModeLogStore.clear()
    }

    @Test
    fun filterSoftEtherShowsSoftEtherAndAutoOnly() {
        AutoModeLogStore.add(AutoModeLogStore.Source.AUTO, "[AUTO] Started")
        AutoModeLogStore.add(AutoModeLogStore.Source.SOFTETHER, "TLS_HANDSHAKE")
        AutoModeLogStore.add(AutoModeLogStore.Source.OPENVPN, "connect to 1.2.3.4")
        AutoModeLogStore.add(AutoModeLogStore.Source.SSTP, "ROOT_STATE = true")
        AutoModeLogStore.add(AutoModeLogStore.Source.SYSTEM, "irrelevant")

        val filtered = AutoModeLogStore.filterFor(AutoModeProtocol.SOFTETHER_UDP)
        assertEquals(
            listOf(AutoModeLogStore.Source.AUTO, AutoModeLogStore.Source.SOFTETHER),
            filtered.map { it.source },
        )
    }

    @Test
    fun filterOpenVpnShowsOpenVpnAndAutoOnly() {
        AutoModeLogStore.add(AutoModeLogStore.Source.AUTO, "[AUTO] Trying #1")
        AutoModeLogStore.add(AutoModeLogStore.Source.SOFTETHER, "CONNECTED")
        AutoModeLogStore.add(AutoModeLogStore.Source.OPENVPN, "CONNECTED, GREAT SUCCESS")

        val filtered = AutoModeLogStore.filterFor(AutoModeProtocol.OPENVPN_TCP)
        assertEquals(
            listOf(AutoModeLogStore.Source.AUTO, AutoModeLogStore.Source.OPENVPN),
            filtered.map { it.source },
        )
    }

    @Test
    fun filterNullShowsEverything() {
        AutoModeLogStore.add(AutoModeLogStore.Source.AUTO, "a")
        AutoModeLogStore.add(AutoModeLogStore.Source.SSTP, "b")
        assertEquals(2, AutoModeLogStore.filterFor(null).size)
    }

    @Test
    fun clearEmptiesTheBuffer() {
        AutoModeLogStore.add(AutoModeLogStore.Source.AUTO, "line")
        AutoModeLogStore.clear()
        assertTrue(AutoModeLogStore.lines.value.isEmpty())
        assertEquals("", AutoModeLogStore.asText())
    }

    @Test
    fun bufferIsBounded() {
        repeat(2500) { AutoModeLogStore.add(AutoModeLogStore.Source.AUTO, "line $it") }
        assertEquals(2000, AutoModeLogStore.lines.value.size)
    }

    @Test
    fun linesAreFormattedWithTimestampAndSource() {
        AutoModeLogStore.add(AutoModeLogStore.Source.SOFTETHER, "hello world")
        val text = AutoModeLogStore.asText()
        assertTrue(text.contains("SOFTETHER: hello world"))
        assertTrue(text.contains(":")) // timestamp present
    }
}
