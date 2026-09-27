package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VpnM Phase 5 — genuine proof of life for a connected tunnel.
 *
 * ## Why this exists and why it is not a byte counter
 *
 * The stacks' own keepalive bookkeeping cannot be reused as proof of life.
 * Verified in this repository:
 *
 * - SoftEther: `KeepAliveManager.recordReceived()` is called from the receive
 *   loop whenever `softether_receive_batch` returns 0. In
 *   `packet_handler.c:softether_fill_recv_queue_locked` a plain `poll()`
 *   timeout also returns 0, so on an idle-but-healthy tunnel the method fires
 *   roughly every 100 ms regardless of the network. It proves the local loop
 *   is turning, not that the server is reachable. That also makes
 *   `KeepAliveManager.isConnectionDead()` structurally unable to reach its
 *   threshold.
 * - OpenVPN: `VpnStatus.updateByteCount` is driven by the management
 *   interface's periodic `bytecount` command, which the local management
 *   thread answers on its own; it does not require the encrypted path to
 *   deliver anything to the peer.
 * - SSTP (kittoku OSC): exposes only the `ROOT_STATE` preference flip, which
 *   is itself a passive one-shot notification.
 *
 * None of them answers the question the watchdog asks: *can the tunnel still
 * carry traffic?*
 *
 * ## What this probes
 *
 * A TCP connect to a stable, well-known anycast endpoint, opened from the
 * app's default network. Because Android routes a normal socket through the
 * active VpnService tun interface, a successful connect+close is a full round
 * trip: tun -> encrypted transport -> VPN server -> internet and back. A
 * black-holed path cannot produce it.
 *
 * The probe is therefore protocol-agnostic: identical for SoftEther (TCP and
 * NAT-T/UDP), OpenVPN, and SSTP, which is exactly the coverage the watchdog
 * needs and exactly what the per-stack signals lack.
 *
 * ## Cost control
 *
 * The probe is not a heartbeat the protocol already pays for, so it is kept
 * cheap and infrequent: [intervalMs] between probes and a short
 * [probeTimeoutMs] connect timeout. Nothing is sent over the established
 * connection beyond the handshake, and between probes this object performs no
 * I/O at all, so an idle user pays one small connect per interval.
 *
 * ## Failure semantics
 *
 * A single failed probe is **not** a loss. Failures are simply not recorded as
 * proof; the watchdog applies its own hysteresis. This class only answers
 * "alive since last call". Before the first probe completes the answer is
 * `false`, and the watchdog treats "no proof yet" as *not lost* rather than
 * dead, so a slow first probe cannot cause a spurious disconnect.
 */
class TunnelLivenessProbe(
    private val scope: CoroutineScope,
    private val probeHost: String = DEFAULT_PROBE_HOST,
    private val probePort: Int = DEFAULT_PROBE_PORT,
    private val probeTimeoutMs: Int = DEFAULT_PROBE_TIMEOUT_MS,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    /**
     * Wall-clock time of the last successful round trip, or
     * [TunnelLivenessWatchdog.NO_PROOF] before the first one succeeds.
     */
    @Volatile
    private var lastProofAtMs: Long = TunnelLivenessWatchdog.NO_PROOF

    /** The value of [lastProofAtMs] already handed to the watchdog. */
    @Volatile
    private var consumedProofAtMs: Long = TunnelLivenessWatchdog.NO_PROOF

    private val running = AtomicBoolean(false)
    private var job: Job? = null

    /** Serialises probes so a slow attempt cannot overlap the next interval. */
    private val probeLock = Mutex()

    /** True when a round trip has succeeded at least once since [reset]. */
    val hasProof: Boolean
        get() = lastProofAtMs != TunnelLivenessWatchdog.NO_PROOF

    /**
     * True when the tunnel has shown a sign of life *since this method was
     * last called*. This is the shape `AutoModeController` expects: it
     * consumes the signal once per sample, and the watchdog turns the gap into
     * a loss decision.
     */
    fun consumeProof(): Boolean {
        val last = lastProofAtMs
        if (last == TunnelLivenessWatchdog.NO_PROOF) return false
        if (last == consumedProofAtMs) return false
        consumedProofAtMs = last
        return true
    }
    /**
     * Start probing for [protocol]. Idempotent: a call while already running
     * is ignored, so a reconnect cannot start two overlapping loops.
     */
    fun start(protocol: AutoModeProtocol) {
        if (!running.compareAndSet(false, true)) return
        lastProofAtMs = TunnelLivenessWatchdog.NO_PROOF
        consumedProofAtMs = TunnelLivenessWatchdog.NO_PROOF
        job = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    if (probeOnce()) {
                        lastProofAtMs = System.currentTimeMillis()
                    }
                    delay(intervalMs)
                }
            } finally {
                running.set(false)
            }
        }
    }

    /** Stop probing and forget all proof. */
    fun stop() {
        job?.cancel()
        job = null
        running.set(false)
        lastProofAtMs = TunnelLivenessWatchdog.NO_PROOF
        consumedProofAtMs = TunnelLivenessWatchdog.NO_PROOF
    }

    /**
     * One connect+close round trip. Returns false on any failure, which is
     * the normal state while the network is down and must not throw.
     */
    private suspend fun probeOnce(): Boolean = probeLock.withLock {
        withContext(Dispatchers.IO) {
            if (probeHost.isBlank() || probePort <= 0 || probePort > 65535) {
                return@withContext false
            }
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.soTimeout = probeTimeoutMs
                    // connect() is the round trip; close() releases immediately.
                    socket.connect(InetSocketAddress(probeHost, probePort), probeTimeoutMs)
                    true
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    companion object {
        /**
         * Cloudflare anycast front door on 443. Chosen because it is anycast
         * (no single point of failure), served on 443 in every region, and
         * needs no request payload to establish the connection.
         */
        const val DEFAULT_PROBE_HOST = "1.1.1.1"

        /** TLS/HTTPS port, open on the probe host. */
        const val DEFAULT_PROBE_PORT = 443

        /** Long enough to ride out a slow mobile link, short enough to retry. */
        const val DEFAULT_PROBE_TIMEOUT_MS = 3_000

        /**
         * Must satisfy `intervalMs + probeTimeoutMs < silenceTimeoutMs`, so a
         * healthy tunnel always has time to deliver the next proof before the
         * watchdog's silence window expires. The watchdog's own hysteresis then
         * adds a further `threshold` samples of margin, so a single slow probe
         * on a good tunnel cannot be mistaken for a dead one.
         */
        const val DEFAULT_INTERVAL_MS = 8_000L
    }
}
