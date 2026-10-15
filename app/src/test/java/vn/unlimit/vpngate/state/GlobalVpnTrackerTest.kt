package vn.unlimit.vpngate.state

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.unlimit.softether.TunnelHealth
import java.io.File

/**
 * R4a unit tests — the tunnel-health counters and the `isTunnelUsable()`
 * decision that lift the blocker recorded in `AUDIT_AXES_A_B_C_v1.0.11.md`
 * §4.4 / §5.4.
 *
 * These target `TunnelHealth` (a pure top-level object in `SoftEtherClient`)
 * rather than `GlobalVpnTracker` directly: `GlobalVpnTracker`'s object
 * initializer needs the Android runtime (`Handler(Looper.getMainLooper())`)
 * and therefore cannot be initialized in a plain JVM test. The §7 Q1
 * decision is a pure function in `TunnelHealth.isUsable`, so it is fully
 * exercisable here.
 */
class GlobalVpnTrackerTest {

    private val now = 1_000_000L

    @Before
    fun setUp() {
        TunnelHealth.resetHealthCountersForTest()
    }

    @After
    fun tearDown() {
        TunnelHealth.resetHealthCountersForTest()
    }

    // ── isUsable: the three §7 Q1 conditions ────────────────────────────

    @Test
    fun `not connected is never usable even with fresh rx and no eio`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - 1_000L)
        assertFalse(TunnelHealth.isUsable(isConnected = false, now))
    }

    @Test
    fun `connected with recent rx and no eio is usable`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - 1_000L)
        assertTrue(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `never-observed rx is not usable`() {
        // lastRxAdvanceAtMs == 0 → fails closed on missing evidence.
        assertFalse(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `rx older than the 10s window is not usable`() {
        // Exactly at the boundary is already stale (>= window).
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - TunnelHealth.RX_WINDOW_MS)
        assertFalse(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `rx just inside the 10s window is usable`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - TunnelHealth.RX_WINDOW_MS + 1_000L)
        assertTrue(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `eio inside the 30s window blocks usability`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - 1_000L)
        TunnelHealth.setLastEioAtMsForTest(now - 1_000L)
        assertFalse(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `eio older than the 30s window does not block usability`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - 1_000L)
        TunnelHealth.setLastEioAtMsForTest(now - TunnelHealth.EIO_WINDOW_MS - 1_000L)
        assertTrue(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `eio exactly at the window boundary does not block usability`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - 1_000L)
        TunnelHealth.setLastEioAtMsForTest(now - TunnelHealth.EIO_WINDOW_MS)
        assertTrue(TunnelHealth.isUsable(isConnected = true, now))
    }

    @Test
    fun `stale rx and recent eio both fail closed`() {
        TunnelHealth.setLastRxAdvanceAtMsForTest(now - TunnelHealth.RX_WINDOW_MS)
        TunnelHealth.setLastEioAtMsForTest(now - 1_000L)
        assertFalse(TunnelHealth.isUsable(isConnected = true, now))
    }

    // ── counter behaviour ──────────────────────────────────────────────

    @Test
    fun `rx advance increments counter and stamps clock`() {
        assertEquals(0L, TunnelHealth.rxCounter())
        TunnelHealth.noteRxAdvance()
        TunnelHealth.noteRxAdvance()
        assertEquals(2L, TunnelHealth.rxCounter())
        assertTrue(TunnelHealth.lastRxAdvanceAtMs() > 0L)
    }

    @Test
    fun `noteTunEio increments counter and stamps clock`() {
        assertEquals(0L, TunnelHealth.tunEioCount())
        assertFalse(TunnelHealth.hasSeenEio())
        TunnelHealth.noteTunEio()
        assertEquals(1L, TunnelHealth.tunEioCount())
        assertTrue(TunnelHealth.hasSeenEio())
        assertTrue(TunnelHealth.lastEioAtMs() > 0L)
    }

    @Test
    fun `window constants match the already-decided Q1 contract`() {
        // SERVER_SOURCE_ARCHITECTURE.md §7 Q1: RX within 10 s, no EIO in 30 s.
        // These are USER-DIRECTED REQUIREMENT values — assert they are not drifted.
        assertEquals(10_000L, TunnelHealth.RX_WINDOW_MS)
        assertEquals(30_000L, TunnelHealth.EIO_WINDOW_MS)
    }

    // ── A-2 (§3.2): the system-VPN loss re-check budget ────────────────
    //
    // `onLost` re-checks "is any VPN transport still alive?" after a grace
    // period. That grace period is load-bearing in BOTH directions, so it is
    // pinned from both sides rather than asserted to equal one magic number:
    //
    //   ceiling — it was 500 ms, which kept a dead tunnel published as
    //             CONNECTED for half a second (the A-2 defect);
    //   floor   — it must not be 0, because during a network handover
    //             `onLost` for the old network fires BEFORE `onAvailable` for
    //             the new one, and a zero-delay check would read that as
    //             "all VPN transports lost" and tear down a tunnel that is
    //             merely being re-parented.
    //
    // Referencing the constant is safe here: a `const val` is inlined by the
    // compiler, so no reference to the Android-dependent `GlobalVpnTracker`
    // object is emitted and nothing is class-loaded.

    @Test
    fun `vpnLostRecheckIsBoundedWellBelowTheOldHalfSecond`() {
        assertTrue(
            "A-2: the stale-CONNECTED window must be far below the old 500 ms",
            VPN_LOST_RECHECK_MS < 500L,
        )
    }

    @Test
    fun `vpnLostRecheckStillLeavesRoomForANetworkHandover`() {
        assertTrue(
            "A-2: a zero-delay re-check would misread a network handover as a lost tunnel",
            VPN_LOST_RECHECK_MS >= 10L,
        )
    }

    @Test
    fun `vpnLostRecheckUsesTheNamedConstantNotAnInlineDelay`() {
        val source = sourceOf("src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt")

        // The re-check is parameterised by the named constant ...
        assertTrue(
            "onLost must re-check after VPN_LOST_RECHECK_MS, not an inline literal",
            source.contains("}, VPN_LOST_RECHECK_MS)"),
        )
        // ... and the old half-second literal is gone from the callback.
        assertTrue(
            "no inline 500 ms delay may remain in the system-VPN watcher",
            !source.contains("}, 500)"),
        )
        // The constant documents both halves of its rationale. The KDoc sits
        // immediately above the declaration, so read backwards from it.
        val doc = source.substringBefore("internal const val VPN_LOST_RECHECK_MS").takeLast(900)
        assertTrue(
            "the constant must document why the delay is reduced rather than removed",
            doc.contains("handover") || doc.contains("re-parent"),
        )
    }

    // ── A-3 (§3.3): the protocol egates are gone ───────────────────────

    @Test
    fun `terminalBranchesNoLongerDropWrongProtocolEvents`() {
        val source = sourceOf("src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt")

        // Before A-3, five branches each guarded on the published protocol.
        // Four were converted to explicit attribution; the SSTP preference
        // listener keeps its guard, which is behaviour-identical — its
        // condition already requires SSTP to own the published CONNECTED slot,
        // which is exactly the case in which the ledger publishes the same
        // teardown, and in every other case it agrees with the ledger's
        // "publish nothing". Only `if (... == ...)` guards are counted, and
        // comment lines are skipped (the A-3 block above quotes the removed
        // pattern verbatim to document it).
        val guardPattern = Regex("""if \(_vpnState\.value\.protocol\s*==\s*""")
        val guards = source.lines().count { line ->
            val trimmed = line.trimStart()
            !trimmed.startsWith("//") && guardPattern.containsMatchIn(trimmed)
        }
        assertEquals(
            "only the SSTP listener may still gate on the published protocol",
            1,
            guards,
        )
        assertTrue(
            "the SoftEther egate must be gone",
            !source.contains("""if (_vpnState.value.protocol == "SoftEther")"""),
        )
        assertTrue(
            "the OpenVPN egate must be gone",
            !source.contains("""if (_vpnState.value.protocol == "OpenVPN")"""),
        )
    }

    @Test
    fun `updateStateDelegatesToTheReconciliationLedger`() {
        val source = sourceOf("src/main/java/vn/unlimit/vpngate/state/GlobalVpnTracker.kt")

        assertTrue(
            "updateState must fold every event through the pure ledger",
            source.contains("VpnStackLedger.reconcile("),
        )
        assertTrue(
            "the tracker must own the live-stack list the ledger folds",
            source.contains("private val liveStacks"),
        )
    }

    /** Reads a production source file, relative to the module directory. */
    private fun sourceOf(relativePath: String): String {
        for (path in listOf(relativePath, "../$relativePath", "app/$relativePath")) {
            val file = File(path)
            if (file.isFile) return file.readText(Charsets.UTF_8)
        }
        error("source file not found: $relativePath (cwd=${File(".").absolutePath})")
    }
}
