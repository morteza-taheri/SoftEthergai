package vn.unlimit.vpngate.network

import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.ProbeTransport
import vn.unlimit.vpngate.automode.ReachabilityTarget
import vn.unlimit.vpngate.automode.probeTransport
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Stage 2F-F.16 (Y3): what Auto Connect concluded about one server/protocol
 * endpoint.
 *
 * The tri-state exists because "we did not get an answer" and "we proved it is
 * not there" are different facts, and on UDP they are different *most of the
 * time*: a filtered or silent-datagram UDP endpoint produces no error at all.
 * Collapsing both into one boolean is what made a UDP verdict unsafe to trust.
 */
enum class AutoConnectReachabilityState {
    /** Proven reachable: the intended endpoint answered in time. */
    REACHABLE,

    /**
     * Proven not reachable: the operating system reported a definitive error for
     * this destination, such as a refused connection or an ICMP port-unreachable
     * surfaced by the platform.
     */
    UNREACHABLE,

    /**
     * No conclusion. A timeout, a silently dropped datagram, a filtering
     * firewall, a reply that could not be attributed to this probe, or a probe
     * that had no endpoint to aim at.
     *
     * UNKNOWN is never failure, and never an elimination on its own. It is the
     * honest answer, and it is deliberately not convertible to UNREACHABLE.
     */
    UNKNOWN,
}

/**
 * Stage 2F-F.16 (Y3): Auto Connect's reachability verdict for one server/protocol
 * endpoint. Runtime only — never persisted.
 *
 * The Server List keeps its own binary [ReachabilityResult]; the two namespaces
 * are stored separately precisely so neither can be read as the other (Y3/Y5/D11).
 */
data class AutoConnectReachabilityResult(
    val state: AutoConnectReachabilityState,
    /**
     * Round trip in ms, measured with a monotonic clock, and **only** when the
     * state is [AutoConnectReachabilityState.REACHABLE]. Null otherwise: no RTT is
     * invented for an unanswered or refused probe, and a TCP round trip is never
     * reused for a UDP protocol.
     */
    val rttMs: Long? = null,
) {
    val isReachable: Boolean get() = state == AutoConnectReachabilityState.REACHABLE

    /**
     * The RTT this result is allowed to contribute to Auto Connect ranking, or null.
     *
     * UNKNOWN and UNREACHABLE contribute nothing (Phase 4 aggregation: minimum
     * valid RTT over the candidate's usable protocols). Keeping this rule next to
     * the result is what stops a "no measurement" state from silently donating a
     * number to the comparator.
     */
    fun rttIfReachable(): Long? = if (state == AutoConnectReachabilityState.REACHABLE) rttMs else null

    companion object {
        fun reachable(rttMs: Long): AutoConnectReachabilityResult =
            AutoConnectReachabilityResult(AutoConnectReachabilityState.REACHABLE, rttMs.coerceAtLeast(1L))

        fun unreachable(): AutoConnectReachabilityResult =
            AutoConnectReachabilityResult(AutoConnectReachabilityState.UNREACHABLE, null)

        fun unknown(): AutoConnectReachabilityResult =
            AutoConnectReachabilityResult(AutoConnectReachabilityState.UNKNOWN, null)
    }
}

/**
 * Stage 2F-F.16 (D7): what the socket layer actually observed on the wire.
 *
 * Kept separate from [AutoConnectReachabilityState] so the mapping from wire
 * evidence to verdict is one small, pure, exhaustively testable function instead
 * of being buried in socket handling.
 */
enum class WireOutcome {
    /** A reply from the intended endpoint arrived before the timeout. */
    ANSWERED,

    /**
     * A definitive, OS-reported error for this destination: refused connection, or
     * an ICMP port-unreachable that the platform surfaced as
     * [PortUnreachableException].
     */
    REFUSED,

    /**
     * Nothing usable arrived: timeout, silent drop, filtering, an unrelated reply,
     * or no endpoint to probe at all.
     */
    SILENT,
}

/**
 * D7 + Y2, the whole verdict table in one place.
 *
 * Note what is **not** here: there is no rule turning a timeout into UNREACHABLE.
 * Silence is evidence of nothing, and on UDP silence is the normal state of a
 * filtered or firewalled endpoint.
 */
internal fun classify(outcome: WireOutcome): AutoConnectReachabilityState = when (outcome) {
    WireOutcome.ANSWERED -> AutoConnectReachabilityState.REACHABLE
    WireOutcome.REFUSED -> AutoConnectReachabilityState.UNREACHABLE
    WireOutcome.SILENT -> AutoConnectReachabilityState.UNKNOWN
}

/** What a probe observed, plus a monotonic elapsed time when it was answered. */
data class ProbeReport(
    val outcome: WireOutcome,
    val elapsedNanos: Long = 0L,
) {
    fun toResult(): AutoConnectReachabilityResult = when (classify(outcome)) {
        AutoConnectReachabilityState.REACHABLE ->
            AutoConnectReachabilityResult.reachable(elapsedNanos / 1_000_000L)
        AutoConnectReachabilityState.UNREACHABLE -> AutoConnectReachabilityResult.unreachable()
        AutoConnectReachabilityState.UNKNOWN -> AutoConnectReachabilityResult.unknown()
    }
}

/**
 * Stage 2F-F.16: the transport-level probe, injectable so the classification and
 * the transport selection can be tested deterministically without a network.
 *
 * The seam is a parameter, not a test-only global: production calls simply omit it
 * and get [SocketAutoConnectReachabilityProbe].
 */
interface AutoConnectReachabilityProbe {
    fun probe(target: ReachabilityTarget, tcpTimeoutMs: Int): ProbeReport
}

/**
 * Stage 2F-F.16 (D7 + Y2): the real transport probe.
 *
 * **The transport comes from the protocol.** A UDP protocol is probed over UDP and
 * a TCP protocol over TCP — never the other way round. A TCP socket cannot prove a
 * UDP endpoint is reachable (they are different transports through different
 * firewalls), and a TCP failure cannot prove a UDP one is closed. That
 * cross-transport borrowing is exactly what Phase 2F-F.2 removed from *endpoint*
 * selection; this removes the same mistake from *probing*.
 */
object SocketAutoConnectReachabilityProbe : AutoConnectReachabilityProbe {

    /**
     * Stage 2F-F.16 (Y2): the UDP probe budget.
     *
     * 1000 ms, local to this probe. It does not touch the Auto Connect dial
     * timeout, the TCP reachability timeout, or any VPN connection timeout.
     */
    const val UDP_PROBE_TIMEOUT_MS = 1000

    /**
     * A deliberately protocol-neutral marker payload.
     *
     * This is NOT a protocol handshake and is not intended to be: no SoftEther or
     * OpenVPN packet is emulated, nothing is authenticated, and no session is
     * negotiated. It exists only so there is a datagram to aim at the endpoint.
     * Most VPN servers will ignore it — which is expected, and is precisely why a
     * missing reply is UNKNOWN rather than UNREACHABLE.
     */
    private val UDP_PROBE_PAYLOAD = "vpngate-reach".toByteArray(Charsets.US_ASCII)

    /** Bounded receive buffer; a probe reply, if any, is a control-packet-sized thing. */
    private const val UDP_RECEIVE_BUFFER = 512

    override fun probe(target: ReachabilityTarget, tcpTimeoutMs: Int): ProbeReport =
        when (target.protocol.probeTransport) {
            ProbeTransport.UDP -> probeUdp(target.candidate.ip, target.port)
            ProbeTransport.TCP -> probeTcp(target.candidate.ip, target.port, tcpTimeoutMs)
        }

    /**
     * TCP: connect and observe.
     *
     * A completed connection is proof. A refusal is proof. A timeout is **not**
     * proof — a dropped SYN, a black-holed route and a filtered port all look like
     * a timeout — so it stays UNKNOWN rather than being rounded down to
     * UNREACHABLE.
     */
    private fun probeTcp(host: String?, port: Int, timeoutMs: Int): ProbeReport {
        if (host.isNullOrBlank() || port <= 0) return ProbeReport(WireOutcome.SILENT)
        val start = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                ProbeReport(WireOutcome.ANSWERED, System.nanoTime() - start)
            }
        } catch (_: ConnectException) {
            // ECONNREFUSED / EHOSTUNREACH / ENETUNREACH: the OS is telling us the
            // destination is not there.
            ProbeReport(WireOutcome.REFUSED)
        } catch (_: SocketTimeoutException) {
            ProbeReport(WireOutcome.SILENT)
        } catch (_: IOException) {
            ProbeReport(WireOutcome.SILENT)
        }
    }

    /**
     * UDP: send a neutral datagram and wait, without pretending to be a client.
     *
     * Three properties matter here.
     *
     * 1. **No endpoint means UNKNOWN.** SoftEther UDP can be a perfectly usable
     *    protocol for Auto Connect while publishing no UDP port of its own (Phase
     *    2), because the native stack negotiates the UDP endpoint at connect time.
     *    There is then nothing to aim a pre-connection probe at, so the answer is
     *    UNKNOWN. It is emphatically **not** UNREACHABLE, and no port is borrowed
     *    from another protocol to manufacture a probe.
     *
     * 2. **Correlation is by peer filtering.** The socket is `connect`ed to the
     *    intended endpoint, so the kernel only delivers datagrams from that peer;
     *    a reply is attributable to this probe at the IP/port level without
     *    inventing an application-level token. The source is re-checked anyway.
     *    There is no protocol-level handshake to correlate against, and this is
     *    the strongest honest guarantee available without one.
     *
     * 3. **Only a real OS-level refusal counts.** Android surfaces an ICMP
     *    port-unreachable as [PortUnreachableException] on a connected datagram
     *    socket; that is the one definitive signal, and it maps to UNREACHABLE.
     *    Everything else — timeout, silent drop, filtering, an unrecognised reply —
     *    is UNKNOWN. No raw ICMP is read, and no privileged API is needed.
     */
    private fun probeUdp(host: String?, port: Int): ProbeReport {
        if (host.isNullOrBlank() || port <= 0) return ProbeReport(WireOutcome.SILENT)
        val start = System.nanoTime()
        return try {
            // Resolved up front so the reply's source can be compared against the
            // exact address the datagram was sent to.
            val address = InetAddress.getByName(host)
            DatagramSocket().use { socket ->
                socket.soTimeout = UDP_PROBE_TIMEOUT_MS
                // Connecting a UDP socket sets its default peer and makes the kernel
                // deliver ONLY datagrams from that peer. No network I/O happens here
                // and no handshake is implied — this is address filtering, not a
                // connection, and no VPN session is negotiated or authenticated.
                socket.connect(address, port)
                socket.send(
                    DatagramPacket(UDP_PROBE_PAYLOAD, UDP_PROBE_PAYLOAD.size, address, port),
                )

                val buffer = ByteArray(UDP_RECEIVE_BUFFER)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)

                val fromIntendedPeer =
                    reply.address?.hostAddress == address.hostAddress && reply.port == port
                if (fromIntendedPeer) {
                    ProbeReport(WireOutcome.ANSWERED, System.nanoTime() - start)
                } else {
                    // Something answered, but not provably in answer to this probe.
                    ProbeReport(WireOutcome.SILENT)
                }
            }
        } catch (_: PortUnreachableException) {
            ProbeReport(WireOutcome.REFUSED)
        } catch (_: SocketTimeoutException) {
            // The normal outcome for a filtering firewall or an endpoint that
            // simply ignores neutral payloads. Never UNREACHABLE.
            ProbeReport(WireOutcome.SILENT)
        } catch (_: IOException) {
            ProbeReport(WireOutcome.SILENT)
        }
    }
}
