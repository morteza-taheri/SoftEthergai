package vn.unlimit.vpngate.automode

import vn.unlimit.vpngate.data.model.VpnPortSource

/**
 * Protocol-capability + quality snapshot of one server, decoupled from
 * the Parcelable VPNGateConnection so the controller core stays a pure
 * JVM unit-test target. [payload] carries the platform object (the
 * VPNGateConnection) back to the Android connection adapter.
 */
data class AutoModeCandidate(
    val hostname: String?,
    val ip: String?,
    /** Server speed in bps as collected. */
    val speed: Long,
    /** Ping in ms. */
    val ping: Int,
    val sessions: Int,
    val score: Int,
    /** Number of independent sources confirming this server. */
    val sources: Int,
    // Protocol capabilities (§7)
    val seTcpPort: Int,
    val seUdpPort: Int,
    val seUdpSupported: Boolean,
    val openVpnTcpPort: Int,
    val openVpnUdpPort: Int,
    /**
     * v1.0.53 (§1.6.45) TASK A: whether this server's OpenVPN **config bytes**
     * are actually in hand.
     *
     * OpenVPN is the one protocol whose port is not enough to dial it: the
     * adapter builds an `.ovpn` profile from `VPNGateConnection
     * .openVpnConfigData` (see `AndroidConnectionAdapter.connectOpenVpn`) and
     * has nothing to dial without it. The manual path has always required both
     * (`DetailActivity.protocolOptions` gates on `openVpnConfigData != null &&
     * port > 0`); Auto Connect did not, so it dialled servers that could only
     * ever time out.
     *
     * A `do_openvpn` link on the HTML page is **not** enough: nothing in the
     * app ever downloads that link, so a link-only server has no bytes and is
     * not usable here.
     *
     * Defaults to `false` deliberately — fail closed. A candidate built without
     * this fact is treated as having no config, so a forgotten construction
     * site loses a protocol instead of burning a timeout.
     */
    val openVpnConfigAvailable: Boolean = false,
    val l2tpSupported: Boolean,
    val sstpSupported: Boolean,
    /**
     * Stage 2F-F.2 (Q-6): MS-SSTP's OWN published port, or 0 when the source
     * printed none. It is never borrowed from OpenVPN's or SoftEther's port.
     */
    val sstpPort: Int = 0,
    /**
     * Provenance of [sstpPort] — EXPLICIT, DEFAULT or UNKNOWN. A
     * [vn.unlimit.vpngate.data.model.VpnPortSource] constant is kept as the
     * value rather than collapsed to a boolean, because "the source printed
     * 443", "443 is the protocol default" and "we do not know the port" are
     * three different facts the user has to be told apart.
     */
    val sstpPortSource: String = VpnPortSource.UNKNOWN,
    /**
     * The port an MS-SSTP connection would actually dial, resolved ONCE from
     * [vn.unlimit.vpngate.models.VPNGateConnection.sstpConnectPort] when the
     * candidate is built.
     *
     * Deriving it here rather than re-deriving it in every consumer is what
     * keeps the provenance rule in exactly one place: a published port wins,
     * DEFAULT falls back to the protocol default, and UNKNOWN yields 0 rather
     * than a guessed port. **0 means MS-SSTP cannot be dialled at all.**
     */
    val sstpConnectPort: Int = 0,
    /**
     * Stage 2F-F.15 (D4 + Y1-b): the candidate's representative ACTIVE round-trip
     * time in ms, or null when no usable protocol has a measured one.
     *
     * Aggregated as the **minimum valid** RTT across the candidate's usable,
     * automatable protocols (Y1-b): the fastest route the app actually found is
     * the honest description of the server. Never averaged, never the maximum,
     * never weighted by Protocol Priority, never fabricated.
     *
     * **Runtime only.** This is ranking data derived from probe results and is
     * deliberately not persisted: no Room column, no snapshot field, no
     * preference, no Gson DTO. A fresh process starts with null, which is the
     * honest answer ("not measured yet"), not zero.
     */
    val activeRttMs: Long? = null,
    val payload: Any? = null,
)

/**
 * Stage 2F-F.13 (Y4): one protocol/endpoint the controller has ALREADY decided
 * is usable for a candidate, ready to be probed.
 *
 * The point of passing this instead of `(server, protocol)` is that the probe
 * stops being able to make capability claims. Previously the tester received a
 * server list plus one protocol and decided for itself whether a port existed,
 * whether the transport was supported, and whether the server was worth
 * keeping. All three are capability decisions, and they belong to
 * [AutoModeController.usableProtocols] — the layer that knows the user's
 * Protocol Priority, what the data model supports, and what the app can drive
 * unattended. The tester now receives the answer and only measures it.
 */
data class ReachabilityTarget(
    val candidate: AutoModeCandidate,
    /** The protocol this endpoint belongs to; never inferred by the tester. */
    val protocol: AutoModeProtocol,
    /** The endpoint [endpointPort] resolved for [protocol]. */
    val port: Int,
)

/**
 * Stage 2F-F.17 (D1 = FILTER_ONLY): what the reachability layer decided.
 *
 * **Reachability is a gate, never a ranker.** [kept] is always a subsequence of
 * the input list, in the input's own order: the caller compared those candidates
 * with [AutoModeController.byQuality] before handing them over, and a probe has
 * no business rearranging a quality decision. There is no RTT ordering here, no
 * "reachable first" ordering, and no protocol-priority ordering.
 *
 * [allUnreachable] exists because an empty [kept] means two completely different
 * things, and treating them the same is the bug D1 fixes:
 *
 * - **every probed candidate had every usable protocol definitively UNREACHABLE**
 *   — a real measurement, and the one case where removing everything is correct;
 * - **nothing was probed at all** — no usable protocol, no endpoint, no targets,
 *   or a probe that failed to run. That is an absence of evidence, and restoring
 *   the full list is the only honest response.
 *
 * Conflating them lets a probe that learned nothing delete the entire queue, or
 * lets a genuinely dead network pass everything through. A caller that wants to
 * keep its list on any non-definitive outcome asks [allUnreachable] rather than
 * guessing from `kept.isEmpty()`.
 *
 * Runtime only; never persisted.
 */
data class ReachabilityGate(
    /** The candidates that survived, as a subsequence of the input list. */
    val kept: List<AutoModeCandidate>,
    /**
     * **True only when at least one candidate was measured, and every measured
     * candidate was definitively `UNREACHABLE`.**
     *
     * Both halves matter, and the first one is what makes the flag safe:
     *
     * - **"measured"** means a probe actually ran and recorded a verdict for that
     *   candidate's reachable endpoint. A candidate with no usable protocol, no
     *   published endpoint, or no IP was never measured — it produced no verdict,
     *   so it cannot be counted here and it is never removed by the gate. It
     *   remains in [kept].
     * - **"every measured candidate"** scopes the claim to what was actually
     *   observed. A single `REACHABLE` or a single `UNKNOWN` anywhere makes this
     *   false, because both of those are legitimate reasons to keep a server.
     *
     * Therefore `allUnreachable == true` guarantees a non-empty measured set:
     * `true` is unreachable when nothing was probed at all.
     *
     * It is deliberately **not** the negation of `kept.isEmpty()`. The two can
     * disagree, because a candidate may survive on evidence that never existed: if
     * one candidate is genuinely down and another cannot be probed at all (no IP,
     * no endpoint), then [kept] is non-empty while this flag is `true`. A caller
     * must therefore consult [kept] **first** and only consult this flag when
     * [kept] is empty — which is what
     * [AutoModeController]'s three-way gate application does. Reading the flag on
     * its own, or reading it before [kept], would discard a server on no evidence.
     */
    val allUnreachable: Boolean,
)

/** §20 UI-facing state machine. */
sealed class AutoModeState {
    object Disconnected : AutoModeState()

    data class Connecting(
        val hostname: String?,
        val ip: String?,
        val protocol: AutoModeProtocol,
        val speed: Long,
        val ping: Int,
        val attempt: Int,
        val total: Int,
    ) : AutoModeState()

    data class Connected(
        val hostname: String?,
        val ip: String?,
        val protocol: AutoModeProtocol,
        val speed: Long,
        val ping: Int,
    ) : AutoModeState()

    data class Error(val message: String) : AutoModeState()
}
