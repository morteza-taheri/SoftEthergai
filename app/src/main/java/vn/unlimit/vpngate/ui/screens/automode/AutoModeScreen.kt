package vn.unlimit.vpngate.ui.screens.automode

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.automode.AutoModeController
import vn.unlimit.vpngate.automode.AutoModeLogStore
import vn.unlimit.vpngate.automode.AutoModeState
import vn.unlimit.vpngate.ui.components.FlagImage
import vn.unlimit.vpngate.ui.theme.ExtendedTheme
import vn.unlimit.vpngate.ui.theme.MonospaceStyle
import vn.unlimit.vpngate.viewmodels.AutoModeViewModel

/**
 * Auto Mode screen: a single dynamic button across the four states plus
 * live server info, attempt progress, the live module log window and the
 * Try-next-server button. Logic stays in AutoModeEngine via the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoModeScreen() {
    val context = LocalContext.current
    val viewModel: AutoModeViewModel = viewModel()
    val state by viewModel.state.observeAsState(AutoModeState.Disconnected)

    // Developer-mode gating for the log panel (re-checked on every render,
    // matching renderLogPanelVisibility()).
    var developerMode by remember {
        mutableStateOf(viewModel.dataUtil.getDeveloperMode())
    }
    // Live log lines filtered by the active protocol (mirrors renderLog()).
    var logText by remember { mutableStateOf(formatLog(state, context)) }
    LaunchedEffect(Unit) {
        AutoModeLogStore.lines.collect {
            logText = formatLog(state, context)
            developerMode = viewModel.dataUtil.getDeveloperMode()
        }
    }
    LaunchedEffect(state) {
        logText = formatLog(state, context)
        developerMode = viewModel.dataUtil.getDeveloperMode()
    }

    // VPN permission must be granted BEFORE Auto Mode starts (§12).
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onButtonPressed()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.auto_mode_error_vpn_permission),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun startAutoModeOrRequestPermission() {
        val current = viewModel.state.value
        val isStart = current is AutoModeState.Disconnected || current is AutoModeState.Error
        if (!isStart) {
            viewModel.onButtonPressed()
            return
        }
        try {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent == null) {
                viewModel.onButtonPressed()
            } else {
                vpnPermissionLauncher.launch(prepareIntent)
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.getString(R.string.auto_mode_error_vpn_permission),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val (buttonColor, buttonTextRes, buttonEnabled) = when (val s = state) {
        is AutoModeState.Disconnected ->
            Triple(ExtendedTheme.autoDisconnected, R.string.auto_mode_state_disconnected, true)
        is AutoModeState.Connecting ->
            Triple(ExtendedTheme.autoConnecting, R.string.auto_mode_state_connecting, true)
        is AutoModeState.Connected ->
            Triple(ExtendedTheme.autoConnected, R.string.auto_mode_state_connected, true)
        is AutoModeState.Error ->
            Triple(ExtendedTheme.autoDisconnected, R.string.auto_mode_state_error, true)
        else ->
            Triple(ExtendedTheme.autoDisconnected, R.string.auto_mode_state_disconnected, true)
    }
    val animatedColor by animateColorAsState(buttonColor, label = "autoBtnColor")

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auto_mode)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(buttonTextRes),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (state is AutoModeState.Connecting) {
                val connecting = state as AutoModeState.Connecting
                Text(
                    stringResource(
                        R.string.auto_mode_attempt_progress,
                        connecting.attempt,
                        connecting.total,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Button(
                onClick = { startAutoModeOrRequestPermission() },
                colors = ButtonDefaults.buttonColors(containerColor = animatedColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .height(88.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                if (state is AutoModeState.Connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .height(24.dp)
                            .width(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    stringResource(buttonTextRes),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            OutlinedButton(
                onClick = { viewModel.tryNextServer() },
                enabled = state is AutoModeState.Connecting || state is AutoModeState.Connected,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) {
                Text(stringResource(R.string.auto_mode_try_next_server))
            }
            if (state is AutoModeState.Error) {
                val error = state as AutoModeState.Error
                Text(
                    text = when (error.message) {
                        AutoModeController.ERROR_NO_SERVER ->
                            stringResource(R.string.auto_mode_error_no_server)
                        AutoModeController.ERROR_VPN_PERMISSION ->
                            stringResource(R.string.auto_mode_error_vpn_permission)
                        else -> stringResource(R.string.auto_mode_error_generic)
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            when (val s = state) {
                is AutoModeState.Connecting -> {
                    ServerInfoCard(
                        hostname = s.hostname,
                        ip = s.ip,
                        protocol = s.protocol.id.lowercase().replace('_', ' '),
                        speed = s.speed,
                        ping = s.ping,
                        attempt = s.attempt,
                        total = s.total,
                    )
                }
                is AutoModeState.Connected -> {
                    ServerInfoCard(
                        hostname = s.hostname,
                        ip = s.ip,
                        protocol = s.protocol.id.lowercase().replace('_', ' '),
                        speed = s.speed,
                        ping = s.ping,
                        attempt = null,
                        total = null,
                    )
                }
                else -> Unit
            }
            if (developerMode) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.auto_mode_log_title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.TextButton(onClick = {
                                val text = AutoModeLogStore.asText()
                                if (text.isNotEmpty()) {
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("AutoModeLog", text),
                                    )
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.auto_mode_log_copied),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }) {
                                Text(stringResource(R.string.auto_mode_log_copy))
                            }
                            androidx.compose.material3.TextButton(onClick = {
                                AutoModeLogStore.clear()
                            }) {
                                Text(stringResource(R.string.auto_mode_log_clear))
                            }
                        }
                        val logListState = rememberLazyListState()
                        LaunchedEffect(logText) {
                            if (logListState.layoutInfo.totalItemsCount > 0) {
                                logListState.animateScrollToItem(logListState.layoutInfo.totalItemsCount - 1)
                            }
                        }
                        LaunchedEffect(Unit) {
                            AutoModeLogStore.lines.collect {
                                developerMode = viewModel.dataUtil.getDeveloperMode()
                            }
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                        ) {
                            LazyColumn(
                                state = logListState,
                                contentPadding = PaddingValues(6.dp),
                            ) {
                                items(logText.lines()) { line ->
                                    SelectionContainer {
                                        Text(
                                            line,
                                            style = MonospaceStyle,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerInfoCard(
    hostname: String?,
    ip: String?,
    protocol: String,
    speed: Long,
    ping: Int,
    attempt: Int?,
    total: Int?,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.auto_mode_server_name, hostname ?: "-"),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.auto_mode_server_ip, ip ?: "-"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.auto_mode_server_protocol, protocol),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.auto_mode_server_speed, speed / 1_000_000),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.auto_mode_server_ping, ping),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (attempt != null && total != null) {
                LinearProgressIndicator(
                    progress = { attempt.toFloat() / total.coerceAtLeast(1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
                Text(
                    stringResource(R.string.auto_mode_attempt_n_of_m, attempt, total),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun formatLog(state: AutoModeState, context: Context): String {
    val protocol = when (state) {
        is AutoModeState.Connecting -> state.protocol
        is AutoModeState.Connected -> state.protocol
        else -> null
    }
    val filtered = AutoModeLogStore.filterFor(protocol)
    return if (filtered.isEmpty()) {
        context.getString(R.string.auto_mode_log_empty)
    } else {
        filtered.joinToString("\n") { AutoModeLogStore.format(it) }
    }
}
