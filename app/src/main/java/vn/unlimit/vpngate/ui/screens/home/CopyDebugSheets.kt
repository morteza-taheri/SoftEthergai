package vn.unlimit.vpngate.ui.screens.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.repository.VpnServerRepository

/** Copy sheet: flag + IP title, copy IP / copy hostname / collector debug. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopySheet(
    connection: VPNGateConnection,
    onCopyIp: () -> Unit,
    onCopyHostname: () -> Unit,
    onDebugInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as vn.unlimit.vpngate.App
    val baseUrl = app.dataUtil?.baseUrl ?: "https://www.vpngate.net"
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                vn.unlimit.vpngate.ui.components.FlagImage(
                    url = "$baseUrl/images/flags/${connection.countryShort}.png",
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    connection.ip ?: "",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            OutlinedButton(onClick = onCopyIp, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.copy_ip))
            }
            OutlinedButton(onClick = onCopyHostname, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.copy_hostname))
            }
            TextButton(onClick = onDebugInfo, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.collector_debug_source))
            }
        }
    }
}

/** Collector debug sheet with dump/raw-HTML/raw-API tabs and copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSheet(
    hostname: String,
    payload: VpnServerRepository.DebugPayload?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var mode by remember { mutableIntStateOf(0) } // 0 dump, 1 raw html, 2 raw api
    var content by remember { mutableStateOf(payload?.dump ?: "") }
    val copiedMessage = stringResource(R.string.copied)
    val rawUnavailable = stringResource(R.string.collector_debug_raw_unavailable)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                hostname.ifEmpty { stringResource(R.string.collector_debug_title) },
                style = MaterialTheme.typography.titleLarge,
            )
            if (payload == null) {
                Text(
                    stringResource(R.string.collector_debug_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    vn.unlimit.vpngate.ui.components.FilterBadgeButton(
                        label = stringResource(R.string.collector_debug_dump),
                        selected = mode == 0,
                        onClick = {
                            mode = 0
                            content = payload.dump
                        },
                    )
                    vn.unlimit.vpngate.ui.components.FilterBadgeButton(
                        label = stringResource(R.string.collector_debug_raw_html),
                        selected = mode == 1,
                        onClick = {
                            val raw = payload.rawHtml
                            if (raw == null) {
                                Toast.makeText(context, rawUnavailable, Toast.LENGTH_SHORT).show()
                            } else {
                                mode = 1
                                content = raw
                            }
                        },
                    )
                    vn.unlimit.vpngate.ui.components.FilterBadgeButton(
                        label = stringResource(R.string.collector_debug_raw_api),
                        selected = mode == 2,
                        onClick = {
                            val raw = payload.rawApi
                            if (raw == null) {
                                Toast.makeText(context, rawUnavailable, Toast.LENGTH_SHORT).show()
                            } else {
                                mode = 2
                                content = raw
                            }
                        },
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(min = 160.dp, max = 420.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                content,
                                style = vn.unlimit.vpngate.ui.theme.MonospaceStyle,
                            )
                        }
                    }
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(content))
                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.copy_label))
                }
            }
        }
    }
}
