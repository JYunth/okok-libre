package app.okoklibre.ui.goals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.okoklibre.R
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.GoalEntity
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.formatWeightKg
import app.okoklibre.ui.common.shortDate
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.ListRow

/** The active person's finished goals, newest first. */
@Composable
fun GoalHistoryScreen(onBack: () -> Unit) {
    val vm: GoalsViewModel = appViewModel { GoalsViewModel(it) }
    val body by vm.body.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val state = body
    val goals = history

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppBar(stringResource(R.string.goal_history_title), onBack = onBack) },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when {
            state == null || goals == null -> Unit
            state.role == null -> EmptyState(stringResource(R.string.goal_no_role), null, modifier)
            goals.isEmpty() -> Column(modifier) {
                GoalOwner(state.role, Modifier.padding(horizontal = Gutter).padding(top = 8.dp))
                EmptyState(stringResource(R.string.goal_history_empty), null)
            }
            else -> LazyColumn(modifier.fillMaxSize()) {
                item {
                    GoalOwner(state.role, Modifier.padding(horizontal = Gutter).padding(top = 8.dp, bottom = 12.dp))
                }
                items(goals, key = { it.id }) { goal -> PastGoal(goal, state.unit) }
            }
        }
    }
}

@Composable
private fun PastGoal(goal: GoalEntity, unit: WeightUnit) {
    val start = shortDate(goal.startEpochDay)
    val end = goal.endEpochDay
    ListRow(
        title = stringResource(
            R.string.goal_start_to_target,
            formatWeightKg(goal.initWeightKg, unit, 1),
            formatWeightKg(goal.targetWeightKg, unit, 1),
            weightUnitLabel(unit),
        ),
        subtitle = if (end != null) {
            stringResource(R.string.goals_history_range, start, shortDate(end))
        } else {
            stringResource(R.string.goals_history_started, start)
        },
        value = stringResource(
            if (goal.endWeightKg != null) R.string.goals_history_reached else R.string.goals_history_ended,
        ),
    )
}
