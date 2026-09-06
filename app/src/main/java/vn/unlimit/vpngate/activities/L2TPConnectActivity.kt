package vn.unlimit.vpngate.activities

import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import vn.unlimit.vpngate.ui.screens.l2tp.L2tpConnectScreen
import vn.unlimit.vpngate.ui.theme.VpnGateTheme
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.utils.DataUtil

class L2TPConnectActivity : AppCompatActivity() {
    private var mVPNGateConnection: VPNGateConnection? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        try {
            mVPNGateConnection = IntentCompat.getParcelableExtra(
                intent, BaseProvider.PASS_DETAIL_VPN_CONNECTION,
                VPNGateConnection::class.java,
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val dataUtil = (application as vn.unlimit.vpngate.App).dataUtil!!
        val useDomain = dataUtil.getBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, false)
        setContent {
            VpnGateTheme {
                L2tpConnectScreen(
                    hostName = mVPNGateConnection?.hostName,
                    endPoint = if (useDomain) {
                        mVPNGateConnection?.hostName + ".opengw.net"
                    } else {
                        mVPNGateConnection?.ip
                    },
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                )
            }
        }
    }
}
