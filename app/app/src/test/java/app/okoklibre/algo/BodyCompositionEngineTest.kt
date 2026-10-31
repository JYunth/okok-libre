package app.okoklibre.algo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Expected values in this file were computed independently from the formulas
 * (IEEE-754 double arithmetic, BigDecimal HALF_UP rounding on the decimal representation).
 */
class BodyCompositionEngineTest {

    private fun engine(
        h: Double,
        wt: Double,
        sex: Sex,
        age: Int,
        z: Double,
        prevZ: Double? = null,
        dtMin: Long? = null,
    ) = BodyCompositionEngine.compute(CompositionInput(h, wt, sex, age, z, prevZ, dtMin))

    // ---------- full pipeline: male 30 ----------

    @Test
    fun male30_fullPipeline() {
        val r = engine(175.0, 70.0, Sex.MALE, 30, 500.0)
        assertEquals(22.9, r.bmi, 0.0)
        assertEquals(18.36, r.bodyFatPct, 0.0)
        assertEquals(12.852, r.fatMassKg, 1e-9)
        assertEquals(7.0, r.visceralFat, 0.0)
        assertEquals(56.11, r.waterPct, 0.0)
        assertEquals(54.26, r.muscleMassKg, 0.0)
        assertEquals(77.52, r.muscleRatePct, 0.0)
        assertEquals(43.5, r.skeletalMuscleRatePct, 0.0)
        assertEquals(30.45, r.skeletalMuscleKg, 1e-9)
        assertEquals(2.888, r.boneMassKg, 1e-9)
        assertEquals(1617.6, r.bmrKcal, 0.0)
        assertEquals(26, r.bodyAge)
        assertEquals(66.5, r.idealWeightKg, 1e-9)
        assertEquals(5.263157894736842, r.obesityDegreePct, 1e-9)
        assertEquals(57.15, r.leanBodyMassKg, 0.0)
        assertEquals(21.4, r.proteinPct, 0.0)
        assertEquals(79, r.bodyScore)
        assertEquals(BodyShape.HEALTHY, r.bodyShape) // fg2 [15,20) / bg2 -> type 4
        assertEquals(-3.5, r.weightControlKg, 1e-9)
        assertEquals(-0.35999999999999943, r.muscleControlKg, 1e-9)
        assertEquals(-2.8305882352941185, r.fatControlKg, 1e-9)
        assertEquals(500.0, r.effectiveImpedanceOhm, 0.0)
    }

    // ---------- full pipeline: female 28 ----------

    @Test
    fun female28_fullPipeline() {
        val r = engine(160.0, 55.0, Sex.FEMALE, 28, 600.0)
        assertEquals(21.5, r.bmi, 0.0)
        assertEquals(28.32, r.bodyFatPct, 0.0)
        assertEquals(15.576, r.fatMassKg, 1e-9)
        assertEquals(3.5, r.visceralFat, 0.0) // 3.6397 -> half-step round-up to 3.5
        assertEquals(50.26, r.waterPct, 0.0)
        assertEquals(36.66, r.muscleMassKg, 0.0)
        assertEquals(66.66, r.muscleRatePct, 0.0)
        assertEquals(36.4, r.skeletalMuscleRatePct, 0.0)
        assertEquals(20.02, r.skeletalMuscleKg, 1e-9)
        assertEquals(2.764, r.boneMassKg, 1e-9)
        assertEquals(1184.1, r.bmrKcal, 0.0)
        assertEquals(29, r.bodyAge)
        assertEquals(54.0, r.idealWeightKg, 1e-9)
        assertEquals(1.8518518518518516, r.obesityDegreePct, 1e-9)
        assertEquals(39.42, r.leanBodyMassKg, 0.0)
        assertEquals(16.4, r.proteinPct, 0.0)
        assertEquals(87, r.bodyScore)
        assertEquals(BodyShape.SLIGHTLY_OVERWEIGHT, r.bodyShape) // fg3 [28,∞) / bg2 -> type 5
        assertEquals(-1.0, r.weightControlKg, 1e-9)
        assertEquals(3.7650000000000006, r.muscleControlKg, 1e-9)
        assertEquals(-4.778749999999998, r.fatControlKg, 1e-9)
    }

    // ---------- age bands ----------

    @Test
    fun male45_visceralHalfStepRoundUp() {
        val r = engine(180.0, 90.0, Sex.MALE, 45, 450.0)
        // v = 15.7511 -> floor 15, frac >= 0.5 -> 15.5
        assertEquals(15.5, r.visceralFat, 0.0)
        assertEquals(26.09, r.bodyFatPct, 0.0)
        assertEquals(63.49, r.muscleMassKg, 0.0)
        assertEquals(70.55, r.muscleRatePct, 0.0)
        assertEquals(39.8, r.skeletalMuscleRatePct, 0.0)
        assertEquals(1870.5, r.bmrKcal, 0.0)
        assertEquals(44, r.bodyAge)
        assertEquals(55, r.bodyScore)
        assertEquals(BodyShape.OBESITY, r.bodyShape) // fg3 [20,∞) / bg3 -> type 2
        assertEquals(70.0, r.idealWeightKg, 1e-9)
        assertEquals(28.57142857142857, r.obesityDegreePct, 1e-9)
    }

    @Test
    fun female65_fullPipeline() {
        val r = engine(155.0, 48.0, Sex.FEMALE, 65, 650.0)
        assertEquals(20.0, r.bmi, 0.0)
        assertEquals(27.23, r.bodyFatPct, 0.0)
        assertEquals(5.0, r.visceralFat, 0.0)
        assertEquals(52.7, r.waterPct, 0.0)
        assertEquals(32.29, r.muscleMassKg, 0.0)
        assertEquals(67.27, r.muscleRatePct, 0.0)
        assertEquals(41.7, r.skeletalMuscleRatePct, 0.0)
        assertEquals(934.1, r.bmrKcal, 0.0)
        assertEquals(55, r.bodyAge)
        assertEquals(82, r.bodyScore)
        assertEquals(BodyShape.HEALTHY, r.bodyShape) // fg2 [23,28) / bg1 -> type 4
        // underweight: Wt < BW -> FC = WC - MC
        assertEquals(3.0, r.weightControlKg, 1e-9)
        assertEquals(2.990000000000002, r.muscleControlKg, 1e-9)
        assertEquals(0.00999999999999801, r.fatControlKg, 1e-9)
    }

    // ---------- gating ----------

    @Test
    fun age16_waterComputedButVisceralBodyAgeScoreProteinZero() {
        val r = engine(170.0, 60.0, Sex.MALE, 16, 500.0)
        // deliberate deviation: water is computed at all ages
        assertEquals(58.49, r.waterPct, 0.0)
        assertEquals(13.4, r.bodyFatPct, 0.0)
        assertEquals(49.51, r.muscleMassKg, 0.0)
        assertEquals(82.51, r.muscleRatePct, 0.0)
        assertEquals(46.1, r.skeletalMuscleRatePct, 0.0)
        assertEquals(1509.3, r.bmrKcal, 0.0)
        // age <= 17 gates
        assertEquals(0.0, r.visceralFat, 0.0)
        assertEquals(0, r.bodyAge)
        assertEquals(0, r.bodyScore)
        assertEquals(0.0, r.proteinPct, 0.0)
        assertEquals(BodyShape.SLIM, r.bodyShape)
    }

    @Test
    fun age17_female_gatesVisceralBodyAgeScore() {
        val r = engine(165.0, 55.0, Sex.FEMALE, 17, 500.0)
        assertEquals(51.89, r.waterPct, 0.0)
        assertEquals(23.59, r.bodyFatPct, 0.0)
        assertEquals(0.0, r.visceralFat, 0.0)
        assertEquals(0, r.bodyAge)
        assertEquals(0, r.bodyScore)
        assertEquals(0.0, r.proteinPct, 0.0)
        assertEquals(1290.5, r.bmrKcal, 0.0)
    }

    @Test
    fun age5_allImpedanceMetricsZeroed() {
        val r = engine(120.0, 25.0, Sex.FEMALE, 5, 500.0)
        assertEquals(17.4, r.bmi, 0.0) // BMI is still computed
        assertEquals(0.0, r.bodyFatPct, 0.0)
        assertEquals(0.0, r.waterPct, 0.0)
        assertEquals(0.0, r.muscleMassKg, 0.0)
        assertEquals(0.0, r.boneMassKg, 0.0)
        assertEquals(0.0, r.bmrKcal, 0.0)
        assertEquals(0, r.bodyAge)
        assertEquals(0, r.bodyScore)
        assertNull(r.bodyShape)
        // weight-only metrics still computed
        assertEquals(30.0, r.idealWeightKg, 1e-9)
        assertEquals(-16.666666666666664, r.obesityDegreePct, 1e-9)
        assertEquals(25.0, r.leanBodyMassKg, 0.0)
        assertEquals(5.0, r.weightControlKg, 1e-9)
        assertEquals(18.375, r.muscleControlKg, 1e-9)
        assertEquals(-13.375, r.fatControlKg, 1e-9)
    }

    @Test
    fun weightBelow20_impedanceIgnored() {
        val r = engine(170.0, 15.0, Sex.MALE, 30, 500.0)
        assertEquals(0.0, r.effectiveImpedanceOhm, 0.0)
        assertEquals(0.0, r.bodyFatPct, 0.0)
        assertEquals(0.0, r.waterPct, 0.0)
        assertEquals(5.2, r.bmi, 0.0)
        assertEquals(47.99999999999999, r.weightControlKg, 1e-9)
        assertEquals(36.44999999999999, r.fatControlKg, 1e-9)
    }

    @Test
    fun bmiBelow5_impedanceIgnored() {
        val r = engine(220.0, 20.0, Sex.MALE, 30, 500.0)
        assertEquals(4.1, r.bmi, 0.0)
        assertEquals(0.0, r.effectiveImpedanceOhm, 0.0)
        assertEquals(0.0, r.bodyFatPct, 0.0)
        assertNull(r.bodyShape)
    }

    @Test
    fun noImpedance_allDerivedMetricsZero() {
        val r = engine(175.0, 70.0, Sex.MALE, 30, 0.0)
        assertEquals(22.9, r.bmi, 0.0)
        assertEquals(0.0, r.bodyFatPct, 0.0)
        assertEquals(0.0, r.fatMassKg, 0.0)
        assertEquals(0.0, r.visceralFat, 0.0)
        assertEquals(0.0, r.waterPct, 0.0)
        assertEquals(0.0, r.muscleMassKg, 0.0)
        assertEquals(0.0, r.bmrKcal, 0.0)
        assertEquals(0, r.bodyAge)
        assertEquals(0, r.bodyScore)
        assertNull(r.bodyShape)
        assertEquals(70.0, r.leanBodyMassKg, 0.0)
        assertEquals(66.5, r.idealWeightKg, 1e-9)
    }

    // ---------- clamps ----------

    @Test
    fun bfrClampHigh45_slmHighBranch() {
        val r = engine(150.0, 200.0, Sex.MALE, 30, 600.0)
        assertEquals(45.0, r.bodyFatPct, 0.0) // raw 51.399 -> clamped
        assertEquals(106.0, r.muscleMassKg, 0.0) // Wt*0.55 - 4
        assertEquals(53.0, r.muscleRatePct, 0.0)
        assertEquals(90.0, r.fatMassKg, 1e-9)
        assertEquals(4.0, r.boneMassKg, 0.0) // clamped at 4
        assertEquals(45, r.bodyScore) // clamped at 45 (raw 44.2 truncated)
        assertEquals(59.0, r.visceralFat, 0.0) // clamped at 59
        assertEquals(BodyShape.OBESITY, r.bodyShape) // fg3/bg3 -> type 2
    }

    @Test
    fun bfrClampLow5_slmLowBranch() {
        val r = engine(190.0, 40.0, Sex.MALE, 25, 300.0)
        assertEquals(5.0, r.bodyFatPct, 0.0) // raw -31.4 -> clamped
        assertEquals(37.0, r.muscleMassKg, 0.0) // Wt*0.95 - 1
        assertEquals(92.5, r.muscleRatePct, 0.0)
        assertEquals(1.0, r.boneMassKg, 0.0) // clamped at 1
        assertEquals(1.0, r.visceralFat, 0.0) // clamped at 1
        assertEquals(BodyShape.EMACIATED, r.bodyShape) // fg0/bg0 -> type 9
    }

    @Test
    fun waterClampHigh85() {
        val r = engine(200.0, 30.0, Sex.MALE, 30, 600.0)
        assertEquals(85.0, r.waterPct, 0.0) // raw 86.33 -> clamped
    }

    @Test
    fun waterClampLow20() {
        val r = engine(150.0, 100.0, Sex.MALE, 40, 5000.0)
        assertEquals(20.0, r.waterPct, 0.0) // raw 17.009 -> clamped
    }

    @Test
    fun visceralClampHigh59() {
        val r = engine(140.0, 200.0, Sex.MALE, 70, 100.0)
        assertEquals(59.0, r.visceralFat, 0.0) // raw 72.0011 -> clamped
        assertEquals(80, r.bodyAge) // clamped at 80
    }

    @Test
    fun visceralClampLow1_negativeRaw() {
        val r = engine(200.0, 40.0, Sex.MALE, 18, 800.0)
        assertEquals(1.0, r.visceralFat, 0.0) // raw -10.24 -> clamped
        assertEquals(18, r.bodyAge) // clamped at 18
    }

    @Test
    fun slmRegressionClampLow20() {
        // regression gives 19.8054 -> clamped to 20, only in the regression branch
        val r = engine(120.0, 25.0, Sex.FEMALE, 10, 500.0)
        assertEquals(20.0, r.muscleMassKg, 0.0)
        assertEquals(80.0, r.muscleRatePct, 0.0)
    }

    @Test
    fun slmRegressionClampHigh70() {
        val r = engine(200.0, 150.0, Sex.MALE, 45, 600.0)
        assertEquals(70.0, r.muscleMassKg, 0.0) // regression raw 90.94 -> clamped
        assertEquals(46.67, r.muscleRatePct, 0.0)
        assertEquals(-0.6, r.proteinPct, 0.0)
    }

    @Test
    fun skeletalMuscleRateZeroWhenNotBelowMuscleRate() {
        // MW clamps to 20 kg -> MWL = 80.0 which is >= muscleRate 80.0 -> skeletal rate 0
        val r = engine(120.0, 25.0, Sex.FEMALE, 10, 500.0)
        assertEquals(0.0, r.skeletalMuscleRatePct, 0.0)
        assertEquals(0.0, r.skeletalMuscleKg, 0.0)
    }

    @Test
    fun bodyAgeClampAgeMinus10Then18() {
        // raw body age 1 -> age-10 = 15 -> clamped to 18
        val r = engine(185.0, 65.0, Sex.MALE, 25, 300.0)
        assertEquals(18, r.bodyAge)
    }

    @Test
    fun bodyAgeClampAgePlus10() {
        // raw body age 115 -> clamped to age+10 = 50
        val r = engine(150.0, 120.0, Sex.MALE, 40, 900.0)
        assertEquals(50, r.bodyAge)
        assertEquals(45, r.bodyScore) // low clamp
    }

    // ---------- impedance smoothing ladder ----------

    @Test
    fun smoothing_allFourBranchesAndBoundaries() {
        // |d| <= 24 -> prev + d/16
        assertEquals(501.25, BodyCompositionEngine.smoothImpedance(520.0, 500.0, 10), 0.0)
        assertEquals(498.5, BodyCompositionEngine.smoothImpedance(476.0, 500.0, 10), 0.0)
        assertEquals(501.5, BodyCompositionEngine.smoothImpedance(524.0, 500.0, 10), 0.0) // |d|=24 edge
        // 24 < |d| <= 32 -> prev + d/4
        assertEquals(507.0, BodyCompositionEngine.smoothImpedance(528.0, 500.0, 10), 0.0)
        assertEquals(492.0, BodyCompositionEngine.smoothImpedance(468.0, 500.0, 10), 0.0)
        assertEquals(508.0, BodyCompositionEngine.smoothImpedance(532.0, 500.0, 10), 0.0) // |d|=32 edge
        // 32 < |d| <= 64 -> prev + d/2
        assertEquals(525.0, BodyCompositionEngine.smoothImpedance(550.0, 500.0, 10), 0.0)
        assertEquals(468.0, BodyCompositionEngine.smoothImpedance(436.0, 500.0, 10), 0.0)
        assertEquals(532.0, BodyCompositionEngine.smoothImpedance(564.0, 500.0, 10), 0.0) // |d|=64 edge
        // 64 < |d| <= 89 -> (prev + 3*now)/4
        assertEquals(560.0, BodyCompositionEngine.smoothImpedance(580.0, 500.0, 10), 0.0)
        assertEquals(433.25, BodyCompositionEngine.smoothImpedance(411.0, 500.0, 10), 0.0)
        assertEquals(566.75, BodyCompositionEngine.smoothImpedance(589.0, 500.0, 10), 0.0) // |d|=89 edge
        // |d| > 89 -> unchanged
        assertEquals(600.0, BodyCompositionEngine.smoothImpedance(600.0, 500.0, 10), 0.0)
        assertEquals(410.0, BodyCompositionEngine.smoothImpedance(410.0, 500.0, 10), 0.0) // |d|=90 edge
    }

    @Test
    fun smoothing_guards() {
        // dt > 30 min -> unchanged
        assertEquals(520.0, BodyCompositionEngine.smoothImpedance(520.0, 500.0, 31), 0.0)
        assertEquals(501.25, BodyCompositionEngine.smoothImpedance(520.0, 500.0, 30), 0.0) // dt=30 edge
        // equal values -> unchanged
        assertEquals(500.0, BodyCompositionEngine.smoothImpedance(500.0, 500.0, 10), 0.0)
        // either zero -> unchanged
        assertEquals(520.0, BodyCompositionEngine.smoothImpedance(520.0, 0.0, 10), 0.0)
        assertEquals(0.0, BodyCompositionEngine.smoothImpedance(0.0, 500.0, 10), 0.0)
    }

    @Test
    fun smoothing_appliedInPipeline() {
        val r = engine(175.0, 70.0, Sex.MALE, 30, 520.0, prevZ = 500.0, dtMin = 10)
        assertEquals(501.25, r.effectiveImpedanceOhm, 0.0)
        val noPrev = engine(175.0, 70.0, Sex.MALE, 30, 520.0)
        assertEquals(520.0, noPrev.effectiveImpedanceOhm, 0.0)
    }

    // ---------- body score ----------

    @Test
    fun score_vfrZeroBranch() {
        // bmiScore 83.9265, bfScore 92.0954, muscleScore 81.77, vfScore unused
        val s = BodyCompositionEngine.calScore(175.0, 70.0, Sex.MALE, 30, 18.36, 45.67, 0.0)
        assertEquals(85, s) // trunc(0.48*83.93 + 0.4*92.10 + 0.1*81.77) = trunc(85.2999)
    }

    @Test
    fun score_vfrPositiveBranch() {
        // vfScore(9.5) = -20.7858
        val s = BodyCompositionEngine.calScore(175.0, 70.0, Sex.MALE, 30, 18.36, 45.67, 9.5)
        assertEquals(76, s) // trunc(0.40*83.93 + 0.4*92.10 + 0.1*81.77 + 0.08*-20.79) = trunc(76.9229)
    }

    @Test
    fun score_femaleVfrZeroBranch() {
        val s = BodyCompositionEngine.calScore(160.0, 55.0, Sex.FEMALE, 28, 25.0, 40.0, 0.0)
        assertEquals(91, s)
    }

    @Test
    fun score_vfr15OrAbove_forcesMinus50() {
        val s = BodyCompositionEngine.calScore(170.0, 80.0, Sex.MALE, 35, 25.0, 50.0, 15.0)
        assertEquals(56, s)
    }

    @Test
    fun score_clampLow45() {
        // obese pipeline hits raw 44.2 -> truncated 44 -> clamped 45
        val r = engine(150.0, 200.0, Sex.MALE, 30, 600.0)
        assertEquals(45, r.bodyScore)
    }

    // ---------- no-impedance variants ----------

    @Test
    fun bodyAgeNoImpedance() {
        assertEquals(20, BodyCompositionEngine.calBodyAgeNoImpedance(175.0, 70.0, 30, 20.5))
        assertEquals(31, BodyCompositionEngine.calBodyAgeNoImpedance(160.0, 55.0, 28, 30.0))
        // clamps: age-10 -> 18
        assertEquals(18, BodyCompositionEngine.calBodyAgeNoImpedance(190.0, 50.0, 20, 8.0))
        // clamps: age+10 -> 60
        assertEquals(60, BodyCompositionEngine.calBodyAgeNoImpedance(150.0, 120.0, 50, 45.0))
    }

    @Test
    fun proteinNoImpedance() {
        // 1-dp HALF_UP as the original's DecimalFormatUtils.getOneFloat wrapper
        assertEquals(22.9, BodyCompositionEngine.calProteinNoImpedance(70.0, Sex.MALE, 20.5, 52.3), 0.0)
        assertEquals(15.4, BodyCompositionEngine.calProteinNoImpedance(55.0, Sex.FEMALE, 30.0, 50.1), 0.0)
    }

    // ---------- body shape matrix ----------

    @Test
    fun shapeMatrix_allCells_maleAge30() {
        // Thresholds: male {10, 15, 20} — fg0 (<10): 9,6,3,0
        assertEquals(BodyShape.EMACIATED, BodyCompositionEngine.bodyShape(5.0, 18.0, Sex.MALE, 30))
        assertEquals(BodyShape.SLIM_MUSCULAR, BodyCompositionEngine.bodyShape(5.0, 20.0, Sex.MALE, 30))
        assertEquals(BodyShape.MUSCULAR, BodyCompositionEngine.bodyShape(5.0, 22.0, Sex.MALE, 30))
        assertEquals(BodyShape.ATHLETE, BodyCompositionEngine.bodyShape(5.0, 25.0, Sex.MALE, 30))
        // fg1 [10,15): 10,7,3,0
        assertEquals(BodyShape.SLIGHTLY_UNDERWEIGHT, BodyCompositionEngine.bodyShape(12.0, 18.0, Sex.MALE, 30))
        assertEquals(BodyShape.SLIM, BodyCompositionEngine.bodyShape(12.0, 20.0, Sex.MALE, 30))
        assertEquals(BodyShape.MUSCULAR, BodyCompositionEngine.bodyShape(12.0, 22.0, Sex.MALE, 30))
        assertEquals(BodyShape.ATHLETE, BodyCompositionEngine.bodyShape(12.0, 25.0, Sex.MALE, 30))
        // fg2 [15,20): 10,4,4,1
        assertEquals(BodyShape.SLIGHTLY_UNDERWEIGHT, BodyCompositionEngine.bodyShape(17.0, 18.0, Sex.MALE, 30))
        assertEquals(BodyShape.HEALTHY, BodyCompositionEngine.bodyShape(17.0, 20.0, Sex.MALE, 30))
        assertEquals(BodyShape.HEALTHY, BodyCompositionEngine.bodyShape(17.0, 22.0, Sex.MALE, 30))
        assertEquals(BodyShape.MUSCULAR_OBESITY, BodyCompositionEngine.bodyShape(17.0, 25.0, Sex.MALE, 30))
        // fg3 (>=20): 8,8,5,2
        assertEquals(BodyShape.HIDDEN_OBESITY, BodyCompositionEngine.bodyShape(25.0, 18.0, Sex.MALE, 30))
        assertEquals(BodyShape.HIDDEN_OBESITY, BodyCompositionEngine.bodyShape(25.0, 20.0, Sex.MALE, 30))
        assertEquals(BodyShape.SLIGHTLY_OVERWEIGHT, BodyCompositionEngine.bodyShape(25.0, 22.0, Sex.MALE, 30))
        assertEquals(BodyShape.OBESITY, BodyCompositionEngine.bodyShape(25.0, 25.0, Sex.MALE, 30))
    }

    @Test
    fun shapeGradeBoundaries() {
        // fat grade thresholds are [t1,t2) / [t2,t3)
        assertEquals(BodyShape.SLIM_MUSCULAR, BodyCompositionEngine.bodyShape(9.9, 20.0, Sex.MALE, 30)) // fg0
        assertEquals(BodyShape.SLIM, BodyCompositionEngine.bodyShape(10.0, 20.0, Sex.MALE, 30)) // fg1
        assertEquals(BodyShape.HEALTHY, BodyCompositionEngine.bodyShape(15.0, 20.0, Sex.MALE, 30)) // fg2
        assertEquals(BodyShape.HIDDEN_OBESITY, BodyCompositionEngine.bodyShape(20.0, 20.0, Sex.MALE, 30)) // fg3
        // female: 17.9 fg0 / 18.0 fg1 / 22.9 fg1 / 23.0 fg2 / 27.9 fg2 / 28.0 fg3
        assertEquals(BodyShape.SLIM_MUSCULAR, BodyCompositionEngine.bodyShape(17.9, 20.0, Sex.FEMALE, 30))
        assertEquals(BodyShape.SLIM, BodyCompositionEngine.bodyShape(18.0, 20.0, Sex.FEMALE, 30))
        assertEquals(BodyShape.SLIM, BodyCompositionEngine.bodyShape(22.9, 20.0, Sex.FEMALE, 30))
        assertEquals(BodyShape.HEALTHY, BodyCompositionEngine.bodyShape(23.0, 20.0, Sex.FEMALE, 30))
        assertEquals(BodyShape.HEALTHY, BodyCompositionEngine.bodyShape(27.9, 20.0, Sex.FEMALE, 30))
        assertEquals(BodyShape.HIDDEN_OBESITY, BodyCompositionEngine.bodyShape(28.0, 20.0, Sex.FEMALE, 30))
        // BMI grade: [18.5,24) splits at midpoint 21.25; >=24 -> 3
        assertEquals(BodyShape.SLIM_MUSCULAR, BodyCompositionEngine.bodyShape(5.0, 18.5, Sex.MALE, 30)) // bg1
        assertEquals(BodyShape.MUSCULAR, BodyCompositionEngine.bodyShape(5.0, 21.25, Sex.MALE, 30)) // bg2
        assertEquals(BodyShape.ATHLETE, BodyCompositionEngine.bodyShape(5.0, 24.0, Sex.MALE, 30)) // bg3
    }

    @Test
    fun shapeFatThresholds_ageIndependent() {
        // The report matrix uses range indices 1-2, which
        // do not vary with age: male {10,15,20}, female {18,23,28}.
        for (age in intArrayOf(30, 45, 60, 75)) {
            assertEquals(BodyShape.SLIM_MUSCULAR, BodyCompositionEngine.bodyShape(9.9, 20.0, Sex.MALE, age))
            assertEquals(BodyShape.SLIM, BodyCompositionEngine.bodyShape(10.0, 20.0, Sex.MALE, age))
            assertEquals(BodyShape.HEALTHY, BodyCompositionEngine.bodyShape(15.0, 20.0, Sex.MALE, age))
            assertEquals(BodyShape.SLIM_MUSCULAR, BodyCompositionEngine.bodyShape(17.9, 20.0, Sex.FEMALE, age))
            assertEquals(BodyShape.SLIM, BodyCompositionEngine.bodyShape(18.0, 20.0, Sex.FEMALE, age))
            assertEquals(BodyShape.HEALTHY, BodyCompositionEngine.bodyShape(23.0, 20.0, Sex.FEMALE, age))
        }
    }

    @Test
    fun shape_nullWhenUnknown() {
        assertNull(BodyCompositionEngine.bodyShape(0.0, 22.0, Sex.MALE, 30))
        assertNull(BodyCompositionEngine.bodyShape(18.0, 0.0, Sex.MALE, 30))
    }

    @Test
    fun shapeLabels_matchOriginalResources() {
        assertEquals("Athlete body type", BodyShape.ATHLETE.label)
        assertEquals("Muscular obesity", BodyShape.MUSCULAR_OBESITY.label)
        assertEquals("Obesity", BodyShape.OBESITY.label)
        assertEquals("Muscular type", BodyShape.MUSCULAR.label)
        assertEquals("Healthy type", BodyShape.HEALTHY.label)
        assertEquals("Slightly overweight", BodyShape.SLIGHTLY_OVERWEIGHT.label)
        assertEquals("Slim muscular type", BodyShape.SLIM_MUSCULAR.label)
        assertEquals("Slim", BodyShape.SLIM.label)
        assertEquals("Hidden obesity", BodyShape.HIDDEN_OBESITY.label)
        assertEquals("Emaciated", BodyShape.EMACIATED.label)
        assertEquals("Slightly underweight", BodyShape.SLIGHTLY_UNDERWEIGHT.label)
        assertEquals(11, BodyShape.entries.size)
    }
}
