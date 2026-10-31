package app.okoklibre.ui.tmpweights

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.okoklibre.R
import app.okoklibre.algo.UnitConverter
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.RoleEntity
import app.okoklibre.data.TmpWeightEntity
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.formatRecordWeightKg
import app.okoklibre.ui.common.readingWhen
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.Avatar
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.ListRow
import app.okoklibre.ui.theme.LocalAppColors
import kotlinx.coroutines.flow.collectLatest

/** Offline records pulled from a scale, each waiting for a person. */
@Composable
fun TmpWeightsScreen(onBack: () -> Unit, onOpenRecord: (Long) -> Unit) {
    val vm: TmpWeightsViewModel = appViewModel { TmpWeightsViewModel(it) }
    val pending by vm.pending.collectAsStateWithLifecycle()
    val roles by vm.roles.collectAsStateWithLifecycle()
    val unit by vm.weightUnit.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    var claimingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val openText = stringResource(R.string.tmp_open)
    val goneText = stringResource(R.string.tmp_gone)

    LaunchedEffect(Unit) {
        // Latest wins: when claiming several in a row, a queue would lag behind the taps.
        vm.claims.collectLatest { outcome ->
            when (outcome) {
                is ClaimOutcome.Claimed -> {
                    val result = snackbars.showSnackbar(
                        context.getString(R.string.tmp_given_to, outcome.ownerName),
                        openText,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) onOpenRecord(outcome.recordId)
                }
                ClaimOutcome.Gone -> snackbars.showSnackbar(goneText)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppBar(title = null, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        val readings = pending
        when {
            readings == null -> Unit
            readings.isEmpty() -> EmptyState(
                stringResource(R.string.tmp_none_title),
                stringResource(R.string.tmp_none_body),
                Modifier.padding(padding),
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    Column(Modifier.padding(horizontal = Gutter).padding(bottom = 12.dp)) {
                        Text(
                            stringResource(R.string.tmp_waiting_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            stringResource(R.string.tmp_waiting_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                items(readings, key = { it.id }) { tmp ->
                    PendingRow(
                        tmp = tmp,
                        unit = unit,
                        onClaim = { claimingId = tmp.id },
                        onDelete = { deletingId = tmp.id },
                    )
                }
            }
        }
    }

    pending?.firstOrNull { it.id == claimingId }?.let { tmp ->
        WhoseSheet(
            tmp = tmp,
            unit = unit,
            roles = roles,
            onDismiss = { claimingId = null },
            onPick = { role ->
                claimingId = null
                vm.claim(tmp.id, role)
            },
        )
    }

    deletingId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.tmp_delete_title)) },
            text = { Text(stringResource(R.string.record_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingId = null
                    vm.delete(id)
                }) { Text(stringResource(R.string.delete), color = colors.attention) }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PendingRow(
    tmp: TmpWeightEntity,
    unit: WeightUnit,
    onClaim: () -> Unit,
    onDelete: () -> Unit,
) {
    ListRow(
        title = weightText(tmp, unit),
        subtitle = buildString {
            append(readingWhen(tmp.timestampEpochSec))
            if (tmp.fatPct > 0.0) {
                append(" · ")
                append(stringResource(R.string.tmp_fat, UnitConverter.formatWeight(tmp.fatPct, 1)))
            }
        },
        trailing = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    stringResource(R.string.delete),
                    tint = LocalAppColors.current.muted,
                )
            }
        },
        onClick = onClaim,
        padding = PaddingValues(start = Gutter, end = 8.dp, top = 10.dp, bottom = 10.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhoseSheet(
    tmp: TmpWeightEntity,
    unit: WeightUnit,
    roles: List<RoleEntity>,
    onDismiss: () -> Unit,
    onPick: (RoleEntity) -> Unit,
) {
    val colors = LocalAppColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.tmp_whose_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = Gutter),
            )
            Text(
                "${weightText(tmp, unit)} · ${readingWhen(tmp.timestampEpochSec)}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.padding(horizontal = Gutter).padding(top = 4.dp, bottom = 8.dp),
            )
            if (roles.isEmpty()) {
                Text(
                    stringResource(R.string.goal_no_role),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = Gutter).padding(vertical = 8.dp),
                )
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                roles.forEach { role ->
                    ListRow(
                        title = role.nickname,
                        leading = { Avatar(role, 32.dp) },
                        onClick = { onPick(role) },
                    )
                }
            }
        }
    }
}

@Composable
private fun weightText(tmp: TmpWeightEntity, unit: WeightUnit): String =
    "${formatRecordWeightKg(tmp.weightKg, unit, tmp.scaleProperty)} ${weightUnitLabel(unit)}"
