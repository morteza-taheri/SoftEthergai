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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.preference.PreferenceManager
import de.blinkt.openvpn.core.OpenVPNService
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
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.repository.ManualCsvImport
import vn.unlimit.vpngate.repository.ServerListUpdateScheduler
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.DnsPresets
import vn.unlimit.vpngate.utils.ExcludeAppsManager
import vn.unlimit.vpngate.viewmodels.ConnectionListViewModel
import java.text.DateFormat

/**
 * Index of the 1-week (168 h) entry in `DataUtil.serverListUpdateIntervalOptions`.
 * Used only as a UI fallback if the stored value somehow matches no option.
 */
private const val DEFAULT_SERVER_UPDATE_INTERVAL_INDEX = 6

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
    // v1.0.71: resource lookups below go through LocalResources rather than
    // LocalContext.current so they stay configuration-aware. `context` is kept
    // for the non-resource uses (Toast, intents, managers); the older
    // `context.getString(...)` calls in this file are the pre-existing
    // LocalContextGetResourceValueCall backlog, which this change does not add
    // to.
    val resources = LocalResources.current
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

    // Z1-IMPL-D (Roadmap §12.5.2 item D): rows created by the old manual-add
    // form. v1.0.54 (§1.6.49) PART 4 replaced that form with CSV import, but
    // these rows are still listed here so nobody's data disappears.
    val manualServers by connectionListViewModel.manualServers.collectAsState()
    // v1.0.54 (§1.6.49) PART 4: imported CSV servers.
    val importedServers by connectionListViewModel.importedServers.collectAsState()
    var csvTextInput by remember { mutableStateOf("") }
    var importSummary by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }

    // BLOCK v1.0.76 — APP UPDATE CHECKER: manual check from Settings.
    var updateCheckBusy by remember { mutableStateOf(false) }
    var updateCheckDecision by remember {
        mutableStateOf<vn.unlimit.vpngate.update.AppUpdateDecision?>(null)
    }

    // ---- Setting states
    var notifySpeed by remember {
        mutableStateOf(dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, DataUtil.DEFAULT_NOTIFY_SPEED))
    }
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
    // v1.0.52 (§1.6.44) TASK B.1: the "Auto-Reconnect in Auto Mode" switch moved
    // to the Auto Connect screen (AutoModeScreen.kt). It keeps the same
    // persisted key and the same default, so this is a move, not a behaviour
    // change — and a value already stored by the user keeps applying.
    //
    // v1.0.52 TASK A.1: the "try tunnel first for server-list update" switch was
    // REMOVED. Collector routing is fully automatic; the collector measures
    // which transport works instead of asking the user to declare it.
    var softetherMaxConnections by remember { mutableStateOf(dataUtil.getSoftEtherMaxConnections()) }
    // Scheduled server list refresh interval, in hours (1..24, default 12).
    var serverUpdateInterval by remember {
        mutableStateOf(dataUtil.getServerListUpdateIntervalHours())
    }

    // Dialog picker visibility
    var showThemePicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showStartupPicker by remember { mutableStateOf(false) }
    var showProtocolPriorityDialog by remember { mutableStateOf(false) }
    var showAutoTimeoutPicker by remember { mutableStateOf(false) }
    var showSoftetherConnectionsPicker by remember { mutableStateOf(false) }
    var showServerUpdateIntervalPicker by remember { mutableStateOf(false) }

    val serverUpdateIntervalOptions = remember { dataUtil.serverListUpdateIntervalOptions }
    val serverUpdateIntervalLabels = serverUpdateIntervalOptions.map { hours ->
        when (hours) {
            1 -> stringResource(R.string.setting_server_update_interval_1h)
            24 -> stringResource(R.string.setting_server_update_interval_24h)
            168 -> stringResource(R.string.setting_server_update_interval_168h)
            else -> stringResource(R.string.setting_server_update_interval_hours, hours)
        }
    }

    val applyNextConnectionNote = stringResource(R.string.setting_apply_on_next_connection_time)
    val showImportOptions = remember { App.isImportToOpenVPN }

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
            // v1.0.54 (§1.6.47) PART 2.3: the one manual cache control. It
            // clears transient files only (app cacheDir + a legacy marker) and
            // has no database access, so the server dataset, imported/manual
            // servers, emergency sources, excluded apps and settings are all
            // untouched.
            val ok = withContext(Dispatchers.IO) { dataUtil.clearTransientCache() }
            if (ok) {
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

    /**
     * v1.0.54 (§1.6.49) PART 4: runs one import and returns the single line the
     * user sees — accepted/rejected counts plus the first few reasons.
     *
     * Every reason string comes from the parser's own verdict; nothing here
     * invents a cause. Content is never logged and never echoed back, because a
     * pasted profile can contain a private address.
     */
    suspend fun runImport(text: String): String {
        val outcome = connectionListViewModel.importManualCsv(text)
        val result = outcome.parse
        return when (result) {
            is ManualCsvImport.Result.Success -> {
                // REQ-RETENTION-003/004 (v1.0.71): the counts come from what the
                // quota-aware store actually persisted, not from how many rows
                // the parser recognised — otherwise a full manual quota would
                // read as a successful import of rows that were never stored.
                val stored = outcome.stored
                val imported = stored?.stored ?: 0
                val rejected = result.rejectedCount + (stored?.quotaRejected ?: 0)
                val base = context.getString(
                    R.string.manual_server_result,
                    imported,
                    rejected,
                )
                val withReasons = if (result.rejectedReasons.isEmpty()) {
                    base
                } else {
                    base + " — " + context.getString(
                        R.string.manual_server_reject_reasons,
                        result.rejectedReasons.joinToString("; "),
                    )
                }
                if (stored != null && stored.quotaRejected > 0) {
                    withReasons + "\n" + resources.getString(
                        R.string.manual_server_quota_reached,
                        stored.manualCount,
                        stored.quota,
                        stored.totalCapacity,
                    )
                } else {
                    withReasons
                }
            }
            is ManualCsvImport.Result.Failure -> when (result.reason) {
                ManualCsvImport.Reason.TOO_LARGE ->
                    context.getString(R.string.manual_server_too_large)
                ManualCsvImport.Reason.TOO_MANY_ROWS ->
                    context.getString(R.string.manual_server_too_many_rows, ManualCsvImport.MAX_ROWS)
                ManualCsvImport.Reason.UNRECOGNIZED ->
                    context.getString(R.string.manual_server_unrecognized)
                ManualCsvImport.Reason.EMPTY,
                ManualCsvImport.Reason.NO_VALID_ROWS ->
                    context.getString(R.string.manual_server_import_failed)
                ManualCsvImport.Reason.PARSE_ERROR ->
                    context.getString(R.string.manual_server_import_error) +
                        (result.detail?.let { ": $it" } ?: "")
            }
        }
    }

    // System document picker (SAF). The MIME filter includes */* on purpose:
    // messengers label CSV attachments inconsistently, and refusing a file the
    // user can see would be worse than parsing it and reporting "unrecognised".
    val csvPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importBusy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        readCappedUtf8(input, ManualCsvImport.MAX_BYTES)?.toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            importSummary = if (text == null) {
                context.getString(R.string.manual_server_too_large)
            } else {
                runImport(text)
            }
            importBusy = false
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
                        title = stringResource(R.string.use_domain_label),
                        subtitle = stringResource(R.string.use_domain_hint),
                        icon = Icons.Outlined.Language,
                        checked = useDomain,
                        onChecked = {
                            useDomain = it
                            dataUtil.setBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, it)
                        },
                    )
                    // v1.0.54 (§1.6.47) PART 2.1: the "Include UDP server"
                    // switch is GONE. UDP servers are always shown and always
                    // included — that was the switch's default, so this is the
                    // default made permanent rather than a behaviour change.
                    //
                    // v1.0.54 PART 2.2: the "Block Ads (Pro only)" switch is
                    // GONE too, with its DNS preset and its flavor gating.
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
                                // v1.0.54 (§1.6.47) PART 2.2: the mutual
                                // exclusion with the removed Block-Ads switch
                                // is gone; Custom DNS is now the only DNS
                                // choice in the app.
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
                    // v1.0.52 (§1.6.44) TASK B.1: "Auto-Reconnect in Auto Mode"
                    // moved to the Auto Connect screen, where the behaviour it
                    // governs actually happens. Same key, same default.
                    //
                    // v1.0.52 TASK A.1: the "try tunnel first for server-list
                    // update" switch was removed — the collector routes itself.
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
                    // v1.0.54 (§1.6.47) PART 2.3: "Cache save time" and
                    // "Cache will auto clear at" are gone. Exactly one manual
                    // control remains, and it clears transient files only —
                    // never the server dataset, imported/manual servers,
                    // emergency sources, excluded apps or settings.
                    SettingActionRow(
                        title = stringResource(R.string.setting_cache_clear_label),
                        subtitle = stringResource(R.string.setting_cache_clear_subtitle),
                        icon = Icons.Outlined.DeleteOutline,
                        actionLabel = stringResource(R.string.setting_cache_clear),
                        onClick = { clearListServerCache(true) },
                    )
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
                    // v1.0.54 (§1.6.47) PART 2.5: the seeded default URL can be
                    // deleted by the user, and the one-shot seed flag then
                    // refuses to put it back (that is the documented policy: a
                    // deletion is a decision). This is the way back — an
                    // explicit, idempotent action rather than a re-seed. It
                    // adds the same URL the publisher writes into
                    // R.string.default_emergency_source_url and never touches
                    // the user's other sources.
                    val defaultEmergencyUrl = stringResource(R.string.default_emergency_source_url)
                    if (emergencySources.none { it.url == defaultEmergencyUrl }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    connectionListViewModel.addEmergencySource(
                                        defaultEmergencyUrl,
                                        context.getString(R.string.emergency_source_default_label),
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.emergency_source_restore_default))
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

            // ---------------- Import servers from CSV (v1.0.54 §1.6.49 PART 4)
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
                    if (importedServers.isEmpty() && manualServers.isEmpty()) {
                        Text(
                            text = stringResource(R.string.manual_server_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    } else {
                        // Imported rows first: they are what this section is for.
                        importedServers.forEachIndexed { index, server ->
                            if (index > 0) SettingDivider()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = server.hostName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = buildString {
                                            append(server.ip ?: "")
                                            if (server.seTcpPort > 0) append(" · SE/TCP ${server.seTcpPort}")
                                            if (server.seUdpPort > 0) append(" · SE/UDP ${server.seUdpPort}")
                                            if (!server.openVpnConfigData.isNullOrBlank()) append(" · OpenVPN")
                                            if (server.isSSTPSupport) append(" · SSTP")
                                            if (server.isL2TPSupport) append(" · L2TP")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                IconButton(
                                    onClick = {
                                        connectionListViewModel.removeImportedServer(server.hostName)
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
                        // Rows created by the old manual-add form. They keep
                        // their enable switch and delete button, so the rewrite
                        // takes no capability away.
                        manualServers.forEachIndexed { index, server ->
                            if (index > 0 || importedServers.isNotEmpty()) SettingDivider()
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
                                        text = "${server.host}:${server.port} · ${server.protocol}",
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
                            value = csvTextInput,
                            onValueChange = { csvTextInput = it },
                            label = { Text(stringResource(R.string.manual_server_paste_label)) },
                            placeholder = { Text(stringResource(R.string.manual_server_paste_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 5,
                            maxLines = 10,
                            shape = RoundedCornerShape(12.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    importBusy = true
                                    scope.launch {
                                        importSummary = runImport(csvTextInput)
                                        importBusy = false
                                    }
                                },
                                enabled = csvTextInput.isNotBlank() && !importBusy,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(stringResource(R.string.manual_server_import_paste))
                            }
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    importSummary = null
                                    csvPicker.launch(arrayOf("text/csv", "text/plain", "*/*"))
                                },
                                enabled = !importBusy,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(stringResource(R.string.manual_server_import_file))
                            }
                        }
                        importSummary?.let { summary ->
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = stringResource(R.string.manual_server_format_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

                // BLOCK v1.0.76 — APP UPDATE CHECKER: the manual check row.
                SettingsSection(title = stringResource(R.string.update_check_title)) {
                    SettingValueRow(
                        title = stringResource(R.string.update_check_button),
                        value = "",
                        subtitle = if (updateCheckBusy) {
                            stringResource(R.string.update_check_running)
                        } else {
                            stringResource(
                                R.string.update_check_button_subtitle,
                                vn.unlimit.vpngate.BuildConfig.VERSION_NAME,
                                vn.unlimit.vpngate.BuildConfig.VERSION_CODE,
                            )
                        },
                        icon = Icons.Outlined.Launch,
                        onClick = {
                            if (!updateCheckBusy) {
                                updateCheckBusy = true
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val decision = runCatching {
                                        vn.unlimit.vpngate.update.AppUpdateChecker().check()
                                    }.getOrElse {
                                        vn.unlimit.vpngate.update.AppUpdateDecision.Unknown(
                                            it.message ?: "error",
                                        )
                                    }
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        updateCheckDecision = decision
                                        updateCheckBusy = false
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // ---- Dialog Pickers

    // BLOCK v1.0.76 — the update-check result dialog.
    updateCheckDecision?.let { decision ->
        val titleRes: Int
        val bodyText: String
        when (decision) {
            is vn.unlimit.vpngate.update.AppUpdateDecision.UpToDate -> {
                titleRes = R.string.update_check_up_to_date_title
                bodyText = stringResource(
                    R.string.update_check_up_to_date_body,
                    vn.unlimit.vpngate.BuildConfig.VERSION_NAME,
                    vn.unlimit.vpngate.BuildConfig.VERSION_CODE,
                )
            }
            is vn.unlimit.vpngate.update.AppUpdateDecision.UpdateAvailable -> {
                titleRes = R.string.update_check_available_title
                bodyText = stringResource(
                    R.string.update_check_available_body,
                    decision.manifest.versionName,
                    decision.manifest.versionCode,
                )
            }
            is vn.unlimit.vpngate.update.AppUpdateDecision.Unknown -> {
                titleRes = R.string.update_check_failed_title
                bodyText = stringResource(R.string.update_check_failed_body)
            }
        }
        AlertDialog(
            onDismissRequest = { updateCheckDecision = null },
            title = { Text(stringResource(titleRes)) },
            text = { Text(bodyText) },
            confirmButton = {
                if (decision is vn.unlimit.vpngate.update.AppUpdateDecision.UpdateAvailable) {
                    TextButton(onClick = {
                        // BLOCK v1.0.76 audit fix: a device without any browser
                        // handler must not crash — the dialog just closes.
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(decision.manifest.releasePageUrl),
                                )
                            )
                        }
                        updateCheckDecision = null
                    }) { Text(stringResource(R.string.update_check_open_page)) }
                } else {
                    TextButton(onClick = { updateCheckDecision = null }) {
                        Text(stringResource(R.string.update_check_later))
                    }
                }
            },
            dismissButton = if (decision is vn.unlimit.vpngate.update.AppUpdateDecision.UpdateAvailable) {
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

    if (showProtocolPriorityDialog) {
        ProtocolPriorityDialog(
            dataUtil = dataUtil,
            onDismiss = { showProtocolPriorityDialog = false },
        )
    }

    if (showAutoTimeoutPicker) {
        // v1.0.46 (§1.6.39) CHANGE B: floor raised 5 -> 8 (operator
        // requirement). v1.0.70 (REQ-TIMEOUT-001): the approved range is
        // 8..30 with a 12 s default. `autoTimeout` comes from
        // DataUtil.getAutoModeTimeoutSeconds(), which clamps on read, so a
        // value stored by an older build at 31..60 arrives here as 30 and
        // `indexOf` always finds it.
        val values = (8..30).toList()
        SingleChoiceDialog(
            title = stringResource(R.string.setting_auto_timeout_label),
            options = values.map { "$it seconds" },
            selectedIndex = values.indexOf(autoTimeout).let { if (it < 0) values.indexOf(8) else it },
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
 * v1.0.54 (§1.6.49) PART 4: reads at most [maxBytes] from [input] and returns
 * them, or null when the stream is larger than that.
 *
 * Why a manual cap instead of `readBytes()`: the document picker hands back a
 * stream of *unknown* length chosen by whoever sent the file. A messenger
 * attachment can legitimately be a 40 MB video with a `.csv` name. Buffering it
 * whole to discover that would spike the heap on a low-RAM device and, worse,
 * happen before the parser's own `MAX_BYTES` check could run — the parser only
 * sees a `String`, which already costs ~2 bytes per char. So the cap is applied
 * at the byte layer, before any decoding.
 *
 * Reading is chunked, and the running total is compared against [maxBytes] after
 * each chunk, so an oversize stream is abandoned as soon as it crosses the
 * limit rather than after it has been fully buffered. The consequence is that
 * the function never distinguishes "exactly at the limit" from "over the
 * limit" by truncating: it returns the bytes only when the whole stream fit.
 * Silently importing a truncated profile list would be worse than refusing it.
 *
 * Returns null both for oversize and for a read failure; the caller reports a
 * single "too large / unreadable" line, because from the user's point of view
 * the file could not be used either way and a precise distinction would only
 * invite them to retry the same file.
 */
internal fun readCappedUtf8(input: java.io.InputStream, maxBytes: Int): ByteArray? {
    val buffer = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
    val chunk = ByteArray(8192)
    var total = 0
    return try {
        var over = false
        while (!over) {
            val read = input.read(chunk)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                over = true
            } else {
                buffer.write(chunk, 0, read)
            }
        }
        if (over) null else buffer.toByteArray()
    } catch (e: java.io.IOException) {
        // A picker stream can fail mid-read when the provider is a cloud app
        // that lost its network. The caller shows one "unusable file" line;
        // throwing here would surface as a crash instead.
        null
    }
}

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
