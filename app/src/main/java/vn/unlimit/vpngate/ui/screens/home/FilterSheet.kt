package vn.unlimit.vpngate.ui.screens.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.models.VPNGateConnectionList

/**
 * Filter bottom sheet: protocol filter chips + numeric filters with
 * operators. Mirrors FilterBottomSheetDialog 1:1 (incl. the >=1 protocol
 * validation and the Android 13+ L2TP hiding).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    initial: VPNGateConnectionList.Filter?,
    onApply: (VPNGateConnectionList.Filter?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val mFilter = remember { initial ?: VPNGateConnectionList.Filter() }
    var showTcp by remember { mutableStateOf(mFilter.isShowTCP) }
    var showUdp by remember { mutableStateOf(mFilter.isShowUDP) }
    var showL2tp by remember {
        mutableStateOf(mFilter.isShowL2TP && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
    }
    var showSstp by remember { mutableStateOf(mFilter.isShowSSTP) }
    var showSoftether by remember { mutableStateOf(mFilter.isShowSoftEther) }
    var pingText by remember { mutableStateOf(mFilter.ping?.toString() ?: "") }
    var pingOperator by remember { mutableStateOf(mFilter.pingFilterOperator) }
    var speedText by remember { mutableStateOf(mFilter.speed?.toString() ?: "") }
    var speedOperator by remember { mutableStateOf(mFilter.speedFilterOperator) }
    var sessionText by remember { mutableStateOf(mFilter.sessionCount?.toString() ?: "") }
    var sessionOperator by remember { mutableStateOf(mFilter.sessionCountFilterOperator) }
    val operatorLabels = listOf(
        stringResource(R.string.operator_equal),
        stringResource(R.string.operator_greater),
        stringResource(R.string.operator_greater_equal),
        stringResource(R.string.operator_less),
        stringResource(R.string.operator_less_equal),
    )
    val operators = VPNGateConnectionList.NumberFilterOperator.entries

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.filter),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.filter_server_protocol),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = showTcp, onClick = { showTcp = !showTcp }, label = {
                    Text(stringResource(R.string.show_tcp_server))
                })
                FilterChip(selected = showUdp, onClick = { showUdp = !showUdp }, label = {
                    Text(stringResource(R.string.show_udp_server))
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    FilterChip(selected = showL2tp, onClick = { showL2tp = !showL2tp }, label = {
                        Text(stringResource(R.string.show_l2tp_server))
                    })
                }
                FilterChip(selected = showSstp, onClick = { showSstp = !showSstp }, label = {
                    Text(stringResource(R.string.show_sstp_server))
                })
            }
            FilterChip(selected = showSoftether, onClick = { showSoftether = !showSoftether }, label = {
                Text(stringResource(R.string.show_softether_server))
            })
            NumericFilterRow(
                label = stringResource(R.string.ping),
                text = pingText,
                onText = { pingText = it },
                operator = pingOperator,
                operators = operators,
                operatorLabels = operatorLabels,
                onOperator = { pingOperator = it },
            )
            NumericFilterRow(
                label = stringResource(R.string.speed),
                text = speedText,
                onText = { speedText = it },
                operator = speedOperator,
                operators = operators,
                operatorLabels = operatorLabels,
                onOperator = { speedOperator = it },
            )
            NumericFilterRow(
                label = stringResource(R.string.session),
                text = sessionText,
                onText = { sessionText = it },
                operator = sessionOperator,
                operators = operators,
                operatorLabels = operatorLabels,
                onOperator = { sessionOperator = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.reset))
                }
                Button(onClick = {
                    if (!showTcp && !showUdp && !showL2tp && !showSstp && !showSoftether) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.must_check_at_least_1_protocol),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@Button
                    }
                    mFilter.isShowTCP = showTcp
                    mFilter.isShowUDP = showUdp
                    mFilter.isShowL2TP = showL2tp
                    mFilter.isShowSSTP = showSstp
                    mFilter.isShowSoftEther = showSoftether
                    mFilter.ping = pingText.toIntOrNull()
                    mFilter.pingFilterOperator = pingOperator
                    mFilter.speed = speedText.toIntOrNull()
                    mFilter.speedFilterOperator = speedOperator
                    mFilter.sessionCount = sessionText.toIntOrNull()
                    mFilter.sessionCountFilterOperator = sessionOperator
                    onApply(mFilter)
                }) {
                    Text(stringResource(R.string.apply))
                }
            }
        }
    }
}

@Composable
private fun NumericFilterRow(
    label: String,
    text: String,
    onText: (String) -> Unit,
    operator: VPNGateConnectionList.NumberFilterOperator,
    operators: List<VPNGateConnectionList.NumberFilterOperator>,
    operatorLabels: List<String>,
    onOperator: (VPNGateConnectionList.NumberFilterOperator) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            OperatorDropdown(
                value = operatorLabels[operators.indexOf(operator)],
                labels = operatorLabels,
                onSelect = { index -> onOperator(operators[index]) },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { input -> onText(input.filter { it.isDigit() }) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperatorDropdown(
    value: String,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.material3.ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = {},
            trailingIcon = {
                androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.menuAnchor(),
            singleLine = true,
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            labels.forEachIndexed { index, label ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}
