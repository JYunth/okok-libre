package app.okoklibre.insights

import app.okoklibre.algo.BodyCompositionEngine
import app.okoklibre.algo.Grading
import app.okoklibre.algo.ScoreParts
import app.okoklibre.data.GoalEntity
import app.okoklibre.data.WeightRecordEntity
import app.okoklibre.data.computeGoalProgress
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// --- what a record is made of ---

/** A weight-only reading stores 0 for every composition field; those zeros are "unknown", not values. */
val WeightRecordEntity.hasComposition: Boolean get() = bodyFatPct > 0.0
val WeightRecordEntity.fatMassKg: Double get() = bodyFatPct * weightKg / 100.0
val WeightRecordEntity.muscleMassKg: Double get() = musclePct * weightKg / 100.0
val WeightRecordEntity.leanMassKg: Double get() = weightKg - fatMassKg

/** The local calendar day of a reading. */
fun WeightRecordEntity.epochDay(zone: ZoneId): Long =
    Instant.ofEpochSecond(timestampEpochSec).atZone(zone).toLocalDate().toEpochDay()

/** Daily means of [value], so several readings in a day count once. */
fun dailySeries(
    records: List<WeightRecordEntity>,
    zone: ZoneId,
    value: (WeightRecordEntity) -> Double,
): List<DayValue> = records
    .groupBy { it.epochDay(zone) }
    .map { (day, rs) -> DayValue(day, rs.map(value).average()) }
    .sortedBy { it.epochDay }

// --- answers ---

enum class Direction { UP, DOWN, STEADY }

/** "Am I gaining muscle?" */
data class MuscleAnswer(
    val windowDays: Long,
    val muscle: Change,
    val fat: Change,
    val lean: Change,
    val weight: Change,
    val muscleDirection: Direction,
    val fatDirection: Direction,
    /** Fat fell while muscle held or rose: the body changed even if the scale barely did. */
    val recomposition: Boolean,
    /** Of the weight lost, the share that was lean tissue; null unless weight really fell. */
    val leanShareOfLoss: Double?,
    val daysSinceComposition: Long,
)

enum class PaceZone { HOLDING, GENTLE, STEADY, FAST }

enum class GoalState { REACHED, ON_COURSE, STALLED, WRONG_WAY }

data class GoalForecast(
    val targetKg: Double,
    val remainingKg: Double,
    val progressFraction: Double,
    val state: GoalState,
    val crossing: Crossing?,
)

/** "Am I on pace?" */
data class PaceAnswer(
    val kgPerWeek: Double,
    val sdPerWeek: Double,
    val pctPerWeek: Double,
    val direction: Direction,
    val zone: PaceZone,
    val goal: GoalForecast?,
)

enum class HealthMetric { BMI, BODY_FAT, MUSCLE, WATER, VISCERAL_FAT, BONE, BMR, PROTEIN }

/** Which end of a metric's range is a concern. Bigger muscle is not a warning. */
enum class Polarity { LOWER_IS_CONCERN, HIGHER_IS_CONCERN, BOTH_ENDS }

data class MetricStatus(
    val metric: HealthMetric,
    val value: Double,
    val level: Grading.Level,
    val concern: Boolean,
    /** 0 in range, 1 slightly out, 2 clearly out. */
    val severity: Int,
)

enum class Lever { LOSE_FAT, GAIN_MUSCLE }

/** What one kilogram of change would do to the score, computed by the real score formula. */
data class ScoreLever(val lever: Lever, val points: Double)

/** "Am I healthy?" */
data class HealthAnswer(
    val checked: Int,
    val inRange: Int,
    val concerns: List<MetricStatus>,
    val all: List<MetricStatus>,
    val score: Int,
    val parts: ScoreParts?,
    val levers: List<ScoreLever>,
)

enum class ReadingFlag { GOOD, WEIGHT_ONLY, RUNNING_DRY, RUNNING_WET, BUILDING_BASELINE }

/** How far to trust today's composition numbers, judged against the person's own baseline. */
data class ReadingCheck(
    val flag: ReadingFlag,
    val waterVsUsualPct: Double?,
    /** Today's reading minus the estimated true level. */
    val weightVsTrendKg: Double?,
)

enum class DayPart { MORNING, MIDDAY, EVENING, NIGHT }

data class WeekdayOffset(val day: DayOfWeek, val offsetKg: Double, val days: Int)
data class DayPartOffset(val part: DayPart, val offsetKg: Double, val readings: Int)

/** Repeatable patterns left over once the trend is removed. */
data class Rhythm(val weekdays: List<WeekdayOffset>, val dayParts: List<DayPartOffset>) {
    val heaviestDay: WeekdayOffset? get() = weekdays.maxByOrNull { it.offsetKg }
    val lightestDay: WeekdayOffset? get() = weekdays.minByOrNull { it.offsetKg }
    val quietestPart: DayPartOffset? get() = dayParts.minByOrNull { abs(it.offsetKg) }
}

enum class PhaseKind { LOSING, HOLDING, GAINING }

/** A stretch of history with one consistent direction. */
data class Phase(
    val kind: PhaseKind,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val deltaKg: Double,
    val kgPerWeek: Double,
)

/** Daily energy surplus or deficit implied by the tissue actually gained or lost. */
data class EnergyBalance(val kcalPerDay: Int, val windowDays: Long, val fromComposition: Boolean)

/** One point of the fat-mass-index against fat-free-mass-index map. */
data class CompositionPoint(val epochDay: Long, val fmi: Double, val ffmi: Double)

data class Consistency(
    val daysWithReadings: Set<Long>,
    val streakWeeks: Int,
    val daysSinceLast: Long,
    val readingsLast30Days: Int,
)

/** Everything the app can honestly say about one person's history. */
data class BodyReport(
    val latest: WeightRecordEntity?,
    val weight: TrendFit?,
    val fat: TrendFit?,
    val muscle: TrendFit?,
    val lean: TrendFit?,
    val muscleAnswer: MuscleAnswer?,
    val pace: PaceAnswer?,
    /** The last reading is too old to say anything about current pace. */
    val paceStale: Boolean,
    val health: HealthAnswer?,
    val reading: ReadingCheck?,
    val rhythm: Rhythm?,
    val phases: List<Phase>,
    val energy: EnergyBalance?,
    val path: List<CompositionPoint>,
    val consistency: Consistency?,
) {
    companion object {
        val EMPTY = BodyReport(
            null, null, null, null, null, null, null, false, null, null, null,
            emptyList(), null, emptyList(), null,
        )
    }
}

/** Pure analysis over one person's readings. It reads the normative metrics and never changes them. */
object BodyAnalysis {

    private const val ANSWER_WINDOW_DAYS = 30L
    private const val MIN_ANSWER_SPAN_DAYS = 10L
    private const val MIN_COMPOSITION_DAYS = 4
    private const val MIN_PACE_DAYS = 5
    private const val MIN_PACE_SPAN_DAYS = 14L

    // A rate this old says nothing about what the body is doing now.
    private const val PACE_STALE_DAYS = 28L

    // Below these a kg of tissue can't be told from bioimpedance noise.
    private const val MIN_MUSCLE_CHANGE_KG = 0.2
    private const val MIN_FAT_CHANGE_KG = 0.2
    private const val MIN_WEIGHT_CHANGE_KG = 0.3

    // Pace bands as % of body weight per week; about 1% is the widely used ceiling for keeping lean mass.
    private const val PACE_HOLDING_PCT = 0.1
    private const val PACE_GENTLE_PCT = 0.5
    private const val PACE_STEADY_PCT = 1.0
    private const val SLOPE_CONFIDENCE_Z = 1.28

    // Hall et al.: stored energy density of fat and of fat-free tissue, in kcal per kg.
    private const val KCAL_PER_KG_FAT = 9400.0
    private const val KCAL_PER_KG_LEAN = 1816.0
    private const val KCAL_PER_KG_WEIGHT = 7700.0
    private const val MIN_ENERGY_WINDOW_DAYS = 14L

    private const val BASELINE_DAYS = 30L
    private const val BASELINE_MIN_READINGS = 5
    private const val HYDRATION_MIN_SHIFT_PCT = 1.0
    private const val HYDRATION_MAD_MULTIPLE = 3.0

    private const val RHYTHM_MIN_WEEKS = 3
    private const val RHYTHM_MIN_DAYPART_READINGS = 4

    private const val PHASE_MIN_DAYS = 10
    private const val PATH_MAX_POINTS = 16

    fun analyze(
        records: List<WeightRecordEntity>,
        goal: GoalEntity?,
        region: Grading.Region,
        today: LocalDate,
        zone: ZoneId,
    ): BodyReport {
        val live = records.filter { !it.deleted }.sortedBy { it.timestampEpochSec }
        val latest = live.lastOrNull() ?: return BodyReport.EMPTY
        val withComposition = live.filter { it.hasComposition }
        val weight = TrendModel.fit(dailySeries(live, zone) { it.weightKg })
        val fat = TrendModel.fit(dailySeries(withComposition, zone) { it.fatMassKg })
        val muscle = TrendModel.fit(dailySeries(withComposition, zone) { it.muscleMassKg })
        val lean = TrendModel.fit(dailySeries(withComposition, zone) { it.leanMassKg })
        val paceStale = weight != null && today.toEpochDay() - weight.last.epochDay > PACE_STALE_DAYS
        return BodyReport(
            latest = latest,
            weight = weight,
            fat = fat,
            muscle = muscle,
            lean = lean,
            muscleAnswer = muscleAnswer(weight, fat, muscle, lean, withComposition.size, today),
            pace = weight?.takeIf { !paceStale }?.let { pace(it, goal, latest.weightKg) },
            paceStale = paceStale,
            health = health(latest, region),
            reading = readingCheck(latest, live, weight, zone),
            rhythm = weight?.let { rhythm(it, live, zone) },
            phases = weight?.let(::phases).orEmpty(),
            energy = energyBalance(weight, fat, lean),
            path = compositionPath(fat, lean, latest.heightCm),
            consistency = consistency(live, today, zone),
        )
    }

    private fun direction(change: Change, floor: Double): Direction = when {
        !change.isReal(floor) -> Direction.STEADY
        change.delta > 0 -> Direction.UP
        else -> Direction.DOWN
    }

    fun muscleAnswer(
        weight: TrendFit?,
        fat: TrendFit?,
        muscle: TrendFit?,
        lean: TrendFit?,
        compositionReadings: Int,
        today: LocalDate,
    ): MuscleAnswer? {
        if (weight == null || fat == null || muscle == null || lean == null) return null
        if (compositionReadings < MIN_COMPOSITION_DAYS || muscle.spanDays < MIN_ANSWER_SPAN_DAYS) return null
        val end = muscle.last.epochDay
        val start = max(muscle.first.epochDay, end - ANSWER_WINDOW_DAYS)
        val muscleChange = muscle.change(start, end)
        val fatChange = fat.change(start, end)
        val leanChange = lean.change(start, end)
        val weightChange = weight.change(start, end)
        val muscleDirection = direction(muscleChange, MIN_MUSCLE_CHANGE_KG)
        val fatDirection = direction(fatChange, MIN_FAT_CHANGE_KG)
        val weightFell = weightChange.isReal(MIN_WEIGHT_CHANGE_KG) && weightChange.delta < 0
        return MuscleAnswer(
            windowDays = end - start,
            muscle = muscleChange,
            fat = fatChange,
            lean = leanChange,
            weight = weightChange,
            muscleDirection = muscleDirection,
            fatDirection = fatDirection,
            recomposition = fatDirection == Direction.DOWN && muscleDirection != Direction.DOWN,
            leanShareOfLoss = if (weightFell) (leanChange.delta / weightChange.delta).coerceIn(0.0, 1.0) else null,
            daysSinceComposition = today.toEpochDay() - end,
        )
    }

    fun pace(fit: TrendFit, goal: GoalEntity?, currentKg: Double): PaceAnswer? {
        if (fit.days.size < MIN_PACE_DAYS || fit.spanDays < MIN_PACE_SPAN_DAYS) return null
        val perWeek = fit.slopePerDay * 7.0
        val sdPerWeek = fit.slopeSd * 7.0
        val pct = perWeek / fit.level * 100.0
        val direction = when {
            abs(perWeek) < SLOPE_CONFIDENCE_Z * sdPerWeek || abs(pct) < PACE_HOLDING_PCT -> Direction.STEADY
            perWeek > 0 -> Direction.UP
            else -> Direction.DOWN
        }
        val zone = when {
            direction == Direction.STEADY -> PaceZone.HOLDING
            abs(pct) < PACE_GENTLE_PCT -> PaceZone.GENTLE
            abs(pct) <= PACE_STEADY_PCT -> PaceZone.STEADY
            else -> PaceZone.FAST
        }
        return PaceAnswer(
            kgPerWeek = perWeek,
            sdPerWeek = sdPerWeek,
            pctPerWeek = pct,
            direction = direction,
            zone = zone,
            goal = goal?.let { goalForecast(it, fit, currentKg, direction) },
        )
    }

    private fun goalForecast(goal: GoalEntity, fit: TrendFit, currentKg: Double, direction: Direction): GoalForecast {
        val progress = computeGoalProgress(goal, currentKg)
        val losing = goal.targetWeightKg < goal.initWeightKg
        val crossing = fit.crossing(goal.targetWeightKg)
        val state = when {
            progress.achieved -> GoalState.REACHED
            direction == Direction.STEADY -> GoalState.STALLED
            (direction == Direction.DOWN) == losing && crossing != null -> GoalState.ON_COURSE
            (direction == Direction.DOWN) == losing -> GoalState.STALLED
            else -> GoalState.WRONG_WAY
        }
        return GoalForecast(
            targetKg = goal.targetWeightKg,
            remainingKg = progress.remainingKg,
            progressFraction = progress.progressPct / 100.0,
            state = state,
            crossing = crossing.takeIf { state == GoalState.ON_COURSE },
        )
    }

    private fun polarity(metric: HealthMetric): Polarity = when (metric) {
        HealthMetric.MUSCLE, HealthMetric.WATER, HealthMetric.BONE, HealthMetric.BMR, HealthMetric.PROTEIN ->
            Polarity.LOWER_IS_CONCERN
        HealthMetric.VISCERAL_FAT -> Polarity.HIGHER_IS_CONCERN
        HealthMetric.BMI, HealthMetric.BODY_FAT -> Polarity.BOTH_ENDS
    }

    private fun isLow(level: Grading.Level) = level == Grading.Level.LOW || level == Grading.Level.THIN

    private fun status(metric: HealthMetric, value: Double, level: Grading.Level): MetricStatus {
        val concern = when {
            level == Grading.Level.STANDARD -> false
            polarity(metric) == Polarity.LOWER_IS_CONCERN -> isLow(level)
            polarity(metric) == Polarity.HIGHER_IS_CONCERN -> !isLow(level)
            else -> true
        }
        val severe = level == Grading.Level.OBESE || level == Grading.Level.VERY_HIGH
        return MetricStatus(metric, value, level, concern, if (!concern) 0 else if (severe) 2 else 1)
    }

    fun health(r: WeightRecordEntity, region: Grading.Region): HealthAnswer {
        val sex = r.sex
        val age = r.ageYears
        val height = r.heightCm
        val statuses = buildList {
            add(status(HealthMetric.BMI, r.bmi, Grading.bmiLevel(r.bmi, region)))
            if (r.hasComposition) {
                add(status(HealthMetric.BODY_FAT, r.bodyFatPct, Grading.bodyFatLevel(r.bodyFatPct, sex, age)))
                add(status(HealthMetric.MUSCLE, r.muscleMassKg, Grading.muscleWeightLevel(r.muscleMassKg, sex, height)))
                add(status(HealthMetric.WATER, r.waterPct, Grading.waterLevel(r.waterPct, sex, age)))
                if (r.visceralFat > 0.0) {
                    add(status(HealthMetric.VISCERAL_FAT, r.visceralFat, Grading.visceralFatLevel(r.visceralFat)))
                }
                if (r.boneKg > 0.0) add(status(HealthMetric.BONE, r.boneKg, Grading.boneMassLevel(r.boneKg, sex, age)))
                if (r.bmrKcal > 0.0) add(status(HealthMetric.BMR, r.bmrKcal, Grading.bmrLevel(r.bmrKcal, sex, age)))
            }
        }
        val concerns = statuses.filter { it.concern }.sortedByDescending { it.severity }
        val hasScore = r.hasComposition && r.score > 0
        return HealthAnswer(
            checked = statuses.size,
            inRange = statuses.size - concerns.size,
            concerns = concerns,
            all = statuses,
            score = r.score,
            parts = if (hasScore) scoreParts(r) else null,
            levers = if (hasScore) levers(r) else emptyList(),
        )
    }

    /** The score's terms as they were really computed: scale-computed records leave visceral out. */
    fun scoreParts(r: WeightRecordEntity): ScoreParts = BodyCompositionEngine.scoreParts(
        r.heightCm, r.weightKg, r.sex, r.ageYears, r.bodyFatPct, r.muscleMassKg, scoredVisceral(r),
    )

    private fun scoredVisceral(r: WeightRecordEntity): Double = if (r.impedanceOhm > 0.0) r.visceralFat else 0.0

    /** Re-runs the score formula with one kilogram moved, holding the visceral level still. */
    private fun levers(r: WeightRecordEntity): List<ScoreLever> {
        val base = scoreParts(r).raw
        fun rawWith(weightKg: Double, fatKg: Double, muscleKg: Double) = BodyCompositionEngine.scoreParts(
            r.heightCm, weightKg, r.sex, r.ageYears, fatKg / weightKg * 100.0, muscleKg, scoredVisceral(r),
        ).raw
        return listOf(
            ScoreLever(Lever.LOSE_FAT, rawWith(r.weightKg - 1.0, r.fatMassKg - 1.0, r.muscleMassKg) - base),
            ScoreLever(Lever.GAIN_MUSCLE, rawWith(r.weightKg + 1.0, r.fatMassKg, r.muscleMassKg + 1.0) - base),
        ).sortedByDescending { it.points }
    }

    /**
     * Bioimpedance reads a dry body as fatter. If today's water sits well outside this
     * person's own recent band, the fat and muscle figures deserve less trust.
     */
    fun readingCheck(
        latest: WeightRecordEntity,
        live: List<WeightRecordEntity>,
        weight: TrendFit?,
        zone: ZoneId,
    ): ReadingCheck {
        val vsTrend = weight?.let { latest.weightKg - it.dayAt(latest.epochDay(zone)).level }
        if (!latest.hasComposition) return ReadingCheck(ReadingFlag.WEIGHT_ONLY, null, vsTrend)
        val cutoff = latest.timestampEpochSec - BASELINE_DAYS * 86_400L
        val baseline = live
            .filter { it.hasComposition && it.timestampEpochSec in cutoff until latest.timestampEpochSec }
            .map { it.waterPct }
        if (baseline.size < BASELINE_MIN_READINGS) return ReadingCheck(ReadingFlag.BUILDING_BASELINE, null, vsTrend)
        val usual = median(baseline)
        val spread = median(baseline.map { abs(it - usual) })
        val shift = latest.waterPct - usual
        val limit = max(HYDRATION_MIN_SHIFT_PCT, HYDRATION_MAD_MULTIPLE * spread)
        val flag = when {
            shift <= -limit -> ReadingFlag.RUNNING_DRY
            shift >= limit -> ReadingFlag.RUNNING_WET
            else -> ReadingFlag.GOOD
        }
        return ReadingCheck(flag, shift, vsTrend)
    }

    private fun dayPart(hour: Int): DayPart = when (hour) {
        in 4..10 -> DayPart.MORNING
        in 11..15 -> DayPart.MIDDAY
        in 16..21 -> DayPart.EVENING
        else -> DayPart.NIGHT
    }

    fun rhythm(fit: TrendFit, live: List<WeightRecordEntity>, zone: ZoneId): Rhythm? {
        val residualByDay = fit.days.associate { it.epochDay to it.observed - it.level }
        val weekdays = residualByDay.entries
            .groupBy { LocalDate.ofEpochDay(it.key).dayOfWeek }
            .filterValues { it.size >= RHYTHM_MIN_WEEKS }
            .map { (day, rs) -> WeekdayOffset(day, rs.map { it.value }.average(), rs.size) }
            .sortedBy { it.day.value }
        val dayParts = live
            .groupBy { dayPart(Instant.ofEpochSecond(it.timestampEpochSec).atZone(zone).hour) }
            .filterValues { it.size >= RHYTHM_MIN_DAYPART_READINGS }
            .map { (part, rs) ->
                val offset = rs.map { it.weightKg - fit.dayAt(it.epochDay(zone)).level }.average()
                DayPartOffset(part, offset, rs.size)
            }
            .sortedBy { it.part.ordinal }
        if (weekdays.size < 4 && dayParts.size < 2) return null
        return Rhythm(weekdays, dayParts)
    }

    /** Runs of one direction, merged until each lasts long enough to be a real stretch of life. */
    fun phases(fit: TrendFit): List<Phase> {
        if (fit.days.size < 2) return emptyList()
        fun kindOf(day: SmoothedDay): PhaseKind {
            val pct = day.slopePerDay * 7.0 / day.level * 100.0
            return when {
                pct <= -PACE_HOLDING_PCT -> PhaseKind.LOSING
                pct >= PACE_HOLDING_PCT -> PhaseKind.GAINING
                else -> PhaseKind.HOLDING
            }
        }
        data class Run(val kind: PhaseKind, var from: Int, var to: Int)
        val runs = mutableListOf<Run>()
        fit.days.forEachIndexed { i, day ->
            val kind = kindOf(day)
            val current = runs.lastOrNull()
            if (current != null && current.kind == kind) current.to = i else runs += Run(kind, i, i)
        }
        fun length(r: Run) = fit.days[r.to].epochDay - fit.days[r.from].epochDay
        var i = 0
        while (i < runs.size && runs.size > 1) {
            val run = runs[i]
            if (length(run) >= PHASE_MIN_DAYS) {
                i++
                continue
            }
            if (i > 0) runs[i - 1].to = run.to else runs[1].from = run.from
            runs.removeAt(i)
            if (i > 0) {
                val previous = runs[i - 1]
                if (i < runs.size && runs[i].kind == previous.kind) {
                    previous.to = runs[i].to
                    runs.removeAt(i)
                }
                i = maxOf(i - 1, 0)
            }
        }
        return runs.map { run ->
            val start = fit.days[run.from]
            val end = fit.days[run.to]
            val span = max(end.epochDay - start.epochDay, 1L)
            val delta = end.level - start.level
            Phase(run.kind, start.epochDay, end.epochDay, delta, delta / span * 7.0)
        }
    }

    fun energyBalance(weight: TrendFit?, fat: TrendFit?, lean: TrendFit?): EnergyBalance? {
        if (weight == null) return null
        val composition = fat != null && lean != null &&
            lean.spanDays >= MIN_ENERGY_WINDOW_DAYS && lean.days.size >= MIN_COMPOSITION_DAYS
        val end = if (composition) lean!!.last.epochDay else weight.last.epochDay
        val start = max(if (composition) lean!!.first.epochDay else weight.first.epochDay, end - ANSWER_WINDOW_DAYS)
        val window = end - start
        if (window < MIN_ENERGY_WINDOW_DAYS) return null
        val stored = if (composition) {
            fat!!.change(start, end).delta * KCAL_PER_KG_FAT + lean!!.change(start, end).delta * KCAL_PER_KG_LEAN
        } else {
            weight.change(start, end).delta * KCAL_PER_KG_WEIGHT
        }
        return EnergyBalance(((stored / window) / 10.0).roundToInt() * 10, window, composition)
    }

    /**
     * Fat-mass and fat-free-mass indices (kg/m²), after Hattori's body composition chart.
     * Their sum is BMI, so one map shows weight, fat and lean moving at the same time.
     */
    fun compositionPath(fat: TrendFit?, lean: TrendFit?, heightCm: Double): List<CompositionPoint> {
        if (fat == null || lean == null || heightCm <= 0.0) return emptyList()
        val m2 = (heightCm / 100.0) * (heightCm / 100.0)
        val days = lean.days
        val picked = (0 until PATH_MAX_POINTS)
            .map { days[it * days.lastIndex / (PATH_MAX_POINTS - 1)] }
            .distinct()
        return picked.map { day ->
            CompositionPoint(day.epochDay, fat.dayAt(day.epochDay).level / m2, day.level / m2)
        }
    }

    fun consistency(live: List<WeightRecordEntity>, today: LocalDate, zone: ZoneId): Consistency? {
        if (live.isEmpty()) return null
        val days = live.mapTo(HashSet()) { it.epochDay(zone) }
        fun weekOf(day: LocalDate) = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeks = days.mapTo(HashSet()) { weekOf(LocalDate.ofEpochDay(it)) }
        var cursor = weekOf(today)
        if (cursor !in weeks) cursor = cursor.minusWeeks(1)
        var streak = 0
        while (cursor in weeks) {
            streak++
            cursor = cursor.minusWeeks(1)
        }
        val todayIndex = today.toEpochDay()
        return Consistency(
            daysWithReadings = days,
            streakWeeks = streak,
            daysSinceLast = max(0L, todayIndex - days.max()),
            readingsLast30Days = days.count { it > todayIndex - 30 },
        )
    }
}
