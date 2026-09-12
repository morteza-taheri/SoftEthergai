package vn.unlimit.vpngate.models

import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import androidx.sqlite.db.SimpleSQLiteQuery
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.db.VPNGateItemDao
import java.util.Locale

/**
 * Created by dongh on 14/01/2018.
 */
class VPNGateConnectionList : Parcelable {
    @JvmField
    var filter: Filter? = null
    var mKeyword: String? = null
    private var data: MutableList<VPNGateConnection>?
    // Resolved lazily: in-memory lists (collector output, unit tests)
    // must work without the Android Application singleton; only the
    // DB-backed sort/filter paths touch the DAO.
    private val vpnGateItemDao: VPNGateItemDao?
        get() = App.instance?.vpnGateItemDao
    private var sortField: String? = null
    private var sortType: Int? = null

    constructor() {
        data = ArrayList()
    }

    private constructor(`in`: Parcel) {
        data = `in`.createTypedArrayList(VPNGateConnection.CREATOR)
    }

    /**
     * Filter connection by keyword using multi-token search
     *
     * @param inKeyword keyword to filter
     * @return
     */
    fun filter(inKeyword: String): VPNGateConnectionList {
        mKeyword = inKeyword
        val dao = vpnGateItemDao
        if (dao != null) {
            val result = dao.filterAndSort(buildQuery())
            clear()
            result.forEach { data!!.add(VPNGateConnection().fromVPNGateItem(it)) }
        } else {
            val filtered = filterInMemory(inKeyword)
            clear()
            data!!.addAll(filtered)
        }
        return this
    }

    private fun getFilterQuery(): String {
        val raw = mKeyword?.trim() ?: return ""
        if (raw.isEmpty()) return ""

        val tokens = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""

        val tokenClauses = mutableListOf<String>()
        for (token in tokens) {
            val sanitized = token.lowercase(Locale.getDefault()).replace("'", "''")
            val subClauses = mutableListOf<String>()

            // General text match
            subClauses.add("countryLong LIKE '%$sanitized%'")
            subClauses.add("countryShort LIKE '%$sanitized%'")
            subClauses.add("hostName LIKE '%$sanitized%'")
            subClauses.add("ip LIKE '%$sanitized%'")
            subClauses.add("operator LIKE '%$sanitized%'")
            subClauses.add("message LIKE '%$sanitized%'")

            // Protocol keywords
            when (sanitized) {
                "openvpn", "ovpn" -> {
                    subClauses.add("(tcpPort > 0 OR udpPort > 0 OR (openVpnConfigData IS NOT NULL AND openVpnConfigData != ''))")
                }
                "softether", "se", "sslvpn", "ssl-vpn" -> {
                    subClauses.add("(seTcpPort > 0 OR seUdpPort > 0 OR seUdpSupported = 1)")
                }
                "sstp", "ms-sstp" -> {
                    subClauses.add("(isSSTPSupport = 1)")
                }
                "l2tp", "ipsec", "l2tp/ipsec" -> {
                    subClauses.add("(isL2TPSupport = 1)")
                }
                "tcp" -> {
                    subClauses.add("(tcpPort > 0 OR seTcpPort > 0)")
                }
                "udp" -> {
                    subClauses.add("(udpPort > 0 OR seUdpPort > 0 OR seUdpSupported = 1)")
                }
            }

            // Numeric port match if token is a valid port integer
            val portNum = sanitized.toIntOrNull()
            if (portNum != null && portNum in 1..65535) {
                subClauses.add("tcpPort = $portNum")
                subClauses.add("udpPort = $portNum")
                subClauses.add("seTcpPort = $portNum")
                subClauses.add("seUdpPort = $portNum")
            }

            tokenClauses.add("(${subClauses.joinToString(" OR ")})")
        }

        return tokenClauses.joinToString(" AND ")
    }

    fun filterInMemory(keyword: String): List<VPNGateConnection> {
        val current = data ?: return emptyList()
        val raw = keyword.trim().lowercase(Locale.getDefault())
        if (raw.isEmpty()) return current.toList()

        val tokens = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return current.toList()

        return current.filter { conn ->
            tokens.all { token ->
                matchesToken(conn, token)
            }
        }
    }

    private fun matchesToken(conn: VPNGateConnection, token: String): Boolean {
        if (conn.countryLong?.lowercase(Locale.getDefault())?.contains(token) == true) return true
        if (conn.countryShort?.lowercase(Locale.getDefault())?.contains(token) == true) return true
        if (conn.hostName?.lowercase(Locale.getDefault())?.contains(token) == true) return true
        if (conn.ip?.lowercase(Locale.getDefault())?.contains(token) == true) return true
        if (conn.operator?.lowercase(Locale.getDefault())?.contains(token) == true) return true
        if (conn.message?.lowercase(Locale.getDefault())?.contains(token) == true) return true

        when (token) {
            "openvpn", "ovpn" -> {
                if (conn.tcpPort > 0 || conn.udpPort > 0 || !conn.openVpnConfigData.isNullOrBlank()) return true
            }
            "softether", "se", "sslvpn", "ssl-vpn" -> {
                if (conn.seTcpPort > 0 || conn.seUdpPort > 0 || conn.seUdpSupported) return true
            }
            "sstp", "ms-sstp" -> {
                if (conn.isSSTPSupport()) return true
            }
            "l2tp", "ipsec", "l2tp/ipsec" -> {
                if (conn.isL2TPSupport()) return true
            }
            "tcp" -> {
                if (conn.tcpPort > 0 || conn.seTcpPort > 0) return true
            }
            "udp" -> {
                if (conn.udpPort > 0 || conn.seUdpPort > 0 || conn.seUdpSupported) return true
            }
        }

        val portNum = token.toIntOrNull()
        if (portNum != null && portNum in 1..65535) {
            if (conn.tcpPort == portNum || conn.udpPort == portNum ||
                conn.seTcpPort == portNum || conn.seUdpPort == portNum ||
                conn.sstpConnectPort == portNum) return true
        }

        return false
    }

    private fun getOrderQuery(): String {
        if (sortField == null || sortField!!.isEmpty()) {
            return ""
        }
        sortField = when (sortField) {
            "COUNTRY" -> SortProperty.COUNTRY
            "SPEED" -> SortProperty.SPEED
            "PING" -> SortProperty.PING
            "SCORE" -> SortProperty.SCORE
            "UPTIME" -> SortProperty.UPTIME
            "SESSION" -> SortProperty.SESSION
            else -> sortField
        }
        if (sortType == ORDER.ASC) {
            return " ORDER BY $sortField ASC"
        }
        return " ORDER BY $sortField DESC"
    }

    /**
     * Get ordered list
     *
     * @param property
     * @param type     order type 0 = ASC, 1 = DESC
     * @return
     */
    fun sort(property: String?, type: Int, skipProcessSort: Boolean = false) {
        property.let {
            sortField = property
            sortType = type
            if (skipProcessSort) {
                return
            }
            val dao = vpnGateItemDao
            if (dao != null) {
                val sortedData: List<VPNGateItem>? = dao.filterAndSort(buildQuery())
                this.clear()
                sortedData?.forEach { data!!.add(VPNGateConnection().fromVPNGateItem(it)) }
            } else {
                sortInMemory(property, type)
            }
        }
    }

    fun sortInMemory(property: String?, type: Int) {
        val d = data ?: return
        val isAsc = type == ORDER.ASC
        d.sortWith(Comparator { a, b ->
            val res = when (property) {
                "COUNTRY", SortProperty.COUNTRY -> (a.countryLong ?: "").compareTo(b.countryLong ?: "", ignoreCase = true)
                "SPEED", SortProperty.SPEED -> a.speed.compareTo(b.speed)
                "PING", SortProperty.PING -> a.ping.compareTo(b.ping)
                "SCORE", SortProperty.SCORE -> a.score.compareTo(b.score)
                "UPTIME", SortProperty.UPTIME -> a.uptime.compareTo(b.uptime)
                "SESSION", SortProperty.SESSION -> a.numVpnSession.compareTo(b.numVpnSession)
                else -> 0
            }
            if (isAsc) res else -res
        })
    }

    fun add(vpnGateConnection: VPNGateConnection) {
        data!!.add(vpnGateConnection)
    }

    fun clear() {
        data!!.clear()
    }

    fun addAll(list: VPNGateConnectionList) {
        data!!.addAll(list.data!!)
    }

    fun get(index: Int): VPNGateConnection? {
        val d = data ?: return null
        return if (index in 0 until d.size) d[index] else null
    }

    fun toList(): List<VPNGateConnection> {
        val d = data ?: return emptyList()
        return synchronized(d) { ArrayList(d) }
    }

    fun size(): Int {
        return data!!.size
    }

    fun advancedFilter(filter: Filter?): VPNGateConnectionList {
        this.filter = filter
        return advancedFilter()
    }

    private fun getOperator(numberFilterOperator: NumberFilterOperator): String {
        return when (numberFilterOperator) {
            NumberFilterOperator.EQUAL -> "="
            NumberFilterOperator.GREATER -> ">"
            NumberFilterOperator.GREATER_OR_EQUAL -> ">="
            NumberFilterOperator.LESS -> "<"
            NumberFilterOperator.LESS_OR_EQUAL -> "<="
        }
    }

    private fun appendQuery(
        currentQuery: String,
        queryToAppend: String
    ): String {
        var query = currentQuery
        query += "${if (query.isEmpty()) "" else " AND "}$queryToAppend"
        return query
    }

    private fun getWhereQuery(): String {
        var whereQuery = ""
        if (filter?.ping != null) {
            whereQuery = "ping ${getOperator(filter!!.pingFilterOperator)} ${filter!!.ping}"
        }
        if (filter?.speed != null) {
            val speedInMb = filter!!.speed!! * 1024 * 1024
            whereQuery = appendQuery(
                whereQuery,
                "speed ${getOperator(filter!!.speedFilterOperator)} $speedInMb"
            )
        }
        if (filter?.sessionCount != null) {
            whereQuery = appendQuery(
                whereQuery,
                "numVpnSession ${getOperator(filter!!.sessionCountFilterOperator)} ${filter!!.sessionCount}"
            )
        }
        if (filter?.isShowTCP == true) {
            whereQuery = appendQuery(
                whereQuery, "tcpPort > 0"
            )
        }
        if (filter?.isShowUDP == true) {
            whereQuery = appendQuery(whereQuery, "udpPort > 0")
        }
        if (filter?.isShowL2TP == true && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            whereQuery = appendQuery(whereQuery, "isL2TPSupport = 1")
        }
        if (filter?.isShowSSTP == true) {
            whereQuery = appendQuery(whereQuery, "isSSTPSupport = 1")
        }
        if (filter?.isShowSoftEther == true) {
            whereQuery = appendQuery(
                whereQuery,
                "(seTcpPort > 0 OR seUdpPort > 0 OR seUdpSupported = 1)"
            )
        }
        return whereQuery
    }

    private fun buildQuery(): SimpleSQLiteQuery {
        val selectQuery = "SELECT * FROM vpngateitem"
        var whereQuery = getWhereQuery()
        val filterQuery = getFilterQuery()
        if (filterQuery.isNotEmpty()) {
            whereQuery = if (whereQuery.isNotEmpty()) {
                appendQuery(whereQuery, "($filterQuery)")
            } else {
                filterQuery
            }
        }
        val orderQuery = getOrderQuery()
        if (whereQuery.isNotEmpty()) {
            return SimpleSQLiteQuery("$selectQuery WHERE $whereQuery$orderQuery")
        }
        return SimpleSQLiteQuery("$selectQuery$orderQuery")
    }

    fun advancedFilter(): VPNGateConnectionList {
        clear()
        val filteredResult: List<VPNGateItem>? = vpnGateItemDao?.filterAndSort(buildQuery())
        filteredResult?.forEach {
            data!!.add(VPNGateConnection().fromVPNGateItem(it))
        }
        return this
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeTypedList(data)
    }

    fun toVPNGateItems(): List<VPNGateItem> {
        val vpnGateItems = ArrayList<VPNGateItem>()
        data?.forEach {
            vpnGateItems.add(
                it.toVPNGateItem()
            )
        }
        return vpnGateItems
    }

    fun fromVPNGateItems(vpnGateItems: List<VPNGateItem>): VPNGateConnectionList {
        data?.clear()
        vpnGateItems.forEach {
            data?.add(VPNGateConnection().fromVPNGateItem(it))
        }
        return this
    }

    enum class NumberFilterOperator {
        EQUAL,
        GREATER,
        GREATER_OR_EQUAL,
        LESS,
        LESS_OR_EQUAL
    }

    class Filter {
        var isShowTCP: Boolean = true
        var isShowUDP: Boolean = true
        var isShowL2TP: Boolean = true
        var isShowSSTP: Boolean = true
        var isShowSoftEther: Boolean = true
        var ping: Int? = null
        var pingFilterOperator: NumberFilterOperator = NumberFilterOperator.LESS_OR_EQUAL
        var speed: Int? = null
        var speedFilterOperator: NumberFilterOperator = NumberFilterOperator.GREATER_OR_EQUAL
        var sessionCount: Int? = null
        var sessionCountFilterOperator: NumberFilterOperator = NumberFilterOperator.LESS_OR_EQUAL
    }

    object ORDER {
        const val ASC: Int = 0
        const val DESC: Int = 1
    }

    object SortProperty {
        const val COUNTRY: String = "countryShort"
        const val SPEED: String = "speed"
        const val PING: String = "ping"
        const val SCORE: String = "score"
        const val UPTIME: String = "uptime"
        const val SESSION: String = "numVpnSession"
    }


    companion object CREATOR : Parcelable.Creator<VPNGateConnectionList> {
        override fun createFromParcel(`in`: Parcel): VPNGateConnectionList {
            return VPNGateConnectionList(`in`)
        }

        override fun newArray(size: Int): Array<VPNGateConnectionList?> {
            return arrayOfNulls(size)
        }
    }
}
