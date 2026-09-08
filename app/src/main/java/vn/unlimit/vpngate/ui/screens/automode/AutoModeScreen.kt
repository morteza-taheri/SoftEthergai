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
import vn.unlimit.vpngate.automode.AutoModeLogStore
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.AutoModeState
import vn.unlimit.vpngate.ui.theme.MonospaceStyle
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.viewmodels.AutoModeViewModel

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

    var showBatteryDialog by remember { mutableStateOf(false) }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            if (!isIgnoringBattery) {
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
        val current = viewModel.state.value
        val isStart = current is AutoModeState.Disconnected || current is AutoModeState.Error
        if (!isStart) {
            // Already connecting or connected: toggle/disconnect immediately
            viewModel.onButtonPressed()
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
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent) else viewModel.onButtonPressed()
                }) {
                    Text(stringResource(R.string.auto_mode_permission_battery_skip))
                }
            },
        )
    }

    // Default protocol from settings
    val defaultProtocolId = viewModel.dataUtil.getStringSetting(
        DataUtil.SETTING_DEFAULT_VPN_PROTOCOL,
        AutoModeProtocol.DEFAULT_ID,
    )
    val defaultProtocol = AutoModeProtocol.fromId(defaultProtocolId)

    val cachedList = viewModel.dataUtil.connectionsCache
    var serverCount by remember { mutableStateOf(cachedList?.size() ?: 0) }
    LaunchedEffect(state) {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? App
            val count = app?.vpnGateItemDao?.count() ?: (viewModel.dataUtil.connectionsCache?.size() ?: 0)
            withContext(Dispatchers.Main) {
                serverCount = count
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Protocol info badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.setting_auto_protocol_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = defaultProtocol.id.replace('_', ' '),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (serverCount == 0 && state is AutoModeState.Disconnected) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.auto_mode_no_servers_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = { onNavigateHome() },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.get_servers))
                        }
                    }
                }
            }

            // Hero Connection Button
            AutoConnectHeroButton(
                state = state,
                onClick = { startAutoModeWithAllChecks() },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Try Next Server Button
            // Active during Connecting, Connected, and Error. Disabled during Disconnected.
            val canTryNext = state !is AutoModeState.Disconnected
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
                            text = when (error.message) {
                                AutoModeController.ERROR_NO_SERVER ->
                                    stringResource(R.string.auto_mode_error_no_server)
                                AutoModeController.ERROR_VPN_PERMISSION ->
                                    stringResource(R.string.auto_mode_error_vpn_permission)
                                else -> stringResource(R.string.auto_mode_error_generic)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // Active server info card
            when (val s = state) {
                is AutoModeState.Connecting -> {
                    ServerInfoCard(
                        hostname = s.hostname,
                        ip = s.ip,
                        protocol = s.protocol.id.replace('_', ' '),
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
                        protocol = s.protocol.id.replace('_', ' '),
                        speed = s.speed,
                        ping = s.ping,
                        attempt = null,
                        total = null,
                    )
                }
                else -> Unit
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
            subtitleRes = R.string.auto_mode_connected_secure,
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

