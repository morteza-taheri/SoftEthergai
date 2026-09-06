package vn.unlimit.vpngate.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.repository.VpnServerRepository
import vn.unlimit.vpngate.utils.DataUtil

class ConnectionListViewModel(application: Application) : BaseViewModel(application) {
    companion object {
        const val TAG = "VPNGateViewModel"
    }

    var dataUtil: DataUtil = App.instance!!.dataUtil!!

    val vpnGateConnectionList = MutableLiveData<VPNGateConnectionList>()
    init {
        vpnGateConnectionList.value = dataUtil.connectionsCache
    }
    private var isRetried = false
    var isError: MutableLiveData<Boolean> = MutableLiveData(false)

    private val vpnServerRepository =
        VpnServerRepository(cacheDir = application.filesDir)

    /** §33: provenance dump + raw sources for the debug panel. */
    fun debugPayload(conn: VPNGateConnection): VpnServerRepository.DebugPayload? =
        vpnServerRepository.debugPayload(conn.ip ?: "", conn.hostName ?: "")

    /**
     * Mode B refresh: the app collects server data itself from the
     * VPN Gate main HTML + official API + official mirrors, merges
     * them with the protocol-first engine and exposes the result.
     * The former GitHub-hosted JSON enrichment is gone; on network
     * failure the last-known-good snapshot is served instead.
     */
    fun getAPIData() {
        if (isLoading.value == true) {
            return
        }
        Log.d(TAG, "Start vpnItem from multi-source collector")
        isLoading.postValue(true)
        isError.postValue(false)
        viewModelScope.launch {
            try {
                val result = vpnServerRepository.refresh()
                val connectionList = result?.connectionList

                if ((connectionList == null || connectionList.size() == 0) && !isRetried) {
                    isRetried = true
                    dataUtil.setUseAlternativeServer(true)
                    isLoading.postValue(false)
                    getAPIData()
                    return@launch
                }

                if (connectionList == null || connectionList.size() == 0) {
                    Log.e(TAG, "Collector returned no servers")
                    isError.postValue(true)
                    return@launch
                }

                vpnGateConnectionList.value = connectionList
                val items = connectionList.toVPNGateItems()
                withContext(Dispatchers.IO) {
                    App.instance!!.vpnGateItemDao.deleteAll()
                    App.instance!!.vpnGateItemDao.insertAll(*items.toTypedArray())
                    val itemCount = App.instance!!.vpnGateItemDao.count()
                    Log.i(
                        TAG,
                        "Collected ${result.serverCount} servers" +
                            " (fromCache=${result.fromCache}). Total in database: $itemCount"
                    )
                    dataUtil.connectionsCache = connectionList
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Got exception when collecting servers", e)
                isError.postValue(true)
            } finally {
                isLoading.postValue(false)
            }
        }
    }
}
