package app.okoklibre.insights

import app.okoklibre.algo.Grading
import app.okoklibre.algo.Sex
import app.okoklibre.data.GoalEntity
import app.okoklibre.data.WeightRecordEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyAnalysisTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val startDay = LocalDate.of(2026, 6, 1).toEpochDay()

    private fun ts(day: Long, hour: Int = 7) = (startDay + day) * 86_400L + hour * 3_600L

    private fun record(
        day: Long,
        weightKg: Double,
        fatPct: Double = 22.0,
        waterPct: Double = 54.0,
        musclePct: Double = 72.0,
        hour: Int = 7,
        score: Int = 75,
        visceralFat: Double = 8.0,
    ) = WeightRecordEntity(
        roleId = 1L, weightKg = weightKg, timestampEpochSec = ts(day, hour), bmi = weightKg / 3.0625,
        bodyFatPct = fatPct, boneKg = 3.0, musclePct = musclePct, waterPct = waterPct, bmrKcal = 1600.0,
        bodyAge = 30, visceralFat = visceralFat, score = score, idealWeightKg = 66.5, heightCm = 175.0,
        sex = Sex.MALE, ageYears = 30, impedanceOhm = 500.0, scaleProperty = 0,
    )

    private fun weightOnly(day: Long, weightKg: Double) = record(
        day, weightKg, fatPct = 0.0, waterPct = 0.0, musclePct = 0.0, score = 0, visceralFat = 0.0,
    ).copy(boneKg = 0.0, bmrKcal = 0.0, impedanceOhm = 0.0)

    /** Fat falls by [fatPerDay] kg while muscle rises by [musclePerDay] kg; small deterministic wobble. */
    private fun recomp(days: Int, fatPerDay: Double, musclePerDay: Double) = (0 until days).map { i ->
        val wobble = if (i % 2 == 0) 0.1 else -0.1
        val fat = 18.0 - fatPerDay * i
        val muscle = 56.0 + musclePerDay * i
        val weight = fat + muscle + 3.0 + wobble
        record(i.toLong(), weight, fatPct = fat / weight * 100, musclePct = muscle / weight * 100)
    }

    private fun analyze(records: List<WeightRecordEntity>, goal: GoalEntity? = null, today: Long? = null) =
        BodyAnalysis.analyze(
            records, goal, Grading.Region.OTHER,
            LocalDate.ofEpochDay(startDay + (today ?: records.maxOf { (it.timestampEpochSec / 86_400) - startDay })),
            zone,
        )

    @Test
    fun emptyHistoryGivesAnEmptyReport() {
        assertEquals(BodyReport.EMPTY, BodyAnalysis.analyze(emptyList(), null, Grading.Region.OTHER, LocalDate.now(), zone))
    }

    @Test
    fun muscleGainUnderFatLossIsRecomposition() {
        val answer = analyze(recomp(45, fatPerDay = 0.04, musclePerDay = 0.02)).muscleAnswer!!
        assertEquals(Direction.UP, answer.muscleDirection)
        assertEquals(Direction.DOWN, answer.fatDirection)
        assertTrue(answer.recomposition)
        assertEquals(30, answer.windowDays)
    }

    @Test
    fun steadyBodyClaimsNothing() {
        val answer = analyze(recomp(45, fatPerDay = 0.0, musclePerDay = 0.0)).muscleAnswer!!
        assertEquals(Direction.STEADY, answer.muscleDirection)
        assertEquals(Direction.STEADY, answer.fatDirection)
        assertFalse(answer.recomposition)
    }

    @Test
    fun tooLittleCompositionStaysQuiet() {
        assertNull(analyze(recomp(6, 0.04, 0.02)).muscleAnswer)
    }

    @Test
    fun leanShareNamesHowMuchOfTheLossWasNotFat() {
        val answer = analyze(recomp(45, fatPerDay = 0.03, musclePerDay = -0.03)).muscleAnswer!!
        assertEquals(0.5, answer.leanShareOfLoss!!, 0.2)
    }

    @Test
    fun weightOnlyReadingsNeverPolluteComposition() {
        val mixed = recomp(45, 0.0, 0.0).mapIndexed { i, r ->
            if (i % 5 == 0) weightOnly(i.toLong(), r.weightKg) else r
        }
        val report = analyze(mixed)
        assertEquals(Direction.STEADY, report.muscleAnswer!!.fatDirection)
        assertTrue(report.fat!!.level > 15.0)
    }

    @Test
    fun weightOnlyLatestReadingSaysSo() {
        val records = recomp(20, 0.0, 0.0) + weightOnly(20, 77.0)
        val report = analyze(records)
        assertEquals(ReadingFlag.WEIGHT_ONLY, report.reading!!.flag)
        assertEquals(1, report.health!!.checked)
    }

    @Test
    fun paceNeedsTwoWeeks() {
        assertNull(analyze((0..8).map { record(it.toLong(), 80.0) }).pace)
    }

    @Test
    fun paceBandsFollowPercentOfBodyWeight() {
        val fast = (0..40).map { record(it.toLong(), 90.0 - it * 0.2) }
        val answer = analyze(fast).pace!!
        assertEquals(Direction.DOWN, answer.direction)
        assertEquals(PaceZone.FAST, answer.zone)
        assertEquals(-1.4, answer.kgPerWeek, 0.15)
    }

    @Test
    fun flatWeightIsHolding() {
        val flat = (0..40).map { record(it.toLong(), if (it % 2 == 0) 80.1 else 79.9) }
        val answer = analyze(flat).pace!!
        assertEquals(Direction.STEADY, answer.direction)
        assertEquals(PaceZone.HOLDING, answer.zone)
    }

    private fun goal(init: Double, target: Double) =
        GoalEntity(roleId = 1, initWeightKg = init, targetWeightKg = target, startEpochDay = startDay)

    @Test
    fun onCourseGoalGetsAWindow() {
        val falling = (0..40).map { record(it.toLong(), 85.0 - it * 0.07) }
        val forecast = analyze(falling, goal(85.0, 78.0)).pace!!.goal!!
        assertEquals(GoalState.ON_COURSE, forecast.state)
        val crossing = forecast.crossing!!
        assertTrue(crossing.earliestEpochDay <= crossing.epochDay)
        assertEquals((startDay + 100).toDouble(), crossing.epochDay.toDouble(), 25.0)
    }

    @Test
    fun goalAwayFromTrendIsCalledOut() {
        val rising = (0..40).map { record(it.toLong(), 80.0 + it * 0.07) }
        val forecast = analyze(rising, goal(80.0, 75.0)).pace!!.goal!!
        assertEquals(GoalState.WRONG_WAY, forecast.state)
        assertNull(forecast.crossing)
    }

    @Test
    fun reachedGoalIsReached() {
        val falling = (0..40).map { record(it.toLong(), 80.0 - it * 0.07) }
        assertEquals(GoalState.REACHED, analyze(falling, goal(80.0, 78.0)).pace!!.goal!!.state)
    }

    @Test
    fun highMuscleIsNotAConcernButHighVisceralIs() {
        val strong = record(0, 80.0, musclePct = 80.0, visceralFat = 12.0)
        val health = BodyAnalysis.health(strong, Grading.Region.OTHER)
        val muscle = health.all.first { it.metric == HealthMetric.MUSCLE }
        assertEquals(Grading.Level.HIGH, muscle.level)
        assertFalse(muscle.concern)
        assertEquals(HealthMetric.VISCERAL_FAT, health.concerns.first { it.metric == HealthMetric.VISCERAL_FAT }.metric)
    }

    @Test
    fun leversComeFromTheRealScoreFormula() {
        val high = record(0, 95.0, fatPct = 30.0, musclePct = 62.0, visceralFat = 12.0)
        val levers = BodyAnalysis.health(high, Grading.Region.OTHER).levers
        assertEquals(2, levers.size)
        assertEquals(Lever.LOSE_FAT, levers.first().lever)
        assertTrue(levers.first().points > 0.0)
    }

    @Test
    fun scorePartsMatchTheStoredFormula() {
        val r = record(0, 80.0)
        val parts = BodyAnalysis.scoreParts(r)
        val rebuilt = parts.bmiWeight * parts.bmiScore + parts.fatWeight * parts.fatScore +
            parts.muscleWeight * parts.muscleScore + parts.visceralWeight * parts.visceralScore
        assertEquals(parts.raw, rebuilt, 1e-9)
    }

    @Test
    fun dryMorningIsFlagged() {
        val usual = (0..15).map { record(it.toLong(), 80.0, waterPct = 54.0 + (it % 3) * 0.1) }
        val report = analyze(usual + record(16, 80.0, waterPct = 51.5))
        assertEquals(ReadingFlag.RUNNING_DRY, report.reading!!.flag)
        assertEquals(-2.6, report.reading!!.waterVsUsualPct!!, 0.2)
    }

    @Test
    fun ordinaryMorningIsGood() {
        val usual = (0..15).map { record(it.toLong(), 80.0, waterPct = 54.0 + (it % 3) * 0.1) }
        assertEquals(ReadingFlag.GOOD, analyze(usual).reading!!.flag)
    }

    @Test
    fun earlyDaysSayBaselineIsBuilding() {
        assertEquals(ReadingFlag.BUILDING_BASELINE, analyze(recomp(3, 0.0, 0.0)).reading!!.flag)
    }

    @Test
    fun rhythmFindsTheWeekendBump() {
        val records = (0..56).map { i ->
            val weekday = LocalDate.ofEpochDay(startDay + i).dayOfWeek
            val bump = if (weekday == DayOfWeek.MONDAY) 0.8 else if (weekday == DayOfWeek.FRIDAY) -0.3 else 0.0
            record(i.toLong(), 80.0 + bump)
        }
        val rhythm = analyze(records).rhythm!!
        assertEquals(DayOfWeek.MONDAY, rhythm.heaviestDay!!.day)
        assertTrue(rhythm.heaviestDay!!.offsetKg > 0.3)
        assertEquals(DayOfWeek.FRIDAY, rhythm.lightestDay!!.day)
    }

    @Test
    fun rhythmUsesLocalHoursNotUtc() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        // 01:30 UTC is 07:00 in Kolkata: a morning reading the old UTC bucketing called night.
        val records = (0..20).map { record(it.toLong(), 80.0, hour = 1).let { r -> r.copy(timestampEpochSec = r.timestampEpochSec + 1800) } }
        val fit = TrendModel.fit(dailySeries(records, kolkata) { it.weightKg })!!
        val rhythm = BodyAnalysis.rhythm(fit, records, kolkata)!!
        assertEquals(listOf(DayPart.MORNING), rhythm.dayParts.map { it.part })
    }

    @Test
    fun phasesSplitAtTheTurn() {
        val records = (0..60).map { record(it.toLong(), 85.0 - it * 0.1) } +
            (61..120).map { record(it.toLong(), 79.0) }
        val phases = analyze(records).phases
        assertEquals(PhaseKind.LOSING, phases.first().kind)
        assertEquals(PhaseKind.HOLDING, phases.last().kind)
        assertTrue(phases.first().deltaKg < -3.0)
    }

    @Test
    fun phasesCoverTheWholeHistoryWithoutGaps() {
        val records = (0..120).map { i ->
            record(i.toLong(), 80.0 + 2.0 * kotlin.math.sin(i / 19.0))
        }
        val phases = analyze(records).phases
        assertEquals(startDay, phases.first().startEpochDay)
        assertEquals(startDay + 120, phases.last().endEpochDay)
        phases.forEach { assertTrue(it.endEpochDay > it.startEpochDay) }
    }

    @Test
    fun energyBalanceWeighsFatAndLeanDifferently() {
        val balance = analyze(recomp(45, fatPerDay = 0.05, musclePerDay = 0.0)).energy!!
        assertTrue(balance.fromComposition)
        assertEquals(-470.0, balance.kcalPerDay.toDouble(), 70.0)
    }

    @Test
    fun energyBalanceFallsBackToWeight() {
        val records = (0..40).map { weightOnly(it.toLong(), 85.0 - it * 0.05) }
        val balance = analyze(records).energy!!
        assertFalse(balance.fromComposition)
        assertEquals(-385.0, balance.kcalPerDay.toDouble(), 60.0)
    }

    @Test
    fun compositionPathIndicesAddUpToBmi() {
        val records = recomp(60, 0.03, 0.01)
        val report = analyze(records)
        val end = report.path.last()
        val weight = report.fat!!.level + report.lean!!.level
        assertEquals(weight / 3.0625, end.fmi + end.ffmi, 0.01)
        assertTrue(report.path.size in 2..17)
        assertEquals(report.lean!!.last.epochDay, end.epochDay)
    }

    @Test
    fun streakCountsLocalWeeksAndSurvivesAnEmptyCurrentWeek() {
        val records = listOf(0L, 7L, 14L, 21L).map { record(it, 80.0) }
        val consistency = analyze(records, today = 23)
        assertEquals(4, consistency.consistency!!.streakWeeks)
        assertEquals(2, consistency.consistency!!.daysSinceLast)
    }

    @Test
    fun softDeletedReadingsAreInvisible() {
        val records = recomp(30, 0.0, 0.0) + record(30, 200.0).copy(deleted = true)
        val report = analyze(records)
        assertTrue(report.latest!!.weightKg < 100.0)
    }

    @Test
    fun attributionRefusesToReadZeroAsFat() {
        val before = weightOnly(0, 80.0)
        val after = record(1, 80.0)
        val attribution = InsightEngine.changeAttribution(before, after)
        assertEquals(AttributionCause.WEIGHT_ONLY, attribution.cause)
        assertNull(attribution.deltaFatPct)
        assertNull(InsightEngine.scoreAttribution(before, after))
    }

    @Test
    fun attributionNamesHydrationFirst() {
        val attribution = InsightEngine.changeAttribution(record(0, 80.0, waterPct = 54.0), record(1, 79.0, waterPct = 52.0))
        assertEquals(AttributionCause.HYDRATION_NOISE, attribution.cause)
    }

    @Test
    fun oldHistoryGivesNoPaceAndNoForecast() {
        val falling = (0..40).map { record(it.toLong(), 85.0 - it * 0.07) }
        val report = analyze(falling, goal(85.0, 78.0), today = 120)
        assertNull(report.pace)
        assertTrue(report.paceStale)
    }

    @Test
    fun scaleComputedScoresLeaveVisceralOut() {
        val scaleComputed = record(0, 80.0, visceralFat = 12.0).copy(impedanceOhm = 0.0)
        assertFalse(BodyAnalysis.scoreParts(scaleComputed).countsVisceral)
        assertTrue(BodyAnalysis.scoreParts(record(0, 80.0, visceralFat = 12.0)).countsVisceral)
    }

    @Test
    fun aHugeJumpIsNeverBlamedOnWater() {
        val attribution = InsightEngine.changeAttribution(record(0, 52.0, waterPct = 50.0), record(1, 72.0, waterPct = 54.0))
        assertEquals(AttributionCause.UNCLEAR, attribution.cause)
    }

    @Test
    fun scoreMovePinsTheTermThatMovedIt() {
        val before = record(0, 80.0, fatPct = 26.0, score = 70)
        val after = record(1, 80.0, fatPct = 22.0, score = 76)
        val attribution = InsightEngine.scoreAttribution(before, after)
        assertNotNull(attribution)
        assertEquals(ScoreComponent.FAT, attribution!!.component)
        assertEquals(6, attribution.deltaScore)
    }
}
