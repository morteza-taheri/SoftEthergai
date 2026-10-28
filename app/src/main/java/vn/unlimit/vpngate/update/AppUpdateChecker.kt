package vn.unlimit.vpngate.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import vn.unlimit.vpngate.BuildConfig
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.OkHttpFetcher
import vn.unlimit.vpngate.utils.DataUtil

/**
 * BLOCK v1.0.76 — APP UPDATE CHECKER: the Android side.
 *
 * Checks [AppUpdateManifest.MANIFEST_URL] (a small JSON file self-published in
 * this repository, same pattern as the emergency CSV) and compares it with the
 * installed build. Two entry points:
 *
 *  - [check] — the manual button in Settings; returns the decision for a dialog.
 *  - [AppUpdateWeeklyCheck.runIfNeeded] — the automatic weekly check from
 *    `App.onCreate`; posts a notification (tap → release page) when a newer
 *    build is published. Never auto-installs, never auto-downloads.
 *
 * Failure semantics: every failure folds into
 * [AppUpdateDecision.Unknown] / `null` — a failed check can never crash the
 * app, block startup or open a browser.
 */
class AppUpdateChecker(
    private val fetcher: HttpFetcher = OkHttpFetcher(),
) {
    suspend fun check(): AppUpdateDecision {
        val response = runCatching { fetcher.get(AppUpdateManifest.MANIFEST_URL) }.getOrNull()
        val body = response?.takeIf { it.status in 200..299 }?.body
        val manifest = AppUpdateCheck.parseManifest(body)
        return AppUpdateCheck.decide(manifest, BuildConfig.VERSION_CODE)
    }
}

/**
 * The weekly automatic check (D-10): at most once per
 * [AppUpdateCheck.WEEKLY_INTERVAL_MS], from `App.onCreate`'s background scope.
 * When a newer build is published, ONE notification is posted per new version
 * (the dismissed-code bookkeeping suppresses repeats); tapping it opens the
 * release page in a browser.
 */
object AppUpdateWeeklyCheck {

    private const val CHANNEL_ID = "app_update"
    private const val NOTIFICATION_ID = 0xA0F1

    suspend fun runIfNeeded(
        context: Context,
        dataUtil: DataUtil,
        log: (String) -> Unit = {},
    ) {
        val now = System.currentTimeMillis()
        if (!AppUpdateCheck.shouldAutoCheck(dataUtil.getAppUpdateLastCheckAt(), now)) return
        dataUtil.setAppUpdateLastCheckAt(now)
        log("[UPDATE] weekly check running")

        val decision = AppUpdateChecker().check()
        val manifest = (decision as? AppUpdateDecision.UpdateAvailable)?.manifest ?: run {
            log("[UPDATE] weekly check: up to date or unknown; nothing to notify")
            return
        }
        if (manifest.versionCode <= dataUtil.getAppUpdateDismissedCode()) {
            log("[UPDATE] weekly check: version ${manifest.versionCode} already dismissed")
            return
        }
        dataUtil.setAppUpdateDismissedCode(manifest.versionCode)
        notifyUpdateAvailable(context, manifest)
        log("[UPDATE] weekly check: notified ${manifest.versionName} (${manifest.versionCode})")
    }

    private fun notifyUpdateAvailable(context: Context, manifest: AppUpdateManifest) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_check_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }

        val openPage = Intent(Intent.ACTION_VIEW, Uri.parse(manifest.releasePageUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            manifest.versionCode,
            openPage,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = context.getString(R.string.update_check_notification_title)
        val text = context.getString(
            R.string.update_check_notification_body,
            manifest.versionName,
        )
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_auto_mode)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .build()
        // BLOCK v1.0.76 audit fix: notify (and its eventual tap) must never
        // crash a device without a browser handler.
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }
}
