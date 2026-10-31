package app.okoklibre.insights

import app.okoklibre.data.WeightRecordEntity
import kotlin.math.abs

enum class AttributionCause { HYDRATION_NOISE, FAT_CHANGE, MUSCLE_CHANGE, MIXED, UNCLEAR, WEIGHT_ONLY }

/** What changed between two readings. Composition deltas are null if either side was weight-only. */
data class ChangeAttribution(
    val cause: AttributionCause,
    val deltaWeightKg: Double,
    val deltaWaterPct: Double?,
    val deltaFatPct: Double?,
    val deltaFatMassKg: Double?,
    val deltaMuscleKg: Double?,
)

enum class ScoreComponent { BMI, FAT, MUSCLE, VISCERAL }

/** A score move pinned to the term of the real formula that moved it most. */
data class ScoreAttribution(
    val component: ScoreComponent,
    val deltaScore: Int,
    val componentPoints: Double,
)

/** Compares one reading with the one before it. Two readings say less than a trend, and the wording says so. */
object InsightEngine {

    // WHY: daily water% swings about ±1% on a BIA scale; a bigger shift is hydration, not tissue.
    private const val HYDRATION_DELTA_WATER_PCT = 1.0
    // WHY: a body can only hold or lose so much water in a day; past this, water isn't the story.
    private const val HYDRATION_MAX_WEIGHT_SHIFT_KG = 2.5
    // WHY: BIA fat% resolution is ~0.1-0.2 points; 0.3 clears the noise floor.
    private const val FAT_CHANGE_DELTA_PCT = 0.3
    // WHY: 0.3 kg of muscle is roughly one month of dedicated training — below that it's noise.
    private const val MUSCLE_CHANGE_DELTA_KG = 0.3

    fun changeAttribution(previous: WeightRecordEntity, current: WeightRecordEntity): ChangeAttribution {
        val deltaWeight = current.weightKg - previous.weightKg
        if (!previous.hasComposition || !current.hasComposition) {
            return ChangeAttribution(AttributionCause.WEIGHT_ONLY, deltaWeight, null, null, null, null)
        }
        val deltaWater = current.waterPct - previous.waterPct
        val deltaFat = current.bodyFatPct - previous.bodyFatPct
        val deltaFatMass = current.fatMassKg - previous.fatMassKg
        val deltaMuscle = current.muscleMassKg - previous.muscleMassKg
        val fatChanged = abs(deltaFat) >= FAT_CHANGE_DELTA_PCT
        val muscleChanged = abs(deltaMuscle) >= MUSCLE_CHANGE_DELTA_KG
        val cause = when {
            abs(deltaWater) >= HYDRATION_DELTA_WATER_PCT && abs(deltaWeight) <= HYDRATION_MAX_WEIGHT_SHIFT_KG ->
                AttributionCause.HYDRATION_NOISE
            abs(deltaWeight) > HYDRATION_MAX_WEIGHT_SHIFT_KG * 2 -> AttributionCause.UNCLEAR
            fatChanged && muscleChanged -> AttributionCause.MIXED
            fatChanged -> AttributionCause.FAT_CHANGE
            muscleChanged -> AttributionCause.MUSCLE_CHANGE
            else -> AttributionCause.UNCLEAR
        }
        return ChangeAttribution(cause, deltaWeight, deltaWater, deltaFat, deltaFatMass, deltaMuscle)
    }

    /** Null unless both readings carry a score and the score actually moved. */
    fun scoreAttribution(previous: WeightRecordEntity, current: WeightRecordEntity): ScoreAttribution? {
        if (!previous.hasComposition || !current.hasComposition) return null
        val deltaScore = current.score - previous.score
        if (deltaScore == 0) return null
        val before = BodyAnalysis.scoreParts(previous)
        val after = BodyAnalysis.scoreParts(current)
        val moves = mapOf(
            ScoreComponent.BMI to after.bmiWeight * after.bmiScore - before.bmiWeight * before.bmiScore,
            ScoreComponent.FAT to after.fatWeight * after.fatScore - before.fatWeight * before.fatScore,
            ScoreComponent.MUSCLE to after.muscleWeight * after.muscleScore - before.muscleWeight * before.muscleScore,
            ScoreComponent.VISCERAL to
                after.visceralWeight * after.visceralScore - before.visceralWeight * before.visceralScore,
        )
        val sign = if (deltaScore > 0) 1.0 else -1.0
        val (component, points) = moves.entries.maxBy { it.value * sign }
        return ScoreAttribution(component, deltaScore, points)
    }
}
