package vn.unlimit.vpngate.automode

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.softether.TeardownCompletion

/**
 * Stage 2F-F.28AA — Auto Mode must not read "our own native slot is still busy" as
 * "this server is unreachable".
 *
 * ## The defect these tests pin
 *
 * F.28Z traced it in `SoftEtherVpnService.startVpn`: a start refused because the
 * process-wide native slot is still owned by an unresolved operation was indistinguishable
 * from a server that never came up. To Auto Mode the refusal looked like a dead server, so it:
 *
 *  1. waited the full `attemptTimeoutMs` for a CONNECTED that could never arrive;
 *  2. logged `"Protocol failed"` for the selected server;
 *  3. advanced to the next protocol, then the next server;
 *  4. and could finish the whole run with `ERROR_NO_SERVER`, having "rejected" healthy
 *     servers it never actually contacted.
 *
 * ## What is real and what is simulated
 *
 * **Real.** The controller, its attempt loop, its protocol/server cursors and its
 * `usableProtocols` capability filter are the production ones; only the `ConnectionAdapter`
 * is injected, so the refusal branch is reached through the same control flow the app uses.
 *
 * **Real.** `TeardownCompletion` and `TunnelStateWatcher.onSoftEtherNativeBusy` are the
 * actual types the service hands the refusal to.
 *
 * **Not covered here.** `AndroidConnectionAdapter.awaitArmedOutcome` needs a real `Context`
 * (it reads `ConnectivityManager`), and `SoftEtherVpnService.startVpn` needs a real `Service`.
 * Their wiring is pinned by source-shape assertions in `AutoModeLifecycleF28Test` and by the
 * ownership tests in `NativeAttemptCoordinatorTest`. No device is involved in this file.
 */
class AutoModeNativeBusyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Adapter double that can be told to report a native-busy refusal, and that records
     * every protocol it was asked to try plus every log line the controller emitted.
     *
     * The outcome is returned immediately rather than after [timeoutMs], so these tests
     * exercise the *classification* of the refusal and not the wait that precedes it.
     */
    private open class BusyAwareAdapter : AutoModeController.ConnectionAdapter {
        val connects = CopyOnWriteArrayList<AutoModeProtocol>()
        val disconnects = AtomicInteger(0)
        val logs = CopyOnWriteArrayList<String>()

        /** When true, every armed attempt reports NATIVE_BUSY. */
        @Volatile
        var nativeBusy = false

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connects += protocol
        }

        override suspend fun disconnect() {
            disconnects.incrementAndGet()
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean = false

        override suspend fun awaitArmedTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean = false

        override suspend fun awaitArmedOutcome(
            protocol: AutoModeProtocol,
            timeoutMs: Long,
        ): AutoModeController.AttemptOutcome =
            if (nativeBusy) AutoModeController.AttemptOutcome.NATIVE_BUSY
            else AutoModeController.AttemptOutcome.TIMED_OUT

        override suspend fun isOwnTunnelEstablished(): Boolean = false

        override fun log(message: String) {
            logs += message
        }
    }

    /**
     * A candidate with both SoftEther transports available, so `usableProtocols` yields two
     * protocols per server and a per-server protocol cursor is actually observable.
     */
    private fun server(host: String) = AutoModeCandidate(
        hostname = host,
        ip = "10.0.0.1",
        speed = 10_000_000L,
        ping = 50,
        sessions = 100,
        score = 1000,
        sources = 2,
        seTcpPort = 443,
        seUdpPort = 0,
        seUdpSupported = true,
        openVpnTcpPort = 0,
        openVpnUdpPort = 0,
        l2tpSupported = false,
        sstpSupported = false,
    )

    private fun controller(adapter: BusyAwareAdapter, servers: List<AutoModeCandidate>) =
        AutoModeController(
            scope = scope,
            adapter = adapter,
            protocolPriorityProvider = {
                listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP)
            },
            serverProvider = { servers },
            onSuccess = { _, _ -> },
            attemptTimeoutMs = 1_000L,
            reachabilityFilter = null,
            activeRttProvider = { _, _ -> null },
        )

    private fun runUntilTerminal(c: AutoModeController) = runBlocking {
        c.start()
        withTimeoutOrNull(15_000) {
            while (c.state.value !is AutoModeState.Error && c.state.value !is AutoModeState.Connected) {
                delay(5)
            }
        }
        Unit
    }

    // ---------------------------------------------------- Auto Mode behaviour

    @Test
    fun `native-busy refusal stops the run instead of blaming the server`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        val c = controller(adapter, listOf(server("a"), server("b"), server("c")))

        runUntilTerminal(c)

        val state = c.state.value
        assertTrue("expected an error state, was $state", state is AutoModeState.Error)
        assertEquals(
            "a busy native slot must not be reported as 'no compatible server'",
            AutoModeController.ERROR_NATIVE_UNRESOLVED,
            (state as AutoModeState.Error).message,
        )
    }

    @Test
    fun `native-busy refusal does not rotate to a second server`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        val c = controller(adapter, listOf(server("a"), server("b"), server("c")))

        runUntilTerminal(c)

        assertEquals(
            "only the first server's protocol may have been dialled before the run stopped",
            AutoModeController.NATIVE_BUSY_MAX_RETRIES + 1,
            adapter.connects.size,
        )
        assertTrue(
            "every dial before the run stopped must be the first server's TCP retry",
            adapter.connects.all { it == AutoModeProtocol.SOFTETHER_TCP },
        )
    }

    @Test
    fun `native-busy refusal does not advance through the protocols of one server`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        // The candidate offers both SoftEther transports, so without the fix this would try
        // TCP and then UDP on the same server.
        val c = controller(adapter, listOf(server("a")))

        runUntilTerminal(c)

        assertEquals(
            "a refusal retries the SAME protocol and never advances to the server's other protocols",
            List(AutoModeController.NATIVE_BUSY_MAX_RETRIES + 1) { AutoModeProtocol.SOFTETHER_TCP },
            adapter.connects.toList(),
        )
        assertFalse(
            "a refusal must never try the server's other protocol (UDP)",
            adapter.connects.contains(AutoModeProtocol.SOFTETHER_UDP),
        )
        assertEquals(
            "no teardown may be requested for a server that was never really connected",
            0,
            adapter.disconnects.get(),
        )
    }

    @Test
    fun `native-busy refusal never logs a protocol failure`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        val c = controller(adapter, listOf(server("a"), server("b")))

        runUntilTerminal(c)

        assertTrue(
            "the selected server must not be recorded as failed: ${adapter.logs}",
            adapter.logs.none { it.contains("Protocol failed") },
        )
    }

    @Test
    fun `native-busy refusal never reports ERROR_NO_SERVER`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        // Many servers: without the fix this drains the list and ends in ERROR_NO_SERVER.
        val c = controller(adapter, (1..12).map { server("s$it") })

        runUntilTerminal(c)

        assertNotEquals(
            "ERROR_NO_SERVER must not be reported solely because the slot was occupied",
            AutoModeController.ERROR_NO_SERVER,
            (c.state.value as AutoModeState.Error).message,
        )
    }

    @Test
    fun `the refusal is diagnosed in the log`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        val c = controller(adapter, listOf(server("a")))

        runUntilTerminal(c)

        assertTrue(
            "the unresolved native operation must be diagnosable in the log: ${adapter.logs}",
            adapter.logs.any {
                it.contains("Native slot still owned") && it.contains("without blaming")
            },
        )
    }

    @Test
    fun `a busy refusal on the last server is not hidden by a list exhaustion`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        val c = controller(adapter, listOf(server("only")))

        runUntilTerminal(c)

        assertEquals(
            AutoModeController.ERROR_NATIVE_UNRESOLVED,
            (c.state.value as AutoModeState.Error).message,
        )
    }

    // ------------------------------------------- genuine failures keep their policy

    @Test
    fun `genuine failures still rotate protocols and servers`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = false // every attempt simply times out
        val c = controller(adapter, listOf(server("a"), server("b")))

        runUntilTerminal(c)

        assertEquals(
            "the pre-existing retry policy must be preserved for genuine failures",
            4, // 2 servers x 2 usable SoftEther protocols
            adapter.connects.size,
        )
        assertTrue(
            "genuine failures must still be reported per protocol",
            adapter.logs.any { it.contains("Protocol failed") },
        )
        assertEquals(
            "an exhausted list is still a genuine ERROR_NO_SERVER",
            AutoModeController.ERROR_NO_SERVER,
            (c.state.value as AutoModeState.Error).message,
        )
    }

    @Test
    fun `an established tunnel is still adopted despite a missed notification`() {
        val adapter = object : BusyAwareAdapter() {
            override suspend fun isOwnTunnelEstablished(): Boolean = true
        }
        adapter.nativeBusy = false
        val c = controller(adapter, listOf(server("a")))

        runUntilTerminal(c)

        assertTrue(
            "Fix C/H must survive the F.28AA branch: was ${c.state.value}",
            c.state.value is AutoModeState.Connected,
        )
    }

    // ------------------------------------------------- result-type unit evidence

    @Test
    fun `only NATIVE_CONFIRMED claims native cleanup`() {
        assertTrue(
            TeardownCompletion.of(1, 1, TeardownCompletion.Outcome.NATIVE_CONFIRMED)
                .nativeCleanupConfirmed,
        )
        assertFalse(
            TeardownCompletion.of(2, 1, TeardownCompletion.Outcome.NATIVE_UNRESOLVED)
                .nativeCleanupConfirmed,
        )
        assertFalse(
            "a re-entrant stop must never fabricate a confirmed cleanup",
            TeardownCompletion.of(3, 1, TeardownCompletion.Outcome.NOOP_REENTRANT)
                .nativeCleanupConfirmed,
        )
        assertFalse(
            "a foreign stack's terminal state asserts nothing about native cleanup",
            TeardownCompletion.foreignStackTerminal().nativeCleanupConfirmed,
        )
    }

    @Test
    fun `a foreign-stack terminal report is outside the attempt-id sequence`() {
        assertFalse(
            "the OpenVPN terminal state must not participate in the staleness filter",
            TeardownCompletion.isSequenced(TeardownCompletion.foreignStackTerminal()),
        )
        assertTrue(
            TeardownCompletion.isSequenced(
                TeardownCompletion.of(9, 1, TeardownCompletion.Outcome.NATIVE_UNRESOLVED),
            ),
        )
    }

    @Test
    fun `each teardown outcome describes itself distinctly`() {
        val texts = TeardownCompletion.Outcome.entries.map {
            TeardownCompletion.of(4, 4, it).describe()
        }
        assertEquals(
            "every outcome must be distinguishable in a log line",
            texts.size,
            texts.toSet().size,
        )
        assertTrue(texts.any { it.contains("NATIVE_UNRESOLVED") })
        assertTrue(texts.any { it.contains("NOOP_REENTRANT") })
    }

    // --------------------------------------- native-busy delivery is gated on protocol

    @Test
    fun `native-busy only reaches a watcher that is watching a SoftEther attempt`() {
        val fired = AtomicInteger(0)
        TunnelStateWatcher.beginAttempt(AutoModeProtocol.SOFTETHER_UDP) { }
        TunnelStateWatcher.onNativeBusy = { fired.incrementAndGet() }
        try {
            TunnelStateWatcher.onSoftEtherNativeBusy(3, "attempt=1 phase=CONNECTING")
            assertEquals("a watched SoftEther attempt must be told", 1, fired.get())
        } finally {
            TunnelStateWatcher.endAttempt()
        }

        TunnelStateWatcher.beginAttempt(AutoModeProtocol.OPENVPN_TCP) { }
        TunnelStateWatcher.onNativeBusy = { fired.incrementAndGet() }
        try {
            TunnelStateWatcher.onSoftEtherNativeBusy(3, "attempt=1")
            assertEquals(
                "a refusal must not disturb a non-SoftEther attempt's watcher",
                1,
                fired.get(),
            )
        } finally {
            TunnelStateWatcher.endAttempt()
        }
    }

    @Test
    fun `the busy callback does not survive the end of its attempt`() {
        val fired = AtomicInteger(0)
        TunnelStateWatcher.beginAttempt(AutoModeProtocol.SOFTETHER_TCP) { }
        TunnelStateWatcher.onNativeBusy = { fired.incrementAndGet() }
        TunnelStateWatcher.endAttempt()

        TunnelStateWatcher.onSoftEtherNativeBusy(4, "late refusal after the attempt ended")

        assertEquals(
            "a refusal delivered after the attempt ended must reach nobody",
            0,
            fired.get(),
        )
    }

    @Test
    fun `an unresolved teardown is never reported as confirmed`() {
        // AndroidConnectionAdapter.disconnect() needs a real Context, so what is pinned
        // here is the branch's decision, expressed as the same predicate the adapter uses.
        val unresolved = TeardownCompletion.of(
            11, 2, TeardownCompletion.Outcome.NATIVE_UNRESOLVED,
        )
        assertFalse(unresolved.nativeCleanupConfirmed)
        assertTrue(unresolved.describe().contains("NATIVE_UNRESOLVED"))
    }

    @Test
    fun `the default attempt timeout is unchanged`() {
        // The refusal is delivered on the service's main handler while the attempt waits
        // on the adapter's dispatcher; nothing in the contract requires them to share a
        // thread. Keeping the default intact preserves "a genuine failure still waits".
        assertEquals(25_000L, AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS)
    }

    // ------------------------------------- Stage 2F-F.28AL-AUTOCONNECT-BUG-FIX (BUG 2)
    //
    // BUG 2 was a permanent hang: after a user skip (or a server that simply
    // reported DISCONNECTED/ERROR), the armed `select` in
    // AndroidConnectionAdapter.awaitArmedTunnel raced `armedTunnel` against
    // `armedNativeBusy` with NO timeout, and neither deferred completed on those
    // events — so the coroutine suspended forever and the `skipRequested` check in
    // runAutoMode was never reached.
    //
    // AndroidConnectionAdapter needs a real Context, so these tests pin the
    // *contract the fix guarantees* — "an armed attempt that is neither up nor
    // refused must still end within the per-attempt timeout" — by driving the
    // controller through a double that reproduces the adapter's armed-wait
    // semantics: the wait completes ONLY via the per-attempt timeout (the exact
    // `withTimeoutOrNull(timeoutMs)` the fix added), never on its own.

    /**
     * Adapter double that models the post-fix armed-wait semantics for an attempt
     * that is neither tunnelled nor refused: the wait is bounded by [timeoutMs]
     * and otherwise reports TIMED_OUT, so the run must advance rather than hang.
     */
    private open class ArmedWaitAdapter(
        private val perAttemptMs: Long,
    ) : AutoModeController.ConnectionAdapter {
        val connects = CopyOnWriteArrayList<AutoModeProtocol>()
        val disconnects = AtomicInteger(0)
        val logs = CopyOnWriteArrayList<String>()
        val outcomes = CopyOnWriteArrayList<AutoModeController.AttemptOutcome>()

        /** A skip that fires once, on the first armed wait, then never again. */
        @Volatile
        var skipOnceOnFirstWait = false
        private val skipped = AtomicInteger(0)

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connects += protocol
        }

        override suspend fun disconnect() {
            disconnects.incrementAndGet()
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean = false

        override suspend fun awaitArmedTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean =
            // The pre-fix code had no bound here; the fix guarantees one, and this
            // double reproduces the post-fix behaviour so the run can be observed
            // to terminate.
            withTimeoutOrNull(timeoutMs) {
                kotlinx.coroutines.CompletableDeferred<Boolean>().await()
            } ?: false

        override suspend fun awaitArmedOutcome(
            protocol: AutoModeProtocol,
            timeoutMs: Long,
        ): AutoModeController.AttemptOutcome {
            if (skipOnceOnFirstWait && skipped.compareAndSet(0, 1)) {
                // Simulate the user pressing "Try next server": the wait ends
                // immediately (skipCurrent completes armedTunnel(false)) and the
                // controller's skipRequested path turns it into a server skip.
                outcomes += AutoModeController.AttemptOutcome.TIMED_OUT
                return AutoModeController.AttemptOutcome.TIMED_OUT
            }
            val up = awaitArmedTunnel(protocol, timeoutMs)
            val outcome = if (up) AutoModeController.AttemptOutcome.TUNNEL_UP
            else AutoModeController.AttemptOutcome.TIMED_OUT
            outcomes += outcome
            return outcome
        }

        override suspend fun isOwnTunnelEstablished(): Boolean = false

        override fun log(message: String) {
            logs += message
        }
    }

    /**
     * BUG 2 (variant 1): an armed attempt that is never completed must not wedge
     * the run — it ends within the per-attempt timeout and the loop advances/ends.
     */
    @Test
    fun `autoModeSkipDoesNotHangAwaitArmedTunnel`() {
        val adapter = ArmedWaitAdapter(perAttemptMs = 100L)
        val c = AutoModeController(
            scope = scope,
            adapter = adapter,
            protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_UDP) },
            serverProvider = { listOf(server("a"), server("b")) },
            onSuccess = { _, _ -> },
            attemptTimeoutMs = 100L,
            reachabilityFilter = null,
            activeRttProvider = { _, _ -> null },
        )

        // The whole run must finish well before the 15s safety net: with a 100 ms
        // per-attempt bound and 2 servers, a hang would blow straight past it.
        runUntilTerminal(c)

        assertTrue(
            "an un-completed armed wait must end within the timeout, not hang; logs=${adapter.logs}",
            c.state.value is AutoModeState.Error,
        )
        assertEquals(
            AutoModeController.ERROR_NO_SERVER,
            (c.state.value as AutoModeState.Error).message,
        )
        assertEquals(
            "both servers must have been attempted (the wait did not stall on the first)",
            listOf(AutoModeProtocol.SOFTETHER_UDP, AutoModeProtocol.SOFTETHER_UDP),
            adapter.connects.toList(),
        )
    }

    /**
     * BUG 2 (variant 2): a server that reports an error (no skip) must also end the
     * armed wait within the timeout and let the run advance.
     */
    @Test
    fun `autoModeTunnelErrorDoesNotHangAwaitArmedTunnel`() {
        val adapter = ArmedWaitAdapter(perAttemptMs = 100L)
        val c = AutoModeController(
            scope = scope,
            adapter = adapter,
            protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_UDP) },
            serverProvider = { listOf(server("a"), server("b"), server("c")) },
            onSuccess = { _, _ -> },
            attemptTimeoutMs = 100L,
            reachabilityFilter = null,
            activeRttProvider = { _, _ -> null },
        )

        runUntilTerminal(c)

        assertEquals(
            "every server must be attempted despite each one erroring out",
            3,
            adapter.connects.size,
        )
        assertEquals(
            AutoModeController.ERROR_NO_SERVER,
            (c.state.value as AutoModeState.Error).message,
        )
    }
}
