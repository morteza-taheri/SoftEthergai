package vn.unlimit.vpngate.ui.screens.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.activities.DetailActivity
import vn.unlimit.vpngate.ui.components.FlagImage
import vn.unlimit.vpngate.ui.components.ProtocolBadge
import vn.unlimit.vpngate.ui.screens.settings.ExcludedAppsSheet
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.DataUtil

/**
 * Detail screen: server facts, protocol badges, connect button, and the
 * protocol/import/excluded-apps flows. All VPN logic lives in the host
 * DetailActivity (ported verbatim).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activity: DetailActivity) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as App
    val dataUtil = remember { app.dataUtil!! }
    val conn = activity.connection
    val state by activity.uiState.observeAsState(DetailUiState())
    var showExcludedApps by remember { mutableStateOf(false) }
    var operatorMessageShown by remember { mutableStateOf<String?>(null) }

    // One-shot flows from the activity.
    LaunchedEffect(Unit) {
        operatorMessageShown = null
    }

    if (conn == null) {
        // No connection data (e.g. stale notification) — nothing to show.
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.error_load_profile))
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = { activity.onBackClicked() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back),
                        )
                    }
                },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlagImage(
                            url = dataUtil.baseUrl + "/images/flags/" + conn.countryShort + ".png",
                            modifier = Modifier.size(44.dp),
                        )
                        Column(modifier = Modifier.padding(start = 14.dp)) {
                            Text(conn.countryLong ?: "", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                conn.calculateHostName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                conn.ip ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // Badges
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.showTcpPort) {
                        ProtocolBadge("TCP ${conn.tcpPort}")
                    }
                    if (state.showUdpPort) {
                        ProtocolBadge("UDP ${conn.udpPort}")
                    }
                    if (state.showSstpBadge) {
                        ProtocolBadge("SSTP")
                    }
                    if (state.showL2tpButton) {
                        ProtocolBadge("L2TP")
                    }
                }
            }
            // Server facts
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        DetailRow(stringResource(R.string.score), conn.scoreAsString)
                        DetailRow(stringResource(R.string.speed), conn.calculateSpeed)
                        DetailRow(stringResource(R.string.ping), conn.pingAsString)
                        DetailRow(stringResource(R.string.session), conn.numVpnSessionAsString)
                        DetailRow(stringResource(R.string.uptime), conn.getCalculateUpTime(context))
                        DetailRow(stringResource(R.string.owner), conn.operator ?: "")
                        DetailRow(stringResource(R.string.total_user), conn.totalUser.toString())
                        DetailRow(stringResource(R.string.total_traffic), conn.calculateTotalTraffic)
                        DetailRow(stringResource(R.string.log_type), conn.logType ?: "")
                    }
                }
            }
            // Status + connect
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.statusText.isNotEmpty()) {
                            Text(
                                state.statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.showNetStats) {
                            Text(
                                state.netStats,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.isImportToOpenVPN) {
                            if (dataUtil.hasOpenVPNInstalled()) {
                                Button(
                                    onClick = { activity.onSaveConfigFileClicked() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.save_config_file))
                                }
                            } else {
                                Button(
                                    onClick = { activity.onInstallOpenVpnClicked() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.install_openvpn))
                                }
                            }
                        } else {
                            Button(
                                onClick = { activity.onConnectClicked() },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        state.connected -> MaterialTheme.colorScheme.error
                                        state.connecting -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                ),
                            ) {
                                Text(
                                    state.connectText.ifEmpty {
                                        stringResource(R.string.connect_to_this_server)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            if (state.showCheckIp) {
                                TextButton(onClick = { activity.onCheckIpClicked() }) {
                                    Text(stringResource(R.string.check_ip))
                                }
                            }
                        }
                        if (state.showL2tpButton) {
                            OutlinedButton(
                                onClick = { activity.onL2tpConnectClicked() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.l2tp_connect))
                            }
                        }
                        OutlinedButton(
                            onClick = { showExcludedApps = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    R.string.exclude_apps_text, state.excludedAppsCount,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // Protocol selection dialog (old VpnProtocolSelectionDialog)
    if (state.showProtocolDialog) {
        ProtocolSelectionDialog(
            options = activity.protocolOptions(),
            onSelect = { protocol ->
                activity.showProtocolDialog = false
                activity.onProtocolSelected(protocol)
            },
            onDismiss = { activity.showProtocolDialog = false },
        )
    }

    // TCP-or-UDP choice for saving the .ovpn config (old ConnectionUseProtocol)
    if (state.showUseProtocolDialog) {
        UseProtocolDialog(
            onUseUdp = { useUdp -> activity.onUseProtocolChoice(useUdp) },
            onDismiss = { activity.onUseProtocolChoice(false) },
        )
    }

    // Operator message dialog (old MessageDialog)
    state.operatorMessage?.let { message ->
        if (operatorMessageShown != message) {
            AlertDialog(
                onDismissRequest = { operatorMessageShown = null },
                title = { Text(stringResource(R.string.operator_message_title)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { operatorMessageShown = null }) {
                        Text(stringResource(R.string.close))
                    }
                },
            )
        }
    }

    if (showExcludedApps) {
        val manager = remember { activity.excludeAppsManager() }
        ExcludedAppsSheet(
            manager = manager,
            onDismiss = {
                showExcludedApps = false
                activity.bindDataRefresh()
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
    HorizontalDivider()
}

/** Old VpnProtocolSelectionDialog, recreated with M3. */
@Composable
fun ProtocolSelectionDialog(
    options: List<ProtocolOption>,
    onSelect: (VpnProtocol) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vpn_protocol_selection_title)) },
        text = {
            Column {
                options.forEach { option ->
                    val labelRes = when (option.protocol) {
                        VpnProtocol.OPENVPN_TCP -> R.string.openvpn_over_tcp
                        VpnProtocol.OPENVPN_UDP -> R.string.openvpn_over_udp
                        VpnProtocol.SOFTETHER_TCP -> R.string.softether_over_tcp
                        VpnProtocol.SOFTETHER_UDP -> R.string.softether_over_udp
                        VpnProtocol.MS_SSTP -> R.string.connect_use_ms_sstp
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { onSelect(option.protocol) },
                    ) {
                        RadioButton(selected = false, onClick = { onSelect(option.protocol) })
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(stringResource(labelRes))
                            option.detail?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Old ConnectionUseProtocol dialog (TCP vs UDP for .ovpn export). */
@Composable
fun UseProtocolDialog(
    onUseUdp: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connect_use)) },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { onUseUdp(false) },
                ) {
                    RadioButton(selected = false, onClick = { onUseUdp(false) })
                    Text(
                        stringResource(R.string.connect_use_tcp),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { onUseUdp(true) },
                ) {
                    RadioButton(selected = false, onClick = { onUseUdp(true) })
                    Text(
                        stringResource(R.string.connect_use_udp),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
