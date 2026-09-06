package vn.unlimit.vpngate.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.utils.DataUtil

/**
 * Redesigned server card: leading flag, country + hostname, metric chips
 * (speed / ping / sessions / score) and protocol badges. Mirrors the info
 * shown by the old item_vpn.xml list row.
 */
@Composable
fun ServerCard(
    connection: VPNGateConnection,
    dataUtil: DataUtil?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isIncludeUdp = dataUtil?.getBooleanSetting(DataUtil.INCLUDE_UDP_SERVER, true) ?: true
    val baseUrl = dataUtil?.baseUrl ?: "https://www.vpngate.net"
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagImage(
                    url = "$baseUrl/images/flags/${connection.countryShort}.png",
                    modifier = Modifier.size(34.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        connection.countryLong ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        connection.calculateHostName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        connection.ip ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.score_short) + ": " + connection.scoreAsString,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetricChip(
                    icon = Icons.Filled.Speed,
                    text = connection.calculateSpeed + " " + stringResource(R.string.speed_unit),
                )
                MetricChip(
                    icon = Icons.Filled.NetworkCheck,
                    text = connection.pingAsString + " " + stringResource(R.string.ping_unit),
                )
                MetricChip(
                    icon = Icons.Filled.Storage,
                    text = connection.numVpnSessionAsString,
                )
                MetricChip(
                    icon = Icons.Filled.Bolt,
                    text = connection.getUpTimeShort(),
                )
            }
            val badges = buildList {
                if (isIncludeUdp && connection.tcpPort > 0) add(ProtocolBadges.TCP)
                if (isIncludeUdp && connection.udpPort > 0) add(ProtocolBadges.UDP)
                if (connection.seTcpPort > 0 || connection.seUdpPort > 0 || connection.seUdpSupported) {
                    add(ProtocolBadges.SOFTETHER)
                }
                if (connection.isSSTPSupport()) add(ProtocolBadges.SSTP)
                if (connection.isL2TPSupport()) add(ProtocolBadges.L2TP)
            }
            if (badges.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    badges.forEach { ProtocolBadge(it) }
                }
            }
        }
    }
}

/** Flag loaded with Coil, rounded placeholder while loading. */
@Composable
fun FlagImage(url: String, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.background(
            MaterialTheme.colorScheme.surfaceVariant,
            RoundedCornerShape(corner),
        ),
        contentAlignment = Alignment.Center,
    ) {
        coil3.compose.AsyncImage(
            model = url,
            contentDescription = null,
            modifier = modifier,
        )
    }
}

/**
 * Big animated circular connect button used by the Status screen. Pulses
 * while connecting, shows a solid ring when connected.
 */
@Composable
fun PowerButton(
    activated: Boolean,
    enabled: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    idleColor: Color = MaterialTheme.colorScheme.outline,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val scale = if (connecting) pulse else 1f
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(size)
            .scale(scale),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (activated) activeColor else MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = if (activated || connecting) 6.dp else 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(size / 2),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 4.dp,
                )
            } else {
                Icon(
                    Icons.Filled.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(size / 2),
                    tint = if (activated) MaterialTheme.colorScheme.onPrimary else idleColor,
                )
            }
        }
    }
}

/** Gradient hero surface used at the top of connection screens. */
@Composable
fun HeroGradient(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.0f),
                    ),
                ),
            ),
    ) { content() }
}

/** Small toggle used by the debug sheet for dump/raw views. */
@Composable
fun FilterBadgeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun VPNGateConnection.getUpTimeShort(): String {
    val seconds = uptime / 1000
    return when {
        seconds < 60 -> uptime.toString()
        seconds < 3600 -> (seconds / 60).toString() + "m"
        seconds < 86400 -> (seconds / 3600).toString() + "h"
        else -> (seconds / 86400).toString() + "d"
    }
}
