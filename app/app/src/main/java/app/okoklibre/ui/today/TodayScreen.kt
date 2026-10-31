package app.okoklibre.ui.today

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.okoklibre.R
import app.okoklibre.data.RoleEntity
import app.okoklibre.insights.BodyState
import app.okoklibre.ui.AppState
import app.okoklibre.ui.Routes
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.formatRecordWeightKg
import app.okoklibre.ui.common.formatWeightKg
import app.okoklibre.ui.common.readingWhen
import app.okoklibre.ui.common.shortDate
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.Avatar
import app.okoklibre.ui.design.ChartDay
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.Hairline
import app.okoklibre.ui.design.RollingNumber
import app.okoklibre.ui.design.TrendChart
import app.okoklibre.ui.design.chartDays
import app.okoklibre.ui.theme.LocalAppColors
import kotlin.math.abs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodayViewModel(private val appState: AppState) : ViewModel() {
    val body: StateFlow<BodyState?> = appState.bodyData.state
    val roles: StateFlow<List<RoleEntity>> = appState.profileSession.roles
    val hasScale: StateFlow<Boolean> = appState.deviceRepository.devices.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val pending: StateFlow<Int> = appState.tmpWeightRepository.pending.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun switchTo(id: Long) {
        viewModelScope.launch { appState.profileSession.switchTo(id) }
    }
}

private const val HOME_CHART_DAYS = 45

/** Who, how much, and the three answers. */
@Composable
fun TodayScreen(nav: NavHostController) {
    val vm: TodayViewModel = appViewModel { TodayViewModel(it) }
    val body by vm.body.collectAsStateWithLifecycle()
    val roles by vm.roles.collectAsStateWithLifecycle()
    val hasScale by vm.hasScale.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val state = body ?: return
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 104.dp),
    ) {
        item {
            People(
                roles = roles,
                activeId = state.role?.id,
                onSwitch = vm::switchTo,
                onAdd = { nav.navigate(Routes.roleEdit()) },
            )
        }
        val latest = state.report.latest
        if (latest == null) {
            item {
                EmptyState(
                    title = stringResource(R.string.today_empty_title, state.role?.nickname.orEmpty()),
                    body = stringResource(
                        if (hasScale) R.string.today_empty_body else R.string.today_empty_body_no_scale,
                    ),
                    action = if (hasScale) null else stringResource(R.string.add_scale),
                    onAction = if (hasScale) null else ({ nav.navigate(Routes.DEVICE_BIND) }),
                )
            }
        } else {
            item { Hero(state, onOpen = { nav.navigate(Routes.reading(latest.id)) }) }
            readingNoteItem(state)
            item {
                AnswerList(state, onSetGoal = { nav.navigate(Routes.GOALS) })
            }
        }
        if (pending > 0) {
            item {
                Hairline(Modifier.padding(horizontal = Gutter))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { nav.navigate(Routes.TMP_WEIGHTS) }
                        .padding(horizontal = Gutter, vertical = 16.dp),
                ) {
                    Text(
                        pluralStringResource(R.plurals.today_pending, pending, pending),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.today_pending_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalAppColors.current.muted,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.readingNoteItem(state: BodyState) {
    item {
        readingNote(state.report.reading)?.let { note ->
            Column(Modifier.padding(horizontal = Gutter).padding(top = 8.dp, bottom = 16.dp)) {
                Text(
                    note.headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = LocalAppColors.current.attention,
                )
                Text(
                    note.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalAppColors.current.muted,
                )
            }
        }
    }
}

/** Everyone on this phone, one tap apart. The active person is the one with a name. */
@Composable
private fun People(
    roles: List<RoleEntity>,
    activeId: Long?,
    onSwitch: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = LocalAppColors.current
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(roles, key = { it.id }) { role ->
            val active = role.id == activeId
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Tab, onClickLabel = role.nickname) { onSwitch(role.id) }
                    .semantics { selected = active }
                    .padding(horizontal = 8.dp)
                    .animateContentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(role, 30.dp)
                if (active) {
                    Spacer(Modifier.width(10.dp))
                    Text(role.nickname, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
            }
        }
        item {
            IconButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, stringResource(R.string.add_person), tint = colors.muted)
            }
        }
    }
}

@Composable
private fun Hero(state: BodyState, onOpen: () -> Unit) {
    val colors = LocalAppColors.current
    val latest = state.report.latest ?: return
    val unit = state.unit
    var scrubbed by remember(state.role?.id) { mutableStateOf<ChartDay?>(null) }
    val fit = state.report.weight
    val shown = scrubbed

    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = Gutter, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (shown != null) {
                    Text(formatWeightKg(shown.observed, unit, 1), style = MaterialTheme.typography.displayLarge)
                } else {
                    RollingNumber(
                        text = formatRecordWeightKg(latest.weightKg, unit, latest.scaleProperty),
                        style = MaterialTheme.typography.displayLarge,
                    )
                }
                Text(
                    weightUnitLabel(unit),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.muted,
                    modifier = Modifier.padding(start = 6.dp, bottom = 14.dp),
                )
            }
            Text(
                if (shown != null) shortDate(shown.epochDay) else readingWhen(latest.timestampEpochSec),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        }
        if (fit != null) {
            val days = remember(fit) { chartDays(fit.days.takeLast(HOME_CHART_DAYS)) }
            val level = shown?.level ?: fit.level
            Text(
                stringResource(R.string.today_true_level, "${formatWeightKg(level, unit, 1)} ${weightUnitLabel(unit)}"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Gutter),
            )
            TrendChart(
                days = days,
                valueLabel = { formatWeightKg(it, unit, 1) },
                dateLabel = ::shortDate,
                summary = stringResource(R.string.today_chart_summary),
                goal = state.goal?.targetWeightKg?.takeIf { goal ->
                    abs(goal - fit.level) < (days.maxOf { it.high } - days.minOf { it.low }) * 1.5
                },
                onScrub = { scrubbed = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 8.dp).height(176.dp),
            )
        } else {
            Text(
                stringResource(R.string.today_trend_soon),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.padding(horizontal = Gutter).padding(top = 8.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun AnswerList(state: BodyState, onSetGoal: () -> Unit) {
    val report = state.report
    Column(Modifier.padding(top = 12.dp)) {
        AnswerRow(stringResource(R.string.answer_label_muscle), muscleAnswer(report.muscleAnswer, state.unit))
        Hairline(Modifier.padding(horizontal = Gutter))
        val goal = report.pace?.goal
        AnswerRow(
            stringResource(R.string.answer_label_pace),
            paceAnswer(report.pace, report.weight, state.unit, report.paceStale),
            onClick = onSetGoal,
        ) {
            if (goal != null) {
                LinearProgressIndicator(
                    progress = { goal.progressFraction.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(2.dp),
                    trackColor = LocalAppColors.current.hairline,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        Hairline(Modifier.padding(horizontal = Gutter))
        AnswerRow(stringResource(R.string.answer_label_health), healthAnswer(report.health))
    }
}

@Composable
private fun AnswerRow(
    label: String,
    answer: Answer,
    onClick: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Gutter, vertical = 16.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier.width(80.dp).padding(top = 2.dp, end = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(answer.headline, style = MaterialTheme.typography.titleLarge)
            Text(
                answer.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
            extra()
        }
    }
}
