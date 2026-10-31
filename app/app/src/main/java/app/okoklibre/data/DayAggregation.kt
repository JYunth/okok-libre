package app.okoklibre.data

import app.okoklibre.algo.Aggregator
import app.okoklibre.algo.FieldAverages

/**
 * Pure day-aggregate computation: arithmetic mean of
 * the day's measurements per field, excluding zero values, rounded HALF_UP to
 * 2 decimals. Kept Android-free so it is unit-testable on the JVM.
 */
object DayAggregation {

    /**
     * Returns the day aggregate for [records] (already filtered to one role
     * and one day), or null when there is nothing to aggregate — the caller
     * should then delete any existing aggregate row for that day.
     */
    fun compute(roleId: Long, dateEpochDay: Long, records: List<WeightRecordEntity>): DayAggregateEntity? {
        val active = records.filter { !it.deleted }
        if (active.isEmpty()) return null
        val averages = Aggregator.average(active.map(::fieldsOf))
        return DayAggregateEntity(
            roleId = roleId,
            dateEpochDay = dateEpochDay,
            weightKg = averages.weightKg,
            bmi = averages.bmi,
            bodyFatPct = averages.bodyFatPct,
            boneKg = averages.boneMassKg,
            musclePct = averages.muscleRatePct,
            waterPct = averages.waterPct,
            bmrKcal = averages.bmrKcal,
            bodyAge = averages.bodyAge,
            visceralFat = averages.visceralFat,
        )
    }

    fun fieldsOf(record: WeightRecordEntity): FieldAverages = FieldAverages(
        weightKg = record.weightKg,
        bmi = record.bmi,
        bodyFatPct = record.bodyFatPct,
        boneMassKg = record.boneKg,
        muscleRatePct = record.musclePct,
        waterPct = record.waterPct,
        bmrKcal = record.bmrKcal,
        bodyAge = record.bodyAge.toDouble(),
        visceralFat = record.visceralFat,
    )
}
