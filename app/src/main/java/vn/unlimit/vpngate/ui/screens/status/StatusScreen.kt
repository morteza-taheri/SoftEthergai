package vn.unlimit.vpngate.ui.screens.status

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.net.toUri
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.ui.components.PowerButton
import vn.unlimit.vpngate.ui.screens.settings.ExcludedAppsSheet
import vn.unlimit.vpngate.ui.screens.status.StatusUiState
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.ExcludeAppsManager

/**
 * Status: big power toggle, status text, check-IP link, traffic grid and
 * the excluded-apps entry. All logic lives in StatusViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val viewModel: StatusViewModel = viewModel()
    val state by viewModel.state.observeAsState(StatusUiState())
    var showExcludedApps by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Wire VPN permission requests from the ViewModel to the OS dialog.
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

    // Lifecycle mirrors: bind/unbind the OpenVPN service while visible.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewModel.onResumeActivity()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewModel.onPauseActivity()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val excludeAppsManager = remember { ExcludeAppsManager(context.applicationContext) }
    LaunchedEffect(Unit) {
        viewModel.start(excludeAppsManager)
    }

    val powerColor by animateColorAsState(
        if (state.powerActivated) {
            vn.unlimit.vpngate.ui.theme.ExtendedTheme.autoConnected
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "powerColor",
    )

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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PowerButton(
                            activated = state.powerActivated,
                            enabled = state.powerEnabled,
                            connecting = state.connecting,
                            onClick = { viewModel.onPowerButtonClicked() },
                            activeColor = powerColor,
                        )
                        Text(
                            state.statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        TextButton(onClick = { showExcludedApps = true }) {
                            Text(
                                stringResource(
                                    R.string.exclude_apps_text,
                                    state.excludedAppsCount,
                                ),
                            )
                        }
                        if (state.showCheckIp) {
                            TextButton(onClick = {
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
                            }) {
                                Text(stringResource(R.string.check_ip))
                            }
                        }
                    }
                }
            }
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.traffic_session),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TrafficRow(
                            label = stringResource(R.string.uploaded_data),
                            value = "${state.uploadSpeed}  |  ${state.uploadSession}",
                        )
                        HorizontalDivider()
                        TrafficRow(
                            label = stringResource(R.string.downloaded_data),
                            value = "${state.downloadSpeed}  |  ${state.downloadSession}",
                        )
                        Text(
                            stringResource(R.string.total_traffic),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        TrafficRow(
                            label = stringResource(R.string.uploaded_data),
                            value = state.totalUpload,
                        )
                        HorizontalDivider()
                        TrafficRow(
                            label = stringResource(R.string.downloaded_data),
                            value = state.totalDownload,
                        )
                    }
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

    if (showExcludedApps) {
        ExcludedAppsSheet(
            manager = excludeAppsManager,
            onDismiss = { showExcludedApps = false },
        )
    }
}

@Composable
private fun TrafficRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}
