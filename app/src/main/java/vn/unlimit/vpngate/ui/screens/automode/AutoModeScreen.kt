package vn.unlimit.vpngate.ui.screens.automode

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.automode.AutoModeController
import vn.unlimit.vpngate.automode.AutoModeEngine
import vn.unlimit.vpngate.automode.AutoModeLogStore
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.AutoModeState
import vn.unlimit.vpngate.automode.ConnectionOrigin
import vn.unlimit.vpngate.state.VpnConnectionStatus
import vn.unlimit.vpngate.ui.theme.MonospaceStyle
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.viewmodels.AutoModeDisplayState
import vn.unlimit.vpngate.viewmodels.tryNextEnabled
import vn.unlimit.vpngate.viewmodels.AutoModeViewModel
import vn.unlimit.vpngate.viewmodels.combineAutoModeState
import vn.unlimit.vpngate.ui.screens.settings.SettingSwitchRow

/**
 * Modern Auto Mode screen with a state-aware animated hero connection button,
 * comprehensive Android permission & battery optimization checks, and full
 * "Try next server" loop iteration support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoModeScreen(
    onNavigateHome: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: AutoModeViewModel = viewModel()
    val state by viewModel.state.observeAsState(AutoModeState.Disconnected)

    // AUTOCONNECT-DEVICE-BUGS-FIX (BUG-1, spec §1.6.32): the hero button renders
    // from the Auto Mode state *combined with* the app-wide tunnel tracker, so a
    // tunnel this run does not own still shows as Connected. `state` above stays
    // the Auto Mode run state and still drives the log, the error card and the
    // "Try next server" affordance.
    val displayState by viewModel.displayState.observeAsState(
        combineAutoModeState(AutoModeState.Disconnected, VpnConnectionStatus.DISCONNECTED),
    )

    var showBatteryDialog by remember { mutableStateOf(false) }

    // v1.0.52 (§1.6.44) TASK B.1: "Auto-Reconnect in Auto Mode" moved here from
    // Settings. Same persisted key (`SETTING_AUTO_RECONNECT_IN_AUTO_MODE`) and
    // same default (true), so a user's existing choice keeps applying and no
    // migration is needed — only the control's location changed, to the screen
    // whose behaviour it governs.
    var autoReconnect by remember {
        mutableStateOf(viewModel.dataUtil.getAutoReconnectInAutoMode())
    }

    // Developer mode and log text
    var developerMode by remember {
        mutableStateOf(viewModel.dataUtil.getDeveloperMode())
    }
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

    // Permission launchers
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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Continue to VPN permission check after notification check
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.onButtonPressed()
        }
    }

    fun proceedWithPermissionsAndConnect() {
        // 1. Check Notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // 2. Check Battery Optimization exemption
        //
        // AUTOCONNECT-DEVICE-BUGS-FIX (BUG-3, spec §1.6.32): the prompt is
        // one-shot. It used to fire whenever the app was not exempt, which is
        // the permanent state after the user taps "Later", so every press
        // re-opened the same dialog. The persisted flag makes it a single ask;
        // the user can still reach the same screen from system Settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            if (!isIgnoringBattery && !viewModel.hasAskedBatteryOptimization()) {
                showBatteryDialog = true
                return
            }
        }

        // 3. Check VPN Service Prepare intent
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

    fun startAutoModeWithAllChecks() {
        // AUTOCONNECT-DEVICE-BUGS-FIX (BUG-1, spec §1.6.32): decide from the same
        // combined state the button renders, so the tap always matches what the
        // user sees. Reading the LiveData value here — rather than a value
        // captured at composition — keeps the decision on the freshest tracker
        // snapshot at click time.
        val shown = viewModel.displayState.value
            ?: combineAutoModeState(
                viewModel.state.value ?: AutoModeState.Disconnected,
                VpnConnectionStatus.DISCONNECTED,
            )
        val current = shown.display
        val isStart = current is AutoModeState.Disconnected || current is AutoModeState.Error
        if (!isStart) {
            if (shown.isExternal) {
                // The tunnel belongs to something else — a manual connect, or a
                // service-level reconnect. AutoModeController's D-4 guard refuses
                // to start in that situation, so pressing here used to do nothing
                // at all while the button read "Connected". Tear the tunnel down
                // instead, which is what "Connected" + tap means to the user.
                viewModel.disconnectExternalTunnel()
            } else {
                // Already connecting or connected: toggle/disconnect immediately
                viewModel.onButtonPressed()
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            val cache = viewModel.dataUtil.connectionsCache
            val count = if (cache != null && cache.size() > 0) {
                cache.size()
            } else {
                val app = context.applicationContext as? App
                try {
                    app?.vpnGateItemDao?.count() ?: 0
                } catch (_: Exception) {
                    0
                }
            }

            if (count == 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_server_list_first),
                        Toast.LENGTH_LONG,
                    ).show()
                    onNavigateHome()
                }
                // Kick off a background acquisition so the list is populated by
                // the time the user lands on Home. It goes through the same
                // acceptance gate as the Update button: a fallback may fill an
                // empty store, a degraded dataset may not.
                try {
                    AutoModeEngine.defaultServers()
                } catch (_: Throwable) {
                    // Network failures are tolerated; Home shows its own error state
                }
                return@launch
            }
            if (count == 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_server_list_first),
                        Toast.LENGTH_LONG,
                    ).show()
                    onNavigateHome()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                proceedWithPermissionsAndConnect()
            }
        }
    }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = {
                showBatteryDialog = false
                // BUG-3: the prompt is one-shot — record it on every exit path.
                viewModel.onBatteryDialogDismissed()
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent) else viewModel.onButtonPressed()
            },
            icon = {
                Icon(
                    Icons.Rounded.BatteryChargingFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = {
                Text(
                    stringResource(R.string.auto_mode_permission_battery_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    stringResource(R.string.auto_mode_permission_battery_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = {
                    showBatteryDialog = false
                    // BUG-3: record the ask before leaving for system Settings.
                    viewModel.onBatteryDialogDismissed()
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {}
                    }
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent) else viewModel.onButtonPressed()
                }) {
                    Text(stringResource(R.string.auto_mode_permission_battery_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    // BUG-3: "Later" is an answer — never ask again.
                    viewModel.onBatteryDialogDismissed()
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent) else viewModel.onButtonPressed()
                }) {
                    Text(stringResource(R.string.auto_mode_permission_battery_skip))
                }
            },
        )
    }

    // The "Default Protocol" setting and its badge were both removed. Auto
    // Connect Protocol Priority (Settings) is now the single source of which
    // protocol is tried and in what order, read by AutoModeController through
    // protocolPriorityProvider(). Nothing here depends on a default protocol.

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero Connection Button
            //
            // BUG-1: renders from the combined display state, not the Auto Mode
            // run state, so a tunnel this run does not own shows as Connected.
            AutoConnectHeroButton(
                state = displayState.display,
                isExternal = displayState.isExternal,
                onClick = { startAutoModeWithAllChecks() },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Try Next Server Button
            // Active during Connecting, Connected (incl. external) and Error; disabled only when
            // the *display* state is genuinely Disconnected. Deriving this from `displayState.display`
            // (the same combined state the hero button renders) is BLOCK v1.0.68 PART A: for a
            // manual/external tunnel the controller is Disconnected, so the old `state !is
            // Disconnected` wrongly disabled the button; skipToNextServer() already handles it.
            val canTryNext = tryNextEnabled(displayState.display)
            FilledTonalButton(
                onClick = { viewModel.tryNextServer() },
                enabled = canTryNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .padding(end = 6.dp),
                )
                Text(
                    text = stringResource(R.string.auto_mode_try_next_server),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Error display card
            if (state is AutoModeState.Error) {
                val error = state as AutoModeState.Error
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = when {
                                // VpnM Phase 1.5: the empty-list guard carries an
                                // already-localised, actionable message, so it
                                // must not be swallowed by the generic branch.
                                error.message == context.getString(
                                    R.string.update_server_list_first,
                                ) -> error.message
                                error.message == AutoModeController.ERROR_NO_SERVER ->
                                    stringResource(R.string.auto_mode_error_no_server)
                                // BLOCK v1.0.75 (D-2): every candidate of the
                                // session has been tried — say so instead of the
                                // generic failure text.
                                error.message == AutoModeController.ERROR_NO_MORE_UNTRIED ->
                                    stringResource(R.string.auto_mode_error_no_more_untried)
                                error.message == AutoModeController.ERROR_NO_ENABLED_PROTOCOL ->
                                    stringResource(R.string.auto_mode_error_no_enabled_protocol)
                                error.message == AutoModeController.ERROR_VPN_PERMISSION ->
                                    stringResource(R.string.auto_mode_error_vpn_permission)
                                // v1.0.42 (§1.6.35 / F2): the offline terminal. Without
                                // this branch the user would see the generic failure text
                                // and could not tell that the device, not the servers,
                                // is the problem.
                                error.message == AutoModeController.ERROR_NO_NETWORK ->
                                    stringResource(R.string.auto_mode_error_no_network)
                                else -> stringResource(R.string.auto_mode_error_generic)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // VpnM Phase 1.5: give the user the way out the spec asks
                    // for — go update the server list.
                    if (error.message == context.getString(R.string.update_server_list_first)) {
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onNavigateHome) {
                            Text(stringResource(R.string.auto_mode_go_to_server_list))
                        }
                    }
                }
            }

            // Active server info card
            when (val s = state) {
                is AutoModeState.Connecting -> {
                    ServerInfoCard(
                        hostname = s.hostname,
                        ip = s.ip,
                        protocol = s.protocol?.id?.replace('_', ' ').orEmpty(),
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
                        protocol = s.protocol?.id?.replace('_', ' ').orEmpty(),
                        speed = s.speed,
                        ping = s.ping,
                        attempt = null,
                        total = null,
                    )
                }
                else -> Unit
            }

            // v1.0.52 (§1.6.44) TASK B.1: the Auto-Reconnect switch lives on the
            // Auto Connect screen now — next to the run it affects, instead of
            // buried in Settings. Same key and default as before.
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.setting_auto_reconnect_label),
                    subtitle = stringResource(R.string.setting_auto_reconnect_hint),
                    icon = Icons.Outlined.Autorenew,
                    checked = autoReconnect,
                    onChecked = {
                        autoReconnect = it
                        viewModel.dataUtil.setAutoReconnectInAutoMode(it)
                    },
                )
            }

            // Developer Log Panel
            if (developerMode) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.auto_mode_log_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
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
                            TextButton(onClick = {
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
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                        ) {
                            LazyColumn(
                                state = logListState,
                                contentPadding = PaddingValues(8.dp),
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

/**
 * Modern circular hero button with animated pulse, rotation, and distinctive
 * color themes for each state:
 * - Disconnected: Sleek Slate Charcoal with Power icon
 * - Connecting: Radiant Amber with spinning Sync icon and breathing pulse rings
 * - Connected: Vivid Emerald Green with Shield icon and ambient aura
 * - Error: Crimson Coral with Refresh icon
 */
@Composable
private fun AutoConnectHeroButton(
    state: AutoModeState,
    isExternal: Boolean = false,
    onClick: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "heroAnimations")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (state is AutoModeState.Connecting) 1.10f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val rotateAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotateAngle",
    )

    // State-specific properties
    val (primaryColor, glowColor, icon, stateTitleRes, stateSubtitleRes) = when (state) {
        is AutoModeState.Disconnected -> StateConfig(
            color = Color(0xFF475569), // Sleek Slate
            glowColor = Color(0x2964748B),
            icon = Icons.Rounded.PowerSettingsNew,
            titleRes = R.string.auto_mode_state_disconnected,
            subtitleRes = R.string.auto_mode_tap_to_connect,
        )
        is AutoModeState.Connecting -> StateConfig(
            color = Color(0xFFF59E0B), // Vivid Amber
            glowColor = Color(0x4DF59E0B),
            icon = Icons.Rounded.Sync,
            titleRes = R.string.auto_mode_state_connecting,
            subtitleRes = R.string.auto_mode_tap_to_stop,
        )
        is AutoModeState.Connected -> StateConfig(
            color = Color(0xFF10B981), // Emerald Green
            glowColor = Color(0x4D10B981),
            icon = Icons.Rounded.Shield,
            titleRes = R.string.auto_mode_state_connected,
            // BLOCK v1.0.75 (D-3): the subtitle answers WHY the tunnel is up —
            // "started by Auto Connect" / "reconnected automatically" /
            // "connected manually" — so a manual tunnel is never mistaken for
            // an Auto Mode win and a reconnect is never mistaken for a fresh
            // user start.
            subtitleRes = when (state.origin) {
                ConnectionOrigin.AUTO_CONNECT -> R.string.automode_origin_auto_connect
                ConnectionOrigin.AUTO_RECONNECT -> R.string.automode_origin_auto_reconnect
                ConnectionOrigin.MANUAL -> R.string.automode_origin_manual
            },
        )
        is AutoModeState.Error -> StateConfig(
            color = Color(0xFFEF4444), // Crimson Red
            glowColor = Color(0x4DEF4444),
            icon = Icons.Rounded.Refresh,
            titleRes = R.string.auto_mode_state_error,
            subtitleRes = R.string.auto_mode_tap_to_retry,
        )
    }

    val animatedButtonColor by animateColorAsState(primaryColor, label = "buttonColorAnim")
    val animatedGlowColor by animateColorAsState(glowColor, label = "glowColorAnim")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(220.dp),
        ) {
            // Outer ambient pulse wave ring
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(if (state is AutoModeState.Connecting || state is AutoModeState.Connected) pulseScale else 1.0f)
                    .clip(CircleShape)
                    .background(animatedGlowColor),
            )

            // Inner middle ring
            Box(
                modifier = Modifier
                    .size(174.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                animatedButtonColor.copy(alpha = 0.35f),
                                animatedButtonColor.copy(alpha = 0.12f),
                            ),
                        ),
                    ),
            )

            // Primary clickable circular button
            Surface(
                modifier = Modifier
                    .size(144.dp)
                    .shadow(elevation = 12.dp, shape = CircleShape, ambientColor = animatedButtonColor, spotColor = animatedButtonColor)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    ),
                shape = CircleShape,
                color = animatedButtonColor,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = stringResource(stateTitleRes),
                        tint = Color.White,
                        modifier = Modifier
                            .size(62.dp)
                            .then(
                                if (state is AutoModeState.Connecting) Modifier.rotate(rotateAngle)
                                else Modifier,
                            ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // State Title
        Text(
            text = stringResource(stateTitleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = animatedButtonColor,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // State Subtitle / Hint
        Text(
            text = stringResource(stateSubtitleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private data class StateConfig(
    val color: Color,
    val glowColor: Color,
    val icon: ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
)

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
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: Host & IP
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = hostname ?: stringResource(R.string.auto_mode_server_name, "-"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.auto_mode_server_ip, ip ?: "-"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // BLOCK v1.0.75 (D-3): the protocol chip is shown ONLY when a
                // concrete protocol is known — a MANUAL tunnel with an ambiguous
                // tracker label ("SoftEther", "OpenVPN") shows no protocol at
                // all rather than a fabricated one.
                if (protocol.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = protocol,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Metrics row: Speed & Ping
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricChip(
                    icon = Icons.Rounded.Speed,
                    label = stringResource(R.string.auto_mode_server_speed, speed / 1_000_000),
                    modifier = Modifier.weight(1f),
                )
                MetricChip(
                    icon = Icons.Rounded.NetworkCheck,
                    label = stringResource(R.string.auto_mode_server_ping, ping),
                    modifier = Modifier.weight(1f),
                )
            }

            // Progress bar if attempting
            if (attempt != null && total != null) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    LinearProgressIndicator(
                        progress = { attempt.toFloat() / total.coerceAtLeast(1) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.auto_mode_attempt_progress, attempt, total),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
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

