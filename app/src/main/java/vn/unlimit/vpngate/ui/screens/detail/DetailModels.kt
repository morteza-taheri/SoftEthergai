package vn.unlimit.vpngate.ui.screens.detail

/** All UI-observable fields for the Detail screen. */
data class DetailUiState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val connectText: String = "",
    val statusText: String = "",
    val netStats: String = "",
    val showNetStats: Boolean = false,
    val showCheckIp: Boolean = false,
    val showL2tpButton: Boolean = false,
    val showTcpPort: Boolean = false,
    val showUdpPort: Boolean = false,
    val showSstpBadge: Boolean = false,
    val excludedAppsCount: Int = 0,
    val isImportToOpenVPN: Boolean = false,
    val operatorMessage: String? = null,
    val showProtocolDialog: Boolean = false,
    val showUseProtocolDialog: Boolean = false,
)

/** VPN protocol identifiers (mirrors the old dialog's enum). */
enum class VpnProtocol {
    OPENVPN_TCP,
    OPENVPN_UDP,
    SOFTETHER_TCP,
    SOFTETHER_UDP,
    MS_SSTP,
}

data class ProtocolOption(
    val protocol: VpnProtocol,
    val detail: String? = null,
)
