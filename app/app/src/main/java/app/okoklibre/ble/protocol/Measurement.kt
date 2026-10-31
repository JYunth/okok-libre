package app.okoklibre.ble.protocol

import kotlin.math.min

/**
 * A decoded weighing.
 *
 * Body-composition fields carry the range clamps applied by the original app: out-of-range values become 0. The raw visceral-fat wire
 * value is preserved in [oriVisceralFat] because 0x6400 (25600) is the sentinel
 * for "scale did not compute fat" used by device-type detection.
 */
data class Measurement(
    val weightKg: Double,
    val lockFlag: Boolean,
    val fatPct: Double = 0.0,
    val waterPct: Double = 0.0,
    val musclePct: Double = 0.0,
    val bmrKcal: Int = 0,
    val visceralFat: Double = 0.0,
    /** Raw visceral-fat wire value (×10), before clamping. */
    val oriVisceralFat: Int = 0,
    val boneKg: Double = 0.0,
    val impedanceOhm: Double = 0.0,
    val heartRateBpm: Int? = null,
    val timestampEpochSec: Long? = null,
    val roleId: Int? = null,
    /** The scaleProperty byte as received on the wire (0..255). */
    val scaleProperty: Int,
    /** Display digit precision resolved by the codec from the property byte. */
    val decimals: Int = 2,
    val isHistory: Boolean = false,
    /** CloudV4: heart-rate measurement still in progress (property bit7). */
    val heartRateMeasuring: Boolean = false,
    /** CloudV4: impedance measurement still in progress (property bit6). */
    val impedanceMeasuring: Boolean = false,
    /**
     * The weight string exactly as the scale displays it (its own unit and
     * digit precision). Set on
     * locked/history frames.
     */
    val scaleWeightString: String? = null,
    /**
     * The raw frame that produced this measurement (for multi-packet records,
     * the reassembled record bytes). Set on locked/history frames; persisted
     * as hex for diagnostics.
     */
    val rawFrame: ByteArray? = null,
) {
    /** Lowercase hex of [rawFrame], null when absent. */
    val rawFrameHex: String?
        get() = rawFrame?.joinToString("") { "%02x".format(it) }

    companion object {
        /** fat% ×10 → %, clamped to [5, 45]; ≥65535 (unset) → 0. */
        fun fatFromRaw(raw: Int): Double {
            if (raw >= 65535) return 0.0
            val v = raw / 10.0
            return if (v in 5.0..45.0) v else 0.0
        }

        /** water% ×10 → %, clamped to [20, 85]; 100 (and above) → 0. */
        fun waterFromRaw(raw: Int): Double {
            val v = raw / 10.0
            return if (v >= 100.0 || v < 20.0 || v > 85.0) 0.0 else v
        }

        /** muscle% ×10 → %, <100 kept, ≥100 → 0. */
        fun muscleFromRaw(raw: Int): Double {
            val v = raw / 10.0
            return if (v >= 100.0) 0.0 else v
        }

        /** BMR kcal; ≥65535 (unset) → 0, ≥5000 → ÷10. */
        fun bmrFromRaw(raw: Int): Int = when {
            raw >= 65535 -> 0
            raw >= 5000 -> raw / 10
            else -> raw
        }

        /** visceral ×10 → clamped to ≤59 (≥100 or >59 → 0). */
        fun visceralFromRaw(raw: Int): Double {
            val v = raw / 10.0
            return if (v >= 100.0 || v > 59.0) 0.0 else v
        }

        /** bone kg ×10 → kg, clamped to [1, 4]; ≥65535 (unset) → 0. */
        fun boneFromRaw(raw: Int): Double {
            if (raw >= 65535) return 0.0
            val v = raw / 10.0
            return if (v < 1.0) 0.0 else min(v, 4.0)
        }
    }
}
