package vn.unlimit.vpngate.ui.screens.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.unlimit.vpngate.BuildConfig
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.update.AppUpdateChecker
import vn.unlimit.vpngate.update.AppUpdateDecision

/** About: app identity, version, description, links and licenses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)

    // BLOCK v1.0.76 manual update check, moved here from Settings.
    val scope = rememberCoroutineScope()
    var updateCheckBusy by remember { mutableStateOf(false) }
    var updateCheckDecision by remember { mutableStateOf<AppUpdateDecision?>(null) }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    Image(
                        // Must be a raster, not a mipmap: R.mipmap.ic_launcher
                        // resolves to the adaptive-icon XML on API 26+, which
                        // painterResource cannot decode.
                        painter = painterResource(R.drawable.ic_app_logo),
                        contentDescription = stringResource(R.string.app_icon),
                        modifier = Modifier.size(88.dp),
                    )
                    Text(
                        appName,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        stringResource(R.string.version) + " " + BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.about_copyright),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionSurface {
                    Text(
                        stringResource(R.string.update_check_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(16.dp),
                    )
                    ActionRow(
                        label = stringResource(R.string.update_check_button),
                        supporting = if (updateCheckBusy) {
                            stringResource(R.string.update_check_running)
                        } else {
                            stringResource(
                                R.string.update_check_button_subtitle,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE,
                            )
                        },
                        onClick = {
                            if (!updateCheckBusy) {
                                updateCheckBusy = true
                                scope.launch(Dispatchers.IO) {
                                    val decision = runCatching {
                                        AppUpdateChecker().check()
                                    }.getOrElse {
                                        AppUpdateDecision.Unknown(it.message ?: "error")
                                    }
                                    withContext(Dispatchers.Main) {
                                        updateCheckDecision = decision
                                        updateCheckBusy = false
                                    }
                                }
                            }
                        },
                    )
                }
            }
            item {
                SectionSurface {
                    Text(
                        stringResource(R.string.about_html, appName),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    LinkRow(
                        label = stringResource(R.string.vpn_gate_link),
                        onClick = { openUrl(context.getString(R.string.vpn_gate_link)) },
                    )
                }
            }
            item {
                SectionSurface {
                    Text(
                        stringResource(R.string.about_development),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(16.dp),
                    )
                    Text(
                        stringResource(R.string.about_author_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Text(
                        stringResource(R.string.about_author_name),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LinkRow(
                        label = stringResource(R.string.about_author_email),
                        onClick = { openUrl("mailto:" + context.getString(R.string.about_author_email)) },
                    )
                    LinkRow(
                        label = stringResource(R.string.about_author_github),
                        onClick = { openUrl(context.getString(R.string.about_author_github)) },
                    )
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.about_source_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                    Text(
                        stringResource(R.string.about_source_description),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    LinkRow(
                        label = stringResource(R.string.about_source_link),
                        onClick = { openUrl(context.getString(R.string.about_source_link)) },
                    )
                }
            }
            item {
                SectionSurface {
                    Text(
                        stringResource(R.string.license),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(16.dp),
                    )
                    Text(
                        stringResource(R.string.license_html, appName),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    LinkRow(
                        label = stringResource(R.string.license_label),
                        onClick = { openUrl(context.getString(R.string.license_link)) },
                    )
                    HorizontalDivider()
                    LinkRow(
                        label = stringResource(R.string.license_glide_label),
                        onClick = { openUrl(context.getString(R.string.license_glide_link)) },
                    )
                    LinkRow(
                        label = stringResource(R.string.license_sstp_label),
                        onClick = { openUrl(context.getString(R.string.license_sstp_link)) },
                    )
                    LinkRow(
                        label = stringResource(R.string.license_softether_label),
                        onClick = { openUrl(context.getString(R.string.license_softether_link)) },
                    )
                }
            }
        }
    }

    // BLOCK v1.0.76 — manual update-check result dialog, moved here from
    // Settings together with its row.
    updateCheckDecision?.let { decision ->
        val titleRes: Int
        val bodyText: String
        when (decision) {
            is AppUpdateDecision.UpToDate -> {
                titleRes = R.string.update_check_up_to_date_title
                bodyText = stringResource(
                    R.string.update_check_up_to_date_body,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                )
            }
            is AppUpdateDecision.UpdateAvailable -> {
                titleRes = R.string.update_check_available_title
                bodyText = stringResource(
                    R.string.update_check_available_body,
                    decision.manifest.versionName,
                    decision.manifest.versionCode,
                )
            }
            is AppUpdateDecision.Unknown -> {
                titleRes = R.string.update_check_failed_title
                bodyText = stringResource(R.string.update_check_failed_body)
            }
        }
        AlertDialog(
            onDismissRequest = { updateCheckDecision = null },
            title = { Text(stringResource(titleRes)) },
            text = { Text(bodyText) },
            confirmButton = {
                if (decision is AppUpdateDecision.UpdateAvailable) {
                    TextButton(onClick = {
                        // A device without any browser handler must not crash.
                        openUrl(decision.manifest.releasePageUrl)
                        updateCheckDecision = null
                    }) { Text(stringResource(R.string.update_check_open_page)) }
                } else {
                    TextButton(onClick = { updateCheckDecision = null }) {
                        Text(stringResource(R.string.update_check_later))
                    }
                }
            },
            dismissButton = if (decision is AppUpdateDecision.UpdateAvailable) {
                {
                    TextButton(onClick = { updateCheckDecision = null }) {
                        Text(stringResource(R.string.update_check_later))
                    }
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun SectionSurface(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            content()
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Start,
            )
        },
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun ActionRow(
    label: String,
    supporting: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        supportingContent = {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick),
    )
}
