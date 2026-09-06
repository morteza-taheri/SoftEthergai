package vn.unlimit.vpngate.ranking

import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.merger.Provenance
import kotlin.math.min

/**
 * Quality scoring — port of the Python oracle's formula with ONE
 * documented difference (plan T3.5): the multi-source bonus counts
 * INDEPENDENT provenance groups instead of raw source tags, so six
 * mirrors can no longer inflate a score.
 */
object ServerQualityCalculator {
    fun performanceBaseScore(server: VpnServerRecord): Double {
        val perf = VpnRecords.performance(server)

        val speed = VpnRecords.num(perf["speedMbps"])
        val ping = VpnRecords.num(perf["pingMs"])
        val sessions = VpnRecords.int(perf["sessions"])
        val uptime = VpnRecords.num(perf["uptimeDays"])

        var score = 0.0

        // Speed: 0..30
        score += when {
            speed >= 1000 -> 30.0
            speed >= 500 -> 27.0
            speed >= 250 -> 24.0
            speed >= 100 -> 20.0
            speed >= 50 -> 15.0
            speed >= 10 -> 9.0
            speed > 0 -> 4.0
            else -> 0.0
        }

        // Ping: 0..25
        score += when {
            ping in 1.0..20.0 -> 25.0
            ping <= 40 -> 22.0
            ping <= 70 -> 19.0
            ping <= 100 -> 15.0
            ping <= 150 -> 10.0
            ping <= 250 -> 5.0
            else -> 0.0
        }

        // Sessions: lower congestion can be better.
        score += when {
            sessions <= 5 -> 15.0
            sessions <= 20 -> 13.0
            sessions <= 50 -> 10.0
            sessions <= 100 -> 7.0
            sessions <= 200 -> 4.0
            else -> 0.0
        }

        // Uptime: 0..15
        score += when {
            uptime >= 90 -> 15.0
            uptime >= 30 -> 12.0
            uptime >= 7 -> 9.0
            uptime >= 1 -> 5.0
            else -> 0.0
        }

        // Multi-source confidence: 0..15 — INDEPENDENT groups, not
        // raw source count (plan T3.5).
        val groupCount = Provenance.independentSourceGroups(server).size

        score += when {
            groupCount >= 6 -> 15.0
            groupCount >= 4 -> 13.0
            groupCount >= 2 -> 9.0
            groupCount == 1 -> 5.0
            else -> 0.0
        }

        return min(100.0, score)
    }

    fun scoreServer(server: VpnServerRecord) {
        val base = performanceBaseScore(server)
        val p = VpnRecords.protocols(server)
        val quality = VpnRecords.map(server["quality"])

        fun protocolScore(supported: Boolean, portScore: Int, configScore: Int = 0): Int {
            if (!supported) return 0
            return min(100.0, base * 0.75 + portScore + configScore).toInt()
        }

        val se = VpnRecords.map(p["softether"])
        val ov = VpnRecords.map(p["openvpn"])
        val sstp = VpnRecords.map(p["sstp"])
        val l2 = VpnRecords.map(p["l2tpIpsec"])

        val seSupported = VpnRecords.bool(se["supported"])
        val ovSupported = VpnRecords.bool(ov["supported"])
        val sstpSupported = VpnRecords.bool(sstp["supported"])
        val l2Supported = VpnRecords.bool(l2["supported"])

        var overall = base
        if (seSupported) overall += 5
        if (ovSupported) overall += 4
        if (sstpSupported) overall += 3
        if (l2Supported) overall += 3

        quality["overall"] = min(100.0, overall).toInt()

        quality["softether"] = protocolScore(
            seSupported,
            if (VpnRecords.bool(VpnRecords.transport(se, "tcp")["supported"])) 15 else 0,
            if (VpnRecords.bool(VpnRecords.transport(se, "udp")["supported"])) 5 else 0,
        )

        quality["openvpn"] = protocolScore(
            ovSupported,
            (if (VpnRecords.bool(VpnRecords.transport(ov, "tcp")["supported"])) 8 else 0) +
                (if (VpnRecords.bool(VpnRecords.transport(ov, "udp")["supported"])) 6 else 0),
            if (VpnRecords.bool(ov["configAvailable"])) 5 else 0,
        )

        quality["sstp"] = protocolScore(
            sstpSupported,
            if (sstp["port"] != null && sstp["port"] != 0) 10 else 0,
        )

        quality["l2tp"] = protocolScore(
            l2Supported,
            if (VpnRecords.int(l2["port"]) == 1701) 10 else 0,
        )
    }

    fun scoreAll(servers: List<VpnServerRecord>) {
        for (server in servers) {
            scoreServer(server)
        }
    }

    fun hasProtocol(server: VpnServerRecord, name: String): Boolean =
        VpnRecords.bool(VpnRecords.protocol(server, name)["supported"])

    fun sortServers(
        servers: List<VpnServerRecord>,
        protocol: String? = null,
    ): List<VpnServerRecord> {
        fun qualityKey(s: VpnServerRecord): Int =
            if (protocol == null) {
                VpnRecords.int(VpnRecords.map(s["quality"])["overall"])
            } else {
                VpnRecords.int(VpnRecords.map(s["quality"])[protocol])
            }

        fun pingTerm(s: VpnServerRecord): Double {
            val ping = VpnRecords.num(VpnRecords.performance(s)["pingMs"])
            return if (ping > 0) ping else 999999.0
        }

        return servers.sortedWith(
            compareByDescending<VpnServerRecord> { qualityKey(it) }
                .thenByDescending { VpnRecords.num(VpnRecords.performance(it)["speedMbps"]) }
                .thenBy { pingTerm(it) }
        )
    }
}
