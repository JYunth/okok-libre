package app.okoklibre.data

import app.okoklibre.algo.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayAggregationTest {

    private fun record(
        id: Long,
        weightKg: Double,
        bmi: Double = 0.0,
        bodyFatPct: Double = 0.0,
        deleted: Boolean = false,
    ) = WeightRecordEntity(
        id = id,
        roleId = 1,
        weightKg = weightKg,
        timestampEpochSec = 1_700_000_000 + id * 60,
        bmi = bmi,
        bodyFatPct = bodyFatPct,
        boneKg = 0.0,
        musclePct = 0.0,
        waterPct = 0.0,
        bmrKcal = 0.0,
        bodyAge = 0,
        visceralFat = 0.0,
        score = 0,
        idealWeightKg = 70.0,
        heightCm = 175.0,
        sex = Sex.MALE,
        ageYears = 30,
        impedanceOhm = 0.0,
        scaleProperty = 0,
        deleted = deleted,
    )

    @Test
    fun `empty day yields no aggregate`() {
        assertNull(DayAggregation.compute(1, 20000, emptyList()))
    }

    @Test
    fun `day of only soft-deleted records yields no aggregate`() {
        assertNull(DayAggregation.compute(1, 20000, listOf(record(1, 80.0, deleted = true))))
    }

    @Test
    fun `fields are arithmetic means rounded to 2 decimals`() {
        val agg = DayAggregation.compute(
            1, 20000,
            listOf(
                record(1, 80.0, bmi = 25.0, bodyFatPct = 20.0),
                record(2, 81.0, bmi = 25.3, bodyFatPct = 20.4),
                record(3, 80.55, bmi = 25.15, bodyFatPct = 20.2),
            ),
        )!!

        assertEquals(80.52, agg.weightKg, 1e-9) // 241.55 / 3 = 80.5166.. -> HALF_UP 80.52
        assertEquals(25.15, agg.bmi, 1e-9)
        assertEquals(20.2, agg.bodyFatPct, 1e-9)
        assertEquals(1, agg.roleId)
        assertEquals(20000, agg.dateEpochDay)
    }

    @Test
    fun `zero values are excluded from means`() {
        val agg = DayAggregation.compute(
            1, 20000,
            listOf(
                record(1, 80.0, bodyFatPct = 20.0),
                record(2, 82.0, bodyFatPct = 0.0), // no impedance: fat unknown
            ),
        )!!

        assertEquals(81.0, agg.weightKg, 1e-9)
        assertEquals(20.0, agg.bodyFatPct, 1e-9)
        assertEquals(0.0, agg.bmi, 1e-9) // unknown on both records
    }

    @Test
    fun `soft-deleted records are excluded`() {
        val agg = DayAggregation.compute(
            1, 20000,
            listOf(
                record(1, 80.0),
                record(2, 90.0, deleted = true),
            ),
        )!!

        assertEquals(80.0, agg.weightKg, 1e-9)
    }
}
