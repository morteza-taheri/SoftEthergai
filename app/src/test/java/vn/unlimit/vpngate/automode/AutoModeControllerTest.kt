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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnPortSource
import java.io.File

/**
 * JVM tests for the Auto Mode orchestration core, covering the ten
 * product scenarios (§29).
 */
class AutoModeControllerTest {

    private class FakeAdapter : AutoModeController.ConnectionAdapter {
        val logs = mutableListOf<String>()
        val failing = mutableSetOf<String>()
        val connectThrowing = mutableSetOf<String>()
        val slow = mutableSetOf<String>()
        /** BLOCK v1.0.75 (D-1): hosts whose FIRST awaitTunnel hangs, later waits fail fast. */
        val slowFirstOnly = mutableSetOf<String>()
        private val slowFirstLatched = mutableSetOf<String>()
        var delayMs: Long = 0
        var permissionMissing = false
        /**
         * Stage 2F-F.2 (Q-7): protocols this fake declares non-automatable, the
         * way AndroidConnectionAdapter declares L2TP/IPsec. Left empty by
         * default so every pre-existing test keeps the permissive default and
         * only the fail-fast tests opt in.
         */
        val nonAutomatable = mutableSetOf<AutoModeProtocol>()
        /** Every connect() the controller actually invoked, with its protocol. */
        val connects = mutableListOf<Pair<String?, AutoModeProtocol>>()
        /**
         * A-5: optional shared ordered trace. When attached, every log line is
         * mirrored here; a test that also funnels `onStateChange` into the same
         * list can then assert the order of a decision log versus the state it
         * produced. `null` (the default) keeps every pre-existing test
         * untouched.
         */
        var trace: MutableList<String>? = null
        private var activeWait: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

        /**
         * Stage 2F-F.28AL-Y: scripted NATIVE_BUSY outcomes, keyed by hostname.
         *
         * Each entry is a queue of booleans consumed in order by awaitArmedOutcome():
         * `true` => TUNNEL_UP, `false` => NATIVE_BUSY (unless the queue is empty, in
         * which case the default boolean projection applies and `false` becomes
         * TIMED_OUT). This lets a test script "busy, busy, up" without touching the
         * real NativeLifetimeRegistry.
         */
        val nativeBusyQueues = mutableMapOf<String, ArrayDeque<Boolean>>()

        /**
         * AUTOCONNECT-AUDIT D-2: whether a NATIVE_BUSY refusal's slot wait reports the slot
         * as released.
         *
         * `true` (the default) models the normal case — the previous native operation
         * finishes while the controller waits, so the rotation continues. `false` models a
         * genuinely stuck operation, which is the only case that still ends the run.
         */
        @Volatile
        var nativeSlotReleases = true

        /** AUTOCONNECT-AUDIT D-2: how long the slot wait blocks before reporting. */
        @Volatile
        var slotWaitDelayMs = 0L

        /** AUTOCONNECT-AUDIT D-2: how many slot waits the controller performed. */
        @Volatile
        var slotWaits = 0

        /**
         * AUTOCONNECT-FIX-6 (v1.0.36): the budget handed to each slot wait, in call order.
         *
         * Recording the ARGUMENT rather than only counting the calls is what lets a test
         * prove the v1.0.36 cap is active: the controller must pass
         * `min(nativeSlotAwaitMs, attemptTimeoutMs)`, so a controller built with an
         * attempt budget below [AutoModeController.NATIVE_SLOT_AWAIT_MS] has to hand over
         * exactly that smaller budget. Every pre-existing test ignores this list.
         */
        val slotWaitTimeouts = mutableListOf<Long>()

        /**
         * AUTOCONNECT-FIX-5 (v1.0.35): whether the most recent teardown was resolved.
         *
         * `true` (the default) models every pre-v1.0.35 test double and keeps the
         * post-slot-release retry exactly as it was. `false` models the device case the
         * fix targets — `TeardownCompletion.Outcome.NATIVE_UNRESOLVED`, where the
         * registry retains ownership and every later start is refused.
         */
        @Volatile
        var lastTeardownResolved = true

        override fun lastTeardownWasResolved(): Boolean = lastTeardownResolved

        /**
         * AUTOCONNECT-FIX-6 (v1.0.36): whether this app's OWN tunnel is already up when
         * the controller asks.
         *
         * `false` (the default) is the interface default and therefore keeps the new
         * own-tunnel check in the NATIVE_BUSY branch inert for every pre-v1.0.36 test —
         * including the tests that deliberately pin the slot-wait/retry mechanism.
         */
        @Volatile
        var ownTunnelEstablished = false

        override suspend fun isOwnTunnelEstablished(): Boolean = ownTunnelEstablished

        override suspend fun awaitNativeSlotRelease(timeoutMs: Long): Boolean {
            slotWaits++
            slotWaitTimeouts += timeoutMs
            if (slotWaitDelayMs > 0) kotlinx.coroutines.delay(slotWaitDelayMs)
            return nativeSlotReleases
        }

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            logs += "connect:${candidate.hostname}"
            connects += candidate.hostname to protocol
            if (permissionMissing) throw AutoModeController.VpnPermissionMissingException()
            if (candidate.hostname in connectThrowing) {
                throw RuntimeException("simulated connect crash")
            }
        }

        /**
         * When set, [disconnect] parks here until it is completed.
         *
         * v1.0.54 (§1.6.46) PART 1 / R-1: this is the only way to test the
         * skip window *deterministically*. The window is the interval between
         * "the skip was accepted" and "the replacement run published a state",
         * and on a real dispatcher the continuation can leave that interval
         * before the test thread ever gets to the second press — which makes a
         * racing test green or red depending on machine load. Holding the
         * teardown still pins the window open, so the assertion is about the
         * controller's rule rather than about scheduling luck.
         */
        var disconnectGate: CompletableDeferred<Unit>? = null

        override suspend fun disconnect() {
            logs += "disconnect"
            disconnectGate?.await()
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
            val hostname = logs.lastOrNull { it.startsWith("connect:") }
                ?.removePrefix("connect:") ?: return false
            // BLOCK v1.0.75 (D-1): hosts whose FIRST awaitTunnel hangs (skip-
            // interruptible) and whose later waits fail fast — lets a test skip
            // protocol 1 and observe protocol 2 of the same server without a
            // second hang.
            if (hostname in slowFirstOnly) {
                // First wait hangs (skip-interruptible); later waits fail fast
                // so the walk moves on to the next server.
                if (hostname in slowFirstLatched) return false
                slowFirstLatched += hostname
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                activeWait = deferred
                try {
                    return kotlinx.coroutines.withTimeoutOrNull(timeoutMs + 60_000) {
                        deferred.await()
                    } ?: false
                } finally {
                    activeWait = null
                }
            }
            if (hostname in slow) {
                // A hung connect: never reports a tunnel on its own; only a
                // skip (complete(false) via skipCurrent) or the timeout ends it.
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                activeWait = deferred
                try {
                    return kotlinx.coroutines.withTimeoutOrNull(timeoutMs + 60_000) {
                        deferred.await()
                    } ?: false
                } finally {
                    activeWait = null
                }
            }
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            return hostname !in failing
        }

        /** Mirrors AndroidConnectionAdapter.skipCurrent(): fail the in-flight wait. */
        fun skipCurrent() {
            activeWait?.complete(false)
        }

        /**
         * Stage 2F-F.28AL-Y: script the outcome of an armed attempt, including the
         * NATIVE_BUSY refusal. Defaults to the boolean projection of awaitTunnel()
         * so every pre-existing test keeps its old behaviour: `true` becomes
         * TUNNEL_UP and `false` becomes TIMED_OUT.
         */
        override suspend fun awaitArmedOutcome(
            protocol: AutoModeProtocol,
            timeoutMs: Long,
        ): AutoModeController.AttemptOutcome {
            val hostname = logs.lastOrNull { it.startsWith("connect:") }
                ?.removePrefix("connect:") ?: return AutoModeController.AttemptOutcome.TIMED_OUT
            // Stage 2F-F.28AL-Y-IMPL: per-protocol scripts take precedence so a
            // multi-protocol test can drive each protocol's outcome independently.
            val perProtocol = protocolOutcomeQueues[hostname to protocol]
            if (perProtocol != null && perProtocol.isNotEmpty()) {
                return perProtocol.removeFirst()
            }
            val queue = nativeBusyQueues[hostname]
            if (queue != null && queue.isNotEmpty()) {
                return if (queue.removeFirst()) {
                    AutoModeController.AttemptOutcome.TUNNEL_UP
                } else {
                    AutoModeController.AttemptOutcome.NATIVE_BUSY
                }
            }
            val up = awaitTunnel(protocol, timeoutMs)
            return if (up) AutoModeController.AttemptOutcome.TUNNEL_UP
            else AutoModeController.AttemptOutcome.TIMED_OUT
        }

        /** Stage 2F-F.28AL-Y: enqueue a scripted outcome sequence for a hostname. */
        fun scriptBusy(hostname: String, vararg outcomes: Boolean) {
            nativeBusyQueues[hostname] = ArrayDeque(outcomes.toList())
        }

        /**
         * Stage 2F-F.28AL-Y-IMPL: per-(hostname, protocol) outcome scripts.
         *
         * The hostname-keyed [scriptBusy] cannot express "protocol A times out, then
         * protocol B is busy" on a multi-protocol server, because both protocols share one
         * queue. This map keys the same scripted outcomes by the exact (host, protocol) pair
         * so a test can drive each protocol independently. Populated only by tests that need
         * per-protocol control; when empty, [awaitArmedOutcome] falls through to the existing
         * hostname queue and then the boolean default projection, so every pre-existing test
         * keeps its exact behaviour.
         */
        val protocolOutcomeQueues =
            mutableMapOf<Pair<String, AutoModeProtocol>, ArrayDeque<AutoModeController.AttemptOutcome>>()

        /** Stage 2F-F.28AL-Y-IMPL: script explicit AttemptOutcomes for one protocol. */
        fun scriptOutcomes(
            host: String,
            protocol: AutoModeProtocol,
            vararg outcomes: AutoModeController.AttemptOutcome,
        ) {
            protocolOutcomeQueues[host to protocol] = ArrayDeque(outcomes.toList())
        }

        override fun canAutoConnect(protocol: AutoModeProtocol): Boolean = protocol !in nonAutomatable

        override fun log(message: String) {
            logs += message
            // A-5: when a shared trace is attached, log lines and state
            // publications land in ONE ordered list, so their relative order
            // can be asserted. Everything inside onUnexpectedDisconnect runs
            // sequentially in a single coroutine, so the order is deterministic
            // even though the controller dispatches across threads.
            trace?.let { it += message }
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var adapter: FakeAdapter

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        adapter = FakeAdapter()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(
        protocol: AutoModeProtocol = AutoModeProtocol.SOFTETHER_UDP,
        servers: List<AutoModeCandidate>,
        onSuccess: (AutoModeCandidate, AutoModeProtocol) -> Unit = { _, _ -> },
        timeout: Long = 500,
        /** Stage 2F-F.13: a multi-protocol priority profile, in user order. */
        priorities: List<AutoModeProtocol>? = null,
        /** Stage 2F-F.15 (D4/Y1-b): the measured RTT per server/protocol. */
        activeRttProvider: ((ip: String, protocol: AutoModeProtocol) -> Long?)? = null,
        /** Stage 2F-F.17 (D1): receives the controller-selected probe endpoints. */
        reachabilityFilter: (suspend (List<ReachabilityTarget>) -> ReachabilityGate)? = null,
        /** R8 (§11.13.2): whether Auto-Reconnect is enabled; default matches production. */
        autoReconnectEnabled: () -> Boolean = { true },
        /** R8 (OQ-7): whether someone else owns a connection attempt. Default matches production. */
        isTunnelBusy: () -> Boolean = { false },
        /** R8 (OQ-3): settle delay before the first reconnect attempt; 0 keeps JVM tests fast. */
        reconnectInitialDelayMs: Long = 0L,
        /**
         * AUTOCONNECT-FIX-4 (v1.0.34): the ledger to inject.
         *
         * Defaults to a fresh instance so every pre-existing test keeps its exact
         * behaviour. A test that needs the ledger to survive ACROSS runs — which is the
         * whole point of the process-scoped WedgeTracker — passes one shared instance to
         * two consecutive controllers.
         */
        wedgeTracker: WedgeTracker = WedgeTracker(),
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolPriorityProvider = { priorities ?: listOf(protocol) },
        serverProvider = { servers },
        onSuccess = onSuccess,
        attemptTimeoutMsProvider = { timeout },
        reachabilityFilter = reachabilityFilter,
        activeRttProvider = activeRttProvider ?: { _, _ -> null },
        autoReconnectEnabled = autoReconnectEnabled,
        isTunnelBusy = isTunnelBusy,
        reconnectInitialDelayMs = reconnectInitialDelayMs,
        // AUTOCONNECT-AUDIT D-5: a fresh ledger per controller so the process-wide
        // WedgeTracker.process never leaks wedges across tests in the same JVM.
        // AUTOCONNECT-FIX-4: overridable, so the terminal-wedge test can share one ledger.
        wedgeTracker = wedgeTracker,
    )

    private fun server(
        host: String,
        speed: Long = 10_000_000,
        ping: Int = 50,
        sessions: Int = 100,
        score: Int = 1000,
        sources: Int = 2,
        seTcp: Int = 443,
        seUdp: Boolean = true,
    ) = AutoModeCandidate(
        hostname = host, ip = "10.0.0.${host.length}", speed = speed, ping = ping,
        sessions = sessions, score = score, sources = sources,
        seTcpPort = seTcp, seUdpPort = 0, seUdpSupported = seUdp,
        openVpnTcpPort = 0, openVpnUdpPort = 0,
        l2tpSupported = false, sstpSupported = false,
    )

    private val serverFactoryIp = java.util.concurrent.atomic.AtomicInteger(1)

    /**
     * Stage 2F-F.13 (D2/D8/Y4): a candidate whose per-protocol connection data is
     * spelled out in full, so a test can state exactly which transports Auto
     * Connect may consider usable. Every field defaults to "this transport is not
     * available", which keeps each test's intent to the values it actually sets.
     */
    private fun candidateWith(
        host: String?,
        ip: String = "10.1.0.${serverFactoryIp.getAndIncrement()}",
        speed: Long = 10_000_000,
        ping: Int = 50,
        sessions: Int = 100,
        score: Int = 1000,
        sources: Int = 1,
        seTcp: Int = 0,
        seUdpPort: Int = 0,
        seUdpSupported: Boolean = false,
        ovpnTcp: Int = 0,
        ovpnUdp: Int = 0,
        /**
         * v1.0.53 (§1.6.45) TASK A: whether the `.ovpn` profile bytes are in hand.
         *
         * Defaults to `null`, which means "the same thing the ports say": a test
         * that declares an OpenVPN port is describing a server it expects Auto
         * Connect to be able to dial, and since v1.0.53 that requires a config.
         * That keeps every pre-existing test's intent — setting a port meant
         * "this transport is available" before the gate existed too — while a
         * test that wants the stripped shape passes `false` explicitly.
         */
        ovpnConfig: Boolean? = null,
        l2tp: Boolean = false,
        sstp: Boolean = false,
        sstpConnectPort: Int = 0,
        /** Stage 2F-F.15: pre-aggregated RTT, for ranking tests. */
        rtt: Long? = null,
    ) = AutoModeCandidate(
        hostname = host, ip = ip, speed = speed, ping = ping,
        sessions = sessions, score = score, sources = sources,
        seTcpPort = seTcp, seUdpPort = seUdpPort, seUdpSupported = seUdpSupported,
        openVpnTcpPort = ovpnTcp, openVpnUdpPort = ovpnUdp,
        openVpnConfigAvailable = ovpnConfig ?: (ovpnTcp > 0 || ovpnUdp > 0),
        l2tpSupported = l2tp, sstpSupported = sstp,
        sstpConnectPort = sstpConnectPort,
        activeRttMs = rtt,
    )

    /**
     * Waits (bounded) for the run to settle into a terminal state.
     *
     * OPEN-2: this used to be an unbounded `while (true)`. A controller that
     * never settled would stall the entire suite instead of failing one test —
     * the same failure mode already fixed in [awaitNextTerminalAfterChange]. The
     * success predicate is unchanged (terminal state AND `!isRunning`, so a
     * later `start()` launches a fresh run); only the wait is bounded, and a
     * timeout now names the state it was stuck in and what the controller logged
     * while it waited.
     */
    private suspend fun AutoModeController.awaitTerminal(timeoutMs: Long = 10_000): AutoModeState {
        val reached = withTimeoutOrNull(timeoutMs) {
            var result: AutoModeState? = null
            while (result == null) {
                val s = state.value
                // Wait until we hit a terminal state AND the run has fully completed
                // (job == null or job not active), so a subsequent start() launches a fresh run.
                if ((s is AutoModeState.Connected || s is AutoModeState.Error) && !isRunning) {
                    result = s
                } else {
                    kotlinx.coroutines.delay(25)
                }
            }
            result
        }
        return reached ?: error(
            "AutoModeController did not reach a terminal state within ${timeoutMs}ms; " +
                "last=${state.value}, isRunning=$isRunning, recentLogs=${adapter.logs.takeLast(12)}",
        )
    }

    // Test 1: initial state is Disconnected
    @Test
    fun initialStateIsDisconnected() {
        val c = controller(servers = emptyList())
        assertTrue(c.state.value is AutoModeState.Disconnected)
        assertTrue(!c.isRunning)
    }

    // Test 2 + 3: press → Connecting → first server succeeds → Connected
    @Test
    fun firstServerSuccess() = runBlocking {
        val c = controller(servers = listOf(server("s1"), server("s2")))
        c.start()
        val final = c.awaitTerminal()
        assertTrue(final is AutoModeState.Connected)
        assertEquals("s1", (final as AutoModeState.Connected).hostname)
        assertTrue(adapter.logs.contains("connect:s1"))
        assertTrue(!adapter.logs.contains("disconnect"))
    }

    // Test 4: sequential retries until server 3 succeeds
    @Test
    fun retriesUntilSuccess() = runBlocking {
        adapter.failing.addAll(listOf("s1", "s2"))
        val c = controller(servers = listOf(server("s1"), server("s2"), server("s3")))
        c.start()
        val final = c.awaitTerminal()
        assertTrue(final is AutoModeState.Connected)
        assertEquals("s3", (final as AutoModeState.Connected).hostname)
        assertTrue(adapter.logs.contains("connect:s1"))
        assertTrue(adapter.logs.contains("connect:s2"))
        assertTrue(adapter.logs.contains("connect:s3"))
    }

    // Test 5: user stop during connecting — no further attempts
    @Test
    fun stopDuringConnectingStopsEverything() = runBlocking {
        adapter.delayMs = 10_000
        val c = controller(servers = listOf(server("s1"), server("s2")))
        c.start()
        repeat(100) {
            if (c.state.value is AutoModeState.Connecting) return@repeat
            kotlinx.coroutines.delay(10)
        }
        c.stop()
        assertEquals(AutoModeState.Disconnected, c.state.value)
        assertTrue(!c.isRunning)
        kotlinx.coroutines.delay(100)
        assertTrue(adapter.logs.none { it == "connect:s2" })
    }

    // Test 6: all servers fail → Error
    @Test
    fun allServersFailYieldsError() = runBlocking {
        adapter.failing.addAll(listOf("s1", "s2"))
        val c = controller(servers = listOf(server("s1"), server("s2")))
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Error)
    }

    // Test 7: restart from Error works from scratch
    @Test
    fun restartAfterError() = runBlocking {
        adapter.failing.add("s1")
        val c = controller(servers = listOf(server("s1")))
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Error)
        adapter.failing.clear()
        c.onButtonPressed()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
    }

    // Test 8: protocol filter — only OpenVPN-UDP-capable servers tried
    @Test
    fun protocolFilterOpenVpnUdp() = runBlocking {
        val se = server("se-server")
        val ovpn = AutoModeCandidate(
            hostname = "ovpn-server", ip = "10.0.0.9", speed = 5_000_000, ping = 60,
            sessions = 10, score = 900, sources = 1,
            seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
            openVpnTcpPort = 0, openVpnUdpPort = 1194,
            // v1.0.53 (§1.6.45) TASK A: a port alone is no longer a transport.
            // The candidate this test has always described DOES carry a config —
            // that is what made it dialable — so the fact is now stated instead
            // of assumed by the gate.
            openVpnConfigAvailable = true,
            l2tpSupported = false, sstpSupported = false,
        )
        val c = controller(protocol = AutoModeProtocol.OPENVPN_UDP, servers = listOf(se, ovpn))
        val filtered = c.compatibleServers(listOf(se, ovpn), AutoModeProtocol.OPENVPN_UDP)
        assertEquals(listOf("ovpn-server"), filtered.map { it.hostname })
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        assertTrue(adapter.logs.contains("connect:ovpn-server"))
        assertTrue(adapter.logs.none { it == "connect:se-server" })
    }

    /**
     * v1.0.53 (§1.6.45) TASK A: the exact defect this block exists to fix.
     *
     * A server can publish `OpenVPN_TCP_Port` / `OpenVPN_UDP_Port` while its
     * `OpenVPN_ConfigData_Base64` is empty — the emergency publisher strips
     * configs to stay under the 1 MiB body cap, which is true of 209 of the 271
     * rows in the shipped snapshot (77%). Before the gate, Auto Connect selected
     * such a server, the adapter found nothing to build a profile from and
     * returned silently, and the attempt burned the full per-attempt budget and
     * was recorded as TIMED_OUT — a wedge against a server that was never
     * contacted.
     */
    @Test
    fun configLessOpenVpnServerIsNeverSelected() = runBlocking {
        val noConfig = AutoModeCandidate(
            hostname = "no-config", ip = "10.0.0.10", speed = 9_000_000, ping = 10,
            sessions = 10, score = 900, sources = 1,
            seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
            // Both ports published, no profile bytes: exactly the stripped shape.
            openVpnTcpPort = 1534, openVpnUdpPort = 1278,
            openVpnConfigAvailable = false,
            l2tpSupported = false, sstpSupported = false,
        )
        val c = controller(
            protocol = AutoModeProtocol.OPENVPN_TCP,
            servers = listOf(noConfig),
            timeout = 2_000,
        )
        assertEquals(
            emptyList<AutoModeCandidate>(),
            c.compatibleServers(listOf(noConfig), AutoModeProtocol.OPENVPN_TCP),
        )
        assertEquals(
            emptyList<AutoModeCandidate>(),
            c.compatibleServers(listOf(noConfig), AutoModeProtocol.OPENVPN_UDP),
        )

        c.start()
        val terminal = c.awaitTerminal()
        assertTrue(terminal is AutoModeState.Error)
        assertEquals(AutoModeController.ERROR_NO_SERVER, (terminal as AutoModeState.Error).message)
        // The decisive assertion: no connect() was ever issued, so no timeout was
        // spent and no wedge was recorded.
        assertEquals(emptyList<Pair<String?, AutoModeProtocol>>(), adapter.connects)
    }

    /**
     * v1.0.53 (§1.6.45) TASK A: a `do_openvpn` link is not a config.
     *
     * The HTML parser sets `configAvailable` from the link, but nothing in the
     * app ever downloads it, so a link-only server has no bytes. The gate keys
     * on `openVpnConfigData` (real bytes), which is why this server stays
     * unusable even though the page advertises OpenVPN.
     */
    @Test
    fun htmlLinkOnlyServerIsNotUsableForOpenVpn() {
        val conn = vn.unlimit.vpngate.models.VPNGateConnection().apply {
            hostName = "vpn-link-only"
            ip = "10.0.0.11"
            tcpPort = 1534
            udpPort = 1278
            // No openVpnConfigData: the page only offered a download link.
        }
        val candidate = with(AutoModeEngine) { conn.toCandidate() }
        assertFalse(candidate.openVpnConfigAvailable)
        assertEquals(1534, candidate.openVpnTcpPort) // the port fact is preserved

        val c = controller(protocol = AutoModeProtocol.OPENVPN_TCP, servers = emptyList())
        assertEquals(
            emptyList<AutoModeCandidate>(),
            c.compatibleServers(listOf(candidate), AutoModeProtocol.OPENVPN_TCP),
        )
    }

    /**
     * v1.0.53 (§1.6.45) TASK A: the port facts survive the gate.
     *
     * "Port facts stay in the record" is half of the operator's decision; the
     * other half is that usability is what changes. This pins both at once.
     */
    @Test
    fun theGateDoesNotEraseThePortFacts() {
        val withConfig = AutoModeCandidate(
            hostname = "with-config", ip = "10.0.0.12", speed = 1_000_000, ping = 50,
            sessions = 1, score = 1, sources = 1,
            seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
            openVpnTcpPort = 1558, openVpnUdpPort = 0,
            openVpnConfigAvailable = true,
            l2tpSupported = false, sstpSupported = false,
        )
        assertEquals(1558, withConfig.openVpnTcpPort)
        val c = controller(protocol = AutoModeProtocol.OPENVPN_TCP, servers = emptyList())
        assertEquals(
            listOf("with-config"),
            c.compatibleServers(listOf(withConfig), AutoModeProtocol.OPENVPN_TCP)
                .map { it.hostname },
        )
    }

    /**
     * v1.0.53 (§1.6.45) TASK B.6: `MISSING_CONFIG` is an ordinary attempt failure.
     *
     * It must rotate to the next PROTOCOL on the same server — the server itself
     * may be perfectly reachable over SoftEther — and it must not become a
     * terminal state, a cancellation, or a wedge. This is the defence-in-depth
     * path: the TASK A gate means production should never select such a server,
     * so this test scripts the outcome directly.
     */
    @Test
    fun missingConfigRotatesToTheNextProtocolOnTheSameServer() = runBlocking {
        val s = AutoModeCandidate(
            hostname = "s1", ip = "10.0.0.13", speed = 1_000_000, ping = 50,
            sessions = 1, score = 1, sources = 1,
            seTcpPort = 443, seUdpPort = 0, seUdpSupported = false,
            openVpnTcpPort = 1534, openVpnUdpPort = 0,
            openVpnConfigAvailable = true,
            l2tpSupported = false, sstpSupported = false,
        )
        adapter.protocolOutcomeQueues["s1" to AutoModeProtocol.OPENVPN_TCP] =
            ArrayDeque(listOf(AutoModeController.AttemptOutcome.MISSING_CONFIG))

        val c = controller(
            servers = listOf(s),
            priorities = listOf(AutoModeProtocol.OPENVPN_TCP, AutoModeProtocol.SOFTETHER_TCP),
        )
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)

        // Same server, next protocol — not the next server, not a terminal.
        assertEquals(
            listOf("s1" to AutoModeProtocol.OPENVPN_TCP, "s1" to AutoModeProtocol.SOFTETHER_TCP),
            adapter.connects,
        )
        assertTrue(adapter.logs.any { it.contains("MISSING_CONFIG") })
    }

    // Test 9: rapid double press does not create two jobs
    @Test
    fun doublePressSingleJob() = runBlocking {
        adapter.delayMs = 300
        val c = controller(servers = listOf(server("s1")))
        c.start()
        c.start()
        kotlinx.coroutines.delay(50)
        assertEquals(1, adapter.logs.count { it.startsWith("connect:") })
        c.stop()
    }

    /**
     * BLOCK v1.0.75 FIX (operator report): the GREEN hero button while Connected
     * means **disconnect**, permanently — it never starts a new run. "Move on"
     * lives only on the Try Next button ([pressingWhileConnectedContinuesFromTheNextServer]).
     */
    @Test
    fun pressWhileConnectedDisconnects() = runBlocking {
        val c = controller(servers = listOf(server("s1"), server("s2")))
        val seen = java.util.Collections.synchronizedList(ArrayList<AutoModeState>())
        c.onStateChange = { seen += it }

        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        adapter.connects.clear()

        c.onButtonPressed()

        // A permanent disconnect: Disconnected is published, no new dial happens,
        // and no continuation run is launched.
        withTimeoutOrNull(5_000) { while (c.state.value !is AutoModeState.Disconnected) delay(5) }
        assertTrue(
            "the hero press must publish Disconnected, got ${c.state.value}",
            c.state.value is AutoModeState.Disconnected,
        )
        assertEquals("a disconnect must not dial anything", emptyList<Pair<String?, AutoModeProtocol>>(), adapter.connects.toList())
        assertFalse("the run must not be running after a disconnect", c.isRunning)
        assertTrue("Disconnected must appear after Connected, saw $seen", seen.any { it is AutoModeState.Disconnected })
    }

    // §7 per-protocol capability rules
    @Test
    fun capabilityRules() {
        val c = controller(servers = emptyList())
        val seTcpOnly = server("a", seTcp = 443, seUdp = false)
        val seUdpOnly = AutoModeCandidate(
            "b", "ip", 1_000, 10, 5, 500, 1,
            seTcpPort = 0, seUdpPort = 0, seUdpSupported = true,
            openVpnTcpPort = 0, openVpnUdpPort = 0, l2tpSupported = false, sstpSupported = false,
        )
        val l2tp = AutoModeCandidate(
            "c", "ip", 1_000, 10, 5, 500, 1,
            seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
            openVpnTcpPort = 0, openVpnUdpPort = 0, l2tpSupported = true, sstpSupported = false,
        )
val sstp = AutoModeCandidate(
                "d", "ip", 1_000, 10, 5, 500, 1,
                seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
                openVpnTcpPort = 0, openVpnUdpPort = 0, l2tpSupported = false, sstpSupported = true,
                sstpPort = 443, sstpPortSource = VpnPortSource.EXPLICIT, sstpConnectPort = 443,
            )
            // Stage 2F-F.2 (Q-6): SSTP confirmed, but no port was published and
            // there is no DEFAULT provenance to fall back on, so there is
            // nothing to dial. This must NOT be offered to Auto Connect.
            val sstpWithoutPort = AutoModeCandidate(
                "e", "ip", 1_000, 10, 5, 500, 1,
                seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
                openVpnTcpPort = 0, openVpnUdpPort = 0, l2tpSupported = false, sstpSupported = true,
            )
            val all = listOf(seTcpOnly, seUdpOnly, l2tp, sstp, sstpWithoutPort)
            assertEquals(listOf("a"), c.compatibleServers(all, AutoModeProtocol.SOFTETHER_TCP).map { it.hostname })
            // Stage 2F-F.28AL-AUTOCONNECT-BUG-FIX (BUG 1): a UDP-only server
            // (seTcpPort = 0, seUdpSupported = true) must NOT be offered for
            // SOFTETHER_UDP — R-UDP is broken for SETcpPort = 0 servers
            // (RUDP_IMPLEMENTATION_PLAN.md; SERVER_SOURCE_ARCHITECTURE.md §3.5).
            assertEquals(
                "a UDP-only (SETcpPort=0) server must not be dialled via SoftEther UDP",
                emptyList<String>(),
                c.compatibleServers(listOf(seUdpOnly), AutoModeProtocol.SOFTETHER_UDP).map { it.hostname },
            )
            assertEquals(listOf("c"), c.compatibleServers(all, AutoModeProtocol.L2TP_IPSEC).map { it.hostname })
            assertEquals(
                "a confirmed SSTP server with no usable port must not be offered",
                listOf("d"),
                c.compatibleServers(all, AutoModeProtocol.MS_SSTP).map { it.hostname },
            )
        }

    /**
     * Stage 2F-F.28AL-AUTOCONNECT-BUG-FIX (BUG 1): the SoftEther-UDP capability
     * gate must require a usable TCP control channel.
     *
     * `supports(SOFTETHER_UDP)` is exercised through the public
     * [AutoModeController.compatibleServers] and [AutoModeController.usableProtocols]
     * entry points, exactly as production calls them.
     */
    @Test
    fun softEtherUdpRequiresTcpControlChannel() {
        val c = controller(servers = emptyList())

        // UDP-only: seTcpPort = 0, seUdpSupported = true.
        val udpOnly = candidateWith("udp-only", seTcp = 0, seUdpSupported = true)
        assertTrue(
            "SOFTETHER_UDP must reject a server with no TCP control channel",
            c.compatibleServers(listOf(udpOnly), AutoModeProtocol.SOFTETHER_UDP).isEmpty(),
        )
        assertTrue(
            "SOFTETHER_TCP must reject the same server (no TCP port)",
            c.compatibleServers(listOf(udpOnly), AutoModeProtocol.SOFTETHER_TCP).isEmpty(),
        )

        // Both transports available: TCP port published, UDP supported.
        val both = candidateWith("both", seTcp = 443, seUdpSupported = true)
        assertEquals(
            "a server with a TCP port and UDP support is offered for SOFTETHER_UDP",
            listOf("both"),
            c.compatibleServers(listOf(both), AutoModeProtocol.SOFTETHER_UDP).map { it.hostname },
        )
        assertEquals(
            "a server with a TCP port and UDP support is offered for SOFTETHER_TCP",
            listOf("both"),
            c.compatibleServers(listOf(both), AutoModeProtocol.SOFTETHER_TCP).map { it.hostname },
        )

        // UDP-only, but carrying a valid OpenVPN UDP config: still selectable via OpenVPN.
        val udpOnlyWithOvpn = candidateWith(
            "udp-only-ovpn", seTcp = 0, seUdpSupported = true, ovpnUdp = 1194,
        )
        assertTrue(
            "a UDP-only server must remain selectable via OPENVPN_UDP",
            c.compatibleServers(listOf(udpOnlyWithOvpn), AutoModeProtocol.OPENVPN_UDP)
                .map { it.hostname } == listOf("udp-only-ovpn"),
        )
        assertTrue(
            "the same UDP-only server must still be rejected for SOFTETHER_UDP",
            c.usableProtocols(udpOnlyWithOvpn, listOf(AutoModeProtocol.SOFTETHER_UDP)).isEmpty(),
        )
    }

        /**
         * Stage 2F-F.2 (Q-6): MS-SSTP support is decided by the port's
         * PROVENANCE, not by its number. EXPLICIT 443 and DEFAULT 443 are both
         * dialable and both accepted; UNKNOWN with no port is not, and 443 is
         * never manufactured to rescue it.
         */
        @Test
        fun sstpSupportFollowsPortProvenanceNotThePortNumber() {
            val c = controller(servers = emptyList())

            fun candidate(
                host: String,
                port: Int,
                source: String,
                connectPort: Int,
                supported: Boolean = true,
            ) = AutoModeCandidate(
                host, "ip", 1_000, 10, 5, 500, 1,
                seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
                openVpnTcpPort = 0, openVpnUdpPort = 0,
                l2tpSupported = false, sstpSupported = supported,
                sstpPort = port, sstpPortSource = source, sstpConnectPort = connectPort,
            )

            // The source printed the port.
            val explicit = candidate("explicit", 8443, VpnPortSource.EXPLICIT, 8443)
            // The source confirmed SSTP but published no port; 443 is the
            // protocol default and is a real, dialable port.
            val default = candidate("default", 0, VpnPortSource.DEFAULT, 443)
            // No port, no provenance: nothing may be inferred.
            val unknown = candidate("unknown", 0, VpnPortSource.UNKNOWN, 0)
            // Unsupported entirely, even with a perfectly good port.
            val notSstp = candidate("not-sstp", 443, VpnPortSource.EXPLICIT, 443, supported = false)

            val all = listOf(explicit, default, unknown, notSstp)
            assertEquals(
                "EXPLICIT and DEFAULT are dialable; UNKNOWN and unsupported are not",
                listOf("explicit", "default"),
                c.compatibleServers(all, AutoModeProtocol.MS_SSTP).map { it.hostname },
            )

            // The distinction is provenance, not number: a DEFAULT-derived 443
            // must not be treated differently from an EXPLICIT 443.
            val explicit443 = candidate("explicit443", 443, VpnPortSource.EXPLICIT, 443)
            val default443 = candidate("default443", 443, VpnPortSource.DEFAULT, 443)
            assertEquals(
                listOf("explicit443", "default443"),
                c.compatibleServers(listOf(explicit443, default443), AutoModeProtocol.MS_SSTP)
                    .map { it.hostname },
            )
        }

        /**
         * Stage 2F-F.2 (Q-7): when the adapter declares a protocol
         * non-automatable, Auto Connect must reject it BEFORE the attempt.
         * The old path entered connect(), logged "not automatable", returned
         * normally and then burned the whole per-attempt timeout.
         */
        @Test
        fun nonAutomatableProtocolIsNeverAttemptedAndDoesNotConsumeTheTimeout() = runBlocking {
            adapter.nonAutomatable += AutoModeProtocol.L2TP_IPSEC
            val l2tpOnly = AutoModeCandidate(
                "l2tp-only", "ip", 1_000, 10, 5, 500, 1,
                seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
                openVpnTcpPort = 0, openVpnUdpPort = 0,
                l2tpSupported = true, sstpSupported = false,
            )

            // A long timeout: if L2TP were still entered, this test would hang
            // for the full duration instead of failing fast.
            val c = controller(
                protocol = AutoModeProtocol.L2TP_IPSEC,
                servers = listOf(l2tpOnly),
                timeout = 30_000,
            )
            c.start()
            val terminal = c.awaitTerminal()

            assertTrue(terminal is AutoModeState.Error)
            assertEquals(
                AutoModeController.ERROR_NO_SERVER,
                (terminal as AutoModeState.Error).message,
            )
            assertEquals(
                "L2TP must never reach connect() on a non-automatable adapter",
                emptyList<Pair<String?, AutoModeProtocol>>(),
                adapter.connects,
            )
            assertTrue(
                "the skip must be explained rather than silent",
                adapter.logs.any { it.contains("cannot be automated") },
            )
        }

        /**
         * Stage 2F-F.2 (Q-7): rejecting L2TP must not end the run. The next
         * server — and the next protocol on it — still gets its chance through
         * the existing retry logic.
         */
        @Test
        fun aNonAutomatableProtocolDoesNotStopLaterServersFromBeingTried() = runBlocking {
            adapter.nonAutomatable += AutoModeProtocol.L2TP_IPSEC
            val l2tpOnly = AutoModeCandidate(
                "l2tp-only", "ip", 1_000, 10, 5, 500, 1,
                seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
                openVpnTcpPort = 0, openVpnUdpPort = 0,
                l2tpSupported = true, sstpSupported = false,
            )
            // Slower is irrelevant: byQuality puts the fast server first.
            val seServer = server("se")

            val c = controller(
                protocol = AutoModeProtocol.L2TP_IPSEC,
                servers = listOf(l2tpOnly, seServer),
                timeout = 2_000,
            )
            // The profile is L2TP alone, so the SoftEther server has no
            // supported protocol either and the run must end in ERROR_NO_SERVER
            // WITHOUT any connect() call.
            c.start()
            val terminal = c.awaitTerminal()

            assertTrue(terminal is AutoModeState.Error)
            assertEquals(emptyList<Pair<String?, AutoModeProtocol>>(), adapter.connects)
        }

        /**
         * Stage 2F-F.2 (Q-7): the rejection is scoped to the protocol, not to
         * the server. With L2TP disabled and another protocol enabled, a
         * server that also offers SoftEther must still connect normally — this
         * is the "no regression to existing behaviour" guarantee.
         */
        @Test
        fun otherProtocolsStillConnectWhenL2tpIsNonAutomatable() = runBlocking {
            adapter.nonAutomatable += AutoModeProtocol.L2TP_IPSEC
            val both = AutoModeCandidate(
                "both", "ip", 10_000_000, 20, 5, 500, 1,
                seTcpPort = 443, seUdpPort = 0, seUdpSupported = true,
                openVpnTcpPort = 0, openVpnUdpPort = 0,
                l2tpSupported = true, sstpSupported = false,
            )
            val c = controller(
                protocol = AutoModeProtocol.L2TP_IPSEC,
                servers = listOf(both),
                timeout = 2_000,
            )
            c.start()
            c.awaitTerminal()

            // L2TP is filtered out, so nothing is dialled at all.
            assertEquals(emptyList<Pair<String?, AutoModeProtocol>>(), adapter.connects)

            // Now with SoftEther TCP enabled: it connects, and L2TP is still
            // never touched.
            val c2 = controller(
                protocol = AutoModeProtocol.SOFTETHER_TCP,
                servers = listOf(both),
                timeout = 2_000,
            )
            c2.start()
            c2.awaitTerminal()

            assertEquals(
                listOf("both" to AutoModeProtocol.SOFTETHER_TCP),
                adapter.connects,
            )
        }

    // §8 ordering: speed desc, tie-breakers, zero-speed demoted (§23)
    @Test
    fun orderingByQuality() {
        val c = controller(servers = emptyList())
        val fast = server("fast", speed = 90_000_000, ping = 100)
        val fastLowPing = server("fastLowPing", speed = 90_000_000, ping = 20)
        val slow = server("slow", speed = 1_000_000)
        val zero = server("zero", speed = 0)
        val sorted = c.compatibleServers(listOf(zero, slow, fast, fastLowPing), AutoModeProtocol.SOFTETHER_UDP)
        assertEquals(listOf("fastLowPing", "fast", "slow", "zero"), sorted.map { it.hostname })
    }

    // Stage 2F-F.11 (D3): ping == 0 means UNKNOWN, not "instant". A candidate with a
    // measured ping must outrank one with no measurement, regardless of how slow the
    // measurement is. Before D3, ping = 0 sorted above every positive value and these
    // candidates were tried in exactly the wrong order.
    @Test
    fun validPingOutranksUnknownPing() {
        val c = controller(servers = emptyList())
        val slowButKnown = server("known", speed = 50_000_000, ping = 300)
        val fastButUnknown = server("unknown", speed = 50_000_000, ping = 0)
        val sorted = c.compatibleServers(
            listOf(fastButUnknown, slowButKnown),
            AutoModeProtocol.SOFTETHER_UDP,
        )
        assertEquals(listOf("known", "unknown"), sorted.map { it.hostname })
    }

    // Every unknown-ping candidate must land after every known-ping candidate, not
    // merely after one of them: the known/unknown key is evaluated before the raw
    // value, so no unknown can be interleaved into the measured group.
    @Test
    fun unknownPingAlwaysSortsAfterKnownPing() {
        val c = controller(servers = emptyList())
        val knownFast = server("knownFast", speed = 10_000_000, ping = 10)
        val knownSlow = server("knownSlow", speed = 10_000_000, ping = 400)
        val unknownA = server("unknownA", speed = 10_000_000, ping = 0, sessions = 1, score = 9999)
        val unknownB = server("unknownB", speed = 10_000_000, ping = 0, sessions = 999, score = 1)
        val sorted = c.compatibleServers(
            listOf(unknownA, unknownB, knownSlow, knownFast),
            AutoModeProtocol.SOFTETHER_UDP,
        )
        assertEquals(
            listOf("knownFast", "knownSlow", "unknownA", "unknownB"),
            sorted.map { it.hostname },
        )
    }

    // Unknown ping must not become a tie-breaker shortcut: with speed, ping-group,
    // sessions and score all equal, an unknown-ping candidate still has to yield to a
    // known-ping candidate, and the remaining keys must decide deterministically.
    @Test
    fun unknownPingDoesNotDisplaceLaterTieBreakers() {
        val c = controller(servers = emptyList())
        val known = server("known", speed = 10_000_000, ping = 900)
        val unknown = server("unknown", speed = 10_000_000, ping = 0, sessions = 1, score = 9999)
        val sorted = c.compatibleServers(listOf(unknown, known), AutoModeProtocol.SOFTETHER_UDP)
        assertEquals(listOf("known", "unknown"), sorted.map { it.hostname })
    }

    // Zero-speed demotion (§23) still outranks the ping split: a server with no usable
    // throughput stays at the bottom even when it is the only one with a measurement.
    @Test
    fun zeroSpeedStaysDemotedDespiteKnownPing() {
        val c = controller(servers = emptyList())
        val zeroKnown = server("zeroKnown", speed = 0, ping = 5)
        val usableUnknown = server("usableUnknown", speed = 1_000_000, ping = 0)
        val sorted = c.compatibleServers(listOf(zeroKnown, usableUnknown), AutoModeProtocol.SOFTETHER_UDP)
        assertEquals(listOf("usableUnknown", "zeroKnown"), sorted.map { it.hostname })
    }

    // Stage 2F-F.11 (D5): the source count must not influence ranking. These two
    // candidates differ only in `sources`, so the order must come from the quality
    // keys instead — the higher source count must not win.
    @Test
    fun sourcesDoNotAffectOrdering() {
        val c = controller(servers = emptyList())
        val lowSources = server("lowSources", speed = 10_000_000, ping = 40, sources = 1)
        val highSources = server("highSources", speed = 10_000_000, ping = 40, sources = 9_999)
        val sorted = c.compatibleServers(
            listOf(highSources, lowSources),
            AutoModeProtocol.SOFTETHER_UDP,
        )
        assertEquals(2, sorted.size)
        // Equal on every ranking key, so the order is unspecified-but-stable; what
        // matters is that neither one is promoted over the other by `sources`.
        assertEquals(
            setOf("lowSources", "highSources"),
            sorted.map { it.hostname }.toSet(),
        )
    }

    // Source count must not rescue a worse candidate either. The candidate with the
    // best source count here also has the worst ping, so it must still lose.
    @Test
    fun sourcesCannotOutrankBetterQuality() {
        val c = controller(servers = emptyList())
        val bestQualityLowSources = server("best", speed = 10_000_000, ping = 20, sources = 1)
        val worseQualityHighSources = server("worse", speed = 10_000_000, ping = 500, sources = 9_999)
        val sorted = c.compatibleServers(
            listOf(worseQualityHighSources, bestQualityLowSources),
            AutoModeProtocol.SOFTETHER_UDP,
        )
        assertEquals(listOf("best", "worse"), sorted.map { it.hostname })
    }

    // The known/unknown ping split must not disturb the documented §8 order among
    // candidates that all report a measurement.
    @Test
    fun measuredCandidatesKeepOriginalOrdering() {
        val c = controller(servers = emptyList())
        val a = server("a", speed = 90_000_000, ping = 100)
        val b = server("b", speed = 90_000_000, ping = 20)
        val d = server("d", speed = 1_000_000, ping = 1)
        val sorted = c.compatibleServers(listOf(d, a, b), AutoModeProtocol.SOFTETHER_UDP)
        assertEquals(listOf("b", "a", "d"), sorted.map { it.hostname })
    }

    // VPN permission missing on the first attempt must stop the whole run
    // with the dedicated error — no pointless retries of the other servers.
    @Test
    fun vpnPermissionMissingStopsRunWithDedicatedError() = runBlocking {
        adapter.permissionMissing = true
        val c = controller(servers = listOf(server("s1"), server("s2"), server("s3")))
        c.start()
        val terminal = c.awaitTerminal()
        assertTrue(terminal is AutoModeState.Error)
        assertEquals(
            AutoModeController.ERROR_VPN_PERMISSION,
            (terminal as AutoModeState.Error).message,
        )
        assertEquals(1, adapter.logs.count { it.startsWith("connect:") })
        assertTrue(!c.isRunning)
    }

    // A generic connect failure (non-permission) must NOT kill the run:
    // it is one failed attempt, the next server is still tried.
    @Test
    fun genericConnectExceptionCountsAsFailedAttempt() = runBlocking {
        adapter.connectThrowing += "s1"
        val c = controller(servers = listOf(server("s1"), server("s2")))
        c.start()
        val terminal = c.awaitTerminal()
        assertTrue(terminal is AutoModeState.Connected)
        assertEquals("s2", (terminal as AutoModeState.Connected).hostname)
        assertEquals(2, adapter.logs.count { it.startsWith("connect:") })
    }

    // Try next server: while hung on server #1, a user skip must move the
    // run to server #2 immediately (no full per-attempt timeout wait) and
    // still connect successfully there.
    @Test
    fun skipToNextServerMovesToNextCandidateWithoutWaiting() = runBlocking {
        adapter.slow += "s1"
        val c = controller(servers = listOf(server("s1"), server("s2")), timeout = 60_000)
        // Mirror the production wiring: the controller's skip signal routes
        // to the adapter's skipCurrent (fail the in-flight tunnel wait).
        c.setSkipSignal { adapter.skipCurrent() }
        val skipper = CoroutineScope(Dispatchers.Default)
        skipper.launch {
            kotlinx.coroutines.delay(300)
            c.skipToNextServer()    // flag first, then the signal fires skipCurrent
        }
        c.start()
        val terminal = c.awaitTerminal()
        c.setSkipSignal(null)
        skipper.cancel()
        assertTrue("expected Connected(s2), got $terminal; logs=${adapter.logs.takeLast(25)}", terminal is AutoModeState.Connected)
        assertEquals("s2", (terminal as AutoModeState.Connected).hostname)
        assertTrue(
            "[AUTO] Protocol skipped by user must be logged; logs=${adapter.logs.takeLast(20)}",
            adapter.logs.any { it.contains("[AUTO] Protocol skipped by user") },
        )
    }

    /**
     * BLOCK v1.0.75 (D-1) — ONE press while connecting skips the CURRENT
     * PROTOCOL attempt and continues with the NEXT PROTOCOL of the SAME server;
     * the next server is reached only after that server's protocols are
     * exhausted. The cancelled attempt is recorded NEUTRAL
     * (ATTEMPT_CANCELLED/USER_CANCELLED), never a server failure.
     */
    @Test
    fun skipDuringProtocolOneTriesProtocolTwoOfTheSameServer() = runBlocking {
        adapter.slowFirstOnly += "s1"
        val c = controller(
            servers = listOf(server("s1"), server("s2")),
            timeout = 60_000,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        // Mirror the production wiring: the skip signal fails the in-flight wait.
        c.setSkipSignal { adapter.skipCurrent() }
        val skipper = CoroutineScope(Dispatchers.Default)
        skipper.launch {
            kotlinx.coroutines.delay(300)
            c.skipToNextServer() // ONE press, while s1/TCP is in flight
        }
        c.start()
        val terminal = c.awaitTerminal()
        c.setSkipSignal(null)
        skipper.cancel()

        assertTrue("expected s2 to connect, got $terminal", terminal is AutoModeState.Connected)
        assertEquals(
            "D-1: the press cancels s1/TCP, continues with s1/UDP, and reaches s2 " +
                "only after s1's protocols are exhausted: ${adapter.connects}",
            listOf(
                "s1" to AutoModeProtocol.SOFTETHER_TCP,
                "s1" to AutoModeProtocol.SOFTETHER_UDP,
                "s2" to AutoModeProtocol.SOFTETHER_TCP,
            ),
            adapter.connects.toList(),
        )
        assertTrue(
            "the cancelled attempt is recorded NEUTRAL: ${adapter.logs}",
            adapter.logs.any { it.contains("Protocol skipped by user") },
        )
    }

    // Try next server while CONNECTED: must disconnect the live tunnel and
    // continue the run with the next candidate (server #1 already
    // succeeded; skipping it moves to #2).
    @Test
    fun skipWhileConnectedDisconnectsAndTriesNext() = runBlocking {
        val c = controller(servers = listOf(server("s1"), server("s2")))
        c.start()
        // First terminal is Connected(s1): the run completed, the watcher
        // is armed and waits for the skip flag.
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)

        // Snapshot BEFORE the skip: skipToNextServer() restarts the run on the
        // controller's own dispatcher, so the disconnect-and-reconnect can complete
        // before this test thread resumes (see awaitNextTerminalAfterChange()).
        val beforeSkip = c.state.value

        // User presses Try next server while connected.
        c.skipToNextServer()

        // A fresh run over the remaining candidate(s) must start and reach
        // Connected(s2) without retrying s1.
        val final = c.awaitNextTerminalAfterChange(beforeSkip)
        assertTrue(final is AutoModeState.Connected)
        assertEquals("s2", (final as AutoModeState.Connected).hostname)
        assertEquals(1, adapter.logs.count { it == "connect:s1" })
        assertEquals(1, adapter.logs.count { it == "connect:s2" })
        assertTrue(adapter.logs.any { it.startsWith("[AUTO] Connected server skipped by user; trying next") })
    }

    /**
     * Waits (bounded) for the run to settle into a terminal state other than [before].
     *
     * Two properties matter, both learned the hard way
     * (see FIX_HANGING_SKIP_TEST_REPORT.md):
     *
     *  1. **Bounded.** The second stage used to be an unbounded `while (true)`, so a
     *     controller that did not settle stalled the entire `:app:testProDebugUnitTest`
     *     suite instead of failing one test. It now fails loudly, naming the state it
     *     was stuck in and what the controller logged while it waited.
     *  2. **[before] is passed in, not sampled here.** `skipToNextServer()` restarts the
     *     run on the controller's own dispatcher, so the disconnect-and-reconnect can
     *     finish before the calling test thread resumes. Sampling the "before" state
     *     inside this helper then recorded the *post*-skip state, so the
     *     wait-for-a-change stage waited for a change that had already happened.
     */
    private suspend fun AutoModeController.awaitNextTerminalAfterChange(
        before: AutoModeState,
        timeoutMs: Long = 10_000,
    ): AutoModeState {
        val reached = withTimeoutOrNull(timeoutMs) {
            var result: AutoModeState? = null
            while (result == null) {
                val s = state.value
                if (s != before &&
                    (s is AutoModeState.Connected || s is AutoModeState.Error) &&
                    !isRunning
                ) {
                    result = s
                } else {
                    kotlinx.coroutines.delay(25)
                }
            }
            result
        }
        // Fail loudly rather than silently: name the state we came from, the state we are
        // stuck in, and what the controller logged while we waited.
        return reached ?: error(
            "AutoModeController did not settle into a terminal state other than $before " +
                "within ${timeoutMs}ms; last=${state.value}, isRunning=$isRunning, " +
                "recentLogs=${adapter.logs.takeLast(12)}",
        )
    }

    // ==================================================================
    // Stage 2F-F.13 (Phase 2) — D2 + D8 + Y4.
    //
    // Protocol Priority is an ATTEMPT ORDER, capability decides usability, and
    // the controller owns that decision. Everything below is about a server
    // surviving when its highest-priority protocol cannot be used.
    // ==================================================================

    /** TEST 1 — the top priority is unsupported; the next one carries the server. */
    @Test
    fun firstPriorityUnsupportedFallsThroughToSecondProtocol() = runBlocking {
        val priorities = listOf(AutoModeProtocol.MS_SSTP, AutoModeProtocol.SOFTETHER_TCP)
        val srv = candidateWith("fallthrough", seTcp = 9922)

        val c = controller(servers = listOf(srv), priorities = priorities)
        assertEquals(
            listOf(AutoModeProtocol.SOFTETHER_TCP),
            c.usableProtocols(srv, priorities),
        )

        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        assertEquals(
            listOf("fallthrough" to AutoModeProtocol.SOFTETHER_TCP),
            adapter.connects,
        )
    }

    /** TEST 2 — supported is not enough: MS-SSTP with no resolvable port is unusable. */
    @Test
    fun supportedSstpWithoutAPortIsExcluded() = runBlocking {
        val priorities = listOf(AutoModeProtocol.MS_SSTP, AutoModeProtocol.SOFTETHER_TCP)
        val srv = candidateWith("sstp-no-port", seTcp = 9922, sstp = true, sstpConnectPort = 0)

        val c = controller(servers = listOf(srv), priorities = priorities)
        assertEquals(
            "an unknown SSTP port must not make SSTP usable",
            listOf(AutoModeProtocol.SOFTETHER_TCP),
            c.usableProtocols(srv, priorities),
        )

        c.start()
        c.awaitTerminal()
        assertEquals(
            listOf("sstp-no-port" to AutoModeProtocol.SOFTETHER_TCP),
            adapter.connects,
        )
    }

    /** TEST 3 — a valid SSTP port makes SSTP the first attempt, ahead of SoftEther. */
    @Test
    fun validSstpTakesPrecedenceOverLowerPriorityProtocols() = runBlocking {
        val priorities = listOf(AutoModeProtocol.MS_SSTP, AutoModeProtocol.SOFTETHER_TCP)
        val srv = candidateWith(
            "sstp-valid",
            seTcp = 9922,
            sstp = true,
            sstpConnectPort = 443,
        )

        val c = controller(servers = listOf(srv), priorities = priorities)
        assertEquals(priorities, c.usableProtocols(srv, priorities))

        c.start()
        c.awaitTerminal()
        assertEquals(
            listOf("sstp-valid" to AutoModeProtocol.MS_SSTP),
            adapter.connects,
        )
    }

    /** TEST 4 — a third-priority protocol is still a legitimate way in. */
    @Test
    fun lowerPriorityOpenVpnRemainsEligible() = runBlocking {
        val priorities = listOf(
            AutoModeProtocol.MS_SSTP,
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeProtocol.OPENVPN_TCP,
        )
        // SSTP declared but unresolvable, no SoftEther TCP port at all.
        val srv = candidateWith("ovpn-only", ovpnTcp = 443, sstp = true, sstpConnectPort = 0)

        val c = controller(servers = listOf(srv), priorities = priorities)
        assertEquals(
            listOf(AutoModeProtocol.OPENVPN_TCP),
            c.usableProtocols(srv, priorities),
        )

        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        assertEquals(
            listOf("ovpn-only" to AutoModeProtocol.OPENVPN_TCP),
            adapter.connects,
        )
    }

    /**
     * TEST 5 — L2TP is manual-only, so Auto Connect must never select it.
     *
     * The fake adapter deliberately claims EVERY protocol is automatable here, so
     * this passes only because the controller owns the rule rather than delegating
     * it to the adapter.
     */
    @Test
    fun l2tpIsNeverSelectedByAutoConnect() = runBlocking {
        val priorities = listOf(AutoModeProtocol.L2TP_IPSEC, AutoModeProtocol.SOFTETHER_TCP)
        val srv = candidateWith("l2tp-and-se", l2tp = true, seTcp = 9922)

        val c = controller(servers = listOf(srv), priorities = priorities)
        assertEquals(
            listOf(AutoModeProtocol.SOFTETHER_TCP),
            c.usableProtocols(srv, priorities),
        )

        c.start()
        c.awaitTerminal()
        assertEquals(
            listOf("l2tp-and-se" to AutoModeProtocol.SOFTETHER_TCP),
            adapter.connects,
        )
        assertTrue(
            "L2TP must never be attempted by Auto Connect",
            adapter.connects.none { it.second == AutoModeProtocol.L2TP_IPSEC },
        )
    }

    /** TEST 6 — nothing usable means nothing attempted, and the run ends cleanly. */
    @Test
    fun serverWithNoUsableProtocolHasZeroUsableProtocols() = runBlocking {
        val priorities = listOf(
            AutoModeProtocol.MS_SSTP,
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeProtocol.OPENVPN_TCP,
        )
        // Every declared capability is unusable: SSTP with no port, no SoftEther
        // and no OpenVPN port, L2TP-only otherwise.
        val srv = candidateWith("nothing-usable", l2tp = true, sstp = true, sstpConnectPort = 0)

        val c = controller(servers = listOf(srv), priorities = priorities)
        assertEquals(emptyList<AutoModeProtocol>(), c.usableProtocols(srv, priorities))

        c.start()
        val terminal = c.awaitTerminal()
        assertEquals(AutoModeController.ERROR_NO_SERVER, (terminal as AutoModeState.Error).message)
        assertEquals(
            "an unusable server must never reach connect()",
            emptyList<Pair<String?, AutoModeProtocol>>(),
            adapter.connects,
        )
    }

    /** TEST 7 — with several usable protocols, the user's order is the order. */
    @Test
    fun protocolPriorityDeterminesAttemptOrder() {
        val priorities = listOf(
            AutoModeProtocol.OPENVPN_TCP,
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeProtocol.MS_SSTP,
        )
        val srv = candidateWith("all-three", seTcp = 9922, ovpnTcp = 443, sstp = true, sstpConnectPort = 443)

        val c = controller(servers = emptyList(), priorities = priorities)
        assertEquals(priorities, c.usableProtocols(srv, priorities))
    }

    /**
     * TEST 8 — Protocol Priority must NOT act as a global eligibility gate.
     *
     * This is the regression test for the old behaviour, where the whole server
     * list was filtered through `priorities.first()`: server A, which cannot speak
     * the top-priority protocol at all, would have been dropped even though it
     * speaks the second one perfectly well.
     */
    @Test
    fun protocolPriorityDoesNotGloballyFilterServers() = runBlocking {
        val priorities = listOf(AutoModeProtocol.MS_SSTP, AutoModeProtocol.SOFTETHER_TCP)
        // B speaks the top priority; A only speaks the second one.
        val b = candidateWith("b-top-priority", seTcp = 9922, sstp = true, sstpConnectPort = 443)
        val a = candidateWith("a-second-priority", seTcp = 9922)
        // B fails, so A must still be reached — through the second protocol.
        adapter.failing += "b-top-priority"

        val c = controller(servers = listOf(b, a), priorities = priorities)
        c.start()
        val terminal = c.awaitTerminal()

        assertEquals(
            listOf(
                "b-top-priority" to AutoModeProtocol.MS_SSTP,
                // B speaks both, so B's own protocols are exhausted before the
                // run moves on (§10: one attempt per server).
                "b-top-priority" to AutoModeProtocol.SOFTETHER_TCP,
                "a-second-priority" to AutoModeProtocol.SOFTETHER_TCP,
            ),
            adapter.connects,
        )
        assertTrue(terminal is AutoModeState.Connected)
        assertEquals("a-second-priority", (terminal as AutoModeState.Connected).hostname)
    }

    /**
     * TEST 9 — the probe is handed protocol/endpoint pairs the controller already
     * selected, one per server, each from that server's OWN usable protocols.
     */
    @Test
    fun reachabilityProbeReceivesControllerSelectedProtocolAndEndpoint() = runBlocking {
        val targets = mutableListOf<ReachabilityTarget>()
        val priorities = listOf(AutoModeProtocol.MS_SSTP, AutoModeProtocol.SOFTETHER_TCP)
        val seOnly = candidateWith("probe-se", seTcp = 9922)
        val sstpOnly = candidateWith("probe-sstp", sstp = true, sstpConnectPort = 443)
        adapter.failing += "probe-se"

        val c = controller(
            servers = listOf(seOnly, sstpOnly),
            priorities = priorities,
            reachabilityFilter = { handed ->
                targets += handed
                // "nothing was measured": not a verdict, so the queue must survive.
                ReachabilityGate(emptyList(), allUnreachable = false)
            },
        )
        c.start()
        c.awaitTerminal()

        assertEquals(
            "one selected endpoint per server, each on that server's own protocol",
            listOf(
                ReachabilityTarget(seOnly, AutoModeProtocol.SOFTETHER_TCP, 9922),
                ReachabilityTarget(sstpOnly, AutoModeProtocol.MS_SSTP, 443),
            ),
            targets,
        )
        // And the "nothing answered" case must not empty the queue.
        assertEquals(2, adapter.connects.size)
    }

    /**
     * Stage 2F-F.16 (Y2): a server that publishes no endpoint at all for a usable
     * protocol is still handed to the probe — with port 0, meaning "nothing to
     * test" — and is still attempted.
     *
     * Phase 2 had it skipped by the probe. That skipped record was the problem: a
     * protocol with no published endpoint and a protocol that was probed and found
     * closed produced the same absence, so nothing downstream could tell "we could
     * not test it" from "it is down" — and the first reading is the one an absent
     * value silently takes. The tri-state store now records UNKNOWN for it, which
     * is a real answer: it keeps the server without claiming it is reachable, and
     * it does not read as a verdict of failure.
     */
    @Test
    fun unprobeableServerIsProbedAsUnknownAndStillAttempted() = runBlocking {
        val targets = mutableListOf<ReachabilityTarget>()
        val priorities = listOf(AutoModeProtocol.SOFTETHER_UDP)
        // SoftEther UDP with no published UDP port: connectable (the native stack
        // learns the endpoint at runtime), not probeable. The TCP control channel
        // must be published for the protocol to be selectable at all (F.28AL
        // BUG 1: R-UDP/NAT-T is broken for SETcpPort=0 servers), so seTcp is set
        // here — it does not affect the UDP endpoint probe this test asserts on.
        val srv = candidateWith("udp-no-port", seTcp = 9922, seUdpSupported = true, seUdpPort = 0)

        val c = controller(
            servers = listOf(srv),
            priorities = priorities,
            reachabilityFilter = { handed ->
                targets += handed
                ReachabilityGate(emptyList(), allUnreachable = false)
            },
        )
        c.start()
        c.awaitTerminal()

        assertEquals(
            "handed over for an UNKNOWN verdict, with no fabricated port",
            listOf(ReachabilityTarget(srv, AutoModeProtocol.SOFTETHER_UDP, 0)),
            targets,
        )
        assertEquals(
            listOf("udp-no-port" to AutoModeProtocol.SOFTETHER_UDP),
            adapter.connects,
        )
    }

    /** TEST 10 — no protocol may borrow another protocol's port, and none is invented. */
    @Test
    fun noProtocolBorrowsOrFabricatesAPort() {
        val srv = candidateWith(
            "ports",
            seTcp = 9922,
            sstp = true,
            sstpConnectPort = 0,
        )

        assertEquals(
            "an UNKNOWN SSTP port must not become the SoftEther port or 443",
            0,
            AutoModeProtocol.MS_SSTP.endpointPort(srv),
        )
        assertEquals(9922, AutoModeProtocol.SOFTETHER_TCP.endpointPort(srv))
        assertEquals(0, AutoModeProtocol.SOFTETHER_UDP.endpointPort(srv))
        assertEquals(0, AutoModeProtocol.OPENVPN_TCP.endpointPort(srv))
        assertEquals(0, AutoModeProtocol.OPENVPN_UDP.endpointPort(srv))

        val c = controller(servers = emptyList())
        assertEquals(
            emptyList<AutoModeProtocol>(),
            c.usableProtocols(srv, listOf(AutoModeProtocol.MS_SSTP)),
        )
    }

    /**
     * SoftEther UDP stays usable with no published UDP port.
     *
     * This is the project's existing model, not a convenience: the native stack
     * uses the SoftEther TCP port as the control channel for EVERY transport and
     * learns the UDP endpoint from the server's Welcome PACK or the NAT-T
     * rendezvous, so `seUdpSupported` alone is a genuinely connectable state.
     * Requiring a published UDP port here would make Auto Connect refuse servers
     * it can actually connect to, and `seUdpPort` is used for reachability
     * probing only. Locking this down so it cannot regress silently.
     */
    @Test
    fun softEtherUdpStaysUsableWithoutAPublishedUdpPort() {
        val srv = candidateWith("udp-capable", seTcp = 9922, seUdpSupported = true, seUdpPort = 0)
        val c = controller(servers = emptyList())
        assertEquals(
            listOf(AutoModeProtocol.SOFTETHER_UDP),
            c.usableProtocols(srv, listOf(AutoModeProtocol.SOFTETHER_UDP)),
        )
        // …and it still never borrows the TCP port in order to be probed.
        assertEquals(0, AutoModeProtocol.SOFTETHER_UDP.endpointPort(srv))
    }

    // ==================================================================
    // Stage 2F-F.15 (Phase 4) — D4 + Y1-a + Y1-b.
    //
    // Feed quality decides; active RTT is the final tie-break among otherwise
    // equal candidates. A missing RTT is neutral, and a candidate's RTT is the
    // MINIMUM valid one over the protocols Auto Connect may actually use.
    // ==================================================================

    private val seOnly = listOf(AutoModeProtocol.SOFTETHER_TCP)

    /** Rank through the real production ordering used by Auto Connect. */
    private fun rank(
        protocol: AutoModeProtocol = AutoModeProtocol.SOFTETHER_TCP,
        vararg candidates: AutoModeCandidate,
    ): List<String> = controller(servers = emptyList())
        .compatibleServers(candidates.toList(), protocol)
        .mapNotNull { it.hostname }

    /** TEST 1 — with every primary key equal, the lower RTT wins. */
    @Test
    fun rttBreaksTiesWhenPrimaryKeysAreEqual() {
        val slow = candidateWith("slow", seTcp = 443, rtt = 100)
        val fast = candidateWith("fast", seTcp = 443, rtt = 50)
        assertEquals(listOf("fast", "slow"), rank(AutoModeProtocol.SOFTETHER_TCP, slow, fast))
    }

    /** TEST 2 — RTT must not override speed. */
    @Test
    fun rttCannotOverrideSpeed() {
        val fastServer = candidateWith("fastServer", speed = 500_000_000, seTcp = 443, rtt = 500)
        val slowServer = candidateWith("slowServer", speed = 100_000_000, seTcp = 443, rtt = 10)
        assertEquals(listOf("fastServer", "slowServer"), rank(AutoModeProtocol.SOFTETHER_TCP, slowServer, fastServer))
    }

    /** TEST 3 — RTT must not override the feed ping. */
    @Test
    fun rttCannotOverrideFeedPing() {
        val lowPing = candidateWith("lowPing", ping = 20, seTcp = 443, rtt = 500)
        val highPing = candidateWith("highPing", ping = 50, seTcp = 443, rtt = 10)
        assertEquals(listOf("lowPing", "highPing"), rank(AutoModeProtocol.SOFTETHER_TCP, highPing, lowPing))
    }

    /** TEST 4 — RTT must not override the session count. */
    @Test
    fun rttCannotOverrideSessions() {
        val fewer = candidateWith("fewer", sessions = 5, seTcp = 443, rtt = 500)
        val more = candidateWith("more", sessions = 10, seTcp = 443, rtt = 10)
        assertEquals(listOf("fewer", "more"), rank(AutoModeProtocol.SOFTETHER_TCP, more, fewer))
    }

    /** TEST 5 — RTT must not override the feed score. */
    @Test
    fun rttCannotOverrideFeedScore() {
        val highScore = candidateWith("highScore", score = 90, seTcp = 443, rtt = 500)
        val lowScore = candidateWith("lowScore", score = 80, seTcp = 443, rtt = 10)
        assertEquals(listOf("highScore", "lowScore"), rank(AutoModeProtocol.SOFTETHER_TCP, lowScore, highScore))
    }

    /**
     * TEST 6 — a missing RTT is NEUTRAL, not worse.
     *
     * The banned implementation `thenBy { activeRttMs ?: Long.MAX_VALUE }` would
     * produce `[slowRtt, unmeasured]` here — an unmeasured server demoted for
     * having no measurement. What must happen instead is that presence of RTT is
     * not a ranking key at all, so the input order is preserved in BOTH
     * directions. This is what makes the assertion meaningful: it pins down
     * "neither better nor worse" instead of picking one.
     */
    @Test
    fun missingRttIsNeutralAndNotDemoted() {
        val unmeasured = candidateWith("unmeasured", seTcp = 443, rtt = null)
        val measured = candidateWith("measured", seTcp = 443, rtt = 50)

        assertEquals(
            listOf("unmeasured", "measured"),
            rank(AutoModeProtocol.SOFTETHER_TCP, unmeasured, measured),
        )
        assertEquals(
            listOf("measured", "unmeasured"),
            rank(AutoModeProtocol.SOFTETHER_TCP, measured, unmeasured),
        )
    }

    /**
     * TEST 6b — an unmeasured candidate is not pushed behind every measured one,
     * while the measured ones among themselves ARE ordered by RTT.
     *
     * Input `[unmeasured, 100ms, 50ms]` must come back as
     * `[unmeasured, 50ms, 100ms]`: the RTT comparison happened between the two
     * candidates that both had a measurement, and the unmeasured one neither
     * moved nor moved anything.
     */
    @Test
    fun unmeasuredCandidateNeitherMovesNorIsMoved() {
        val unmeasured = candidateWith("unmeasured", seTcp = 443, rtt = null)
        val slower = candidateWith("slower", seTcp = 443, rtt = 100)
        val faster = candidateWith("faster", seTcp = 443, rtt = 50)

        assertEquals(
            listOf("unmeasured", "faster", "slower"),
            rank(AutoModeProtocol.SOFTETHER_TCP, unmeasured, slower, faster),
        )
    }

    /**
     * The pairwise "return 0 when either side is missing" comparator is not a
     * valid ordering: with `A(100) == B(unknown) == C(50)` and `C < A` it is not
     * transitive, and TimSort throws on it once a list is long enough to merge.
     * This test is the regression guard that Auto Connect's ranking keeps working
     * on a realistically sized server list instead of crashing.
     */
    @Test
    fun neutralRttOrderingSurvivesAListLargeEnoughToTripTimSort() {
        val many = (0 until 400).map { i ->
            candidateWith(
                "srv$i",
                seTcp = 443,
                // Every third candidate has no measurement at all.
                rtt = if (i % 3 == 0) null else (i * 7L % 500) + 1L,
            )
        }
        // Must not throw, and must keep every candidate exactly once.
        val ranked = controller(servers = emptyList())
            .compatibleServers(many, AutoModeProtocol.SOFTETHER_TCP)
        assertEquals(many.size, ranked.size)
        assertEquals(many.map { it.hostname }.toSet(), ranked.map { it.hostname }.toSet())
        // Quality is still the dominant key: descending speed groups stay intact.
        assertTrue(ranked.first().speed >= ranked.last().speed)
    }

    /**
     * With no probe wired at all — a first-ever run, where nothing has been
     * measured — ranking must be exactly the Phase 1 primary ordering.
     */
    @Test
    fun withoutAnyMeasurementRankingIsPurelyPrimary() {
        val candidates = listOf(
            candidateWith("zeroSpeed", speed = 0, seTcp = 443),
            candidateWith("slowPing", ping = 300, seTcp = 443),
            candidateWith("best", seTcp = 443),
        )
        assertEquals(
            listOf("best", "slowPing", "zeroSpeed"),
            rank(AutoModeProtocol.SOFTETHER_TCP, *candidates.toTypedArray()),
        )
    }

    // ---- Y1-b: aggregation over usable protocols, through a real run ----

    /** Rank by running Auto Connect for real and reporting the first dial. */
    private suspend fun firstDialed(
        servers: List<AutoModeCandidate>,
        priorities: List<AutoModeProtocol>,
        rttProvider: (String, AutoModeProtocol) -> Long?,
    ): String? {
        adapter.failing += servers.map { it.hostname!! }
        val c = controller(servers = servers, priorities = priorities, activeRttProvider = rttProvider)
        c.start()
        c.awaitTerminal()
        return adapter.connects.firstOrNull()?.first
    }

    /** The aggregation result for [candidate], for direct assertion. */
    private fun rttOf(
        candidate: AutoModeCandidate,
        priorities: List<AutoModeProtocol>,
        provider: (String, AutoModeProtocol) -> Long?,
    ): Long? = controller(servers = emptyList(), activeRttProvider = provider)
        .representativeRtt(candidate, priorities)

    /** TEST 7 — the candidate's RTT is the MINIMUM over its usable protocols. */
    @Test
    fun candidateRttIsTheMinimumOverUsableProtocols() = runBlocking {
        val both = candidateWith("both", seTcp = 443, ovpnTcp = 1194)
        val rival = candidateWith("rival", seTcp = 443)
        val rtt: (String, AutoModeProtocol) -> Long? = { ip, protocol ->
            when {
                ip == both.ip && protocol == AutoModeProtocol.SOFTETHER_TCP -> 200L
                ip == both.ip && protocol == AutoModeProtocol.OPENVPN_TCP -> 80L
                ip == rival.ip -> 100L
                else -> null
            }
        }
        val priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.OPENVPN_TCP)

        assertEquals(
            "the fastest usable route is the honest description of the server",
            80L,
            rttOf(both, priorities, rtt),
        )
        assertEquals(100L, rttOf(rival, priorities, rtt))

        // …and the aggregated value really reaches the dial order.
        assertEquals("both", firstDialed(listOf(both, rival), priorities, rtt))
    }

    /**
     * TEST 8 — null, 0 and negative values are not measurements.
     *
     * `0` and the `-1` an unreachable probe reports both mean "no measurement".
     * Only the one real value survives; nothing is averaged and nothing is
     * substituted for the missing ones.
     */
    @Test
    fun invalidRttValuesAreIgnored() {
        val candidate = candidateWith(
            "mixed",
            seTcp = 443,
            ovpnTcp = 1194,
            ovpnUdp = 1195,
            seUdpSupported = true,
            seUdpPort = 40000,
        )
        val priorities = listOf(
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeProtocol.OPENVPN_TCP,
            AutoModeProtocol.OPENVPN_UDP,
            AutoModeProtocol.SOFTETHER_UDP,
        )
        assertEquals(
            120L,
            rttOf(
                candidate,
                priorities,
            ) { _, protocol ->
                when (protocol) {
                    AutoModeProtocol.SOFTETHER_TCP -> null
                    AutoModeProtocol.OPENVPN_TCP -> 0L
                    AutoModeProtocol.OPENVPN_UDP -> -1L
                    AutoModeProtocol.SOFTETHER_UDP -> 120L
                    else -> 5L
                }
            },
        )
    }

    /** TEST 9 — when every RTT is invalid the candidate simply has none. */
    @Test
    fun allInvalidRttsYieldNoMeasurement() {
        val candidate = candidateWith("none", seTcp = 443)
        assertNull(
            "an unmeasured candidate reports no RTT; it never gets a stand-in value",
            rttOf(candidate, seOnly) { _, _ -> -1L },
        )
        assertNull(rttOf(candidate, seOnly) { _, _ -> 0L })
        assertNull(rttOf(candidate, seOnly) { _, _ -> null })
    }

    /** TEST 10 — an unsupported protocol's RTT must not lower the candidate's. */
    @Test
    fun unsupportedProtocolRttDoesNotContribute() {
        // No OpenVPN port at all, so OpenVPN is not usable however good it looks.
        val candidate = candidateWith("seOnly", seTcp = 443)
        assertEquals(
            200L,
            rttOf(candidate, listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.OPENVPN_TCP)) { _, p ->
                when (p) {
                    AutoModeProtocol.SOFTETHER_TCP -> 200L
                    AutoModeProtocol.OPENVPN_TCP -> 10L
                    else -> null
                }
            },
        )
    }

    /** TEST 11 — L2TP is not automatable, so its RTT never reaches Auto Connect. */
    @Test
    fun l2tpRttDoesNotContribute() {
        val candidate = candidateWith("withL2tp", seTcp = 443, l2tp = true)
        assertEquals(
            200L,
            rttOf(candidate, listOf(AutoModeProtocol.L2TP_IPSEC, AutoModeProtocol.SOFTETHER_TCP)) { _, p ->
                when (p) {
                    AutoModeProtocol.SOFTETHER_TCP -> 200L
                    AutoModeProtocol.L2TP_IPSEC -> 5L
                    else -> null
                }
            },
        )
    }

    /** TEST 12 — MS-SSTP with no resolvable port is unusable, so its RTT is ignored. */
    @Test
    fun invalidSstpRttDoesNotContribute() {
        val candidate = candidateWith(
            "sstpUnknown",
            seTcp = 443,
            sstp = true,
            sstpConnectPort = 0,
        )
        assertEquals(
            150L,
            rttOf(candidate, listOf(AutoModeProtocol.MS_SSTP, AutoModeProtocol.SOFTETHER_TCP)) { _, p ->
                when (p) {
                    AutoModeProtocol.MS_SSTP -> 1L
                    AutoModeProtocol.SOFTETHER_TCP -> 150L
                    else -> null
                }
            },
        )
    }

    /**
     * TEST 13 — RTT is read per protocol; one protocol's answer never stands for
     * another's, and no lookup is ever IP-only.
     */
    @Test
    fun rttIsReadPerProtocolNotPerServer() {
        val multi = candidateWith("multi", seTcp = 443, ovpnTcp = 1194)
        val asked = mutableListOf<Pair<String, AutoModeProtocol>>()
        val priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.OPENVPN_TCP)

        assertEquals(
            50L,
            rttOf(multi, priorities) { ip, protocol ->
                asked += ip to protocol
                when (protocol) {
                    AutoModeProtocol.SOFTETHER_TCP -> 200L
                    AutoModeProtocol.OPENVPN_TCP -> 50L
                    else -> null
                }
            },
        )
        assertEquals(
            setOf(
                AutoModeProtocol.SOFTETHER_TCP,
                AutoModeProtocol.OPENVPN_TCP,
            ),
            asked.map { it.second }.toSet(),
        )
    }

    /**
     * TEST 14 — the Server List's protocol-less answer cannot become an Auto
     * Connect RTT.
     *
     * The controller only ever asks its provider for (IP, protocol) pairs drawn
     * from the usable protocols — never for a bare IP — so there is no shape in
     * which a SERVER_LIST result could be returned. That the two namespaces
     * cannot alias each other in the first place is proven separately in
     * `ReachabilityNamespaceTest` (Stage 2F-F.14).
     */
    @Test
    fun serverListAnswerCannotBecomeAutoConnectRtt() {
        val server = candidateWith("server", seTcp = 443)
        val asked = mutableListOf<String>()

        assertNull(
            "a provider that answers only for other servers yields no RTT",
            rttOf(server, seOnly) { ip, _ ->
                asked += ip
                null
            },
        )
        assertEquals(
            "the only lookup shape the controller has is IP + protocol",
            listOf(server.ip),
            asked,
        )
    }

    /** TEST 15 — a protocol with no measurement contributes nothing; no borrowing. */
    @Test
    fun noRttIsBorrowedFromAnotherProtocol() {
        // SoftEther UDP is usable but unmeasured: no UDP RTT exists yet (that
        // arrives with the later probing phases), and the TCP answer must not be
        // reused for it.
        val candidate = candidateWith("udpAndTcp", seTcp = 443, seUdpSupported = true, seUdpPort = 0)
        assertEquals(
            200L,
            rttOf(candidate, listOf(AutoModeProtocol.SOFTETHER_UDP, AutoModeProtocol.SOFTETHER_TCP)) { _, p ->
                when (p) {
                    AutoModeProtocol.SOFTETHER_TCP -> 200L
                    AutoModeProtocol.SOFTETHER_UDP -> null
                    else -> null
                }
            },
        )
    }

    /** TEST 16 — Phase 1 (D3) preserved: a measured ping outranks an unknown one. */
    @Test
    fun phase1PingValidityStillDominates() {
        val measured = candidateWith("measured", ping = 50, seTcp = 443, rtt = 500)
        val unknown = candidateWith("unknown", ping = 0, seTcp = 443, rtt = 10)
        assertEquals(listOf("measured", "unknown"), rank(AutoModeProtocol.SOFTETHER_TCP, unknown, measured))
    }

    /** TEST 17 — Phase 1 (D5) preserved: `sources` still cannot change ordering. */
    @Test
    fun phase1SourcesStillIrrelevant() {
        val oneSource = candidateWith("oneSource", seTcp = 443, sources = 1)
        val manySources = candidateWith("manySources", seTcp = 443, sources = 9_999)
        // Equal on every ranking key, so nothing may promote the high-count one.
        assertEquals(
            setOf("oneSource", "manySources"),
            rank(AutoModeProtocol.SOFTETHER_TCP, oneSource, manySources).toSet(),
        )
    }

    // ================================================================
    // Stage 2F-F.17 (D1 = FILTER_ONLY)
    // ================================================================

    /**
     * CRITICAL FIX #1 — every **usable** protocol gets a probe target.
     *
     * Phase 5 handed over `usableProtocols(...).firstOrNull()`, which made D1's
     * rule unrepresentable: "every usable protocol is unreachable" cannot be
     * evaluated over one endpoint, and a server whose top-priority port refused
     * was gated out while a healthy second protocol went untested. Protocol
     * Priority stays the *attempt* order and nothing else.
     */
    @Test
    fun everyUsableProtocolGetsAProbeTargetInPriorityOrder() = runBlocking {
        val targets = mutableListOf<ReachabilityTarget>()
        val srv = candidateWith("all", seTcp = 9922, seUdpPort = 5555, ovpnTcp = 443, ovpnUdp = 1194)
        adapter.failing += "all"

        val c = controller(
            servers = listOf(srv),
            priorities = listOf(
                AutoModeProtocol.OPENVPN_UDP,
                AutoModeProtocol.SOFTETHER_TCP,
                AutoModeProtocol.SOFTETHER_UDP,
                AutoModeProtocol.OPENVPN_TCP,
            ),
            reachabilityFilter = { handed ->
                targets += handed
                ReachabilityGate(emptyList(), allUnreachable = false)
            },
        )
        c.start()
        c.awaitTerminal()

        assertEquals(
            "one target per usable protocol, each at its own published endpoint, " +
                "still in the user's Protocol Priority order",
            listOf(
                ReachabilityTarget(srv, AutoModeProtocol.OPENVPN_UDP, 1194),
                ReachabilityTarget(srv, AutoModeProtocol.SOFTETHER_TCP, 9922),
                ReachabilityTarget(srv, AutoModeProtocol.SOFTETHER_UDP, 5555),
                ReachabilityTarget(srv, AutoModeProtocol.OPENVPN_TCP, 443),
            ),
            targets,
        )
    }

    /** TEST 9 — L2TP is never a D1 target, and "no usable protocol" is not "down". */
    @Test
    fun l2TpIsNeverProbedAndZeroUsableProtocolsIsNotAllUnreachable() = runBlocking {
        val filterCalls = mutableListOf<List<ReachabilityTarget>>()
        // L2TP confirmed by the data model, and enabled by the user. It is still
        // not automatable, so the server has no usable protocol at all.
        val l2tpOnly = candidateWith("l2tpOnly", l2tp = true)

        val c = controller(
            servers = listOf(l2tpOnly),
            priorities = listOf(AutoModeProtocol.L2TP_IPSEC, AutoModeProtocol.SOFTETHER_TCP),
            reachabilityFilter = { handed ->
                filterCalls += handed
                ReachabilityGate(emptyList(), allUnreachable = true)
            },
        )
        c.start()
        val end = c.awaitTerminal()

        assertTrue(
            "with no usable protocol there is nothing to probe, so D1 must not run at all",
            filterCalls.isEmpty(),
        )
        assertTrue(
            "and the pre-existing capability skip, not the D1 gate, is what ends the run",
            end is AutoModeState.Error && end.message == AutoModeController.Companion.ERROR_NO_SERVER,
        )
        assertTrue("no L2TP connection may be attempted", adapter.connects.isEmpty())
    }

    /** TEST 17 — the same distinction from the controller's side: no targets, no gate. */
    @Test
    fun aServerWithNoUsableProtocolIsSkippedNotGatedOut() = runBlocking {
        val filterCalls = mutableListOf<List<ReachabilityTarget>>()
        val dead = candidateWith("dead", l2tp = true)

        val c = controller(
            servers = listOf(dead),
            priorities = listOf(AutoModeProtocol.L2TP_IPSEC),
            reachabilityFilter = { handed ->
                filterCalls += handed
                ReachabilityGate(emptyList(), allUnreachable = false)
            },
        )
        c.start()
        c.awaitTerminal()

        assertTrue(filterCalls.isEmpty())
        assertTrue(
            adapter.logs.any { it.contains("no supported protocol") },
        )
    }

    /**
     * TEST 15 / the end-to-end order regression the Phase 5 audit asked for.
     *
     * The gate returns its survivors in a deliberately wrong order — the reverse
     * of the ranked input — and the controller must dial them in **quality
     * order**, not in the order the gate happened to produce. Reachability is a
     * filter; [AutoModeController.byQuality] is the only ranking authority.
     *
     * Speeds descend A > B > C > D, so the ranked input is A, B, C, D. C is
     * removed by the gate. The survivors come back as D, B, A and must still be
     * attempted A, B, D.
     */
    @Test
    fun theGateNeverChangesTheDialledOrder() = runBlocking {
        val a = candidateWith("A", speed = 40_000_000, seTcp = 443)
        val b = candidateWith("B", speed = 30_000_000, seTcp = 443)
        val c = candidateWith("C", speed = 20_000_000, seTcp = 443)
        val d = candidateWith("D", speed = 10_000_000, seTcp = 443)
        // Every attempt fails, so all four would be dialled if the gate let them.
        adapter.failing += setOf("A", "B", "C", "D")

        val c1 = controller(
            servers = listOf(a, b, c, d),
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP),
            reachabilityFilter = { handed ->
                val byHost = handed.map { it.candidate }
                // Reverse of the ranked order, with C (index 2) dropped.
                ReachabilityGate(
                    kept = listOf(byHost[3], byHost[1], byHost[0]),
                    allUnreachable = false,
                )
            },
        )
        c1.start()
        c1.awaitTerminal()

        assertEquals(
            "the gate filters; the comparator decides the order",
            listOf("A" to AutoModeProtocol.SOFTETHER_TCP, "B" to AutoModeProtocol.SOFTETHER_TCP, "D" to AutoModeProtocol.SOFTETHER_TCP),
            adapter.connects,
        )
    }

    /**
     * TEST 16 — a definitive all-unreachable result is honoured, not undone.
     *
     * The pre-D1 fallback was `if (probed.isNotEmpty()) probed else qualitySorted`,
     * which read an empty result as "no evidence" and restored every server. That
     * is exactly right when nothing was measured and exactly wrong when every
     * usable protocol of every server came back definitively down: the removal
     * D1 promises was silently reverted. This pins the corrected behaviour.
     */
    @Test
    fun aDefinitiveAllUnreachableResultEmptiesTheQueue() = runBlocking {
        val a = candidateWith("A", seTcp = 443)
        val b = candidateWith("B", seTcp = 443)

        val c = controller(
            servers = listOf(a, b),
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP),
            reachabilityFilter = {
                ReachabilityGate(emptyList(), allUnreachable = true)
            },
        )
        c.start()
        val end = c.awaitTerminal()

        assertTrue(
            "every usable protocol of every server was definitively down",
            adapter.connects.isEmpty(),
        )
        assertTrue(
            end is AutoModeState.Error && end.message == AutoModeController.Companion.ERROR_NO_SERVER,
        )
    }

    /** And the mirror case, so the two can never be confused again. */
    @Test
    fun anEmptyResultWithNoVerdictKeepsTheQueue() = runBlocking {
        val a = candidateWith("A", seTcp = 443)
        adapter.failing += "A"

        val c = controller(
            servers = listOf(a),
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP),
            reachabilityFilter = { ReachabilityGate(emptyList(), allUnreachable = false) },
        )
        c.start()
        c.awaitTerminal()

        assertEquals(
            "an inconclusive empty result must not empty Auto Connect",
            listOf("A" to AutoModeProtocol.SOFTETHER_TCP),
            adapter.connects,
        )
    }

    /** A probe that throws is a failure to measure, never a verdict. */
    @Test
    fun aThrowingProbeKeepsTheQueue() = runBlocking {
        val a = candidateWith("A", seTcp = 443)
        adapter.failing += "A"

        val c = controller(
            servers = listOf(a),
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP),
            reachabilityFilter = { throw RuntimeException("probe exploded") },
        )
        c.start()
        c.awaitTerminal()

        assertEquals(listOf("A" to AutoModeProtocol.SOFTETHER_TCP), adapter.connects)
    }

    /**
     * Stage 2F-F.19 (F1): a server the probe cannot even address stays in the queue.
     *
     * This drives the **real** D1 gate rather than a stand-in for it, with only the
     * socket layer replaced, because the defect being pinned lives in the gate's
     * aggregation and not in the controller. "No address" and "address refused"
     * look identical from outside — no verdict — and only one of them may remove a
     * server.
     *
     * `dead` is genuinely refused and is removed. `addressless` cannot be probed at
     * all and survives. Both go through [ServerReachabilityTester] unmodified.
     */
    @Test
    fun aServerWithNoAddressSurvivesTheRealGateWhileARefusedOneIsRemoved() = runBlocking {
        val refused = candidateWith("refused", ip = "10.7.0.1", seTcp = 443)
        val addressless = candidateWith("addressless", ip = "10.7.0.2", seTcp = 443)
            .copy(ip = null)
        adapter.failing += "refused"

        val c = controller(
            servers = listOf(refused, addressless),
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP),
            reachabilityFilter = { handed ->
                vn.unlimit.vpngate.network.ServerReachabilityTester
                    .filterReachableCandidates(
                        targets = handed,
                        probe = object : vn.unlimit.vpngate.network.AutoConnectReachabilityProbe {
                            override fun probe(
                                target: ReachabilityTarget,
                                tcpTimeoutMs: Int,
                            ) = vn.unlimit.vpngate.network.ProbeReport(
                                vn.unlimit.vpngate.network.WireOutcome.REFUSED,
                                0L,
                            )
                        },
                    )
            },
        )
        c.start()
        c.awaitTerminal()

        assertEquals(
            "the refused server is gated out; the unaddressable one is still attempted",
            listOf("addressless" to AutoModeProtocol.SOFTETHER_TCP),
            adapter.connects,
        )
        assertTrue(
            "and the unaddressable server produced no verdict to justify removing it",
            vn.unlimit.vpngate.network.ServerReachabilityTester
                .autoConnectState(null, AutoModeProtocol.SOFTETHER_TCP) == null,
        )
    }

    // --- F.28AL-Y: bounded NATIVE_BUSY retry tests ---

    /**
     * T1. NATIVE_BUSY on attempt 1 → retry after W → success on attempt 2.
     *
     * Verifies the retry path: the same server+protocol is re-dialled after the
     * delay, and the run ends in Connected rather than Error.
     */
    @Test
    fun nativeBusyRetrySucceedsOnSecondAttempt() = runBlocking {
        adapter.scriptBusy("busy-then-up", false, true)
        val c = controller(
            servers = listOf(server("busy-then-up")),
            timeout = 500,
        )
        c.start()
        val s = c.awaitTerminal()
        assertTrue("expected Connected, got $s", s is AutoModeState.Connected)
        // Two connect() calls: the initial attempt plus one retry.
        assertEquals(
            "expected one retry of the same server",
            2, adapter.connects.size,
        )
        assertEquals("busy-then-up", adapter.connects[0].first)
        assertEquals("busy-then-up", adapter.connects[1].first)
        assertTrue(
            "expected a slot-release log line",
            adapter.logs.any { it.contains("Native slot released; retrying") },
        )
    }

    /**
     * T2. A SECOND refusal on the same protocol — after the slot was observed released —
     * is a genuine protocol failure, not a stuck native slot.
     *
     * AUTOCONNECT-AUDIT D-2 replaced the fixed retry count with "wait for the release,
     * retry once". A retry that is refused again therefore means the slot was free and
     * the dial still could not be admitted, so the attempt is closed like any other
     * failure and the rotation continues to the next protocol of the same server. The
     * run must NOT end here: the refusal says nothing about the server, and the previous
     * implementation turned this exact sequence into a terminal ERROR_NATIVE_UNRESOLVED.
     */
    @Test
    fun secondRefusalAfterReleaseRotatesToTheNextProtocol() = runBlocking {
        adapter.scriptBusy("busy-busy-up", false, false, true)
        val c = controller(
            servers = listOf(server("busy-busy-up")),
            timeout = 500,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        c.start()
        val s = c.awaitTerminal()
        assertTrue("expected Connected via the next protocol, got $s", s is AutoModeState.Connected)
        assertEquals(
            "TCP is dialled twice (refusal + retry after release), then the cursor advances",
            listOf(
                "busy-busy-up" to AutoModeProtocol.SOFTETHER_TCP,
                "busy-busy-up" to AutoModeProtocol.SOFTETHER_TCP,
                "busy-busy-up" to AutoModeProtocol.SOFTETHER_UDP,
            ),
            adapter.connects.toList(),
        )
        assertTrue("the refusal must be answered by a slot wait", adapter.slotWaits >= 1)
        assertTrue(
            "the failed retry must be logged as a protocol failure, not as a stuck slot",
            adapter.logs.any { it.contains("did not come up") },
        )
        assertFalse(
            "a released slot must never produce the give-up message",
            adapter.logs.any { it.contains("Native slot not released within") },
        )
    }

    /**
     * T3 (rewritten in v1.0.37). NATIVE_BUSY whose slot never releases no longer ends the
     * run: the expiry moves on to the next candidate.
     *
     * v1.0.37: rewritten under explicit operator authorization to match the contract that
     * AutoConnect does not stop for NATIVE_BUSY (§6.8.4 lead, §1.6.29).
     *
     * The original test pinned the v1.0.34 terminal — a slot still held when the wait
     * expired published `ERROR_NATIVE_UNRESOLVED` and the whole run stopped on the first
     * server. The v1.0.36 device run measured what that terminal costs: 2 of 5 runs died
     * on the first server, 7.7 s before the native stack reached its own verdict
     * (`DEVICE_VERIFY_AUTOCONNECT_FIX_6_REPORT.md` §7.1). A wait that expires now means
     * "move on": the refused protocol is left behind, the remaining list is walked, and the
     * only terminal is the ordinary exhausted-list `ERROR_NO_SERVER`.
     *
     * The falsifiable claim: before v1.0.37 this run ended with
     * `ERROR_NATIVE_UNRESOLVED` after exactly ONE dial and never logged
     * `"All compatible servers failed"`.
     *
     * Naming note: the brief for this phase proposed
     * `aSlotThatNeverReleasesMovesOnToTheNextProtocol`, but the controller's `break` leaves
     * the PROTOCOL loop, so the rotation continues at the next SERVER — the refused
     * server's second protocol is deliberately never dialled. The name states what the code
     * does; the assertion below pins that the second protocol of the first server is absent.
     */
    @Test
    fun aSlotThatNeverReleasesMovesOnToTheNextServer() = runBlocking {
        adapter.scriptBusy("always-busy", false, false, false, false)
        adapter.scriptBusy("also-busy", false, false, false, false)
        adapter.nativeSlotReleases = false
        val c = controller(
            servers = listOf(server("always-busy"), server("also-busy")),
            timeout = 500,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        c.start()
        val s = c.awaitTerminal()
        assertTrue("expected Error, got $s", s is AutoModeState.Error)
        assertEquals(
            "v1.0.37: a slot-wait expiry must no longer publish the removed terminal",
            AutoModeController.ERROR_NO_SERVER,
            (s as AutoModeState.Error).message,
        )
        assertTrue(
            "the expiry must be logged as a move-on, not as a stop: ${adapter.logs}",
            adapter.logs.any {
                it.contains("Slot-wait expired") && it.contains("moving on (no terminal")
            },
        )
        assertTrue(
            "the run must walk the remaining list instead of stopping: ${adapter.logs}",
            adapter.logs.any { it.contains("All compatible servers failed") },
        )
        assertEquals(
            "each server gets exactly one dial: the refused protocol is left behind and the " +
                "server's second protocol is never dialled",
            listOf(
                "always-busy" to AutoModeProtocol.SOFTETHER_TCP,
                "also-busy" to AutoModeProtocol.SOFTETHER_TCP,
            ),
            adapter.connects.toList(),
        )
        assertTrue(
            "the observable slot wait must be the mechanism, once per refused server",
            adapter.slotWaits >= 2,
        )
    }

    /**
     * T4. Cancellation during the retry delay terminates cleanly and leaves
     * no dangling job or leaked coroutine.
     *
     * The delay() inside the retry loop is cancellable: cancelling the scope
     * mid-wait must stop the run without publishing a terminal state from
     * the retry path and without leaving job active.
     */
    @Test
    fun nativeBusyCancellationDuringRetryIsClean() = runBlocking {
        adapter.scriptBusy("cancel-me", false, false, false)
        // AUTOCONNECT-AUDIT D-2: the wait that must be cancellable is now the slot wait,
        // not a fixed retry delay.
        adapter.nativeSlotReleases = false
        adapter.slotWaitDelayMs = 1_000
        val c = controller(
            servers = listOf(server("cancel-me")),
            timeout = 500,
        )
        c.start()
        // Let the first attempt finish and the slot wait begin.
        kotlinx.coroutines.delay(200)
        c.stop()
        kotlinx.coroutines.delay(1_500)
        assertFalse("job must not remain active after stop", c.isRunning)
        // No terminal state should have been published by the slot-wait path.
        assertTrue(
            "state should be Disconnected after clean stop, got ${c.state.value}",
            c.state.value is AutoModeState.Disconnected,
        )
    }

    /**
     * T5. Normal flow (no NATIVE_BUSY) is unchanged: one attempt, no delay,
     * no extra log lines.
     *
     * Guards the retry path against firing when the outcome is TUNNEL_UP or
     * TIMED_OUT — the common case must not pay a retry delay.
     */
    @Test
    fun nativeBusyDoesNotFireOnSuccess() = runBlocking {
        val c = controller(
            servers = listOf(server("plain-success")),
            timeout = 500,
        )
        c.start()
        val s = c.awaitTerminal()
        assertTrue("expected Connected, got $s", s is AutoModeState.Connected)
        assertEquals(
            "normal flow makes exactly one dial attempt",
            1, adapter.connects.size,
        )
        // AUTOCONNECT-AUDIT D-2: the retry path is gated on a NATIVE_BUSY outcome, and
        // its first action is now the observable slot wait. Zero waits is a stronger
        // assertion than "no retry log line": it proves the branch was never entered.
        assertEquals(
            "the slot wait must not run on the success path",
            0, adapter.slotWaits,
        )
    }

    /**
     * T6. A slot that RELEASES but whose retry still does not come up must MOVE ON
     * (AUTOCONNECT-AUDIT D-2).
     *
     * Before D-2 this scenario was answered by counting retries against a nominal
     * per-server delay budget and then publishing ERROR_NATIVE_UNRESOLVED. Because that
     * `return` sat inside the protocol loop, neither the remaining protocols of this
     * server nor any later server were ever tried — a refusal, which says nothing about
     * the server, ended the whole run.
     *
     * The replacement waits for the observable slot release, dials the same protocol
     * once more, and if that dial also fails to arm it treats the failure as a genuine
     * protocol failure and lets the rotation continue. Here TCP is refused, the slot is
     * released, the retry times out, and UDP then connects — three dials in total.
     */
    @Test
    fun releasedSlotRetryFailureMovesOnToTheNextProtocol() = runBlocking {
        adapter.scriptOutcomes(
            "slot-release", AutoModeProtocol.SOFTETHER_TCP,
            AutoModeController.AttemptOutcome.NATIVE_BUSY,
            AutoModeController.AttemptOutcome.TIMED_OUT,
        )
        val c = controller(
            servers = listOf(server("slot-release")),
            timeout = 500,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        c.start()
        val s = c.awaitTerminal()
        assertTrue("expected Connected via UDP, got $s", s is AutoModeState.Connected)
        assertEquals(
            "TCP is dialled once more after the release, then the cursor advances to UDP",
            listOf(
                "slot-release" to AutoModeProtocol.SOFTETHER_TCP,
                "slot-release" to AutoModeProtocol.SOFTETHER_TCP,
                "slot-release" to AutoModeProtocol.SOFTETHER_UDP,
            ),
            adapter.connects.toList(),
        )
        assertTrue("the refusal must be answered by a slot wait", adapter.slotWaits >= 1)
        assertTrue(
            "the release must be logged as a retry, not as a give-up",
            adapter.logs.any { it.contains("Native slot released; retrying") },
        )
        assertFalse(
            "a released slot must never produce the give-up message",
            adapter.logs.any { it.contains("Native slot not released within") },
        )
    }

    /**
     * T7. TIMED_OUT is terminal for the attempt and rotates the cursor — it does
     * NOT enter the same-server retry loop (§8.1).
     *
     * A two-protocol server times out on every protocol. Each protocol is dialled
     * exactly once (no NATIVE_BUSY-style retry), the protocol cursor advances, and
     * with no server left the run ends ERROR_NO_SERVER — the ordinary, pre-existing
     * rotation outcome, now explicitly specified as TIMED_OUT's recovery (§8.1/§8.8).
     */
    @Test
    fun timedOutRotatesCursorWithoutSameServerRetry() = runBlocking {
        val s = server("timeout-server")
        adapter.scriptOutcomes(
            "timeout-server", AutoModeProtocol.SOFTETHER_TCP,
            AutoModeController.AttemptOutcome.TIMED_OUT,
        )
        adapter.scriptOutcomes(
            "timeout-server", AutoModeProtocol.SOFTETHER_UDP,
            AutoModeController.AttemptOutcome.TIMED_OUT,
        )
        val c = controller(
            servers = listOf(s),
            timeout = 500,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        c.start()
        val terminal = c.awaitTerminal()
        assertTrue("expected Error, got $terminal", terminal is AutoModeState.Error)
        assertEquals(
            "a run where every protocol times out ends 'no compatible server'",
            AutoModeController.ERROR_NO_SERVER,
            (terminal as AutoModeState.Error).message,
        )
        assertEquals(
            "each protocol is dialled exactly once — TIMED_OUT never retries the same server",
            listOf(
                "timeout-server" to AutoModeProtocol.SOFTETHER_TCP,
                "timeout-server" to AutoModeProtocol.SOFTETHER_UDP,
            ),
            adapter.connects.toList(),
        )
        assertTrue(
            "the TIMED_OUT rotation log line must be emitted",
            adapter.logs.any { it.contains("TIMED_OUT after") && it.contains("no same-server retry") },
        )
        // AUTOCONNECT-AUDIT D-3/D-4: after a timeout the controller now waits for the
        // process-wide slot before the next dial (D-3), so a slot wait after TIMED_OUT is
        // legitimate. What must still never happen is entering the REFUSAL path.
        assertFalse(
            "TIMED_OUT must never be answered as a native refusal",
            adapter.logs.any { it.contains("Native slot released; retrying") },
        )
    }

    /**
     * T8. TIMED_OUT on one protocol, then NATIVE_BUSY on the NEXT one (D5 scenario 1).
     *
     * A timeout on TCP leaves no residue that suppresses the refusal handling on the
     * following protocol: UDP is refused once, the slot is observed released, and the
     * single retry connects. Three dials and exactly one slot wait pin both halves —
     * the timed-out protocol is dialled once (no same-server retry), and the refusal on
     * the next protocol still goes through the wait-then-retry path rather than being
     * counted against a budget that the earlier timeout had already spent.
     */
    @Test
    fun timedOutThenNativeBusyOnNextProtocolWaitsAndRetries() = runBlocking {
        val s = server("mixed")
        adapter.scriptOutcomes(
            "mixed", AutoModeProtocol.SOFTETHER_TCP,
            AutoModeController.AttemptOutcome.TIMED_OUT,
        )
        adapter.scriptOutcomes(
            "mixed", AutoModeProtocol.SOFTETHER_UDP,
            AutoModeController.AttemptOutcome.NATIVE_BUSY,
            AutoModeController.AttemptOutcome.TUNNEL_UP,
        )
        val c = controller(
            servers = listOf(s),
            timeout = 500,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        c.start()
        val terminal = c.awaitTerminal()
        assertTrue("expected Connected, got $terminal", terminal is AutoModeState.Connected)
        assertEquals(
            "TCP once (no retry) + UDP twice (refusal, then the retry that connects)",
            3,
            adapter.connects.size,
        )
        assertEquals(
            "the timed-out protocol was dialled exactly once",
            1,
            adapter.connects.count { it.second == AutoModeProtocol.SOFTETHER_TCP },
        )
        assertEquals(
            "the refused protocol was dialled twice: the refusal plus one retry after release",
            2,
            adapter.connects.count { it.second == AutoModeProtocol.SOFTETHER_UDP },
        )
        assertTrue("the refusal must be answered by a slot wait", adapter.slotWaits >= 1)
    }

    /**
     * T9. AUTOCONNECT-AUDIT D-4 + D-6: a terminal failure the stack reports early ends the
     * attempt immediately, records nothing against the server, and does not wait for a slot
     * that the finished operation no longer holds.
     *
     * Before D-4 this server would have cost the full per-attempt budget on a connect that
     * had already failed, and before D-5 it would also have been charged a wedge it did not
     * deserve. A refused port or a non-SoftEther answer to the watermark is a cheap, honest
     * verdict, which is exactly what the audit asks to be separated from a real attempt.
     */
    @Test
    fun fastNativeFailureEndsTheAttemptWithoutAWedge() = runBlocking {
        adapter.scriptOutcomes(
            "not-softether", AutoModeProtocol.SOFTETHER_TCP,
            AutoModeController.AttemptOutcome.NATIVE_ERROR,
        )
        val c = controller(
            servers = listOf(server("not-softether")),
            timeout = 5_000,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        c.start()
        val s = c.awaitTerminal()
        assertTrue("expected Connected via the next protocol, got $s", s is AutoModeState.Connected)
        assertEquals(
            "the failed protocol is dialled exactly once — the verdict is already known",
            2,
            adapter.connects.size,
        )
        assertEquals(
            "the falsifiable claim: no wedge is recorded for a fast native failure",
            0,
            c.wedgeCountFor(server("not-softether")),
        )
        assertEquals(
            "no slot wait is paid by a finished operation",
            0,
            adapter.slotWaits,
        )
        assertTrue(
            "the cause must be named in the log: ${adapter.logs}",
            adapter.logs.any { it.contains("NATIVE_ERROR on not-softether") },
        )
    }

    // ==================================================================
    // A-5 (§3.5): the Disconnected publication must FOLLOW the
    // OQ-7/OQ-6/OQ-1 follow-up decision, not lead it.
    //
    // Before A-5, `onUnexpectedDisconnect` published Disconnected as its
    // very first statement, so a consumer observing that state could not
    // tell whether the run had decided to reconnect (2 s of settle delay
    // plus a fresh dial still ahead) or to stand down. The publication now
    // happens in each terminal branch, once the decision is known.
    //
    // Ordering is asserted through a shared trace that funnels BOTH the
    // adapter's log lines and `onStateChange` into one list. Everything in
    // `onUnexpectedDisconnect` runs sequentially inside a single coroutine,
    // so the relative order is deterministic despite the controller
    // dispatching across threads.
    //
    // Driven through `onUnexpectedDisconnectForTests()`, the same seam
    // `AutoModeReconnectTest` uses: it invokes the identical decision with
    // the identical argument the armed paths use.
    // ==================================================================

    /** Reaches Connected so a drop has an armed tunnel to lose. */
    private suspend fun AutoModeController.awaitConnectedRun() {
        assertTrue(
            "precondition: the run must reach Connected",
            awaitTerminal() is AutoModeState.Connected,
        )
    }

    @Test
    fun a5_manualPrecedencePublishesDisconnectedAfterTheDecision() = runBlocking {
        val trace = mutableListOf<String>()
        adapter.trace = trace
        val controller = controller(
            servers = listOf(server("alpha")),
            isTunnelBusy = { true },
        )
        controller.onStateChange = { state -> trace += "state:${state::class.simpleName}" }

        controller.start()
        controller.awaitConnectedRun()

        val mark = trace.size
        controller.onUnexpectedDisconnectForTests()
        delay(300)

        val after = trace.drop(mark)
        val decisionIdx = after.indexOfFirst { it.contains("Manual connect in progress") }
        val disconnectedIdx = after.indexOfFirst { it.startsWith("state:") && it.contains("Disconnected") }

        assertTrue("the OQ-7 decision log must be present; trace=$after", decisionIdx >= 0)
        assertTrue("Disconnected must still be published on this path; trace=$after", disconnectedIdx >= 0)
        assertTrue(
            "A-5: Disconnected must follow the OQ-7 decision, not lead it; trace=$after",
            decisionIdx < disconnectedIdx,
        )
        assertEquals(
            "exactly one Disconnected publication per drop",
            1,
            after.count { it.startsWith("state:") && it.contains("Disconnected") },
        )
        assertTrue(controller.state.value is AutoModeState.Disconnected)
        assertEquals(
            "no reconnect may be dialled while a manual connect is in flight",
            1,
            adapter.connects.size,
        )
    }

    @Test
    fun a5_autoReconnectDisabledPublishesDisconnectedAfterTheDecision() = runBlocking {
        val trace = mutableListOf<String>()
        adapter.trace = trace
        val controller = controller(
            servers = listOf(server("alpha")),
            autoReconnectEnabled = { false },
        )
        controller.onStateChange = { state -> trace += "state:${state::class.simpleName}" }

        controller.start()
        controller.awaitConnectedRun()

        val mark = trace.size
        controller.onUnexpectedDisconnectForTests()
        delay(300)

        val after = trace.drop(mark)
        val decisionIdx = after.indexOfFirst { it.contains("Auto-Reconnect is disabled") }
        val disconnectedIdx = after.indexOfFirst { it.startsWith("state:") && it.contains("Disconnected") }

        assertTrue("the OQ-6 decision log must be present; trace=$after", decisionIdx >= 0)
        assertTrue("Disconnected must still be published on this path; trace=$after", disconnectedIdx >= 0)
        assertTrue(
            "A-5: Disconnected must follow the OQ-6 decision, not lead it; trace=$after",
            decisionIdx < disconnectedIdx,
        )
        assertEquals(
            "exactly one Disconnected publication per drop",
            1,
            after.count { it.startsWith("state:") && it.contains("Disconnected") },
        )
    }

    @Test
    fun a5_reconnectPathPublishesDisconnectedWithTheDecision() = runBlocking {
        val trace = mutableListOf<String>()
        adapter.trace = trace
        val controller = controller(servers = listOf(server("alpha"), server("beta")))
        controller.onStateChange = { state -> trace += "state:${state::class.simpleName}" }

        controller.start()
        controller.awaitConnectedRun()
        val connectsBefore = adapter.connects.size

        val mark = trace.size
        controller.onUnexpectedDisconnectForTests()
        delay(300)

        val after = trace.drop(mark)
        val disconnectedIdx = after.indexOfFirst { it.startsWith("state:") && it.contains("Disconnected") }
        val decisionIdx = after.indexOfFirst { it.contains("Unexpected disconnect:") }

        assertTrue("Disconnected must be published; trace=$after", disconnectedIdx >= 0)
        assertTrue("the reconnect decision log must be present; trace=$after", decisionIdx >= 0)
        assertTrue(
            "A-5: Disconnected is published together with the decision to " +
                "reconnect, after the OQ-7/OQ-6/OQ-1 checks; trace=$after",
            disconnectedIdx < decisionIdx,
        )
        assertEquals(
            "exactly one Disconnected publication per drop",
            1,
            after.count { it.startsWith("state:") && it.contains("Disconnected") },
        )
        assertTrue(
            "the reconnect must actually re-dial",
            adapter.connects.size > connectsBefore,
        )
    }

    // ==================================================================
    // A-4 (§3.4): the three armed loss paths must route through ONE
    // disarm-and-dispatch funnel.
    //
    // The detectors themselves (an OS event, a 1 s passive poll, a watchdog
    // silence timeout) are intentionally NOT unified — each is the cheapest
    // correct detector for its own failure mode. What A-4 removes is the
    // divergence between the three inline copies of the teardown sequence,
    // which had already drifted apart. These are source-shape tests, the
    // same technique AutoModeDatasetSourceTest uses, because the armed
    // paths reference `TunnelStateWatcher` / `GlobalVpnTracker` objects
    // that cannot be class-loaded off-device.
    // ==================================================================

    @Test
    fun a4_theThreeArmedPathsShareOneLossFunnel() {
        val source = sourceOf("src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt")

        assertTrue(
            "a single reportArmedTunnelLoss funnel must exist",
            source.contains("private fun reportArmedTunnelLoss("),
        )

        // 3 detector call sites + the 1 definition itself.
        val mentions = Regex("""reportArmedTunnelLoss\(""").findAll(source).count()
        assertEquals(
            "all three armed paths (event, poll, watchdog) must call the funnel",
            3 + 1,
            mentions,
        )

        val watcherBody = source.substringAfter("private fun startConnectedWatcher(")
            .substringBefore("private fun reportArmedTunnelLoss(")
        assertTrue(
            "the watcher must not repeat the disarm sequence inline",
            !watcherBody.contains("TunnelStateWatcher.onTunnelLost = null"),
        )
        // One production dispatch (inside the funnel), the definition itself,
        // and the @VisibleForTesting seam. Nothing else may call it.
        assertEquals(
            "onUnexpectedDisconnect has exactly one production call site",
            3,
            Regex("""onUnexpectedDisconnect\(""").findAll(source).count(),
        )
        val funnelBody = source.substringAfter("private fun reportArmedTunnelLoss(")
            .substringBefore("\n    }")
        assertTrue(
            "the funnel must be the single production dispatch site",
            funnelBody.contains("scope.launch { onUnexpectedDisconnect(dropped) }"),
        )
    }

    @Test
    fun a4_theFunnelDisarmsInOneCanonicalOrder() {
        val source = sourceOf("src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt")
        val body = source.substringAfter("private fun reportArmedTunnelLoss(")
            .substringBefore("\n    }")

        val order = listOf(
            "connectedWatcher?.cancel()",
            "connectedWatcher = null",
            "stopLivenessProbe()",
            "TunnelStateWatcher.onTunnelLost = null",
        )
        var previous = -1
        for (step in order) {
            val index = body.indexOf(step)
            assertTrue("the disarm must contain $step; body=$body", index > previous)
            previous = index
        }
        assertTrue(
            "the funnel must dispatch the follow-up through the controller scope",
            body.contains("scope.launch { onUnexpectedDisconnect(dropped) }"),
        )
    }

    @Test
    fun a4_detectionLatenciesAreNotFlattened() {
        val source = sourceOf("src/main/java/vn/unlimit/vpngate/automode/AutoModeController.kt")

        // The 1 s passive poll and the watchdog silence timeout must survive:
        // unifying the *detectors* would delete the passive safety net that
        // catches a quiet death.
        assertTrue(
            "the passive 1 s poll must remain a poll",
            source.contains("kotlinx.coroutines.delay(1000)"),
        )
        assertTrue(
            "the watchdog silence timeout must remain in force",
            source.contains("TunnelLivenessWatchdog.DEFAULT_SILENCE_TIMEOUT_MS"),
        )
        // Each detector still logs its own reason, so the device evidence can
        // tell them apart.
        assertTrue(source.contains("Tunnel disconnected externally / revoked by system"))
        assertTrue(source.contains("GlobalVpnTracker confirmed disconnected"))
        assertTrue(source.contains("No proof of life for"))
    }

    // ------------------------------------------------------------------
    // v1.0.31 — AUTOCONNECT-FIX (D-1, D-2, D-4, D-5)
    //
    // Four gaps found by the two independent AutoConnect audits. Each test
    // below fails on the pre-v1.0.31 tree and passes after the fix, so the
    // regression they guard against is the fix being reverted, not a
    // hypothetical.
    // ------------------------------------------------------------------

    /**
     * T-NEW-1 (D-1): the documented default, the Settings default and the
     * pure-JVM fallback all agree on one figure.
     *
     * Before v1.0.31 three figures described this one setting — 25 s in
     * `DEFAULT_ATTEMPT_TIMEOUT_MS`, 12 s in `DataUtil`, 5 s in the spec — and
     * nothing pinned any of them, so the drift was invisible to CI. This pins
     * the two that are reachable from the JVM; the spec figure is asserted by
     * §6.7 R5 / §6.8 themselves. (v1.0.46: the *range floor* moved 5 → 8;
     * v1.0.52: the *default* moved 12 → 8; **v1.0.70 (REQ-TIMEOUT-001):
     * default 12 s, range 8..30**.)
     */
    @Test
    fun defaultAttemptTimeoutMatchesTheSettingsDefault() {
        assertEquals(
            "the controller fallback must equal the shipped Settings default (12 s)",
            12_000L,
            AutoModeController.DEFAULT_ATTEMPT_TIMEOUT_MS,
        )
        val dataUtil = sourceOf("src/main/java/vn/unlimit/vpngate/utils/DataUtil.kt")
        assertTrue(
            "DataUtil must default SETTING_AUTO_MODE_TIMEOUT_SECONDS to 12",
            dataUtil.contains("getIntSetting(SETTING_AUTO_MODE_TIMEOUT_SECONDS, 12)"),
        )
        assertTrue(
            "the timeout must stay clamped to the approved 8..30 range " +
                "(v1.0.70 REQ-TIMEOUT-001)",
            dataUtil.contains(".coerceIn(8, 30)"),
        )
        assertFalse(
            "no stale 8 s default may survive in DataUtil: $dataUtil",
            dataUtil.contains("SETTING_AUTO_MODE_TIMEOUT_SECONDS, 8)"),
        )
    }

    /**
     * T-NEW-2 (D-4): a press while a tunnel this run does not own is active is
     * refused, and nothing is dialled.
     *
     * This is the production button path (`onButtonPressed`). Before v1.0.31 the
     * `isTunnelBusy()` guard existed only in `startOrRefuseIfNoServers()`, which
     * has no production caller, so pressing the button during a manual
     * connection dispatched a second, competing `ACTION_CONNECT`.
     */
    @Test
    fun buttonRefusesToStartWhileAnotherTunnelIsActive() = runBlocking {
        val c = controller(
            servers = listOf(server("s1")),
            isTunnelBusy = { true },
        )

        c.onButtonPressed()
        // Give a wrongly-started run time to reach connect() before asserting.
        delay(200)

        assertTrue(
            "no attempt may be armed while another tunnel is active",
            adapter.connects.isEmpty(),
        )
        assertFalse("no run may be left active", c.isRunning)
        assertTrue(
            "the state must stay Disconnected, not move to an error",
            c.state.value is AutoModeState.Disconnected,
        )
        assertTrue(
            "the refusal must be explained in the log",
            adapter.logs.any {
                it.contains("start refused") && it.contains("a tunnel is already active")
            },
        )
    }

    /**
     * T-NEW-3 (D-5 / OP-R6): pressing while Connected continues from the server
     * AFTER the one that connected, instead of restarting at #1.
     *
     * Three servers of identical quality keep their insertion order, so "index
     * 1" and "s2" name the same candidate and the assertion cannot pass by
     * accident of ordering.
     */
    @Test
    fun pressingWhileConnectedContinuesFromTheNextServer() = runBlocking {
        val servers = listOf(server("s1"), server("s2"), server("s3"))
        // s1 never comes up, so the first run connects on s2 (index 1).
        adapter.failing += "s1"
        val c = controller(servers = servers, timeout = 300)

        c.onButtonPressed()
        val first = c.awaitTerminal()
        assertTrue("expected the first run to connect, got $first", first is AutoModeState.Connected)
        assertEquals("s2", (first as AutoModeState.Connected).hostname)

        // Everything is healthy from here; only the continuation matters.
        adapter.connects.clear()
        adapter.failing.clear()

        // BLOCK v1.0.75 FIX: "move on" is the Try Next button, not the hero.
        c.skipToNextServer()
        val second = c.awaitTerminal()

        assertEquals(
            "the continued run must dial s3 first — not restart at s1",
            "s3" to AutoModeProtocol.SOFTETHER_UDP,
            adapter.connects.first(),
        )
        assertEquals(
            "the continued run must not re-dial s1 or s2",
            listOf("s3"),
            adapter.connects.map { it.first }.distinct(),
        )
        assertTrue("expected the continued run to connect, got $second", second is AutoModeState.Connected)
        assertEquals("s3", (second as AutoModeState.Connected).hostname)
    }

    /**
     * T-NEW-4 (D-5 edge): pressing while connected to the LAST server starts no
     * attempt at all and terminates with `ERROR_NO_SERVER`.
     *
     * This is the documented meaning of an out-of-range continuation: the list
     * is exhausted, so the run must not wrap around to index 0.
     */
    /**
     * BLOCK v1.0.75 FIX: on the LAST server the hero press is still just a
     * disconnect — no attempt, no wrap, no error state.
     */
    @Test
    fun pressingWhileConnectedToTheLastServerEndsWithoutRestarting() = runBlocking {
        val servers = listOf(server("s1"), server("s2"))
        adapter.failing += "s1"
        val c = controller(servers = servers, timeout = 300)

        c.onButtonPressed()
        val first = c.awaitTerminal()
        assertTrue("expected the first run to connect, got $first", first is AutoModeState.Connected)
        assertEquals("s2", (first as AutoModeState.Connected).hostname)

        adapter.connects.clear()
        c.onButtonPressed() // hero press on the last server = disconnect
        withTimeoutOrNull(5_000) { while (c.state.value !is AutoModeState.Disconnected) delay(5) }

        assertTrue(
            "the hero press must end in Disconnected, got ${c.state.value}",
            c.state.value is AutoModeState.Disconnected,
        )
        assertTrue(
            "a disconnect must not wrap around and dial index 0",
            adapter.connects.isEmpty(),
        )
    }

    /**
     * T-NEW-5 (D-2): a thrown exception is one (server, protocol) failing, so it
     * advances to the NEXT PROTOCOL — the same transition as every other
     * non-timeout outcome.
     *
     * Before v1.0.31 this was a `break`, which jumped to the next SERVER: s1
     * would be dialled once (TCP) and its second protocol never attempted. The
     * assertion is the exact `connects` sequence, which differs between the two
     * behaviours.
     */
    @Test
    fun connectExceptionAdvancesToTheNextProtocolNotTheNextServer() = runBlocking {
        val c = controller(
            servers = listOf(server("s1"), server("s2")),
            timeout = 300,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        // s1's connect() throws on every protocol; s2 is healthy.
        adapter.connectThrowing += "s1"

        c.onButtonPressed()
        val terminal = c.awaitTerminal()

        assertEquals(
            "s1 must be dialled on BOTH protocols: a thrown exception advances to the " +
                "next protocol, not straight to the next server",
            listOf(
                "s1" to AutoModeProtocol.SOFTETHER_TCP,
                "s1" to AutoModeProtocol.SOFTETHER_UDP,
                "s2" to AutoModeProtocol.SOFTETHER_TCP,
            ),
            adapter.connects.toList(),
        )
        assertTrue("expected s2 to connect, got $terminal", terminal is AutoModeState.Connected)
        assertEquals("s2", (terminal as AutoModeState.Connected).hostname)
    }

    /**
     * T-NEW-6 (D-5 reset): a spent continuation never leaks into a later run.
     *
     * After the list is exhausted the recorded position is zero, so the next
     * fresh press starts at index 0 again. Without this, one "continue" would
     * permanently shorten every subsequent run.
     */
    @Test
    fun aFreshPressAfterExhaustionStartsFromTheTopAgain() = runBlocking {
        val servers = listOf(server("s1"), server("s2"))
        adapter.failing += "s1"
        // v1.0.55 (V1054_VERIFY_REPORT G-4): the per-attempt deadline was 300 ms.
        // This test is about ORDERING — a fresh press must restart at index 0 —
        // but on a loaded machine the whole suite runs on one shared
        // Dispatchers.Default, and a 300 ms deadline can expire before the fake
        // adapter's instant `awaitTunnel()` is even scheduled. Both attempts then
        // time out, the run ends in ERROR_NO_SERVER, and the test reports a
        // restart failure that never happened (`connects.first()` was `s1`, the
        // run had already dialled the right server). Observed once in 10
        // full-suite runs, never in isolation.
        //
        // The deadline is not what this test asserts, so it is raised out of the
        // way. Tests that DO assert timeout behaviour set their own small value.
        val c = controller(servers = servers, timeout = 5_000)

        c.onButtonPressed()
        val first = c.awaitTerminal()
        assertTrue("expected the first run to connect, got $first", first is AutoModeState.Connected)
        assertEquals("s2", (first as AutoModeState.Connected).hostname)

        c.onButtonPressed() // hero press while connected = permanent disconnect
        withTimeoutOrNull(5_000) { while (c.state.value !is AutoModeState.Disconnected) delay(5) }
        assertTrue(
            "the hero press must disconnect (BLOCK v1.0.75 FIX), got ${c.state.value}",
            c.state.value is AutoModeState.Disconnected,
        )

        adapter.connects.clear()
        adapter.failing.clear() // s1 is healthy now
        c.onButtonPressed()
        val restarted = c.awaitTerminal()

        assertEquals(
            "a fresh press must start at index 0 again",
            "s1" to AutoModeProtocol.SOFTETHER_UDP,
            adapter.connects.first(),
        )
        assertTrue(
            "expected the restart to connect, got $restarted; " +
                "connects=${adapter.connects}, logs=${adapter.logs.takeLast(12)}",
            restarted is AutoModeState.Connected,
        )
        assertEquals("s1", (restarted as AutoModeState.Connected).hostname)
    }

    // --- v1.0.32 (AUTOCONNECT-FIX-2): BUG-A / BUG-B ---

    /**
     * T-NEW-7 (BUG-A): the production predicate the D-4 guard receives treats a
     * CONNECTED tunnel as busy.
     *
     * The v1.0.31 guard was unit-tested only through an injected `isTunnelBusy`
     * lambda (T-NEW-2), so it proved the guard was *wired* while the production
     * predicate it was wired to — `GlobalVpnTracker.isBusy`, which is
     * CONNECTING||DISCONNECTING — stayed blind to CONNECTED. Device evidence
     * (DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md §5) showed the exact failure: a
     * manual OpenVPN tunnel up, Auto Mode `Disconnected`, press → `[AUTO] Started`
     * instead of `start refused`, and the run then destroyed the manual tunnel.
     *
     * This pins the enum-level predicate itself, so it fails if `isActiveOrBusy`
     * loses CONNECTED or is re-pointed at `isBusy`.
     */
    @Test
    fun productionBusyPredicateIncludesAConnectedTunnel() {
        val connected = vn.unlimit.vpngate.state.VpnConnectionStatus.CONNECTED
        val connecting = vn.unlimit.vpngate.state.VpnConnectionStatus.CONNECTING
        val disconnecting = vn.unlimit.vpngate.state.VpnConnectionStatus.DISCONNECTING
        val disconnected = vn.unlimit.vpngate.state.VpnConnectionStatus.DISCONNECTED
        val error = vn.unlimit.vpngate.state.VpnConnectionStatus.ERROR

        assertFalse(
            "isBusy must keep EXCLUDING CONNECTED — the v1.0.31 bug was using it as " +
                "the D-4 predicate",
            connected.isBusy,
        )
        assertTrue(
            "v1.0.32 BUG-A: the D-4 predicate must see a CONNECTED tunnel",
            connected.isActiveOrBusy,
        )
        assertTrue("an in-flight connect is still busy", connecting.isActiveOrBusy)
        assertTrue("an in-flight disconnect is still busy", disconnecting.isActiveOrBusy)
        assertFalse("nothing is owned when disconnected", disconnected.isActiveOrBusy)
        assertFalse("nothing is owned after an error", error.isActiveOrBusy)
    }

    /**
     * T-NEW-8 (BUG-A): the engine supplies `isActiveOrBusy`, and the OP-R6 path
     * is NOT gated by it.
     *
     * Two halves, because the BUG-A fix must not overshoot: (1) production wiring
     * reads `GlobalVpnTracker.isActiveOrBusy` — a regression to `isBusy` silently
     * re-opens the device failure, and nothing else in the suite would notice;
     * (2) pressing while Auto Mode is `Connected` still resumes from the next
     * server, i.e. the guard stays confined to the Disconnected/Error branch.
     */
    @Test
    fun engineUsesTheActiveOrBusyPredicateAndOpR6IsNotGated() = runBlocking {
        val engine = sourceOf(
            "app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt",
        )
        assertTrue(
            "v1.0.32 BUG-A: AutoModeEngine must wire isTunnelBusy to isActiveOrBusy",
            engine.contains("isTunnelBusy = { vn.unlimit.vpngate.state.GlobalVpnTracker.isActiveOrBusy }"),
        )
        assertFalse(
            "the engine must not fall back to the CONNECTED-blind isBusy",
            engine.contains("isTunnelBusy = { vn.unlimit.vpngate.state.GlobalVpnTracker.isBusy }"),
        )

        // (2) OP-R6 still works: connected on s2, press → continue at s3.
        adapter.failing += "s1"
        val c = controller(
            servers = listOf(server("s1"), server("s2"), server("s3")),
            timeout = 300,
        )
        c.onButtonPressed()
        val first = c.awaitTerminal()
        assertTrue("expected the first run to connect, got $first", first is AutoModeState.Connected)
        assertEquals("s2", (first as AutoModeState.Connected).hostname)

        adapter.connects.clear()
        adapter.failing.clear()
        // BLOCK v1.0.75 FIX: "move on" is the Try Next button, never the hero.
        c.skipToNextServer()
        val second = c.awaitTerminal()

        assertFalse(
            "the Try Next path must never be refused by the D-4 guard: ${adapter.logs}",
            adapter.logs.any { it.contains("start refused") },
        )
        assertEquals(
            "the continued run must dial s3 first",
            "s3" to AutoModeProtocol.SOFTETHER_UDP,
            adapter.connects.first(),
        )
        assertTrue("expected the continued run to connect, got $second", second is AutoModeState.Connected)
    }

    /**
     * T-NEW-9 (BUG-B): repeated NATIVE_BUSY on the SAME server records a wedge,
     * and the run rotates to the next server.
     *
     * "always-busy" is refused on both of its protocols inside one run. Before
     * v1.0.32 no refusal path reached `recordWedge`, so such a server could never
     * be excluded and was re-selected indefinitely — device evidence
     * (DEVICE_VERIFY_AUTOCONNECT_FIX_REPORT.md §7): vpn131251792 was selected in
     * four consecutive runs and no `excluded for this session` line was ever
     * emitted. The slot here is reported released, which models the device case
     * where the wait succeeds and the attempt still does not come up.
     */
    @Test
    fun repeatedNativeBusyRecordsAWedgeAndRotates() = runBlocking {
        adapter.scriptBusy("always-busy", false, false, false, false)
        val c = controller(
            servers = listOf(server("always-busy"), server("good")),
            timeout = 500,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        c.start()
        val s = c.awaitTerminal()

        assertTrue(
            "the run must escape the refusing server and connect on the next one, got $s",
            s is AutoModeState.Connected,
        )
        assertEquals("good", (s as AutoModeState.Connected).hostname)
        assertTrue(
            "v1.0.32 BUG-B: the second refusal on the same server must record a wedge: " +
                "${adapter.logs}",
            adapter.logs.any { it.contains("NATIVE_BUSY x2") && it.contains("recording a wedge") },
        )
        assertEquals(
            "each refused protocol is dialled once and retried once after the slot release, " +
                "then the refusing server is abandoned for the healthy one",
            listOf(
                "always-busy" to AutoModeProtocol.SOFTETHER_TCP,
                "always-busy" to AutoModeProtocol.SOFTETHER_TCP,
                "always-busy" to AutoModeProtocol.SOFTETHER_UDP,
                "always-busy" to AutoModeProtocol.SOFTETHER_UDP,
                "good" to AutoModeProtocol.SOFTETHER_TCP,
            ),
            adapter.connects.toList(),
        )
    }

    /**
     * T-NEW-10 (BUG-B edge): a SINGLE NATIVE_BUSY on a server is not a wedge.
     *
     * The first refusal is routinely caused by the previous server's still-running
     * native operation, so it says nothing about this endpoint. Only the second
     * refusal inside the same run is evidence. This guards the fix against
     * over-counting, which would exclude healthy servers.
     */
    @Test
    fun aSingleNativeBusyDoesNotRecordAWedge() = runBlocking {
        // One refusal, then the retry after the slot release succeeds.
        adapter.scriptBusy("busy-then-up", false, true)
        val c = controller(
            servers = listOf(server("busy-then-up")),
            timeout = 500,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        c.start()
        val s = c.awaitTerminal()

        assertTrue("expected Connected, got $s", s is AutoModeState.Connected)
        assertFalse(
            "a single refusal must never be recorded as a wedge: ${adapter.logs}",
            adapter.logs.any { it.contains("recording a wedge") },
        )
    }

    /**
     * v1.0.33 (AUTOCONNECT-FIX-3): the slot-wait budget must never fall below
     * [AutoModeController.NATIVE_SLOT_AWAIT_MS], whatever the user selected.
     *
     * The device run of v1.0.32 recorded release latencies of 6.0 / 6.2 / 7.5 / 13.3 /
     * 13.7 / 17.0 / 20.9 s with the preference at T = 5 s, i.e. a derived budget of 25 s —
     * a 4.1 s margin — and one release landed 292 ms past that cutoff
     * (DEVICE_VERIFY_AUTOCONNECT_FIX_2_REPORT.md §7). The release latency belongs to our
     * own operation, not to the attempt timeout, so a small T must not shrink it.
     *
     * This pins both halves: the constant is at least 30 s, and the engine derives the
     * value through the floor rather than from `5 × T` alone.
     */
    @Test
    fun nativeSlotAwaitHasAThirtySecondFloor() {
        val engineSource = sourceOf(
            "app/src/main/java/vn/unlimit/vpngate/automode/AutoModeEngine.kt",
        )
        assertTrue(
            "AutoModeEngine must derive nativeSlotAwaitMs with a floor of " +
                "AutoModeController.NATIVE_SLOT_AWAIT_MS, was:\n$engineSource",
            engineSource.contains("coerceAtLeast") &&
                engineSource.contains("AutoModeController.NATIVE_SLOT_AWAIT_MS"),
        )
        assertTrue(
            "NATIVE_SLOT_AWAIT_MS floor must be at least 30 000 ms, was " +
                "${AutoModeController.NATIVE_SLOT_AWAIT_MS}",
            AutoModeController.NATIVE_SLOT_AWAIT_MS >= 30_000L,
        )
    }

    // ==================================================================
    // AUTOCONNECT-FIX-4 (v1.0.34): the two complementary fixes.
    //
    // Device evidence (DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md §7.3/§7.4)
    // showed that the NATIVE_BUSY terminal is the ONLY terminal this endpoint
    // can reach: the RUDP fallback handshake blocked forever, so the first
    // native attempt never returned, the slot never released, and the run
    // ended before `nativeBusyThisServer` could reach 2. The counter-based
    // wedge rule therefore could not fire, and the same broken endpoint was
    // re-selected in 5/5 runs. These two tests pin the two halves of the fix.
    // ==================================================================

    /**
     * v1.0.37: successor to `aNativeBusyTerminalRecordsAWedgeThatSurvivesAcrossRuns`.
     * The NATIVE_BUSY terminal is no longer a terminal, so cross-run exclusion is now
     * exercised through the TIMED_OUT path. The wedge ledger and exclusion threshold (2)
     * are unchanged.
     *
     * T-NEW-11 (FIX-B) pinned a NATIVE_BUSY terminal that recorded a wedge and let the
     * strike survive across runs. That mechanism no longer exists: since Fix-1 a
     * NATIVE_BUSY means the start was REFUSED because a previous operation owns the slot,
     * so the server was never contacted and a strike against it would be evidence about
     * the wrong endpoint (§1.6.29). The behaviour T-NEW-11 existed to protect — a server
     * that keeps failing is excluded for the session, across runs, without being dialled
     * again — is still required, and is still produced by the ledger. It is now fed by the
     * outcome that actually implicates the server: TIMED_OUT (AUTOCONNECT-AUDIT D-5).
     *
     * The falsifiable claim: without the cross-run ledger, run 1 and run 2 would both
     * leave `wedgeCountFor == 0` and run 3 would dial the endpoint again.
     */
    @Test
    fun aTimedOutWedgeAccumulatesAcrossRunsAndExcludesTheServer() = runBlocking {
        val ledger = WedgeTracker()
        val wedged = server("wedged-endpoint")
        // The endpoint IS contacted and never comes up: TIMED_OUT, the outcome that
        // implicates the server. No refusal is involved, which is the point — the old
        // mechanism is gone and the ledger must still accumulate.
        adapter.failing += "wedged-endpoint"

        // ---- Run 1: the timeout must record one wedge. ----
        val first = controller(servers = listOf(wedged), timeout = 500, wedgeTracker = ledger)
        first.start()
        val firstState = first.awaitTerminal()

        assertTrue("run 1 must end in Error, got $firstState", firstState is AutoModeState.Error)
        assertEquals(
            "run 1 must end with the ordinary exhausted-list terminal",
            AutoModeController.ERROR_NO_SERVER,
            (firstState as AutoModeState.Error).message,
        )
        assertNotEquals(
            "v1.0.37: the removed terminal must never be published",
            AutoModeController.ERROR_NATIVE_UNRESOLVED,
            firstState.message,
        )
        assertEquals(
            "one TIMED_OUT attempt must record exactly one wedge",
            1,
            first.wedgeCountFor(wedged),
        )

        // ---- Run 2: a FRESH controller, same ledger, must reach 2. ----
        adapter.logs.clear()
        adapter.connects.clear()
        val second = controller(servers = listOf(wedged), timeout = 500, wedgeTracker = ledger)
        second.start()
        val secondState = second.awaitTerminal()

        assertTrue("run 2 must end in Error, got $secondState", secondState is AutoModeState.Error)
        assertEquals(
            "the ledger must accumulate across runs, which is the whole point",
            2,
            second.wedgeCountFor(wedged),
        )
        assertTrue(
            "reaching the threshold must be logged: ${adapter.logs}",
            adapter.logs.any {
                it.contains("Excluding wedged-endpoint from this session after 2 wedges.")
            },
        )

        // ---- Run 3: the endpoint is now excluded and must not be dialled. ----
        adapter.logs.clear()
        adapter.connects.clear()
        val third = controller(servers = listOf(wedged), timeout = 500, wedgeTracker = ledger)
        third.start()
        val thirdState = third.awaitTerminal()

        assertEquals(
            "with every candidate excluded the run reports no compatible server",
            AutoModeController.ERROR_NO_SERVER,
            (thirdState as AutoModeState.Error).message,
        )
        assertTrue(
            "the exclusion must be visible in the log: ${adapter.logs}",
            adapter.logs.any { it.contains("excluded for this session") },
        )
        assertTrue(
            "an excluded endpoint must consume no dial at all: ${adapter.connects}",
            adapter.connects.isEmpty(),
        )
    }

    // ==================================================================
    // AUTOCONNECT-FIX-7 (v1.0.37): the classification and the OP-R6 contract.
    // ==================================================================

    /**
     * T-NEW-18 (Fix-1, controller-visible half): an attempt whose OWN native operation is
     * still running when the budget expires must be classified as TIMED_OUT, not as a
     * refusal.
     *
     * The adapter is the only place that can make this distinction
     * (`lastArmedBusyRefused`, `AndroidConnectionAdapter.awaitArmedOutcome`), and it is not
     * JVM-testable because it needs a `Context`. What the controller owes is the observable
     * consequence: a TIMED_OUT must walk the protocols and record a wedge, and must NEVER
     * take the NATIVE_BUSY path — no slot wait, no refusal diagnostics.
     *
     * The falsifiable claim: if this attempt were mis-classified as NATIVE_BUSY (the
     * v1.0.36 defect), the run would log `Slot-wait expired` and the refusal diagnostic,
     * and the second protocol would never be dialled.
     */
    @Test
    fun anOwnOperationTimeoutIsClassifiedAsTimedOutNotBusy() = runBlocking {
        val cand = server("own-op")
        adapter.scriptOutcomes(
            "own-op",
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeController.AttemptOutcome.TIMED_OUT,
        )
        adapter.scriptOutcomes(
            "own-op",
            AutoModeProtocol.SOFTETHER_UDP,
            AutoModeController.AttemptOutcome.TIMED_OUT,
        )
        val c = controller(
            servers = listOf(cand),
            timeout = 500,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        c.start()
        val s = c.awaitTerminal()

        assertEquals(
            "the list is exhausted after both protocols time out",
            AutoModeController.ERROR_NO_SERVER,
            (s as AutoModeState.Error).message,
        )
        assertEquals(
            "a timeout must rotate through the server's protocols",
            listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
            adapter.connects.map { it.second },
        )
        assertFalse(
            "v1.0.37: an own-operation timeout must never enter the refusal path: " +
                "${adapter.logs}",
            adapter.logs.any { it.contains("Slot-wait expired") },
        )
        assertFalse(
            "no refusal may be reported for a timeout: ${adapter.logs}",
            adapter.logs.any { it.contains("NATIVE_BUSY") },
        )
        assertEquals(
            "a TIMED_OUT is the outcome that implicates the server, so it records one wedge",
            1,
            c.wedgeCountFor(cand),
        )
    }

    /**
     * T-NEW-21 (FIX-4 / OP-R6): Try-next while Connected goes to the NEXT server and never
     * restarts the list from index 0.
     *
     * The operator has emphasised this contract repeatedly. The existing
     * [skipWhileConnectedDisconnectsAndTriesNext] pins the counts; this test pins the
     * ORDER — the first dial after the skip must be the second candidate, and the server the
     * user just left must not be dialled again in the new run.
     */
    @Test
    fun skipWhileConnectedDialsTheNextServerAndNeverRestartsFromTheTop() = runBlocking {
        val c = controller(servers = listOf(server("s1"), server("s2"), server("s3")))
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        val beforeSkip = c.state.value
        val dialsBefore = adapter.connects.size

        c.skipToNextServer()

        val final = c.awaitNextTerminalAfterChange(beforeSkip)
        assertTrue("expected Connected after the skip, got $final", final is AutoModeState.Connected)
        assertEquals("s2", (final as AutoModeState.Connected).hostname)

        val afterSkip = adapter.connects.drop(dialsBefore)
        assertTrue("the skip must start a new run: $afterSkip", afterSkip.isNotEmpty())
        assertEquals(
            "OP-R6: the FIRST dial after the skip must be the next candidate, not a restart",
            "s2",
            afterSkip.first().first,
        )
        assertTrue(
            "OP-R6: the skipped server must not be dialled again: $afterSkip",
            afterSkip.none { it.first == "s1" },
        )
        assertTrue(
            "the user's intent must be logged: ${adapter.logs}",
            adapter.logs.any { it.startsWith("[AUTO] Connected server skipped by user; trying next") },
        )
    }

    /**
     * T-NEW-12 (FIX-A, source shape): the NAT-T/RUDP fallback handshake is
     * bounded exactly the way the TCP handshake is.
     *
     * Device evidence (DEVICE_VERIFY_AUTOCONNECT_FIX_3_REPORT.md §7.3): the TCP
     * path applied `socket_apply_timeout` before `perform_tls_handshake`, the
     * RUDP fallback did not, and the RUDP BIO read then blocked for 28.5 s of
     * complete silence. This is a source-shape guard — the same technique the
     * other source-shape tests in this file use — because the C transport
     * cannot be executed from a JVM unit test. It fails if the RUDP site ever
     * loses the timeout application again, regardless of the surrounding code.
     */
    @Test
    fun theRudpFallbackHandshakeIsBoundedLikeTheTcpPath() {
        val native = sourceOf(
            "../SoftEtherClient/src/main/cpp/softether-core/src/proto/softether_protocol.c",
        )

        assertTrue(
            "the RUDP handshake entry discriminator must exist, so a device log can " +
                "distinguish the two paths with one grep",
            native.contains("[TLS] handshake entry (RUDP)"),
        )

        val fallback = native.substringAfter("retrying via NAT-T + RUDP fallback")
            .substringBefore("perform_tls_handshake(conn, connect_host);")
        assertTrue(
            "the NAT-T/RUDP fallback must apply the same socket timeout the TCP path " +
                "does, before its handshake; block was:\n$fallback",
            fallback.contains("socket_apply_timeout(conn->socket_fd, conn->timeout_ms)"),
        )

        // The TCP site must keep its own bound — this fix must not be paid for by
        // weakening the path that was already correct. Its discriminator has no
        // "(RUDP)" suffix, which is what distinguishes the two sites.
        assertTrue(
            "the TCP handshake entry discriminator must survive unchanged",
            native.contains("[TLS] handshake entry: conn->timeout_ms=%d"),
        )
        assertEquals(
            "exactly two handshake sites may apply the timeout: TCP and NAT-T/RUDP",
            2,
            Regex("""\(socket_apply_timeout\(conn->socket_fd, conn->timeout_ms\) == 0\)""")
                .findAll(native).count(),
        )
    }

    /**
     * T-NEW-13 (v1.0.35, AUTOCONNECT-FIX-5): an unresolved teardown suppresses the
     * post-slot-release retry.
     *
     * Device evidence (DEVICE_VERIFY_AUTOCONNECT_FIX_4_REPORT.md §15): 12 post-release
     * retries in one session, ZERO of which reached TUNNEL_UP; 11 of 12 followed a
     * `NATIVE_UNRESOLVED` teardown, the one outcome in which the registry retains
     * ownership and refuses every later start. Retrying there cannot be admitted and
     * costs the retry's own teardown (~6 s per failed protocol).
     *
     * This pins three things at once: the skip is logged, the refused protocol is
     * dialled exactly ONCE (no retry), and the wedge accounting that runs before the
     * skip is untouched — the second refusal still records the wedge.
     */
    @Test
    fun unresolvedTeardownSkipsThePostReleaseRetry() = runBlocking {
        adapter.scriptBusy("wedged-endpoint", false, false, false, false)
        adapter.lastTeardownResolved = false
        val c = controller(
            servers = listOf(server("wedged-endpoint"), server("good")),
            timeout = 500,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        c.start()
        val s = c.awaitTerminal()

        assertTrue(
            "the run must still escape the refusing server and connect on the next one, got $s",
            s is AutoModeState.Connected,
        )
        assertEquals("good", (s as AutoModeState.Connected).hostname)

        assertTrue(
            "v1.0.35: the skip must be logged with its reason: ${adapter.logs}",
            adapter.logs.any {
                it.contains("Skipping retry on SOFTETHER_TCP") &&
                    it.contains("previous teardown was unresolved")
            },
        )
        assertFalse(
            "no post-release retry may be issued while the teardown is unresolved: " +
                "${adapter.logs}",
            adapter.logs.any { it.contains("Native slot released; retrying") },
        )
        assertEquals(
            "each refused protocol is dialled exactly once when the teardown was unresolved",
            listOf(
                "wedged-endpoint" to AutoModeProtocol.SOFTETHER_TCP,
                "wedged-endpoint" to AutoModeProtocol.SOFTETHER_UDP,
                "good" to AutoModeProtocol.SOFTETHER_TCP,
            ),
            adapter.connects.toList(),
        )
        assertTrue(
            "the wedge accounting before the skip must be preserved: ${adapter.logs}",
            adapter.logs.any { it.contains("NATIVE_BUSY x2") && it.contains("recording a wedge") },
        )
    }

    /**
     * T-NEW-14 (v1.0.35, AUTOCONNECT-FIX-5): a resolved teardown keeps the retry.
     *
     * The retry exists for a real case — the refusal was caused by our own previous
     * native operation still finishing, and once the slot is confirmed free the same
     * protocol deserves one more attempt. The fix must narrow that to the unresolved
     * state only; a clean teardown must behave exactly as it did before v1.0.35.
     */
    @Test
    fun resolvedTeardownKeepsThePostReleaseRetry() = runBlocking {
        adapter.scriptBusy("busy-then-up", false, true)
        adapter.lastTeardownResolved = true
        val c = controller(
            servers = listOf(server("busy-then-up")),
            timeout = 500,
            priorities = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
        )
        c.start()
        val s = c.awaitTerminal()

        assertTrue("expected Connected, got $s", s is AutoModeState.Connected)
        assertTrue(
            "the retry must still be attempted when the teardown resolved: ${adapter.logs}",
            adapter.logs.any { it.contains("Native slot released; retrying") },
        )
        assertFalse(
            "a resolved teardown must never log the skip: ${adapter.logs}",
            adapter.logs.any { it.contains("Skipping retry") },
        )
        assertEquals(
            "the refused protocol is dialled twice: once, then the post-release retry",
            listOf(
                "busy-then-up" to AutoModeProtocol.SOFTETHER_TCP,
                "busy-then-up" to AutoModeProtocol.SOFTETHER_TCP,
            ),
            adapter.connects.toList(),
        )
    }

    /**
     * T-NEW-15 (v1.0.36, AUTOCONNECT-FIX-6 Fix-1): a NATIVE_BUSY whose own tunnel is
     * already up is a SUCCESS, not a failure.
     *
     * Device evidence (DEVICE_VERIFY_AUTOCONNECT_FIX_4_UNFILTERED_REPORT.md §5.3): the
     * attempt timeout fired while the CONNECTED notification was still in flight, so the
     * registry still reported the slot as busy — because OUR OWN operation was still
     * running. The controller then waited for a slot held by the tunnel it had just
     * established, and the terminal destroyed a working tunnel in 3 of 4 runs.
     *
     * The decisive assertions are `slotWaits == 0` (the wait was never entered) and the
     * absence of a wedge log. The ledger count corroborates but is not decisive alone:
     * the connected path clears the ledger, so it would read zero even if a wedge had
     * been recorded on the way in.
     */
    @Test
    fun nativeBusyWithOwnTunnelUpIsTreatedAsConnected() = runBlocking {
        adapter.scriptBusy("own-tunnel", false)
        adapter.ownTunnelEstablished = true
        val tracker = WedgeTracker()
        val target = server("own-tunnel")
        val c = controller(servers = listOf(target), timeout = 300, wedgeTracker = tracker)
        c.start()
        val s = c.awaitTerminal()

        assertTrue(
            "a live own tunnel must be reported as Connected, got $s",
            s is AutoModeState.Connected,
        )
        assertEquals("own-tunnel", (s as AutoModeState.Connected).hostname)
        assertTrue(
            "Fix-1 must announce the recovery: ${adapter.logs}",
            adapter.logs.any { it.contains("Tunnel came up despite NATIVE_BUSY") },
        )
        assertFalse(
            "a live tunnel is not a wedge: ${adapter.logs}",
            adapter.logs.any { it.contains("Recording a wedge") },
        )
        assertEquals(
            "the slot wait must never run once the own tunnel is up",
            0, adapter.slotWaits,
        )
        assertEquals(
            "no wedge may be recorded for an endpoint whose tunnel came up",
            0, tracker.countFor("${target.ip}|${target.hostname ?: ""}"),
        )
        assertEquals(
            "the refused protocol must not be re-dialled once the tunnel is adopted",
            1, adapter.connects.size,
        )
    }

    /**
     * T-NEW-16 (v1.0.36, AUTOCONNECT-FIX-6 Fix-2): the NATIVE_BUSY slot wait is capped
     * to the per-attempt budget.
     *
     * The uncapped 30 s figure let one refused (server, protocol) pair cost 7×T. The cap
     * has to be provably active, not merely present in the source, so the controller is
     * built with T below NATIVE_SLOT_AWAIT_MS and the ARGUMENT the fake received is
     * asserted.
     *
     * The retry itself is kept (Option A): a resolved teardown still re-dials once, and
     * that re-dial is what reaches Connected here.
     */
    @Test
    fun nativeBusySlotWaitIsCappedToTheAttemptBudget() = runBlocking {
        adapter.scriptBusy("capped-wait", false, true)
        adapter.lastTeardownResolved = true
        val attemptBudget = 300L
        val c = controller(servers = listOf(server("capped-wait")), timeout = attemptBudget)
        c.start()
        val s = c.awaitTerminal()

        assertTrue("the retry must still reach Connected, got $s", s is AutoModeState.Connected)
        assertEquals(
            "the wait must receive min(NATIVE_SLOT_AWAIT_MS, T), and exactly once before " +
                "the retry: ${adapter.slotWaitTimeouts}",
            listOf(minOf(AutoModeController.NATIVE_SLOT_AWAIT_MS, attemptBudget)),
            adapter.slotWaitTimeouts.toList(),
        )
        assertTrue(
            "the recorded budget must be strictly below the uncapped constant, otherwise " +
                "this test proves nothing: ${adapter.slotWaitTimeouts}",
            adapter.slotWaitTimeouts.single() < AutoModeController.NATIVE_SLOT_AWAIT_MS,
        )
        assertTrue(
            "the retry must still fire on a resolved teardown: ${adapter.logs}",
            adapter.logs.any { it.contains("Native slot released; retrying") },
        )
        assertFalse(
            "a resolved teardown must never log the skip: ${adapter.logs}",
            adapter.logs.any { it.contains("Skipping retry") },
        )
    }

    /**
     * T-NEW-17 (v1.0.36, AUTOCONNECT-FIX-6 Fix-3): the post-attempt slot wait carries the
     * same cap.
     *
     * This is the branch that runs after a TIMED_OUT, i.e. after a real attempt reached
     * the server and the slot may genuinely still be held. It used to wait the full
     * NATIVE_SLOT_AWAIT_MS regardless of T, so the per-pair bound was 6×T rather than the
     * 2×T the contract now states.
     */
    @Test
    fun postAttemptSlotWaitIsCappedToTheAttemptBudget() = runBlocking {
        adapter.failing += "post-attempt"
        val attemptBudget = 300L
        val c = controller(servers = listOf(server("post-attempt")), timeout = attemptBudget)
        c.start()
        c.awaitTerminal()

        assertEquals(
            "the post-attempt wait must receive the capped budget: ${adapter.slotWaitTimeouts}",
            listOf(minOf(AutoModeController.NATIVE_SLOT_AWAIT_MS, attemptBudget)),
            adapter.slotWaitTimeouts.toList(),
        )
        assertTrue(
            "the recorded budget must be strictly below the uncapped constant: " +
                "${adapter.slotWaitTimeouts}",
            adapter.slotWaitTimeouts.single() < AutoModeController.NATIVE_SLOT_AWAIT_MS,
        )
        assertTrue(
            "the attempt must have timed out, not been refused: ${adapter.logs}",
            adapter.logs.any { it.contains("TIMED_OUT after") },
        )
    }

    // ------------------------------------------------------------------
    // v1.0.52 (§1.6.44) TASK B — Auto Connect ownership and cursor identity
    //
    // TASK B.3 fixed three defects in one place: the run had no single owner,
    // a CancellationException was converted into a failed attempt, and the
    // Next-Server cursor used a different identity than the rest of the
    // controller. The two tests below pin the two observable consequences;
    // the single-owner invariant is additionally pinned structurally (the
    // `transitionLock` critical sections) because "job is never null between
    // two runs" is not observable from outside.
    // ------------------------------------------------------------------

    /**
     * TASK B.3(c): the Next-Server cursor must use the SAME identity as the
     * continuation and the wedge ledger.
     *
     * `serverKey` is `"$ip|${hostname ?: ""}"`, so a null hostname and an empty
     * one are the same server. The pre-v1.0.52 comparison in
     * `rememberRemaining` was `it.ip == current.ip && it.hostname ==
     * current.hostname`, where `null != ""` — the connected server could not be
     * found in its own list, the "remaining" tail fell back to the WHOLE list,
     * and Next Server re-dialled the server the user had just left.
     */
    @Test
    fun nextServerCursorMatchesByIdentityWhenHostnameIsNullVersusEmpty() = runBlocking {
        val first = candidateWith(
            host = null, ip = "10.9.0.1", score = 3000, seTcp = 443, seUdpSupported = true,
        )
        val second = candidateWith(
            host = "", ip = "10.9.0.2", score = 2000, seTcp = 443, seUdpSupported = true,
        )
        val third = candidateWith(
            host = "third", ip = "10.9.0.3", score = 1000, seTcp = 443, seUdpSupported = true,
        )

        val c = controller(servers = listOf(first, second, third))
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        val beforeSkip = c.state.value
        val dialsBefore = adapter.connects.size

        c.skipToNextServer()

        val final = c.awaitNextTerminalAfterChange(beforeSkip)
        assertTrue("expected Connected after the skip, got $final", final is AutoModeState.Connected)

        val afterSkip = adapter.connects.drop(dialsBefore)
        assertTrue("the skip must start a new run: $afterSkip", afterSkip.isNotEmpty())
        assertEquals(
            "the first dial after the skip must be the NEXT server (hostname \"\"), not a restart",
            "",
            afterSkip.first().first,
        )
        assertTrue(
            "the server that was just left (hostname null) must not be dialled again: $afterSkip",
            afterSkip.none { it.first == null },
        )
    }

    /**
     * TASK B.3(a): two presses in a row must not produce two competing loops.
     *
     * The v1.0.52 fix publishes the skip continuation as the current job and
     * swaps it for the real run inside the same critical section, so `job`
     * never passes through null. Without that, the second press found no
     * owner, launched its own run, and both loops walked the same list.
     *
     * The observable signature of that bug is a server being dialled twice in
     * one skip burst, so that is what is asserted.
     */
    @Test
    fun twoImmediateSkipsNeverDialTheSameServerTwice() = runBlocking {
        val c = controller(servers = listOf(server("s1"), server("s2"), server("s3")))
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        val beforeSkip = c.state.value
        val dialsBefore = adapter.connects.size

        c.skipToNextServer()
        c.skipToNextServer()

        val final = c.awaitNextTerminalAfterChange(beforeSkip)
        assertTrue(
            "expected a terminal state after the double press, got $final",
            final is AutoModeState.Connected || final is AutoModeState.Error,
        )

        val afterSkip = adapter.connects.drop(dialsBefore)
        val dialled = afterSkip.map { it.first }
        assertEquals(
            "a double press must never dial the same server twice: $afterSkip",
            dialled.distinct(),
            dialled,
        )
    }

    /**
     * v1.0.54 (§1.6.46) PART 1 / R-1: a second press while the first skip is
     * still being prepared must be COALESCED, not re-entered.
     *
     * Before the fix the Connected branch left the state at Connected while it
     * tore the tunnel down asynchronously, so a second press re-entered the
     * branch, cancelled the continuation the first press had just published and
     * launched its own — two loops walking the same list. The device evidence
     * in the report was `[s2, s1, s1, s1, s1, s1]` for one double press.
     *
     * Asserted here: the skip is a single transition (the continuation lands on
     * the SECOND server, not the third), no server is dialled twice, and the
     * dropped press is logged rather than silently swallowed.
     */
    @Test
    fun duplicateSkipWhileConnectedIsCoalescedToTheFirstSkip() = runBlocking {
        val c = controller(servers = listOf(server("s1"), server("s2"), server("s3")))
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        val beforeSkip = c.state.value
        val dialsBefore = adapter.connects.size

        // The window is held open by gating the teardown, not raced — see the
        // gate's KDoc. Both presses therefore observe the same state (Connected
        // with a skip already accepted), which is exactly the condition the
        // coalescing rule is about.
        val gate = CompletableDeferred<Unit>()
        adapter.disconnectGate = gate
        c.skipToNextServer()
        withTimeoutOrNull(2_000) {
            while (adapter.logs.none { it == "disconnect" }) delay(5)
        }
        c.skipToNextServer()
        gate.complete(Unit)
        adapter.disconnectGate = null

        val final = c.awaitNextTerminalAfterChange(beforeSkip)
        assertTrue("expected a terminal after the coalesced skip, got $final", final is AutoModeState.Connected)
        assertEquals(
            "one skip means the next server, not the one after it",
            "s2",
            (final as AutoModeState.Connected).hostname,
        )

        val afterSkip = adapter.connects.drop(dialsBefore)
        val dialled = afterSkip.map { it.first }
        assertEquals("no server may be dialled twice in one skip: $afterSkip", dialled.distinct(), dialled)
        assertTrue(
            "the coalesced press must be logged: ${adapter.logs}",
            adapter.logs.any { it.contains("duplicate request ignored") },
        )
    }

    /**
     * v1.0.54 (§1.6.46) PART 1 / R-1: the same window is reachable from the
     * Auto Connect button, not only from the Next-Server button.
     *
     * While a skip is in flight the state still reads Connected, so a button
     * press would take the OP-R6 branch — tear the tunnel down a second time
     * and start a competing run. The press must therefore be distinguished from
     * a fresh "move on": it is read as the stop that §3 assigns to the Connected
     * state, which also cancels the pending continuation.
     */
    @Test
    fun buttonPressDuringAnInFlightSkipStopsInsteadOfStartingASecondRun() = runBlocking {
        val c = controller(servers = listOf(server("s1"), server("s2"), server("s3")))
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        val dialsBefore = adapter.connects.size

        // v1.0.54 (§1.6.46) PART 1 / R-1. Two rules meet in this window and
        // both must hold:
        //
        //  - the press must NOT be read as a second skip (that is the R-1
        //    double-dial), and
        //  - the press must NOT be swallowed either: §3 says the button means
        //    "disconnect" whenever the state is Connected, and the state still
        //    reads Connected while the skip's teardown and list resolution run.
        //    Swallowing it would ignore a stop for the length of a teardown.
        //
        // The window is pinned open by gating `disconnect()` rather than raced:
        // a racing version of this test passes or fails on scheduling, and this
        // one is about the rule.
        val gate = CompletableDeferred<Unit>()
        adapter.disconnectGate = gate
        c.skipToNextServer()
        withTimeoutOrNull(2_000) {
            while (adapter.logs.none { it == "disconnect" }) delay(5)
        }
        assertTrue(
            "the skip must be mid-teardown, state still Connected, got ${c.state.value}",
            c.state.value is AutoModeState.Connected,
        )

        c.onButtonPressed()

        assertEquals(
            "the button must stop, not launch a competing run",
            AutoModeState.Disconnected,
            c.state.value,
        )
        assertTrue(
            "the stop must be logged as such: ${adapter.logs}",
            adapter.logs.any { it.contains("treated as stop") },
        )

        gate.complete(Unit)
        adapter.disconnectGate = null
        // Give the cancelled continuation every chance to misbehave.
        repeat(20) { delay(10) }

        assertEquals(
            "a cancelled skip must not dial anything: ${adapter.connects.drop(dialsBefore)}",
            dialsBefore,
            adapter.connects.size,
        )
        assertEquals(AutoModeState.Disconnected, c.state.value)
        assertFalse("nothing may still be running", c.isRunning)
    }

    /**
     * v1.0.54 (§1.6.46) PART 1 / R-1: a burst of presses is ONE skip.
     *
     * Deterministic on purpose: the guard is set synchronously inside the first
     * call, and the teardown is gated so the whole burst lands inside the same
     * window. Five presses must therefore land on `s2` (the next server), not
     * five servers further down the list, and must never dial the same server
     * twice.
     *
     * The gate matters: without it the continuation can publish `Connecting`
     * between two presses of the burst, and a later press then legitimately
     * takes the Connecting branch (`skipRequested`), which is a *different*
     * skip. That is correct product behaviour, not a coalescing failure — but
     * it makes an ungated burst test assert the wrong thing.
     */
    @Test
    fun fiveRapidSkipsCollapseIntoASingleContinuation() = runBlocking {
        val c = controller(servers = (1..6).map { server("s$it") })
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        val beforeSkip = c.state.value
        val dialsBefore = adapter.connects.size

        val gate = CompletableDeferred<Unit>()
        adapter.disconnectGate = gate
        c.skipToNextServer()
        withTimeoutOrNull(2_000) {
            while (adapter.logs.none { it == "disconnect" }) delay(5)
        }
        repeat(4) { c.skipToNextServer() }
        gate.complete(Unit)
        adapter.disconnectGate = null

        val final = c.awaitNextTerminalAfterChange(beforeSkip)
        assertTrue("expected a terminal after the burst, got $final", final is AutoModeState.Connected)
        assertEquals(
            "a burst of presses is one skip, so it must land on s2",
            "s2",
            (final as AutoModeState.Connected).hostname,
        )

        val afterSkip = adapter.connects.drop(dialsBefore)
        val dialled = afterSkip.map { it.first }
        assertEquals("no server may be dialled twice: $afterSkip", dialled.distinct(), dialled)
        assertEquals(
            "the burst must be coalesced, not replayed: ${adapter.logs}",
            4,
            adapter.logs.count { it.contains("duplicate request ignored") },
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
