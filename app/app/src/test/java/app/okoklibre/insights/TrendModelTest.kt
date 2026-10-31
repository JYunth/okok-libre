package app.okoklibre.insights

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendModelTest {

    /** A body falling at [perDay] under Gaussian water noise, weighed on [days]. */
    private fun series(
        days: List<Long>,
        start: Double = 80.0,
        perDay: Double = -0.05,
        noiseSd: Double = 0.4,
        seed: Int = 7,
    ): List<DayValue> {
        val random = java.util.Random(seed.toLong())
        return days.map { DayValue(it, start + perDay * it + random.nextGaussian() * noiseSd) }
    }

    private val everyDay = (0L until 90L).toList()

    @Test
    fun needsThreeDays() {
        assertNull(TrendModel.fit(series(listOf(0, 1))))
        assertNotNull(TrendModel.fit(series(listOf(0, 1, 2))))
    }

    @Test
    fun recoversRateUnderDailyNoise() {
        val fit = TrendModel.fit(series(everyDay))!!
        assertEquals(-0.35, fit.slopePerDay * 7, 0.12)
        assertTrue("slope sd should be small with daily data", fit.slopeSd * 7 < 0.12)
    }

    @Test
    fun smoothedLevelBeatsTheRawReadings() {
        val data = series(everyDay)
        val fit = TrendModel.fit(data)!!
        fun truth(day: Long) = 80.0 - 0.05 * day
        val rawError = data.map { abs(it.value - truth(it.epochDay)) }.average()
        val smoothError = fit.days.map { abs(it.level - truth(it.epochDay)) }.average()
        assertTrue("smoothed $smoothError vs raw $rawError", smoothError < rawError / 2)
    }

    @Test
    fun estimatesNoiseFromTheData() {
        val fit = TrendModel.fit(series(everyDay, noiseSd = 0.4))!!
        assertEquals(0.4, fit.noiseSd, 0.12)
    }

    @Test
    fun copesWithGaps() {
        val sparse = listOf(0L, 1, 4, 5, 9, 16, 17, 24, 30, 38, 45, 46, 52, 60)
        val fit = TrendModel.fit(series(sparse))!!
        assertEquals(-0.35, fit.slopePerDay * 7, 0.2)
    }

    @Test
    fun uncertaintyGrowsAcrossAGap() {
        val days = (0L..20L).toList() + (61L..81L).toList()
        val fit = TrendModel.fit(series(days))!!
        val insideRun = fit.dayAt(10).levelSd
        val edgeOfGap = fit.dayAt(20).levelSd
        assertTrue(edgeOfGap > insideRun)
    }

    @Test
    fun oneWildReadingBarelyMovesTheTrend() {
        val clean = series(everyDay, noiseSd = 0.2)
        val spiked = clean.map { if (it.epochDay == 45L) it.copy(value = it.value + 6.0) else it }
        val a = TrendModel.fit(clean)!!
        val b = TrendModel.fit(spiked)!!
        assertTrue(abs(a.dayAt(45).level - b.dayAt(45).level) < 0.6)
        assertTrue(abs(a.level - b.level) < 0.2)
    }

    @Test
    fun aFlatBodyReadsAsFlat() {
        val fit = TrendModel.fit(series(everyDay, perDay = 0.0))!!
        assertTrue(abs(fit.slopePerDay * 7) < 0.15)
        assertFalse(fit.change(0).isReal(0.3))
    }

    @Test
    fun realChangeIsCalledReal() {
        val fit = TrendModel.fit(series(everyDay))!!
        val change = fit.change(30)
        assertEquals(-3.0, change.delta, 0.6)
        assertTrue(change.isReal(0.3))
    }

    @Test
    fun crossingProjectsAWindowAroundTheDate() {
        val fit = TrendModel.fit(series(everyDay))!!
        val crossing = fit.crossing(70.0)!!
        assertTrue(crossing.earliestEpochDay <= crossing.epochDay)
        val latest = crossing.latestEpochDay
        assertNotNull(latest)
        assertTrue(latest!! >= crossing.epochDay)
        assertEquals(200.0, crossing.epochDay.toDouble(), 60.0)
    }

    @Test
    fun noCrossingWhenMovingAway() {
        val fit = TrendModel.fit(series(everyDay))!!
        assertNull(fit.crossing(fit.level + 5.0))
    }

    @Test
    fun noCrossingWhenFlat() {
        val noiseless = (0L until 60L).map { DayValue(it, 75.0) }
        assertNull(TrendModel.fit(noiseless)!!.crossing(70.0))
    }

    @Test
    fun kcalScaleMetricsSettleOnTheirTrueLevel() {
        val flat = listOf(DayValue(0, 1560.0)) + (1L until 120L).map { DayValue(it, 1500.0) }
        val fit = TrendModel.fit(flat, unitsPerKg = 13.0)!!
        assertEquals(1500.0, fit.level, 8.0)
    }

    @Test
    fun unsortedInputIsHandled() {
        val data = series(everyDay).shuffled(Random(3))
        val fit = TrendModel.fit(data)!!
        assertEquals(0L, fit.first.epochDay)
        assertEquals(89L, fit.last.epochDay)
    }
}
