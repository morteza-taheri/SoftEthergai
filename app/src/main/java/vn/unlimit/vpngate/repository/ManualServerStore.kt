package vn.unlimit.vpngate.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.data.remote.ManualServer
import vn.unlimit.vpngate.data.remote.ManualServerDao
import vn.unlimit.vpngate.data.remote.ManualServerEntity
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * Z1-IMPL-D (Roadmap §12.5.2 item D): the read/write seam between the
 * `manual_server` table and everything that uses it.
 *
 * The DAO is injected rather than looked up, so this stays a pure-JVM
 * class: the preservation invariant is proved with a fake DAO and no
 * Android, Room or network. The production wiring reaches it through
 * `App.instance?.manualServerDao`, exactly as the emergency source list is
 * reached.
 *
 * This class never touches the network. A manual server is user-authored
 * data — the one thing in the server list that is not an observation of a
 * feed — so it must not be able to fail because a host was unreachable.
 */
class ManualServerStore(private val dao: ManualServerDao) {

    /** The full list for Settings, including disabled rows. */
    fun observeAll(): Flow<List<ManualServer>> =
        dao.observeAll().map { rows -> rows.map { ManualServer.fromEntity(it) } }

    suspend fun list(): List<ManualServer> = dao.listAll().map { ManualServer.fromEntity(it) }

    /** Only the rows that take part in the server list. */
    suspend fun listEnabled(): List<ManualServer> =
        dao.listEnabled().map { ManualServer.fromEntity(it) }

    /**
     * Stores one entry. Returns false — and stores nothing — when the input
     * is not a dialable server, so the caller can refuse it at the boundary
     * instead of discovering later that it never reached the list.
     *
     * The address must be an IPv4 literal. The record layer keys a server on
     * a valid address (`VpnServerMerger.normalizeServer` rejects anything
     * else), so a bare DNS name would be accepted here and then silently
     * dropped by the merge. Refusing it keeps the Settings UI honest; DNS
     * resolution for manual entries is a later phase's problem.
     */
    suspend fun add(
        host: String,
        portText: String,
        protocol: String,
        label: String?,
        notes: String?,
    ): Boolean {
        val hostRaw = VpnUtil.clean(host)
        if (!ManualServer.isValidHost(hostRaw)) return false

        val port = VpnUtil.clean(portText).toIntOrNull() ?: return false
        if (!ManualServer.isValidPort(port)) return false

        val proto = ManualServer.normalizeProtocol(protocol)
        if (!ManualServer.isValidProtocol(proto)) return false

        val hostKey = VpnUtil.normalizeHost(hostRaw)
        val now = System.currentTimeMillis()
        val existing = try {
            dao.findByKey(hostKey, port, proto)
        } catch (e: Exception) {
            null
        }

        // Re-entering an existing server keeps its id and its original
        // creation order — the user is editing a decision, not making a
        // second one.
        dao.upsert(
            ManualServerEntity(
                id = existing?.id ?: 0L,
                host = hostKey,
                port = port,
                protocol = proto,
                label = label?.let { VpnUtil.clean(it) }?.ifEmpty { null },
                notes = notes?.let { VpnUtil.clean(it) }?.ifEmpty { null },
                enabled = true,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
        return true
    }

    /**
     * A disabled manual server stays in the table — the user's address,
     * port and notes are theirs — but stops being re-emitted into the
     * server list, so it cannot be dialled.
     */
    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val existing = list().firstOrNull { it.id == id } ?: return
        dao.upsert(
            existing.copy(enabled = enabled, updatedAt = System.currentTimeMillis()).toEntity()
        )
    }

    suspend fun remove(id: Long) {
        dao.deleteById(id)
    }

    /**
     * The manual servers as rows of the stored server list.
     *
     * This is a mapping, not a parse: a manual entry is already the four
     * facts the row needs, so there is nothing to guess and nothing to
     * validate a second time. Each protocol writes only the columns that
     * protocol owns — never another protocol's port, which is the same rule
     * the collector's mapper follows (`VpnConnectionMapper`).
     *
     * [message][VPNGateItem.message] is deliberately left null. Notes are
     * the user's private annotation and belong to the manual-server screen,
     * not to a server detail dialog labelled "operator message".
     */
    fun toItems(servers: List<ManualServer>): List<VPNGateItem> =
        servers.map { toItem(it) }

    /**
     * v1.0.26: the stored dataset with every enabled manual server written
     * into it — the write-through used when the user adds a server.
     *
     * Before this existed, a manual row reached the server list only through
     * `ServerListUpdates.withManualRows`, i.e. only inside an *accepted*
     * refresh. A user could add a server, see it in Settings, and not see it
     * in the list until some later refresh happened to be accepted — which is
     * why a restart appeared to "fix" it. This closes that gap by running the
     * same merge at add time.
     *
     * It is deliberately [withManualRows] and not a second merge: that
     * function already encodes the preservation invariant (a host the network
     * already serves is not duplicated, no other row is dropped, the result is
     * collapsed by Room identity). Reimplementing it here would create a
     * second policy that could drift from the refresh path's.
     *
     * Returns the caller's list unchanged when no manual server is enabled,
     * by identity — the same no-op guarantee [withManualRows] gives.
     */
    suspend fun materialize(current: List<VPNGateItem>): List<VPNGateItem> =
        withManualRows(current, toItems(listEnabled()))

    fun toItem(server: ManualServer): VPNGateItem {
        val host = VpnUtil.normalizeHost(server.host)
        val base = VPNGateItem(
            hostName = host,
            ip = host,
            // No telemetry of its own: a manual entry was never crawled, so
            // publishing a score, a country or a traffic figure for it would
            // be invented evidence. The row sorts last, which is where a
            // server nobody has measured belongs.
            countryLong = null,
            countryShort = null,
            logType = null,
            operator = server.label?.takeIf { it.isNotBlank() },
            message = null,
            openVpnConfigData = null,
        )
        return when (server.protocol) {
            VpnProtocolNames.SOFTETHER -> base.copy(seTcpPort = server.port)
            VpnProtocolNames.SSTP -> base.copy(
                isSSTPSupport = true,
                sstpPort = server.port,
                // The user typed the port, so it is EXPLICIT evidence of
                // exactly the same kind a published port is.
                sstpPortSource = VpnPortSource.EXPLICIT,
            )
            VpnProtocolNames.L2TP_IPSEC -> base.copy(isL2TPSupport = true)
            VpnProtocolNames.OPENVPN -> base.copy(tcpPort = server.port)
            else -> base
        }
    }
}
