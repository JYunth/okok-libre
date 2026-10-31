package app.okoklibre.ui.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.okoklibre.R
import app.okoklibre.ble.protocol.ScaleProtocolType
import app.okoklibre.data.DeviceRepository
import app.okoklibre.data.ScaleDeviceEntity
import app.okoklibre.ui.AppState
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.readingWhen
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.DestructiveAction
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.ListRow
import app.okoklibre.ui.theme.LocalAppColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DevicesViewModel(private val appState: AppState) : ViewModel() {
    /** Null until the first read, so the empty state doesn't flash. */
    val devices: StateFlow<List<ScaleDeviceEntity>?> = appState.deviceRepository.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun unbind(mac: String) {
        viewModelScope.launch { appState.deviceRepository.remove(mac) }
    }
}

@Composable
fun DevicesScreen(onAdd: () -> Unit, onBack: () -> Unit) {
    val viewModel: DevicesViewModel = appViewModel { DevicesViewModel(it) }
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    var expandedMac by rememberSaveable { mutableStateOf<String?>(null) }
    var removingMac by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppBar(stringResource(R.string.devices_title), onBack) },
    ) { padding ->
        val scales = devices
        when {
            scales == null -> Unit
            scales.isEmpty() -> EmptyState(
                stringResource(R.string.devices_empty_title),
                stringResource(R.string.devices_empty_body),
                Modifier.padding(padding),
                action = stringResource(R.string.devices_add_scale),
                onAction = onAdd,
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(scales, key = { it.mac }) { device ->
                    DeviceRow(
                        device = device,
                        expanded = device.mac == expandedMac,
                        onToggle = { expandedMac = device.mac.takeIf { it != expandedMac } },
                        onRemove = { removingMac = device.mac },
                    )
                }
                item(key = "add") {
                    ListRow(
                        title = stringResource(R.string.devices_add_scale),
                        leading = { Icon(Icons.Outlined.Add, null, tint = colors.muted) },
                        onClick = onAdd,
                    )
                }
            }
        }
    }

    devices?.firstOrNull { it.mac == removingMac }?.let { device ->
        AlertDialog(
            onDismissRequest = { removingMac = null },
            title = { Text(stringResource(R.string.devices_remove_title)) },
            text = { Text(stringResource(R.string.devices_unbind_body, device.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unbind(device.mac)
                    removingMac = null
                    expandedMac = null
                }) { Text(stringResource(R.string.devices_remove), color = colors.attention) }
            },
            dismissButton = {
                TextButton(onClick = { removingMac = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Plain words on the row. The address and removal wait behind a tap, away from a stray thumb. */
@Composable
private fun DeviceRow(
    device: ScaleDeviceEntity,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    // readingWhen starts a sentence; here it sits mid-line.
    val seen = readingWhen(device.lastSeenEpochSec).replaceFirstChar { it.lowercase() }
    Column {
        ListRow(
            title = device.name,
            subtitle = stringResource(R.string.devices_row_summary, deviceTypeLabel(device.typeId), seen),
            onClick = onToggle,
        )
        AnimatedVisibility(expanded) {
            Column {
                Text(
                    stringResource(
                        R.string.devices_detail,
                        device.mac,
                        protocolLabel(device.protocolType),
                        device.protocolVersion,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalAppColors.current.muted,
                    modifier = Modifier.padding(horizontal = Gutter),
                )
                DestructiveAction(stringResource(R.string.devices_remove), onClick = onRemove)
            }
        }
    }
}

@Composable
private fun protocolLabel(type: ScaleProtocolType): String = stringResource(
    when (type) {
        ScaleProtocolType.OKOK_CLASSIC -> R.string.devices_protocol_classic
        ScaleProtocolType.OKOK_CLOUD -> R.string.devices_protocol_cloud
        ScaleProtocolType.OKOK_CLOUD_V4 -> R.string.devices_protocol_cloud_v4
    },
)

/** Fat and impedance scales read the same to a person: either way the app shows composition. */
@Composable
private fun deviceTypeLabel(typeId: Int): String = stringResource(
    if (typeId == DeviceRepository.TYPE_WEIGHER) R.string.devices_kind_weight else R.string.devices_kind_body,
)
