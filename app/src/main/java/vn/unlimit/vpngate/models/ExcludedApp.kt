package vn.unlimit.vpngate.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An installed application offered in the Excluded Apps sheet.
 *
 * [isSystem] is presentation-only metadata used to split the list into
 * "User apps" and "System apps" sections. It is intentionally NOT part of the
 * Room entity ([ExcludedApp]) — the database keeps storing exactly
 * (packageName, appName), so adding this field cannot trigger a schema
 * migration, cannot drop a stored selection, and cannot change a package name.
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean,
)

@androidx.room.Entity(tableName = "excluded_apps")
data class ExcludedApp(
    @PrimaryKey val packageName: String,
    val appName: String
)
