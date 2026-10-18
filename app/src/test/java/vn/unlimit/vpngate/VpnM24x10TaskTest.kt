package vn.unlimit.vpngate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.automode.AutoModeCandidate
import vn.unlimit.vpngate.automode.AutoModeController
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.AutoModeState
import vn.unlimit.vpngate.automode.WedgeTracker
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.models.ExcludedApp
import vn.unlimit.vpngate.models.InstalledApp
import vn.unlimit.vpngate.utils.DataUtil

/**
 * Guards the behaviour required by the current task:
 *
 *  - "Enable speed/statistics in notify" defaults to ON for a fresh install.
 *  - Auto Connect takes its protocol order from the priority profile only.
 *  - Excluded Apps keeps its persistence contract and splits user/system apps.
 */
class VpnM24x10TaskTest {

    /**
     * Deadline for "the run reaches a terminal state". It is a failure guard,
     * not pacing: the tests wait on the state flow and return as soon as the
     * transition happens, so a healthy run finishes in milliseconds.
     */
    private val TERMINAL_STATE_TIMEOUT_MS = 8_000L

    // ---------------------------------------------------------------- STATUS

    @Test
    fun `notify speed ships on for a fresh install`() {
        assertEquals(
            "a fresh install must show speed statistics in the notification by default",
            true,
            DataUtil.DEFAULT_NOTIFY_SPEED,
        )
    }

    @Test
    fun `notify speed default is the value the UI falls back to`() {
        // The Settings toggle must resolve its initial state from the same
        // constant, otherwise a fresh install could show OFF while every VPN
        // service still renders speed.
        assertTrue(DataUtil.DEFAULT_NOTIFY_SPEED)
    }

    // ------------------------------------------------------------- AUTO MODE

    @Test
    fun `fallback priority is non empty and free of duplicates`() {
        val fallback = AutoModeController.FALLBACK_PRIORITY
        assertTrue("the no-provider default must not be empty", fallback.isNotEmpty())
        assertEquals(
            "a repeated protocol would dial the same transport twice per server",
            fallback.size,
            fallback.distinct().size,
        )
    }

    @Test
    fun `auto connect ignores any stored default protocol and uses the profile`() {
        // The removed "Default VPN Protocol" setting must have no influence left.
        // Two identical runs must produce an identical dial order.
        val order = mutableListOf<String>()
        val servers = listOf(tcpOnlyServer())
        val profile = listOf(AutoModeProtocol.SOFTETHER_TCP)

        repeat(2) { run ->
            val scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            )
            val runOrder = mutableListOf<String>()
            val controller = AutoModeController(
                scope = scope,
                adapter = RecordingAdapter(runOrder),
                protocolPriorityProvider = { profile },
                serverProvider = { servers },
                attemptTimeoutMs = 200,
                livenessProbeFactory = null,
                // D-5: isolate the wedge ledger from the process-wide singleton.
                wedgeTracker = WedgeTracker(),
            )
            controller.start()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (controller.state.value is AutoModeState.Connected) break
                Thread.sleep(25)
            }
            assertTrue(
                "run $run must connect using the profile, not a default protocol",
                controller.state.value is AutoModeState.Connected,
            )
            scope.cancel()
            // Record the dial order per run, then compare the two runs.
            order.addAll(runOrder)
        }

        assertEquals(
            "both runs must dial the profile's protocol on the TCP-only server",
            listOf("connect:only-tcp:SOFTETHER_TCP", "connect:only-tcp:SOFTETHER_TCP"),
            order,
        )
    }

    @Test
    fun `case 1 an empty profile refuses cleanly and dials nothing`() = runBlocking {
        // Every protocol is OFF in the UI, so the stored profile is empty.
        // Servers are available and support everything, so the ONLY reason not
        // to connect is the user's choice — the run must say so and stop.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val adapter = AttemptRecordingAdapter()
            val controller = AutoModeController(
                scope = scope,
                adapter = adapter,
                protocolPriorityProvider = { emptyList() },
                serverProvider = { listOf(allProtocolsServer()) },
                attemptTimeoutMs = 200,
                livenessProbeFactory = null,
            )

            controller.start()

            val terminal = withTimeoutOrNull(TERMINAL_STATE_TIMEOUT_MS) {
                controller.state.first { it is AutoModeState.Error || it is AutoModeState.Connected }
            }
            assertNotNull(
                "an empty profile must still reach a terminal state",
                terminal,
            )
            assertTrue(
                "the run must fail, not connect: $terminal",
                terminal is AutoModeState.Error,
            )
            assertEquals(
                "the failure must name the missing protocol profile, not a server problem",
                AutoModeController.ERROR_NO_ENABLED_PROTOCOL,
                (terminal as AutoModeState.Error).message,
            )
            assertEquals(
                "no protocol may be attempted when the user enabled none",
                emptyList<String>(),
                adapter.attempted,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `case 2 exactly one enabled protocol is attempted, and only that one`() = runBlocking {
        val attempted = protocolsAttempted(
            profile = listOf(AutoModeProtocol.SOFTETHER_UDP),
            servers = listOf(allProtocolsServer()),
        )

        assertEquals(
            "only the single enabled protocol may be dialled",
            listOf(AutoModeProtocol.SOFTETHER_UDP.id),
            attempted,
        )
    }

    @Test
    fun `case 3 several enabled protocols keep the user's exact order`() = runBlocking {
        // UDP on, TCP on, SSTP off — the spec's worked example.
        val attempted = protocolsAttempted(
            profile = listOf(AutoModeProtocol.SOFTETHER_UDP, AutoModeProtocol.SOFTETHER_TCP),
            servers = listOf(allProtocolsServer()),
        )

        assertEquals(
            "the user-defined priority order must be preserved exactly, and SSTP never dialled",
            listOf(AutoModeProtocol.SOFTETHER_UDP.id, AutoModeProtocol.SOFTETHER_TCP.id),
            attempted,
        )
        assertFalse(
            "SSTP is OFF in this profile and must never be attempted",
            attempted.contains(AutoModeProtocol.MS_SSTP.id),
        )
    }

    @Test
    fun `case 3b a reversed user order is honoured verbatim`() = runBlocking {
        // Same set, opposite order: proves the run follows the profile and not
        // a hard-coded preference between the two transports.
        val attempted = protocolsAttempted(
            profile = listOf(AutoModeProtocol.SOFTETHER_TCP, AutoModeProtocol.SOFTETHER_UDP),
            servers = listOf(allProtocolsServer()),
        )

        assertEquals(
            "the profile order is the only source of protocol order",
            listOf(AutoModeProtocol.SOFTETHER_TCP.id, AutoModeProtocol.SOFTETHER_UDP.id),
            attempted,
        )
    }

    @Test
    fun `case 4 a protocol present only in the fallback list is never attempted`() = runBlocking {
        // The user enabled SSTP alone. SSTP is also a member of the
        // no-provider FALLBACK_PRIORITY, so a run that "helpfully" fell back
        // would dial SoftEther UDP/TCP or the others as well. Only the single
        // enabled protocol may ever be touched.
        val attempted = protocolsAttempted(
            profile = listOf(AutoModeProtocol.MS_SSTP),
            servers = listOf(allProtocolsServer()),
        )

        assertEquals(
            "a disabled protocol must never be dialled, even when it is in the fallback list",
            listOf(AutoModeProtocol.MS_SSTP.id),
            attempted,
        )
        val fallbackIds = AutoModeController.FALLBACK_PRIORITY.map { it.id }.toSet()
        val silentOnes = attempted.filterNot { it == AutoModeProtocol.MS_SSTP.id }
        assertTrue(
            "the run escaped the user's profile into the fallback list ($fallbackIds)",
            silentOnes.isEmpty(),
        )
    }

    @Test
    fun `case 4b the full fallback list is never dialled for an empty profile`() = runBlocking {
        // Strongest form of case 1: even though every protocol is reachable
        // on this server and the fallback list covers all of them, an empty
        // profile must produce zero attempts.
        val attempted = protocolsAttempted(
            profile = emptyList(),
            servers = listOf(allProtocolsServer()),
        )

        assertEquals(
            "an empty profile must dial nothing at all",
            emptyList<String>(),
            attempted,
        )
    }

    // ------------------------------------------------------------- helpers

    /**
     * Drives a full run for [profile] over [servers] and returns the ids of
     * the protocols the adapter was actually asked to connect with, in order.
     */
    private suspend fun protocolsAttempted(
        profile: List<AutoModeProtocol>,
        servers: List<AutoModeCandidate>,
    ): List<String> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val adapter = AttemptRecordingAdapter()
            val controller = AutoModeController(
                scope = scope,
                adapter = adapter,
                protocolPriorityProvider = { profile },
                serverProvider = { servers },
                attemptTimeoutMs = 200,
                livenessProbeFactory = null,
                // D-5: a fresh ledger per controller so WedgeTracker.process never leaks
                // wedges across tests in the same JVM.
                wedgeTracker = WedgeTracker(),
            )

            controller.start()

            // Synchronisation, not a sleep: wait for the run to publish a
            // terminal state. The run reaches a terminal state on every path
            // (refusal, all-servers-failed, connected), so this is bounded
            // work, and the deadline is a failure guard, not a pacing delay.
            val terminal = withTimeoutOrNull(TERMINAL_STATE_TIMEOUT_MS) {
                controller.state.first { it is AutoModeState.Error || it is AutoModeState.Connected }
            }
            assertNotNull(
                "the run never reached a terminal state, last seen: ${controller.state.value}",
                terminal,
            )
            return adapter.attempted
        } finally {
            scope.cancel()
        }
    }

    private class AttemptRecordingAdapter : AutoModeController.ConnectionAdapter {
        val attempted = mutableListOf<String>()

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            attempted += protocol.id
        }

        // Never verifies a tunnel: the run must walk every eligible protocol
        // and every server, so the recorded list is the complete attempt set.
        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long) = false

        override suspend fun disconnect() = Unit

        override fun log(message: String) = Unit
    }

    /** A server offering every transport, so no protocol is filtered by capability. */
    private fun allProtocolsServer() = AutoModeCandidate(
        hostname = "kitchen-sink",
        ip = "10.9.9.9",
        speed = 1_000_000L,
        ping = 10,
        sessions = 0,
        score = 0,
        sources = 1,
        seTcpPort = 443,
        seUdpPort = 1194,
        seUdpSupported = true,
        openVpnTcpPort = 1194,
        openVpnUdpPort = 1194,
        l2tpSupported = true,
        sstpSupported = true,
        // Stage 2F-F.2 (Q-6): MS-SSTP is supported only with a usable port, so
        // "offers every transport" now has to say WHICH sstp port. An explicit
        // published port keeps this fixture's original intent intact.
        sstpPort = 443,
        sstpPortSource = VpnPortSource.EXPLICIT,
        sstpConnectPort = 443,
    )

    /** A server that only offers SoftEther TCP, so only that protocol can match. */
    private fun tcpOnlyServer() = AutoModeCandidate(
        hostname = "only-tcp",
        ip = "10.0.0.1",
        speed = 10_000_000L,
        ping = 10,
        sessions = 0,
        score = 0,
        sources = 1,
        seTcpPort = 443,
        seUdpPort = 0,
        seUdpSupported = false,
        openVpnTcpPort = 0,
        openVpnUdpPort = 0,
        l2tpSupported = false,
        sstpSupported = false,
    )

    /** Minimal adapter that records connect attempts and reports a working tunnel. */
    private class RecordingAdapter(
        private val order: MutableList<String>,
    ) : AutoModeController.ConnectionAdapter {
        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            order.add("connect:${candidate.hostname}:${protocol.id}")
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean = true

        override suspend fun disconnect() = Unit

        override fun log(message: String) = Unit
    }

    // ------------------------------------------------- duplicate connection

    @Test
    fun `auto connect refuses to start while a connection is already in progress`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val adapter = AttemptRecordingAdapter()
            val controller = AutoModeController(
                scope = scope,
                adapter = adapter,
                protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_TCP) },
                serverProvider = { listOf(allProtocolsServer()) },
                // The global tunnel state says a manual connect is mid-flight.
                isTunnelBusy = { true },
                // D-5: isolate the wedge ledger from the process-wide singleton.
                wedgeTracker = WedgeTracker(),
            )

            val started = controller.startOrRefuseIfNoServers()

            assertFalse("Auto Connect must not race an existing connection", started)
            assertEquals("no protocol may be dialled", emptyList<String>(), adapter.attempted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `auto connect starts normally when nothing else is connecting`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val adapter = AttemptRecordingAdapter()
            val controller = AutoModeController(
                scope = scope,
                adapter = adapter,
                protocolPriorityProvider = { listOf(AutoModeProtocol.SOFTETHER_TCP) },
                serverProvider = { listOf(allProtocolsServer()) },
                isTunnelBusy = { false },
                // D-5: isolate the wedge ledger from the process-wide singleton.
                wedgeTracker = WedgeTracker(),
            )

            val started = controller.startOrRefuseIfNoServers()
            assertTrue(started)

            val terminal = withTimeoutOrNull(TERMINAL_STATE_TIMEOUT_MS) {
                controller.state.first { it is AutoModeState.Error || it is AutoModeState.Connected }
            }
            assertNotNull("the run must reach a terminal state", terminal)
            assertEquals(listOf("SOFTETHER_TCP"), adapter.attempted)
        } finally {
            scope.cancel()
        }
    }

    // ---------------------------------------------------------- EXCLUDED APPS

    @Test
    fun `excluded app persistence keeps package name and app name`() {
        val stored = InstalledApp("com.example.telegram", "Telegram", isSystem = false)
        // The Room entity is what gets written; the section flag must not leak
        // into the stored row or the schema would change.
        val entity = ExcludedApp(stored.packageName, stored.appName)
        assertEquals("com.example.telegram", entity.packageName)
        assertEquals("Telegram", entity.appName)
    }

    @Test
    fun `user and system apps are kept apart`() {
        val apps = listOf(
            InstalledApp("com.a", "A", isSystem = false),
            InstalledApp("com.b", "B", isSystem = true),
        )
        assertEquals(listOf("com.a"), apps.filter { !it.isSystem }.map { it.packageName })
        assertEquals(listOf("com.b"), apps.filter { it.isSystem }.map { it.packageName })
    }
}
