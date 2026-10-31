package app.okoklibre.algo

import kotlin.math.abs

/**
 * The per-field averages of a measurement set. A field of 0 means "no data"
 * and is excluded from means.
 */
data class FieldAverages(
    val weightKg: Double = 0.0,
    val bmi: Double = 0.0,
    val bodyFatPct: Double = 0.0,
    val boneMassKg: Double = 0.0,
    val muscleRatePct: Double = 0.0,
    val waterPct: Double = 0.0,
    val bmrKcal: Double = 0.0,
    val bodyAge: Double = 0.0,
    val visceralFat: Double = 0.0,
)

/**
 * Day/week/month rollups: arithmetic means per field excluding zero
 * values, rounded HALF_UP to 2 decimals. Week and month records are means of
 * the contained day records, i.e. the same [average] applied to day aggregates.
 */
object Aggregator {

    fun meanExcludingZeros(values: List<Double>): Double {
        var sum = 0.0
        var count = 0
        for (v in values) {
            if (v != 0.0) {
                sum += v
                count++
            }
        }
        return if (count == 0) 0.0 else round2(sum / count)
    }

    fun average(records: List<FieldAverages>): FieldAverages = FieldAverages(
        weightKg = meanExcludingZeros(records.map { it.weightKg }),
        bmi = meanExcludingZeros(records.map { it.bmi }),
        bodyFatPct = meanExcludingZeros(records.map { it.bodyFatPct }),
        boneMassKg = meanExcludingZeros(records.map { it.boneMassKg }),
        muscleRatePct = meanExcludingZeros(records.map { it.muscleRatePct }),
        waterPct = meanExcludingZeros(records.map { it.waterPct }),
        bmrKcal = meanExcludingZeros(records.map { it.bmrKcal }),
        bodyAge = meanExcludingZeros(records.map { it.bodyAge }),
        visceralFat = meanExcludingZeros(records.map { it.visceralFat }),
    )

    /** Week/month rollup: mean of the period's day aggregates. */
    fun averageDays(dayAggregates: List<FieldAverages>): FieldAverages = average(dayAggregates)
}

/**
 * Role attribution: roles whose last recorded
 * weight is within ±3 kg (inclusive) of [newWeightKg], ordered by closeness.
 * Empty when nobody matches (the app then asks the user).
 */
fun matchRole(lastWeightByRole: Map<Int, Double>, newWeightKg: Double): List<Int> =
    lastWeightByRole.entries
        .filter { abs(it.value - newWeightKg) <= 3.0 }
        .sortedBy { abs(it.value - newWeightKg) }
        .map { it.key }
