package vn.unlimit.vpngate.ui.screens.settings

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.models.ExcludedApp
import vn.unlimit.vpngate.utils.ExcludeAppsManager

/**
 * Rasterize an arbitrary [Drawable] into an [ImageBitmap].
 *
 * Launcher icons are frequently [android.graphics.drawable.AdaptiveIconDrawable]
 * or other vector-backed drawables: valid to *draw*, but they cannot be used as
 * a Bitmap without rasterizing first. Returns null when the drawable has no
 * usable size or drawing throws — the caller then shows a neutral placeholder
 * instead of crashing.
 */
private fun Drawable.rasterize(): ImageBitmap? = try {
    if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
        null
    } else {
        val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bitmap.asImageBitmap()
    }
} catch (t: Throwable) {
    // Out of memory, malformed drawable, OEM bug — one bad app must not kill the list.
    null
}

/**
 * Load a single installed app icon, defensively.
 *
 * Everything that can go wrong with a third-party package (uninstalled between
 * the list query and this call, broken resources, PackageManager quirks) is
 * caught and reported as `null`, which renders as the placeholder icon.
 */
private fun loadAppIcon(pm: PackageManager, packageName: String): ImageBitmap? = try {
    val appInfo: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
    pm.getApplicationIcon(appInfo).rasterize()
} catch (t: Throwable) {
    null
}

/**
 * Icon slot for an excluded-apps row: the real launcher icon once it resolves,
 * a neutral placeholder while loading or on any failure.
 */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            loadAppIcon(context.packageManager, packageName)
        }
    }
    val bitmap = icon
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(34.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Excluded-apps bottom sheet: searchable app list (icon + name + package) with
 * a live count. The selection lives in local UI state and is only written to
 * the database (Room, via [App.excludedAppDao]) when the user presses **Save**,
 * so dismissing the sheet leaves the stored selection untouched. Saving shows a
 * snackbar and re-entering the screen (or restarting the app) shows the saved
 * state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcludedAppsSheet(
    manager: ExcludeAppsManager,
    onDismiss: () -> Unit,
    /**
     * Invoked after a successful write to the database. The sheet closes itself
     * right after, so the caller (Settings) owns the "saved" snackbar — a
     * snackbar hosted inside a dismissed sheet would never be seen.
     * [Boolean] = success.
     */
    onSaved: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val allApps = remember { mutableListOf<ExcludedApp>() }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var originalSelection by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        val (excluded, apps) = withContext(Dispatchers.IO) {
            val ex = (app.excludedAppDao.getAllExcludedApps())
                .filter { it.packageName != context.packageName }
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName }
                .map {
                    ExcludedApp(
                        it.packageName,
                        runCatching { pm.getApplicationLabel(it).toString() }
                            .getOrDefault(it.packageName),
                    )
                }
                .sortedBy { it.appName }
            ex to installed
        }
        allApps.addAll(apps)
        selected = excluded.map { it.packageName }.toSet()
        originalSelection = selected
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.add_apps_to_exclude),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.exclude_app_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                placeholder = { Text(stringResource(R.string.search_apps_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
            )
            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                val filtered = if (query.isEmpty()) {
                    allApps
                } else {
                    allApps.filter {
                        it.appName.contains(query, ignoreCase = true) ||
                                it.packageName.contains(query, ignoreCase = true)
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(filtered, key = { it.packageName }) { appItem ->
                        val isSel = appItem.packageName in selected
                        ListItem(
                            leadingContent = { AppIcon(appItem.packageName) },
                            headlineContent = { Text(appItem.appName) },
                            supportingContent = {
                                Text(
                                    appItem.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = isSel,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) {
                                            selected + appItem.packageName
                                        } else {
                                            selected - appItem.packageName
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    val hasChanges = selected != originalSelection
                    Button(
                        enabled = hasChanges,
                        onClick = {
                            val chosen = allApps.filter { it.packageName in selected }
                            scope.launch(Dispatchers.IO) {
                                try {
                                    app.excludedAppDao.let { dao ->
                                        val withSelf = chosen.toMutableList()
                                        if (withSelf.none { it.packageName == context.packageName }) {
                                            withSelf.add(ExcludedApp(context.packageName, "Self"))
                                        }
                                        dao.getAllExcludedApps().forEach { dao.deleteExcludedApp(it) }
                                        withSelf.forEach { dao.insertExcludedApp(it) }
                                        delay(100)
                                    }
                                    withContext(Dispatchers.Main) {
                                        // New baseline: a re-opened sheet must show
                                        // the just-saved state as "unchanged".
                                        originalSelection = selected
                                        onSaved(true)
                                        onDismiss()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    withContext(Dispatchers.Main) {
                                        onSaved(false)
                                    }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}
