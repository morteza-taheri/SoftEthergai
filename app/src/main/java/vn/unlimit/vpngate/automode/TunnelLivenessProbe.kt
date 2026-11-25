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
     * BLOCK v1.0.81 (review finding #2): every endpoint a probe may use.
     *
     * A single hard-coded target made the whole watchdog hostage to one
     * address: if the VPN exit filtered `1.1.1.1:443`, no probe could ever
     * succeed, and because the watchdog is seeded with proof when a tunnel
     * comes up, EVERY tunnel would be declared lost roughly
     * `silence + hysteresis` after connecting. That is a false disconnect on a
     * perfectly healthy tunnel, which is precisely the symptom being fixed.
     *
     * Several independent anycast/public resolvers are tried in order and the
     * probe succeeds on the first that completes a round trip, so no single
     * filtered address can condemn a live tunnel. The cost on a healthy tunnel
     * is unchanged: the first target answers and the rest are never touched.
     *
     * When a caller overrides [probeHost] (the unit tests point at a local
     * listener, diagnostics may point somewhere specific) only that target is
     * used — an override means "probe exactly this", never "probe this and
     * also the internet".
     */
    private val targets: List<Pair<String, Int>> =
        if (probeHost == DEFAULT_PROBE_HOST && probePort == DEFAULT_PROBE_PORT) {
            DEFAULT_TARGETS
        } else {
            listOf(probeHost to probePort)
        }

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

    /**
     * BLOCK v1.0.81 (review finding #6): identifies the CURRENT loop.
     *
     * `running` alone could not express this. `stop()` cancels the job and
     * clears the flag, but the cancelled loop still runs its `finally` block
     * later — on another thread, after `start()` may already have launched a
     * replacement. That stale `finally` then cleared `running` while the new
     * loop was live, so a subsequent `start()` saw `false`, acquired the flag
     * and launched a SECOND concurrent loop against the same socket budget.
     *
     * Each `start()` takes a new generation; a loop only clears `running` if
     * its own generation is still the current one.
     */
    @Volatile
    private var generation: Long = 0

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
        val myGeneration = ++generation
        lastProofAtMs = TunnelLivenessWatchdog.NO_PROOF
        consumedProofAtMs = TunnelLivenessWatchdog.NO_PROOF
        job = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && myGeneration == generation) {
                    val alive = probeOnce()
                    if (alive) {
                        lastProofAtMs = System.currentTimeMillis()
                    }
                    // BLOCK v1.0.81 (review finding #1): a failed probe retries
                    // quickly instead of waiting a whole interval.
                    //
                    // The old code slept [intervalMs] unconditionally, so one
                    // probe that timed out created a gap of
                    // `timeout + interval` (3 s + 8 s = 11 s) before the next
                    // attempt could even start, and up to
                    // `timeout + interval + timeout` (14 s) before the next
                    // success could be recorded. With a 15 s silence window
                    // that left almost no margin, and on a slow-but-working
                    // link a single timed-out probe could push the gap past
                    // the window and get a healthy tunnel torn down — the
                    // exact "connects, then drops for no reason" behaviour.
                    //
                    // Retrying after [PROBE_RETRY_MS] bounds the gap caused by
                    // one failure to `timeout + retry` (5 s), which the 30 s
                    // silence window absorbs with a wide margin.
                    delay(if (alive) intervalMs else PROBE_RETRY_MS)
                }
            } finally {
                // Only the current loop may clear the flag (see [generation]).
                if (myGeneration == generation) running.set(false)
            }
        }
    }

    /** Stop probing and forget all proof. */
    fun stop() {
        generation++
        job?.cancel()
        job = null
        running.set(false)
        lastProofAtMs = TunnelLivenessWatchdog.NO_PROOF
        consumedProofAtMs = TunnelLivenessWatchdog.NO_PROOF
    }

    /**
     * One connect+close round trip, trying each target in turn.
     *
     * Returns false on any failure, which is the normal state while the network
     * is down and must not throw.
     */
    private suspend fun probeOnce(): Boolean = probeLock.withLock {
        withContext(Dispatchers.IO) {
            for ((host, port) in targets) {
                if (host.isBlank() || port <= 0 || port > 65535) continue
                try {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.soTimeout = probeTimeoutMs
                        // connect() is the round trip; close() releases immediately.
                        socket.connect(InetSocketAddress(host, port), probeTimeoutMs)
                        return@withContext true
                    }
                } catch (_: Throwable) {
                    // Try the next target: one filtered endpoint is not a
                    // verdict about the tunnel.
                }
            }
            false
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
         * BLOCK v1.0.81: how soon a FAILED probe is retried.
         *
         * Short on purpose. A failure is the moment the silence window is
         * burning down, so waiting a full [DEFAULT_INTERVAL_MS] after one
         * timeout is what used to let a healthy tunnel be declared dead.
         */
        const val PROBE_RETRY_MS = 2_000L

        /**
         * Independent targets, tried in order, success on the first round trip.
         *
         * All are anycast/public resolvers reachable on 443 or 53 from
         * effectively any network, and none is operated by the VPN provider, so
         * a probe through the tunnel is a genuine end-to-end path test. Several
         * are listed so that one filtered address cannot make every tunnel look
         * dead (review finding #2).
         */
        val DEFAULT_TARGETS: List<Pair<String, Int>> = listOf(
            "1.1.1.1" to 443,
            "8.8.8.8" to 443,
            "9.9.9.9" to 443,
            "1.0.0.1" to 443,
        )

        /**
         * Must satisfy `intervalMs + probeTimeoutMs < silenceTimeoutMs`, so a
         * healthy tunnel always has time to deliver the next proof before the
         * watchdog's silence window expires. The watchdog's own hysteresis then
         * adds a further `threshold` samples of margin.
         *
         * BLOCK v1.0.81: the failure path is covered too — with
         * [PROBE_RETRY_MS] the worst case caused by one timed-out probe is
         * `probeTimeoutMs + PROBE_RETRY_MS` = 5 s, comfortably inside the
         * watchdog's 30 s window.
         */
        const val DEFAULT_INTERVAL_MS = 8_000L
    }
}
