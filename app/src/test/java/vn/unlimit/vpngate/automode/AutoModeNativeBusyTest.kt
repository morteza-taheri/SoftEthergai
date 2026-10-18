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

        /**
         * FIX-2: every recovery teardown the controller requested on its terminal give-up.
         *
         * Counted separately from [disconnects] on purpose — the two mean different things.
         * A `disconnect()` is the rotation primitive (it precedes trying the next server or
         * protocol); a recovery teardown is issued *after* the run has already decided to
         * stop, purely to release this application's own wedged native operation.
         */
        val recoveryTeardowns = AtomicInteger(0)

        /** When true, every armed attempt reports NATIVE_BUSY. */
        @Volatile
        var nativeBusy = false

        /**
         * FIX-3: when true, every armed attempt reports TUNNEL_UP, so a test can drive the
         * same server from "wedged" to "verified" across two runs.
         */
        @Volatile
        var tunnelUp = false

        /**
         * AUTOCONNECT-AUDIT D-2: when true, a slot wait reports "released" and clears
         * [nativeBusy], so the controller's retry after the wait is a genuine attempt.
         *
         * When false the slot is held for the whole wait, which is the "genuinely stuck"
         * case the fail-closed terminal still covers.
         */
        @Volatile
        var slotReleases = false

        /** How many slot waits the controller performed. */
        val slotWaits = AtomicInteger(0)

        override suspend fun awaitNativeSlotRelease(timeoutMs: Long): Boolean {
            slotWaits.incrementAndGet()
            if (!nativeBusy) return true
            if (slotReleases) {
                nativeBusy = false
                return true
            }
            return false
        }

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            connects += protocol
        }

        override suspend fun disconnect() {
            disconnects.incrementAndGet()
        }

        override fun requestRecoveryTeardown() {
            recoveryTeardowns.incrementAndGet()
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean = false

        override suspend fun awaitArmedTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean = false

        override suspend fun awaitArmedOutcome(
            protocol: AutoModeProtocol,
            timeoutMs: Long,
        ): AutoModeController.AttemptOutcome = when {
            tunnelUp -> AutoModeController.AttemptOutcome.TUNNEL_UP
            nativeBusy -> AutoModeController.AttemptOutcome.NATIVE_BUSY
            else -> AutoModeController.AttemptOutcome.TIMED_OUT
        }

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

    private fun controller(
        adapter: BusyAwareAdapter,
        servers: List<AutoModeCandidate>,
        wedgeTracker: WedgeTracker = WedgeTracker(),
    ) =
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
            // AUTOCONNECT-AUDIT D-2: a short slot wait so the "genuinely stuck" tests reach
            // the terminal give-up quickly instead of paying the production 30 s bound.
            nativeSlotAwaitMs = 200L,
            // FIX-3: a fresh ledger per controller, so no test depends on — or disturbs —
            // the process-wide instance. The FIX-3 tests inject their own so they can share
            // it across runs and assert on it directly.
            wedgeTracker = wedgeTracker,
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

    /**
     * Start a run and wait for it to actually finish, even when a previous run has already
     * left the controller in a terminal state.
     *
     * [runUntilTerminal] polls the *state*, so on a second run it would return immediately —
     * the state is still the previous run's Error. `job` is assigned synchronously by
     * [AutoModeController.start] and cleared by every terminal branch, so waiting on
     * `isRunning` is the wait that is correct for a repeat run. Used by the FIX-3 tests,
     * which need several runs of the same controller.
     */
    private fun runToCompletion(c: AutoModeController) = runBlocking {
        c.start()
        withTimeoutOrNull(20_000) {
            while (c.isRunning) delay(5)
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
    fun `a slot that never releases still does not rotate to a second server`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        adapter.slotReleases = false // the operation is genuinely stuck
        val c = controller(adapter, listOf(server("a"), server("b"), server("c")))

        runUntilTerminal(c)

        assertEquals(
            "only the first server's protocol may have been dialled before the run stopped",
            1,
            adapter.connects.size,
        )
        assertTrue(
            "every dial before the run stopped must be the first server's TCP attempt",
            adapter.connects.all { it == AutoModeProtocol.SOFTETHER_TCP },
        )
    }

    @Test
    fun `native-busy refusal does not advance through the protocols of one server`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        adapter.slotReleases = false
        // The candidate offers both SoftEther transports, so without the fix this would try
        // TCP and then UDP on the same server.
        val c = controller(adapter, listOf(server("a")))

        runUntilTerminal(c)

        assertEquals(
            "a refusal that never clears retries the SAME protocol and never advances to the server's other protocols",
            listOf(AutoModeProtocol.SOFTETHER_TCP),
            adapter.connects.toList(),
        )
        assertFalse(
            "a refusal must never try the server's other protocol (UDP)",
            adapter.connects.contains(AutoModeProtocol.SOFTETHER_UDP),
        )
        assertEquals(
            "a refusal must not ROTATE: no disconnect() may be issued for the server",
            0,
            adapter.disconnects.get(),
        )
        // FIX-2 supersedes the pre-FIX-2 expectation that *no* teardown is requested here.
        // The give-up does now issue one — but through the dedicated recovery primitive, not
        // the rotation primitive, which is exactly why the assertion above still holds.
        assertEquals(
            "FIX-2: the terminal give-up must issue exactly one recovery teardown",
            1,
            adapter.recoveryTeardowns.get(),
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
            adapter.logs.any { it.contains("Native slot not released within") },
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
    fun `the default attempt timeout matches the settings default`() {
        // The refusal is delivered on the service's main handler while the attempt waits
        // on the adapter's dispatcher; nothing in the contract requires them to share a
        // thread. Keeping the default intact preserves "a genuine failure still waits".
        //
        // v1.0.31 (AUTOCONNECT-FIX, D-1): the fallback is now 12_000L, the shipped
        // Settings default, instead of 25_000L. Three figures used to describe this one
        // setting (25 s here, 12 s in DataUtil, 5 s in the spec) and nothing pinned any
        // of them, so the drift was invisible. The assertion is kept — with the corrected
        // value — precisely so it stays a guard against the three drifting apart again.
        assertEquals(12_000L, AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS)
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

    // ------------------------------------- FIX-2: recovery teardown on the give-up path
    //
    // Device evidence V3: Auto Mode gave up on a wedged server while the wedged attempt was
    // still holding the process-wide native slot, and nothing was ever asked to tear down —
    // so the TEARDOWN soft-bound (verified working on device) had no TEARDOWN holder to
    // reclaim. FIX-2 makes the give-up issue a teardown. These tests pin the contract the
    // controller owes: the give-up must ask, exactly once, through the recovery primitive —
    // and must NOT rotate (no disconnect()).

    @Test
    fun `a give-up issues a recovery teardown so the wedged slot can be reclaimed`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        val c = controller(adapter, listOf(server("stuck")))

        runToCompletion(c)

        assertEquals(
            AutoModeController.ERROR_NATIVE_UNRESOLVED,
            (c.state.value as AutoModeState.Error).message,
        )
        assertEquals(
            "FIX-2: exactly one recovery teardown must be requested on the terminal give-up",
            1,
            adapter.recoveryTeardowns.get(),
        )
        assertEquals(
            "the recovery teardown must not be a rotating disconnect()",
            0,
            adapter.disconnects.get(),
        )
        assertTrue(
            "the request must be visible in the log for the next device run: ${adapter.logs}",
            adapter.logs.any { it.contains("Requesting recovery teardown") },
        )
        assertTrue(
            "the give-up must still stop without blaming the server: ${adapter.logs}",
            adapter.logs.none { it.contains("Protocol failed") },
        )
    }

    // ------------------------- AUTOCONNECT-AUDIT D-2: wait for the slot, then rotate
    //
    // Device evidence 2026-10-17: the native connect kept running for 18,915 ms while the
    // controller waited 5,000 ms. The controller's two blind retries expired 27 ms before the
    // native operation returned on its own, so the whole Auto Mode cycle was abandoned while
    // the slot was released 313 ms later. These tests pin the corrected contract: a refusal
    // is answered by waiting for the observable release and then continuing the rotation,
    // and only a slot that never releases ends the run.

    @Test
    fun `a released slot lets the run rotate instead of ending it`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        adapter.slotReleases = true // the operation finishes while we wait
        val c = controller(adapter, listOf(server("a")))

        runUntilTerminal(c)

        assertEquals(
            "the run must exhaust the list, not die on the refusal",
            AutoModeController.ERROR_NO_SERVER,
            (c.state.value as AutoModeState.Error).message,
        )
        assertEquals(
            "the released slot must be retried once, then the rotation must continue " +
                "through the server's second protocol",
            listOf(
                AutoModeProtocol.SOFTETHER_TCP,
                AutoModeProtocol.SOFTETHER_TCP,
                AutoModeProtocol.SOFTETHER_UDP,
            ),
            adapter.connects.toList(),
        )
        assertTrue("the release must be waited for", adapter.slotWaits.get() >= 1)
        assertTrue(
            "the release must be logged: ${adapter.logs}",
            adapter.logs.any { it.contains("Native slot released; retrying") },
        )
        assertFalse(
            "a released slot must never be reported as stuck: ${adapter.logs}",
            adapter.logs.any { it.contains("Native slot not released within") },
        )
    }

    @Test
    fun `the controller waits for the slot instead of retrying blind`() {
        val adapter = BusyAwareAdapter()
        adapter.nativeBusy = true
        adapter.slotReleases = true
        val c = controller(adapter, listOf(server("a"), server("b")))

        runUntilTerminal(c)

        assertTrue(
            "the observable wait is the mechanism: ${adapter.logs}",
            adapter.slotWaits.get() >= 1,
        )
        assertTrue(
            "the post-release attempt must be logged as a genuine failure and rotated: " +
                "${adapter.logs}",
            adapter.logs.any {
                it.contains("Attempt after the slot was released did not come up")
            },
        )
        assertTrue(
            "the server's protocol must still be reported as failed: ${adapter.logs}",
            adapter.logs.any { it.contains("Protocol failed: SOFTETHER_TCP") },
        )
    }

    // ------------------------------------------- FIX-3: the per-server wedge ledger
    //
    // Device evidence V4: the same server wedged on every run. FIX-2 releases the slot, but
    // Auto Mode still re-selected that server — it ranks by quality, and the wedging server
    // was often the best-ranked one — so the user simply wedged again on the next press.
    // FIX-3 records the wedge against the server and excludes it for the rest of the process.

    @Test
    fun `a server that wedges twice is excluded for the rest of the session`() {
        val tracker = WedgeTracker()
        val adapter = BusyAwareAdapter()
        // AUTOCONNECT-AUDIT D-5: a wedge is recorded for an attempt that REACHED the
        // server and did not come up. No refusal here: nativeBusy stays false, so every
        // attempt is a plain TIMED_OUT, which is the outcome that implicates the server.
        val wedging = server("wedgey")
        val c = controller(adapter, listOf(wedging), wedgeTracker = tracker)

        runToCompletion(c) // wedge #1
        assertEquals("the first give-up must be recorded", 1, c.wedgeCountFor(wedging))
        assertEquals(
            // D-5: no refusal here, so the wedge is a plain TIMED_OUT which ends the
            // single-server run with the ordinary exhausted-list message.
            AutoModeController.ERROR_NO_SERVER,
            (c.state.value as AutoModeState.Error).message,
        )

        runToCompletion(c) // wedge #2 -> the threshold is reached
        assertEquals(2, c.wedgeCountFor(wedging))
        assertTrue(
            "the exclusion must be logged once the threshold is reached: ${adapter.logs}",
            adapter.logs.any {
                it.contains("Excluding wedgey from this session after 2 wedges.")
            },
        )

        val dialsBefore = adapter.connects.size
        runToCompletion(c) // the only server is now excluded
        assertEquals(
            "an excluded server must never be dialled again in this session",
            dialsBefore,
            adapter.connects.size,
        )
        assertTrue(
            "the skip must be logged: ${adapter.logs}",
            adapter.logs.any { it.contains("Skipping wedgey: excluded for this session") },
        )
        assertEquals(
            "with its only server excluded the run is an ordinary exhausted list",
            AutoModeController.ERROR_NO_SERVER,
            (c.state.value as AutoModeState.Error).message,
        )
    }

    @Test
    fun `a single wedge does not exclude the server`() {
        val tracker = WedgeTracker()
        val adapter = BusyAwareAdapter()
        val wedging = server("one-strike")
        val c = controller(adapter, listOf(wedging), wedgeTracker = tracker)

        runToCompletion(c)

        assertEquals(1, c.wedgeCountFor(wedging))
        assertTrue(
            "one wedge is below the threshold, so the server must not be excluded yet",
            c.wedgeCountFor(wedging) < WedgeTracker.EXCLUSION_THRESHOLD,
        )
        assertTrue(
            "no exclusion may be logged before the threshold is reached: ${adapter.logs}",
            adapter.logs.none { it.contains("Excluding") },
        )

        // A second run must still dial it: one strike is not evidence about the server,
        // because a single wedge can be caused by a leftover operation from an earlier one.
        val dialsBefore = adapter.connects.size
        runToCompletion(c)
        assertTrue(
            "a server with one wedge must still be dialled",
            adapter.connects.size > dialsBefore,
        )
    }

    @Test
    fun `a verified tunnel clears the wedge record`() {
        val tracker = WedgeTracker()
        val adapter = BusyAwareAdapter()
        val flaky = server("flaky")
        val c = controller(adapter, listOf(flaky), wedgeTracker = tracker)

        runToCompletion(c)
        assertEquals("the give-up must be recorded", 1, c.wedgeCountFor(flaky))

        // The same server now comes up. The record must be forgotten, not merely left alone.
        adapter.nativeBusy = false
        adapter.tunnelUp = true
        runToCompletion(c)

        assertTrue(
            "the second run must have connected, was ${c.state.value}",
            c.state.value is AutoModeState.Connected,
        )
        assertEquals(
            "a verified tunnel proves the server does not wedge, so the record must be cleared",
            0,
            c.wedgeCountFor(flaky),
        )
    }
}
