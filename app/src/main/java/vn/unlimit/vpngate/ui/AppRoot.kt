package vn.unlimit.vpngate.ui

import android.content.Context
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.ui.screens.about.AboutScreen
import vn.unlimit.vpngate.ui.screens.automode.AutoModeScreen
import vn.unlimit.vpngate.ui.screens.home.HomeScreen
import vn.unlimit.vpngate.ui.screens.settings.SettingsScreen
import vn.unlimit.vpngate.ui.screens.status.StatusScreen

object NavRoutes {
    const val HOME = "home"
    const val AUTO = "auto"
    const val STATUS = "status"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

private data class NavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val destinations = listOf(
    NavDestination(
        NavRoutes.AUTO,
        R.string.auto_mode,
        Icons.Rounded.Shield,
        Icons.Outlined.Shield,
    ),
    NavDestination(
        NavRoutes.HOME,
        R.string.home,
        Icons.Rounded.Dns,
        Icons.Outlined.Dns,
    ),
    NavDestination(
        NavRoutes.STATUS,
        R.string.status,
        Icons.Rounded.Security,
        Icons.Outlined.Security,
    ),
    NavDestination(
        NavRoutes.SETTINGS,
        R.string.setting,
        Icons.Rounded.Settings,
        Icons.Outlined.Settings,
    ),
    NavDestination(
        NavRoutes.ABOUT,
        R.string.about,
        Icons.Rounded.Info,
        Icons.Outlined.Info,
    ),
)

/**
 * The fully-Compose app shell: Scaffold + NavigationBar + NavHost with
 * the five destinations that used to live in the drawer.
 */
@Composable
fun AppRoot(
    connectionListViewModel: vn.unlimit.vpngate.viewmodels.ConnectionListViewModel,
    startDestination: String,
    onNavigateHomeRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: startDestination
    val context = LocalContext.current
    val connectOneWarning = stringResource(R.string.connect_one_warning)

    // Status tab guard: mirrors the old drawer behavior (warn when no
    // VPN connection has ever been made) — now with a snackbar instead
    // of a Toast because the tab itself stays visible.
    fun navigate(route: String) {
        if (route == NavRoutes.STATUS) {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val sstpHostName = prefs.getString(kittoku.osc.preference.OscPrefKey.HOME_HOSTNAME.toString(), "")
            val hasConnection = vn.unlimit.vpngate.App.instance?.dataUtil?.lastVPNConnection != null ||
                    !sstpHostName.isNullOrEmpty()
            if (!hasConnection) {
                scope.launch { snackbarHostState.showSnackbar(connectOneWarning) }
                return
            }
        }
        if (route != currentRoute) {
            navController.navigate(route) {
                popUpTo(startDestination) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                destinations.forEach { dest ->
                    val selected = currentRoute == dest.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigate(dest.route) },
                        icon = {
                            Icon(
                                if (selected) dest.selectedIcon else dest.unselectedIcon,
                                contentDescription = stringResource(dest.labelRes),
                            )
                        },
                        label = { Text(stringResource(dest.labelRes)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable(NavRoutes.HOME) {
                HomeScreen(
                    connectionListViewModel = connectionListViewModel,
                    onOpenStatus = { navigate(NavRoutes.STATUS) },
                )
            }
            composable(NavRoutes.AUTO) {
                AutoModeScreen(
                    onNavigateHome = { navigate(NavRoutes.HOME) },
                )
            }
            composable(NavRoutes.STATUS) {
                StatusScreen(
                    onOpenSettings = { navigate(NavRoutes.SETTINGS) },
                )
            }
            composable(NavRoutes.SETTINGS) {
                SettingsScreen(
                    onNavigateHomeRequested = onNavigateHomeRequested,
                )
            }
            composable(NavRoutes.ABOUT) {
                AboutScreen()
            }
        }
    }
}
