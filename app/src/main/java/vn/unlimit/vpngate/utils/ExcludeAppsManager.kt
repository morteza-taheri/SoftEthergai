package vn.unlimit.vpngate.utils

import android.content.Context
import android.widget.Toast
import de.blinkt.openvpn.VpnProfile
import kotlinx.coroutines.*
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.models.ExcludedApp

class ExcludeAppsManager(private val context: Context) {

    interface ExcludeAppsCallback {
        fun updateButtonText(count: Int)
        fun restartVpnIfRunning()
    }

    /** Self package is always excluded — hidden from user, cannot be removed */
    private val selfPackageName: String get() = context.packageName

    private var callback: ExcludeAppsCallback? = null

    fun setCallback(callback: ExcludeAppsCallback) {
        this.callback = callback
    }

    /**
     * The Compose ExcludedAppsSheet performs the database write itself and
     * then calls this to run the shared post-save behavior.
     */
    fun notifyAppsSaved(selectedApps: List<ExcludedApp>) {
        callback?.updateButtonText(selectedApps.count { it.packageName != selfPackageName })
        callback?.restartVpnIfRunning()
    }

    fun saveSelectedApps(selectedApps: List<ExcludedApp>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                App.instance?.excludedAppDao?.let { dao ->
                    // Force-add self package — always excluded, user cannot remove
                    val withSelf = selectedApps.toMutableList()
                    if (withSelf.none { it.packageName == selfPackageName }) {
                        withSelf.add(ExcludedApp(selfPackageName, "Self"))
                    }
                    // Simple approach: delete all and insert selected
                    dao.getAllExcludedApps().forEach { dao.deleteExcludedApp(it) }
                    withSelf.forEach { dao.insertExcludedApp(it) }

                    // Add small delay to ensure database operations are complete
                    kotlinx.coroutines.delay(100)
                }
                // Update button text and restart VPN if needed
                withContext(Dispatchers.Main) {
                    callback?.updateButtonText(selectedApps.size)

                    // Always call restart callback - it will check if any VPN is running
                    callback?.restartVpnIfRunning()

                    Toast.makeText(context, context.getString(R.string.apps_updated_successfully), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.error_saving_apps), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun updateExcludeAppsButtonText(callback: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allExcluded = App.instance?.excludedAppDao?.getAllExcludedApps() ?: emptyList()
                // Exclude self from user-visible count
                val userCount = allExcluded.count { it.packageName != selfPackageName }
                val text = context.getString(R.string.exclude_apps_text, userCount)
                withContext(Dispatchers.Main) {
                    callback(text)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val text = context.getString(R.string.exclude_apps_text, 0)
                withContext(Dispatchers.Main) {
                    callback(text)
                }
            }
        }
    }

    fun getExcludedAppsCount(): Int {
        return try {
            val allExcluded = App.instance?.excludedAppDao?.getAllExcludedApps() ?: emptyList()
            // Exclude self from user-visible count
            allExcluded.count { it.packageName != selfPackageName }
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    fun configureSplitTunneling(vpnProfile: VpnProfile?) {
        try {
            // Get all excluded apps from database (presence indicates exclusion)
            val excludedApps = App.instance?.excludedAppDao?.getAllExcludedApps() ?: emptyList()

            android.util.Log.d("ExcludeAppsManager", "Found ${excludedApps.size} excluded apps in database")

            // Initialize the allowed apps set if not already done
            if (vpnProfile?.mAllowedAppsVpn == null) {
                vpnProfile?.mAllowedAppsVpn = java.util.HashSet()
            }

            // Clear existing apps
            vpnProfile?.mAllowedAppsVpn?.clear()

            // Add excluded apps as disallowed (they bypass VPN)
            for (excludedApp in excludedApps) {
                vpnProfile?.mAllowedAppsVpn?.add(excludedApp.packageName)
                android.util.Log.d("ExcludeAppsManager", "Excluding app: ${excludedApp.packageName}")
            }

            // Set as disallowed - meaning these apps are excluded from VPN
            vpnProfile?.mAllowedAppsVpnAreDisallowed = true
            vpnProfile?.mAllowAppVpnBypass = true

            android.util.Log.d("ExcludeAppsManager", "Configured split tunneling for ${excludedApps.size} excluded apps")
        } catch (e: Exception) {
            android.util.Log.e("ExcludeAppsManager", "Error configuring split tunneling", e)
            // Ensure default behavior on error
            vpnProfile?.mAllowedAppsVpnAreDisallowed = true
            vpnProfile?.mAllowAppVpnBypass = false
        }
    }
}
