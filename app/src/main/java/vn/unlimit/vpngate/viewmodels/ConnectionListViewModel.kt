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
import vn.unlimit.vpngate.data.remote.EmergencySourceConfig
import vn.unlimit.vpngate.data.remote.EmergencySourceConfigDao
import vn.unlimit.vpngate.data.remote.EmergencySourceConfigEntity
import vn.unlimit.vpngate.data.remote.SourceKind
import vn.unlimit.vpngate.data.remote.SourceUrl
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.models.VPNGateItem
import vn.unlimit.vpngate.repository.ServerListUpdates
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
    init {
        vpnGateConnectionList.value = dataUtil.connectionsCache
        lastUpdatedTime.value = dataUtil.connectionCacheUpdatedAt
    }
    private var isRetried = false
    var isError: MutableLiveData<Boolean> = MutableLiveData(false)

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
                                dataUtil.markConnectionsCacheFresh()
                            }
                            val snapshotNote = when {
                                outcome.sourcedFromCache -> "snapshot kept"
                                outcome.snapshotSaved -> "saved"
                                else -> "not saved"
                            }
                            Log.i(
                                TAG,
                                "Server list updated atomically: ${outcome.serverCount} servers " +
                                    "(snapshot $snapshotNote)",
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
