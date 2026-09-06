package vn.unlimit.vpngate.utils

/**
 * Local configuration values for the app.
 *
 * These values are used instead of a cloud/remote config service so the app
 * works fully offline without any external dependency.
 */
object AppConfig {
    /**
     * Returns the string value for the given key.
     */
    fun getString(key: String): String {
        return when (key) {
            "vpn_dns_block_ads_primary" -> "176.103.130.130"
            "vpn_dns_block_ads_alternative" -> "176.103.130.131"
            "vpn_alternative_api" -> "https://www.vpngate.net"
            "vpn_udp_api_v2" -> "https://www.vpngate.net/api/iphone/"
            "vpn_check_ip_url" -> "https://whatismyipaddress.com/"
            "vpn_paid_server_api" -> "https://www.vpngate.net/api/"
            "vpn_import_open_vpn" -> "false"
            "vpn_header_session_name" -> "vpn_header_session_name"
            else -> ""
        }
    }

    fun getBoolean(key: String): Boolean {
        return when (key) {
            "vpn_import_open_vpn" -> false
            else -> false
        }
    }
}