package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.VPNGateItem

/**
 * The production wiring of [ServerListUpdater].
 *
 * Three call sites need an updater that behaves identically — the Update
 * button, the scheduled refresh, and Auto Connect's empty-store acquisition —
 * so it is built exactly once, here. Two of them differ in a single decision,
 * and only that decision is a parameter:
 *
 *  - [forUpdateTrigger] is a request for *news*. A snapshot / stored /
 *    bundled-seed fallback is the existing list, not a new observation, so it
 *    is reported as a failed refresh and nothing is stored. This is the
 *    behaviour Stage 2C-B established for the button and the scheduler.
 *  - [forAcquisition] is a request for *something to work with* when the store
 *    is empty. The same fallback chain is offered to the acceptance boundary
 *    instead of being thrown away, so a fresh install can still be filled from
 *    the last-known-good snapshot or the bundled seed — but only after
 *    [DatasetIntegrity] has judged it, and only through the one writer.
 *
 * Both share the same store, the same snapshot writer and the same process-wide
 * mutex, so a dataset can only reach the table through one acceptance sequence.
 *
 * Nothing here persists a dataset by itself: the DAO is reached exclusively
 * from inside [ServerListUpdater.Store].
 */
object ServerListUpdates {

    /** The Update button and the scheduled refresh: only new data counts. */
    fun forUpdateTrigger(
        repository: VpnServerRepository,
        dao: VPNGateItemDao?,
    ): ServerListUpdater = build(repository, dao, acceptCacheFallback = false)

    /** [forUpdateTrigger] for a caller that only has an application instance. */
    fun forUpdateTrigger(app: vn.unlimit.vpngate.App): ServerListUpdater = forUpdateTrigger(
        repository = VpnServerRepository(cacheDir = app.filesDir),
        dao = app.vpnGateItemDao,
    )

    /**
     * Auto Connect's acquisition: an empty store may be filled from the
     * repository's own fallback chain, subject to the same acceptance.
     */
    fun forAcquisition(
        repository: VpnServerRepository,
        dao: VPNGateItemDao?,
    ): ServerListUpdater = build(repository, dao, acceptCacheFallback = true)

    /** [forAcquisition] for a caller that only has an application instance. */
    fun forAcquisition(app: vn.unlimit.vpngate.App): ServerListUpdater = forAcquisition(
        repository = VpnServerRepository(cacheDir = app.filesDir),
        dao = app.vpnGateItemDao,
    )

    private fun build(
        repository: VpnServerRepository,
        dao: VPNGateItemDao?,
        acceptCacheFallback: Boolean,
    ): ServerListUpdater {
        val capability = LastFetchCapability()
        val origin = LastFetchOrigin()
        return ServerListUpdater(
            fetch = {
                val result = try {
                    repository.refresh()
                } catch (e: Exception) {
                    throw ServerListParseException(e)
                }
                when {
                    result == null -> null
                    // A fallback is the existing list, not a download: for an
                    // update trigger that means "keep what is stored".
                    result.fromCache && !acceptCacheFallback -> null
                    else -> {
                        capability.value = result.capability
                        origin.value = result.fromCache
                        result.connectionList.toVPNGateItems()
                    }
                }
            },
            fetchCapability = { capability.value },
            fetchedFromCache = { origin.value },
            store = daoStore(repository, dao),
        )
    }

    /**
     * The one persistence boundary. [ServerListUpdater] decides when this runs;
     * nothing else may call [VPNGateItemDao.replaceAll].
     */
    private fun daoStore(
        repository: VpnServerRepository,
        dao: VPNGateItemDao?,
    ): ServerListUpdater.Store = object : ServerListUpdater.Store {
        override fun currentCount(): Int = dao?.count() ?: 0

        override fun currentOpenVpnConfigCount(): Int = dao?.countWithOpenVpnConfig() ?: 0

        override fun currentDataset(): List<VPNGateItem> = dao?.getAll().orEmpty()

        // VPNGateItemDao.replaceAll is @Transaction and refuses an empty
        // list, so this swap is atomic and can never blank the table.
        override fun replaceAll(items: List<VPNGateItem>) {
            dao?.replaceAll(items)
        }

        // Written only after the accepted dataset is already in the table, so
        // the last-known-good fallback can never be replaced by a rejected one.
        override fun saveLastKnownGood(items: List<VPNGateItem>): Boolean =
            repository.saveLastKnownGood(items)
    }
}

/**
 * Whether the most recent fetch was an observation of the network or a
 * fallback the repository already had. Written by the fetch lambda and read
 * once afterwards, under the updater's process-wide gate.
 */
private class LastFetchOrigin {
    @Volatile
    var value: Boolean = false
}
