package app.okoklibre.ui.reading

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.okoklibre.R
import app.okoklibre.algo.CompositionResult
import app.okoklibre.algo.Grading
import app.okoklibre.algo.WeightUnit
import app.okoklibre.ble.protocol.PropertyByte
import app.okoklibre.data.WeightRecordEntity
import app.okoklibre.insights.hasComposition
import app.okoklibre.ui.common.formatWeightKg
import app.okoklibre.ui.common.levelLabel
import app.okoklibre.ui.common.weightUnitLabel

/** One index of the report: its value, where that sits, and whether it's a concern. */
data class MetricRow(
    val title: String,
    val value: String,
    val levelText: String?,
    val concern: Boolean,
    val raw: Double,
    val low: Double?,
    val high: Double?,
    val rangeText: String?,
    val about: String?,
)

private fun one(value: Double) = "%.1f".format(value)

private val Grading.Level.isLow get() = this == Grading.Level.LOW || this == Grading.Level.THIN

/**
 * Every index the original report shows. [lowIsBad] picks which end
 * counts as a concern, because a high muscle reading is good news, not a warning.
 */
@Composable
fun rememberMetricRows(
    record: WeightRecordEntity,
    result: CompositionResult,
    unit: WeightUnit,
    region: Grading.Region,
): List<MetricRow> {
    val unitLabel = weightUnitLabel(unit)
    val pct = stringResource(R.string.unit_percent)
    val kcal = stringResource(R.string.unit_kcal)
    val bpm = stringResource(R.string.unit_bpm)
    val sex = record.sex
    val age = record.ageYears
    val height = record.heightCm
    val weight = record.weightKg
    val digits = PropertyByte.displayDigits(record.scaleProperty)
    val w: (Double) -> String = { "${formatWeightKg(it, unit, minOf(digits, 2))} $unitLabel" }
    val p: (Double) -> String = { "${one(it)} $pct" }
    val k: (Double) -> String = { "${it.toInt()} $kcal" }
    val n: (Double) -> String = ::one

    val levels = levelNames()

    @Composable
    fun row(
        titleRes: Int,
        raw: Double,
        format: (Double) -> String,
        level: Grading.Level?,
        low: Double?,
        high: Double?,
        aboutRes: Int?,
        concernWhen: (Grading.Level) -> Boolean = { it != Grading.Level.STANDARD },
    ) = MetricRow(
        title = stringResource(titleRes),
        value = format(raw),
        levelText = level?.let { levels.getValue(it) },
        concern = level?.let(concernWhen) ?: false,
        raw = raw,
        low = low,
        high = high,
        rangeText = if (low != null && high != null) "${format(low)} – ${format(high)}" else null,
        about = aboutRes?.let { stringResource(it) },
    )

    val lowBad: (Grading.Level) -> Boolean = { it.isLow }

    val composed = record.hasComposition

    return buildList {
        val bmiLevel = Grading.bmiLevel(record.bmi, region)
        val weights = Grading.standardWeightRange(height, region)
        add(row(R.string.metric_weight, weight, w, bmiLevel, weights[1], weights[2], null))
        val bmi = Grading.bmiRange(region)
        add(row(R.string.metric_bmi, record.bmi, n, bmiLevel, bmi[1], bmi[2], R.string.about_bmi))
        if (composed) {
            val fatLevel = Grading.bodyFatLevel(result.bodyFatPct, sex, age)
            val fat = Grading.bodyFatRange(sex, age)
            add(row(R.string.metric_body_fat, result.bodyFatPct, p, fatLevel, fat[1], fat[2], R.string.about_fat))
            val fatKg = Grading.bodyFatWeightRange(weight, sex, age)
            add(row(R.string.metric_fat_weight, result.fatMassKg, w, fatLevel, fatKg[1], fatKg[2], null))
            if (result.effectiveImpedanceOhm > 0.0) {
                val smLevel = Grading.skeletalMuscleRateLevel(result.skeletalMuscleRatePct, sex)
                val sm = Grading.skeletalMuscleRateRange(sex)
                add(
                    row(
                        R.string.metric_skeletal_muscle_rate, result.skeletalMuscleRatePct, p, smLevel,
                        sm[1], sm[2], R.string.about_skeletal, lowBad,
                    ),
                )
                val smKg = Grading.skeletalMuscleWeightRange(weight, sex)
                add(row(R.string.metric_skeletal_muscle, result.skeletalMuscleKg, w, smLevel, smKg[1], smKg[2], null, lowBad))
            }
            val muscleLevel = Grading.muscleWeightLevel(result.muscleMassKg, sex, height)
            val muscle = Grading.muscleWeightRange(sex, height)
            add(row(R.string.metric_muscle, result.muscleMassKg, w, muscleLevel, muscle[1], muscle[2], R.string.about_muscle, lowBad))
            add(
                row(
                    R.string.metric_muscle_rate, result.muscleRatePct, p, muscleLevel,
                    muscle[1] / weight * 100.0, muscle[2] / weight * 100.0, null, lowBad,
                ),
            )
            val waterLevel = Grading.waterLevel(result.waterPct, sex, age)
            val water = Grading.waterRange(sex, age)
            add(row(R.string.metric_water, result.waterPct, p, waterLevel, water[1], water[2], R.string.about_water, lowBad))
            add(
                row(
                    R.string.metric_water_weight, weight * result.waterPct / 100.0, w, waterLevel,
                    water[1] * weight / 100.0, water[2] * weight / 100.0, null, lowBad,
                ),
            )
            val visceral = Grading.visceralFatRange()
            if (result.visceralFat > 0.0) {
                add(
                    row(
                        R.string.metric_visceral_fat, result.visceralFat, n, Grading.visceralFatLevel(result.visceralFat),
                        visceral[0], visceral[1], R.string.about_visceral,
                    ) { !it.isLow && it != Grading.Level.STANDARD },
                )
            }
            val bone = Grading.boneMassRange(sex, age)
            add(row(R.string.metric_bone, result.boneMassKg, w, Grading.boneMassLevel(result.boneMassKg, sex, age), bone[1], bone[2], null, lowBad))
            val bmr = Grading.bmrRange(sex, age)
            add(row(R.string.metric_bmr, result.bmrKcal, k, Grading.bmrLevel(result.bmrKcal, sex, age), bmr[0], bmr[1], R.string.about_bmr, lowBad))
            // Not computed under 18, and 0 there means unknown.
            if (result.proteinPct > 0.0) {
                add(row(R.string.metric_protein, result.proteinPct, p, Grading.proteinLevel(result.proteinPct), 16.0, 20.0, null, lowBad))
            }
        }
        add(
            row(
                R.string.metric_obesity_degree, result.obesityDegreePct, p,
                Grading.obesityDegreeLevel(result.obesityDegreePct), -10.0, 10.0, null,
            ),
        )
        if (composed && result.bodyAge > 0) {
            add(
                MetricRow(
                    title = stringResource(R.string.metric_body_age),
                    value = result.bodyAge.toString(),
                    levelText = null,
                    concern = false,
                    raw = result.bodyAge.toDouble(),
                    low = null,
                    high = null,
                    rangeText = null,
                    about = stringResource(R.string.about_body_age, age),
                ),
            )
        }
        // Without composition the engine reports the whole weight as lean, which says nothing.
        if (composed) {
            val lean = Grading.leanBodyMassRange(height)
            add(
                row(
                    R.string.metric_lean_body_mass, result.leanBodyMassKg, w,
                    Grading.leanBodyMassLevel(result.leanBodyMassKg, height), lean[0], lean[1], null, lowBad,
                ),
            )
        }
        record.heartRateBpm?.let { rate ->
            val level = when {
                rate < 60 -> Grading.Level.LOW
                rate > 100 -> Grading.Level.HIGH
                else -> Grading.Level.STANDARD
            }
            add(row(R.string.metric_heart_rate, rate.toDouble(), { "${it.toInt()} $bpm" }, level, 60.0, 100.0, null))
        }
    }
}

@Composable
private fun levelNames(): Map<Grading.Level, String> = Grading.Level.entries.associateWith { level ->
    levelLabel(level)
}
