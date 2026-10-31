package app.okoklibre.ui.trends

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.okoklibre.R
import app.okoklibre.algo.WeightUnit
import app.okoklibre.insights.BodyReport
import app.okoklibre.insights.BodyState
import app.okoklibre.insights.Consistency
import app.okoklibre.insights.DayPart
import app.okoklibre.insights.EnergyBalance
import app.okoklibre.insights.GoalState
import app.okoklibre.insights.Phase
import app.okoklibre.insights.PhaseKind
import app.okoklibre.insights.Rhythm
import app.okoklibre.insights.SmoothedDay
import app.okoklibre.insights.TrendFit
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.formatWeightKg
import app.okoklibre.ui.common.shortDate
import app.okoklibre.ui.common.signedWeight
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.ChangeChart
import app.okoklibre.ui.design.ChangeSeries
import app.okoklibre.ui.design.ChartDay
import app.okoklibre.ui.design.CompositionMap
import app.okoklibre.ui.design.DayGrid
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.KeyRow
import app.okoklibre.ui.design.OffsetBars
import app.okoklibre.ui.design.OptionRow
import app.okoklibre.ui.design.Section
import app.okoklibre.ui.design.SectionGap
import app.okoklibre.ui.design.SegmentStrip
import app.okoklibre.ui.design.Stat
import app.okoklibre.ui.design.Tissue
import app.okoklibre.ui.design.TrendChart
import app.okoklibre.ui.design.chartDays
import app.okoklibre.ui.theme.LocalAppColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.absoluteValue

/** Any metric over any range, then everything the history can tell. */
@Composable
fun TrendsScreen(nav: NavHostController) {
    val vm: TrendsViewModel = appViewModel { TrendsViewModel(it) }
    val body by vm.body.collectAsStateWithLifecycle()
    val metric by vm.metric.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val metricFit by vm.fit.collectAsStateWithLifecycle()
    val state = body ?: return
    val fit = metricFit?.takeIf { it.roleId == state.role?.id && it.metric == metric }?.fit

    if (state.report.weight == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            EmptyState(
                title = stringResource(R.string.trends_empty_title),
                body = stringResource(R.string.trends_empty_body),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Text(
                stringResource(R.string.tab_trends),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(horizontal = Gutter).padding(top = 20.dp, bottom = 12.dp),
            )
        }
        item {
            OptionRow(
                options = Metric.entries,
                selected = metric,
                label = { stringResource(it.label) },
                onSelect = vm::select,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Gutter - 4.dp),
            )
        }
        item { MetricView(state, metric, fit, range, vm::select) }
        analysisSections(state)
    }
}

@Composable
private fun metricUnit(metric: Metric, unit: WeightUnit): String = when (metric.kind) {
    MetricKind.WEIGHT -> weightUnitLabel(unit)
    MetricKind.PERCENT -> stringResource(R.string.unit_percent)
    else -> if (metric == Metric.BMR) stringResource(R.string.unit_kcal) else ""
}

private fun List<SmoothedDay>.inRange(range: ChartRange): List<SmoothedDay> {
    val days = range.days ?: return this
    val from = last().epochDay - days
    return filter { it.epochDay >= from }
}

@Composable
private fun MetricView(
    state: BodyState,
    metric: Metric,
    fit: TrendFit?,
    range: ChartRange,
    onRange: (ChartRange) -> Unit,
) {
    val colors = LocalAppColors.current
    val unit = state.unit
    Column(Modifier.padding(top = 16.dp)) {
        if (fit == null) {
            Text(
                stringResource(R.string.trends_metric_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.muted,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 40.dp),
            )
            return@Column
        }
        val visible = remember(fit, range) { fit.days.inRange(range) }
        val days = remember(visible) { chartDays(visible) }
        var scrubbed by remember(fit, range) { mutableStateOf<ChartDay?>(null) }
        val headline = scrubbed?.level ?: visible.last().level
        val delta = visible.last().level - visible.first().level
        val suffix = metricUnit(metric, unit)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(metric.format(headline, unit), style = MaterialTheme.typography.displayMedium)
            if (suffix.isNotEmpty()) {
                Text(
                    suffix,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.muted,
                    modifier = Modifier.padding(start = 6.dp, bottom = 9.dp),
                )
            }
        }
        Text(
            scrubbed?.let { shortDate(it.epochDay) }
                ?: stringResource(
                    R.string.trends_delta,
                    "${if (delta < 0) "−" else "+"}${metric.format(abs(delta), unit)}",
                    shortDate(visible.first().epochDay),
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = Gutter),
        )

        val forecast = state.report.pace?.goal
            ?.takeIf { metric == Metric.WEIGHT && it.state == GoalState.ON_COURSE }
            ?.let { goal -> goal.crossing?.let { forecastEnd(visible, it.epochDay, goal.targetKg) } }
        if (days.size >= 2) {
            TrendChart(
                days = days,
                valueLabel = { metric.format(it, unit) },
                dateLabel = ::shortDate,
                summary = stringResource(R.string.trends_chart_summary, stringResource(metric.label)),
                goal = state.goal?.targetWeightKg?.takeIf { forecast?.second == it },
                forecast = forecast,
                onScrub = { scrubbed = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 12.dp).height(236.dp),
            )
        }
        OptionRow(
            options = ChartRange.entries,
            selected = range,
            label = { stringResource(it.label) },
            onSelect = onRange,
            modifier = Modifier.padding(horizontal = Gutter - 4.dp).padding(top = 8.dp),
        )
        val low = visible.minBy { it.observed }
        val high = visible.maxBy { it.observed }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat(stringResource(R.string.trends_average), metric.format(visible.map { it.observed }.average(), unit))
            Stat(stringResource(R.string.trends_lowest, shortDate(low.epochDay)), metric.format(low.observed, unit))
            Stat(stringResource(R.string.trends_highest, shortDate(high.epochDay)), metric.format(high.observed, unit))
        }
    }
}

/**
 * Where to stop the dashed forecast: at the goal if it's near, otherwise along the same
 * line at a third of the visible span, so a distant date can't squash the real history.
 */
private fun forecastEnd(visible: List<SmoothedDay>, goalDay: Long, target: Double): Pair<Long, Double> {
    val last = visible.last()
    val reach = (last.epochDay - visible.first().epochDay) / 3
    if (goalDay - last.epochDay <= reach) return goalDay to target
    val fraction = reach.toDouble() / (goalDay - last.epochDay)
    return (last.epochDay + reach) to (last.level + (target - last.level) * fraction)
}

private fun LazyListScope.gap() = item { Spacer(Modifier.height(SectionGap)) }

private fun LazyListScope.analysisSections(state: BodyState) {
    val report = state.report
    val unit = state.unit
    if (report.muscle != null && report.fat != null && report.muscle.days.size >= 4) {
        gap()
        item { TissueChange(report, unit) }
    }
    if (report.path.size >= 4) {
        gap()
        item { BodyMap(report) }
    }
    if (report.phases.isNotEmpty() && (report.weight?.spanDays ?: 0) >= 21) {
        gap()
        item { Phases(report.phases, unit) }
    }
    report.energy?.let { energy ->
        gap()
        item { Energy(energy, report.latest?.bmrKcal ?: 0.0) }
    }
    report.rhythm?.let { rhythm ->
        gap()
        item { RhythmSection(rhythm, unit) }
    }
    report.consistency?.let { consistency ->
        gap()
        item { ConsistencySection(consistency, state.today) }
    }
}

private fun TrendFit.changeSince(from: Long): List<Pair<Long, Double>> {
    val base = dayAt(from).level
    return days.filter { it.epochDay >= from }.map { it.epochDay to it.level - base }
}

@Composable
private fun TissueChange(report: BodyReport, unit: WeightUnit) {
    val colors = LocalAppColors.current
    val muscle = report.muscle!!
    val fat = report.fat!!
    val from = maxOf(muscle.first.epochDay, muscle.last.epochDay - 120)
    val muscleSeries = remember(muscle, from) { muscle.changeSince(from) }
    val fatSeries = remember(fat, from) { fat.changeSince(from) }
    val muscleColor = Tissue.muscle(colors)
    val fatColor = Tissue.fat(colors)
    Section(
        title = stringResource(R.string.trends_tissue_title),
        caption = stringResource(R.string.trends_tissue_caption, shortDate(from)),
    ) {
        ChangeChart(
            series = listOf(
                ChangeSeries(fatColor, fatSeries),
                ChangeSeries(muscleColor, muscleSeries),
            ),
            dateLabel = ::shortDate,
            summary = stringResource(R.string.trends_tissue_title),
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
        Spacer(Modifier.height(8.dp))
        val label = weightUnitLabel(unit)
        KeyRow(muscleColor, stringResource(R.string.metric_muscle), "${signedWeight(muscleSeries.last().second, unit)} $label")
        KeyRow(fatColor, stringResource(R.string.metric_fat_weight), "${signedWeight(fatSeries.last().second, unit)} $label")
    }
}

@Composable
private fun BodyMap(report: BodyReport) {
    Section(
        title = stringResource(R.string.trends_map_title),
        caption = stringResource(R.string.trends_map_caption),
    ) {
        val points = remember(report.path) { report.path.map { it.ffmi to it.fmi } }
        val bmiText = stringResource(R.string.trends_map_bmi)
        CompositionMap(
            points = points,
            xAxis = stringResource(R.string.trends_map_x),
            yAxis = stringResource(R.string.trends_map_y),
            bmiLabel = { bmiText.format(it) },
            summary = stringResource(R.string.trends_map_title),
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
    }
}

@Composable
private fun phaseName(kind: PhaseKind): String = stringResource(
    when (kind) {
        PhaseKind.LOSING -> R.string.phase_losing
        PhaseKind.HOLDING -> R.string.phase_holding
        PhaseKind.GAINING -> R.string.phase_gaining
    },
)

@Composable
private fun Phases(phases: List<Phase>, unit: WeightUnit) {
    val colors = LocalAppColors.current
    Section(title = stringResource(R.string.trends_phases_title)) {
        SegmentStrip(
            phases.map { phase ->
                (phase.endEpochDay - phase.startEpochDay) to when (phase.kind) {
                    PhaseKind.LOSING -> colors.accent
                    PhaseKind.HOLDING -> colors.faint
                    PhaseKind.GAINING -> colors.ink
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        val label = weightUnitLabel(unit)
        phases.asReversed().take(5).forEach { phase ->
            val weeks = ((phase.endEpochDay - phase.startEpochDay) / 7).toInt().coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(phaseName(phase.kind), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${shortDate(phase.startEpochDay)} – ${shortDate(phase.endEpochDay)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${signedWeight(phase.deltaKg, unit)} $label", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        pluralStringResource(R.plurals.weeks, weeks, weeks),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun Energy(energy: EnergyBalance, bmr: Double) {
    val colors = LocalAppColors.current
    val kcal = energy.kcalPerDay
    Section(
        title = stringResource(R.string.trends_energy_title),
        caption = stringResource(
            if (energy.fromComposition) R.string.trends_energy_caption else R.string.trends_energy_caption_weight,
            energy.windowDays.toInt(),
        ),
    ) {
        Text(
            stringResource(
                when {
                    kcal.absoluteValue < 50 -> R.string.trends_energy_even
                    kcal < 0 -> R.string.trends_energy_deficit
                    else -> R.string.trends_energy_surplus
                },
                kcal.absoluteValue,
            ),
            style = MaterialTheme.typography.headlineMedium,
        )
        if (bmr > 0.0) {
            Text(
                stringResource(R.string.trends_energy_bmr, bmr.toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun partName(part: DayPart): String = stringResource(
    when (part) {
        DayPart.MORNING -> R.string.part_morning
        DayPart.MIDDAY -> R.string.part_midday
        DayPart.EVENING -> R.string.part_evening
        DayPart.NIGHT -> R.string.part_night
    },
)

@Composable
private fun RhythmSection(rhythm: Rhythm, unit: WeightUnit) {
    val colors = LocalAppColors.current
    val label = weightUnitLabel(unit)
    val heaviest = rhythm.heaviestDay
    Section(
        title = stringResource(R.string.trends_rhythm_title),
        caption = stringResource(R.string.trends_rhythm_caption),
    ) {
        if (rhythm.weekdays.size >= 4) {
            val byDay = rhythm.weekdays.associateBy { it.day }
            OffsetBars(
                values = DayOfWeek.entries.map { day ->
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()) to byDay[day]?.offsetKg
                },
                summary = stringResource(R.string.trends_rhythm_title),
            )
            if (heaviest != null && heaviest.offsetKg >= 0.1) {
                Text(
                    stringResource(
                        R.string.trends_rhythm_heaviest,
                        heaviest.day.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                        "${formatWeightKg(heaviest.offsetKg, unit, 1)} $label",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
            }
        }
        rhythm.dayParts.forEach { part ->
            KeyRow(colors.faint, partName(part.part), "${signedWeight(part.offsetKg, unit)} $label")
        }
    }
}

@Composable
private fun ConsistencySection(consistency: Consistency, today: LocalDate) {
    val colors = LocalAppColors.current
    val weeks = remember(consistency, today) { dayColumns(consistency.daysWithReadings, today) }
    val streak = consistency.streakWeeks
    Section(
        title = pluralStringResource(R.plurals.trends_streak, streak, streak),
        caption = pluralStringResource(
            R.plurals.trends_readings_30,
            consistency.readingsLast30Days,
            consistency.readingsLast30Days,
        ),
    ) {
        DayGrid(weeks, summary = stringResource(R.string.trends_grid_summary))
        Text(
            stringResource(R.string.trends_grid_caption),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private const val GRID_WEEKS = 26

/** Monday-first columns ending at today; days in the future are null. */
private fun dayColumns(days: Set<Long>, today: LocalDate): List<List<Boolean?>> {
    val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    return (GRID_WEEKS - 1 downTo 0).map { weeksAgo ->
        val monday = thisMonday.minusWeeks(weeksAgo.toLong())
        (0L until 7L).map { offset ->
            val day = monday.plusDays(offset)
            if (day.isAfter(today)) null else day.toEpochDay() in days
        }
    }
}
