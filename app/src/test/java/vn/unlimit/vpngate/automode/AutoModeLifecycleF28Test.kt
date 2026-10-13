package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 2F-F.28 — Auto Connect lifecycle tests for the defect chain reproduced
 * on device in Stage 2F-F.26-ALT and diagnosed in Stage 2F-F.27.
 *
 * TEST C  a timed-out attempt must not continue as failure when an OWNED tunnel
 *         is actually established
 * TEST D  the tunnel watcher is armed BEFORE connect, so an immediate CONNECTED
 *         cannot be missed; and stop() while Connecting requests real teardown
 * TEST G  arming before connect does not lose an already-CONNECTED state
 * TEST H  a timeout cannot leave an owned tunnel behind
 * TEST I  a third-party VPN is never adopted as this app's success
 */
class AutoModeLifecycleF28Test {

    /**
     * Records the controller's call ORDER (not just outcomes) so the
     * arm-before-connect invariant can be asserted, and can model an owned
     * tunnel that the notification wait never reported.
     */
    private class RecordingAdapter : AutoModeController.ConnectionAdapter {
        val calls = mutableListOf<String>()
        val connects = mutableListOf<Pair<String?, AutoModeProtocol>>()
        val failures = mutableSetOf<String>()

        /** Servers whose wait never completes on its own (held until cancel). */
        val hang = mutableSetOf<String>()

        /** Stage 2F-F.28 Fix E: synchronous teardown requests. */
        var requestCount = 0

        /** Calls to the awaiting teardown form, including loop-driven cleanup. */
        var disconnectCount = 0

        /** Stage 2F-F.28 Fix C: what the owned-VPN check reports. */
        var ownTunnelEstablished = false

        override fun armTunnelWatch(protocol: AutoModeProtocol) {
            calls += "arm:${protocol.id}"
        }

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            calls += "connect:${candidate.hostname}"
            connects += candidate.hostname to protocol
        }

        override fun requestDisconnect() {
            calls += "requestDisconnect"
            requestCount++
        }

        override suspend fun disconnect() {
            calls += "disconnect"
            disconnectCount++
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
            calls += "await:${protocol.id}"
            val hostname = connects.lastOrNull()?.first ?: return false
            if (hostname in hang) {
                // Model a connect that neither succeeds nor fails within the
                // attempt window: the run stays in Connecting until cancelled.
                val held = CompletableDeferred<Boolean>()
                return withTimeoutOrNull(timeoutMs) { held.await() } ?: false
            }
            return hostname !in failures
        }

        /** Honours the arm, so ordering is observable end to end. */
        override suspend fun awaitArmedTunnel(
            protocol: AutoModeProtocol,
            timeoutMs: Long,
        ): Boolean = awaitTunnel(protocol, timeoutMs)

        override suspend fun isOwnTunnelEstablished(): Boolean {
            calls += "isOwnTunnelEstablished"
            return ownTunnelEstablished
        }

        override fun log(message: String) {
            calls += "log:$message"
        }

        fun logs(): List<String> = calls.filter { it.startsWith("log:") }.map { it.removePrefix("log:") }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var adapter: RecordingAdapter

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        adapter = RecordingAdapter()
        // Stage 2F-F.28AA: every teardown test below asserts against the *same* process-global
        // high-water mark in TunnelStateWatcher, and this JVM runs them all in one process.
        // Without this rewind, whichever test runs first consumes the top of the attempt-id
        // space and every later one is rejected as stale, so the failures would say nothing
        // about the behaviour under test. No waiter is armed here, so the guard in
        // rewindTeardownSequenceForTests() admits the reset.
        TunnelStateWatcher.rewindTeardownSequenceForTests()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(
        servers: List<AutoModeCandidate>,
        protocol: AutoModeProtocol = AutoModeProtocol.SOFTETHER_UDP,
        timeout: Long = 300,
        onSuccess: (AutoModeCandidate, AutoModeProtocol) -> Unit = { _, _ -> },
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolPriorityProvider = { listOf(protocol) },
        serverProvider = { servers },
        onSuccess = onSuccess,
        attemptTimeoutMs = timeout,
        reachabilityFilter = null,
        activeRttProvider = { _, _ -> null },
    )

    /**
     * F.28AL BUG 1 fixture correction.
     *
     * A SoftEther server is only reachable when its TCP control channel is
     * published (`seTcpPort > 0`): R-UDP/NAT-T has never worked for
     * `SETcpPort=0` servers (see RUDP_IMPLEMENTATION_PLAN.md and
     * SERVER_SOURCE_ARCHITECTURE.md §3.5). `AutoModeProtocol.supports()` now
     * enforces that rule, so a UDP-only fixture is no longer selectable and
     * would silently turn every test below into a "no server" run.
     *
     * This fixture therefore publishes a TCP control channel. It is a
     * test-data correction demanded by the fix, not a behaviour change: no
     * assertion and no production file is touched.
     */
    private fun server(host: String) = AutoModeCandidate(
        hostname = host, ip = "10.0.0.1", speed = 10_000_000, ping = 50,
        sessions = 100, score = 1000, sources = 1,
        seTcpPort = 443, seUdpPort = 0, seUdpSupported = true,
        openVpnTcpPort = 0, openVpnUdpPort = 0,
        l2tpSupported = false, sstpSupported = false,
    )

    // ---------------------------------------------------------------- TEST C

    @Test
    fun `TEST C - timeout with an owned tunnel established is treated as connected`() {
        adapter.failures += "good.example.com"
        adapter.ownTunnelEstablished = true
        var success: Pair<String?, AutoModeProtocol>? = null

        val c = controller(listOf(server("good.example.com"))) { s, p -> success = s.hostname to p }
        runBlocking { c.start() }
        runBlocking { withTimeoutOrNull(3_000) { while (success == null) delay(10) } }

        assertEquals(
            "an owned, established tunnel must be adopted rather than failed",
            "good.example.com" to AutoModeProtocol.SOFTETHER_UDP,
            success,
        )
        assertEquals(
            "no disconnect may be issued for the adopted tunnel",
            0,
            adapter.disconnectCount,
        )
        assertTrue(
            "adoption must be logged explicitly",
            adapter.logs().any { it.contains("notification missed") },
        )
    }

    @Test
    fun `TEST C - adoption does not continue to the next server`() {
        adapter.failures += "first.example.com"
        adapter.ownTunnelEstablished = true
        var success: Pair<String?, AutoModeProtocol>? = null

        val c = controller(
            listOf(server("first.example.com"), server("second.example.com")),
        ) { s, p -> success = s.hostname to p }
        runBlocking { c.start() }
        runBlocking { withTimeoutOrNull(3_000) { while (success == null) delay(10) } }

        assertEquals("first.example.com", success?.first)
        assertEquals(
            "Auto Connect must stop retrying once an owned tunnel exists",
            1,
            adapter.connects.size,
        )
    }

    // ---------------------------------------------------------------- TEST D

    @Test
    fun `TEST D - watcher is armed before the connect request`() {
        var success: Pair<String?, AutoModeProtocol>? = null
        val c = controller(listOf(server("a.example.com"))) { s, p -> success = s.hostname to p }
        runBlocking { c.start() }
        runBlocking { withTimeoutOrNull(3_000) { while (success == null) delay(10) } }

        val armIndex = adapter.calls.indexOfFirst { it.startsWith("arm:") }
        val connectIndex = adapter.calls.indexOfFirst { it.startsWith("connect:") }
        val awaitIndex = adapter.calls.indexOfFirst { it.startsWith("await:") }

        assertTrue("the watcher must be armed", armIndex >= 0)
        assertTrue("connect must be issued", connectIndex >= 0)
        assertTrue("the wait must happen", awaitIndex >= 0)
        assertTrue(
            "armTunnelWatch must precede connect (F.27 RC-6): ${adapter.calls}",
            armIndex < connectIndex,
        )
        assertTrue(
            "the wait must follow connect: ${adapter.calls}",
            connectIndex < awaitIndex,
        )
    }

    @Test
    fun `TEST D - stop while Connecting requests real teardown`() = runBlocking {
        // A server whose wait never completes keeps the run in Connecting.
        adapter.hang += "slow.example.com"
        val c = controller(listOf(server("slow.example.com")), timeout = 30_000)
        c.start()
        withTimeoutOrNull(3_000) {
            while (adapter.calls.none { it.startsWith("await:") }) delay(10)
        }
        assertTrue(
            "must be Connecting for this test to mean anything",
            c.state.value is AutoModeState.Connecting,
        )

        c.stop()

        withTimeoutOrNull(3_000) {
            while (adapter.requestCount == 0) delay(10)
        }
        assertTrue(
            "stop() must request actual teardown (F.27 RC-2): ${adapter.calls}",
            adapter.requestCount >= 1,
        )
        assertTrue(
            "the terminal state must still be published synchronously",
            c.state.value is AutoModeState.Disconnected,
        )
    }

    @Test
    fun `TEST D - stop publishes Disconnected only after teardown is requested`() = runBlocking {
        adapter.hang += "slow.example.com"
        val observed = mutableListOf<String>()
        val c = AutoModeController(
            scope = scope,
            adapter = adapter,
            protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_UDP) },
            serverProvider = { listOf(server("slow.example.com")) },
            onSuccess = { _, _ -> },
            attemptTimeoutMs = 30_000,
            reachabilityFilter = null,
            activeRttProvider = { _, _ -> null },
        ).also { ctrl ->
            ctrl.onStateChange = { state ->
                if (state is AutoModeState.Disconnected) {
                    observed += "requests=${adapter.requestCount}"
                }
            }
        }

        c.start()
        withTimeoutOrNull(3_000) {
            while (adapter.calls.none { it.startsWith("await:") }) delay(10)
        }
        c.stop()
        withTimeoutOrNull(3_000) {
            while (observed.isEmpty()) delay(10)
        }

        assertEquals(
            "teardown must already have been requested when Disconnected is published",
            "requests=1",
            observed.first(),
        )
    }

    // ---------------------------------------------------------------- TEST G

    @Test
    fun `TEST G - replay fires for SoftEther when already CONNECTED`() {
        assertTrue(
            shouldReplayConnectedOnArm(
                AutoModeProtocol.SOFTETHER_UDP,
                vn.unlimit.softether.SoftEtherVpnService.STATE_CONNECTED,
            ),
        )
        assertTrue(
            shouldReplayConnectedOnArm(
                AutoModeProtocol.SOFTETHER_TCP,
                vn.unlimit.softether.SoftEtherVpnService.STATE_CONNECTED,
            ),
        )
    }

    @Test
    fun `TEST G - replay does not fire when not connected`() {
        assertFalse(
            shouldReplayConnectedOnArm(
                AutoModeProtocol.SOFTETHER_UDP,
                vn.unlimit.softether.SoftEtherVpnService.STATE_DISCONNECTED,
            ),
        )
        assertFalse(
            shouldReplayConnectedOnArm(AutoModeProtocol.SOFTETHER_UDP, null),
        )
    }

    @Test
    fun `TEST G - replay is scoped to the SoftEther stack`() {
        // An OpenVPN attempt must not be resolved by SoftEther's state.
        assertFalse(
            shouldReplayConnectedOnArm(
                AutoModeProtocol.OPENVPN_TCP,
                vn.unlimit.softether.SoftEtherVpnService.STATE_CONNECTED,
            ),
        )
        assertFalse(
            shouldReplayConnectedOnArm(
                AutoModeProtocol.MS_SSTP,
                vn.unlimit.softether.SoftEtherVpnService.STATE_CONNECTED,
            ),
        )
    }

    @Test
    fun `TEST G - protocol classification helpers`() {
        assertTrue(isSoftEtherProtocol(AutoModeProtocol.SOFTETHER_UDP))
        assertTrue(isSoftEtherProtocol(AutoModeProtocol.SOFTETHER_TCP))
        assertFalse(isSoftEtherProtocol(AutoModeProtocol.OPENVPN_UDP))
        assertTrue(isOpenVpnProtocol(AutoModeProtocol.OPENVPN_UDP))
        assertTrue(isOpenVpnProtocol(AutoModeProtocol.OPENVPN_TCP))
        assertFalse(isOpenVpnProtocol(AutoModeProtocol.SOFTETHER_UDP))
    }

    // ---------------------------------------------------------------- TEST H

    @Test
    fun `TEST H - a timeout leaves no owned tunnel behind`() {
        adapter.failures += "a.example.com"
        adapter.failures += "b.example.com"
        adapter.ownTunnelEstablished = true
        var success: Pair<String?, AutoModeProtocol>? = null

        val c = controller(
            listOf(server("a.example.com"), server("b.example.com")),
        ) { s, p -> success = s.hostname to p }
        runBlocking { c.start() }
        runBlocking { withTimeoutOrNull(3_000) { while (success == null) delay(10) } }

        assertEquals("must adopt rather than walk past a live owned tunnel", 1, adapter.connects.size)
        assertEquals("no teardown may be issued while the tunnel is live", 0, adapter.disconnectCount)
        assertEquals("a.example.com", success?.first)
    }

    @Test
    fun `TEST H - without an owned tunnel the normal failure path still runs`() {
        adapter.failures += "a.example.com"
        adapter.failures += "b.example.com"
        adapter.ownTunnelEstablished = false

        val c = controller(listOf(server("a.example.com"), server("b.example.com")))
        runBlocking { c.start() }
        runBlocking { withTimeoutOrNull(5_000) { while (c.state.value !is AutoModeState.Error) delay(10) } }

        assertTrue(c.state.value is AutoModeState.Error)
        assertEquals("both servers must be attempted", 2, adapter.connects.size)
        assertTrue("each failed attempt tears down", adapter.disconnectCount >= 2)
    }

    // ---------------------------------------------------------------- TEST I

    @Test
    fun `TEST I - ownership rule accepts this app's own VPN`() {
        assertTrue(
            OwnedVpnDecision.isOwnedByThisApp(
                hasVpnTransport = true, ownerUid = 10340, sdkInt = 33, myUid = 10340,
            ),
        )
    }

    @Test
    fun `TEST I - ownership rule rejects a third-party VPN`() {
        assertFalse(
            "an unrelated VPN must never be adopted as this app's tunnel",
            OwnedVpnDecision.isOwnedByThisApp(
                hasVpnTransport = true, ownerUid = 10222, sdkInt = 33, myUid = 10340,
            ),
        )
    }

    @Test
    fun `TEST I - ownership rule rejects a non-VPN network`() {
        assertFalse(
            OwnedVpnDecision.isOwnedByThisApp(
                hasVpnTransport = false, ownerUid = 10340, sdkInt = 33, myUid = 10340,
            ),
        )
    }

    @Test
    fun `TEST I - ownership is undecidable below API 29 and must answer false`() {
        assertFalse(
            OwnedVpnDecision.isOwnedByThisApp(
                hasVpnTransport = true, ownerUid = 10340, sdkInt = 24, myUid = 10340,
            ),
        )
        assertEquals(29, OwnedVpnDecision.MIN_SDK_FOR_OWNER_UID)
    }

    @Test
    fun `TEST I - a third-party VPN does not stop Auto Connect retrying`() {
        // Ownership false => behaves exactly as before the fix: keep retrying.
        adapter.failures += "a.example.com"
        adapter.failures += "b.example.com"
        adapter.ownTunnelEstablished = false

        val c = controller(listOf(server("a.example.com"), server("b.example.com")))
        runBlocking { c.start() }
        runBlocking { withTimeoutOrNull(5_000) { while (c.state.value !is AutoModeState.Error) delay(10) } }

        assertEquals(2, adapter.connects.size)
        assertTrue(c.state.value is AutoModeState.Error)
    }

    // ------------------------------------------- arm/disarm teardown waiter

    @Test
    fun `teardown waiter does not fire on CONNECTED`() {
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val token = TunnelStateWatcher.armTeardown(21) { fired.set(true) }
        try {
            TunnelStateWatcher.onSoftEtherStateChanged(
                vn.unlimit.softether.SoftEtherVpnService.STATE_CONNECTED, "10.211.1.28",
            )
            assertFalse("CONNECTED must not satisfy a teardown waiter", fired.get())
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    @Test
    fun `armed watcher completes on CONNECTED only for SoftEther protocols`() {
        val seen = mutableListOf<AutoModeProtocol>()
        TunnelStateWatcher.beginAttempt(AutoModeProtocol.SOFTETHER_UDP) { seen += it }
        try {
            TunnelStateWatcher.onSoftEtherStateChanged(
                vn.unlimit.softether.SoftEtherVpnService.STATE_CONNECTED, "10.211.1.28",
            )
            assertEquals(listOf(AutoModeProtocol.SOFTETHER_UDP), seen)
        } finally {
            TunnelStateWatcher.endAttempt()
        }
    }

    @Test
    fun `endAttempt stops the watcher delivering CONNECTED`() {
        val seen = mutableListOf<AutoModeProtocol>()
        TunnelStateWatcher.beginAttempt(AutoModeProtocol.SOFTETHER_UDP) { seen += it }
        TunnelStateWatcher.endAttempt()
        TunnelStateWatcher.onSoftEtherStateChanged(
            vn.unlimit.softether.SoftEtherVpnService.STATE_CONNECTED, "10.211.1.28",
        )
        assertTrue("no callback may fire after endAttempt", seen.isEmpty())
    }

    @Test
    fun `TEST G - arming replays an already-CONNECTED SoftEther tunnel`() {
        // Models the arm-before-connect ordering when the tunnel is ALREADY up:
        // the arm itself must resolve the wait immediately.
        val previous = TunnelStateWatcher.stateProvider
        TunnelStateWatcher.stateProvider = {
            vn.unlimit.softether.SoftEtherVpnService.STATE_CONNECTED
        }
        try {
            var replayed = false
            TunnelStateWatcher.beginAttempt(AutoModeProtocol.SOFTETHER_UDP) { replayed = true }
            assertTrue(
                "an already-CONNECTED tunnel must not be missed when arming before connect",
                replayed,
            )
            TunnelStateWatcher.endAttempt()
        } finally {
            TunnelStateWatcher.stateProvider = previous
        }
    }

    @Test
    fun `TEST G - arming does not replay when nothing is connected`() {
        val previous = TunnelStateWatcher.stateProvider
        TunnelStateWatcher.stateProvider = {
            vn.unlimit.softether.SoftEtherVpnService.STATE_DISCONNECTED
        }
        try {
            var replayed = false
            TunnelStateWatcher.beginAttempt(AutoModeProtocol.SOFTETHER_UDP) { replayed = true }
            assertFalse("nothing to replay when disconnected", replayed)
            TunnelStateWatcher.endAttempt()
        } finally {
            TunnelStateWatcher.stateProvider = previous
        }
    }

    @Test
    fun `TEST G - a state provider failure must not crash the arm`() {
        val previous = TunnelStateWatcher.stateProvider
        TunnelStateWatcher.stateProvider = { throw RuntimeException("no looper in unit test") }
        try {
            var replayed = false
            TunnelStateWatcher.beginAttempt(AutoModeProtocol.SOFTETHER_UDP) { replayed = true }
            assertFalse("a provider failure degrades to no replay", replayed)
            TunnelStateWatcher.endAttempt()
        } finally {
            TunnelStateWatcher.stateProvider = previous
        }
    }

    @Test
    fun `TEST T1 - a logical terminal state alone does NOT satisfy a teardown waiter`() {
        // Stage 2F-F.28A-REVIEW regression guard: STATE_DISCONNECTED is
        // published when teardown STARTS, before destroyResources() runs, so
        // completing the waiter on it made disconnect() return while the TUN,
        // keepalive jobs and native handle were still alive.
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val token = TunnelStateWatcher.armTeardown(21) { fired.set(true) }
        try {
            TunnelStateWatcher.onSoftEtherStateChanged(
                vn.unlimit.softether.SoftEtherVpnService.STATE_DISCONNECTED, "",
            )
            TunnelStateWatcher.onSoftEtherStateChanged(
                vn.unlimit.softether.SoftEtherVpnService.STATE_ERROR, "",
            )
            assertFalse(
                "the terminal logical state must not be treated as resource-destruction completion",
                fired.get(),
            )
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    @Test
    fun `TEST T2 - the teardown waiter completes on the teardown-completion signal`() {
        val down = CompletableDeferred<vn.unlimit.softether.TeardownCompletion>()
        // Stage 2F-F.28AD-B: the waiter names the attempt it is waiting for, and only that
        // attempt's completion satisfies it. The id no longer has to out-rank everything the
        // shared JVM has already seen — identity decides, not magnitude.
        val token = TunnelStateWatcher.armTeardown(31) { result -> down.complete(result) }
        try {
            // Stage 2F-F.28AA: the result is delivered verbatim.
            TunnelStateWatcher.onSoftEtherTeardownComplete(
                vn.unlimit.softether.TeardownCompletion.of(
                    attemptId = 31,
                    sessionId = 1,
                    outcome = vn.unlimit.softether.TeardownCompletion.Outcome.NATIVE_CONFIRMED,
                ),
            )
            assertTrue(
                "only a post-destroy signal may satisfy the teardown waiter",
                runBlocking { withTimeoutOrNull(1_000) { down.await(); true } == true },
            )
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    @Test
    fun `TEST T2b - a consumed waiter is not satisfied again by a later signal`() {
        val seen = mutableListOf<vn.unlimit.softether.TeardownCompletion>()
        val token = TunnelStateWatcher.armTeardown(41) { seen += it }
        try {
            // The same completion arriving twice must still be delivered only once.
            repeat(2) {
                TunnelStateWatcher.onSoftEtherTeardownComplete(
                    vn.unlimit.softether.TeardownCompletion.of(
                        41, 1,
                        vn.unlimit.softether.TeardownCompletion.Outcome.NATIVE_CONFIRMED,
                    ),
                )
            }
            assertEquals(
                "a consumed waiter must not be completed a second time",
                1,
                seen.size,
            )
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    @Test
    fun `TEST T3 - the service publishes completion only after destroyResources`() {
        // Pins the ordering that TEST T2 relies on. Executing stopVpn() needs a
        // real Service, so the guarantee is asserted on the source shape.
        val service = File("../SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt")
            .takeIf { it.isFile }
            ?: File("SoftEtherClient/src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt")
        assertTrue("SoftEtherVpnService.kt not found from ${File(".").absolutePath}", service.isFile)
        val text = service.readText()
        val stopVpn = text.substringAfter("private fun stopVpn(").substringBefore("\n    private fun showDisconnectedNotification")

        // Match the real call, not the explanatory comment above it, and look for the
        // completion signal AFTER it: stopVpn also publishes completion on its
        // re-entrant no-op path, where nothing was left to destroy.
        //
        // Stage 2F-F.28AA: matched without the empty argument list, because the call now
        // carries a TeardownCompletion. Asserting the old `notifyTeardownComplete()` literal
        // would pin the pre-AA signature and fail on the argument that exists precisely so
        // the outcome is no longer ambiguous.
        val destroyAt = stopVpn.indexOf("currentController?.destroyResources()")
        assertTrue("stopVpn() must still call destroyResources()", destroyAt >= 0)
        val completionAt = stopVpn.indexOf("notifyTeardownComplete(", destroyAt)
        assertTrue("stopVpn() must publish a teardown-completion signal", completionAt >= 0)
        assertTrue(
            "teardown completion must be published AFTER destroyResources(), not before it",
            completionAt > destroyAt,
        )
    }

    @Test
    fun `TEST T4 - repeated arming replaces the waiter instead of stacking`() {
        val first = java.util.concurrent.atomic.AtomicBoolean(false)
        val second = java.util.concurrent.atomic.AtomicBoolean(false)
        TunnelStateWatcher.armTeardown(51) { first.set(true) }
        val secondToken = TunnelStateWatcher.armTeardown(52) { second.set(true) }
        try {
            // Only the second waiter's own attempt may satisfy it.
            TunnelStateWatcher.onSoftEtherTeardownComplete(
                vn.unlimit.softether.TeardownCompletion.of(
                    52, 1,
                    vn.unlimit.softether.TeardownCompletion.Outcome.NATIVE_CONFIRMED,
                ),
            )
            assertFalse("a replaced waiter must not fire", first.get())
            assertTrue("the current waiter must fire exactly once", second.get())
        } finally {
            TunnelStateWatcher.disarmTeardown(secondToken)
        }
    }

    @Test
    fun `TEST T5 - disarm stops the completion signal`() {
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val token = TunnelStateWatcher.armTeardown(61) { fired.set(true) }
        TunnelStateWatcher.disarmTeardown(token)
        TunnelStateWatcher.onSoftEtherTeardownComplete(
            vn.unlimit.softether.TeardownCompletion.of(
                61, 1,
                vn.unlimit.softether.TeardownCompletion.Outcome.NATIVE_CONFIRMED,
            ),
        )
        assertFalse("a disarmed waiter must not fire", fired.get())
    }

    @Test
    fun `TEST T5b - disarming one waiter cannot disarm a newer one`() {
        val first = java.util.concurrent.atomic.AtomicBoolean(false)
        val second = java.util.concurrent.atomic.AtomicBoolean(false)
        val firstToken = TunnelStateWatcher.armTeardown(71) { first.set(true) }
        val secondToken = TunnelStateWatcher.armTeardown(72) { second.set(true) }
        try {
            // The FIRST waiter unwinding must not disarm the waiter armed after it.
            TunnelStateWatcher.disarmTeardown(firstToken)
            TunnelStateWatcher.onSoftEtherTeardownComplete(
                vn.unlimit.softether.TeardownCompletion.of(
                    72, 1,
                    vn.unlimit.softether.TeardownCompletion.Outcome.NATIVE_CONFIRMED,
                ),
            )
            assertFalse("the older waiter must stay disarmed", first.get())
            assertTrue("the newer waiter must survive an older disarm", second.get())
        } finally {
            TunnelStateWatcher.disarmTeardown(secondToken)
        }
    }

    @Test
    fun `TEST T5c - a stale completion cannot satisfy a newer waiter`() {
        val seen = mutableListOf<vn.unlimit.softether.TeardownCompletion>()
        val token = TunnelStateWatcher.armTeardown(7) { seen += it }
        try {
            // Attempt 7 is delivered and accounted for...
            TunnelStateWatcher.onSoftEtherTeardownComplete(
                vn.unlimit.softether.TeardownCompletion.of(
                    7, 1,
                    vn.unlimit.softether.TeardownCompletion.Outcome.NATIVE_CONFIRMED,
                ),
            )
            assertEquals(1, seen.size)

            // ...so a late completion from attempt 7 must not reach a newly armed waiter,
            // which is waiting for attempt 8. Stage 2F-F.28AD-B: that is now decided by
            // identity, not by recency — 7 is simply not the attempt this waiter named.
            val token2 = TunnelStateWatcher.armTeardown(8) { seen += it }
            TunnelStateWatcher.onSoftEtherTeardownComplete(
                vn.unlimit.softether.TeardownCompletion.of(
                    7, 1,
                    vn.unlimit.softether.TeardownCompletion.Outcome.NATIVE_UNRESOLVED,
                ),
            )
            assertEquals(
                "a completion for another attempt must be dropped",
                1,
                seen.size,
            )

            // The attempt this waiter actually named is accepted.
            TunnelStateWatcher.onSoftEtherTeardownComplete(
                vn.unlimit.softether.TeardownCompletion.of(
                    8, 1,
                    vn.unlimit.softether.TeardownCompletion.Outcome.NATIVE_UNRESOLVED,
                ),
            )
            assertEquals(2, seen.size)
            assertEquals(
                vn.unlimit.softether.TeardownCompletion.Outcome.NATIVE_UNRESOLVED,
                seen.last().outcome,
            )
            TunnelStateWatcher.disarmTeardown(token2)
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    @Test
    fun `TEST T5d - an unresolved outcome never claims native confirmation`() {
        val seen = mutableListOf<vn.unlimit.softether.TeardownCompletion>()
        val token = TunnelStateWatcher.armTeardown(81) { seen += it }
        try {
            TunnelStateWatcher.onSoftEtherTeardownComplete(
                vn.unlimit.softether.TeardownCompletion.of(
                    81, 1,
                    vn.unlimit.softether.TeardownCompletion.Outcome.NATIVE_UNRESOLVED,
                ),
            )
            assertEquals(1, seen.size)
            assertFalse(
                "NATIVE_UNRESOLVED must not be readable as a confirmed cleanup",
                seen.single().nativeCleanupConfirmed,
            )
            // A second signal cannot fabricate anything for the consumed waiter.
            TunnelStateWatcher.onSoftEtherTeardownComplete(
                vn.unlimit.softether.TeardownCompletion.of(
                    81, 1,
                    vn.unlimit.softether.TeardownCompletion.Outcome.NOOP_REENTRANT,
                ),
            )
        } finally {
            TunnelStateWatcher.disarmTeardown(token)
        }
    }

    @Test
    fun `TEST T6 - an unconfirmed teardown is reported as not confirmed`() {
        val body = disconnectBodySource()
        assertTrue(
            "a timeout must be recorded as 'not confirmed', never as a completed teardown",
            body.contains("Teardown NOT confirmed"),
        )
    }

    // ---------------------------------------------------------------- TEST J

    /**
     * `AndroidConnectionAdapter.disconnect()` needs a real `Context`, so its body
     * cannot be executed on the JVM. What must be pinned is the *shape* of the
     * mechanism: teardown completion comes from the terminal-state signal, and a
     * fixed sleep must not creep back as the only synchronisation.
     */
    private fun disconnectBodySource(): String {
        val candidates = listOf(
            File("src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt"),
            File("app/src/main/java/vn/unlimit/vpngate/automode/AndroidConnectionAdapter.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("AndroidConnectionAdapter.kt not found from ${File(".").absolutePath}")
        val body = file.readText()
            .substringAfter("override suspend fun disconnect()")
            .substringBefore("\n    }")
        // Drop KDoc/line comments: they legitimately mention the old delay.
        return body.lineSequence()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    @Test
    fun `TEST J - disconnect waits on the teardown signal instead of a fixed sleep`() {
        val body = disconnectBodySource()
        assertTrue(
            "disconnect() must arm the real teardown waiter before dispatching",
            body.contains("TunnelStateWatcher.armTeardown"),
        )
        assertTrue(
            "disconnect() must await that signal, bounded so a silent stack cannot wedge Auto Connect",
            body.contains("withTimeoutOrNull(TEARDOWN_TIMEOUT_MS)") && body.contains("teardown.await()"),
        )
        assertFalse(
            "a fixed delay is not proof that the TUN and native sockets were released",
            Regex("""\bdelay\s*\(""").containsMatchIn(body),
        )
    }
}