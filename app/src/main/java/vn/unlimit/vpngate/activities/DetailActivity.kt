package vn.unlimit.vpngate.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNManagement
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.core.VpnStatus.ByteCountListener
import de.blinkt.openvpn.utils.TotalTraffic
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.service.SstpTrafficSnapshot
import kittoku.osc.service.SstpVpnService
import vn.unlimit.softether.SoftEtherTrafficSnapshot
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.BuildConfig
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.ui.screens.detail.DetailScreen
import vn.unlimit.vpngate.ui.screens.detail.DetailUiState
import vn.unlimit.vpngate.ui.screens.detail.ProtocolOption
import vn.unlimit.vpngate.ui.screens.detail.VpnProtocol
import vn.unlimit.vpngate.ui.theme.VpnGateTheme
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.ExcludeAppsManager
import vn.unlimit.vpngate.utils.Ipv6Ula
import vn.unlimit.vpngate.utils.NotificationUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader

/**
 * Detail: Compose-hosted server details + connect flows. All VPN connect
 * logic is ported verbatim from the ViewBinding version; UI state is
 * exposed through a LiveData [uiState] that the screen observes.
 */
class DetailActivity : AppCompatActivity(), VpnStatus.StateListener, ByteCountListener {
    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            mVPNService = IOpenVPNServiceInternal.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mVPNService = null
        }
    }
    private lateinit var dataUtil: DataUtil
    private var mVpnGateConnection: VPNGateConnection? = null
    private lateinit var vpnProfile: VpnProfile
    private lateinit var prefs: SharedPreferences
    private lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener
    private var isConnecting = false
    private var isAuthFailed = false
    private var isSSTPConnectOrDisconnecting = false
    private var isSSTPConnected = false
    private lateinit var excludeAppsManager: ExcludeAppsManager
    private var isSoftEtherConnected = false

    @Volatile
    private var isSoftEtherConnecting = false
    private var lastDisconnectTime: Long = 0
    private val disconnectCooldownMS = 1000L // 1 second cooldown after disconnect
    private var pendingSoftEtherUseTcp: Boolean = true
    private var notificationPermissionRequested = false

    /** UI state consumed by the Compose screen. */
    val uiState = MutableLiveData(DetailUiState())
    val connection: VPNGateConnection? get() = mVpnGateConnection

    private fun update(transform: (DetailUiState) -> DetailUiState) {
        uiState.value = transform(uiState.value ?: DetailUiState())
    }

    private val softEtherStateListener = object : SoftEtherVpnService.StateListener {
        override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
            Log.d(TAG, if (assignedIp.isNotEmpty()) "SoftEther state: $state ip=$assignedIp" else "SoftEther state: $state")

            if ((state == SoftEtherVpnService.STATE_DISCONNECTED || state == SoftEtherVpnService.STATE_ERROR)
                && !isSoftEtherConnected && !isSoftEtherConnecting
            ) {
                return
            }

            when (state) {
                SoftEtherVpnService.STATE_CONNECTING -> {
                    isSoftEtherConnected = false
                    isConnecting = true
                    isSoftEtherConnecting = true
                    update {
                        it.copy(
                            connecting = true,
                            connectText = getString(R.string.cancel),
                            statusText = getString(R.string.softether_connecting),
                            showCheckIp = false,
                            showNetStats = false,
                        )
                    }
                }
                SoftEtherVpnService.STATE_TLS_HANDSHAKE -> {
                    isSoftEtherConnected = false
                    isConnecting = true
                    isSoftEtherConnecting = true
                    update {
                        it.copy(
                            connecting = true,
                            connectText = getString(R.string.cancel),
                            statusText = getString(R.string.softether_tls_handshake),
                            showCheckIp = false,
                            showNetStats = false,
                        )
                    }
                }
                SoftEtherVpnService.STATE_PROTOCOL_HANDSHAKE -> {
                    isSoftEtherConnected = false
                    isConnecting = true
                    isSoftEtherConnecting = true
                    update {
                        it.copy(
                            connecting = true,
                            connectText = getString(R.string.cancel),
                            statusText = getString(R.string.softether_protocol_handshake),
                            showCheckIp = false,
                            showNetStats = false,
                        )
                    }
                }
                SoftEtherVpnService.STATE_AUTHENTICATING -> {
                    isSoftEtherConnected = false
                    isConnecting = true
                    isSoftEtherConnecting = true
                    update {
                        it.copy(
                            connecting = true,
                            connectText = getString(R.string.cancel),
                            statusText = getString(R.string.softether_authenticating),
                            showCheckIp = false,
                            showNetStats = false,
                        )
                    }
                }
                SoftEtherVpnService.STATE_SESSION_SETUP -> {
                    isSoftEtherConnected = false
                    isConnecting = true
                    isSoftEtherConnecting = true
                    update {
                        it.copy(
                            connecting = true,
                            connectText = getString(R.string.cancel),
                            statusText = getString(R.string.softether_session_setup),
                            showCheckIp = false,
                            showNetStats = false,
                        )
                    }
                }
                SoftEtherVpnService.STATE_CONNECTED -> {
                    isSoftEtherConnected = true
                    isConnecting = false
                    isSoftEtherConnecting = false
                    if (isCurrent) {
                        update {
                            it.copy(
                                connecting = false,
                                connected = true,
                                connectText = getString(R.string.disconnect),
                                statusText = getString(R.string.softether_connected, assignedIp),
                                showNetStats = true,
                                showCheckIp = true,
                            )
                        }
                        renderSoftEtherTraffic(SoftEtherVpnService.currentTrafficSnapshot)
                    }
                }
                SoftEtherVpnService.STATE_DISCONNECTING -> {
                    isSoftEtherConnected = true
                    isConnecting = false
                    isSoftEtherConnecting = false
                    update {
                        it.copy(
                            connecting = false,
                            connected = false,
                            connectText = getString(R.string.connect_to_this_server),
                            statusText = getString(R.string.softether_disconnecting),
                            showNetStats = false,
                            showCheckIp = false,
                        )
                    }
                }
                SoftEtherVpnService.STATE_DISCONNECTED -> {
                    isSoftEtherConnected = false
                    isConnecting = false
                    isSoftEtherConnecting = false
                    update {
                        it.copy(
                            connecting = false,
                            connected = false,
                            connectText = getString(R.string.connect_to_this_server),
                            statusText = getString(R.string.softether_disconnected),
                            showNetStats = false,
                            showCheckIp = false,
                        )
                    }
                }
                SoftEtherVpnService.STATE_ERROR -> {
                    isSoftEtherConnected = false
                    isConnecting = false
                    isSoftEtherConnecting = false
                    update {
                        it.copy(
                            connecting = false,
                            connected = false,
                            connectText = getString(R.string.retry_connect),
                            statusText = getString(R.string.softether_disconnected_by_error),
                            showNetStats = false,
                            showCheckIp = false,
                        )
                    }
                }
                else -> Log.w(TAG, "Unknown SoftEther state: $state")
            }
        }
    }

    private val softEtherTrafficListener = object : SoftEtherVpnService.TrafficListener {
        override fun onSoftEtherTrafficUpdated(snapshot: SoftEtherTrafficSnapshot) {
            if (!isSoftEtherConnected || !isCurrent) return
            renderSoftEtherTraffic(snapshot)
        }
    }

    private val sstpTrafficListener = object : SstpVpnService.TrafficListener {
        override fun onSstpTrafficUpdated(snapshot: SstpTrafficSnapshot) {
            if (!isSSTPConnected || !isCurrent) return
            renderSstpTraffic(snapshot)
        }
    }

    private fun renderSoftEtherTraffic(snapshot: SoftEtherTrafficSnapshot) {
        update {
            it.copy(
                netStats = String.format(
                    getString(de.blinkt.openvpn.R.string.statusline_bytecount),
                    OpenVPNService.humanReadableByteCount(snapshot.inBytes, false, resources),
                    OpenVPNService.humanReadableByteCount(snapshot.inBytesPerSecond(), true, resources),
                    OpenVPNService.humanReadableByteCount(snapshot.outBytes, false, resources),
                    OpenVPNService.humanReadableByteCount(snapshot.outBytesPerSecond(), true, resources),
                ),
                showNetStats = true,
            )
        }
    }

    private fun renderSstpTraffic(snapshot: SstpTrafficSnapshot) {
        update {
            it.copy(
                netStats = String.format(
                    getString(de.blinkt.openvpn.R.string.statusline_bytecount),
                    OpenVPNService.humanReadableByteCount(snapshot.inBytes, false, resources),
                    OpenVPNService.humanReadableByteCount(snapshot.inBytesPerSecond(), true, resources),
                    OpenVPNService.humanReadableByteCount(snapshot.outBytes, false, resources),
                    OpenVPNService.humanReadableByteCount(snapshot.outBytesPerSecond(), true, resources),
                ),
                showNetStats = true,
            )
        }
    }

    private fun startVpnSSTPService(action: String) {
        if (action == ACTION_VPN_CONNECT) {
            val isStartUpDetail = dataUtil.getIntSetting(DataUtil.SETTING_STARTUP_SCREEN, 0) == 0
            val targetClass = if (isStartUpDetail) DetailActivity::class.java else MainActivity::class.java
            SstpVpnService.notificationTargetActivity = targetClass
            SstpVpnService.mDisplaySpeed =
                dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true)
        }
        val intent = Intent(applicationContext, SstpVpnService::class.java).setAction(action)
        if (action == ACTION_VPN_CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
    }

    private fun initSSTP() {
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _: SharedPreferences?, key: String? ->
                if (OscPrefKey.ROOT_STATE.toString() == key) {
                    val newState = prefs.getBoolean(OscPrefKey.ROOT_STATE.toString(), false)
                    if (!newState) {
                        val statusRes = if (isSSTPConnectOrDisconnecting) {
                            if (isSSTPConnected) R.string.sstp_disconnected else R.string.canceled
                        } else {
                            R.string.sstp_disconnected_by_error
                        }
                        isSSTPConnected = false
                        isConnecting = false
                        isSSTPConnectOrDisconnecting = false
                        update {
                            it.copy(
                                statusText = getString(statusRes),
                                showCheckIp = false,
                                showNetStats = false,
                                connected = false,
                                connectText = getString(R.string.connect_to_this_server),
                            )
                        }
                    }
                }
                if (OscPrefKey.HOME_CONNECTED_IP.toString() == key) {
                    val connectedIp = prefs.getString(OscPrefKey.HOME_CONNECTED_IP.toString(), "")
                    if (connectedIp!!.isNotEmpty()) {
                        isSSTPConnected = true
                        isConnecting = false
                        isSSTPConnectOrDisconnecting = false
                        val sstpHostName = prefs.getString(OscPrefKey.HOME_HOSTNAME.toString(), "")
                        if (mVpnGateConnection != null && sstpHostName == mVpnGateConnection!!.calculateHostName) {
                            update {
                                it.copy(
                                    statusText = getString(R.string.sstp_connected, connectedIp),
                                    showCheckIp = true,
                                    connected = true,
                                    connectText = getString(R.string.disconnect),
                                )
                            }
                            renderSstpTraffic(SstpVpnService.currentTrafficSnapshot)
                        }
                        val intent = Intent(BaseProvider.ACTION.ACTION_CONNECT_VPN)
                        sendBroadcast(intent)
                    }
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        isSSTPConnected = prefs.getBoolean(OscPrefKey.ROOT_STATE.toString(), false)
        val sstpHostName = prefs.getString(OscPrefKey.HOME_HOSTNAME.toString(), "")
        if (isSSTPConnected && mVpnGateConnection != null && sstpHostName == mVpnGateConnection!!.calculateHostName) {
            renderSstpTraffic(SstpVpnService.currentTrafficSnapshot)
            update {
                it.copy(showCheckIp = true, connected = true, connectText = getString(R.string.disconnect))
            }
        }
    }

    @SuppressLint("SetTextI18n")
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataUtil = (application as App).dataUtil!!
        excludeAppsManager = ExcludeAppsManager(this)

        // Handle disconnect action from notification
        if (intent.action == SoftEtherVpnService.ACTION_DISCONNECT) {
            Log.d(TAG, "Disconnect action from notification")
            disconnectSoftEther()
            finish()
            return
        }

        if (intent.getIntExtra(TYPE_START, TYPE_NORMAL) == TYPE_FROM_NOTIFY) {
            val lastMethod = dataUtil.getStringSetting(DataUtil.LAST_CONNECT_METHOD, "openvpn")
            mVpnGateConnection = dataUtil.lastVPNConnection
            if (lastMethod != "softether" && mVpnGateConnection != null) {
                loadVpnProfile(dataUtil.getBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, false))
            }
        } else {
            mVpnGateConnection = IntentCompat.getParcelableExtra(
                intent, BaseProvider.PASS_DETAIL_VPN_CONNECTION,
                VPNGateConnection::class.java,
            )
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        excludeAppsManager.setCallback(object : ExcludeAppsManager.ExcludeAppsCallback {
            override fun updateButtonText(count: Int) {
                update { it.copy(excludedAppsCount = count) }
            }

            override fun restartVpnIfRunning() {
                var vpnRestarted = false
                val useUdp = dataUtil.getBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, false)
                if (isCurrent && checkStatus()) {
                    stopVpn()
                    Handler(mainLooper).postDelayed({ handleConnection(useUdp) }, 500)
                    vpnRestarted = true
                } else if (isSSTPConnected) {
                    startVpnSSTPService(ACTION_VPN_DISCONNECT)
                    Handler(mainLooper).postDelayed({ connectSSTPVPN() }, 500)
                    vpnRestarted = true
                } else if (isSoftEtherConnected || isSoftEtherConnecting) {
                    disconnectSoftEther()
                    Handler(mainLooper).postDelayed({ startSoftEtherConnection(!useUdp) }, 500)
                    vpnRestarted = true
                }
                if (vpnRestarted) {
                    Toast.makeText(this@DetailActivity, getString(R.string.vpn_restarted_for_settings), Toast.LENGTH_LONG).show()
                }
            }
        })

        initSSTP()
        bindData()
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)

        setContent {
            VpnGateTheme {
                DetailScreen(activity = this)
            }
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        VpnStatus.removeStateListener(this)
        VpnStatus.removeByteCountListener(this)
        if (this::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    override fun setConnectedVPN(uuid: String) {
        // Do nothing
    }

    override fun updateState(
        state: String,
        logmessage: String,
        localizedResId: Int,
        status: ConnectionStatus,
        intent: Intent?,
    ) {
        runOnUiThread {
            try {
                if (isSoftEtherConnected || isSoftEtherConnecting || isSSTPConnected || isSSTPConnectOrDisconnecting) {
                    return@runOnUiThread
                }
                update { it.copy(statusText = VpnStatus.getLastCleanLogMessage(this)) }
                when (status) {
                    ConnectionStatus.LEVEL_CONNECTED -> {
                        if (isCurrent) {
                            update {
                                it.copy(
                                    connected = true,
                                    connectText = getString(R.string.disconnect),
                                    showNetStats = true,
                                    showCheckIp = true,
                                )
                            }
                            if (isConnecting && mVpnGateConnection!!.message!!.isNotEmpty() &&
                                dataUtil.getIntSetting(DataUtil.SETTING_HIDE_OPERATOR_MESSAGE_COUNT, 0) == 0
                            ) {
                                if (!isFinishing) {
                                    operatorMessage = mVpnGateConnection!!.message
                                }
                            }
                            val isStartUpDetail =
                                dataUtil.getIntSetting(DataUtil.SETTING_STARTUP_SCREEN, 0) == 0
                            OpenVPNService.setNotificationActivityClass(
                                if (isStartUpDetail) DetailActivity::class.java else MainActivity::class.java,
                            )
                        }
                        isConnecting = false
                        isAuthFailed = false
                    }

                    ConnectionStatus.LEVEL_NOTCONNECTED -> if (!isConnecting && !isAuthFailed) {
                        update {
                            it.copy(
                                showCheckIp = if (isSSTPConnected) it.showCheckIp else false,
                                connected = false,
                                connectText = getString(R.string.connect_to_this_server),
                                statusText = getString(R.string.disconnected),
                                showNetStats = false,
                            )
                        }
                    }

                    ConnectionStatus.LEVEL_AUTH_FAILED -> {
                        isAuthFailed = true
                        isConnecting = false
                        update {
                            it.copy(
                                connectText = getString(R.string.retry_connect),
                                statusText = getString(R.string.vpn_auth_failure),
                                showCheckIp = false,
                                connected = false,
                            )
                        }
                    }

                    else -> update { it.copy(showCheckIp = false) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UpdateState error", e)
            }
        }
    }

    /** Operator message surfaced to the Compose screen as a dialog. */
    var operatorMessage: String? by uiStateDelegate()
    private fun uiStateDelegate() = object : kotlin.properties.ReadWriteProperty<Any?, String?> {
        private var value: String? = null
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = value
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, v: String?) {
            value = v
            update { it.copy(operatorMessage = v) }
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun bindData() {
        if (mVpnGateConnection != null) {
            try {
                val conn = mVpnGateConnection!!
                val isIncludeUDP = dataUtil.getBooleanSetting(DataUtil.INCLUDE_UDP_SERVER, true)
                val showL2tp = conn.isL2TPSupport()
                if (isCurrent && (checkStatus() || isSSTPConnected || isSoftEtherConnected)) {
                    val statusText = if (isSSTPConnected) {
                        val connectedIp = prefs.getString(OscPrefKey.HOME_CONNECTED_IP.toString(), "")
                        if (connectedIp!!.isNotEmpty()) {
                            getString(R.string.sstp_connected, connectedIp)
                        } else {
                            getString(R.string.sstp_connecting)
                        }
                    } else {
                        VpnStatus.getLastCleanLogMessage(this)
                    }
                    update {
                        it.copy(
                            connected = true,
                            connectText = getString(R.string.disconnect),
                            statusText = statusText,
                            showCheckIp = true,
                        )
                    }
                    if (isSSTPConnected) {
                        renderSstpTraffic(SstpVpnService.currentTrafficSnapshot)
                    } else if (isSoftEtherConnected) {
                        renderSoftEtherTraffic(SoftEtherVpnService.currentTrafficSnapshot)
                    }
                }
                update {
                    it.copy(
                        showL2tpButton = showL2tp,
                        showTcpPort = isIncludeUDP && conn.tcpPort > 0,
                        showUdpPort = isIncludeUDP && conn.udpPort > 0,
                        showSstpBadge = conn.isSSTPSupport(),
                        excludedAppsCount = excludeAppsManager.getExcludedAppsCount(),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "bindData error", e)
            }
        }
    }

    private val isCurrent: Boolean
        get() {
            val vpnGateConnection = dataUtil.lastVPNConnection
            return vpnGateConnection != null && mVpnGateConnection != null &&
                    vpnGateConnection.name == mVpnGateConnection!!.name
        }

    public override fun onResume() {
        super.onResume()
        SoftEtherVpnService.addStateListener(softEtherStateListener)
        SoftEtherVpnService.addTrafficListener(softEtherTrafficListener)
        SstpVpnService.addTrafficListener(sstpTrafficListener)
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(this, OpenVPNService::class.java)
                OpenVPNService.mDisplaySpeed =
                    dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true)
                SoftEtherVpnService.mDisplaySpeed =
                    dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true)
                SstpVpnService.mDisplaySpeed =
                    dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true)
                intent.action = OpenVPNService.START_SERVICE
                bindService(intent, mConnection, BIND_AUTO_CREATE)
            }, 300)
            update { it.copy(isImportToOpenVPN = App.isImportToOpenVPN) }
        } catch (e: Exception) {
            Log.e(TAG, "onResume error", e)
        }
    }

    public override fun onPause() {
        try {
            super.onPause()
            SoftEtherVpnService.removeStateListener(softEtherStateListener)
            SoftEtherVpnService.removeTrafficListener(softEtherTrafficListener)
            SstpVpnService.removeTrafficListener(sstpTrafficListener)
            TotalTraffic.saveTotal(this)
            unbindService(mConnection)
        } catch (e: Exception) {
            Log.e(TAG, "onPause error", e)
        }
    }

    private fun handleImport(useUdp: Boolean) {
        val data = if (useUdp) {
            mVpnGateConnection!!.openVpnConfigDataUdp
        } else {
            mVpnGateConnection!!.getOpenVpnConfigDataString()
        }
        try {
            while (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100,
                )
            }
            val fileName = mVpnGateConnection!!.getName(useUdp) + ".ovpn"
            val writeFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName,
            )
            val fileOutputStream = FileOutputStream(writeFile)
            fileOutputStream.write(data!!.toByteArray())
            Toast.makeText(
                applicationContext,
                getString(R.string.saved_ovpn_file_in, "Download/$fileName"),
                Toast.LENGTH_LONG,
            ).show()
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                val packageManager = packageManager
                val intent = packageManager.getLaunchIntentForPackage("net.openvpn.openvpn")
                if (intent != null) {
                    intent.action = Intent.ACTION_VIEW
                    startActivity(intent)
                }
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "handleImport error", e)
        }
    }

    private fun handleConnection(useUdp: Boolean) {
        if (isSSTPConnected) {
            startVpnSSTPService(ACTION_VPN_DISCONNECT)
        }
        val needToStopSoftEther = isSoftEtherConnected || isSoftEtherConnecting
        if (needToStopSoftEther) {
            disconnectSoftEther()
        }
        if (checkStatus()) {
            stopVpn()
            update { it.copy(showCheckIp = false) }
            Handler(Looper.getMainLooper()).postDelayed(
                { prepareVpn(useUdp) },
                if (needToStopSoftEther) 1000L else 500L,
            )
        } else {
            if (needToStopSoftEther) {
                update { it.copy(showCheckIp = false) }
                Handler(Looper.getMainLooper()).postDelayed({ prepareVpn(useUdp) }, 1000L)
            } else {
                prepareVpn(useUdp)
            }
        }
        update {
            it.copy(
                connecting = true,
                connectText = getString(R.string.cancel),
                statusText = getString(R.string.connecting),
            )
        }
        isConnecting = true
        dataUtil.lastVPNConnection = mVpnGateConnection
        sendConnectVPN()
    }

    // ------------------------------------------------------------------
    // Click handlers invoked by the Compose screen
    // ------------------------------------------------------------------

    fun onBackClicked() {
        finish()
    }

    fun onConnectClicked() {
        try {
            if (!isConnecting) {
                if (isSoftEtherConnected && isCurrent) {
                    disconnectSoftEther()
                    update {
                        it.copy(
                            connected = false,
                            connectText = getString(R.string.connect_to_this_server),
                            statusText = getString(R.string.softether_disconnecting),
                            showNetStats = false,
                            showCheckIp = false,
                        )
                    }
                } else if (isSSTPConnected) {
                    handleSSTPBtn()
                } else if (checkStatus() && isCurrent) {
                    stopVpn()
                    update {
                        it.copy(
                            connected = false,
                            connectText = getString(R.string.connect_to_this_server),
                            statusText = getString(R.string.disconnecting),
                        )
                    }
                } else {
                    showVpnProtocolSelectionDialog()
                }
            } else {
                if (isSoftEtherConnecting) {
                    disconnectSoftEther()
                } else if (isSSTPConnectOrDisconnecting) {
                    startVpnSSTPService(ACTION_VPN_DISCONNECT)
                } else {
                    stopVpn()
                }
                update {
                    it.copy(
                        connected = false,
                        connectText = getString(R.string.connect_to_this_server),
                        statusText = getString(R.string.canceled),
                    )
                }
                isConnecting = false
                isSoftEtherConnecting = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "onConnectClicked error", e)
        }
    }

    fun onCheckIpClicked() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, AppConfig.getString("vpn_check_ip_url").toUri()),
            )
        } catch (e: Exception) {
            Log.e(TAG, "onCheckIpClicked error", e)
        }
    }

    fun onL2tpConnectClicked() {
        try {
            val l2tpIntent = Intent(this, L2TPConnectActivity::class.java)
            l2tpIntent.putExtra(BaseProvider.PASS_DETAIL_VPN_CONNECTION, mVpnGateConnection)
            startActivity(l2tpIntent)
        } catch (e: Exception) {
            Log.e(TAG, "onL2tpConnectClicked error", e)
        }
    }

    fun onInstallOpenVpnClicked() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, "market://details?id=net.openvpn.openvpn".toUri()),
            )
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://play.google.com/store/apps/details?id=net.openvpn.openvpn".toUri(),
                ),
            )
        }
    }

    fun onSaveConfigFileClicked() {
        try {
            if (mVpnGateConnection!!.tcpPort > 0 && mVpnGateConnection!!.udpPort > 0) {
                pendingUseProtocolChoice = { useUdp -> handleImport(useUdp) }
                update { it.copy(showUseProtocolDialog = true) }
            } else {
                handleImport(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onSaveConfigFileClicked error", e)
        }
    }

    fun onUseProtocolChoice(useUdp: Boolean) {
        update { it.copy(showUseProtocolDialog = false) }
        pendingUseProtocolChoice?.invoke(useUdp)
        pendingUseProtocolChoice = null
    }

    /** Re-run bindData (used after the excluded-apps sheet closes). */
    fun bindDataRefresh() {
        bindData()
    }

    fun onExcludeAppsClicked() {
        // Handled by the Compose screen via ExcludedAppsSheet.
    }

    private fun connectSSTPVPN() {
        dataUtil.setStringSetting(DataUtil.LAST_CONNECT_METHOD, "sstp")
        val needToStopSoftEther = isSoftEtherConnected || isSoftEtherConnecting
        if (needToStopSoftEther) {
            disconnectSoftEther()
        }
        val excludedApps = App.instance?.excludedAppDao?.getAllExcludedApps() ?: emptyList()
        val excludedPackageNames = excludedApps.map { it.packageName }.toSet()

        prefs.edit {
            putString(OscPrefKey.HOME_HOSTNAME.toString(), mVpnGateConnection!!.calculateHostName)
            putString(
                OscPrefKey.HOME_COUNTRY.toString(),
                mVpnGateConnection!!.countryShort!!.uppercase(),
            )
            putString(OscPrefKey.HOME_SERVER_NAME.toString(), mVpnGateConnection!!.getName(false))
            putString(OscPrefKey.HOME_USERNAME.toString(), "vpn")
            putString(OscPrefKey.HOME_PASSWORD.toString(), "vpn")
            putString(OscPrefKey.SSL_PORT.toString(), mVpnGateConnection!!.sstpConnectPort.toString())
            putStringSet(OscPrefKey.ROUTE_EXCLUDED_APPS.toString(), excludedPackageNames)
            putBoolean(OscPrefKey.PPP_IPv6_ENABLED.toString(), true)
            putString(OscPrefKey.HOME_ULA_V6.toString(), Ipv6Ula.getOrDerive(this@DetailActivity))
        }
        isConnecting = true
        update {
            it.copy(
                connecting = true,
                connectText = getString(R.string.cancel),
                statusText = getString(R.string.sstp_connecting),
            )
        }
        if (needToStopSoftEther) {
            Handler(mainLooper).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    startVpnSSTPService(ACTION_VPN_CONNECT)
                }
            }, 1000L)
        } else {
            startVpnSSTPService(ACTION_VPN_CONNECT)
        }
    }

    private val startActivityIntentSSTPVPN: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        handleActivityResult(START_VPN_SSTP, it.resultCode)
    }

    private val startActivityIntentSoftEther: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (it.resultCode == RESULT_OK) {
            startSoftEtherConnection()
        }
    }

    private fun startSSTPVPN() {
        if (checkStatus()) {
            stopVpn()
        }
        val intent = VpnService.prepare(this)

        if (intent != null) {
            try {
                startActivityIntentSSTPVPN.launch(intent)
            } catch (_: ActivityNotFoundException) {
                Log.e(TAG, "OS does not support VPN")
            }
        } else {
            handleActivityResult(START_VPN_SSTP, RESULT_OK)
        }
    }

    private fun handleSSTPBtn() {
        isSSTPConnectOrDisconnecting = true
        val sstpHostName = prefs.getString(OscPrefKey.HOME_HOSTNAME.toString(), "")
        if (isSSTPConnected && sstpHostName != mVpnGateConnection!!.calculateHostName) {
            startVpnSSTPService(ACTION_VPN_DISCONNECT)
            update { it.copy(showCheckIp = false) }
            Handler(mainLooper).postDelayed({ this.connectSSTPVPN() }, 100)
        } else if (!isSSTPConnected && !isConnecting) {
            dataUtil.lastVPNConnection = mVpnGateConnection
            startSSTPVPN()
        } else {
            startVpnSSTPService(ACTION_VPN_DISCONNECT)
            isConnecting = false
            update {
                it.copy(
                    connected = false,
                    connectText = getString(R.string.connect_to_this_server),
                    statusText = getString(R.string.sstp_disconnecting),
                )
            }
        }
    }

    private fun sendConnectVPN() {
        val intent = Intent(BaseProvider.ACTION.ACTION_CONNECT_VPN)
        sendBroadcast(intent)
    }

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

    private fun prepareVpn(useUdp: Boolean) {
        if (loadVpnProfile(useUdp)) {
            startOpenVpn()
        } else {
            Toast.makeText(this, getString(R.string.error_load_profile), Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadVpnProfile(useUDP: Boolean): Boolean {
        val data = if (useUDP) {
            mVpnGateConnection!!.openVpnConfigDataUdp!!.toByteArray()
        } else {
            mVpnGateConnection!!.getOpenVpnConfigDataString()!!.toByteArray()
        }
        dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, useUDP)
        dataUtil.setStringSetting(DataUtil.LAST_CONNECT_METHOD, "openvpn")
        val cp = ConfigParser()
        val isr = InputStreamReader(ByteArrayInputStream(data))
        try {
            cp.parseConfig(isr)
            vpnProfile = cp.convertProfile()
            vpnProfile.mName = mVpnGateConnection!!.getName(useUDP)
            val ulaV6 = Ipv6Ula.getOrDerive(this)
            vpnProfile.mUseIPv6 = true
            vpnProfile.mIPv6Address = "$ulaV6/64"
            vpnProfile.mUseDefaultRoutev6 = true
            vpnProfile.mCompatMode = App.VPN_PROFILE_COMPAT_MODE_24X
            if (dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false) ||
                dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false)
            ) {
                vpnProfile.mOverrideDNS = true
                vpnProfile.mDNS1 = resolvePrimaryDns()
                vpnProfile.mDNS2 = resolveSecondaryDns()
            }
            excludeAppsManager.configureSplitTunneling(vpnProfile)
            ProfileManager.setTemporaryProfile(applicationContext, vpnProfile)
        } catch (e: IOException) {
            Log.e(TAG, "loadVpnProfile error", e)
            return false
        } catch (e: ConfigParser.ConfigParseError) {
            Log.e(TAG, "loadVpnProfile error", e)
            return false
        }
        return true
    }

    private fun checkStatus(): Boolean {
        try {
            return VpnStatus.isVPNActive()
        } catch (e: Exception) {
            Log.e(TAG, "checkStatus error", e)
        }
        return false
    }

    private fun stopVpn() {
        ProfileManager.setConntectedVpnProfileDisconnected(this)
        if (mVPNService != null) {
            try {
                mVPNService!!.stopVPN(false)
            } catch (e: RemoteException) {
                VpnStatus.logException(e)
            }
        }
    }

    private val startActivityIntentOpenVPN: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        handleActivityResult(START_VPN_PROFILE, it.resultCode)
    }

    private fun startOpenVpn() {
        val intent = VpnService.prepare(this)

        if (intent != null) {
            VpnStatus.updateStateString(
                "USER_VPN_PERMISSION", "", R.string.state_user_vpn_permission,
                ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT,
            )
            try {
                startActivityIntentOpenVPN.launch(intent)
            } catch (_: ActivityNotFoundException) {
                VpnStatus.logError(de.blinkt.openvpn.R.string.no_vpn_support_image)
            }
        } else {
            handleActivityResult(START_VPN_PROFILE, RESULT_OK)
        }
    }

    private fun handleActivityResult(requestCode: Int, resultCode: Int) {
        try {
            if (resultCode == RESULT_OK) {
                if (requestCode == START_VPN_PROFILE) {
                    VPNLaunchHelper.startOpenVpn(vpnProfile, baseContext, null, true)
                }
                if (requestCode == START_VPN_SSTP) {
                    connectSSTPVPN()
                }
                NotificationUtil(this).requestPermission()
                dataUtil.setBooleanSetting(DataUtil.USER_ALLOWED_VPN, true)
            } else {
                dataUtil.setBooleanSetting(DataUtil.USER_ALLOWED_VPN, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleActivityResult error", e)
        }
    }

    override fun updateByteCount(`in`: Long, out: Long, diffIn: Long, diffOut: Long) {
        if (!isCurrent || isSSTPConnected || isSoftEtherConnected) {
            return
        }
        runOnUiThread {
            val netstat = String.format(
                getString(de.blinkt.openvpn.R.string.statusline_bytecount),
                OpenVPNService.humanReadableByteCount(`in`, false, resources),
                OpenVPNService.humanReadableByteCount(
                    diffIn / OpenVPNManagement.mBytecountInterval, true, resources,
                ),
                OpenVPNService.humanReadableByteCount(out, false, resources),
                OpenVPNService.humanReadableByteCount(
                    diffOut / OpenVPNManagement.mBytecountInterval, true, resources,
                ),
            )
            update { it.copy(netStats = netstat, showNetStats = true) }
        }
    }

    // ------------------------------------------------------------------
    // Protocol selection (Compose dialogs driven by these hooks)
    // ------------------------------------------------------------------

    /** Pending "which UDP/TCP?" choice for saving the .ovpn config file. */
    var pendingUseProtocolChoice: ((Boolean) -> Unit)? = null

    /** Pending protocol menu callbacks. */
    fun onProtocolSelected(protocol: VpnProtocol) {
        when (protocol) {
            VpnProtocol.OPENVPN_TCP -> handleConnection(false)
            VpnProtocol.OPENVPN_UDP -> handleConnection(true)
            VpnProtocol.SOFTETHER_TCP -> startSoftEtherConnection(true)
            VpnProtocol.SOFTETHER_UDP -> startSoftEtherConnection(false)
            VpnProtocol.MS_SSTP -> handleSSTPBtn()
        }
    }

    fun shouldShowProtocolDialog(): Boolean {
        val conn = mVpnGateConnection ?: return false
        val hasOpenVpnConfigOnly = conn.openVpnConfigData != null &&
                conn.tcpPort <= 0 && conn.udpPort <= 0 &&
                conn.seTcpPort <= 0 && conn.seUdpPort <= 0
        return !hasOpenVpnConfigOnly
    }

    fun connectDirectlyWithOpenVpn() {
        handleConnection(false)
    }

    /** Builds the protocol options the dialog should show. */
    fun protocolOptions(): List<ProtocolOption> {
        val conn = mVpnGateConnection ?: return emptyList()
        val options = mutableListOf<ProtocolOption>()
        if (conn.openVpnConfigData != null && conn.tcpPort > 0) {
            options.add(
                ProtocolOption(
                    VpnProtocol.OPENVPN_TCP,
                    getString(R.string.protocol_available_port, conn.tcpPort),
                ),
            )
        }
        if (conn.openVpnConfigData != null && conn.udpPort > 0) {
            options.add(
                ProtocolOption(
                    VpnProtocol.OPENVPN_UDP,
                    getString(R.string.protocol_available_port, conn.udpPort),
                ),
            )
        }
        if (conn.seTcpPort > 0) {
            options.add(
                ProtocolOption(
                    VpnProtocol.SOFTETHER_TCP,
                    getString(R.string.protocol_available_port, conn.seTcpPort),
                ),
            )
        }
        if (conn.seUdpPort > 0 || conn.seUdpSupported) {
            val detail = if (conn.seUdpPort > 0) {
                getString(R.string.protocol_available_port, conn.seUdpPort)
            } else {
                getString(R.string.protocol_supported_port_unknown)
            }
            options.add(ProtocolOption(VpnProtocol.SOFTETHER_UDP, detail))
        }
        if (conn.isSSTPSupport()) {
            val detail = if (conn.tcpPort > 0) {
                getString(R.string.protocol_available_port, conn.sstpConnectPort)
            } else {
                getString(R.string.protocol_available_default_port, conn.sstpConnectPort)
            }
            options.add(ProtocolOption(VpnProtocol.MS_SSTP, detail))
        }
        return options
    }

    private fun showVpnProtocolSelectionDialog() {
        if (isFinishing || isDestroyed) {
            Log.w(TAG, "Cannot show dialog, activity is finishing or destroyed")
            return
        }
        if (mVpnGateConnection == null) {
            Log.e(TAG, "Cannot show dialog, VPN connection is null")
            Toast.makeText(this, R.string.error_load_profile, Toast.LENGTH_SHORT).show()
            return
        }
        if (!shouldShowProtocolDialog()) {
            Log.d(TAG, "Only embedded-port OpenVPN config available, connecting directly")
            handleConnection(false)
            return
        }
        showProtocolDialog = true
    }

    /** One-shot flag consumed by the Compose screen to open the protocol dialog. */
    @Volatile
    var showProtocolDialog: Boolean = false
        set(value) {
            field = value
            update { it.copy(showProtocolDialog = value) }
        }

    private fun disconnectSoftEther() {
        Log.d(TAG, "Disconnecting SoftEther")
        isConnecting = false
        isSoftEtherConnecting = false
        lastDisconnectTime = System.currentTimeMillis()
        try {
            val intent = Intent(this, SoftEtherVpnService::class.java).apply {
                action = SoftEtherVpnService.ACTION_DISCONNECT
            }
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending disconnect intent", e)
        }
    }

    private fun startSoftEtherConnection(useTcp: Boolean = true) {
        if (mVpnGateConnection == null) {
            Log.e(TAG, "Cannot start SoftEther connection: VPN connection is null")
            Toast.makeText(this, R.string.error_load_profile, Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Requesting POST_NOTIFICATIONS permission before VPN permission")
                pendingSoftEtherUseTcp = useTcp
                notificationPermissionRequested = true
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION,
                )
                return
            }
        }
        continueSoftEtherConnection(useTcp)
    }

    private fun continueSoftEtherConnection(useTcp: Boolean) {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            Log.d(TAG, "VPN permission required, launching VPN permission dialog")
            try {
                startActivityIntentSoftEther.launch(vpnIntent)
            } catch (_: ActivityNotFoundException) {
                Log.e(TAG, "OS does not support VPN")
                Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (isSoftEtherConnected || isSoftEtherConnecting) {
            disconnectSoftEther()
        }

        val timeSinceDisconnect = System.currentTimeMillis() - lastDisconnectTime
        if (timeSinceDisconnect < disconnectCooldownMS) {
            Log.d(TAG, "Waiting for cooldown period: ${disconnectCooldownMS - timeSinceDisconnect}ms remaining")
            Handler(mainLooper).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    continueSoftEtherConnection(useTcp)
                }
            }, disconnectCooldownMS - timeSinceDisconnect)
            return
        }

        if (isConnecting || isSoftEtherConnecting) {
            Log.w(TAG, "Connection already in progress, ignoring request")
            return
        }

        try {
            if (isSSTPConnected) {
                startVpnSSTPService(ACTION_VPN_DISCONNECT)
            }
            if (checkStatus()) {
                stopVpn()
            }

            val serverName = mVpnGateConnection!!.getName(!useTcp, true)
            val useDomainToConnect = dataUtil.getBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, false)
            val serverHost = if (useDomainToConnect) {
                mVpnGateConnection!!.calculateHostName
            } else {
                mVpnGateConnection!!.ip!!
            }
            val isUdpOnly = mVpnGateConnection!!.isUdpOnly
            if (isUdpOnly) {
                Log.i(TAG, "UDP-only server (no SoftEther TCP port): NAT-T path implied")
            }
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
                excludedApps = (App.instance?.excludedAppDao?.getAllExcludedApps() ?: emptyList())
                    .map { it.packageName },
                isMetered = false,
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
                if (isStartUpDetail) DetailActivity::class.java else MainActivity::class.java

            val intent = Intent(this, SoftEtherVpnService::class.java).apply {
                action = SoftEtherVpnService.ACTION_CONNECT
                putExtra(SoftEtherVpnService.EXTRA_CONFIG, config)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            update {
                it.copy(
                    connecting = true,
                    connectText = getString(R.string.cancel),
                    statusText = getString(R.string.softether_connecting),
                )
            }
            isConnecting = true
            isSoftEtherConnecting = true
            dataUtil.lastVPNConnection = mVpnGateConnection
            dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_USE_UDP, !useTcp)
            dataUtil.setBooleanSetting(DataUtil.LAST_CONNECT_SOFTETHER_USE_UDP, !useTcp)
            dataUtil.setStringSetting(DataUtil.LAST_CONNECT_METHOD, "softether")
            sendConnectVPN()

            Toast.makeText(this, R.string.softether_connecting, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SoftEther connection", e)
            isConnecting = false
            isSoftEtherConnecting = false
            Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_NOTIFICATION_PERMISSION -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    Log.d(TAG, "POST_NOTIFICATIONS permission granted")
                } else {
                    Log.d(TAG, "POST_NOTIFICATIONS permission denied, but continuing with connection")
                }
                notificationPermissionRequested = false
                continueSoftEtherConnection(pendingSoftEtherUseTcp)
                return
            }
        }
    }

    fun excludeAppsManager(): ExcludeAppsManager = excludeAppsManager

    @androidx.annotation.Keep
    companion object {
        const val TYPE_FROM_NOTIFY: Int = 1001
        const val TYPE_NORMAL: Int = 1000
        const val TYPE_START: String = "vn.unlimit.vpngate.TYPE_START"
        const val START_VPN_PROFILE: Int = 70
        const val START_VPN_SSTP: Int = 80
        const val REQUEST_NOTIFICATION_PERMISSION: Int = 90
        const val ACTION_VPN_CONNECT: String = "kittoku.osc.connect"
        const val ACTION_VPN_DISCONNECT: String = "kittoku.osc.disconnect"
        private const val TAG = "DetailActivity"
        private var mVPNService: IOpenVPNServiceInternal? = null
    }
}
