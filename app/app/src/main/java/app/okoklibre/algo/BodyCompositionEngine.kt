package app.okoklibre.algo

import kotlin.math.floor

enum class Sex(val code: Int) {
    MALE(1),
    FEMALE(0),
}

/**
 * The 11 body-shape types of the report matrix, with the English
 * labels from the original app's resources indexed by matrix value 0..10.
 */
enum class BodyShape(val type: Int, val label: String) {
    ATHLETE(0, "Athlete body type"),
    MUSCULAR_OBESITY(1, "Muscular obesity"),
    OBESITY(2, "Obesity"),
    MUSCULAR(3, "Muscular type"),
    HEALTHY(4, "Healthy type"),
    SLIGHTLY_OVERWEIGHT(5, "Slightly overweight"),
    SLIM_MUSCULAR(6, "Slim muscular type"),
    SLIM(7, "Slim"),
    HIDDEN_OBESITY(8, "Hidden obesity"),
    EMACIATED(9, "Emaciated"),
    SLIGHTLY_UNDERWEIGHT(10, "Slightly underweight"),
    ;

    companion object {
        fun fromType(type: Int): BodyShape? = entries.firstOrNull { it.type == type }
    }
}

data class CompositionInput(
    val heightCm: Double,
    val weightKg: Double,
    val sex: Sex,
    val ageYears: Int,
    val impedanceOhm: Double = 0.0,
    val previousImpedanceOhm: Double? = null,
    val minutesSincePrevious: Long? = null,
)

/**
 * Scale-computed composition block as sent by a classic fat scale (type 1) in
 * the locked 0xCA frame — no impedance involved.
 */
data class ScaleComputedInput(
    val heightCm: Double,
    val weightKg: Double,
    val sex: Sex,
    val ageYears: Int,
    val fatPct: Double,
    val waterPct: Double,
    val musclePct: Double,
    val bmrKcal: Double,
    val visceralFat: Double,
    val boneKg: Double,
)

data class CompositionResult(
    /** Stored BMI, round1 HALF_UP of Wt/H²·10000 (raw BMI is used internally for score/shape). */
    val bmi: Double,
    val bodyFatPct: Double,
    val fatMassKg: Double,
    /** Visceral fat level in half steps; 0 when age ≤ 17 or no impedance. */
    val visceralFat: Double,
    /** Body water %; computed at all ages (deliberate deviation from the original). */
    val waterPct: Double,
    val muscleMassKg: Double,
    val muscleRatePct: Double,
    val skeletalMuscleRatePct: Double,
    val skeletalMuscleKg: Double,
    val boneMassKg: Double,
    val bmrKcal: Double,
    /** 0 when age ≤ 17 or no impedance. */
    val bodyAge: Int,
    val idealWeightKg: Double,
    val obesityDegreePct: Double,
    val leanBodyMassKg: Double,
    val proteinPct: Double,
    /** 0 when age ≤ 17. */
    val bodyScore: Int,
    /** Null when no impedance metrics are available. */
    val bodyShape: BodyShape?,
    val weightControlKg: Double,
    val muscleControlKg: Double,
    val fatControlKg: Double,
    /** Impedance actually used after gating and smoothing. */
    val effectiveImpedanceOhm: Double,
) {
    val hasImpedanceMetrics: Boolean get() = effectiveImpedanceOhm > 0.0 && bodyFatPct != 0.0

    /**
     * True when the record carries body composition at all — either computed
     * from impedance or computed on the scale (type-1 scales, no impedance).
     */
    val hasBodyComposition: Boolean get() = effectiveImpedanceOhm > 0.0 || bodyFatPct > 0.0
}

/** The weighted terms of the body score. [raw] is the score before truncation and clamping. */
data class ScoreParts(
    val bmiScore: Double,
    val fatScore: Double,
    val muscleScore: Double,
    val visceralScore: Double,
    val countsVisceral: Boolean,
) {
    val bmiWeight: Double get() = if (countsVisceral) 0.40 else 0.48
    val fatWeight: Double get() = 0.4
    val muscleWeight: Double get() = 0.1
    val visceralWeight: Double get() = if (countsVisceral) 0.08 else 0.0

    val raw: Double
        get() = if (countsVisceral) {
            (0.40 * bmiScore) + (0.4 * fatScore) + (0.1 * muscleScore) + (0.08 * visceralScore)
        } else {
            (0.48 * bmiScore) + (0.4 * fatScore) + (0.1 * muscleScore)
        }
}

/**
 * Pure-Kotlin reimplementation of the OKOK body-composition algorithms.
 */
object BodyCompositionEngine {

    private val SHAPE_MATRIX = arrayOf(
        intArrayOf(9, 6, 3, 0),
        intArrayOf(10, 7, 3, 0),
        intArrayOf(10, 4, 4, 1),
        intArrayOf(8, 8, 5, 2),
    )

    fun compute(input: CompositionInput): CompositionResult {
        val h = input.heightCm
        val wt = input.weightKg
        val age = input.ageYears
        val sex = input.sex

        val bmiRaw = wt / (h * h) * 100.0 * 100.0

        var z = input.impedanceOhm
        if (wt < 20.0 || bmiRaw < 5.0) z = 0.0
        val prevZ = input.previousImpedanceOhm
        val dtMin = input.minutesSincePrevious
        if (z != 0.0 && prevZ != null && dtMin != null) {
            z = smoothImpedance(z, prevZ, dtMin)
        }

        val bw = idealWeight(sex, h)
        val wc = bw - wt
        val bm = wt * if (sex == Sex.MALE) 0.77 else 0.735

        if (z <= 0.0 || age <= 5) {
            val lbm = round2(wt)
            val mc = bm
            val fc = fatControl(sex, wt, bw, wc, mc, 0.0)
            return CompositionResult(
                bmi = round1(bmiRaw),
                bodyFatPct = 0.0,
                fatMassKg = 0.0,
                visceralFat = 0.0,
                waterPct = 0.0,
                muscleMassKg = 0.0,
                muscleRatePct = 0.0,
                skeletalMuscleRatePct = 0.0,
                skeletalMuscleKg = 0.0,
                boneMassKg = 0.0,
                bmrKcal = 0.0,
                bodyAge = 0,
                idealWeightKg = bw,
                obesityDegreePct = (wt - bw) / bw * 100.0,
                leanBodyMassKg = lbm,
                proteinPct = 0.0,
                bodyScore = 0,
                bodyShape = null,
                weightControlKg = wc,
                muscleControlKg = mc,
                fatControlKg = fc,
                effectiveImpedanceOhm = z,
            )
        }

        val bfrRaw = when (sex) {
            Sex.MALE -> ((h * -0.3315) + (0.6216 * wt) + (age * 1.0 * 0.0183) + (z * 0.0085) + 22.554) / wt * 100.0
            Sex.FEMALE -> ((h * -0.3332) + (0.7509 * wt) + (age * 1.0 * 0.0196) + (z * 0.0072) + 22.7193) / wt * 100.0
        }
        val bfr = round2(clamp(bfrRaw, 5.0, 45.0))
        val fm = bfr * wt / 100.0

        val vfr = if (age <= 17) {
            0.0
        } else {
            val v = when (sex) {
                Sex.MALE -> (h * -0.2675) + (wt * 0.42) + (age * 1.0 * 0.1462) + (z * 0.0123) + 13.9871
                Sex.FEMALE -> (h * -0.1651) + (wt * 0.2628) + (age * 1.0 * 0.0649) + (z * 0.0024) + 12.3445
            }
            var i = floor(v)
            if (v - i >= 0.5) i += 0.5
            clamp(i, 1.0, 59.0)
        }

        // Deliberate deviation from the original: water is computed at all ages.
        val waterRaw = when (sex) {
            Sex.MALE -> ((h * 0.0939) + (0.3758 * wt) - (age * 1.0 * 0.0032) - (z * 0.006925) + 0.097) / wt * 100.0
            Sex.FEMALE -> ((h * 0.0877) + (0.2973 * wt) + (age * 1.0 * 0.0128) - (z * 0.00603) + 0.5175) / wt * 100.0
        }
        val water = round2(clamp(waterRaw, 20.0, 85.0))

        val slmRaw = when {
            bfrRaw > 45.0 -> wt - (0.45 * wt) - 4.0
            bfrRaw < 5.0 -> wt - (0.05 * wt) - 1.0
            else -> {
                val r = when (sex) {
                    Sex.MALE -> ((h * 0.2867) + (wt * 0.3894) - (age * 1.0 * 0.0408) - (z * 0.01235)) - 15.7665
                    Sex.FEMALE -> ((h * 0.3186) + (wt * 0.1934) - (age * 1.0 * 0.0206) - (z * 0.0132)) - 16.4556
                }
                clamp(r, 20.0, 70.0)
            }
        }
        val slm = round2(slmRaw)
        // The original computes the rate from the unrounded SLM (getSLMPercent(0)).
        val muscleRate = round2(slmRaw / wt * 100.0)

        val mw = clamp(
            ((h * 0.2573) + (wt * 0.1745) - (age * 1.0 * 0.0161) + (sex.code * 2.4269) - (z * 0.017)) - 20.2165,
            20.0,
            70.0,
        )
        val mwl = mw / wt * 100.0
        val skeletalRate = if (round1(mwl) >= muscleRate) 0.0 else round1(mwl)
        val skeletalKg = wt * skeletalRate / 100.0

        val msw = clamp(wt - fm - slm, 1.0, 4.0)

        val bmr = round1(
            when (sex) {
                Sex.MALE -> ((h * 7.5037) + (wt * 13.1523) - (age * 1.0 * 4.3376) - (z * 0.3486)) - 311.7751
                Sex.FEMALE -> ((h * 7.5432) + (wt * 9.9474) - (age * 1.0 * 3.4382) - (z * 0.309)) - 288.2821
            },
        )

        val bodyAge = if (age <= 17) {
            0
        } else {
            val raw = when (sex) {
                Sex.MALE -> (h * -0.7471) + (wt * 0.9161) + (age * 1.0 * 0.4184) + (z * 0.0517) + 54.2267
                Sex.FEMALE -> (h * -1.1165) + (wt * 1.5784) + (age * 1.0 * 0.4615) + (z * 0.0415) + 83.2548
            }
            clampBodyAge(raw.toInt(), age)
        }

        val mc = bm - slm
        val fc = fatControl(sex, wt, bw, wc, mc, fm)
        val od = (wt - bw) / bw * 100.0
        val lbm = round2(wt - fm)
        val protein = if (age > 17) round1(round1(muscleRate) - water) else 0.0
        val score = if (age > 17) calScore(h, wt, sex, age, bfr, slm, vfr) else 0
        val shape = bodyShape(bfr, bmiRaw, sex, age)

        return CompositionResult(
            bmi = round1(bmiRaw),
            bodyFatPct = bfr,
            fatMassKg = fm,
            visceralFat = vfr,
            waterPct = water,
            muscleMassKg = slm,
            muscleRatePct = muscleRate,
            skeletalMuscleRatePct = skeletalRate,
            skeletalMuscleKg = skeletalKg,
            boneMassKg = msw,
            bmrKcal = bmr,
            bodyAge = bodyAge,
            idealWeightKg = bw,
            obesityDegreePct = od,
            leanBodyMassKg = lbm,
            proteinPct = protein,
            bodyScore = score,
            bodyShape = shape,
            weightControlKg = wc,
            muscleControlKg = mc,
            fatControlKg = fc,
            effectiveImpedanceOhm = z,
        )
    }

    /**
     * Scale-computed path: the scale already
     * sent fat/water/muscle/BMR/visceral/bone in its locked frame (type-1
     * scales never send impedance). Those values are taken as-is; the
     * remaining derived metrics are computed exactly as the original app does
     * without impedance:
     *
     * - body age via [calBodyAgeNoImpedance], protein via [calProteinNoImpedance]
     *   (both 0 when age ≤ 17, mirroring the impedance path's age gates);
     * - score via [calScore] with SLM_kg = muscle%·Wt/100 and VFR = 0 — the
     *   original's report score always passes 0
     *   for the visceral term;
     * - skeletal muscle stays 0 (requires impedance, as in the original);
     * - control values reuse the impedance-path formulas with SLM = scale
     *   muscle mass.
     */
    fun fromScaleComputed(input: ScaleComputedInput): CompositionResult {
        val h = input.heightCm
        val wt = input.weightKg
        val age = input.ageYears
        val sex = input.sex
        val bfr = input.fatPct

        val bmiRaw = wt / (h * h) * 100.0 * 100.0
        val fm = bfr * wt / 100.0
        val bw = idealWeight(sex, h)
        val wc = bw - wt
        val bm = wt * if (sex == Sex.MALE) 0.77 else 0.735
        val muscleKg = input.musclePct * wt / 100.0
        val mc = bm - muscleKg
        val fc = fatControl(sex, wt, bw, wc, mc, fm)

        val bodyAge = if (age > 17) calBodyAgeNoImpedance(h, wt, age, bfr) else 0
        // No age gate on the no-impedance protein branch.
        val protein = calProteinNoImpedance(wt, sex, bfr, input.waterPct)
        val score = if (age > 17) calScore(h, wt, sex, age, bfr, muscleKg, 0.0) else 0

        return CompositionResult(
            bmi = round1(bmiRaw),
            bodyFatPct = bfr,
            fatMassKg = fm,
            visceralFat = input.visceralFat,
            waterPct = input.waterPct,
            muscleMassKg = muscleKg,
            muscleRatePct = input.musclePct,
            skeletalMuscleRatePct = 0.0,
            skeletalMuscleKg = 0.0,
            boneMassKg = input.boneKg,
            bmrKcal = input.bmrKcal,
            bodyAge = bodyAge,
            idealWeightKg = bw,
            obesityDegreePct = (wt - bw) / bw * 100.0,
            leanBodyMassKg = round2(wt - fm),
            proteinPct = protein,
            bodyScore = score,
            bodyShape = bodyShape(bfr, bmiRaw, sex, age),
            weightControlKg = wc,
            muscleControlKg = mc,
            fatControlKg = fc,
            effectiveImpedanceOhm = 0.0,
        )
    }

    /** Impedance smoothing ladder. */
    fun smoothImpedance(now: Double, previous: Double, minutesBetween: Long): Double {
        if (now != 0.0 && previous != 0.0 && now != previous && minutesBetween <= 30) {
            val d = now - previous
            val a = kotlin.math.abs(d)
            return when {
                a <= 24.0 -> previous + d / 16.0
                a <= 32.0 -> previous + d / 4.0
                a <= 64.0 -> previous + d / 2.0
                a <= 89.0 -> (previous + now * 3.0) / 4.0
                else -> now
            }
        }
        return now
    }

    fun idealWeight(sex: Sex, heightCm: Double): Double =
        if (sex == Sex.MALE) (heightCm - 80.0) * 0.7 else (heightCm - 70.0) * 0.6

    /** Body score. [muscleKg] is the rounded SLM; [visceralFat] the rounded VFR. */
    fun calScore(
        heightCm: Double,
        weightKg: Double,
        sex: Sex,
        ageYears: Int,
        bfr: Double,
        muscleKg: Double,
        visceralFat: Double,
    ): Int = clamp(scoreParts(heightCm, weightKg, sex, ageYears, bfr, muscleKg, visceralFat).raw.toInt(), 45, 100)

    /** The four weighted terms behind [calScore], exposed so analysis can explain a score exactly. */
    fun scoreParts(
        heightCm: Double,
        weightKg: Double,
        sex: Sex,
        ageYears: Int,
        bfr: Double,
        muscleKg: Double,
        visceralFat: Double,
    ): ScoreParts {
        val bmi = weightKg / (heightCm * heightCm) * 100.0 * 100.0
        val bmiScore = clamp(((bmi * bmi) * -5.686) + (bmi * 241.7) - 2470.0, 55.0, 96.0)
        val bfAdj = bfr + ageYears * 0.03
        val bf2 = bfAdj * bfAdj
        val bf3 = bf2 * bfAdj
        val bfScore: Double
        val k: Double
        when (sex) {
            Sex.MALE -> {
                bfScore = maxOf(
                    ((bf3 * bfAdj) * 1.085E-4) - (bf3 * 0.003181) - (bf2 * 0.2952) + (bfAdj * 10.85) + 0.4248,
                    55.0,
                )
                k = 0.77
            }
            Sex.FEMALE -> {
                bfScore = maxOf(
                    ((bf3 * bfAdj) * 2.469E-4) - (bf3 * 0.02788) + (bf2 * 0.9597) - (bfAdj * 10.02) + 80.42,
                    55.0,
                )
                k = 0.735
            }
        }
        val muscleScore = minOf(muscleKg + 90.0 - weightKg * k, 100.0)
        val vfScore = if (visceralFat >= 15.0) {
            -50.0
        } else {
            val v2 = visceralFat * visceralFat
            val v3 = v2 * visceralFat
            val v4 = v3 * visceralFat
            maxOf(
                ((v4 * visceralFat) * 0.007212) - (v4 * 0.2825) + (v3 * 3.912) - (v2 * 22.27) +
                    (30.38 * visceralFat) + 89.35,
                -50.0,
            )
        }
        return ScoreParts(bmiScore, bfScore, muscleScore, vfScore, countsVisceral = visceralFat != 0.0)
    }

    /** No-impedance body-age variant, used when only fat % is known. */
    fun calBodyAgeNoImpedance(heightCm: Double, weightKg: Double, ageYears: Int, bfr: Double): Int {
        val raw = floor(
            (heightCm * -0.2387) + (weightKg * 0.2258) + (ageYears * 0.3452) + (bfr * 1.2675) + 9.5081 + 0.5,
        ).toInt()
        return clampBodyAge(raw, ageYears)
    }

    /**
     * No-impedance protein %, used when fat % and water are known:
     * Rounded 1-dp HALF_UP as the original always does
     * (DecimalFormatUtils.getOneFloat) — and with no age gate on this branch.
     */
    fun calProteinNoImpedance(weightKg: Double, sex: Sex, bfr: Double, waterPct: Double): Double =
        round1(100.0 - bfr - waterPct - ((if (sex == Sex.MALE) 3.0 else 2.5) / weightKg) * 100.0)

    /** Report body-shape matrix lookup. Null when fat or BMI is unknown. */
    fun bodyShape(bfr: Double, bmi: Double, sex: Sex, ageYears: Int): BodyShape? {
        if (bfr <= 0.0 || bmi <= 0.0) return null
        val t = shapeFatThresholds(sex)
        val fatGrade = when {
            bfr < t[0] -> 0
            bfr < t[1] -> 1
            bfr < t[2] -> 2
            else -> 3
        }
        val bmiGrade = when {
            bmi < 18.5 -> 0
            bmi < 24.0 -> if (bmi < 21.25) 1 else 2
            else -> 3
        }
        return BodyShape.fromType(SHAPE_MATRIX[fatGrade][bmiGrade])
    }

    /**
     * Shape-matrix fat thresholds t1/t2/t3: built by the report from
     * `getAxungeStandardRange` indices 1–2, which are age-independent —
     * male {10, 15, 20}, female {18, 23, 28}. (The age-banded 11/22/27-style
     * values belong to a different classifier.)
     */
    fun shapeFatThresholds(sex: Sex): DoubleArray = when (sex) {
        Sex.MALE -> doubleArrayOf(10.0, 15.0, 20.0)
        Sex.FEMALE -> doubleArrayOf(18.0, 23.0, 28.0)
    }

    private fun fatControl(sex: Sex, wt: Double, bw: Double, wc: Double, mc: Double, fm: Double): Double {
        if (wt < bw) return wc - mc
        return if (sex == Sex.MALE) {
            ((wt + mc) * 0.15 - fm) / 0.85
        } else {
            ((wt + mc) * 0.20 - fm) / 0.80
        }
    }

    private fun clampBodyAge(bodyAge: Int, age: Int): Int {
        var ba = bodyAge
        if (ba - age > 10) {
            ba = age + 10
        } else if (age - ba > 10) {
            ba = age - 10
        }
        if (ba < 18) ba = 18
        if (ba > 80) ba = 80
        return ba
    }

    private fun clamp(value: Int, min: Int, max: Int): Int = minOf(maxOf(value, min), max)
}
