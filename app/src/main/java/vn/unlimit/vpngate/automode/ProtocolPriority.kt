package vn.unlimit.vpngate.automode

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import vn.unlimit.vpngate.utils.DataUtil

/**
 * Protocol priority configuration item for Auto Connect.
 * Represents an ordered protocol with its enabled state.
 */
data class ProtocolPriorityItem(
    val protocol: AutoModeProtocol,
    var isEnabled: Boolean = true,
)

object ProtocolPriorityManager {

    private const val KEY_PROTOCOL_PRIORITY_LIST = "auto_mode_protocol_priority_json"
    private val gson = Gson()

    /**
     * Default protocol priority profile:
     * 1. SoftEther UDP
     * 2. SoftEther TCP
     * 3. MS-SSTP
     * 4. OpenVPN UDP
     * 5. OpenVPN TCP
     */
    fun getDefaultPriorityList(): List<ProtocolPriorityItem> = listOf(
        ProtocolPriorityItem(AutoModeProtocol.SOFTETHER_UDP, isEnabled = true),
        ProtocolPriorityItem(AutoModeProtocol.SOFTETHER_TCP, isEnabled = true),
        ProtocolPriorityItem(AutoModeProtocol.MS_SSTP, isEnabled = true),
        ProtocolPriorityItem(AutoModeProtocol.OPENVPN_UDP, isEnabled = true),
        ProtocolPriorityItem(AutoModeProtocol.OPENVPN_TCP, isEnabled = true),
    )

    fun getPriorityList(dataUtil: DataUtil): List<ProtocolPriorityItem> {
        val json = dataUtil.getStringSetting(KEY_PROTOCOL_PRIORITY_LIST, null)
        if (json.isNullOrBlank()) {
            return getDefaultPriorityList()
        }
        return try {
            val type = object : TypeToken<List<ProtocolPriorityItem>>() {}.type
            val list: List<ProtocolPriorityItem> = gson.fromJson(json, type)
            // Ensure all known protocols exist in list (in case new protocols are added in future)
            val existingProtocols = list.map { it.protocol }.toSet()
            val missing = AutoModeProtocol.entries.filter { it != AutoModeProtocol.L2TP_IPSEC && !existingProtocols.contains(it) }
            list + missing.map { ProtocolPriorityItem(it, isEnabled = true) }
        } catch (_: Exception) {
            getDefaultPriorityList()
        }
    }

    fun savePriorityList(dataUtil: DataUtil, list: List<ProtocolPriorityItem>) {
        try {
            val json = gson.toJson(list)
            dataUtil.setStringSetting(KEY_PROTOCOL_PRIORITY_LIST, json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Returns only enabled protocols in their defined priority order.
     */
    fun getEnabledProtocols(dataUtil: DataUtil): List<AutoModeProtocol> {
        val list = getPriorityList(dataUtil)
        val enabled = list.filter { it.isEnabled }.map { it.protocol }
        return if (enabled.isNotEmpty()) enabled else listOf(AutoModeProtocol.SOFTETHER_UDP, AutoModeProtocol.SOFTETHER_TCP)
    }
}
