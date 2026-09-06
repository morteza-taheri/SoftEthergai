package vn.unlimit.vpngate.automode

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
    val l2tpSupported: Boolean,
    val sstpSupported: Boolean,
    val payload: Any? = null,
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
