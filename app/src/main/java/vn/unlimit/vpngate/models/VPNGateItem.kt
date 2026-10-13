package vn.unlimit.vpngate.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import vn.unlimit.vpngate.data.model.VpnPortSource

@Entity
data class VPNGateItem(
    @PrimaryKey val hostName: String,
    @ColumnInfo val ip: String?,
    @ColumnInfo val score: Int = 0,
    @ColumnInfo val ping: Int = 0,
    @ColumnInfo val speed : Int = 0,
    @ColumnInfo val countryLong: String?,
    @ColumnInfo val countryShort: String?,
    @ColumnInfo val numVpnSession: Int = 0,
    @ColumnInfo val uptime: Int = 0,
    @ColumnInfo val totalUser: Int = 0,
    @ColumnInfo val totalTraffic: Long = 0,
    @ColumnInfo val logType: String?,
    @ColumnInfo val operator: String?,
    @ColumnInfo val message: String?,
    @ColumnInfo val openVpnConfigData: String?,
    @ColumnInfo val tcpPort: Int = 0,
    @ColumnInfo val udpPort: Int = 0,
    @ColumnInfo val isL2TPSupport: Boolean = false,
    @ColumnInfo val isSSTPSupport: Boolean = false,
    // MS-SSTP's own port and its provenance. sstpPort = 0 with
    // sstpPortSource = UNKNOWN is a valid, meaningful state: the source
    // confirmed SSTP but published no port, and no other protocol's port
    // may ever stand in for it.
    //
    // Stage 2F-A (L-1): UNKNOWN is the literal string VpnPortSource.UNKNOWN
    // (previously the empty string), which is also what the 4 -> 5 migration
    // writes for rows that never had an SSTP port.
    @ColumnInfo val sstpPort: Int = 0,
    @ColumnInfo val sstpPortSource: String = VpnPortSource.UNKNOWN,
    @ColumnInfo val seTcpPort: Int = 0,
    @ColumnInfo val seUdpPort: Int = 0,
    @ColumnInfo val seUdpSupported: Boolean = false
) {
    val isUdpOnly: Boolean
        get() = seTcpPort <= 0 && seUdpPort > 0

    /** SoftEther UDP is offered but no port was published (§6/§9). */
    val isSeUdpPortUnknown: Boolean
        get() = seUdpSupported && seUdpPort <= 0

    companion object {

        /**
         * Stage 2F-A (E-1): collapse a dataset to one row per Room identity.
         *
         * Room's primary key for this table is [hostName], while the collector
         * merges on IP. A single feed can therefore legitimately contain the
         * same hostname on two addresses (a stale mirror, or a server whose
         * address changed mid-crawl): the merger keeps both because they are
         * different addresses, and the mapper maps both to the same host name.
         * `insertAll` aborts on a duplicate key, which failed the whole
         * transactional replacement and reported it as a generic store
         * failure even though the feed was perfectly good.
         *
         * Two servers must never be allowed to do that, and a server must not
         * be invented for them either. The policy is a deterministic
         * deduplication by Room identity:
         *
         *  1. group by the trimmed host name (the primary key);
         *  2. keep the single most connectable row of each group.
         *
         * "Most connectable" is a total order, so the result does not depend
         * on the order the collector happened to produce the rows in: the most
         * published connect options first, then the better-reported score, then
         * the faster link, and finally the numerically smaller address, which
         * is unique within a group of genuinely different servers.
         */
        fun dedupeForStorage(items: List<VPNGateItem>): List<VPNGateItem> {
            if (items.size < 2) return items
            // Fast path: nothing to collapse, so the common case keeps the
            // caller's exact list (same order, same instances).
            val distinct = items.map { it.hostName.trim() }.toSet()
            if (distinct.size == items.size) return items

            return items.sortedWith(WINNER_FIRST).distinctBy { it.hostName.trim() }
        }

        /** Most connectable first, then the smallest address as final tiebreak. */
        private val WINNER_FIRST = Comparator<VPNGateItem> { a, b ->
            connectOptions(b).compareTo(connectOptions(a)).takeIf { it != 0 }
                ?: b.score.compareTo(a.score).takeIf { it != 0 }
                ?: b.speed.compareTo(a.speed).takeIf { it != 0 }
                ?: b.ping.compareTo(a.ping).takeIf { it != 0 }
                ?: a.ip.orEmpty().compareTo(b.ip.orEmpty())
        }

        /** How many protocols this row can actually be dialled on. */
        private fun connectOptions(item: VPNGateItem): Int {
            var n = 0
            if (!item.openVpnConfigData.isNullOrBlank()) n++
            if (item.seTcpPort > 0) n++
            if (item.seUdpPort > 0 || item.seUdpSupported) n++
            if (item.isSSTPSupport && item.sstpPort > 0) n++
            if (item.isSSTPSupport && VpnPortSource.normalized(item.sstpPortSource) == VpnPortSource.DEFAULT) n++
            if (item.isL2TPSupport) n++
            return n
        }
    }
}
