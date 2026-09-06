package vn.unlimit.vpngate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    error = ErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    outline = OutlineLight,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    error = ErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    outline = OutlineDark,
)

/** Extra semantic tokens shared by screens but not part of the M3 scheme. */
data class ExtendedColors(
    val autoDisconnected: Color,
    val autoConnecting: Color,
    val autoConnected: Color,
    val link: Color,
    val goodStatus: Color,
    val primaryDark: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        autoDisconnected = AutoDisconnectedLight,
        autoConnecting = AutoConnectingLight,
        autoConnected = AutoConnectedLight,
        link = LinkLight,
        goodStatus = GoodStatusLight,
        primaryDark = PrimaryDarkLight,
    )
}

val ExtendedTheme: ExtendedColors
    @Composable get() = LocalExtendedColors.current

@Composable
fun VpnGateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extended = if (darkTheme) {
        ExtendedColors(
            autoDisconnected = AutoDisconnectedDark,
            autoConnecting = AutoConnectingDark,
            autoConnected = AutoConnectedDark,
            link = LinkDark,
            goodStatus = GoodStatusDark,
            primaryDark = PrimaryDarkDark,
        )
    } else {
        ExtendedColors(
            autoDisconnected = AutoDisconnectedLight,
            autoConnecting = AutoConnectingLight,
            autoConnected = AutoConnectedLight,
            link = LinkLight,
            goodStatus = GoodStatusLight,
            primaryDark = PrimaryDarkLight,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            shapes = AppShapes,
            content = content,
        )
    }
}
