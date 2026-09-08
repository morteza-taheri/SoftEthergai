package vn.unlimit.vpngate.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.automode.AutoModeProtocol
import vn.unlimit.vpngate.automode.ProtocolPriorityItem
import vn.unlimit.vpngate.automode.ProtocolPriorityManager
import vn.unlimit.vpngate.utils.DataUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolPrioritySheet(
    dataUtil: DataUtil,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var items by remember {
        mutableStateOf(ProtocolPriorityManager.getPriorityList(dataUtil))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_protocol_priority_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.setting_protocol_priority_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(items) { index, item ->
                    ProtocolPriorityRow(
                        index = index,
                        total = items.size,
                        item = item,
                        onToggle = { enabled ->
                            val updated = items.toMutableList()
                            updated[index] = item.copy(isEnabled = enabled)
                            items = updated
                            ProtocolPriorityManager.savePriorityList(dataUtil, updated)
                        },
                        onMoveUp = {
                            if (index > 0) {
                                val updated = items.toMutableList()
                                val temp = updated[index]
                                updated[index] = updated[index - 1]
                                updated[index - 1] = temp
                                items = updated
                                ProtocolPriorityManager.savePriorityList(dataUtil, updated)
                            }
                        },
                        onMoveDown = {
                            if (index < items.size - 1) {
                                val updated = items.toMutableList()
                                val temp = updated[index]
                                updated[index] = updated[index + 1]
                                updated[index + 1] = temp
                                items = updated
                                ProtocolPriorityManager.savePriorityList(dataUtil, updated)
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProtocolPriorityRow(
    index: Int,
    total: Int,
    item: ProtocolPriorityItem,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val protocolName = when (item.protocol) {
        AutoModeProtocol.SOFTETHER_UDP -> stringResource(R.string.proto_softether_udp)
        AutoModeProtocol.SOFTETHER_TCP -> stringResource(R.string.proto_softether_tcp)
        AutoModeProtocol.MS_SSTP -> stringResource(R.string.proto_sstp)
        AutoModeProtocol.OPENVPN_UDP -> stringResource(R.string.proto_openvpn_udp)
        AutoModeProtocol.OPENVPN_TCP -> stringResource(R.string.proto_openvpn_tcp)
        AutoModeProtocol.L2TP_IPSEC -> "L2TP / IPsec"
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isEnabled) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Priority badge
            Surface(
                shape = CircleShape,
                color = if (item.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(28.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (item.isEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = protocolName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (item.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            // Up / Down reorder buttons
            IconButton(
                onClick = onMoveUp,
                enabled = index > 0,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(R.string.move_up),
                    modifier = Modifier.size(18.dp),
                    tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                )
            }

            IconButton(
                onClick = onMoveDown,
                enabled = index < total - 1,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Rounded.ArrowDownward,
                    contentDescription = stringResource(R.string.move_down),
                    modifier = Modifier.size(18.dp),
                    tint = if (index < total - 1) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Switch(
                checked = item.isEnabled,
                onCheckedChange = onToggle,
            )
        }
    }
}
