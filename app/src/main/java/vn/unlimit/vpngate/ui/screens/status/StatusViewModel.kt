package vn.unlimit.vpngate.ui.screens.status

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNManagement
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.utils.TotalTraffic
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.service.SstpTrafficSnapshot
import kittoku.osc.service.SstpVpnService
import vn.unlimit.softether.SoftEtherTrafficSnapshot
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.App.Companion.instance
import vn.unlimit.vpngate.BuildConfig
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.NotificationUtil
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader

/** Immutable snapshot of every UI-observable field the old StatusFragment wrote to views. */
data class StatusUiState(
    val powerActivated: Boolean = false,
    val powerEnabled: Boolean = false,
    val connecting: Boolean = false,
    val statusText: String = "",
    val showCheckIp: Boolean = false,
    val excludedAppsCount: Int = 0,
    val downloadSession: String = "0 B",
    val downloadSpeed: String = "0 kbps",
    val uploadSession: String = "0 B",
    val uploadSpeed: String = "0 kbps",
    val totalDownload: String = "0 B",
    val totalUpload: String = "0 B",
)

/**
 * Port of StatusFragment's logic (service binding, OpenVPN/SoftEther/SSTP
 * listeners, connect/disconnect flows). Only the view layer moved to
 * Compose; every listener body and ordering is preserved.
 */
class StatusViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "StatusViewModel"
        const val START_VPN_SSTP: Int = 80
        const val ACTION_VPN_CONNECT: String = "kittoku.osc.connect"
        const val ACTION_VPN_DISCONNECT: String = "kittoku.osc.disconnect"
    }

    private val appContext: Context get() = getApplication<Application>().applicationContext
    val dataUtil: DataUtil = instance!!.dataUtil!!
    val state = MutableLiveData(StatusUiState())

    private fun update(transform: (StatusUiState) -> StatusUiState) {
        state.value = transform(state.value ?: StatusUiState())
    }

    private val handler = Handler(Looper.getMainLooper())

    var mVPNService: IOpenVPNServiceInternal? = null
    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            mVPNService = IOpenVPNServiceInternal.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mVPNService = null
        }
    }

    private var mVpnGateConnection: VPNGateConnection? = null
    private var isConnecting = false
    private var isAuthFailed = false
    private var vpnProfile: VpnProfile? = null
    private var isSSTPConnected = false
    private var isSSTPConnectOrDisconnecting = false
    private var isSoftEtherConnected = false
    private var lastOpenVpnInBytes = 0L
    private var lastOpenVpnOutBytes = 0L
    private var lastOpenVpnDiffInBytes = 0L
    private var lastOpenVpnDiffOutBytes = 0L
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener
    private lateinit var excludeAppsManager: vn.unlimit.vpngate.utils.ExcludeAppsManager

    // Results of the ActivityResult launchers — surfaced back to the screen
    // via the activity, which forwards here.
    var onVpnPermissionResult: ((Boolean) -> Unit)? = null

    private val isFreeConnected: Boolean
        get() = checkStatus() || isSoftEtherConnected || isSSTPConnected

    private val isAnyConnected: Boolean
        get() = checkStatus() || isSSTPConnected || isSoftEtherConnected

    private val connectionName: String
        get() {
            val method = dataUtil.getStringSetting(DataUtil.LAST_CONNECT_METHOD, "openvpn")
            val useUdp = if (method == "sstp") {
                false
            } else {
                dataUtil.getBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, false)
            }
            val baseName = mVpnGateConnection!!.getName(useUdp, method == "softether")
            return if (method == "sstp") {
                "MS-SSTP:$baseName"
            } else if (method == "softether") {
                "SoftEther:$baseName"
            } else {
                "OpenVPN:$baseName"
            }
        }

    // ------------------------------------------------------------------ init

    fun start(excludeAppsManager: vn.unlimit.vpngate.utils.ExcludeAppsManager) {
        this.excludeAppsManager = excludeAppsManager
        excludeAppsManager.setCallback(object :
            vn.unlimit.vpngate.utils.ExcludeAppsManager.ExcludeAppsCallback {
            override fun updateButtonText(count: Int) {
                update { it.copy(excludedAppsCount = count) }
            }

            override fun restartVpnIfRunning() {
                var vpnRestarted = false
                if (checkStatus()) {
                    stopVpn()
                    handler.postDelayed({ prepareVpn() }, 500)
                    vpnRestarted = true
                } else if (isSoftEtherConnected && mVpnGateConnection != null) {
                    stopVpn()
                    handler.postDelayed({
                        val useTcp = !dataUtil.getBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, false)
                        startSoftEtherConnection(useTcp)
                    }, 500)
                    vpnRestarted = true
                } else if (isSSTPConnected && mVpnGateConnection != null) {
                    startVpnSSTPService(ACTION_VPN_DISCONNECT)
                    handler.postDelayed({ connectSSTPVPN() }, 500)
                    vpnRestarted = true
                }
                if (vpnRestarted) {
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.vpn_restarted_for_settings),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        })
        initSSTP()
        bindData()
        excludeAppsManager.updateExcludeAppsButtonText { text ->
            // Count is embedded in the localized text; also expose the raw count.
            update { it.copy(excludedAppsCount = excludeAppsManager.getExcludedAppsCount()) }
        }
        registerListeners()
        bindService()
    }

    override fun onCleared() {
        try {
            VpnStatus.removeStateListener(stateListener)
            VpnStatus.removeByteCountListener(byteCountListener)
            SoftEtherVpnService.removeStateListener(softEtherStateListener)
            SoftEtherVpnService.removeTrafficListener(softEtherTrafficListener)
            SstpVpnService.removeTrafficListener(sstpTrafficListener)
            if (this::prefs.isInitialized) {
                prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            }
            TotalTraffic.saveTotal(appContext)
            appContext.unbindService(mConnection)
        } catch (e: Exception) {
            Log.e(TAG, "onCleared cleanup error", e)
        }
        super.onCleared()
    }

    fun onResumeActivity() {
        try {
            val intent = Intent(appContext, OpenVPNService::class.java)
            OpenVPNService.mDisplaySpeed =
                dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true)
            SoftEtherVpnService.mDisplaySpeed =
                dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true)
            SstpVpnService.mDisplaySpeed =
                dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true)
            intent.action = OpenVPNService.START_SERVICE
            appContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    fun onPauseActivity() {
        try {
            TotalTraffic.saveTotal(appContext)
            appContext.unbindService(mConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun bindService() {
        // onResumeActivity() handles (re)binding when the screen is visible.
    }

    private fun registerListeners() {
        VpnStatus.addStateListener(stateListener)
        VpnStatus.addByteCountListener(byteCountListener)
        SoftEtherVpnService.addStateListener(softEtherStateListener)
        SoftEtherVpnService.addTrafficListener(softEtherTrafficListener)
        SstpVpnService.addTrafficListener(sstpTrafficListener)
    }

    // ------------------------------------------------------------- bind data

    fun bindData() {
        try {
            mVpnGateConnection = dataUtil.lastVPNConnection
            val base = appContext.getString(
                if (isFreeConnected) R.string.tap_to_disconnect
                else R.string.tap_to_connect_last,
                connectionName,
            )
            if (isFreeConnected) {
                update {
                    it.copy(
                        powerActivated = true,
                        powerEnabled = true,
                        statusText = base,
                    )
                }
            } else if (mVpnGateConnection != null) {
                update {
                    it.copy(
                        powerActivated = false,
                        powerEnabled = true,
                        statusText = base,
                    )
                }
            } else {
                update {
                    it.copy(
                        powerActivated = false,
                        powerEnabled = false,
                        statusText = appContext.getString(R.string.no_last_vpn_server),
                    )
                }
            }
            refreshTrafficMonitor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ----------------------------------------------------------- OpenVPN

    private val stateListener = object : VpnStatus.StateListener {
        override fun setConnectedVPN(uuid: String) {}

        override fun updateState(
            state: String,
            logmessage: String,
            localizedResId: Int,
            status: ConnectionStatus,
            intent: Intent?,
        ) {
            handler.post {
                try {
                    if (!isSSTPConnected && !isSoftEtherConnected) {
                        update { it.copy(statusText = VpnStatus.getLastCleanLogMessage(appContext)) }
                    }
                    dataUtil.setBooleanSetting(DataUtil.USER_ALLOWED_VPN, true)
                    when (status) {
                        ConnectionStatus.LEVEL_CONNECTED -> {
                            isConnecting = false
                            isAuthFailed = false
                            update {
                                it.copy(
                                    powerActivated = true,
                                    connecting = false,
                                    showCheckIp = true,
                                    statusText = appContext.getString(
                                        R.string.connected_to, connectionName,
                                    ),
                                )
                            }
                            val isStartUpDetail =
                                dataUtil.getIntSetting(DataUtil.SETTING_STARTUP_SCREEN, 0) == 0
                            OpenVPNService.setNotificationActivityClass(
                                if (isStartUpDetail) vn.unlimit.vpngate.activities.DetailActivity::class.java
                                else vn.unlimit.vpngate.activities.MainActivity::class.java,
                            )
                        }

                        ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT ->
                            dataUtil.setBooleanSetting(DataUtil.USER_ALLOWED_VPN, false)

                        ConnectionStatus.LEVEL_NOTCONNECTED ->
                            if (!isConnecting && !isAuthFailed && !isSSTPConnected && !isSoftEtherConnected) {
                                update {
                                    it.copy(
                                        powerActivated = false,
                                        connecting = false,
                                        showCheckIp = false,
                                        statusText = appContext.getString(
                                            R.string.tap_to_connect_last, connectionName,
                                        ),
                                    )
                                }
                            }

                        ConnectionStatus.LEVEL_AUTH_FAILED -> {
                            isAuthFailed = true
                            isConnecting = false
                            update {
                                it.copy(
                                    powerActivated = false,
                                    connecting = false,
                                    showCheckIp = false,
                                    statusText = appContext.getString(R.string.vpn_auth_failure),
                                )
                            }
                        }

                        else -> update { it.copy(showCheckIp = false) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Status update error", e)
                }
            }
        }
    }

    private val byteCountListener = object : VpnStatus.ByteCountListener {
        override fun updateByteCount(`in`: Long, out: Long, diffIn: Long, diffOut: Long) {
            lastOpenVpnInBytes = `in`
            lastOpenVpnOutBytes = out
            lastOpenVpnDiffInBytes = diffIn
            lastOpenVpnDiffOutBytes = diffOut
            handler.post {
                if (isFreeConnected && !isSoftEtherConnected && !isSSTPConnected) {
                    updateOpenVpnTrafficUi()
                }
            }
        }
    }

    private fun updateOpenVpnTrafficUi() {
        update {
            it.copy(
                downloadSession = OpenVPNService.humanReadableByteCount(lastOpenVpnInBytes, false, appContext.resources),
                downloadSpeed = OpenVPNService.humanReadableByteCount(
                    lastOpenVpnDiffInBytes / OpenVPNManagement.mBytecountInterval,
                    true,
                    appContext.resources,
                ),
                uploadSession = OpenVPNService.humanReadableByteCount(lastOpenVpnOutBytes, false, appContext.resources),
                uploadSpeed = OpenVPNService.humanReadableByteCount(
                    lastOpenVpnDiffOutBytes / OpenVPNManagement.mBytecountInterval,
                    true,
                    appContext.resources,
                ),
                totalDownload = OpenVPNService.humanReadableByteCount(TotalTraffic.inTotal, false, appContext.resources),
                totalUpload = OpenVPNService.humanReadableByteCount(TotalTraffic.outTotal, false, appContext.resources),
            )
        }
    }

    // ------------------------------------------------------------ SoftEther

    private val softEtherStateListener = object : SoftEtherVpnService.StateListener {
        override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
            handler.post {
                when (state) {
                    SoftEtherVpnService.STATE_CONNECTED -> {
                        isSoftEtherConnected = true
                        isConnecting = false
                        update {
                            it.copy(
                                powerActivated = true,
                                connecting = false,
                                showCheckIp = true,
                                statusText = appContext.getString(
                                    R.string.tap_to_disconnect, connectionName,
                                ),
                            )
                        }
                        renderSoftEtherTraffic(SoftEtherVpnService.currentTrafficSnapshot)
                    }
                    SoftEtherVpnService.STATE_DISCONNECTED,
                    SoftEtherVpnService.STATE_ERROR,
                    -> {
                        isSoftEtherConnected = false
                        isConnecting = false
                        refreshTrafficMonitor()
                        if (!isAnyConnected) {
                            update {
                                it.copy(
                                    powerActivated = false,
                                    connecting = false,
                                    showCheckIp = false,
                                    statusText = appContext.getString(
                                        R.string.tap_to_connect_last, connectionName,
                                    ),
                                )
                            }
                        }
                    }
                    SoftEtherVpnService.STATE_CONNECTING -> {
                        isConnecting = true
                        update {
                            it.copy(
                                powerActivated = true,
                                connecting = true,
                                statusText = appContext.getString(R.string.softether_connecting),
                            )
                        }
                    }
                    SoftEtherVpnService.STATE_TLS_HANDSHAKE -> {
                        isConnecting = true
                        update {
                            it.copy(
                                powerActivated = true,
                                connecting = true,
                                statusText = appContext.getString(R.string.softether_tls_handshake),
                            )
                        }
                    }
                    SoftEtherVpnService.STATE_PROTOCOL_HANDSHAKE -> {
                        isConnecting = true
                        update {
                            it.copy(
                                powerActivated = true,
                                connecting = true,
                                statusText = appContext.getString(R.string.softether_protocol_handshake),
                            )
                        }
                    }
                    SoftEtherVpnService.STATE_AUTHENTICATING -> {
                        isConnecting = true
                        update {
                            it.copy(
                                powerActivated = true,
                                connecting = true,
                                statusText = appContext.getString(R.string.softether_authenticating),
                            )
                        }
                    }
                    SoftEtherVpnService.STATE_SESSION_SETUP -> {
                        isConnecting = true
                        update {
                            it.copy(
                                powerActivated = true,
                                connecting = true,
                                statusText = appContext.getString(R.string.softether_session_setup),
                            )
                        }
                    }
                    SoftEtherVpnService.STATE_DISCONNECTING -> {
                        isConnecting = false
                        update {
                            it.copy(
                                powerActivated = false,
                                connecting = false,
                                statusText = appContext.getString(R.string.softether_disconnecting),
                            )
                        }
                    }
                }
            }
        }
    }

    private val softEtherTrafficListener = object : SoftEtherVpnService.TrafficListener {
        override fun onSoftEtherTrafficUpdated(snapshot: SoftEtherTrafficSnapshot) {
            if (!isSoftEtherConnected) return
            handler.post { renderSoftEtherTraffic(snapshot) }
        }
    }

    private fun renderSoftEtherTraffic(snapshot: SoftEtherTrafficSnapshot) {
        TotalTraffic.getTotalTraffic(appContext)
        update {
            it.copy(
                downloadSession = OpenVPNService.humanReadableByteCount(snapshot.inBytes, false, appContext.resources),
                downloadSpeed = OpenVPNService.humanReadableByteCount(snapshot.inBytesPerSecond(), true, appContext.resources),
                uploadSession = OpenVPNService.humanReadableByteCount(snapshot.outBytes, false, appContext.resources),
                uploadSpeed = OpenVPNService.humanReadableByteCount(snapshot.outBytesPerSecond(), true, appContext.resources),
                totalDownload = OpenVPNService.humanReadableByteCount(TotalTraffic.inTotal, false, appContext.resources),
                totalUpload = OpenVPNService.humanReadableByteCount(TotalTraffic.outTotal, false, appContext.resources),
            )
        }
    }

    // ----------------------------------------------------------------- SSTP

    private val sstpTrafficListener = object : SstpVpnService.TrafficListener {
        override fun onSstpTrafficUpdated(snapshot: SstpTrafficSnapshot) {
            if (!isSSTPConnected) return
            handler.post { renderSstpTraffic(snapshot) }
        }
    }

    private fun renderSstpTraffic(snapshot: SstpTrafficSnapshot) {
        TotalTraffic.getTotalTraffic(appContext)
        update {
            it.copy(
                downloadSession = OpenVPNService.humanReadableByteCount(snapshot.inBytes, false, appContext.resources),
                downloadSpeed = OpenVPNService.humanReadableByteCount(snapshot.inBytesPerSecond(), true, appContext.resources),
                uploadSession = OpenVPNService.humanReadableByteCount(snapshot.outBytes, false, appContext.resources),
                uploadSpeed = OpenVPNService.humanReadableByteCount(snapshot.outBytesPerSecond(), true, appContext.resources),
                totalDownload = OpenVPNService.humanReadableByteCount(TotalTraffic.inTotal, false, appContext.resources),
                totalUpload = OpenVPNService.humanReadableByteCount(TotalTraffic.outTotal, false, appContext.resources),
            )
        }
    }

    private fun refreshTrafficMonitor() {
        val lastConnectMethod = dataUtil.getStringSetting(DataUtil.LAST_CONNECT_METHOD, "openvpn")
        when {
            isSoftEtherConnected ->
                renderSoftEtherTraffic(SoftEtherVpnService.currentTrafficSnapshot)
            isSSTPConnected ->
                renderSstpTraffic(SstpVpnService.currentTrafficSnapshot)
            checkStatus() -> updateOpenVpnTrafficUi()
            lastConnectMethod == "softether" &&
                    SoftEtherVpnService.lastTrafficSnapshot != SoftEtherTrafficSnapshot.EMPTY ->
                renderSoftEtherTraffic(SoftEtherVpnService.lastTrafficSnapshot)
            lastConnectMethod == "sstp" &&
                    SstpVpnService.lastTrafficSnapshot != SstpTrafficSnapshot.EMPTY ->
                renderSstpTraffic(SstpVpnService.lastTrafficSnapshot)
            else -> resetTrafficUi()
        }
    }

    private fun resetTrafficUi() {
        update {
            it.copy(
                downloadSession = OpenVPNService.humanReadableByteCount(0, false, appContext.resources),
                downloadSpeed = OpenVPNService.humanReadableByteCount(0, true, appContext.resources),
                uploadSession = OpenVPNService.humanReadableByteCount(0, false, appContext.resources),
                uploadSpeed = OpenVPNService.humanReadableByteCount(0, true, appContext.resources),
                totalDownload = OpenVPNService.humanReadableByteCount(0, false, appContext.resources),
                totalUpload = OpenVPNService.humanReadableByteCount(0, false, appContext.resources),
            )
        }
    }

    private fun initSSTP() {
        prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            handler.post {
                if (OscPrefKey.ROOT_STATE.toString() == key) {
                    val newState = prefs.getBoolean(OscPrefKey.ROOT_STATE.toString(), false)
                    if (!newState) {
                        update {
                            it.copy(
                                statusText = appContext.getString(
                                    if (isSSTPConnectOrDisconnecting) R.string.sstp_disconnected
                                    else R.string.sstp_disconnected_by_error,
                                ),
                            )
                        }
                        isSSTPConnected = false
                        update { it.copy(showCheckIp = false) }
                        bindData()
                        refreshTrafficMonitor()
                    }
                    isSSTPConnectOrDisconnecting = false
                }
                if (OscPrefKey.HOME_CONNECTED_IP.toString() == key) {
                    val connectedIp = prefs.getString(OscPrefKey.HOME_CONNECTED_IP.toString(), "")
                    if ("" != connectedIp) {
                        update {
                            it.copy(
                                statusText = appContext.getString(R.string.sstp_connected, connectedIp),
                                powerActivated = true,
                                connecting = false,
                                showCheckIp = true,
                            )
                        }
                        isSSTPConnected = true
                        renderSstpTraffic(SstpVpnService.currentTrafficSnapshot)
                    }
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        isSSTPConnected = prefs.getBoolean(OscPrefKey.ROOT_STATE.toString(), false)
        if (isSSTPConnected) {
            val connectedIp = prefs.getString(OscPrefKey.HOME_CONNECTED_IP.toString(), "")
            if (connectedIp!!.isNotEmpty()) {
                update {
                    it.copy(
                        statusText = appContext.getString(R.string.sstp_connected, connectedIp),
                        powerActivated = true,
                        connecting = false,
                        showCheckIp = true,
                    )
                }
                refreshTrafficMonitor()
            }
        }
    }

    // -------------------------------------------------------------- actions

    fun onPowerButtonClicked() {
        if (mVpnGateConnection == null) {
            return
        }
        if (isAnyConnected) {
            if (isFreeConnected) {
                if (checkStatus() || isSoftEtherConnected) {
                    stopVpn()
                    isConnecting = false
                    update {
                        it.copy(
                            powerActivated = false,
                            connecting = false,
                            statusText = appContext.getString(R.string.disconnecting),
                        )
                    }
                } else {
                    startVpnSSTPService(ACTION_VPN_DISCONNECT)
                    update {
                        it.copy(
                            powerActivated = false,
                            connecting = false,
                            statusText = appContext.getString(R.string.sstp_disconnecting),
                        )
                    }
                }
            }
        } else {
            if (isConnecting) {
                val method = dataUtil.getStringSetting(DataUtil.LAST_CONNECT_METHOD, "openvpn")
                when (method) {
                    "softether" -> {
                        val intent = Intent(appContext, SoftEtherVpnService::class.java)
                        intent.action = SoftEtherVpnService.ACTION_DISCONNECT
                        appContext.startService(intent)
                    }
                    "sstp" -> startVpnSSTPService(ACTION_VPN_DISCONNECT)
                    else -> stopVpn()
                }
                isConnecting = false
                update {
                    it.copy(
                        powerActivated = false,
                        connecting = false,
                        statusText = appContext.getString(R.string.disconnecting),
                    )
                }
            } else {
                val method = dataUtil.getStringSetting(DataUtil.LAST_CONNECT_METHOD, "openvpn")
                when (method) {
                    "sstp" -> handleSSTPBtn()
                    "softether" -> {
                        val useTcp = !dataUtil.getBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, false)
                        startSoftEtherConnection(useTcp)
                        update {
                            it.copy(
                                statusText = appContext.getString(R.string.connecting) + " " + connectionName,
                                powerActivated = true,
                                connecting = true,
                            )
                        }
                        isConnecting = true
                    }
                    else -> {
                        prepareVpn()
                        update {
                            it.copy(
                                statusText = appContext.getString(R.string.connecting) + " " + connectionName,
                                powerActivated = true,
                                connecting = true,
                            )
                        }
                        isConnecting = true
                    }
                }
            }
        }
    }

    fun clearStatistics() {
        TotalTraffic.clearTotal(appContext)
        Toast.makeText(appContext, "Statistics clear completed", Toast.LENGTH_SHORT).show()
        update {
            it.copy(
                totalUpload = OpenVPNService.humanReadableByteCount(0, false, appContext.resources),
                totalDownload = OpenVPNService.humanReadableByteCount(0, false, appContext.resources),
            )
        }
    }

    fun openExcludeApps(onOpen: () -> Unit) {
        onOpen()
    }

    fun prepareVpn() {
        if (loadVpnProfile()) {
            startVpn()
        } else {
            Toast.makeText(appContext, appContext.getString(R.string.error_load_profile), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission(activity: android.app.Activity?) {
        (activity as? androidx.appcompat.app.AppCompatActivity)?.let {
            NotificationUtil(it).requestPermission()
        }
    }

    private fun startVpn(activity: android.app.Activity? = null) {
        requestNotificationPermission(activity)
        val intent = VpnService.prepare(appContext)
        if (intent != null) {
            VpnStatus.updateStateString(
                "USER_VPN_PERMISSION", "", de.blinkt.openvpn.R.string.state_user_vpn_permission,
                ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT,
            )
            try {
                onVpnPermissionResult = { granted ->
                    if (granted) {
                        vpnProfile?.let { profile ->
                            VPNLaunchHelper.startOpenVpn(profile, appContext, null, true)
                        }
                    }
                }
                // The screen launches the system dialog and calls
                // vpnPermissionResult() with the outcome.
                notifyPermissionRequest?.invoke(intent)
            } catch (ane: ActivityNotFoundException) {
                VpnStatus.logError(de.blinkt.openvpn.R.string.no_vpn_support_image)
            }
        } else {
            VPNLaunchHelper.startOpenVpn(vpnProfile, appContext, null, true)
        }
    }

    /** Set by the screen: called when the OS VPN permission dialog is needed. */
    var notifyPermissionRequest: ((Intent) -> Unit)? = null

    fun vpnPermissionResult(granted: Boolean) {
        val callback = onVpnPermissionResult
        onVpnPermissionResult = null
        if (granted) {
            callback?.invoke(true)
            dataUtil.setBooleanSetting(DataUtil.USER_ALLOWED_VPN, true)
        } else {
            dataUtil.setBooleanSetting(DataUtil.USER_ALLOWED_VPN, false)
        }
    }

    private fun stopVpn() {
        ProfileManager.setConntectedVpnProfileDisconnected(appContext)
        if (mVPNService != null) {
            try {
                mVPNService!!.stopVPN(false)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
        if (isSoftEtherConnected) {
            val intent = Intent(appContext, SoftEtherVpnService::class.java)
            intent.action = SoftEtherVpnService.ACTION_DISCONNECT
            appContext.startService(intent)
        }
    }

    private fun checkStatus(): Boolean {
        return try {
            VpnStatus.isVPNActive()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ------------------------------------------------------------- SoftEther

    fun softEtherPermissionGranted() {
        handler.postDelayed({ startSoftEtherConnection() }, 500)
    }

    private fun startSoftEtherConnection(useTcp: Boolean = true, activity: android.app.Activity? = null) {
        requestNotificationPermission(activity)
        if (mVpnGateConnection == null) {
            Toast.makeText(appContext, R.string.error_load_profile, Toast.LENGTH_SHORT).show()
            return
        }
        val vpnIntent = VpnService.prepare(appContext)
        if (vpnIntent != null) {
            try {
                onSoftEtherPermissionResult = { granted ->
                    if (granted) {
                        handler.postDelayed({ startSoftEtherConnection(useTcp) }, 500)
                    } else {
                        Toast.makeText(appContext, R.string.error_unknown, Toast.LENGTH_SHORT).show()
                    }
                }
                notifyPermissionRequest?.invoke(vpnIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "OS does not support VPN", e)
            }
            return
        }

        val serverName = mVpnGateConnection!!.getName(!useTcp, true)
        val useDomainToConnect = dataUtil.getBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, false)
        val serverHost = if (useDomainToConnect) {
            mVpnGateConnection!!.calculateHostName
        } else {
            mVpnGateConnection!!.ip!!
        }
        val isUdpOnly = mVpnGateConnection!!.isUdpOnly
        val serverPort = when {
            isUdpOnly -> mVpnGateConnection!!.seUdpPort
            useTcp -> mVpnGateConnection!!.seTcpPort
            else -> mVpnGateConnection!!.seUdpPort
        }
        val config = vn.unlimit.softether.model.ConnectionConfig(
            serverHost = serverHost,
            serverPort = serverPort,
            username = "vpn",
            password = "vpn",
            virtualHub = "vpngate",
            sessionName = serverName,
            localAddress = "10.21.0.2",
            prefixLength = 19,
            dnsServer = resolvePrimaryDns(),
            secondaryDnsServer = resolveSecondaryDns(),
            routes = listOf(vn.unlimit.softether.model.Route("0.0.0.0", 0)),
            mtu = 1500,
            useTcp = useTcp,
            udpPort = mVpnGateConnection!!.seUdpPort,
            udpOnly = isUdpOnly,
            clientProductName = "VPN Gate Connector Pro",
            clientVersion = BuildConfig.VERSION_NAME,
            clientBuild = BuildConfig.VERSION_CODE,
            maxConnections = dataUtil.getSoftEtherMaxConnections(),
        )
        val isStartUpDetail = dataUtil.getIntSetting(DataUtil.SETTING_STARTUP_SCREEN, 0) == 0
        SoftEtherVpnService.notificationTargetActivity =
            if (isStartUpDetail) vn.unlimit.vpngate.activities.DetailActivity::class.java
            else vn.unlimit.vpngate.activities.MainActivity::class.java
        val intent = Intent(appContext, SoftEtherVpnService::class.java).apply {
            action = SoftEtherVpnService.ACTION_CONNECT
            putExtra(SoftEtherVpnService.EXTRA_CONFIG, config)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
        dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, !useTcp)
        dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_SOFTETHER_USE_UDP, !useTcp)
        dataUtil.setStringSetting(DataUtil.LAST_CONNECT_METHOD, "softether")
    }

    var onSoftEtherPermissionResult: ((Boolean) -> Unit)? = null

    // --------------------------------------------------------------- SSTP

    fun sstpPermissionResult(granted: Boolean) {
        handleActivityResult(START_VPN_SSTP, if (granted) Activity.RESULT_OK else Activity.RESULT_CANCELED)
    }

    private fun handleActivityResult(requestCode: Int, resultCode: Int) {
        try {
            if (resultCode == Activity.RESULT_OK) {
                if (requestCode == START_VPN_SSTP) {
                    connectSSTPVPN()
                }
                dataUtil.setBooleanSetting(DataUtil.USER_ALLOWED_VPN, true)
            } else {
                dataUtil.setBooleanSetting(DataUtil.USER_ALLOWED_VPN, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleActivityResult error", e)
        }
    }

    private fun startSSTPVPN() {
        requestNotificationPermission(null)
        if (checkStatus()) {
            stopVpn()
        }
        val intent = VpnService.prepare(appContext)
        if (intent != null) {
            try {
                onSstpPermissionRequest?.invoke(intent)
            } catch (_: ActivityNotFoundException) {
                Log.e(TAG, "OS does not support VPN")
            }
        } else {
            handleActivityResult(START_VPN_SSTP, Activity.RESULT_OK)
        }
    }

    var onSstpPermissionRequest: ((Intent) -> Unit)? = null

    private fun connectSSTPVPN() {
        if (mVpnGateConnection == null) return
        val excludedApps = instance?.excludedAppDao?.getAllExcludedApps() ?: emptyList()
        val excludedPackageNames = excludedApps.map { it.packageName }.toSet()
        prefs.edit {
            putString(OscPrefKey.HOME_HOSTNAME.toString(), mVpnGateConnection!!.calculateHostName)
            putString(
                OscPrefKey.HOME_COUNTRY.toString(),
                mVpnGateConnection!!.countryShort?.uppercase() ?: "",
            )
            putString(OscPrefKey.HOME_USERNAME.toString(), "vpn")
            putString(OscPrefKey.HOME_PASSWORD.toString(), "vpn")
            putString(OscPrefKey.SSL_PORT.toString(), mVpnGateConnection!!.tcpPort.toString())
            putStringSet(OscPrefKey.ROUTE_EXCLUDED_APPS.toString(), excludedPackageNames)
        }
        update {
            it.copy(
                powerActivated = true,
                connecting = true,
                statusText = appContext.getString(R.string.sstp_connecting),
            )
        }
        startVpnSSTPService(ACTION_VPN_CONNECT)
    }

    private fun handleSSTPBtn() {
        isSSTPConnectOrDisconnecting = true
        val sstpHostName = prefs.getString(OscPrefKey.HOME_HOSTNAME.toString(), "")
        if (isSSTPConnected && sstpHostName != mVpnGateConnection!!.calculateHostName) {
            startVpnSSTPService(ACTION_VPN_DISCONNECT)
            handler.postDelayed({ connectSSTPVPN() }, 100)
        } else if (!isSSTPConnected) {
            dataUtil.lastVPNConnection = mVpnGateConnection
            startSSTPVPN()
        } else {
            startVpnSSTPService(ACTION_VPN_DISCONNECT)
            update {
                it.copy(
                    powerActivated = false,
                    connecting = false,
                    statusText = appContext.getString(R.string.sstp_disconnecting),
                )
            }
        }
    }

    private fun startVpnSSTPService(action: String) {
        if (action == ACTION_VPN_CONNECT) {
            val isStartUpDetail = dataUtil.getIntSetting(DataUtil.SETTING_STARTUP_SCREEN, 0) == 0
            val targetClass = if (isStartUpDetail) {
                vn.unlimit.vpngate.activities.DetailActivity::class.java
            } else {
                vn.unlimit.vpngate.activities.MainActivity::class.java
            }
            SstpVpnService.notificationTargetActivity = targetClass
            SstpVpnService.mDisplaySpeed =
                dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true)
        }
        val intent = Intent(appContext, SstpVpnService::class.java).setAction(action)
        if (action == ACTION_VPN_CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    // --------------------------------------------------------------- DNS

    private fun resolvePrimaryDns(): String {
        return when {
            dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false) ->
                AppConfig.getString("vpn_dns_block_ads_primary").ifEmpty { "8.8.8.8" }
            dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false) ->
                dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_1, "8.8.8.8") ?: "8.8.8.8"
            else -> "8.8.8.8"
        }
    }

    private fun resolveSecondaryDns(): String {
        return when {
            dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false) ->
                AppConfig.getString("vpn_dns_block_ads_alternative").ifEmpty { "8.8.4.4" }
            dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false) ->
                dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_2, "8.8.4.4")
                    ?.takeIf { it.isNotEmpty() } ?: "8.8.4.4"
            else -> "8.8.4.4"
        }
    }

    // ---------------------------------------------------------- OpenVPN

    private fun loadVpnProfile(): Boolean {
        try {
            val useUdp = dataUtil.getBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, false)
            dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, useUdp)
            val data = if (useUdp) {
                mVpnGateConnection!!.openVpnConfigDataUdp!!.toByteArray()
            } else {
                mVpnGateConnection!!.openVpnConfigData!!.toByteArray()
            }
            val cp = ConfigParser()
            val isr = InputStreamReader(ByteArrayInputStream(data))
            cp.parseConfig(isr)
            vpnProfile = cp.convertProfile()
            vpnProfile!!.mName = mVpnGateConnection!!.getName(useUdp)
            vpnProfile?.mCompatMode = App.VPN_PROFILE_COMPAT_MODE_24X
            excludeAppsManager.configureSplitTunneling(vpnProfile)
            if (dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false) ||
                dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false)
            ) {
                vpnProfile!!.mOverrideDNS = true
                vpnProfile!!.mDNS1 = resolvePrimaryDns()
                vpnProfile!!.mDNS2 = resolveSecondaryDns()
            }
            ProfileManager.setTemporaryProfile(appContext, vpnProfile)
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } catch (e: ConfigParser.ConfigParseError) {
            e.printStackTrace()
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    val lastConnection: VPNGateConnection?
        get() = mVpnGateConnection
}
