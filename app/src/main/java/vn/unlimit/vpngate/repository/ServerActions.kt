package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.favourites.FavouriteServers
import vn.unlimit.vpngate.models.VPNGateConnection

/**
 * The two actions offered by the server list's long-press sheet: delete one
 * server completely, and export that one server as a re-importable CSV row.
 */
object ServerActions {

    /**
     * Deletes [hostName] from every local store and marks it so a later feed
     * refresh cannot resurrect it.
     *
     * Ordering matters: the tombstone is written FIRST, so even a concurrent
     * refresh that is already merging cannot re-insert the row. Raw SQL is used
     * for the Room deletions for the same reason as [ServerPurge]: adding
     * single-row DAO methods would force every test fake to implement them,
     * and the four deletes belong to one atomic maintenance transaction.
     *
     * The connected server is intentionally NOT protected here — the user
     * explicitly chose this row, and the tunnel stays up until they disconnect.
     */
    fun delete(hostName: String, importStore: ManualImportStore? = null) {
        val host = hostName.trim()
        if (host.isEmpty()) return
        DeletedServers.mark(host)

        val app = App.instance ?: return
        val db = app.appDatabase.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM vpngateitem WHERE hostName = ?", arrayOf<Any>(host))
            db.execSQL("DELETE FROM server_history_summary WHERE hostName = ?", arrayOf<Any>(host))
            db.execSQL("DELETE FROM server_history_protocol WHERE hostName = ?", arrayOf<Any>(host))
            db.execSQL("DELETE FROM server_history_event WHERE hostName = ?", arrayOf<Any>(host))
            // A manually entered row keyed by the same host must go too, or it
            // is re-emitted on the next accepted write.
            db.execSQL("DELETE FROM manual_server WHERE host = ?", arrayOf<Any>(host))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        importStore?.remove(host)
        val removedStar = FavouriteServers.snapshot().filter { it.equals(host, ignoreCase = true) }
        if (removedStar.isNotEmpty()) FavouriteServers.removeAll(removedStar)
    }

    /**
     * The canonical 15-column VPN Gate /api/iphone/ header. [ManualCsvImport]
     * detects this dialect, so a file produced here can be pasted straight back
     * into Settings → import.
     */
    const val API_HEADER: String =
        "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions," +
            "Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message," +
            "OpenVPN_ConfigData_Base64"

    /**
     * One row + header, using the raw API units the model already stores
     * (Speed = bits/sec, Uptime = ms, TotalTraffic = bytes). [VpnGateApiParser]
     * applies the inverse conversions on import, so the values round-trip.
     * Text fields are RFC4180-quoted because Operator/Message are free text.
     */
    fun toCsv(connection: VPNGateConnection): String {
        val configB64 = connection.openVpnConfigData
            ?.takeIf { it.isNotBlank() }
            ?.let { encodeBase64(it.toByteArray(Charsets.UTF_8)) }
            .orEmpty()
        val row = listOf(
            connection.hostName.orEmpty(),
            connection.ip.orEmpty(),
            connection.score.toString(),
            connection.ping.toString(),
            connection.speed.toString(),
            connection.countryLong.orEmpty(),
            connection.countryShort.orEmpty(),
            connection.numVpnSession.toString(),
            connection.uptime.toString(),
            connection.totalUser.toString(),
            connection.totalTraffic.toString(),
            connection.logType.orEmpty(),
            connection.operator.orEmpty(),
            connection.message.orEmpty(),
            configB64,
        ).joinToString(",") { quote(it) }
        return API_HEADER + "\n" + row + "\n"
    }

    /**
     * Pure-Kotlin Base64 encoder. `android.util.Base64` is stubbed in plain
     * JVM unit tests and `java.util.Base64` needs API 26 (minSdk is 24), so
     * neither can be used here. Mirrors [OpenVpnConfigParser.decodeBase64].
     */
    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun encodeBase64(bytes: ByteArray): String {
        val alphabet = BASE64_ALPHABET
        val out = StringBuilder(bytes.size * 4 / 3 + 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or
                ((bytes[i + 1].toInt() and 0xff) shl 8) or
                (bytes[i + 2].toInt() and 0xff)
            out.append(alphabet[(n shr 18) and 63])
                .append(alphabet[(n shr 12) and 63])
                .append(alphabet[(n shr 6) and 63])
                .append(alphabet[n and 63])
            i += 3
        }
        val remaining = bytes.size - i
        if (remaining == 1) {
            val n = (bytes[i].toInt() and 0xff) shl 16
            out.append(alphabet[(n shr 18) and 63])
                .append(alphabet[(n shr 12) and 63])
                .append("==")
        } else if (remaining == 2) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or
                ((bytes[i + 1].toInt() and 0xff) shl 8)
            out.append(alphabet[(n shr 18) and 63])
                .append(alphabet[(n shr 12) and 63])
                .append(alphabet[(n shr 6) and 63])
                .append('=')
        }
        return out.toString()
    }

    private fun quote(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }

    /** A safe, readable filename for the exported single-server CSV. */
    fun fileNameFor(connection: VPNGateConnection): String {
        val raw = connection.hostName?.trim().orEmpty().ifEmpty { connection.ip.orEmpty() }
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').take(60)
        return (safe.ifEmpty { "vpn_server" }) + ".csv"
    }
}
