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
 * active. The *only* notification Auto Mode owns is the transient
 * "Connecting – server n of m" scan-progress one shown while it is cycling
 * through servers.
 *
 * Once a tunnel is actually up we deliberately do **not** post a second
 * ongoing notification: the VPN foreground service (SoftEtherVpnService,
 * id 1001) already surfaces the connected state — title carries the server
 * name and there is a disconnect action. Posting a second ongoing
 * notification from a different channel/id is exactly what produced the
 * "two separate notifications" complaint, so on [notifyConnected] we clear
 * the Auto Mode notification and let the service notification be the single
 * source of truth.
 *
 * The Auto Mode notification is cleared in every non-Connecting outcome
 * (Connected / Idle / Stopped / Error) and when the run stops.
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

    /**
     * Called when a tunnel comes up. The VPN foreground service notification
     * (id 1001) already shows the connected server with a disconnect action,
     * so posting a second ongoing notification here is what created the
     * duplicate. Instead we clear the Auto Mode notification and defer to the
     * service notification as the single persistent indicator.
     *
     * [hostname] / [protocolId] are accepted for API compatibility with the
     * engine's call site but are no longer rendered.
     */
    fun notifyConnected(context: Context, hostname: String?, protocolId: String) {
        clear(context)
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
