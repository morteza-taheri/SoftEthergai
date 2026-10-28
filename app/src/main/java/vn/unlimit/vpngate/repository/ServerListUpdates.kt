package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.remote.EmergencySourceFetcher
import vn.unlimit.vpngate.data.remote.ManualServer
import vn.unlimit.vpngate.data.remote.ManualServerDao
import vn.unlimit.vpngate.data.remote.SourceIdentity
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.history.ServerHistoryDao
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
    fun forUpdateTrigger(app: vn.unlimit.vpngate.App): ServerListUpdater = build(
        repository = VpnServerRepository(cacheDir = app.filesDir),
        dao = app.vpnGateItemDao,
        acceptCacheFallback = false,
        historyDao = app.serverHistoryDao,
        database = app.appDatabase,
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
    fun forAcquisition(app: vn.unlimit.vpngate.App): ServerListUpdater = build(
        repository = VpnServerRepository(cacheDir = app.filesDir),
        dao = app.vpnGateItemDao,
        acceptCacheFallback = true,
        historyDao = app.serverHistoryDao,
        database = app.appDatabase,
    )

    private fun build(
        repository: VpnServerRepository,
        dao: VPNGateItemDao?,
        acceptCacheFallback: Boolean,
        historyDao: ServerHistoryDao? = null,
        database: androidx.room.RoomDatabase? = null,
    ): ServerListUpdater {
        val capability = LastFetchCapability()
        val origin = LastFetchOrigin()
        val manual = LastManualRows()
        return ServerListUpdater(
            fetch = {
                // v1.0.63 §3: clear any emergency-origin flag left by a previous
                // refresh on this updater instance *before* evaluating this run's
                // fetch. `origin` is instance-scoped state captured by the
                // `fetchedFromCache` and `store` lambdas below; without this reset
                // a stale `true` (set by an earlier emergency rung) could leak into
                // a later primary refresh and wrongly route its single write
                // through `mergeFallback`. The fetch result below sets `origin`
                // correctly for this run (true only when the result really is a
                // cache/stored/seed fallback), so the reset never hides a genuine
                // fallback classification.
                origin.value = false
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
                        // BLOCK v1.0.76 audit fix: an emergency-supplemented
                        // dataset is a supplement, not a fresh observation —
                        // propagate it so the updater exempts it from history
                        // markers, snapshot promotion and the fresh baseline.
                        origin.value = result.fromCache || result.suppliedByEmergency
                        result.connectionList.toVPNGateItems()
                    }
                }
            },
            fetchCapability = { capability.value },
            fetchedFromCache = { origin.value },
            store = daoStore(repository, dao, manual, capability, historyDao, database) { origin.value },
            emergencyFetch = {
                // Re-read at this rung too: the emergency fetch can take
                // seconds, and the rows written at the end of this run must be
                // the ones the user has right now.
                manual.refresh()
                // v1.0.63 Part B: the emergency rung is a *supplementary*
                // source — an excerpt of the same feed, not a fresh network
                // observation — so its rows must be merged with the stored
                // dataset rather than force-replaced. Force-replacing via
                // mergeAll would drop retention metadata and any field the
                // incoming subset does not carry (e.g. a stored OpenVPN config
                // or SoftEther UDP support). Flagging the origin true makes the
                // single write route through mergeFallback — the very path the
                // snapshot / stored / bundled-seed fallbacks use — which
                // preserves both. `suppliedByEmergency` is already true, so the
                // snapshot is still never promoted to last-known-good.
                origin.value = true
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
        historyDao: ServerHistoryDao? = null,
        database: androidx.room.RoomDatabase? = null,
        fetchedFromCache: () -> Boolean = { false },
    ): ServerListUpdater.Store = object : ServerListUpdater.Store {
        override fun currentCount(): Int = dao?.count() ?: 0

        override fun currentOpenVpnConfigCount(): Int = dao?.countWithOpenVpnConfig() ?: 0

        override fun currentDataset(): List<VPNGateItem> = dao?.getAll().orEmpty()

        // BLOCK v1.0.75 FIX: the baseline for the size-collapse check is the
        // last ACCEPTED fresh dataset, persisted in DataUtil — never the
        // retention union.
        override fun lastAcceptedFreshSize(): Int =
            runCatching {
                vn.unlimit.vpngate.App.instance?.dataUtil?.getLastAcceptedDatasetSize() ?: 0
            }.getOrDefault(0)

        override fun recordAcceptedFreshSize(size: Int) {
            runCatching {
                vn.unlimit.vpngate.App.instance?.dataUtil?.setLastAcceptedDatasetSize(size)
            }
        }

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
            // REQ-RET-001: one history query supplies the success-count rank
            // used by the 500 cap. Absent history is 0 ("no history").
            val successCounts = runCatching {
                historyDao?.historyViews()?.associate { it.hostName.trim() to it.successCount }
            }.getOrNull().orEmpty()
            if (fetchedFromCache()) {
                // G-F29-1: a fallback (last-known-good snapshot, stored list or
                // bundled seed) is not a network observation, but it must not
                // erase the retention metadata already stored for a server.
                // `VPNGateConnection.toVPNGateItem()` never carries
                // firstSeenAt / lastSeenAt / healthScore, so a raw replaceAll
                // would reset them to 0; mergeFallback preserves the stored
                // values instead.
                mergeFallback(dao, items, manual.rows, System.currentTimeMillis(), successCounts)
            } else {
                mergeAll(dao, items, manual.rows, System.currentTimeMillis(), successCounts)
            }
        }

        // BLOCK v1.0.69 PART 1B — one real transaction for the server rows and the
        // history markers. `replaceAll`'s inner @Transaction joins this outer one,
        // so a failure rolls both back together.
        override fun replaceAllAndMarkSeen(items: List<VPNGateItem>, marker: Long) {
            val db = database
            val hd = historyDao
            if (db == null || hd == null) {
                replaceAll(items)
                return
            }
            db.runInTransaction {
                replaceAll(items)
                hd.markSeen(items.map { it.hostName }, marker)
                // BLOCK v1.0.75 (D-8): one UPDATE that ages every summary row
                // this ACCEPTED FRESH refresh did not list. markSeen just moved
                // the listed rows to `marker`, so only the absent ones are hit.
                // Emergency/cache datasets never reach this method (the caller
                // routes them to plain replaceAll), so they can never age rows.
                hd.incrementMissedRefresh(marker)
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

/**
 * The enabled manual servers as rows of the server list; never throws.
 *
 * v1.0.55 (`V1054_VERIFY_REPORT` G-2): the two sources are injected instead of
 * being read from [vn.unlimit.vpngate.App] directly. The rule these lines
 * implement — *imported rows are re-emitted on every write, so a refresh cannot
 * delete them* — is the whole reason `ManualImportStore` is consulted here at
 * all, and it was covered by no test: the function was private and reached a
 * singleton the JVM harness cannot construct. Production passes the default
 * providers, so this is the same code path; a test passes fakes and asserts the
 * emission rule directly.
 *
 * The order is deliberate and load-bearing: legacy `manual_server` rows first,
 * imported rows second. [withManualRows] drops a manual row whose host the
 * network already serves, so a collision resolves in favour of the collected
 * row either way; the order only decides which of two user rows is kept, and
 * the older surface (the one that existed before CSV import) wins.
 */
internal suspend fun manualServerRows(
    importedProvider: () -> List<VPNGateItem> = ::appImportedRows,
    daoProvider: () -> ManualServerDao? = ::appManualServerDao,
): List<VPNGateItem> {
    // v1.0.54 (§1.6.49) PART 4: imported CSV rows are a second, richer source of
    // user-owned servers. They are re-emitted here — at the single write — for
    // exactly the same reason the DAO rows are: a refresh replaces the stored
    // list wholesale, so anything not re-emitted would be deleted by the very
    // update that succeeded.
    val imported = try {
        importedProvider()
    } catch (e: Exception) {
        CollectorLog.d("Imported server list unavailable: ${e.message}")
        emptyList()
    }

    val dao = try {
        daoProvider()
    } catch (e: Exception) {
        null
    }
    if (dao == null) return imported

    val enabled = try {
        dao.listEnabled()
    } catch (e: Exception) {
        CollectorLog.d("Manual server list unavailable: ${e.message}")
        return imported
    }
    if (enabled.isEmpty()) return imported

    return ManualServerStore(dao).toItems(enabled.map { ManualServer.fromEntity(it) }) + imported
}

/** Production source of imported rows; a singleton lookup that may be absent. */
private fun appImportedRows(): List<VPNGateItem> =
    vn.unlimit.vpngate.App.instance?.manualImportStore?.items().orEmpty()

/** Production source of legacy manual rows; a singleton lookup that may be absent. */
private fun appManualServerDao(): ManualServerDao? =
    vn.unlimit.vpngate.App.instance?.manualServerDao


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
// REQ-RET-001 (v1.0.70) / REQ-RETENTION-001..002 (v1.0.71): server capacity.
// -------------------------------------------------------------------------

/**
 * The approved maximum retained server dataset — the **single authoritative
 * capacity value**. `MAX_TOTAL_SERVERS` in the specification is this constant;
 * there is no second copy of the number anywhere in the app.
 *
 * Every other capacity figure (the reserved share, the manual-server quota) is
 * **derived** from it by [reservedCapacity] and [manualServerQuota]. Change this
 * one number and both follow automatically.
 */
internal const val MAX_RETAINED_SERVERS: Int = 500

/**
 * REQ-RETENTION-002 — the share of the total capacity that is always kept free
 * of manually added servers, in percent.
 *
 * This is a percentage, not a count: the count is
 * `ceil(MAX_RETAINED_SERVERS × 10%)`, so it scales with the capacity above and
 * can never drift out of step with it.
 */
internal const val RESERVED_CAPACITY_PERCENT: Int = 10

/**
 * REQ-RETENTION-002 — the number of slots reserved from manual-server
 * allocation: `ceil(total × 10%)`.
 *
 * Integer arithmetic only — `(total * 10 + 99) / 100` is exactly
 * `ceil(total * 10 / 100)` for every non-negative total, with no floating-point
 * rounding to drift on values such as 501 (→ 51, not 50).
 */
internal fun reservedCapacity(total: Int = MAX_RETAINED_SERVERS): Int {
    if (total <= 0) return 0
    val scaled = total.toLong() * RESERVED_CAPACITY_PERCENT
    return ((scaled + 99) / 100).toInt()
}

/**
 * REQ-RETENTION-002 — the maximum number of manually added servers:
 * `total − reservedCapacity(total)`.
 *
 * Derived on every call, never stored: 500 → 450, 501 → 450, 1000 → 900.
 */
internal fun manualServerQuota(total: Int = MAX_RETAINED_SERVERS): Int =
    (total - reservedCapacity(total)).coerceAtLeast(0)

/**
 * REQ-RET-001 — cap [rows] at [cap] using the approved priority order:
 * **quality → protocol breadth → successful-connection history → recency**.
 *
 * Manual/imported rows are never pruned: they are excluded from the ranking and
 * kept in full. If they alone exceed [cap], all of them are kept (the
 * alternative would delete user-supplied data, which no requirement permits).
 *
 * The tie-break is deterministic (trimmed `hostName` ascending) so two runs over
 * the same input produce the same set. It is **not** a quality weight.
 *
 * @param successCounts per-host success counts from `server_history_summary`;
 *   absent entries count as 0, which is exactly "no history".
 */
internal fun capRetainedServers(
    rows: List<VPNGateItem>,
    manualHostNames: Set<String>,
    successCounts: Map<String, Int> = emptyMap(),
    cap: Int = MAX_RETAINED_SERVERS,
): List<VPNGateItem> {
    if (rows.size <= cap) return rows
    val manual = rows.filter { it.hostName.trim() in manualHostNames }
    val network = rows.filter { it.hostName.trim() !in manualHostNames }
    val room = (cap - manual.size).coerceAtLeast(0)
    if (network.size <= room) return rows
    val kept = network.sortedWith(
        compareByDescending<VPNGateItem> { it.healthScore }
            .thenByDescending { VPNGateItem.connectOptions(it) }
            .thenByDescending { successCounts[it.hostName.trim()] ?: 0 }
            .thenByDescending { it.lastSeenAt }
            .thenBy { it.hostName.trim() },
    ).take(room)
    return manual + kept
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
    successCounts: Map<String, Int> = emptyMap(),
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
                    // BLOCK v1.0.75 FIX (server-list update): a feed fetched
                    // WITHOUT a tunnel cannot carry OpenVPN profile bytes (the
                    // profile API is tunnel-only). Overwriting a stored profile
                    // with null would turn a connectable server into an
                    // unconnectable one, so the stored bytes survive until the
                    // feed can supply the real thing again.
                    openVpnConfigData = item.openVpnConfigData ?: prev?.openVpnConfigData,
                )
            )
    }
    // Retain stored rows the incoming feed did not mention. This is the whole
    // point of phase 1: a quiet server is kept, not pruned.
    for (row in stored) {
        if (row.hostName.trim() !in incomingKeys) merged.add(row)
    }

    // REQ-RET-001: the retained dataset is capped at 500, manual rows protected.
    dao.replaceAll(
        capRetainedServers(
            rows = merged,
            manualHostNames = manualRows.map { it.hostName.trim() }.toSet(),
            successCounts = successCounts,
        ),
    )
}

/**
 * Merge a *fallback* dataset (the last-known-good snapshot, the stored list or
 * the bundled seed) into the stored table without erasing retention metadata.
 *
 * This is the G-F29-1 fix for the cache branch of [DaoStore.replaceAll]. The
 * fallback is explicitly classified as **not** representing a new network
 * observation, so unlike [mergeAll] it must not refresh `lastSeenAt` to `now`
 * or recompute `healthScore` as if the server had just been measured on the
 * network.
 *
 * For a host that already exists in storage the stored retention values are
 * kept verbatim:
 *   - `firstSeenAt`  ← stored (the real first-observation moment)
 *   - `lastSeenAt`   ← stored (the real last-observation moment; never forged
 *                       into `now`)
 *   - `healthScore`  ← stored (a property of the last real observation)
 *
 * A host that is genuinely new to storage has no retention history to preserve,
 * so it follows the same init rules as [mergeAll]: `firstSeenAt = now`,
 * `lastSeenAt = now`, `healthScore = retentionHealthScore(item, now)`.
 *
 * As in [mergeAll], stored rows absent from the fallback are retained unchanged
 * (a quiet server is never pruned), and the write goes through the
 * `@Transaction` [VPNGateItemDao.replaceAll]. [mergeAll] itself is unchanged.
 *
 * @param now the record timestamp; injected so callers and tests share one
 *   clock. Used only to initialise brand-new rows, never to overwrite a stored
 *   `lastSeenAt`.
 */
internal fun mergeFallback(
    dao: VPNGateItemDao?,
    incoming: List<VPNGateItem>,
    manualRows: List<VPNGateItem>,
    now: Long,
    successCounts: Map<String, Int> = emptyMap(),
) {
    if (dao == null) return
    if (incoming.isEmpty()) return

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
        // v1.0.63 Part B: a supplementary source (emergency snapshot / stored /
        // bundled-seed) must not erase a stored OpenVPN profile merely because
        // its CSV representation lacks one. The publisher strips profiles to stay
        // under the body cap (≈77% of the shipped rows carry none), so an
        // emergency record for a server the live API had fully populated would
        // otherwise delete the profile. A blank incoming config is "not
        // provided"; a stored non-blank profile is a valid known fact and is
        // kept. No other field is overwritten here — only OpenVPN config has a
        // clean "absent = not provided" signal; other protocol fields' defaults
        // are ambiguous and are intentionally left to the incoming value.
        val openVpnConfigData = if (prev != null &&
            item.openVpnConfigData.isNullOrBlank() &&
            !prev.openVpnConfigData.isNullOrBlank()
        ) {
            prev.openVpnConfigData
        } else {
            item.openVpnConfigData
        }
        // v1.0.63 §2.4: a *supplementary* refresh is not a fresh live
        // observation, so for a host already in storage its quality fields
        // (score / ping / speed / uptime / sessions / traffic) are taken from
        // the stored row, never from the incoming excerpt. The emergency CSV
        // always carries these columns, so "incoming" would otherwise silently
        // overwrite a measured value with the publisher's copy of the same feed
        // — which is exactly the staleness §2.4 forbids. A genuinely new host
        // has no stored measurement, so it falls back to the incoming value.
        // Protocol/port fields and OpenVPN config follow the field policy
        // elsewhere in this block; only the quality telemetry is preserved here.
        val quality = prev ?: item
        merged.add(
            item.copy(
                // A returning server keeps the real retention history stored
                // for it. lastSeenAt is NOT refreshed to `now` because the
                // fallback is not a network observation, and healthScore is
                // preserved rather than recomputed.
                firstSeenAt = prev?.firstSeenAt ?: now,
                lastSeenAt = prev?.lastSeenAt ?: now,
                healthScore = prev?.healthScore ?: retentionHealthScore(item, now),
                openVpnConfigData = openVpnConfigData,
                score = quality.score,
                ping = quality.ping,
                speed = quality.speed,
                numVpnSession = quality.numVpnSession,
                uptime = quality.uptime,
                totalUser = quality.totalUser,
                totalTraffic = quality.totalTraffic,
            )
        )
    }
    // Retain stored rows the fallback did not mention, unchanged.
    for (row in stored) {
        if (row.hostName.trim() !in incomingKeys) merged.add(row)
    }

    // REQ-RET-001: the retained dataset is capped at 500, manual rows protected.
    dao.replaceAll(
        capRetainedServers(
            rows = merged,
            manualHostNames = manualRows.map { it.hostName.trim() }.toSet(),
            successCounts = successCounts,
        ),
    )
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
