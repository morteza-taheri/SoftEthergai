package vn.unlimit.vpngate.ui.screens.settings

import android.app.Activity
import android.content.Intent
import android.net.InetAddresses
import android.os.Build
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.preference.PreferenceManager
import de.blinkt.openvpn.core.OpenVPNService
import vn.unlimit.vpngate.utils.DateTimeFormatterUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.activities.DetailActivity
import vn.unlimit.vpngate.activities.MainActivity
import vn.unlimit.vpngate.automode.AutoModeLogStore
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnProtocolNames
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.repository.ServerListUpdateScheduler
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.DnsPresets
import vn.unlimit.vpngate.utils.ExcludeAppsManager
import vn.unlimit.vpngate.viewmodels.ConnectionListViewModel
import java.text.DateFormat

/**
 * Index of the 12-hour entry in `DataUtil.serverListUpdateIntervalOptions`.
 * Used only as a UI fallback if the stored value somehow matches no option.
 */
private const val DEFAULT_SERVER_UPDATE_INTERVAL_INDEX = 3

/**
 * Redesigned Settings screen: sleek, well-ordered Material 3 layout with compact
 * card groups, clean typography, consistent icons, and dialog pickers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateHomeRequested: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val dataUtil = remember { app.dataUtil!! }
    val prefs = remember {
        PreferenceManager.getDefaultSharedPreferences(context)
    }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val excludeAppsManager = remember { ExcludeAppsManager(context) }

    // Z1-IMPL-B (Roadmap §12.5.2 item B): emergency server sources. The list
    // is config-only — nothing is fetched from these URLs yet (item C).
    val connectionListViewModel: ConnectionListViewModel = viewModel()
    val emergencySources by connectionListViewModel.emergencySources.collectAsState()
    var emergencyUrlInput by remember { mutableStateOf("") }
    var emergencyLabelInput by remember { mutableStateOf("") }

    // Z1-IMPL-D (Roadmap §12.5.2 item D): manually entered servers. Unlike an
    // emergency source these are never fetched — they are the user's own rows,
    // re-emitted into the server list on every accepted update.
    val manualServers by connectionListViewModel.manualServers.collectAsState()
    var manualHostInput by remember { mutableStateOf("") }
    var manualPortInput by remember { mutableStateOf("") }
    var manualProtocolInput by remember { mutableStateOf(VpnProtocolNames.SSTP) }
    var manualLabelInput by remember { mutableStateOf("") }
    var manualNotesInput by remember { mutableStateOf("") }

    // ---- Setting states
    var notifySpeed by remember {
        mutableStateOf(dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, DataUtil.DEFAULT_NOTIFY_SPEED))
    }
    var blockAds by remember { mutableStateOf(dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false)) }
    var includeUdp by remember { mutableStateOf(dataUtil.getBooleanSetting(DataUtil.INCLUDE_UDP_SERVER, true)) }
    var useCustomDns by remember { mutableStateOf(dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false)) }
    // Defaults shown for the fields when nothing is stored yet. These are only
    // *applied* to the tunnel when Custom DNS is ON, so a fresh install keeps
    // the app's existing default DNS behavior.
    var dns1 by remember {
        mutableStateOf(dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_1, DnsPresets.GOOGLE.primary) ?: "")
    }
    var dns2 by remember {
        mutableStateOf(dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_2, DnsPresets.GOOGLE.secondary) ?: "")
    }
    // Which preset the current address pair corresponds to (CUSTOM when hand-typed).
    var dnsPresetId by remember { mutableStateOf(DnsPresets.idFor(dns1, dns2)) }
    var useDomain by remember { mutableStateOf(dataUtil.getBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, false)) }
    var cacheTimeIndex by remember {
        mutableStateOf(dataUtil.getIntSetting(DataUtil.SETTING_CACHE_TIME_KEY, DataUtil.DEFAULT_CACHE_TIME_INDEX).coerceIn(0, 8))
    }
    var startupScreenIndex by remember {
        mutableStateOf(dataUtil.getIntSetting(DataUtil.SETTING_STARTUP_SCREEN, 0).coerceIn(0, 2))
    }
    var themeIndex by remember {
        mutableStateOf(dataUtil.getIntSetting(DataUtil.SETTING_THEME, 2).coerceIn(0, 2))
    }
    var languageIndex by remember {
        mutableStateOf(
            when (AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
                "fa" -> 2
                "en" -> 1
                else -> 0
            },
        )
    }
    var developerMode by remember { mutableStateOf(dataUtil.getDeveloperMode()) }
    var excludedAppsCount by remember { mutableStateOf(excludeAppsManager.getExcludedAppsCount()) }
    var showExcludedApps by remember { mutableStateOf(false) }
    var autoTimeout by remember { mutableStateOf(dataUtil.getAutoModeTimeoutSeconds()) }
    // R8 (§11.13): Auto-Reconnect in Auto Mode toggle state.
    var autoReconnect by remember { mutableStateOf(dataUtil.getAutoReconnectInAutoMode()) }
    var softetherMaxConnections by remember { mutableStateOf(dataUtil.getSoftEtherMaxConnections()) }
    var cacheExpires by remember { mutableStateOf(dataUtil.connectionCacheExpires) }
    // Scheduled server list refresh interval, in hours (1..24, default 12).
    var serverUpdateInterval by remember {
        mutableStateOf(dataUtil.getServerListUpdateIntervalHours())
    }

    // Dialog picker visibility
    var showThemePicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showStartupPicker by remember { mutableStateOf(false) }
    var showCacheTimePicker by remember { mutableStateOf(false) }
    var showProtocolPriorityDialog by remember { mutableStateOf(false) }
    var showAutoTimeoutPicker by remember { mutableStateOf(false) }
    var showSoftetherConnectionsPicker by remember { mutableStateOf(false) }
    var showServerUpdateIntervalPicker by remember { mutableStateOf(false) }

    val serverUpdateIntervalOptions = remember { dataUtil.serverListUpdateIntervalOptions }
    val serverUpdateIntervalLabels = serverUpdateIntervalOptions.map { hours ->
        when (hours) {
            1 -> stringResource(R.string.setting_server_update_interval_1h)
            24 -> stringResource(R.string.setting_server_update_interval_24h)
            else -> stringResource(R.string.setting_server_update_interval_hours, hours)
        }
    }

    val applyNextConnectionNote = stringResource(R.string.setting_apply_on_next_connection_time)
    val showImportOptions = remember { App.isImportToOpenVPN }

    val cacheTimes = cacheTimeLabels()
    val startupScreens = listOf(
        stringResource(R.string.startup_screen_auto),
        stringResource(R.string.startup_screen_list),
        stringResource(R.string.startup_screen_status),
    )
    val themeNames = listOf(
        stringResource(R.string.setting_theme_system),
        stringResource(R.string.setting_theme_light),
        stringResource(R.string.setting_theme_dark),
    )
    val languageNames = listOf(
        stringResource(R.string.setting_language_system),
        "English",
        "فارسی",
    )

    fun clearListServerCache(showToast: Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { dataUtil.clearConnectionCache() }
            if (ok) {
                cacheExpires = null
                if (showToast) {
                    Toast.makeText(context, context.getString(R.string.setting_clear_cache_success), Toast.LENGTH_SHORT).show()
                }
                withContext(Dispatchers.IO) {
                    context.sendBroadcast(Intent(BaseProvider.ACTION.ACTION_CLEAR_CACHE))
                }
            } else if (showToast) {
                Toast.makeText(context, context.getString(R.string.setting_clear_cache_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.setting),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------------- Connection section
            item {
                SettingsSection(title = stringResource(R.string.status)) {
                    SettingSwitchRow(
                        title = stringResource(R.string.enable_notification_speed),
                        subtitle = stringResource(R.string.enable_notification_speed_hint),
                        icon = Icons.Outlined.Speed,
                        checked = notifySpeed,
                        onChecked = {
                            notifySpeed = it
                            Toast.makeText(context, applyNextConnectionNote, Toast.LENGTH_SHORT).show()
                            dataUtil.setBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, it)
                        },
                    )
                    SettingDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.udp_setting_label),
                        subtitle = stringResource(R.string.udp_setting_hint),
                        icon = Icons.Outlined.Router,
                        checked = includeUdp,
                        onChecked = {
                            includeUdp = it
                            dataUtil.setBooleanSetting(DataUtil.INCLUDE_UDP_SERVER, it)
                            clearListServerCache(false)
                        },
                    )
                    SettingDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.use_domain_label),
                        subtitle = stringResource(R.string.use_domain_hint),
                        icon = Icons.Outlined.Language,
                        checked = useDomain,
                        onChecked = {
                            useDomain = it
                            dataUtil.setBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, it)
                        },
                    )
                    if (!showImportOptions) {
                        SettingDivider()
                        SettingSwitchRow(
                            title = stringResource(R.string.block_ads_setting_label),
                            subtitle = stringResource(R.string.block_ads_setting_hint),
                            icon = Icons.Outlined.Block,
                            checked = blockAds,
                            onChecked = {
                                blockAds = it
                                Toast.makeText(context, applyNextConnectionNote, Toast.LENGTH_SHORT).show()
                                dataUtil.setBooleanSetting(DataUtil.SETTING_BLOCK_ADS, it)
                                if (it && useCustomDns) {
                                    useCustomDns = false
                                    dataUtil.setBooleanSetting(DataUtil.USE_CUSTOM_DNS, false)
                                }
                                prefs.edit().apply {
                                    if (it) {
                                        putBoolean(kittoku.osc.preference.OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.toString(), true)
                                        putString(
                                            kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS.toString(),
                                            AppConfig.getString("vpn_dns_block_ads_primary"),
                                        )
                                        putString(
                                            kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString(),
                                            AppConfig.getString("vpn_dns_block_ads_alternative"),
                                        )
                                    } else {
                                        putBoolean(kittoku.osc.preference.OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.toString(), false)
                                        remove(kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS.toString())
                                        remove(kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString())
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // ---------------- DNS section
            if (!showImportOptions) {
                item {
                    SettingsSection(title = stringResource(R.string.dns_setting_label)) {
                        SettingSwitchRow(
                            title = stringResource(R.string.dns_setting_label),
                            subtitle = stringResource(R.string.dns_setting_hint),
                            icon = Icons.Outlined.Dns,
                            checked = useCustomDns,
                            onChecked = {
                                useCustomDns = it
                                dataUtil.setBooleanSetting(DataUtil.USE_CUSTOM_DNS, it)
                                if (it && blockAds) {
                                    blockAds = false
                                    dataUtil.setBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false)
                                }
                                prefs.edit().apply {
                                    if (it) {
                                        putBoolean(kittoku.osc.preference.OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.toString(), true)
                                        putString(
                                            kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS.toString(),
                                            dns1.ifEmpty { DnsPresets.GOOGLE.primary },
                                        )
                                        putString(
                                            kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString(),
                                            dns2.ifEmpty { DnsPresets.GOOGLE.secondary },
                                        )
                                    } else {
                                        remove(kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS.toString())
                                        remove(kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString())
                                        putBoolean(kittoku.osc.preference.OscPrefKey.DNS_DO_USE_CUSTOM_SERVER.toString(), false)
                                    }
                                }
                            },
                        )
                        AnimatedVisibility(
                            visible = useCustomDns,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.dns_preset_label),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // Preset radio group. Selecting one fills both fields;
                                // "Custom" leaves whatever the user typed alone.
                                val presetOptions: List<Pair<String, String>> =
                                    DnsPresets.all.map { it.id to stringResource(it.labelRes) } +
                                            (DnsPresets.ID_CUSTOM to stringResource(R.string.dns_preset_custom))
                                presetOptions.forEach { (id, label) ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .selectable(
                                                    selected = dnsPresetId == id,
                                                    onClick = {
                                                        dnsPresetId = id
                                                        DnsPresets.byId(id)?.let { preset ->
                                                            dns1 = preset.primary
                                                            dns2 = preset.secondary
                                                        }
                                                    },
                                                )
                                                .padding(vertical = 2.dp),
                                        ) {
                                            RadioButton(
                                                selected = dnsPresetId == id,
                                                onClick = {
                                                    dnsPresetId = id
                                                    DnsPresets.byId(id)?.let { preset ->
                                                        dns1 = preset.primary
                                                        dns2 = preset.secondary
                                                    }
                                                },
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(label, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                OutlinedTextField(
                                    value = dns1,
                                    onValueChange = {
                                        dns1 = it
                                        dnsPresetId = DnsPresets.idFor(it, dns2)
                                    },
                                    label = { Text(stringResource(R.string.dns_ip_1)) },
                                    placeholder = { Text("8.8.8.8") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                OutlinedTextField(
                                    value = dns2,
                                    onValueChange = {
                                        dns2 = it
                                        dnsPresetId = DnsPresets.idFor(dns1, it)
                                    },
                                    label = { Text(stringResource(R.string.dns_ip_2)) },
                                    placeholder = { Text("8.8.4.4") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                Button(
                                    onClick = {
                                        fun isValidIp(ip: String): Boolean =
                                            if (ip.isEmpty()) true else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                InetAddresses.isNumericAddress(ip)
                                            } else {
                                                @Suppress("DEPRECATION")
                                                Patterns.IP_ADDRESS.matcher(ip).matches()
                                            }
                                        if (isValidIp(dns1) && isValidIp(dns2)) {
                                            dataUtil.setStringSetting(DataUtil.CUSTOM_DNS_IP_1, dns1)
                                            dataUtil.setStringSetting(DataUtil.CUSTOM_DNS_IP_2, dns2)
                                            dataUtil.setStringSetting(
                                                DataUtil.CUSTOM_DNS_PRESET,
                                                DnsPresets.idFor(dns1, dns2),
                                            )
                                            prefs.edit()
                                                .putString(kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS.toString(), dns1)
                                                .putString(
                                                    kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString(),
                                                    dns2.ifEmpty { DnsPresets.GOOGLE.secondary },
                                                )
                                                .apply()
                                            scope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.apply))
                                            }
                                        } else {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    context.getString(R.string.dns_invalid_ip),
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.End),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text(stringResource(R.string.apply))
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- Auto Mode section
            item {
                SettingsSection(title = stringResource(R.string.auto_mode)) {
                    // No "Default VPN Protocol" row: Auto Connect Protocol
                    // Priority below is the only source of protocol order.
                    SettingValueRow(
                        title = stringResource(R.string.setting_auto_protocol_priority_label),
                        value = dataUtil.getAutoModeProtocolPriority()
                            .joinToString(" → ") { it.id.lowercase().replace('_', ' ') }
                            .ifEmpty { stringResource(R.string.setting_auto_protocol_priority_default) },
                        icon = Icons.Outlined.Reorder,
                        onClick = { showProtocolPriorityDialog = true },
                    )
                    SettingDivider()
                    SettingValueRow(
                        title = stringResource(R.string.setting_auto_timeout_label),
                        value = "$autoTimeout s",
                        icon = Icons.Outlined.Timer,
                        onClick = { showAutoTimeoutPicker = true },
                    )
                    SettingDivider()
                    // R8 (§11.13): Auto-Reconnect in Auto Mode.
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_auto_reconnect_label),
                        subtitle = stringResource(R.string.setting_auto_reconnect_hint),
                        icon = Icons.Outlined.Autorenew,
                        checked = autoReconnect,
                        onChecked = {
                            autoReconnect = it
                            dataUtil.setAutoReconnectInAutoMode(it)
                        },
                    )
                    SettingDivider()
                    SettingValueRow(
                        title = stringResource(R.string.setting_softether_max_connections_label),
                        value = stringResource(
                            R.string.setting_softether_max_connections_value,
                            softetherMaxConnections,
                        ),
                        icon = Icons.Outlined.Hub,
                        onClick = { showSoftetherConnectionsPicker = true },
                    )
                    SettingDivider()
                    SettingActionRow(
                        title = stringResource(R.string.setting_excluded_apps_label),
                        subtitle = stringResource(R.string.exclude_apps_text, excludedAppsCount),
                        icon = Icons.Outlined.Apps,
                        onClick = { showExcludedApps = true },
                    )
                }
            }

            // ---------------- Cache section
            item {
                SettingsSection(title = stringResource(R.string.setting_cache_label)) {
                    SettingValueRow(
                        title = stringResource(R.string.setting_cache_label),
                        value = cacheTimes[cacheTimeIndex],
                        subtitle = stringResource(R.string.setting_cache_subtitle),
                        icon = Icons.Outlined.Storage,
                        onClick = { showCacheTimePicker = true },
                    )
                    if (cacheExpires != null) {
                        SettingDivider()
                        SettingActionRow(
                            title = stringResource(R.string.setting_cache_auto_clear_label),
                            subtitle = DateTimeFormatterUtil.formatDate(cacheExpires),
                            icon = Icons.Outlined.DeleteOutline,
                            actionLabel = stringResource(R.string.setting_cache_clear),
                            onClick = { clearListServerCache(true) },
                        )
                    }
                    SettingDivider()
                    SettingValueRow(
                        title = stringResource(R.string.setting_server_update_interval_label),
                        value = serverUpdateIntervalLabels[
                            serverUpdateIntervalOptions.indexOf(serverUpdateInterval)
                                .takeIf { it >= 0 } ?: DEFAULT_SERVER_UPDATE_INTERVAL_INDEX
                        ],
                        subtitle = stringResource(R.string.setting_server_update_interval_subtitle),
                        icon = Icons.Outlined.Schedule,
                        onClick = { showServerUpdateIntervalPicker = true },
                    )
                }
            }

            // ---------------- Emergency Server Sources (Z1-IMPL-B, §12.5.2 item B)
            item {
                SettingsSection(title = stringResource(R.string.emergency_source_title)) {
                    Text(
                        text = stringResource(R.string.emergency_source_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    if (emergencySources.isEmpty()) {
                        Text(
                            text = stringResource(R.string.emergency_source_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    } else {
                        emergencySources.forEachIndexed { index, source ->
                            if (index > 0) {
                                SettingDivider()
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = source.label?.takeIf { it.isNotBlank() }
                                            ?: source.url,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (!source.label.isNullOrBlank()) {
                                        Text(
                                            text = source.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = source.enabled,
                                    onCheckedChange = {
                                        connectionListViewModel.toggleEmergencySource(
                                            source.url,
                                            it,
                                        )
                                    },
                                )
                                IconButton(
                                    onClick = {
                                        connectionListViewModel.removeEmergencySource(source.url)
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = stringResource(R.string.emergency_source_remove),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    SettingDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = emergencyUrlInput,
                            onValueChange = { emergencyUrlInput = it },
                            label = { Text(stringResource(R.string.emergency_source_url_label)) },
                            placeholder = { Text("https://") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        OutlinedTextField(
                            value = emergencyLabelInput,
                            onValueChange = { emergencyLabelInput = it },
                            label = { Text(stringResource(R.string.emergency_source_label_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                        Button(
                            onClick = {
                                val added = connectionListViewModel.addEmergencySource(
                                    emergencyUrlInput,
                                    emergencyLabelInput,
                                )
                                if (added) {
                                    emergencyUrlInput = ""
                                    emergencyLabelInput = ""
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.emergency_source_invalid_url),
                                        )
                                    }
                                }
                            },
                            enabled = emergencyUrlInput.isNotBlank(),
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(stringResource(R.string.emergency_source_add))
                        }
                    }
                }
            }

            // ---------------- Manual Servers (Z1-IMPL-D, §12.5.2 item D)
            item {
                SettingsSection(title = stringResource(R.string.manual_server_title)) {
                    Text(
                        text = stringResource(R.string.manual_server_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    if (manualServers.isEmpty()) {
                        Text(
                            text = stringResource(R.string.manual_server_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    } else {
                        manualServers.forEachIndexed { index, server ->
                            if (index > 0) {
                                SettingDivider()
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = server.label?.takeIf { it.isNotBlank() }
                                            ?: "${server.host}:${server.port}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = buildString {
                                            append(server.host)
                                            append(":")
                                            append(server.port)
                                            append(" · ")
                                            append(manualProtocolLabel(server.protocol))
                                            if (!server.label.isNullOrBlank()) {
                                                append(" · ")
                                                append(server.label)
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = server.enabled,
                                    onCheckedChange = {
                                        connectionListViewModel.toggleManualServer(server.id, it)
                                    },
                                )
                                IconButton(
                                    onClick = {
                                        connectionListViewModel.removeManualServer(server.id)
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = stringResource(R.string.manual_server_remove),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    SettingDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = manualHostInput,
                            onValueChange = { manualHostInput = it },
                            label = { Text(stringResource(R.string.manual_server_host_label)) },
                            placeholder = { Text("203.0.113.7") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        OutlinedTextField(
                            value = manualPortInput,
                            onValueChange = { manualPortInput = it },
                            label = { Text(stringResource(R.string.manual_server_port_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Text(
                            text = stringResource(R.string.manual_server_protocol_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // One chip per record-layer protocol, in the same
                            // order the filter sheet uses.
                            listOf(
                                VpnProtocolNames.OPENVPN,
                                VpnProtocolNames.SOFTETHER,
                                VpnProtocolNames.L2TP_IPSEC,
                                VpnProtocolNames.SSTP,
                            ).forEach { protocol ->
                                FilterChip(
                                    selected = manualProtocolInput == protocol,
                                    onClick = { manualProtocolInput = protocol },
                                    label = { Text(manualProtocolLabel(protocol)) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = manualLabelInput,
                            onValueChange = { manualLabelInput = it },
                            label = { Text(stringResource(R.string.manual_server_label_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = manualNotesInput,
                            onValueChange = { manualNotesInput = it },
                            label = { Text(stringResource(R.string.manual_server_notes_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            shape = RoundedCornerShape(12.dp),
                        )
                        Button(
                            onClick = {
                                val added = connectionListViewModel.addManualServer(
                                    host = manualHostInput,
                                    portText = manualPortInput,
                                    protocol = manualProtocolInput,
                                    label = manualLabelInput,
                                    notes = manualNotesInput,
                                )
                                if (added) {
                                    manualHostInput = ""
                                    manualPortInput = ""
                                    manualLabelInput = ""
                                    manualNotesInput = ""
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.manual_server_invalid),
                                        )
                                    }
                                }
                            },
                            enabled = manualHostInput.isNotBlank() && manualPortInput.isNotBlank(),
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(stringResource(R.string.manual_server_add))
                        }
                    }
                }
            }

            // ---------------- Startup + Appearance
            item {
                SettingsSection(title = stringResource(R.string.setting_theme_label)) {
                    SettingValueRow(
                        title = stringResource(R.string.setting_startup_screen),
                        value = startupScreens[startupScreenIndex],
                        icon = Icons.Outlined.Launch,
                        onClick = { showStartupPicker = true },
                    )
                    SettingDivider()
                    SettingValueRow(
                        title = stringResource(R.string.setting_language_label),
                        value = languageNames[languageIndex],
                        icon = Icons.Outlined.Translate,
                        onClick = { showLanguagePicker = true },
                    )
                    SettingDivider()
                    SettingValueRow(
                        title = stringResource(R.string.setting_theme_label),
                        value = themeNames[themeIndex],
                        subtitle = stringResource(R.string.setting_theme_subtitle),
                        icon = Icons.Outlined.Palette,
                        onClick = { showThemePicker = true },
                    )
                    SettingDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_developer_mode_label),
                        subtitle = stringResource(R.string.setting_developer_mode_summary),
                        icon = Icons.Outlined.Code,
                        checked = developerMode,
                        onChecked = {
                            developerMode = it
                            dataUtil.setDeveloperMode(it)
                            CollectorLog.enabled = it
                            AutoModeLogStore.setPaused(!it)
                        },
                    )
                }
            }
        }
    }

    // ---- Dialog Pickers
    if (showStartupPicker) {
        SingleChoiceDialog(
            title = stringResource(R.string.setting_startup_screen),
            options = startupScreens,
            selectedIndex = startupScreenIndex,
            onSelect = { index ->
                startupScreenIndex = index
                dataUtil.setIntSetting(DataUtil.SETTING_STARTUP_SCREEN, index)
                OpenVPNService.setNotificationActivityClass(
                    if (index == 2) DetailActivity::class.java
                    else MainActivity::class.java,
                )
            },
            onDismiss = { showStartupPicker = false },
        )
    }

    if (showLanguagePicker) {
        SingleChoiceDialog(
            title = stringResource(R.string.setting_language_label),
            options = languageNames,
            selectedIndex = languageIndex,
            onSelect = { index ->
                languageIndex = index
                val locales = when (index) {
                    1 -> LocaleListCompat.forLanguageTags("en")
                    2 -> LocaleListCompat.forLanguageTags("fa")
                    else -> LocaleListCompat.getEmptyLocaleList()
                }
                AppCompatDelegate.setApplicationLocales(locales)
            },
            onDismiss = { showLanguagePicker = false },
        )
    }

    if (showThemePicker) {
        SingleChoiceDialog(
            title = stringResource(R.string.setting_theme_label),
            options = themeNames,
            selectedIndex = themeIndex,
            onSelect = { index ->
                if (themeIndex != index) {
                    themeIndex = index
                    dataUtil.setIntSetting(DataUtil.SETTING_THEME, index)
                    val mode = when (index) {
                        1 -> AppCompatDelegate.MODE_NIGHT_NO
                        2 -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                    AppCompatDelegate.setDefaultNightMode(mode)
                    (context as? Activity)?.recreate()
                }
            },
            onDismiss = { showThemePicker = false },
        )
    }

    if (showCacheTimePicker) {
        SingleChoiceDialog(
            title = stringResource(R.string.setting_cache_label),
            options = cacheTimes,
            selectedIndex = cacheTimeIndex,
            onSelect = { index ->
                cacheTimeIndex = index
                dataUtil.setIntSetting(DataUtil.SETTING_CACHE_TIME_KEY, index)
            },
            onDismiss = { showCacheTimePicker = false },
        )
    }

    if (showProtocolPriorityDialog) {
        ProtocolPriorityDialog(
            dataUtil = dataUtil,
            onDismiss = { showProtocolPriorityDialog = false },
        )
    }

    if (showAutoTimeoutPicker) {
        val values = (5..60).toList()
        SingleChoiceDialog(
            title = stringResource(R.string.setting_auto_timeout_label),
            options = values.map { "$it seconds" },
            selectedIndex = values.indexOf(autoTimeout).let { if (it < 0) values.indexOf(12) else it },
            onSelect = { index ->
                autoTimeout = values[index]
                dataUtil.setAutoModeTimeoutSeconds(values[index])
            },
            onDismiss = { showAutoTimeoutPicker = false },
        )
    }

    if (showSoftetherConnectionsPicker) {
        val values = listOf(1, 2, 3, 4, 5, 6, 8)
        SingleChoiceDialog(
            title = stringResource(R.string.setting_softether_max_connections_label),
            options = values.map { context.getString(R.string.setting_softether_max_connections_value, it) },
            selectedIndex = values.indexOf(softetherMaxConnections).let { if (it < 0) values.indexOf(4) else it },
            onSelect = { index ->
                softetherMaxConnections = values[index]
                dataUtil.setSoftEtherMaxConnections(values[index])
            },
            onDismiss = { showSoftetherConnectionsPicker = false },
        )
    }

    if (showServerUpdateIntervalPicker) {
        SingleChoiceDialog(
            title = stringResource(R.string.setting_server_update_interval_label),
            options = serverUpdateIntervalLabels,
            selectedIndex = serverUpdateIntervalOptions.indexOf(serverUpdateInterval)
                .let { if (it < 0) DEFAULT_SERVER_UPDATE_INTERVAL_INDEX else it },
            onSelect = { index ->
                val hours = serverUpdateIntervalOptions[index]
                serverUpdateInterval = hours
                dataUtil.setServerListUpdateIntervalHours(hours)
                // Apply immediately: the user just told the app how often the
                // list may refresh, so a due update should not wait for the
                // next cold start.
                ServerListUpdateScheduler.runIfDue()
            },
            onDismiss = { showServerUpdateIntervalPicker = false },
        )
    }

    if (showExcludedApps) {
        ExcludedAppsSheet(
            manager = excludeAppsManager,
            onDismiss = {
                showExcludedApps = false
                excludedAppsCount = excludeAppsManager.getExcludedAppsCount()
            },
            onSaved = { success ->
                // The sheet has already closed, so the confirmation snackbar is
                // hosted here in Settings where it stays visible.
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            if (success) R.string.apps_updated_successfully
                            else R.string.error_saving_apps,
                        ),
                    )
                }
            },
        )
    }
}

/**
 * Z1-IMPL-D: the display name of a record-layer protocol key.
 *
 * The stored value is the collector's own vocabulary
 * ([VpnProtocolNames]) so a manual row and a collected row name the same
 * protocol identically; only the label is translated.
 */
@Composable
private fun manualProtocolLabel(protocol: String): String = stringResource(
    when (protocol) {
        VpnProtocolNames.SOFTETHER -> R.string.manual_server_protocol_softether
        VpnProtocolNames.OPENVPN -> R.string.manual_server_protocol_openvpn
        VpnProtocolNames.L2TP_IPSEC -> R.string.manual_server_protocol_l2tpipsec
        else -> R.string.manual_server_protocol_sstp
    },
)

@Composable
private fun cacheTimeLabels(): List<String> = listOf(    stringResource(R.string.cache_time_15m),
    stringResource(R.string.cache_time_30m),
    stringResource(R.string.cache_time_1h),
    stringResource(R.string.cache_time_2h),
    stringResource(R.string.cache_time_4h),
    stringResource(R.string.cache_time_6h),
    stringResource(R.string.cache_time_12h),
    stringResource(R.string.cache_time_24h),
    stringResource(R.string.cache_time_never),
)

/**
 * §6-§10 Protocol Priority Profile editor: enable/disable protocols and
 * reorder them (priority = list order). Persisted via DataUtil.
 */
@Composable
private fun ProtocolPriorityDialog(
    dataUtil: DataUtil,
    onDismiss: () -> Unit,
) {
    var ordered by remember {
        mutableStateOf(AutoModeProtocol.entries.toList())
    }
    var enabledIds by remember {
        mutableStateOf(dataUtil.getAutoModeProtocolPriority().map { it.id }.toSet())
    }

    fun persist() {
        dataUtil.setAutoModeProtocolPriority(ordered.filter { it.id in enabledIds })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_auto_protocol_priority_label)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.setting_auto_protocol_priority_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                ordered.forEachIndexed { index, protocol ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = protocol.id in enabledIds,
                            onCheckedChange = { checked ->
                                enabledIds =
                                    if (checked) enabledIds + protocol.id else enabledIds - protocol.id
                                persist()
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                protocol.id.lowercase().replace('_', ' ')
                                    .replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (protocol.id in enabledIds) {
                                Text(
                                    stringResource(
                                        R.string.setting_auto_protocol_priority_rank,
                                        ordered.filter { it.id in enabledIds }.indexOf(protocol) + 1,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        IconButton(
                            enabled = index > 0,
                            onClick = {
                                ordered = ordered.toMutableList().apply {
                                    add(index - 1, removeAt(index))
                                }
                                persist()
                            },
                        ) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "up") }
                        IconButton(
                            enabled = index < ordered.lastIndex,
                            onClick = {
                                ordered = ordered.toMutableList().apply {
                                    add(index + 1, removeAt(index))
                                }
                                persist()
                            },
                        ) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "down") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
