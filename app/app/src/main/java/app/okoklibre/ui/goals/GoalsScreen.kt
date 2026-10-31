package app.okoklibre.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.okoklibre.R
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.GoalEntity
import app.okoklibre.data.RoleEntity
import app.okoklibre.data.computeGoalProgress
import app.okoklibre.insights.BodyState
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.formatWeightKg
import app.okoklibre.ui.common.parseWeightToKg
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.Avatar
import app.okoklibre.ui.design.DestructiveAction
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.PrimaryButton
import app.okoklibre.ui.design.SectionGap
import app.okoklibre.ui.design.Stat
import app.okoklibre.ui.theme.LocalAppColors
import app.okoklibre.ui.today.paceAnswer
import kotlin.math.abs

/** Below this a target is just the weight they're already at, and the goal would finish instantly. */
private const val SAME_WEIGHT_KG = 0.05

@Composable
fun GoalsScreen(onHistory: () -> Unit, onBack: () -> Unit) {
    val vm: GoalsViewModel = appViewModel { GoalsViewModel(it) }
    val body by vm.body.collectAsStateWithLifecycle()
    var setting by rememberSaveable { mutableStateOf(false) }
    var confirmingEnd by rememberSaveable { mutableStateOf(false) }
    val state = body

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(stringResource(R.string.goals_title), onBack = onBack) {
                if (state?.role != null) {
                    IconButton(onClick = onHistory) {
                        Icon(Icons.Outlined.History, stringResource(R.string.goal_history_title))
                    }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when {
            state == null -> Unit
            state.role == null -> EmptyState(stringResource(R.string.goal_no_role), null, modifier)
            state.goal == null -> NoGoal(
                role = state.role,
                hasReading = state.report.latest != null,
                onSet = { setting = true },
                modifier = modifier,
            )
            else -> ActiveGoal(
                state = state,
                role = state.role,
                goal = state.goal,
                onFinish = vm::finishGoal,
                onEnd = { confirmingEnd = true },
                modifier = modifier,
            )
        }
    }

    val startKg = state?.report?.latest?.weightKg
    if (setting && state != null && startKg != null) {
        SetGoalSheet(
            startKg = startKg,
            unit = state.unit,
            onDismiss = { setting = false },
            onSet = { targetKg ->
                setting = false
                vm.startGoal(targetKg)
            },
        )
    }

    if (confirmingEnd) {
        AlertDialog(
            onDismissRequest = { confirmingEnd = false },
            title = { Text(stringResource(R.string.goals_end_title)) },
            text = { Text(stringResource(R.string.goals_end_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingEnd = false
                    vm.endGoal()
                }) { Text(stringResource(R.string.goals_end), color = LocalAppColors.current.attention) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingEnd = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Goals belong to one person, so these screens say whose they are. */
@Composable
internal fun GoalOwner(role: RoleEntity, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Avatar(role, 26.dp)
        Text(
            role.nickname,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** A goal starts from a real reading, so without one there's nothing to offer yet. */
@Composable
private fun NoGoal(role: RoleEntity, hasReading: Boolean, onSet: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GoalOwner(role, Modifier.padding(horizontal = Gutter).padding(top = 8.dp))
        EmptyState(
            title = stringResource(R.string.goals_empty_title),
            body = stringResource(if (hasReading) R.string.goals_empty_body else R.string.goals_empty_needs_reading),
            action = if (hasReading) stringResource(R.string.goals_set) else null,
            onAction = if (hasReading) onSet else null,
        )
    }
}

@Composable
private fun ActiveGoal(
    state: BodyState,
    role: RoleEntity,
    goal: GoalEntity,
    onFinish: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val unit = state.unit
    val report = state.report
    val latest = report.latest
    val label = weightUnitLabel(unit)
    fun kg(value: Double) = "${formatWeightKg(abs(value), unit, 1)} $label"
    // Worked out here, not read from report.pace.goal: pace needs two weeks of readings,
    // and a goal has progress, and can be reached, from the first day.
    val progress = computeGoalProgress(goal, latest?.weightKg)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
            GoalOwner(role, Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    formatWeightKg(goal.targetWeightKg, unit, 1),
                    style = MaterialTheme.typography.displayMedium,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.muted,
                    modifier = Modifier.padding(start = 6.dp, bottom = 9.dp),
                )
            }
            Text(
                stringResource(R.string.goal_target_weight),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
            LinearProgressIndicator(
                progress = { (progress.progressPct / 100.0).toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(2.dp),
                trackColor = colors.hairline,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Row(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Stat(stringResource(R.string.goal_init_weight), kg(goal.initWeightKg), Modifier.weight(1f))
                if (latest != null) {
                    Stat(stringResource(R.string.goals_latest), kg(latest.weightKg), Modifier.weight(1f))
                }
                if (!progress.achieved) {
                    Stat(stringResource(R.string.goals_to_go), kg(progress.remainingKg), Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(SectionGap))
            if (progress.achieved) {
                Text(
                    stringResource(R.string.goals_reached, kg(goal.targetWeightKg)),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(20.dp))
                PrimaryButton(stringResource(R.string.goals_finish), onClick = onFinish)
                Spacer(Modifier.height(24.dp))
            } else {
                val forecast = paceAnswer(report.pace, report.weight, unit, report.paceStale)
                Text(forecast.headline, style = MaterialTheme.typography.titleLarge)
                Text(
                    forecast.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (!progress.achieved) {
            DestructiveAction(stringResource(R.string.goals_end), onClick = onEnd)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetGoalSheet(startKg: Double, unit: WeightUnit, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    val colors = LocalAppColors.current
    val label = weightUnitLabel(unit)
    var text by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val targetKg = parseWeightToKg(text.replace(',', '.'), unit)
    val sameAsStart = targetKg != null && abs(targetKg - startKg) < SAME_WEIGHT_KG
    val problem = when {
        !attempted -> null
        targetKg == null -> stringResource(R.string.invalid_weight)
        sameAsStart -> stringResource(R.string.goals_same_as_start)
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.ground,
    ) {
        Column(
            Modifier.navigationBarsPadding().imePadding().padding(horizontal = Gutter).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.goals_set), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.goals_sheet_from, "${formatWeightKg(startKg, unit, 1)} $label"),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.goal_target_weight)) },
                suffix = { Text(label) },
                isError = problem != null,
                supportingText = if (problem != null) ({ Text(problem) }) else null,
                singleLine = true,
                // A number pad has no colon, and st:lb can't be typed without one.
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (unit == WeightUnit.ST_LB) KeyboardType.Text else KeyboardType.Decimal,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                stringResource(R.string.save),
                onClick = {
                    attempted = true
                    if (targetKg != null && !sameAsStart) onSet(targetKg)
                },
            )
        }
    }
}
