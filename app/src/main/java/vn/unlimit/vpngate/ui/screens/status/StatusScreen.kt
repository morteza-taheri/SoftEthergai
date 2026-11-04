package vn.unlimit.vpngate.ui.screens.status

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.state.GlobalVpnTracker
import vn.unlimit.vpngate.ui.components.FlagImage
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.ExcludeAppsManager

/**
 * BLOCK v1.0.82 — the Status screen is a REPORT now.
 *
 * The power toggle it used to carry was dead weight: Auto Mode owns connecting
 * (its hero button is the only place a user starts or stops a tunnel), so the
 * Status button either duplicated it or did nothing. Both it and its handler were
 * removed; what replaces it answers the questions this screen should answer:
 *
 *  * **which server am I on** — country, host, IP, protocol, assigned IP;
 *  * **how fast is it right now** — live download/upload rates;
 *  * **how much has this session moved**;
 *  * **how much have I moved today** (persisted per calendar day, [DailyTraffic]);
 *  * **how much in total**, with the existing "clear statistics" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: StatusViewModel = viewModel()
    val state by viewModel.state.observeAsState(StatusUiState())
    val lifecycleOwner = LocalLifecycleOwner.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.vpnPermissionResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    val sstpPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.sstpPermissionResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        viewModel.notifyPermissionRequest = { intent -> vpnPermissionLauncher.launch(intent) }
        viewModel.onSstpPermissionRequest = { intent -> sstpPermissionLauncher.launch(intent) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewModel.onResumeActivity()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewModel.onPauseActivity()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val excludeAppsManager = remember { ExcludeAppsManager(context.applicationContext) }
    LaunchedEffect(Unit) { viewModel.start(excludeAppsManager) }

    // The server this session is on, and the tunnel's live identity. Read from the
    // single source of truth rather than cached in the state: the tracker is what
    // every stack publishes into, and the last connection is what the list stored.
    val lastServer = viewModel.dataUtil.lastVPNConnection
    val tracker = GlobalVpnTracker.vpnState.value
    val isUp = tracker.status == vn.unlimit.vpngate.state.VpnConnectionStatus.CONNECTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.status)) },
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ConnectionCard(
                    isUp = isUp,
                    connecting = state.connecting,
                    statusText = state.statusText,
                    country = lastServer?.countryLong ?: "",
                    countryCode = lastServer?.countryShort ?: "",
                    host = lastServer?.hostName ?: "",
                    ip = tracker.serverIp ?: lastServer?.ip ?: "",
                    protocol = tracker.protocol ?: viewModel.currentProtocolLabel(),
                    assignedIp = tracker.assignedIp ?: "",
                    showCheckIp = state.showCheckIp,
                    onCheckIp = {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    AppConfig.getString("vpn_check_ip_url").toUri(),
                                ),
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                )
            }
            item {
                SectionCard(title = stringResource(R.string.status_now)) {
                    BigMetricRow(
                        down = state.downloadSpeed,
                        up = state.uploadSpeed,
                        downLabel = stringResource(R.string.status_download),
                        upLabel = stringResource(R.string.status_upload),
                    )
                }
            }
            item {
                SectionCard(title = stringResource(R.string.status_session)) {
                    MetricRow(
                        down = state.downloadSession,
                        up = state.uploadSession,
                        downLabel = stringResource(R.string.downloaded_data),
                        upLabel = stringResource(R.string.uploaded_data),
                    )
                }
            }
            item {
                SectionCard(title = stringResource(R.string.status_today)) {
                    MetricRow(
                        down = state.todayDownload,
                        up = state.todayUpload,
                        downLabel = stringResource(R.string.downloaded_data),
                        upLabel = stringResource(R.string.uploaded_data),
                    )
                }
            }
            item {
                SectionCard(title = stringResource(R.string.status_all_time)) {
                    MetricRow(
                        down = state.totalDownload,
                        up = state.totalUpload,
                        downLabel = stringResource(R.string.downloaded_data),
                        upLabel = stringResource(R.string.uploaded_data),
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = { viewModel.clearStatistics() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.clear_statistics))
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    isUp: Boolean,
    connecting: Boolean,
    statusText: String,
    country: String,
    countryCode: String,
    host: String,
    ip: String,
    protocol: String,
    assignedIp: String,
    showCheckIp: Boolean,
    onCheckIp: () -> Unit,
) {
    val accent = when {
        isUp -> vn.unlimit.vpngate.ui.theme.ExtendedTheme.autoConnected
        connecting -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = accent.copy(alpha = 0.16f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = accent,
                            modifier = Modifier.size(8.dp),
                        ) {}
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = stringResource(
                                when {
                                    isUp -> R.string.status_state_connected
                                    connecting -> R.string.status_state_connecting
                                    else -> R.string.status_state_disconnected
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = accent,
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (protocol.isNotEmpty()) {
                    Text(
                        text = protocol,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (countryCode.isNotEmpty()) {
                    FlagImage(
                        url = (viewModel_baseUrl()) + "/images/flags/" +
                            countryCode.uppercase() + ".png",
                        countryCode = countryCode,
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = country.ifEmpty { host.ifEmpty { "—" } },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    if (host.isNotEmpty() || ip.isNotEmpty()) {
                        Text(
                            text = listOf(host, ip).filter { it.isNotEmpty() }.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (assignedIp.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                InfoLine(label = stringResource(R.string.status_assigned_ip), value = assignedIp)
            }

            if (statusText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (showCheckIp) {
                TextButton(onClick = onCheckIp, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.check_ip))
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

/** Two big numbers, for the live rate card. */
@Composable
private fun BigMetricRow(down: String, up: String, downLabel: String, upLabel: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigMetric(
            value = down,
            label = downLabel,
            icon = { Icon(Icons.Filled.ArrowDownward, null, Modifier.size(14.dp)) },
            modifier = Modifier.weight(1f),
        )
        BigMetric(
            value = up,
            label = upLabel,
            icon = { Icon(Icons.Filled.ArrowUpward, null, Modifier.size(14.dp)) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BigMetric(
    value: String,
    label: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** Two rows of label + value, for the volume cards. */
@Composable
private fun MetricRow(down: String, up: String, downLabel: String, upLabel: String) {
    Column {
        InfoLine(label = downLabel, value = down, icon = {
            Icon(
                Icons.Filled.ArrowDownward,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        })
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        InfoLine(label = upLabel, value = up, icon = {
            Icon(
                Icons.Filled.ArrowUpward,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
        })
    }
}

@Composable
private fun InfoLine(
    label: String,
    value: String,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(modifier = Modifier.size(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

/** The list's base URL, for the flag image (same source the server cards use). */
private fun viewModel_baseUrl(): String =
    vn.unlimit.vpngate.App.instance?.dataUtil?.baseUrl ?: "https://www.vpngate.net"
