package vn.unlimit.vpngate.activities

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import kittoku.osc.preference.OscPrefKey
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.BuildConfig
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.ui.AppRoot
import vn.unlimit.vpngate.ui.NavRoutes
import vn.unlimit.vpngate.ui.theme.VpnGateTheme
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.DataUtil.Companion.isOnline
import vn.unlimit.vpngate.viewmodels.ConnectionListViewModel
import java.util.Objects

/**
 * Compose-host shell. Stays an AppCompatActivity (per-app locales and the
 * manual light/dark theme rely on AppCompatDelegate) and keeps every piece
 * of the old MainActivity's non-UI logic: the broadcast receiver, the
 * activity-scoped ConnectionListViewModel and the double-back-to-exit.
 */
class MainActivity : AppCompatActivity() {
    var connectionListViewModel: ConnectionListViewModel? = null
    private var dataUtil: DataUtil? = null
    private var doubleBackToExitPressedOnce: Boolean = false
    private var isInFront = false
    private var startDestination: String = NavRoutes.AUTO

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (Objects.requireNonNull<String?>(intent.action)) {
                BaseProvider.ACTION.ACTION_CHANGE_NETWORK_STATE -> {}
                BaseProvider.ACTION.ACTION_CLEAR_CACHE ->
                    connectionListViewModel?.vpnGateConnectionList?.postValue(null)

                BaseProvider.ACTION.ACTION_CONNECT_VPN -> {}
                else -> {}
            }
        }
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        dataUtil = (application as App).dataUtil
        connectionListViewModel = ViewModelProvider(this)[ConnectionListViewModel::class.java]

        // Start destination: AutoMode by default, or follows the startup-screen setting
        val targetFragment = intent.getStringExtra(TARGET_FRAGMENT)
        val startupScreenSetting = dataUtil!!.getIntSetting(DataUtil.SETTING_STARTUP_SCREEN, 0)
        startDestination = when {
            targetFragment == "status" -> NavRoutes.STATUS
            targetFragment == "auto" -> NavRoutes.AUTO
            targetFragment == "home" -> NavRoutes.HOME
            startupScreenSetting == 1 -> NavRoutes.HOME
            startupScreenSetting == 2 && dataUtil!!.lastVPNConnection != null -> NavRoutes.STATUS
            else -> NavRoutes.AUTO
        }
        intent.removeExtra(TARGET_FRAGMENT)

        val filter = IntentFilter()
        filter.addAction(BaseProvider.ACTION.ACTION_CHANGE_NETWORK_STATE)
        filter.addAction(BaseProvider.ACTION.ACTION_CLEAR_CACHE)
        filter.addAction(BaseProvider.ACTION.ACTION_CONNECT_VPN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, filter)
        }

        // Double-back-to-exit (was in addBackPressedHandler()).
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (doubleBackToExitPressedOnce) {
                    finish()
                    return
                }
                doubleBackToExitPressedOnce = true
                Toast.makeText(
                    this@MainActivity,
                    resources.getString(vn.unlimit.vpngate.R.string.press_back_again_to_exit),
                    Toast.LENGTH_SHORT,
                ).show()
                Handler(mainLooper).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
            }
        })

        setContent {
            VpnGateTheme {
                AppRoot(
                    connectionListViewModel = connectionListViewModel!!,
                    startDestination = startDestination,
                    onNavigateHomeRequested = { },
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isInFront = false
    }

    override fun onResume() {
        super.onResume()
        isInFront = true
    }

    override fun onDestroy() {
        unregisterReceiver(broadcastReceiver)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    companion object {
        const val TARGET_FRAGMENT: String = "TARGET_FRAGMENT"
        private const val TAG = "MainActivity"
    }
}
