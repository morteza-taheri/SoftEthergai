package vn.unlimit.vpngate.history

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F.29 §7 — the network classifier's pure core.
 *
 * The Android read (`current`) is not exercised here: it needs a
 * `ConnectivityManager`, and this source set has no Robolectric. What is
 * exercised is the mapping, which is the part that can be wrong in a way that
 * silently corrupts the per-network counters.
 */
class NetworkTypeClassifierTest {

    private val wifi = setOf(TransportKind.WIFI)
    private val cellular = setOf(TransportKind.CELLULAR)
    private val vpn = setOf(TransportKind.VPN)

    // ---------------------------------------------------------------------
    // The active network is proof
    // ---------------------------------------------------------------------

    @Test
    fun `an active wifi network is wifi`() {
        assertEquals(NetworkType.WIFI, NetworkTypeClassifier.classify(wifi, emptyList()))
    }

    @Test
    fun `an active cellular network is cellular`() {
        assertEquals(NetworkType.CELLULAR, NetworkTypeClassifier.classify(cellular, emptyList()))
    }

    @Test
    fun `an active network with no physical transport is unknown`() {
        assertEquals(
            NetworkType.UNKNOWN,
            NetworkTypeClassifier.classify(setOf(TransportKind.OTHER), emptyList()),
        )
    }

    @Test
    fun `no active network and no physical network is unknown`() {
        assertEquals(NetworkType.UNKNOWN, NetworkTypeClassifier.classify(null, emptyList()))
    }

    // ---------------------------------------------------------------------
    // The VPN transport is never the answer
    // ---------------------------------------------------------------------

    @Test
    fun `an active vpn with one wifi underneath is wifi`() {
        assertEquals(
            NetworkType.WIFI,
            NetworkTypeClassifier.classify(vpn, listOf(wifi)),
        )
    }

    @Test
    fun `an active vpn with one cellular underneath is cellular`() {
        assertEquals(
            NetworkType.CELLULAR,
            NetworkTypeClassifier.classify(vpn, listOf(cellular)),
        )
    }

    @Test
    fun `an active vpn with both wifi and cellular underneath is unknown`() {
        // Nothing proves which one carries the tunnel, so nothing is claimed.
        assertEquals(
            NetworkType.UNKNOWN,
            NetworkTypeClassifier.classify(vpn, listOf(wifi, cellular)),
        )
    }

    @Test
    fun `an active vpn with no physical network at all is unknown`() {
        assertEquals(NetworkType.UNKNOWN, NetworkTypeClassifier.classify(vpn, emptyList()))
    }

    @Test
    fun `a missing active network falls back to the single physical network`() {
        assertEquals(
            NetworkType.WIFI,
            NetworkTypeClassifier.classify(null, listOf(wifi)),
        )
        assertEquals(
            NetworkType.UNKNOWN,
            NetworkTypeClassifier.classify(null, listOf(wifi, cellular)),
        )
    }

    // ---------------------------------------------------------------------
    // Non-physical transports never leak into the answer
    // ---------------------------------------------------------------------

    @Test
    fun `ethernet and bluetooth are never claimed as wifi or cellular`() {
        val ethernet = setOf(TransportKind.OTHER)
        assertEquals(NetworkType.UNKNOWN, NetworkTypeClassifier.classify(ethernet, emptyList()))
    }

    @Test
    fun `a physical vpn network is ignored when looking through the tunnel`() {
        // The list handed to classify is already VPN-filtered by `current`, but
        // the classifier must not be fooled if a VPN network sneaks in.
        assertEquals(
            NetworkType.UNKNOWN,
            NetworkTypeClassifier.classify(vpn, listOf(setOf(TransportKind.VPN))),
        )
    }

    @Test
    fun `a network advertising both transports resolves deterministically`() {
        // Rare in practice, but the mapping must be total and stable rather
        // than dependent on set iteration order: ofTransports checks WIFI
        // first, so a dual-transport network is answered WIFI. Pinned here so
        // a future reordering is a visible decision rather than an accident.
        val both = setOf(TransportKind.WIFI, TransportKind.CELLULAR)
        assertEquals(NetworkType.WIFI, NetworkTypeClassifier.classify(both, emptyList()))
        assertEquals(NetworkType.WIFI, NetworkTypeClassifier.classify(both, emptyList()))
    }

    // ---------------------------------------------------------------------
    // Storage round trip
    // ---------------------------------------------------------------------

    @Test
    fun `storage round trips every known value`() {
        for (value in NetworkType.entries) {
            assertEquals(value, NetworkType.fromStorage(value.name))
        }
    }

    @Test
    fun `an unrecognised stored value is unknown never wifi`() {
        assertEquals(NetworkType.UNKNOWN, NetworkType.fromStorage("ETHERNET"))
        assertEquals(NetworkType.UNKNOWN, NetworkType.fromStorage("wifi"))
        assertEquals(NetworkType.UNKNOWN, NetworkType.fromStorage(""))
        assertEquals(NetworkType.UNKNOWN, NetworkType.fromStorage(null))
    }

    @Test
    fun `the enum has exactly three values`() {
        // The closed set is a product decision, not an accident: a fourth value
        // would invite a caller to treat it as "known".
        assertEquals(3, NetworkType.entries.size)
    }
}
