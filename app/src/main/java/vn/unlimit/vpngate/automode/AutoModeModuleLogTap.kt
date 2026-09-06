package vn.unlimit.vpngate.automode

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.LogItem
import de.blinkt.openvpn.core.VpnStatus
import kittoku.osc.preference.OscPrefKey
import vn.unlimit.softether.SoftEtherVpnService
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Taps the real module outputs and feeds them into [AutoModeLogStore]:
 *  - SoftEther: [SoftEtherVpnService] state changes plus the module's own
 *    logcat output (ConnectionController / SoftEtherProtocol / SoftEtherJNI /
 *    SoftEtherVpnService / TunTerminal tags are all logged by the SoftEther
 *    stack in this process).
 *  - OpenVPN: [VpnStatus.LogListener] — the module's canonical log stream.
 *  - MS-SSTP: the kittoku OSC service's logcat output (SstpVpnService /
 *    SSTPController / SstpClient / osc tags) plus the ROOT_STATE pref.
 *
 * `logcat -s TAG...` on a normal app reads only its own process's entries,
 * which is exactly the module output we want — no extra permissions.
 */
object AutoModeModuleLogTap :
    SoftEtherVpnService.StateListener,
    VpnStatus.LogListener,
    VpnStatus.StateListener {

    private const val TAG = "AutoModeLogTap"

    /** Tags of the SoftEther stack (JNI + Kotlin service layer). */
    private val SOFTETHER_TAGS =
        arrayOf("ConnectionController", "SoftEtherProtocol", "SoftEtherJNI", "SoftEtherVpnService", "TunTerminal", "KeepAliveManager", "SoftEtherClient")

    /** Tags of the kittoku OSC (MS-SSTP) stack. */
    private val SSTP_TAGS =
        arrayOf("SstpVpnService", "SSTPController", "SstpClient", "OSC", "Muxer", "SstpProtocol", "ControlPacket", "DataPacket", "SstpServer")

    private var prefs: SharedPreferences? = null
    private var logcatThread: Thread? = null

    @Volatile
    private var logcatRunning = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val sstpStateListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == OscPrefKey.ROOT_STATE.toString()) {
                val up = prefs?.getBoolean(key, false) == true
                AutoModeLogStore.add(AutoModeLogStore.Source.SSTP, "ROOT_STATE = $up")
            }
        }

    fun attach(context: Context) {
        if (prefs != null) return
        prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        prefs?.registerOnSharedPreferenceChangeListener(sstpStateListener)
        SoftEtherVpnService.addStateListener(this)
        VpnStatus.addLogListener(this)
        VpnStatus.addStateListener(this)
        startLogcatTap()
        Log.d(TAG, "Module log tap attached")
    }

    fun detach() {
        prefs?.unregisterOnSharedPreferenceChangeListener(sstpStateListener)
        prefs = null
        SoftEtherVpnService.removeStateListener(this)
        VpnStatus.removeLogListener(this)
        VpnStatus.removeStateListener(this)
        stopLogcatTap()
    }

    // ---- SoftEther: state stream ----

    override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
        val text = if (assignedIp.isNotEmpty()) "$state ip=$assignedIp" else state
        AutoModeLogStore.add(AutoModeLogStore.Source.SOFTETHER, text)
    }

    // ---- OpenVPN: canonical module log stream ----

    override fun newLog(logItem: LogItem) {
        // State/log lines the OpenVPN module itself produced.
        AutoModeLogStore.add(AutoModeLogStore.Source.OPENVPN, logItem.toString())
    }

    override fun updateState(
        state: String,
        logmessage: String,
        localizedResId: Int,
        status: ConnectionStatus,
        intent: android.content.Intent?,
    ) {
        // Not duplicated into the store: newLog receives the same events
        // already formatted by the module.
    }

    override fun setConnectedVPN(uuid: String?) {
        // Not used for the log window.
    }

    // ---- logcat tap for the native stacks ----

    private fun startLogcatTap() {
        if (logcatRunning) return
        logcatRunning = true
        val tags = SOFTETHER_TAGS + SSTP_TAGS
        val command = arrayOf("logcat", "-v", "time", "-s") + tags
        logcatThread = Thread({
            try {
                val process = Runtime.getRuntime().exec(command)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                while (logcatRunning) {
                    val raw = reader.readLine() ?: break
                    if (raw.isBlank()) continue
                    val parsed = parseLogcatLine(raw) ?: continue
                    if (parsed.second in SOFTETHER_TAGS) {
                        AutoModeLogStore.add(AutoModeLogStore.Source.SOFTETHER, parsed.first)
                    } else if (parsed.second in SSTP_TAGS) {
                        AutoModeLogStore.add(AutoModeLogStore.Source.SSTP, parsed.first)
                    }
                }
            } catch (e: Exception) {
                if (logcatRunning) {
                    Log.w(TAG, "logcat tap stopped: ${e.message}")
                }
            }
        }, "AutoModeLogcatTap").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopLogcatTap() {
        logcatRunning = false
        logcatThread?.interrupt()
        logcatThread = null
    }

    /**
     * `logcat -v time -s TAG` prints `MM-DD HH:MM:SS.mmm LEVEL/TAG( PID): msg`
     * Keep only the message part; the store adds its own timestamp.
     */
    private fun parseLogcatLine(raw: String): Pair<String, String>? {
        val closeParen = raw.indexOf("): ")
        if (closeParen < 0) return null
        val head = raw.substring(0, closeParen)
        val openParen = head.lastIndexOf('(')
        if (openParen < 0) return null
        val tag = head.substring(head.lastIndexOf('/', openParen - 1) + 1, openParen).trim()
        val message = raw.substring(closeParen + 3)
        if (message.isBlank()) return null
        return message to tag
    }
}
