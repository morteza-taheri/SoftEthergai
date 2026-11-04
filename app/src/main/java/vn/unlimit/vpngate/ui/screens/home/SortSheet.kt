package vn.unlimit.vpngate.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.models.VPNGateConnectionList

/**
 * Sort bottom sheet: property (country/speed/ping/score/uptime/session) and
 * direction. Mirrors SortBottomSheetDialog; persists via the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(
    initialProperty: String?,
    initialType: Int,
    onApply: (String?, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var property by remember { mutableStateOf(initialProperty ?: "") }
    var type by remember { mutableStateOf(initialType) }
    data class SortOption(val labelRes: Int, val value: String)

    val options = listOf(
        SortOption(R.string.country, VPNGateConnectionList.SortProperty.COUNTRY),
        SortOption(R.string.speed, VPNGateConnectionList.SortProperty.SPEED),
        SortOption(R.string.ping, VPNGateConnectionList.SortProperty.PING),
        SortOption(R.string.score, VPNGateConnectionList.SortProperty.SCORE),
        SortOption(R.string.uptime, VPNGateConnectionList.SortProperty.UPTIME),
        SortOption(R.string.session, VPNGateConnectionList.SortProperty.SESSION),
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.sort_property),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = property == option.value,
                        onClick = { property = option.value },
                    )
                    Text(
                        stringResource(option.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Text(
                stringResource(R.string.sort_type),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = type == VPNGateConnectionList.ORDER.ASC,
                    onClick = { type = VPNGateConnectionList.ORDER.ASC },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) {
                    Text(stringResource(R.string.sort_asc))
                }
                SegmentedButton(
                    selected = type == VPNGateConnectionList.ORDER.DESC,
                    onClick = { type = VPNGateConnectionList.ORDER.DESC },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) {
                    Text(stringResource(R.string.sort_desc))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { onApply(property, type) }) {
                    Text(stringResource(R.string.apply))
                }
            }
        }
    }
}
