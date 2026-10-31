package app.okoklibre.algo

import org.junit.Assert.assertEquals
import org.junit.Test

/** Day/week/month rollups and role matching. */
class AggregatorTest {

    @Test
    fun meanExcludesZeros() {
        assertEquals(70.5, Aggregator.meanExcludingZeros(listOf(70.0, 0.0, 71.0)), 0.0)
        assertEquals(70.08, Aggregator.meanExcludingZeros(listOf(70.0, 70.1, 70.15)), 0.0) // round2 HALF_UP
        assertEquals(1.01, Aggregator.meanExcludingZeros(listOf(1.005)), 0.0)
        assertEquals(0.0, Aggregator.meanExcludingZeros(listOf(0.0, 0.0)), 0.0)
        assertEquals(0.0, Aggregator.meanExcludingZeros(emptyList()), 0.0)
        assertEquals(22.91, Aggregator.meanExcludingZeros(listOf(22.8, 0.0, 22.9, 23.02)), 0.0)
    }

    @Test
    fun dayAverage_allFields() {
        val morning = FieldAverages(
            weightKg = 70.0, bmi = 22.9, bodyFatPct = 18.4, boneMassKg = 2.9,
            muscleRatePct = 77.5, waterPct = 56.1, bmrKcal = 1617.6, bodyAge = 26.0, visceralFat = 7.0,
        )
        val evening = FieldAverages(
            weightKg = 71.0, bmi = 23.2, bodyFatPct = 0.0, boneMassKg = 0.0,
            muscleRatePct = 0.0, waterPct = 0.0, bmrKcal = 0.0, bodyAge = 0.0, visceralFat = 0.0,
        )
        val day = Aggregator.average(listOf(morning, evening))
        assertEquals(70.5, day.weightKg, 0.0)
        assertEquals(23.05, day.bmi, 0.0)
        // zero fields excluded: the day value is just the non-zero measurement
        assertEquals(18.4, day.bodyFatPct, 0.0)
        assertEquals(2.9, day.boneMassKg, 0.0)
        assertEquals(77.5, day.muscleRatePct, 0.0)
        assertEquals(56.1, day.waterPct, 0.0)
        assertEquals(1617.6, day.bmrKcal, 0.0)
        assertEquals(26.0, day.bodyAge, 0.0)
        assertEquals(7.0, day.visceralFat, 0.0)
    }

    @Test
    fun weekIsMeanOfDayRecords() {
        val d1 = FieldAverages(weightKg = 70.5, bodyFatPct = 18.4)
        val d2 = FieldAverages(weightKg = 70.1, bodyFatPct = 18.2)
        val d3 = FieldAverages(weightKg = 0.0, bodyFatPct = 18.6) // missing weight day
        val week = Aggregator.averageDays(listOf(d1, d2, d3))
        assertEquals(70.3, week.weightKg, 0.0) // (70.5+70.1)/2, zero excluded
        assertEquals(18.4, week.bodyFatPct, 0.0)
        // months use the same mean-of-day-records rule
        val month = Aggregator.averageDays(listOf(d1, d2))
        assertEquals(70.3, month.weightKg, 0.0)
    }

    @Test
    fun matchRole_withinPlusMinus3kg_orderedByCloseness() {
        val roles = linkedMapOf(1 to 70.0, 2 to 72.0, 3 to 80.0)
        assertEquals(listOf(2, 1), matchRole(roles, 71.5))
        // ±3 kg boundaries are inclusive
        assertEquals(listOf(1), matchRole(mapOf(1 to 70.0, 2 to 80.0), 73.0))
        assertEquals(listOf(1), matchRole(mapOf(1 to 70.0, 2 to 80.0), 67.0))
        assertEquals(emptyList<Int>(), matchRole(mapOf(1 to 70.0), 73.01))
        assertEquals(emptyList<Int>(), matchRole(emptyMap(), 70.0))
        // exact match first, ties keep map order
        assertEquals(listOf(2, 1, 3), matchRole(linkedMapOf(1 to 71.0, 2 to 70.0, 3 to 71.0), 70.0))
    }
}
