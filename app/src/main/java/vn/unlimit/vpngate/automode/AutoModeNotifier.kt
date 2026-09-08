package vn.unlimit.vpngate.automode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.utils.DataUtil

/**
 * Manages VpnM VPN notifications according to specifications:
 * Shows Connected status with Server and Protocol, and optionally traffic details.
 */
object AutoModeNotifier {

    private const val CHANNEL_ID = "auto_mode_status"
    private const val NOTIFICATION_ID = 0xA070

    fun notifyConnecting(context: Context, hostname: String?, attempt: Int, total: Int) {
        val appName = context.getString(R.string.app_name)
        val text = context.getString(
            R.string.auto_mode_notification_connecting,
            hostname ?: "",
            attempt,
            total,
        )
        post(context, appName, text, ongoing = true)
    }

    fun notifyConnected(
        context: Context,
        hostname: String?,
        protocolId: String,
        bytesIn: Long = 0L,
        bytesOut: Long = 0L,
    ) {
        val appName = context.getString(R.string.app_name)
        val protocolLabel = when (protocolId) {
            "SOFTETHER_UDP" -> "SoftEther UDP"
            "SOFTETHER_TCP" -> "SoftEther TCP"
            "MS_SSTP" -> "MS-SSTP"
            "OPENVPN_UDP" -> "OpenVPN UDP"
            "OPENVPN_TCP" -> "OpenVPN TCP"
            else -> protocolId.replace('_', ' ')
        }

        val serverLabel = hostname?.ifBlank { "VPN Server" } ?: "VPN Server"
        val dataUtil = (context.applicationContext as? App)?.dataUtil
        val showTraffic = dataUtil?.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true) ?: true

        val builder = StringBuilder()
        builder.append(context.getString(R.string.auto_mode_state_connected)).append("\n")
        builder.append("Server: ").append(serverLabel).append("\n")
        builder.append("Protocol: ").append(protocolLabel)

        if (showTraffic && (bytesIn > 0 || bytesOut > 0)) {
            builder.append("\n\n")
            builder.append("↓ ").append(formatBytes(bytesIn)).append("   ")
            builder.append("↑ ").append(formatBytes(bytesOut))
        }

        val text = builder.toString()
        post(context, appName, text, ongoing = true)
    }

    fun clear(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            else -> String.format(java.util.Locale.US, "%.1f KB", kb)
        }
    }

    private fun post(context: Context, title: String, text: String, ongoing: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "VpnM Connection Status",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val launchIntent = Intent().apply {
            setClassName(context, "vn.unlimit.vpngate.activities.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("vn.unlimit.vpngate.OPEN_AUTO_MODE", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_auto_mode)
            .setContentTitle(title)
            .setContentText(text.lines().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(context, R.color.colorPrimary))
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
