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
import vn.unlimit.vpngate.R

/**
 * Reflects the Auto Mode state in an ongoing notification while a run is
 * active: "Connecting – server n of m" while trying servers, and
 * "Connected via <protocol> – <server>" once a tunnel is up. Cleared when
 * the run stops (user stop / terminal error).
 */
object AutoModeNotifier {

    private const val CHANNEL_ID = "auto_mode_status"
    private const val NOTIFICATION_ID = 0xA070

    fun notifyConnecting(context: Context, hostname: String?, attempt: Int, total: Int) {
        val text = context.getString(
            R.string.auto_mode_notification_connecting,
            hostname ?: "",
            attempt,
            total,
        )
        post(context, context.getString(R.string.auto_mode_notification_title), text, ongoing = true)
    }

    fun notifyConnected(context: Context, hostname: String?, protocolId: String) {
        val protocolLabel = protocolId.lowercase().replace('_', ' ')
        val text = context.getString(
            R.string.auto_mode_notification_connected,
            hostname ?: "",
            protocolLabel,
        )
        post(context, context.getString(R.string.auto_mode_notification_title), text, ongoing = true)
    }

    fun clear(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, title: String, text: String, ongoing: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.auto_mode_notification_title),
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
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(context, R.color.colorPrimary))
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
