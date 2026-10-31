package app.okoklibre.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.okoklibre.R
import app.okoklibre.algo.WeightUnit
import app.okoklibre.insights.Direction
import app.okoklibre.insights.GoalForecast
import app.okoklibre.insights.GoalState
import app.okoklibre.insights.HealthAnswer
import app.okoklibre.insights.HealthMetric
import app.okoklibre.insights.Lever
import app.okoklibre.insights.MuscleAnswer
import app.okoklibre.insights.PaceAnswer
import app.okoklibre.insights.PaceZone
import app.okoklibre.insights.ReadingCheck
import app.okoklibre.insights.ReadingFlag
import app.okoklibre.insights.TrendFit
import app.okoklibre.ui.common.formatWeightKg
import app.okoklibre.ui.common.shortDate
import app.okoklibre.ui.common.weightUnitLabel
import kotlin.math.abs
import kotlin.math.roundToInt

/** A headline and the sentence that backs it up. */
data class Answer(val headline: String, val detail: String)

@Composable
private fun kg(value: Double, unit: WeightUnit): String =
    "${formatWeightKg(abs(value), unit, 1)} ${weightUnitLabel(unit)}"

@Composable
fun muscleAnswer(answer: MuscleAnswer?, unit: WeightUnit): Answer {
    if (answer == null) {
        return Answer(stringResource(R.string.answer_need_data), stringResource(R.string.answer_muscle_need))
    }
    val days = answer.windowDays.toInt()
    val muscle = kg(answer.muscle.delta, unit)
    val fat = kg(answer.fat.delta, unit)
    val headline = when (answer.muscleDirection) {
        Direction.UP -> stringResource(R.string.answer_muscle_up, muscle, days)
        Direction.DOWN -> stringResource(R.string.answer_muscle_down, muscle, days)
        Direction.STEADY -> stringResource(R.string.answer_muscle_steady)
    }
    val share = answer.leanShareOfLoss
    val detail = when {
        answer.daysSinceComposition > 14 ->
            stringResource(R.string.answer_muscle_stale, answer.daysSinceComposition.toInt())
        answer.recomposition && answer.muscleDirection == Direction.UP ->
            stringResource(R.string.answer_recomp, fat)
        answer.recomposition -> stringResource(R.string.answer_fat_only, fat)
        share != null && share >= 0.3 -> stringResource(R.string.answer_lean_share, (share * 100).roundToInt())
        answer.fatDirection == Direction.DOWN -> stringResource(R.string.answer_fat_down, fat)
        answer.fatDirection == Direction.UP -> stringResource(R.string.answer_fat_up, fat)
        else -> stringResource(R.string.answer_fat_steady)
    }
    return Answer(headline, detail)
}

@Composable
private fun goalSentence(goal: GoalForecast, unit: WeightUnit): String {
    val target = kg(goal.targetKg, unit)
    return when (goal.state) {
        GoalState.REACHED -> stringResource(R.string.answer_goal_reached, target)
        GoalState.ON_COURSE -> {
            val crossing = goal.crossing!!
            val latest = crossing.latestEpochDay
            if (latest != null) {
                stringResource(
                    R.string.answer_goal_window, target, shortDate(crossing.earliestEpochDay), shortDate(latest),
                )
            } else {
                stringResource(R.string.answer_goal_around, target, shortDate(crossing.epochDay))
            }
        }
        GoalState.STALLED -> stringResource(R.string.answer_goal_stalled, target)
        GoalState.WRONG_WAY -> stringResource(R.string.answer_goal_wrong_way, target)
    }
}

@Composable
fun paceAnswer(pace: PaceAnswer?, trend: TrendFit?, unit: WeightUnit, stale: Boolean = false): Answer {
    if (stale) {
        return Answer(stringResource(R.string.answer_pace_stale), stringResource(R.string.answer_pace_stale_body))
    }
    if (pace == null) {
        return Answer(stringResource(R.string.answer_need_data), stringResource(R.string.answer_pace_need))
    }
    val rate = kg(pace.kgPerWeek, unit)
    val headline = when (pace.direction) {
        Direction.DOWN -> stringResource(R.string.answer_pace_losing, rate)
        Direction.UP -> stringResource(R.string.answer_pace_gaining, rate)
        Direction.STEADY -> stringResource(R.string.answer_pace_holding, kg(trend?.level ?: 0.0, unit))
    }
    val detail = when {
        pace.zone == PaceZone.FAST && pace.direction == Direction.DOWN ->
            stringResource(R.string.answer_pace_fast)
        pace.goal != null -> goalSentence(pace.goal, unit)
        else -> stringResource(R.string.answer_pace_no_goal)
    }
    return Answer(headline, detail)
}

@Composable
fun healthMetricName(metric: HealthMetric): String = stringResource(
    when (metric) {
        HealthMetric.BMI -> R.string.metric_bmi
        HealthMetric.BODY_FAT -> R.string.metric_body_fat
        HealthMetric.MUSCLE -> R.string.metric_muscle
        HealthMetric.WATER -> R.string.metric_water
        HealthMetric.VISCERAL_FAT -> R.string.metric_visceral_fat
        HealthMetric.BONE -> R.string.metric_bone
        HealthMetric.BMR -> R.string.metric_bmr
        HealthMetric.PROTEIN -> R.string.metric_protein
    },
)

@Composable
fun leverName(lever: Lever): String = stringResource(
    when (lever) {
        Lever.LOSE_FAT -> R.string.lever_lose_fat
        Lever.GAIN_MUSCLE -> R.string.lever_gain_muscle
    },
)

@Composable
fun healthAnswer(health: HealthAnswer?): Answer {
    if (health == null) {
        return Answer(stringResource(R.string.answer_need_data), stringResource(R.string.answer_health_need))
    }
    val headline = if (health.concerns.isEmpty()) {
        pluralStringResource(R.plurals.answer_all_in_range, health.checked, health.checked)
    } else {
        stringResource(R.string.answer_in_range, health.inRange, health.checked)
    }
    val concern = health.concerns.firstOrNull()
    val lever = health.levers.firstOrNull()?.takeIf { it.points >= 0.3 }
    val detail = when {
        health.checked == 1 -> stringResource(R.string.answer_health_weight_only)
        concern != null -> stringResource(R.string.answer_health_watch, healthMetricName(concern.metric))
        lever != null -> stringResource(
            R.string.answer_health_lever,
            leverName(lever.lever),
            formatPoints(lever.points),
        )
        else -> stringResource(R.string.answer_health_clear)
    }
    return Answer(headline, detail)
}

fun formatPoints(points: Double): String = "%.1f".format(points)

/** A note about today's reading, only when there's something to say. */
@Composable
fun readingNote(check: ReadingCheck?): Answer? = when (check?.flag) {
    ReadingFlag.RUNNING_DRY -> Answer(
        stringResource(R.string.note_dry_title),
        stringResource(R.string.note_dry_body, "%.1f".format(abs(check.waterVsUsualPct ?: 0.0))),
    )
    ReadingFlag.RUNNING_WET -> Answer(
        stringResource(R.string.note_wet_title),
        stringResource(R.string.note_wet_body, "%.1f".format(abs(check.waterVsUsualPct ?: 0.0))),
    )
    ReadingFlag.WEIGHT_ONLY -> Answer(
        stringResource(R.string.note_weight_only_title),
        stringResource(R.string.note_weight_only_body),
    )
    else -> null
}
