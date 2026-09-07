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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import vn.unlimit.vpngate.provider.BaseProvider
import vn.unlimit.vpngate.utils.AppConfig
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.ExcludeAppsManager
import java.text.DateFormat

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

    // ---- Setting states
    var notifySpeed by remember { mutableStateOf(dataUtil.getBooleanSetting(DataUtil.SETTING_NOTIFY_SPEED, true)) }
    var blockAds by remember { mutableStateOf(dataUtil.getBooleanSetting(DataUtil.SETTING_BLOCK_ADS, false)) }
    var includeUdp by remember { mutableStateOf(dataUtil.getBooleanSetting(DataUtil.INCLUDE_UDP_SERVER, true)) }
    var useCustomDns by remember { mutableStateOf(dataUtil.getBooleanSetting(DataUtil.USE_CUSTOM_DNS, false)) }
    var dns1 by remember { mutableStateOf(dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_1, "8.8.8.8") ?: "") }
    var dns2 by remember { mutableStateOf(dataUtil.getStringSetting(DataUtil.CUSTOM_DNS_IP_2, "") ?: "") }
    var useDomain by remember { mutableStateOf(dataUtil.getBooleanSetting(DataUtil.USE_DOMAIN_TO_CONNECT, false)) }
    var cacheTimeIndex by remember {
        mutableStateOf(dataUtil.getIntSetting(DataUtil.SETTING_CACHE_TIME_KEY, DataUtil.DEFAULT_CACHE_TIME_INDEX).coerceIn(0, 8))
    }
    var startupScreenIndex by remember {
        mutableStateOf(dataUtil.getIntSetting(DataUtil.SETTING_STARTUP_SCREEN, 0).coerceIn(0, 2))
    }
    var themeIndex by remember {
        mutableStateOf(dataUtil.getIntSetting(DataUtil.SETTING_THEME, 0).coerceIn(0, 2))
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
    var autoProtocol by remember {
        mutableStateOf(
            AutoModeProtocol.fromId(
                dataUtil.getStringSetting(DataUtil.SETTING_DEFAULT_VPN_PROTOCOL, null),
            ),
        )
    }
    var autoTimeout by remember { mutableStateOf(dataUtil.getAutoModeTimeoutSeconds()) }
    var softetherMaxConnections by remember { mutableStateOf(dataUtil.getSoftEtherMaxConnections()) }
    var cacheExpires by remember { mutableStateOf(dataUtil.connectionCacheExpires) }

    // Dialog picker visibility
    var showThemePicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showStartupPicker by remember { mutableStateOf(false) }
    var showCacheTimePicker by remember { mutableStateOf(false) }
    var showAutoProtocolPicker by remember { mutableStateOf(false) }
    var showAutoTimeoutPicker by remember { mutableStateOf(false) }
    var showSoftetherConnectionsPicker by remember { mutableStateOf(false) }

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
                                            dns1.ifEmpty { "8.8.8.8" },
                                        )
                                        putString(
                                            kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString(),
                                            dns2.ifEmpty { "8.8.4.4" },
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
                                OutlinedTextField(
                                    value = dns1,
                                    onValueChange = { dns1 = it },
                                    label = { Text(stringResource(R.string.dns_ip_1)) },
                                    placeholder = { Text("8.8.8.8") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                OutlinedTextField(
                                    value = dns2,
                                    onValueChange = { dns2 = it },
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
                                            prefs.edit()
                                                .putString(kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS.toString(), dns1)
                                                .putString(
                                                    kittoku.osc.preference.OscPrefKey.DNS_CUSTOM_ADDRESS_SECONDARY.toString(),
                                                    dns2.ifEmpty { "8.8.4.4" },
                                                )
                                                .apply()
                                            Toast.makeText(context, context.getString(R.string.apply), Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Invalid IP address", Toast.LENGTH_SHORT).show()
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
                    SettingValueRow(
                        title = stringResource(R.string.setting_auto_protocol_label),
                        value = autoProtocol.id.lowercase().replace('_', ' '),
                        icon = Icons.Outlined.VpnLock,
                        onClick = { showAutoProtocolPicker = true },
                    )
                    SettingDivider()
                    SettingValueRow(
                        title = stringResource(R.string.setting_auto_timeout_label),
                        value = "$autoTimeout s",
                        icon = Icons.Outlined.Timer,
                        onClick = { showAutoTimeoutPicker = true },
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

    if (showAutoProtocolPicker) {
        SingleChoiceDialog(
            title = stringResource(R.string.setting_auto_protocol_label),
            options = AutoModeProtocol.entries.map { it.id.lowercase().replace('_', ' ') },
            selectedIndex = AutoModeProtocol.entries.indexOf(autoProtocol),
            onSelect = { index ->
                autoProtocol = AutoModeProtocol.entries[index]
                dataUtil.setStringSetting(
                    DataUtil.SETTING_DEFAULT_VPN_PROTOCOL,
                    autoProtocol.id,
                )
            },
            onDismiss = { showAutoProtocolPicker = false },
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

    if (showExcludedApps) {
        ExcludedAppsSheet(
            manager = excludeAppsManager,
            onDismiss = {
                showExcludedApps = false
                excludedAppsCount = excludeAppsManager.getExcludedAppsCount()
            },
        )
    }
}

@Composable
private fun cacheTimeLabels(): List<String> = listOf(
    stringResource(R.string.cache_time_15m),
    stringResource(R.string.cache_time_30m),
    stringResource(R.string.cache_time_1h),
    stringResource(R.string.cache_time_2h),
    stringResource(R.string.cache_time_4h),
    stringResource(R.string.cache_time_6h),
    stringResource(R.string.cache_time_12h),
    stringResource(R.string.cache_time_24h),
    stringResource(R.string.cache_time_never),
)
