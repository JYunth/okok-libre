package app.okoklibre.algo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scale-computed (no-impedance) path with no-impedance
 * variants. Expected values computed independently from the spec formulas
 * (IEEE-754 doubles, BigDecimal HALF_UP on the decimal representation).
 */
class ScaleComputedPathTest {

    private fun scaleComputed(
        h: Double,
        wt: Double,
        sex: Sex,
        age: Int,
        fat: Double,
        water: Double,
        muscle: Double,
        bmr: Double,
        visceral: Double,
        bone: Double,
    ) = BodyCompositionEngine.fromScaleComputed(
        ScaleComputedInput(h, wt, sex, age, fat, water, muscle, bmr, visceral, bone),
    )

    // ---------- male 30, 170 cm / 65 kg, scale-computed block ----------

    @Test
    fun male30_scaleComputed() {
        val r = scaleComputed(170.0, 65.0, Sex.MALE, 30, 21.5, 55.0, 45.0, 1500.0, 9.0, 2.5)
        // Scale-sent fields are taken as-is.
        assertEquals(21.5, r.bodyFatPct, 0.0)
        assertEquals(55.0, r.waterPct, 0.0)
        assertEquals(45.0, r.muscleRatePct, 0.0)
        assertEquals(1500.0, r.bmrKcal, 0.0)
        assertEquals(9.0, r.visceralFat, 0.0)
        assertEquals(2.5, r.boneMassKg, 0.0)
        // Derived metrics.
        assertEquals(22.5, r.bmi, 0.0)
        assertEquals(13.975, r.fatMassKg, 1e-9)
        assertEquals(29.25, r.muscleMassKg, 1e-9) // musclePct·Wt/100
        assertEquals(63.0, r.idealWeightKg, 1e-9) // (170−80)·0.7
        assertEquals(3.1746031746031864, r.obesityDegreePct, 1e-9)
        assertEquals(51.03, r.leanBodyMassKg, 0.0) // round2(65 − 13.975)
        assertEquals(21, r.bodyAge) // round(−0.2387·170 + 0.2258·65 + 0.3452·30 + 1.2675·21.5 + 9.5081)
        assertEquals(18.9, r.proteinPct, 0.0) // round1(100 − 21.5 − 55 − 3/65·100), 1-dp HALF_UP
        assertEquals(84, r.bodyScore) // calScore(…, SLMkg=29.25, VFR=0)
        assertEquals(BodyShape.SLIGHTLY_OVERWEIGHT, r.bodyShape) // fatGrade 3 (21.5 ≥ 20), bmiGrade 2 → matrix 5
        // Control values reuse the impedance-path formulas with SLM = scale muscle mass.
        assertEquals(-2.0, r.weightControlKg, 1e-9)
        assertEquals(20.8, r.muscleControlKg, 1e-9)
        assertEquals(-1.3, r.fatControlKg, 1e-9)
        // No impedance: skeletal muscle stays 0, impedance-gated flags off.
        assertEquals(0.0, r.skeletalMuscleRatePct, 0.0)
        assertEquals(0.0, r.skeletalMuscleKg, 0.0)
        assertEquals(0.0, r.effectiveImpedanceOhm, 0.0)
        assertFalse(r.hasImpedanceMetrics)
        assertTrue(r.hasBodyComposition)
    }

    // ---------- female 45, 160 cm / 58 kg ----------

    @Test
    fun female45_scaleComputed() {
        val r = scaleComputed(160.0, 58.0, Sex.FEMALE, 45, 30.2, 48.5, 40.0, 1320.0, 7.0, 2.1)
        assertEquals(22.7, r.bmi, 0.0)
        assertEquals(30.2, r.bodyFatPct, 0.0)
        assertEquals(17.516, r.fatMassKg, 1e-9)
        assertEquals(23.2, r.muscleMassKg, 1e-9)
        assertEquals(54.0, r.idealWeightKg, 1e-9) // (160−70)·0.6
        assertEquals(7.4074074074074066, r.obesityDegreePct, 1e-9)
        assertEquals(40.48, r.leanBodyMassKg, 0.0)
        assertEquals(38, r.bodyAge)
        assertEquals(17.0, r.proteinPct, 0.0) // round1(100 − 30.2 − 48.5 − 2.5/58·100)
        assertEquals(84, r.bodyScore)
        assertEquals(BodyShape.SLIGHTLY_OVERWEIGHT, r.bodyShape) // fatGrade 3 (30.2 ≥ 28), bmiGrade 2 → matrix 5
        assertEquals(19.43, r.muscleControlKg, 1e-9)
        assertEquals(-2.5375, r.fatControlKg, 1e-9)
        assertTrue(r.hasBodyComposition)
    }

    // ---------- age gates ----------

    @Test
    fun minor_getsNoBodyAgeOrScore_butProteinHasNoAgeGate() {
        val r = scaleComputed(170.0, 65.0, Sex.MALE, 15, 21.5, 55.0, 45.0, 1500.0, 9.0, 2.5)
        assertEquals(0, r.bodyAge)
        // No age gate on the no-impedance protein branch.
        assertEquals(18.9, r.proteinPct, 0.0)
        assertEquals(0, r.bodyScore)
        // The scale-sent block is kept verbatim (the original stores it too).
        assertEquals(21.5, r.bodyFatPct, 0.0)
        assertEquals(9.0, r.visceralFat, 0.0)
        assertEquals(1500.0, r.bmrKcal, 0.0)
        assertTrue(r.hasBodyComposition)
    }

    @Test
    fun bodyAge_clampsToAgePlusMinusTen() {
        // Very high fat pushes the raw body age far above age+10.
        val r = scaleComputed(150.0, 120.0, Sex.FEMALE, 25, 45.0, 30.0, 30.0, 1800.0, 20.0, 4.0)
        assertEquals(35, r.bodyAge)
    }
}
