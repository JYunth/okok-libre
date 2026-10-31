package app.okoklibre.algo

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * BigDecimal HALF_UP rounding on the decimal representation of [value],
 * matching the original app's `new BigDecimal(Double.toString(x)).setScale(n, 4)`.
 */
internal fun roundHalfUp(value: Double, decimals: Int): Double =
    BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()

internal fun round1(value: Double): Double = roundHalfUp(value, 1)
internal fun round2(value: Double): Double = roundHalfUp(value, 2)
internal fun round3(value: Double): Double = roundHalfUp(value, 3)

internal fun clamp(value: Double, min: Double, max: Double): Double =
    minOf(maxOf(value, min), max)
