package vn.unlimit.vpngate.ui.screens.settings

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Excluded-apps bottom sheet: searchable app list with live count. Saves the
 * selection through the manager (Room) exactly like the old dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcludedAppsSheet(
    manager: ExcludeAppsManager,
    onDismiss: () -> Unit,
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
                .map { ExcludedApp(it.packageName, pm.getApplicationLabel(it).toString()) }
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
                androidx.compose.foundation.layout.Box(
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
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.apps_updated_successfully),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        onDismiss()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.error_saving_apps),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.apply))
                    }
                }
            }
        }
    }
}
