package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.remote.EmergencySourceFetcher
import vn.unlimit.vpngate.data.remote.SourceIdentity
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.models.VPNGateItem
import vn.unlimit.vpngate.parser.EmergencyCsvParser
import vn.unlimit.vpngate.ranking.ServerQualityCalculator

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
            emergencyFetch = { emergencyDataset() },
        )
    }

    /**
     * The emergency rung's dataset: the enabled user-configured sources,
     * fetched and parsed into the same `List<VPNGateItem>` the primary fetch
     * produces.
     *
     * It is the **last** rung, exactly as `SERVER_SOURCE_ARCHITECTURE.md`
     * §2.5 orders them. [fetch][ServerListUpdater] already covers the open
     * and VPN rungs and the repository's own snapshot / stored / bundled-seed
     * fallbacks, so by the time this runs every in-app source has answered
     * "nothing" and only a source the user configured by hand is left.
     *
     * Returns `null` — never an empty list — whenever there is nothing to
     * say: no enabled source, an unusable URL, a refused or oversized body,
     * a body the parser rejects whole, or no record that survives
     * `validateServers`. `null` is what makes `ServerListUpdater` fall
     * through to `Preserved`, which keeps the stored list untouched.
     *
     * The pipeline is the live one, unchanged (§3.12): parse → merge →
     * validate → score → map. Nothing is written here — `ServerListUpdater`
     * owns the single write, and `DatasetIntegrity` judges the result
     * against what is already stored before it is allowed to happen.
     */
    private suspend fun emergencyDataset(): List<VPNGateItem>? {
        val dao = try {
            vn.unlimit.vpngate.App.instance?.emergencySourceConfigDao
        } catch (e: Exception) {
            null
        } ?: return null

        val sources = try {
            dao.listEnabled()
        } catch (e: Exception) {
            CollectorLog.d("Emergency source list unavailable: ${e.message}")
            return null
        }
        if (sources.isEmpty()) return null

        val fetcher = EmergencySourceFetcher()
        val collected = mutableListOf<VpnServerRecord>()

        // §2.5 path 3 exit condition: the first acceptable dataset wins, and
        // a source that produces nothing is simply skipped. Sources are read
        // sequentially, not raced: they are user-configured URLs whose order
        // the user chose, and a race would make the outcome depend on which
        // host happened to answer first.
        for (config in sources) {
            when (val fetched = fetcher.fetch(config.url)) {
                is EmergencySourceFetcher.FetchResult.Success -> {
                    when (
                        val parsed = EmergencyCsvParser.parse(fetched.body, SourceIdentity.EMERGENCY_TAG)
                    ) {
                        is EmergencyCsvParser.Result.Valid,
                        is EmergencyCsvParser.Result.Partial,
                        -> collected.addAll(EmergencyCsvParser.recordsOrEmpty(parsed))

                        is EmergencyCsvParser.Result.Malformed -> CollectorLog.d(
                            "Emergency source ${config.url} rejected: ${parsed.reason}",
                        )
                    }
                }

                is EmergencySourceFetcher.FetchResult.HttpError -> CollectorLog.d(
                    "Emergency source ${config.url} answered HTTP ${fetched.status}",
                )

                is EmergencySourceFetcher.FetchResult.NetworkError -> CollectorLog.d(
                    "Emergency source ${config.url} unreachable: ${fetched.reason}",
                )

                is EmergencySourceFetcher.FetchResult.TooLarge -> CollectorLog.d(
                    "Emergency source ${config.url} body of ${fetched.length} chars exceeds " +
                        "${EmergencySourceFetcher.MAX_BODY_CHARS}",
                )
            }
        }

        if (collected.isEmpty()) return null

        // The same merge the live collector runs, so an emergency record and
        // a canonical one that describe the same server collapse into one
        // rather than two rows.
        val merged = VpnServerMerger.mergeRecords(collected)
        val valid = VpnServerMerger.validateServers(merged)
        if (valid.isEmpty()) return null

        ServerQualityCalculator.scoreAll(valid)

        val connectionList = VpnConnectionMapper.toConnectionList(valid)
        if (connectionList.size() == 0) return null

        return connectionList.toVPNGateItems()
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
