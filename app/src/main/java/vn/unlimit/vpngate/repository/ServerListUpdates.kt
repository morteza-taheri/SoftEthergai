package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.remote.EmergencySourceFetcher
import vn.unlimit.vpngate.data.remote.ManualServer
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
 * Z1-IMPL-D (Roadmap §12.5.2 item D) adds one thing that is not a rung: the
 * user's own manually entered servers are re-emitted at the single write, so
 * they survive every refresh instead of being wiped out by the very dataset
 * that replaced their row (spec §11.11, `SERVER_SOURCE_ARCHITECTURE.md` §4.3).
 * That merge lives in [withManualRows] and is wired in [daoStore].
 *
 * Z1-IMPL-G (Roadmap §12.5.2 item G) adds one thing that is *not* a decision:
 * the write reports which of the three acceptance outcomes of
 * `SERVER_SOURCE_ARCHITECTURE.md` §9.6 / §10.8 it is ([DatasetOutcome]). The
 * outcome is recorded, never branched on, and it changes nothing about which
 * datasets are stored — see the `DatasetOutcome` KDoc for why the names and
 * their behaviour are deliberately separated.
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
        val manual = LastManualRows()
        return ServerListUpdater(
            fetch = {
                // Read the user's own rows before anything that can fail or
                // throw: the manual merge is not a rung, so it must not
                // depend on the network having answered.
                manual.refresh()
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
            store = daoStore(repository, dao, manual, capability) { origin.value },
            emergencyFetch = {
                // Re-read at this rung too: the emergency fetch can take
                // seconds, and the rows written at the end of this run must be
                // the ones the user has right now.
                manual.refresh()
                emergencyDataset()
            },
        )
    }

    /**
     * v1.0.49 (§1.6.42): the validated emergency records, or null.
     *
     * Single source of truth for the EMERGENCY rung's data. It reuses the
     * v1.0.21 fetch path — [EmergencySourceFetcher] (the 1 MiB /
     * [EmergencySourceFetcher.MAX_BODY_CHARS] body cap and the redirect
     * authority check) and [EmergencyCsvParser] — and runs the same
     * merge -> validate -> score the live collector does. Nothing is written
     * here.
     *
     * Two callers consume it:
     *  - [emergencyDataset] maps it to `List<VPNGateItem>` for
     *    `ServerListUpdater`'s emergency rung (the legacy hook, unchanged).
     *  - `VpnServerRepository.refreshLocked` folds it into the *same*
     *    `collected` pipeline as PHYSICAL/TUNNEL, so the ladder itself
     *    attempts the EMERGENCY rung (not only when the snapshot is empty)
     *    and the records still pass through `DatasetIntegrity` on write.
     */
    internal suspend fun emergencyRecords(): List<VpnServerRecord>? {
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
        return valid
    }

    /**
     * The emergency rung's dataset: the enabled user-configured sources,
     * fetched and parsed into the same `List<VPNGateItem>` the primary fetch
     * produces. v1.0.49 (§1.6.42): delegates the validated records to
     * [emergencyRecords] and maps them to items for `ServerListUpdater`.
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
        val valid = emergencyRecords() ?: return null
        val connectionList = VpnConnectionMapper.toConnectionList(valid)
        if (connectionList.size() == 0) return null
        return connectionList.toVPNGateItems()
    }

    /**
     * The one persistence boundary. [ServerListUpdater] decides when this runs;
     * nothing else may call [VPNGateItemDao.replaceAll].
     *
     * @param capability the run's source capability, the same instance the
     *   fetch lambda writes and the updater reads, so the outcome logged here
     *   is judged with the capability the run actually had.
     */
    private fun daoStore(
        repository: VpnServerRepository,
        dao: VPNGateItemDao?,
        manual: LastManualRows,
        capability: LastFetchCapability,
        fetchedFromCache: () -> Boolean = { false },
    ): ServerListUpdater.Store = object : ServerListUpdater.Store {
        override fun currentCount(): Int = dao?.count() ?: 0

        override fun currentOpenVpnConfigCount(): Int = dao?.countWithOpenVpnConfig() ?: 0

        override fun currentDataset(): List<VPNGateItem> = dao?.getAll().orEmpty()

        // VPNGateItemDao.replaceAll is @Transaction and refuses an empty
        // list, so this swap is atomic and can never blank the table.
        //
        // Z1-IMPL-D: the manual merge happens here, at the single write, so
        // it holds whichever rung supplied the network dataset — the primary
        // fetch, the snapshot/stored/seed fallbacks, or the emergency rung.
        override fun replaceAll(items: List<VPNGateItem>) {
            // Z1-IMPL-G (Roadmap §12.5.2 item G): report which of the three
            // acceptance outcomes of `SERVER_SOURCE_ARCHITECTURE.md` §9.6 /
            // §10.8 this write is.
            //
            // This cannot *branch* on the outcome, because the decision lives
            // in `ServerListUpdater` and that file is not in this phase's
            // authorized set — so it records it. `DatasetIntegrity.evaluate`
            // is pure and deterministic, so re-running it on the same inputs
            // reproduces the verdict the updater already reached rather than
            // forming a second opinion; if the two ever disagreed, that would
            // be worth seeing in the log, which is the point of doing it here
            // instead of assuming the verdict.
            //
            // Note what the outcome does and does not claim: REJECT_DEGRADED
            // is enforced upstream (a refused dataset never reaches this
            // method), while MERGE_WITH_EXISTING is a *name* — the accepted
            // dataset is still replaced, not added to. The cumulative-add
            // reading is Cumulative Server Retention (§9.1).
            val stored = currentDataset()
            val verdict = DatasetIntegrity.evaluate(items, stored, capability.value)
            CollectorLog.d(
                "Dataset outcome ${DatasetIntegrity.outcome(verdict, stored)}: " +
                    "${items.size} incoming vs ${stored.size} stored (${verdict.detail})",
            )
            // RETENTION-IMPL-PHASE-1 (spec §1.6.31): MERGE_WITH_EXISTING is no
            // longer a *name* on the accepted path. A fresh network observation
            // is merged into the stored dataset (so a server the feed no longer
            // lists is kept, not deleted); a fallback — the last-known-good
            // snapshot, the stored list or the bundled seed — already
            // represents a complete known-good set, so it still replaces
            // wholesale exactly as before. `fetchedFromCache` is the origin
            // flag the updater computed from the fetch. REJECT_DEGRADED remains
            // enforced upstream and never reaches this method.
            if (fetchedFromCache()) {
                dao?.replaceAll(withManualRows(items, manual.rows))
            } else {
                mergeAll(dao, items, manual.rows, System.currentTimeMillis())
            }
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

/**
 * Z1-IMPL-D: the user's own rows for the run currently in flight.
 *
 * Written inside the fetch lambdas — the only suspend points that run under
 * `ServerListUpdater`'s gate — and read by the store at the single write. It
 * is a holder rather than a read-at-write because
 * [ServerListUpdater.Store.replaceAll] is deliberately not a suspend
 * function, and making it one would mean changing a class this phase is not
 * authorized to touch.
 */
private class LastManualRows {
    @Volatile
    var rows: List<VPNGateItem> = emptyList()

    suspend fun refresh() {
        rows = manualServerRows()
    }
}

/** The enabled manual servers as rows of the server list; never throws. */
private suspend fun manualServerRows(): List<VPNGateItem> {
    val dao = try {
        vn.unlimit.vpngate.App.instance?.manualServerDao
    } catch (e: Exception) {
        null
    } ?: return emptyList()

    val enabled = try {
        dao.listEnabled()
    } catch (e: Exception) {
        CollectorLog.d("Manual server list unavailable: ${e.message}")
        return emptyList()
    }
    if (enabled.isEmpty()) return emptyList()

    return ManualServerStore(dao).toItems(enabled.map { ManualServer.fromEntity(it) })
}

/**
 * Z1-IMPL-D (Roadmap §12.5.2 item D): the manual-preservation merge.
 *
 * A stored list is replaced wholesale by every accepted refresh, so without
 * this the user's own servers would be deleted by the very update that
 * succeeded — they were never part of the download in the first place.
 * Spec §11.11 ("Manual servers must survive ordinary online server-list
 * updates") and `SERVER_SOURCE_ARCHITECTURE.md` §4.3 ("re-emitted on each
 * merge as long as they remain valid") both require them back on every
 * write, which is exactly where this runs.
 *
 * Two properties this function is built to keep:
 *
 *  - **Zero manual servers means zero change.** An empty list returns the
 *    caller's list unchanged, by identity, so every existing caller and
 *    every existing test observes exactly the behaviour it did before item D.
 *  - **A collision is resolved by the frozen priority, not by preference.**
 *    When the network already serves a host, the manual row is not added
 *    again: `api/html` outrank `manual` (`SERVER_SOURCE_ARCHITECTURE.md`
 *    §3.3/§4.2, "never promoted above canonical"). The manual entry is not
 *    destroyed — it stays in `manual_server` and in Settings — and it comes
 *    back on the next refresh in which the feeds stop listing that host,
 *    which is what "re-emitted as long as they remain valid" means.
 *
 * The result is collapsed by Room identity as a last step, because two
 * manual rows can legitimately share a host with different ports or
 * protocols and the table's primary key is the host name alone. Reusing
 * [VPNGateItem.dedupeForStorage] keeps that collapse the one deterministic
 * policy it already is, rather than inventing a second one here.
 */
internal fun withManualRows(
    networkRows: List<VPNGateItem>,
    manualRows: List<VPNGateItem>,
): List<VPNGateItem> {
    if (manualRows.isEmpty()) return networkRows

    val served = HashSet<String>(networkRows.size * 2)
    for (row in networkRows) served.add(row.hostName.trim())

    val fresh = manualRows.filter { it.hostName.trim() !in served }
    if (fresh.isEmpty()) return networkRows

    return VPNGateItem.dedupeForStorage(networkRows + fresh)
}

// -------------------------------------------------------------------------
// RETENTION-IMPL-PHASE-1 (spec §1.6.31): Cumulative Server Retention, phase 1.
// -------------------------------------------------------------------------

/**
 * Merge [incoming] into the stored dataset instead of replacing it.
 *
 * This is the add-only core of Cumulative Server Retention
 * (`SERVER_SOURCE_ARCHITECTURE.md` §9.1–§9.2). A refresh that no longer
 * mentions a server does **not** delete it: rows present in storage but absent
 * from [incoming] are retained unchanged. Every row that survives — whether it
 * came from the feed or from storage — is written back through
 * [VPNGateItemDao.replaceAll], which is `@Transaction` (deleteAll + insertAll),
 * so the table is never left half-written.
 *
 *   - A server that is new this observation gets `firstSeenAt = now`.
 *   - A server the app has seen before keeps its existing `firstSeenAt` (the
 *     moment it was first observed) and simply has `lastSeenAt` refreshed to
 *     `now`.
 *   - Every surviving row gets a freshly recomputed phase-1 `healthScore`
 *     (see [retentionHealthScore]); the score is a property of the latest
 *     observation, so it is always recomputed, never carried forward.
 *
 * [withManualRows] runs first so the user's own servers survive the merge
 * exactly as they do on the replace path. The merge is computed in memory from
 * a single `dao.getAll()` read and committed in one transaction; the updater's
 * process-wide mutex already prevents a concurrent write from racing the
 * read-then-write.
 *
 * Phases 2–3 (the max-server cap and the §9.3 retention-priority prune) are
 * explicitly out of scope for phase 1; this function only ever *adds* and
 * *refreshes*, never *prunes*.
 *
 * @param now the observation timestamp; injected so callers and tests share
 *   one clock.
 */
internal fun mergeAll(
    dao: VPNGateItemDao?,
    incoming: List<VPNGateItem>,
    manualRows: List<VPNGateItem>,
    now: Long,
) {
    if (dao == null) return
    if (incoming.isEmpty()) return

    // The manual overlay must run before the merge so a user server the feed
    // does not list is still present in `withManual` and therefore matched /
    // retained rather than dropped.
    val withManual = withManualRows(incoming, manualRows)
    if (withManual.isEmpty()) return

    val stored = dao.getAll()
    val storedByHost = stored.associateBy { it.hostName.trim() }
    val incomingKeys = HashSet<String>(withManual.size * 2)
    for (row in withManual) incomingKeys.add(row.hostName.trim())

    val merged = ArrayList<VPNGateItem>(stored.size + withManual.size)
    for (item in withManual) {
        val key = item.hostName.trim()
        val prev = storedByHost[key]
            merged.add(
                item.copy(
                    // A returning server keeps the moment it was first seen; a new
                    // one is stamped now.
                    firstSeenAt = prev?.firstSeenAt ?: now,
                    lastSeenAt = now,
                    healthScore = retentionHealthScore(item, now),
                )
            )
    }
    // Retain stored rows the incoming feed did not mention. This is the whole
    // point of phase 1: a quiet server is kept, not pruned.
    for (row in stored) {
        if (row.hostName.trim() !in incomingKeys) merged.add(row)
    }

    dao.replaceAll(merged)
}

/**
 * Phase-1 retention health score (brief STEP 5): a minimal, explicit ranking
 * of the latest observation, so the §9 retention-priority prune that arrives
 * in phase 2 has a monotonic signal to work with.
 *
 *   - 0   — `lastSeenAt == 0`: an unknown / never-refreshed row (a server that
 *     pre-dates this schema and has not yet been observed by a merge). Reserved
 *     sentinel; an incoming row is always stamped `now`, so it never hits this.
 *   - 100 — `score > 0 && ping > 0`: a server the collector measured as
 *     reachable and ranked.
 *   - 50  — `score > 0 || ping > 0`: measured on one axis only.
 *   - 25  — neither axis measured.
 *
 * This is intentionally the brief's minimal formula; the richer §9
 * retention-priority model is phase 2.
 */
private fun retentionHealthScore(item: VPNGateItem, lastSeenAt: Long): Int {
    if (lastSeenAt == 0L) return 0
    return when {
        item.score > 0 && item.ping > 0 -> 100
        item.score > 0 || item.ping > 0 -> 50
        else -> 25
    }
}
