package vn.unlimit.vpngate.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.data.model.VpnUtil
import vn.unlimit.vpngate.data.remote.EmergencySourceConfig
import vn.unlimit.vpngate.data.remote.EmergencySourceConfigDao
import vn.unlimit.vpngate.data.remote.EmergencySourceConfigEntity
import vn.unlimit.vpngate.data.remote.ManualServer
import vn.unlimit.vpngate.data.remote.ManualServerDao
import vn.unlimit.vpngate.data.remote.SourceKind
import vn.unlimit.vpngate.data.remote.SourceUrl
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.models.VPNGateItem
import vn.unlimit.vpngate.repository.ManualServerStore
import vn.unlimit.vpngate.repository.ImportOutcome
import vn.unlimit.vpngate.repository.ServerListUpdates
import vn.unlimit.vpngate.repository.withManualRows
import vn.unlimit.vpngate.repository.ServerListUpdateScheduler
import vn.unlimit.vpngate.repository.ServerListUpdater
import vn.unlimit.vpngate.repository.VpnServerRepository
import vn.unlimit.vpngate.utils.DataUtil

class ConnectionListViewModel(application: Application) : BaseViewModel(application) {
    companion object {
        const val TAG = "VPNGateViewModel"
    }

    var dataUtil: DataUtil = App.instance!!.dataUtil!!

    val vpnGateConnectionList = MutableLiveData<VPNGateConnectionList>()
    val lastUpdatedTime = MutableLiveData<Long>()
    private var isRetried = false
    var isError: MutableLiveData<Boolean> = MutableLiveData(false)

    /**
     * v1.0.26: a one-shot, user-facing message about the refresh that just
     * finished, as a string resource id — or null when there is nothing to say.
     *
     * It exists because a *rejected* refresh was completely silent: the stored
     * list is correctly kept (`Outcome.Preserved`), so the screen looked
     * unchanged and the user had no way to tell "nothing to update" from "the
     * update failed and your list is stale". [isError] cannot carry this,
     * because it means "there is nothing to show" and would blank the screen —
     * here there is very much something to show, it is just not fresh.
     *
     * Deliberately one-shot: the observer clears it after showing it, so a
     * configuration change cannot replay a message about a refresh that
     * finished minutes ago.
     */
    var refreshNotice: MutableLiveData<Int?> = MutableLiveData(null)

    /** Clears [refreshNotice] once it has been shown. */
    fun clearRefreshNotice() {
        refreshNotice.value = null
    }

    private val vpnServerRepository =
        VpnServerRepository(cacheDir = application.filesDir)

    /**
     * The one safe replacement path. Manual button presses and the periodic
     * scheduler both go through [ServerListUpdater], so neither can clear the
     * list on a failed download, neither can start a second download while
     * one is already running, and neither can replace a healthy list with a
     * degraded one.
     */
    private val updater = ServerListUpdates.forUpdateTrigger(
        repository = vpnServerRepository,
        dao = App.instance?.vpnGateItemDao,
    )

    // ---------------------------------------------------------------------
    // Z1-IMPL-B: emergency server sources (Roadmap §12.5.2 item B).
    //
    // Configuration only: nothing here fetches anything. The retrieval
    // wiring (item C) and the manual-entry merge (item D) are later phases,
    // so these members only persist and observe the user's list.
    // ---------------------------------------------------------------------

    private val emergencySourceDao: EmergencySourceConfigDao?
        get() = App.instance?.emergencySourceConfigDao

    val emergencySources: StateFlow<List<EmergencySourceConfig>> =
        emergencySourceDao?.observeAll()
            ?.map { entities -> entities.map { EmergencySourceConfig.fromEntity(it) } }
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
            ?: MutableStateFlow(emptyList())

    /**
     * Adds — or refreshes — one emergency source. Returns false when the URL
     * is not an absolute http(s) URL with a usable host: the same parse rule
     * every other source identity must pass ([SourceUrl.parse]), so a typo
     * can never reach the database.
     *
     * The database write is asynchronous; the published [emergencySources]
     * flow is the confirmation, exactly like the rest of this screen.
     */
    fun addEmergencySource(url: String, label: String?): Boolean {
        val identity = SourceUrl.parse(url.trim(), SourceKind.EMERGENCY_CSV) ?: return false
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            val dao = emergencySourceDao ?: return@launch
            withContext(Dispatchers.IO) {
                val normalizedLabel = label?.trim()?.ifEmpty { null }
                val existing = dao.getByUrl(identity.url)
                if (existing == null) {
                    dao.upsert(
                        EmergencySourceConfigEntity(
                            url = identity.url,
                            label = normalizedLabel,
                            enabled = true,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                } else {
                    dao.upsert(existing.copy(label = normalizedLabel, updatedAt = now))
                }
            }
        }
        return true
    }

    fun removeEmergencySource(url: String) {
        viewModelScope.launch {
            val dao = emergencySourceDao ?: return@launch
            withContext(Dispatchers.IO) { dao.deleteByUrl(url) }
        }
    }

    fun toggleEmergencySource(url: String, enabled: Boolean) {
        viewModelScope.launch {
            val dao = emergencySourceDao ?: return@launch
            withContext(Dispatchers.IO) {
                val existing = dao.getByUrl(url) ?: return@withContext
                dao.upsert(
                    existing.copy(enabled = enabled, updatedAt = System.currentTimeMillis()),
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Z1-IMPL-D: manually entered servers (Roadmap §12.5.2 item D).
    //
    // Unlike an emergency source, a manual server is not a URL and is never
    // fetched: it is user-authored data that must survive every refresh
    // (spec §11.11). These members only persist and observe the list; the
    // merge that keeps the rows in the server list lives in
    // `ServerListUpdates.withManualRows`.
    // ---------------------------------------------------------------------

    private val manualServerDao: ManualServerDao?
        get() = App.instance?.manualServerDao

    private val manualServerStore: ManualServerStore?
        get() = manualServerDao?.let { ManualServerStore(it) }

    val manualServers: StateFlow<List<ManualServer>> =
        manualServerDao?.observeAll()
            ?.map { entities -> entities.map { ManualServer.fromEntity(it) } }
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
            ?: MutableStateFlow(emptyList())

    /**
     * Stores one manually entered server.
     *
     * REQ-RETENTION-003/004 (v1.0.71): this is now suspending and returns an
     * [AddOutcome], because "did it store?" has three answers the caller must
     * be able to tell apart — stored, invalid input, and *manual quota
     * reached*. The previous Boolean form could only say "no" and would have
     * left a full quota looking like a typo.
     *
     * The published [manualServers] flow is the confirmation, exactly like
     * the emergency-source list.
     */
    suspend fun addManualServer(
        host: String,
        portText: String,
        protocol: String,
        label: String?,
        notes: String?,
    ): vn.unlimit.vpngate.repository.AddOutcome {
        val store = manualServerStore ?: return vn.unlimit.vpngate.repository.AddOutcome.InvalidInput
        val port = VpnUtil.clean(portText).toIntOrNull()
        val accepted = ManualServer.isValidHost(host) &&
            port != null && ManualServer.isValidPort(port) &&
            ManualServer.isValidProtocol(protocol)
        if (!accepted) return vn.unlimit.vpngate.repository.AddOutcome.InvalidInput

        // v1.0.26: write-through. Storing the manual server is not enough
        // for it to be visible — the main list is built from VPNGateItem,
        // and before this the only writer of that table was an *accepted*
        // refresh. So the user added a server, saw it in Settings, and saw
        // nothing in the list until some later refresh happened to be
        // accepted (a restart only looked like the fix because it triggers
        // one). Materialising here makes the add visible immediately.
        //
        // The merge is `store.materialize`, which delegates to the same
        // `withManualRows` the refresh path uses, so the preservation
        // invariant is identical on both paths: an address the network
        // already serves is not duplicated, and no other row is dropped —
        // `replaceAll` is given the current table plus the manual rows.
        val outcome = withContext(Dispatchers.IO) {
            store.addDetailed(host, portText, protocol, label, notes)
        }
        if (outcome !is vn.unlimit.vpngate.repository.AddOutcome.Stored) return outcome

        val published = withContext(Dispatchers.IO) {
            val itemDao = App.instance?.vpnGateItemDao ?: return@withContext null
            val merged = store.materialize(itemDao.getAll())
            // `replaceAll` refuses an empty list, and an empty merge would
            // mean the row we just stored did not survive validation — in
            // which case writing nothing is the correct outcome.
            if (merged.isEmpty()) return@withContext null
            // REQ-RETENTION-007: a write-through is a completed retention
            // operation, so it must leave the table inside the total capacity
            // too — the manual rows are protected, the network rows are
            // ranked and trimmed.
            val capped = capForRetention(merged)
            itemDao.replaceAll(capped)
            capped
        }
        if (published != null) {
            publishLiveData(vpnGateConnectionList, VPNGateConnectionList().fromVPNGateItems(published))
        }
        return outcome
    }

    fun removeManualServer(id: Long) {
        val store = manualServerStore ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.remove(id) }
        }
    }

    fun toggleManualServer(id: Long, enabled: Boolean) {
        val store = manualServerStore ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.setEnabled(id, enabled) }
        }
    }

    // ---------------------------------------------------------------------
    // v1.0.54 (§1.6.49) PART 4: servers imported from a CSV (file or paste).
    //
    // Storage is [ManualImportStore] (a JSON file), not `manual_server` — see
    // that class for the schema argument. The two sources coexist: rows created
    // by the old manual form stay in Room and still appear here, so the rewrite
    // loses nobody's data.
    // ---------------------------------------------------------------------

    private val importStore: vn.unlimit.vpngate.repository.ManualImportStore?
        get() = App.instance?.manualImportStore

    private val _importedServers = MutableStateFlow<List<VPNGateItem>>(emptyList())

    /** The imported rows, newest write wins. Drives the Settings list. */
    val importedServers: StateFlow<List<VPNGateItem>> = _importedServers

    /**
     * Parses [text], stores the accepted rows and writes them through to the
     * server list.
     *
     * Suspending and IO-bound: the caller runs it off the main thread. Returns
     * the parser's verdict unchanged so the UI can show counts and the first
     * few reject reasons — the parse result is the only thing that can tell a
     * user why their file did nothing.
     */

    init {
        vpnGateConnectionList.value = dataUtil.connectionsCache
        lastUpdatedTime.value = dataUtil.connectionCacheUpdatedAt
        // v1.0.57 (§1.6.55): every property this class uses is declared ABOVE
        // this block, and this launch is the last thing init does.
        //
        // refreshImportedServers() starts a coroutine on viewModelScope, which
        // uses Dispatchers.Main.immediate: the coroutine body runs
        // synchronously up to its first suspension point. Any field it writes
        // must therefore already be initialised. Before v1.0.57 the init block
        // sat at the top of the class while _importedServers was declared ~220
        // lines below, so `_importedServers.value = ...` hit a null field and
        // the app died on launch with a NullPointerException on
        // MutableStateFlow.setValue. Moving the block here (after every
        // property) is what makes the write safe.
        //
        // v1.0.54 (§1.6.49) PART 4: load the imported CSV rows once, off the
        // main thread. A missing or corrupt file reads as empty.
        refreshImportedServers()
    }

    suspend fun importManualCsv(text: String): ImportOutcome {
        val store = importStore
            ?: return ImportOutcome(
                vn.unlimit.vpngate.repository.ManualCsvImport.Result.Failure(
                    vn.unlimit.vpngate.repository.ManualCsvImport.Reason.PARSE_ERROR,
                    "storage unavailable",
                )
            )
        val result = withContext(Dispatchers.IO) {
            vn.unlimit.vpngate.repository.ManualCsvImport.parse(text, System.currentTimeMillis())
        }
        if (result !is vn.unlimit.vpngate.repository.ManualCsvImport.Result.Success) {
            return ImportOutcome(result)
        }
        // REQ-RETENTION-003: the rows go through the quota gate, not straight
        // into the file. `stored` is what was actually persisted, so the
        // summary the user sees cannot claim an import that did not happen.
        val stored = withContext(Dispatchers.IO) {
            val quota = store.addWithinQuota(result.items)
            if (quota.stored > 0) {
                materializeImported()
                _importedServers.value = store.items()
            }
            quota
        }
        return ImportOutcome(result, stored)
    }

    /**
     * Deletes one imported row and re-materialises the list.
     *
     * The row is removed from the dataset by its Room identity. If the same
     * host is also served by the network, it returns on the next accepted
     * refresh — which is the frozen `api/html` outranks `manual` policy, not a
     * deletion of somebody else's data.
     */
    fun removeImportedServer(hostName: String) {
        val store = importStore ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!store.remove(hostName)) return@withContext
                val itemDao = App.instance?.vpnGateItemDao
                if (itemDao != null) {
                    val remaining = itemDao.getAll().filterNot { it.hostName.trim() == hostName.trim() }
                    if (remaining.isNotEmpty()) itemDao.replaceAll(remaining)
                    // v1.0.59 (PART A): a LiveData write from inside
                    // `withContext(Dispatchers.IO)` — this was the second crash
                    // the device reproduced (`:333` before the fix). The value is
                    // still published before this block returns, so the ordering
                    // the caller relies on is unchanged.
                    publishLiveData(
                        vpnGateConnectionList,
                        VPNGateConnectionList().fromVPNGateItems(remaining),
                    )
                }
                _importedServers.value = store.items()
            }
        }
    }

    /** Reloads the imported rows from disk (called once at construction). */
    fun refreshImportedServers() {
        viewModelScope.launch {
            _importedServers.value = withContext(Dispatchers.IO) {
                importStore?.items().orEmpty()
            }
        }
    }

    /**
     * Writes every user-owned row back into the dataset: the imported rows plus
     * any surviving `manual_server` rows. Deliberately the same
     * [withManualRows] the refresh path uses, so there is one preservation
     * policy rather than two.
     */
    private suspend fun materializeImported() {
        val itemDao = App.instance?.vpnGateItemDao ?: return
        val store = importStore ?: return
        val current = itemDao.getAll()
        val merged = withManualRows(current, userRows())
        if (merged.isEmpty()) return
        // REQ-RETENTION-007: an import is a completed retention operation, so
        // the stored table must end inside the total capacity. The user's own
        // rows are protected by host name; only collected rows are ranked and
        // trimmed.
        val capped = capForRetention(merged)
        itemDao.replaceAll(capped)
        // v1.0.59 (PART A): NOT `vpnGateConnectionList.value = …`. This function
        // runs inside `withContext(Dispatchers.IO)` at every call site, and
        // `MutableLiveData.setValue` asserts the main thread — the device threw
        // `IllegalStateException: Cannot invoke setValue on a background thread`
        // here after a successful import, killing the app. See [publishLiveData].
        publishLiveData(vpnGateConnectionList, VPNGateConnectionList().fromVPNGateItems(capped))
    }

    /**
     * Every user-supplied row: the enabled `manual_server` rows plus the
     * imported CSV rows.
     *
     * The two surfaces are the app's whole definition of "manual server", and
     * both are re-emitted on every write. Kept in one place so the merge, the
     * retention cap and the quota all agree on what counts as user data.
     */
    private suspend fun userRows(): List<VPNGateItem> {
        val legacyDao = manualServerDao
        val legacyRows = if (legacyDao == null) {
            emptyList()
        } else {
            ManualServerStore(legacyDao).toItems(
                legacyDao.listEnabled().map { ManualServer.fromEntity(it) },
            )
        }
        return legacyRows + importStore?.items().orEmpty()
    }

    /** [userRows] as the set of host names the retention cap must never prune. */
    private suspend fun userHostNames(): Set<String> =
        userRows().mapTo(HashSet()) { it.hostName.trim() }

    /**
     * REQ-RETENTION-007 — trims [rows] to the total capacity, protecting every
     * user-supplied row and ranking the collected ones with the single
     * retention policy in [capRetainedServers].
     */
    private suspend fun capForRetention(rows: List<VPNGateItem>): List<VPNGateItem> =
        vn.unlimit.vpngate.repository.capRetainedServers(
            rows = rows,
            manualHostNames = userHostNames(),
        )

    /** §33: provenance dump + raw sources for the debug panel. */
    fun debugPayload(conn: VPNGateConnection): VpnServerRepository.DebugPayload? =
        vpnServerRepository.debugPayload(conn.ip ?: "", conn.hostName ?: "")

    /**
     * Mode B refresh: the app collects server data itself from the
     * VPN Gate main HTML + official API + official mirrors, merges
     * them with the protocol-first engine and exposes the result.
     * The former GitHub-hosted JSON enrichment is gone; on network
     * failure the last-known-good snapshot or bundled seed is served instead.
     *
     * Every run goes through [ServerListUpdater], so a failed download can
     * never empty the list and two presses can never race.
     */
    fun getAPIData(isUserInitiated: Boolean = false) {
        if (isLoading.value == true) {
            return
        }
        if (isUserInitiated) {
            isRetried = false
        }
        Log.d(TAG, "Start vpnItem from multi-source collector (userInitiated=$isUserInitiated)")
        isLoading.postValue(true)
        isError.postValue(false)
        viewModelScope.launch {
            try {
                when (val outcome = updater.update()) {
                    is ServerListUpdater.Outcome.SkippedConcurrent -> {
                        // Another update owns the list right now — most often the
                        // scheduled one, on a cold start with nothing stored yet.
                        // Wait for it and publish its result; returning here
                        // without it would leave the screen on a spinner.
                        Log.i(TAG, "Server list update skipped: one is already running")
                        updater.awaitRunningUpdate()
                        val items = withContext(Dispatchers.IO) {
                            App.instance?.vpnGateItemDao?.getAll().orEmpty()
                        }
                        if (items.isEmpty()) {
                            Log.e(TAG, "Skipped update finished but the database is still empty")
                            isError.postValue(true)
                        } else {
                            vpnGateConnectionList.value = VPNGateConnectionList().fromVPNGateItems(items)
                            val savedAt = dataUtil.connectionCacheUpdatedAt
                            if (savedAt > 0L) lastUpdatedTime.postValue(savedAt)
                        }
                    }

                    is ServerListUpdater.Outcome.Updated -> {
                        isRetried = false
                        val list = withContext(Dispatchers.IO) {
                            val items = App.instance?.vpnGateItemDao?.getAll().orEmpty()
                            if (items.isEmpty()) null else VPNGateConnectionList().fromVPNGateItems(items)
                        }
                        if (list == null) {
                            Log.e(TAG, "Update reported success but the database is empty")
                            isError.postValue(true)
                        } else {
                            vpnGateConnectionList.value = list
                            val savedAt = System.currentTimeMillis()
                            lastUpdatedTime.postValue(savedAt)
                            // A dataset accepted from a fallback is usable but
                            // is not news, so the screen shows it without
                            // claiming the list was just updated.
                            if (!outcome.sourcedFromCache) {
                                dataUtil.setConnectionCacheUpdatedAt(savedAt)
                                // v1.0.54 (§1.6.47) PART 2.3: the legacy
                                // on-disk cache-expiry marker write
                                // (`markConnectionsCacheFresh`) is gone with the
                                // "Cache save time" setting. The persisted
                                // timestamp above is what the scheduler and the
                                // "last updated" label actually read.
                            }
                            val snapshotNote = when {
                                outcome.sourcedFromCache -> "snapshot kept"
                                outcome.snapshotSaved -> "saved"
                                else -> "not saved"
                            }
                            // v1.0.59 (§1.6.58) PART B3: two numbers, named.
                            // `outcome.serverCount` is the INCOMING dataset
                            // (`usableRows.size` in ServerListUpdater), never the
                            // table's size — reading it as "the dataset went
                            // 261 -> 170" is what produced the false V9 finding
                            // in BLOCK v1.0.58. `list.size()` is the stored table
                            // as it is right now, after the write.
                            Log.i(
                                TAG,
                                "Server list updated atomically: ${outcome.serverCount} incoming, " +
                                    "${list.size()} stored (snapshot $snapshotNote)",
                            )
                        }
                    }

                    is ServerListUpdater.Outcome.Preserved -> {
                        // The previous list is still valid and still stored —
                        // show it rather than blanking the screen.
                        val dbItems = withContext(Dispatchers.IO) {
                            App.instance?.vpnGateItemDao?.getAll().orEmpty()
                        }
                        if (dbItems.isNotEmpty()) {
                            vpnGateConnectionList.value =
                                VPNGateConnectionList().fromVPNGateItems(dbItems)
                            Log.i(
                                TAG,
                                "Update kept the current list (${outcome.reason}); " +
                                    "preserved ${dbItems.size} servers",
                            )
                            val existing = dataUtil.connectionCacheUpdatedAt
                            if (existing > 0L) lastUpdatedTime.postValue(existing)
                            // v1.0.26: a rejected refresh used to be entirely
                            // silent — the stored list is kept, so the screen
                            // looked exactly like "nothing to update". Only a
                            // DEGRADED_DATASET means "we fetched a list and
                            // deliberately threw it away", which is the case a
                            // user cannot infer from the screen. Rejection
                            // semantics are untouched; this only reports them.
                            if (outcome.reason == ServerListUpdater.Reason.DEGRADED_DATASET) {
                                refreshNotice.postValue(R.string.server_list_update_preserved)
                            }
                        } else {
                            Log.e(TAG, "Update failed (${outcome.reason}) and no list is stored")
                            isError.postValue(true)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Got exception when collecting servers", e)
                val dbItems = withContext(Dispatchers.IO) {
                    App.instance?.vpnGateItemDao?.getAll().orEmpty()
                }
                if (dbItems.isNotEmpty()) {
                    vpnGateConnectionList.value = VPNGateConnectionList().fromVPNGateItems(dbItems)
                    Log.i(TAG, "Exception during refresh; preserved ${dbItems.size} servers from database")
                } else {
                    isError.postValue(true)
                }
            } finally {
                isLoading.postValue(false)
            }
        }
    }
}

/**
 * v1.0.59 (§1.6.58) PART A: publish a `LiveData` value from **any** coroutine
 * context, including `Dispatchers.IO`.
 *
 * ## Why this exists
 *
 * `MutableLiveData.setValue` calls `assertMainThread("setValue")` and throws
 * `IllegalStateException: Cannot invoke setValue on a background thread` when
 * it is not. The CSV-import path (v1.0.54) did exactly that, from inside
 * `withContext(Dispatchers.IO)`, at two sites: `materializeImported()` (after a
 * successful import) and `removeImportedServer()` (after a delete). Both
 * completed the *data* work first, so the row was written and then the app
 * died — reproduced on device `5585fecc` at 16:07:13 and 16:09:54.
 *
 * ## Why not `postValue`
 *
 * `postValue` is thread-safe but it **defers**: the value lands on the main
 * loop later, and two rapid calls can coalesce into one. The callers here
 * return to the UI immediately after publishing (`importManualCsv` returns the
 * parse result that drives the summary dialog), so the existing ordering
 * guarantee is "the list is published before the caller continues". Switching
 * to `postValue` would have changed that to "eventually", which is exactly the
 * kind of ordering drift that produces a summary dialog next to a stale list.
 * A main-thread hop keeps the guarantee: the assignment happens on the main
 * thread, synchronously, before this function returns.
 *
 * [dispatcher] exists so a JVM test can drive the hop without a real Android
 * main looper; production always uses the default.
 */
internal suspend fun <T> publishLiveData(
    target: MutableLiveData<T>,
    value: T,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    withContext(dispatcher) { target.value = value }
}
