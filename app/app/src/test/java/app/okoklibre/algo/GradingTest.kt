package app.okoklibre.algo

import app.okoklibre.algo.Grading.Level
import app.okoklibre.algo.Grading.Metric
import app.okoklibre.algo.Grading.Region
import org.junit.Assert.assertEquals
import org.junit.Test

/** Grading tables. */
class GradingTest {

    // ---------- BMI (3 regions) ----------

    @Test
    fun bmiRanges() {
        assertEquals(listOf(14.0, 18.5, 23.9, 26.9, 40.0), Grading.bmiRange(Region.CHINA))
        assertEquals(listOf(14.0, 18.5, 22.9, 27.5, 40.0), Grading.bmiRange(Region.SOUTHEAST_ASIA))
        assertEquals(listOf(14.0, 18.5, 24.9, 29.9, 40.0), Grading.bmiRange(Region.OTHER))
    }

    @Test
    fun bmiLevels_china() {
        assertEquals(Level.THIN, Grading.bmiLevel(18.4, Region.CHINA))
        assertEquals(Level.STANDARD, Grading.bmiLevel(18.5, Region.CHINA))
        assertEquals(Level.STANDARD, Grading.bmiLevel(23.8, Region.CHINA))
        assertEquals(Level.OVERWEIGHT, Grading.bmiLevel(23.9, Region.CHINA))
        assertEquals(Level.OVERWEIGHT, Grading.bmiLevel(26.8, Region.CHINA))
        assertEquals(Level.OBESE, Grading.bmiLevel(26.9, Region.CHINA))
    }

    @Test
    fun bmiLevels_southeastAsia() {
        assertEquals(Level.STANDARD, Grading.bmiLevel(22.8, Region.SOUTHEAST_ASIA))
        assertEquals(Level.OVERWEIGHT, Grading.bmiLevel(22.9, Region.SOUTHEAST_ASIA))
        assertEquals(Level.OBESE, Grading.bmiLevel(27.5, Region.SOUTHEAST_ASIA))
    }

    @Test
    fun bmiLevels_other() {
        assertEquals(Level.STANDARD, Grading.bmiLevel(24.8, Region.OTHER))
        assertEquals(Level.OVERWEIGHT, Grading.bmiLevel(24.9, Region.OTHER))
        assertEquals(Level.OBESE, Grading.bmiLevel(29.9, Region.OTHER))
    }

    @Test
    fun standardWeightRange_fromBmiRange() {
        val r = Grading.standardWeightRange(170.0, Region.CHINA)
        assertEquals(40.46, r[0], 1e-9)
        assertEquals(53.465, r[1], 1e-9)
        assertEquals(69.071, r[2], 1e-9)
        assertEquals(77.741, r[3], 1e-9)
        assertEquals(115.6, r[4], 1e-9)
    }

    // ---------- body fat (sex x 3 age bands) ----------

    @Test
    fun bodyFatRanges_allRows() {
        assertEquals(listOf(5.0, 11.0, 17.0, 27.0, 45.0), Grading.bodyFatRange(Sex.MALE, 39))
        assertEquals(listOf(5.0, 12.0, 18.0, 28.0, 45.0), Grading.bodyFatRange(Sex.MALE, 40))
        assertEquals(listOf(5.0, 12.0, 18.0, 28.0, 45.0), Grading.bodyFatRange(Sex.MALE, 59))
        assertEquals(listOf(5.0, 14.0, 20.0, 30.0, 45.0), Grading.bodyFatRange(Sex.MALE, 60))
        assertEquals(listOf(5.0, 21.0, 28.0, 40.0, 45.0), Grading.bodyFatRange(Sex.FEMALE, 39))
        assertEquals(listOf(5.0, 22.0, 29.0, 41.0, 45.0), Grading.bodyFatRange(Sex.FEMALE, 40))
        assertEquals(listOf(5.0, 22.0, 29.0, 41.0, 45.0), Grading.bodyFatRange(Sex.FEMALE, 59))
        assertEquals(listOf(5.0, 23.0, 30.0, 42.0, 45.0), Grading.bodyFatRange(Sex.FEMALE, 60))
    }

    @Test
    fun bodyFatLevels_male30() {
        assertEquals(Level.LOW, Grading.bodyFatLevel(10.9, Sex.MALE, 30))
        assertEquals(Level.STANDARD, Grading.bodyFatLevel(11.0, Sex.MALE, 30))
        assertEquals(Level.STANDARD, Grading.bodyFatLevel(16.9, Sex.MALE, 30))
        assertEquals(Level.HIGH, Grading.bodyFatLevel(17.0, Sex.MALE, 30))
        assertEquals(Level.HIGH, Grading.bodyFatLevel(26.9, Sex.MALE, 30))
        assertEquals(Level.OBESE, Grading.bodyFatLevel(27.0, Sex.MALE, 30))
    }

    @Test
    fun bodyFatLevels_femaleBands() {
        assertEquals(Level.LOW, Grading.bodyFatLevel(20.9, Sex.FEMALE, 30))
        assertEquals(Level.STANDARD, Grading.bodyFatLevel(21.0, Sex.FEMALE, 30))
        assertEquals(Level.HIGH, Grading.bodyFatLevel(28.0, Sex.FEMALE, 30))
        assertEquals(Level.OBESE, Grading.bodyFatLevel(40.0, Sex.FEMALE, 30))
        assertEquals(Level.LOW, Grading.bodyFatLevel(21.9, Sex.FEMALE, 45))
        assertEquals(Level.STANDARD, Grading.bodyFatLevel(22.0, Sex.FEMALE, 45))
        assertEquals(Level.HIGH, Grading.bodyFatLevel(29.0, Sex.FEMALE, 45))
        assertEquals(Level.OBESE, Grading.bodyFatLevel(41.0, Sex.FEMALE, 45))
        assertEquals(Level.LOW, Grading.bodyFatLevel(22.9, Sex.FEMALE, 70))
        assertEquals(Level.STANDARD, Grading.bodyFatLevel(23.0, Sex.FEMALE, 70))
        assertEquals(Level.HIGH, Grading.bodyFatLevel(30.0, Sex.FEMALE, 70))
        assertEquals(Level.OBESE, Grading.bodyFatLevel(42.0, Sex.FEMALE, 70))
    }

    // ---------- water (sex x age<=30/>30) ----------

    @Test
    fun waterRanges_allRows() {
        assertEquals(listOf(37.8, 53.6, 57.0, 66.0), Grading.waterRange(Sex.MALE, 30))
        assertEquals(listOf(37.8, 52.3, 55.6, 66.0), Grading.waterRange(Sex.MALE, 31))
        assertEquals(listOf(37.8, 49.5, 52.9, 66.0), Grading.waterRange(Sex.FEMALE, 30))
        assertEquals(listOf(37.8, 48.1, 51.5, 66.0), Grading.waterRange(Sex.FEMALE, 31))
    }

    @Test
    fun waterLevels() {
        assertEquals(Level.LOW, Grading.waterLevel(53.5, Sex.MALE, 25))
        assertEquals(Level.STANDARD, Grading.waterLevel(53.6, Sex.MALE, 25))
        assertEquals(Level.STANDARD, Grading.waterLevel(57.0, Sex.MALE, 25))
        assertEquals(Level.HIGH, Grading.waterLevel(57.1, Sex.MALE, 25))
        assertEquals(Level.LOW, Grading.waterLevel(52.29, Sex.MALE, 40))
        assertEquals(Level.STANDARD, Grading.waterLevel(52.3, Sex.MALE, 40))
        assertEquals(Level.STANDARD, Grading.waterLevel(55.6, Sex.MALE, 40))
        assertEquals(Level.HIGH, Grading.waterLevel(55.7, Sex.MALE, 40))
        assertEquals(Level.LOW, Grading.waterLevel(49.4, Sex.FEMALE, 25))
        assertEquals(Level.STANDARD, Grading.waterLevel(49.5, Sex.FEMALE, 25))
        assertEquals(Level.STANDARD, Grading.waterLevel(52.9, Sex.FEMALE, 25))
        assertEquals(Level.HIGH, Grading.waterLevel(53.0, Sex.FEMALE, 25))
        assertEquals(Level.LOW, Grading.waterLevel(48.09, Sex.FEMALE, 40))
        assertEquals(Level.STANDARD, Grading.waterLevel(48.1, Sex.FEMALE, 40))
        assertEquals(Level.STANDARD, Grading.waterLevel(51.5, Sex.FEMALE, 40))
        assertEquals(Level.HIGH, Grading.waterLevel(51.6, Sex.FEMALE, 40))
    }

    // ---------- visceral fat ----------

    @Test
    fun visceralLevels() {
        assertEquals(Level.LOW, Grading.visceralFatLevel(0.9))
        assertEquals(Level.STANDARD, Grading.visceralFatLevel(1.0))
        assertEquals(Level.STANDARD, Grading.visceralFatLevel(9.0))
        assertEquals(Level.HIGH, Grading.visceralFatLevel(9.5))
        assertEquals(Level.HIGH, Grading.visceralFatLevel(14.0))
        assertEquals(Level.VERY_HIGH, Grading.visceralFatLevel(14.5))
        assertEquals(listOf(1.0, 9.0, 14.0), Grading.visceralFatRange())
    }

    // ---------- bone mass ----------

    @Test
    fun boneMassRanges_allRows() {
        // high bound is round1(1.3 * base): float/double rounding yields 3.1, 3.6, 4.0, 2.2, 2.7
        assertEquals(listOf(1.68, 2.4, 3.1, 5.0), Grading.boneMassRange(Sex.MALE, 54).map { roundTo1e9(it) })
        assertEquals(listOf(1.96, 2.8, 3.6, 5.0), Grading.boneMassRange(Sex.MALE, 55).map { roundTo1e9(it) })
        assertEquals(listOf(1.96, 2.8, 3.6, 5.0), Grading.boneMassRange(Sex.MALE, 75).map { roundTo1e9(it) })
        assertEquals(listOf(2.17, 3.1, 4.0, 5.0), Grading.boneMassRange(Sex.MALE, 76).map { roundTo1e9(it) })
        assertEquals(listOf(1.19, 1.7, 2.2, 5.0), Grading.boneMassRange(Sex.FEMALE, 39).map { roundTo1e9(it) })
        assertEquals(listOf(1.47, 2.1, 2.7, 5.0), Grading.boneMassRange(Sex.FEMALE, 40).map { roundTo1e9(it) })
        assertEquals(listOf(1.47, 2.1, 2.7, 5.0), Grading.boneMassRange(Sex.FEMALE, 60).map { roundTo1e9(it) })
        assertEquals(listOf(1.68, 2.4, 3.1, 5.0), Grading.boneMassRange(Sex.FEMALE, 61).map { roundTo1e9(it) })
    }

    private fun roundTo1e9(v: Double) = Math.rint(v * 1e9) / 1e9

    @Test
    fun boneMassLevels() {
        assertEquals(Level.LOW, Grading.boneMassLevel(2.3, Sex.MALE, 30))
        assertEquals(Level.STANDARD, Grading.boneMassLevel(2.4, Sex.MALE, 30))
        assertEquals(Level.STANDARD, Grading.boneMassLevel(3.09, Sex.MALE, 30))
        assertEquals(Level.HIGH, Grading.boneMassLevel(3.1, Sex.MALE, 30))
        assertEquals(Level.LOW, Grading.boneMassLevel(1.6, Sex.FEMALE, 30))
        assertEquals(Level.STANDARD, Grading.boneMassLevel(1.7, Sex.FEMALE, 30))
        assertEquals(Level.STANDARD, Grading.boneMassLevel(2.19, Sex.FEMALE, 30))
        assertEquals(Level.HIGH, Grading.boneMassLevel(2.2, Sex.FEMALE, 30))
    }

    // ---------- skeletal muscle rate ----------

    @Test
    fun skeletalMuscleRateRangesAndLevels() {
        assertEquals(listOf(26.0, 31.0, 39.0, 45.0), Grading.skeletalMuscleRateRange(Sex.MALE))
        assertEquals(listOf(21.0, 25.0, 30.0, 35.0), Grading.skeletalMuscleRateRange(Sex.FEMALE))
        assertEquals(Level.LOW, Grading.skeletalMuscleRateLevel(30.9, Sex.MALE))
        assertEquals(Level.STANDARD, Grading.skeletalMuscleRateLevel(31.0, Sex.MALE))
        assertEquals(Level.STANDARD, Grading.skeletalMuscleRateLevel(39.0, Sex.MALE))
        assertEquals(Level.HIGH, Grading.skeletalMuscleRateLevel(39.1, Sex.MALE))
        assertEquals(Level.LOW, Grading.skeletalMuscleRateLevel(24.9, Sex.FEMALE))
        assertEquals(Level.STANDARD, Grading.skeletalMuscleRateLevel(25.0, Sex.FEMALE))
        assertEquals(Level.STANDARD, Grading.skeletalMuscleRateLevel(30.0, Sex.FEMALE))
        assertEquals(Level.HIGH, Grading.skeletalMuscleRateLevel(30.1, Sex.FEMALE))
    }

    // ---------- muscle weight (sex x height bands) ----------

    @Test
    fun muscleWeightRanges_allRows() {
        assertEquals(listOf(30.0, 38.5, 46.5, 54.0), Grading.muscleWeightRange(Sex.MALE, 159.9))
        assertEquals(listOf(36.0, 44.0, 52.4, 60.0), Grading.muscleWeightRange(Sex.MALE, 160.0))
        assertEquals(listOf(36.0, 44.0, 52.4, 60.0), Grading.muscleWeightRange(Sex.MALE, 170.0))
        assertEquals(listOf(39.0, 49.4, 59.4, 69.0), Grading.muscleWeightRange(Sex.MALE, 170.1))
        assertEquals(listOf(10.0, 21.9, 34.7, 47.0), Grading.muscleWeightRange(Sex.FEMALE, 149.9))
        assertEquals(listOf(27.0, 32.9, 37.5, 42.0), Grading.muscleWeightRange(Sex.FEMALE, 150.0))
        assertEquals(listOf(27.0, 32.9, 37.5, 42.0), Grading.muscleWeightRange(Sex.FEMALE, 160.0))
        assertEquals(listOf(30.0, 36.5, 42.5, 48.0), Grading.muscleWeightRange(Sex.FEMALE, 160.1))
    }

    @Test
    fun muscleWeightLevels() {
        assertEquals(Level.LOW, Grading.muscleWeightLevel(43.9, Sex.MALE, 165.0))
        assertEquals(Level.STANDARD, Grading.muscleWeightLevel(44.0, Sex.MALE, 165.0))
        assertEquals(Level.STANDARD, Grading.muscleWeightLevel(52.4, Sex.MALE, 165.0))
        assertEquals(Level.HIGH, Grading.muscleWeightLevel(52.5, Sex.MALE, 165.0))
        assertEquals(Level.LOW, Grading.muscleWeightLevel(32.8, Sex.FEMALE, 155.0))
        assertEquals(Level.STANDARD, Grading.muscleWeightLevel(32.9, Sex.FEMALE, 155.0))
        assertEquals(Level.HIGH, Grading.muscleWeightLevel(37.6, Sex.FEMALE, 155.0))
    }

    // ---------- BMR (sex x 10 age bands) ----------

    @Test
    fun bmrStandards_allBands() {
        val maleLimits = intArrayOf(2, 5, 8, 11, 14, 17, 29, 49, 69)
        val male = intArrayOf(700, 900, 1090, 1290, 1480, 1610, 1550, 1500, 1350)
        val female = intArrayOf(700, 860, 1000, 1180, 1340, 1300, 1210, 1170, 1110)
        for (i in maleLimits.indices) {
            assertEquals(male[i], Grading.bmrStandard(Sex.MALE, maleLimits[i]))
            assertEquals(female[i], Grading.bmrStandard(Sex.FEMALE, maleLimits[i]))
            if (i > 0) {
                assertEquals(male[i], Grading.bmrStandard(Sex.MALE, maleLimits[i - 1] + 1))
                assertEquals(female[i], Grading.bmrStandard(Sex.FEMALE, maleLimits[i - 1] + 1))
            }
        }
        assertEquals(1220, Grading.bmrStandard(Sex.MALE, 70))
        assertEquals(1220, Grading.bmrStandard(Sex.MALE, 99))
        assertEquals(1010, Grading.bmrStandard(Sex.FEMALE, 70))
        assertEquals(1010, Grading.bmrStandard(Sex.FEMALE, 99))
    }

    @Test
    fun bmrRangeAndLevels() {
        // male 30 -> standard 1500 -> range [1425, 1575]
        assertEquals(listOf(1425.0, 1575.0), Grading.bmrRange(Sex.MALE, 30))
        assertEquals(Level.LOW, Grading.bmrLevel(1425.0, Sex.MALE, 30))
        assertEquals(Level.STANDARD, Grading.bmrLevel(1425.01, Sex.MALE, 30))
        assertEquals(Level.STANDARD, Grading.bmrLevel(1575.0, Sex.MALE, 30))
        assertEquals(Level.HIGH, Grading.bmrLevel(1575.01, Sex.MALE, 30))
        // female 28 -> standard 1210 -> range [1149.5, 1270.5]
        assertEquals(listOf(1149.5, 1270.5), Grading.bmrRange(Sex.FEMALE, 28))
        assertEquals(Level.LOW, Grading.bmrLevel(1149.5, Sex.FEMALE, 28))
        assertEquals(Level.STANDARD, Grading.bmrLevel(1210.0, Sex.FEMALE, 28))
        assertEquals(Level.HIGH, Grading.bmrLevel(1270.51, Sex.FEMALE, 28))
    }

    // ---------- protein ----------

    @Test
    fun proteinLevels() {
        assertEquals(Level.LOW, Grading.proteinLevel(15.9))
        assertEquals(Level.STANDARD, Grading.proteinLevel(16.0))
        assertEquals(Level.STANDARD, Grading.proteinLevel(20.0))
        assertEquals(Level.HIGH, Grading.proteinLevel(20.1))
    }

    // ---------- obesity degree ----------

    @Test
    fun obesityDegreeLevels() {
        assertEquals(Level.STANDARD, Grading.obesityDegreeLevel(0.0))
        assertEquals(Level.STANDARD, Grading.obesityDegreeLevel(-10.0))
        assertEquals(Level.STANDARD, Grading.obesityDegreeLevel(10.0))
        assertEquals(Level.STANDARD, Grading.obesityDegreeLevel(-10.1)) // falls back to standard
        assertEquals(Level.MILD, Grading.obesityDegreeLevel(10.1))
        assertEquals(Level.MILD, Grading.obesityDegreeLevel(20.0))
        assertEquals(Level.MODERATE, Grading.obesityDegreeLevel(20.1))
        assertEquals(Level.MODERATE, Grading.obesityDegreeLevel(30.0))
        assertEquals(Level.SEVERE, Grading.obesityDegreeLevel(30.1))
        assertEquals(Level.SEVERE, Grading.obesityDegreeLevel(50.0))
        assertEquals(Level.EXTREME, Grading.obesityDegreeLevel(50.1))
    }

    // ---------- lean body mass ----------

    @Test
    fun leanBodyMassRangeAndLevels() {
        // h = 1.75 m: thresholds 18h2=55.125, 20h2=61.25, 23h2=70.4375
        val r = Grading.leanBodyMassRange(175.0)
        assertEquals(55.125, r[0], 1e-9)
        assertEquals(61.25, r[1], 1e-9)
        assertEquals(70.4375, r[2], 1e-9)
        assertEquals(Level.LOW, Grading.leanBodyMassLevel(55.125, 175.0))
        assertEquals(Level.STANDARD, Grading.leanBodyMassLevel(55.126, 175.0))
        assertEquals(Level.STANDARD, Grading.leanBodyMassLevel(61.25, 175.0))
        assertEquals(Level.HIGH, Grading.leanBodyMassLevel(61.26, 175.0))
        assertEquals(Level.HIGH, Grading.leanBodyMassLevel(70.4375, 175.0))
        assertEquals(Level.VERY_HIGH, Grading.leanBodyMassLevel(70.4376, 175.0))
    }

    // ---------- grade() dispatcher ----------

    @Test
    fun gradeDispatcher() {
        assertEquals(
            Grading.Grade(Level.STANDARD, listOf(14.0, 18.5, 24.9, 29.9, 40.0)),
            Grading.grade(Metric.BMI, 22.0),
        )
        assertEquals(
            Grading.Grade(Level.HIGH, listOf(5.0, 11.0, 17.0, 27.0, 45.0)),
            Grading.grade(Metric.BODY_FAT, 20.0, sex = Sex.MALE, ageYears = 30),
        )
        assertEquals(Level.STANDARD, Grading.grade(Metric.WATER, 55.0, sex = Sex.MALE, ageYears = 25).level)
        assertEquals(Level.HIGH, Grading.grade(Metric.VISCERAL_FAT, 12.0).level)
        assertEquals(Level.STANDARD, Grading.grade(Metric.BONE_MASS, 2.4, sex = Sex.MALE, ageYears = 30).level)
        assertEquals(Level.LOW, Grading.grade(Metric.SKELETAL_MUSCLE_RATE, 30.0, sex = Sex.MALE).level)
        assertEquals(
            Level.STANDARD,
            Grading.grade(Metric.MUSCLE_WEIGHT, 45.0, sex = Sex.MALE, heightCm = 165.0).level,
        )
        assertEquals(Level.STANDARD, Grading.grade(Metric.BMR, 1500.0, sex = Sex.MALE, ageYears = 30).level)
        assertEquals(Level.LOW, Grading.grade(Metric.PROTEIN, 15.0).level)
        assertEquals(Level.MODERATE, Grading.grade(Metric.OBESITY_DEGREE, 25.0).level)
        assertEquals(Level.STANDARD, Grading.grade(Metric.LEAN_BODY_MASS, 60.0, heightCm = 175.0).level)
    }

    // ---------- gauge helpers ----------

    @Test
    fun gaugeTwoPoint() {
        assertEquals(2.0, Grading.gaugeTwoPoint(26.0, 31.0, 20.0), 1e-9) // below low
        assertEquals(2.0, Grading.gaugeTwoPoint(26.0, 31.0, 26.0), 1e-9) // at low
        assertEquals(6.0, Grading.gaugeTwoPoint(26.0, 31.0, 28.5), 1e-9) // midpoint
        assertEquals(10.0, Grading.gaugeTwoPoint(26.0, 31.0, 31.0), 1e-9) // at high
        assertEquals(10.0, Grading.gaugeTwoPoint(26.0, 31.0, 40.0), 1e-9) // saturated
        assertEquals(10.0, Grading.gaugeTwoPoint(26.0, 31.0, 0.0), 1e-9) // zero quirk
    }

    @Test
    fun gaugePlateau() {
        // water male <=30: 37.8 / 53.6 / 57.0 / 66.0
        assertEquals(2.0, Grading.gaugePlateau(37.8, 53.6, 57.0, 66.0, 37.8), 1e-9)
        assertEquals(8.177215189873419, Grading.gaugePlateau(37.8, 53.6, 57.0, 66.0, 50.0), 1e-9)
        assertEquals(10.0, Grading.gaugePlateau(37.8, 53.6, 57.0, 66.0, 55.0), 1e-9) // plateau
        assertEquals(7.333333333333333, Grading.gaugePlateau(37.8, 53.6, 57.0, 66.0, 60.0), 1e-9)
        assertEquals(2.0, Grading.gaugePlateau(37.8, 53.6, 57.0, 66.0, 30.0), 1e-9) // below axis
        assertEquals(2.0, Grading.gaugePlateau(37.8, 53.6, 57.0, 66.0, 70.0), 1e-9) // above axis
        assertEquals(10.0, Grading.gaugePlateau(37.8, 53.6, 57.0, 66.0, 0.0), 1e-9) // zero quirk
    }

    @Test
    fun gaugePeak() {
        // BMI code: peak at (18.5+23.9)/2 = 21.2
        assertEquals(10.0, Grading.gaugePeak(14.0, 18.5, 23.9, 40.0, 21.2), 1e-9)
        assertEquals(5.333333333333334, Grading.gaugePeak(14.0, 18.5, 23.9, 40.0, 17.0), 1e-9)
        assertEquals(2.0, Grading.gaugePeak(14.0, 18.5, 23.9, 40.0, 14.0), 1e-9) // at axis min
        assertEquals(2.0, Grading.gaugePeak(14.0, 18.5, 23.9, 40.0, 45.0), 1e-9) // beyond axis max
        // viscera code: peak at (1+9)/2 = 5
        assertEquals(10.0, Grading.gaugePeak(0.0, 1.0, 9.0, 30.0, 5.0), 1e-9)
        assertEquals(10.0, Grading.gaugePeak(0.0, 1.0, 9.0, 30.0, 0.0), 1e-9) // zero quirk
    }

    @Test
    fun levelPercent() {
        val t = listOf(5.0, 11.0, 17.0, 27.0, 45.0)
        assertEquals(88.88888533333332, Grading.levelPercent(15.0, t), 1e-9)
        assertEquals(100.0, Grading.levelPercent(50.0, t), 1e-9)
        assertEquals(0.0, Grading.levelPercent(0.0, t), 1e-9)
        assertEquals(0.0, Grading.levelPercent(15.0, listOf(5.0)), 1e-9)
        assertEquals(33.333332, Grading.levelPercent(5.0, t), 1e-6)
    }
}
