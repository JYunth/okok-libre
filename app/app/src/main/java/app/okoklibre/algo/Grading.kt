package app.okoklibre.algo

/**
 * Standard-range grading tables and gauge codes.
 */
object Grading {

    enum class Region { CHINA, SOUTHEAST_ASIA, OTHER }

    enum class Metric {
        BMI, BODY_FAT, WATER, VISCERAL_FAT, BONE_MASS, SKELETAL_MUSCLE_RATE,
        MUSCLE_WEIGHT, BMR, PROTEIN, OBESITY_DEGREE, LEAN_BODY_MASS,
    }

    enum class Level {
        THIN, LOW, STANDARD, HIGH, OVERWEIGHT, VERY_HIGH, OBESE,
        MILD, MODERATE, SEVERE, EXTREME,
    }

    /** [range] is the metric's standard-threshold table for this metric. */
    data class Grade(val level: Level, val range: List<Double>)

    fun grade(
        metric: Metric,
        value: Double,
        sex: Sex = Sex.MALE,
        ageYears: Int = 30,
        heightCm: Double = 170.0,
        region: Region = Region.OTHER,
    ): Grade = when (metric) {
        Metric.BMI -> Grade(bmiLevel(value, region), bmiRange(region))
        Metric.BODY_FAT -> Grade(bodyFatLevel(value, sex, ageYears), bodyFatRange(sex, ageYears))
        Metric.WATER -> Grade(waterLevel(value, sex, ageYears), waterRange(sex, ageYears))
        Metric.VISCERAL_FAT -> Grade(visceralFatLevel(value), visceralFatRange())
        Metric.BONE_MASS -> Grade(boneMassLevel(value, sex, ageYears), boneMassRange(sex, ageYears))
        Metric.SKELETAL_MUSCLE_RATE ->
            Grade(skeletalMuscleRateLevel(value, sex), skeletalMuscleRateRange(sex))
        Metric.MUSCLE_WEIGHT -> Grade(muscleWeightLevel(value, sex, heightCm), muscleWeightRange(sex, heightCm))
        Metric.BMR -> Grade(bmrLevel(value, sex, ageYears), bmrRange(sex, ageYears))
        Metric.PROTEIN -> Grade(proteinLevel(value), listOf(16.0, 20.0))
        Metric.OBESITY_DEGREE -> Grade(obesityDegreeLevel(value), listOf(-10.0, 10.0, 20.0, 30.0, 50.0))
        Metric.LEAN_BODY_MASS -> Grade(leanBodyMassLevel(value, heightCm), leanBodyMassRange(heightCm))
    }

    // BMI

    fun bmiRange(region: Region): List<Double> = when (region) {
        Region.CHINA -> listOf(14.0, 18.5, 23.9, 26.9, 40.0)
        Region.SOUTHEAST_ASIA -> listOf(14.0, 18.5, 22.9, 27.5, 40.0)
        Region.OTHER -> listOf(14.0, 18.5, 24.9, 29.9, 40.0)
    }

    fun bmiLevel(bmi: Double, region: Region): Level {
        val r = bmiRange(region)
        return when {
            bmi < r[1] -> Level.THIN
            bmi < r[2] -> Level.STANDARD
            bmi < r[3] -> Level.OVERWEIGHT
            else -> Level.OBESE
        }
    }

    /** Standard weight range derived from the region's BMI range: H²/10000 · bmi_i. */
    fun standardWeightRange(heightCm: Double, region: Region): List<Double> =
        bmiRange(region).map { weightFromBmi(heightCm, it) }

    fun weightFromBmi(heightCm: Double, bmi: Double): Double =
        (heightCm * heightCm) / 10000.0 * bmi

    // Body fat %

    fun bodyFatRange(sex: Sex, ageYears: Int): List<Double> = when (sex) {
        Sex.MALE -> when {
            ageYears <= 39 -> listOf(5.0, 11.0, 17.0, 27.0, 45.0)
            ageYears <= 59 -> listOf(5.0, 12.0, 18.0, 28.0, 45.0)
            else -> listOf(5.0, 14.0, 20.0, 30.0, 45.0)
        }
        Sex.FEMALE -> when {
            ageYears <= 39 -> listOf(5.0, 21.0, 28.0, 40.0, 45.0)
            ageYears <= 59 -> listOf(5.0, 22.0, 29.0, 41.0, 45.0)
            else -> listOf(5.0, 23.0, 30.0, 42.0, 45.0)
        }
    }

    fun bodyFatLevel(bfr: Double, sex: Sex, ageYears: Int): Level {
        val r = bodyFatRange(sex, ageYears)
        return when {
            bfr < r[1] -> Level.LOW
            bfr < r[2] -> Level.STANDARD
            bfr < r[3] -> Level.HIGH
            else -> Level.OBESE
        }
    }

    /** Same thresholds expressed as kg of fat for the given weight. */
    fun bodyFatWeightRange(weightKg: Double, sex: Sex, ageYears: Int): List<Double> =
        bodyFatRange(sex, ageYears).map { it * weightKg / 100.0 }

    // Body water %

    fun waterRange(sex: Sex, ageYears: Int): List<Double> = when (sex) {
        Sex.MALE -> if (ageYears <= 30) listOf(37.8, 53.6, 57.0, 66.0) else listOf(37.8, 52.3, 55.6, 66.0)
        Sex.FEMALE -> if (ageYears <= 30) listOf(37.8, 49.5, 52.9, 66.0) else listOf(37.8, 48.1, 51.5, 66.0)
    }

    fun waterLevel(water: Double, sex: Sex, ageYears: Int): Level {
        val r = waterRange(sex, ageYears)
        return when {
            water < r[1] -> Level.LOW
            water <= r[2] -> Level.STANDARD
            else -> Level.HIGH
        }
    }

    // Visceral fat (axis 0–30)

    fun visceralFatRange(): List<Double> = listOf(1.0, 9.0, 14.0)

    fun visceralFatLevel(vfr: Double): Level = when {
        vfr < 1.0 -> Level.LOW
        vfr <= 9.0 -> Level.STANDARD
        vfr <= 14.0 -> Level.HIGH
        else -> Level.VERY_HIGH
    }

    // Bone mass kg

    fun boneMassBase(sex: Sex, ageYears: Int): Double = when (sex) {
        Sex.MALE -> when {
            ageYears <= 54 -> 2.4
            ageYears <= 75 -> 2.8
            else -> 3.1
        }
        Sex.FEMALE -> when {
            ageYears <= 39 -> 1.7
            ageYears <= 60 -> 2.1
            else -> 2.4
        }
    }

    fun boneMassRange(sex: Sex, ageYears: Int): List<Double> {
        val f = boneMassBase(sex, ageYears)
        // round1(1.3f) in the original yields 3.1/3.6/4.0/2.2/2.7 (float product
        // like 3.1200001 rounds down); the double product rounds identically
        return listOf(0.7 * f, f, round1(1.3 * f), 5.0)
    }

    fun boneMassLevel(boneKg: Double, sex: Sex, ageYears: Int): Level {
        val r = boneMassRange(sex, ageYears)
        var passed = 0
        for (i in 1 until r.size - 1) {
            if (boneKg >= r[i]) passed++
        }
        return when (passed) {
            0 -> Level.LOW
            1 -> Level.STANDARD
            else -> Level.HIGH
        }
    }

    // Skeletal muscle rate %

    fun skeletalMuscleRateRange(sex: Sex): List<Double> =
        if (sex == Sex.MALE) listOf(26.0, 31.0, 39.0, 45.0) else listOf(21.0, 25.0, 30.0, 35.0)

    fun skeletalMuscleRateLevel(rate: Double, sex: Sex): Level {
        val r = skeletalMuscleRateRange(sex)
        return when {
            rate < r[1] -> Level.LOW
            rate <= r[2] -> Level.STANDARD
            else -> Level.HIGH
        }
    }

    /** Same thresholds expressed as kg for the given weight. */
    fun skeletalMuscleWeightRange(weightKg: Double, sex: Sex): List<Double> =
        skeletalMuscleRateRange(sex).map { it * weightKg / 100.0 }

    // Muscle weight kg by height band

    fun muscleWeightRange(sex: Sex, heightCm: Double): List<Double> = when (sex) {
        Sex.MALE -> when {
            heightCm < 160.0 -> listOf(30.0, 38.5, 46.5, 54.0)
            heightCm <= 170.0 -> listOf(36.0, 44.0, 52.4, 60.0)
            else -> listOf(39.0, 49.4, 59.4, 69.0)
        }
        Sex.FEMALE -> when {
            heightCm < 150.0 -> listOf(10.0, 21.9, 34.7, 47.0)
            heightCm <= 160.0 -> listOf(27.0, 32.9, 37.5, 42.0)
            else -> listOf(30.0, 36.5, 42.5, 48.0)
        }
    }

    fun muscleWeightLevel(muscleKg: Double, sex: Sex, heightCm: Double): Level {
        val r = muscleWeightRange(sex, heightCm)
        return when {
            muscleKg < r[1] -> Level.LOW
            muscleKg <= r[2] -> Level.STANDARD
            else -> Level.HIGH
        }
    }

    /** Muscle-rate % — the weight thresholds expressed as % of [weightKg] (original's getMusclePercentStandardRange). */
    fun muscleRateLevel(ratePct: Double, weightKg: Double, sex: Sex, heightCm: Double): Level =
        muscleWeightLevel(ratePct * weightKg / 100.0, sex, heightCm)

    // BMR kcal

    fun bmrStandard(sex: Sex, ageYears: Int): Int {
        val bands = if (sex == Sex.MALE) {
            intArrayOf(700, 900, 1090, 1290, 1480, 1610, 1550, 1500, 1350, 1220)
        } else {
            intArrayOf(700, 860, 1000, 1180, 1340, 1300, 1210, 1170, 1110, 1010)
        }
        val limits = intArrayOf(2, 5, 8, 11, 14, 17, 29, 49, 69)
        for (i in limits.indices) {
            if (ageYears <= limits[i]) return bands[i]
        }
        return bands[9]
    }

    fun bmrRange(sex: Sex, ageYears: Int): List<Double> {
        val s = bmrStandard(sex, ageYears).toDouble()
        return listOf(0.95 * s, s * 1.05)
    }

    fun bmrLevel(bmr: Double, sex: Sex, ageYears: Int): Level {
        val s = bmrStandard(sex, ageYears).toDouble()
        return when {
            bmr <= 0.95 * s -> Level.LOW
            bmr <= s * 1.05 -> Level.STANDARD
            else -> Level.HIGH
        }
    }

    // Protein %

    fun proteinLevel(protein: Double): Level = when {
        protein < 16.0 -> Level.LOW
        protein > 20.0 -> Level.HIGH
        else -> Level.STANDARD
    }

    // Obesity degree %

    fun obesityDegreeLevel(od: Double): Level = when {
        od <= 10.0 && od >= -10.0 -> Level.STANDARD
        od >= 10.0 && od <= 20.0 -> Level.MILD
        od >= 20.0 && od <= 30.0 -> Level.MODERATE
        od < 30.0 || od > 50.0 -> if (od > 50.0) Level.EXTREME else Level.STANDARD
        else -> Level.SEVERE
    }

    // Lean body mass kg (h = height in m)

    fun leanBodyMassRange(heightCm: Double): List<Double> {
        val h = heightCm / 100.0
        return listOf(18.0 * h * h, 20.0 * h * h, 23.0 * h * h)
    }

    fun leanBodyMassLevel(lbmKg: Double, heightCm: Double): Level {
        val h = heightCm / 100.0
        val t18 = 18.0 * h * h
        return when {
            lbmKg <= t18 -> Level.LOW
            lbmKg <= h * 20.0 * h -> Level.STANDARD
            lbmKg < 20.0 * h * h || lbmKg > h * 23.0 * h ->
                if (lbmKg >= 23.0 * h * h) Level.VERY_HIGH else Level.LOW
            else -> Level.HIGH
        }
    }

    // Gauge dial helpers (needle positions on a 0–10 scale)

    /** Two-point ramp: 2 at/below [low], linear to 10 at [high], saturated above; 10 when value == 0. */
    fun gaugeTwoPoint(low: Double, high: Double, value: Double): Double {
        var code = 1.0
        if (value > low) {
            code = if (value < high) 1.0 + ((value - low) * 4.0) / (high - low) else 5.0
        }
        if (value == 0.0) {
            code = 5.0
        }
        return code / 5.0 * 10.0
    }

    /** Plateau: ramps up axisMin→rangeLow, flat 10 across rangeLow..rangeHigh, ramps down to axisMax. */
    fun gaugePlateau(axisMin: Double, rangeLow: Double, rangeHigh: Double, axisMax: Double, value: Double): Double {
        var code = 1.0
        if (value > axisMin && value <= axisMax) {
            code = when {
                value <= rangeLow -> 1.0 + ((value - axisMin) * 4.0) / (rangeLow - axisMin)
                value <= rangeHigh -> 5.0
                else -> 1.0 + ((axisMax - value) * 4.0) / (axisMax - rangeHigh)
            }
        }
        if (value == 0.0) {
            code = 5.0
        }
        return code / 5.0 * 10.0
    }

    /** Peak: ramps up axisMin to the midpoint of rangeLow..rangeHigh, then down to axisMax. */
    fun gaugePeak(axisMin: Double, rangeLow: Double, rangeHigh: Double, axisMax: Double, value: Double): Double {
        val peak = (rangeHigh + rangeLow) / 2.0
        var code = 1.0
        if (value > axisMin && value <= axisMax) {
            code = when {
                value <= peak -> 1.0 + ((value - axisMin) * 4.0) / (peak - axisMin)
                else -> 1.0 + ((axisMax - value) * 4.0) / (axisMax - peak)
            }
        }
        if (value == 0.0) {
            code = 5.0
        }
        return code / 5.0 * 10.0
    }

    /** Segment progress 0–100 across ascending [thresholds] (33.33 per segment, clamped). */
    fun levelPercent(value: Double, thresholds: List<Double>): Double {
        if (thresholds.size < 2) return 0.0
        var i = 0
        while (i < thresholds.size && value >= thresholds[i]) i++
        val base = if (i > 0) thresholds[i - 1] else 0.0
        val next = if (i >= thresholds.size) thresholds[thresholds.size - 1] * 2.0 else thresholds[i]
        val frac = ((value - base) / (next - base)).coerceIn(0.0, 1.0)
        return minOf(100.0, maxOf(0.0, (i + frac) * 33.333332))
    }
}
