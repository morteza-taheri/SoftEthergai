package vn.unlimit.vpngate.automode

/**
 * Protocols Auto Mode can drive. The list is open-ended: adding a new
 * protocol means adding an enum constant plus a `supports` rule and a
 * connect branch in the connection adapter — no controller rewrite.
 */
enum class AutoModeProtocol(val id: String) {
    SOFTETHER_TCP("SOFTETHER_TCP"),
    SOFTETHER_UDP("SOFTETHER_UDP"),
    OPENVPN_TCP("OPENVPN_TCP"),
    OPENVPN_UDP("OPENVPN_UDP"),
    L2TP_IPSEC("L2TP_IPSEC"),
    MS_SSTP("MS_SSTP");

    companion object {
        /** §24 default when the user never changed the setting. */
        const val DEFAULT_ID = "SOFTETHER_UDP"

        fun fromId(id: String?): AutoModeProtocol =
            entries.firstOrNull { it.id == id } ?: SOFTETHER_UDP
    }
}
