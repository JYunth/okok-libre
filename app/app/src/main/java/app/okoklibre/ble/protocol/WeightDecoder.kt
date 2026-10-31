package app.okoklibre.ble.protocol

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Raw 16-bit weight → kg conversion.
 *
 * `value = raw / 10^decimals`, then converted to kg per the property byte's unit:
 * kg as-is, jin ×0.5, lb ×0.4535924, st:lb = (st×14 + lb) × 0.4535924.
 * For ST the two weight bytes are not a plain integer: the high byte is whole
 * stones and the low byte is pounds ×10.
 *
 * Records with a scaled weight (kg ×10) ≤ 0 or ≥ [MAX_SCALED_WEIGHT] are
 * rejected (decode returns null).
 */
object WeightDecoder {

    /** Rejection bound in the scaled domain (600 kg × 10). */
    const val MAX_SCALED_WEIGHT = 6000.0

    private const val LB_TO_KG = 0.4535924

    /** Wire order of the two weight bytes. */
    enum class Endianness { BIG, LITTLE }

    /**
     * Decode the two weight bytes ([first], [second] in wire order) to kg.
     * Returns null when the decoded weight is outside the valid domain.
     */
    fun decodeToKg(
        first: Byte,
        second: Byte,
        property: Int,
        decimals: Int,
        endianness: Endianness = Endianness.BIG,
    ): Double? {
        val hi = if (endianness == Endianness.BIG) first.toInt() and 0xFF else second.toInt() and 0xFF
        val lo = if (endianness == Endianness.BIG) second.toInt() and 0xFF else first.toInt() and 0xFF
        val kg = when (PropertyByte.unit(property)) {
            WeightUnit.ST -> {
                // hi = whole stones, lo = pounds ×10
                val pounds = hi * 14 + lo / 10.0
                round(round(pounds * LB_TO_KG, 3), decimals)
            }
            WeightUnit.JIN -> round(round(rawValue(hi, lo, decimals), decimals) * 0.5, decimals)
            WeightUnit.LB -> round(round(rawValue(hi, lo, decimals), decimals) * LB_TO_KG, decimals)
            WeightUnit.KG -> round(rawValue(hi, lo, decimals), decimals)
        }
        val scaled = kg * 10.0
        return if (scaled <= 0.0 || scaled >= MAX_SCALED_WEIGHT) null else kg
    }

    private fun rawValue(hi: Int, lo: Int, decimals: Int): Double {
        val raw = (hi shl 8) or lo
        return raw / pow10(decimals)
    }

    /**
     * The weight string exactly as the scale displays it, in the scale's own
     * unit and digit precision:
     * ST → `"<stones>:<pounds×10 / 10>"`, else the raw value with [decimals]
     * digits (0 dp → integer string).
     */
    fun scaleWeightString(
        first: Byte,
        second: Byte,
        property: Int,
        decimals: Int,
        endianness: Endianness = Endianness.BIG,
    ): String {
        val hi = if (endianness == Endianness.BIG) first.toInt() and 0xFF else second.toInt() and 0xFF
        val lo = if (endianness == Endianness.BIG) second.toInt() and 0xFF else first.toInt() and 0xFF
        if (PropertyByte.unit(property) == WeightUnit.ST) return "$hi:${lo / 10.0}"
        val value = round(rawValue(hi, lo, decimals), decimals)
        return when (decimals) {
            0 -> value.toInt().toString()
            1 -> String.format(java.util.Locale.US, "%.1f", value)
            else -> String.format(java.util.Locale.US, "%.2f", value)
        }
    }

    private fun pow10(decimals: Int): Double = when (decimals) {
        0 -> 1.0
        1 -> 10.0
        else -> 100.0
    }

    private fun round(value: Double, decimals: Int): Double =
        BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()
}
