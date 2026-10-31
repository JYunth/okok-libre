package app.okoklibre.insights

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** One calendar day's value of a series. */
data class DayValue(val epochDay: Long, val value: Double)

/** The estimated true level on one day, with its uncertainty. */
data class SmoothedDay(
    val epochDay: Long,
    val observed: Double,
    val level: Double,
    val levelSd: Double,
    val slopePerDay: Double,
)

/** A difference between two days of a trend. Conservative: endpoint errors are treated as independent. */
data class Change(val delta: Double, val sd: Double) {
    /** Clears both a 90% confidence bar and the smallest change worth naming. */
    fun isReal(floor: Double): Boolean = abs(delta) >= max(floor, CONFIDENCE_Z * sd)

    private companion object {
        const val CONFIDENCE_Z = 1.64
    }
}

/** When a straight-line continuation of the trend reaches a target, with an 80% window. */
data class Crossing(
    val epochDay: Long,
    val earliestEpochDay: Long,
    /** Null when the slower end of the slope's range would never arrive. */
    val latestEpochDay: Long?,
)

/** A fitted trend. [days] is smoothed using all the data, so history doesn't wobble as new readings land. */
class TrendFit(
    val days: List<SmoothedDay>,
    val slopePerDay: Double,
    val slopeSd: Double,
    val noiseSd: Double,
) {
    val first: SmoothedDay get() = days.first()
    val last: SmoothedDay get() = days.last()
    val level: Double get() = last.level
    val spanDays: Long get() = last.epochDay - first.epochDay

    /** The latest smoothed day at or before [epochDay], or the first day when earlier than all of them. */
    fun dayAt(epochDay: Long): SmoothedDay = days.lastOrNull { it.epochDay <= epochDay } ?: first

    fun change(fromEpochDay: Long, toEpochDay: Long = last.epochDay): Change {
        val from = dayAt(fromEpochDay)
        val to = dayAt(toEpochDay)
        return Change(to.level - from.level, sqrt(from.levelSd * from.levelSd + to.levelSd * to.levelSd))
    }

    fun crossing(target: Double): Crossing? {
        val gap = target - level
        val direction = if (gap >= 0) 1.0 else -1.0
        val towards = slopePerDay * direction
        if (gap == 0.0 || towards <= MIN_SLOPE_PER_DAY) return null
        val distance = abs(gap)
        val fast = towards + ETA_Z * slopeSd
        val slow = towards - ETA_Z * slopeSd
        fun arrival(slope: Double) = last.epochDay + (distance / slope).toLong()
        return Crossing(
            epochDay = arrival(towards),
            earliestEpochDay = arrival(fast),
            latestEpochDay = if (slow > MIN_SLOPE_PER_DAY) arrival(slow) else null,
        )
    }

    private companion object {
        /** Below this a date would be fiction: 0.05 kg a week. */
        const val MIN_SLOPE_PER_DAY = 0.05 / 7.0
        const val ETA_Z = 1.28
    }
}

/**
 * Local-linear-trend Kalman smoother for irregularly spaced daily values.
 *
 * Bathroom-scale data is a slowly moving body under fast water noise, and people
 * skip days. A state-space model handles the gaps honestly: uncertainty grows while
 * nobody weighs in, which a rolling window can't express. The smoother then gives
 * a level and a rate for every day, each with a standard deviation that the rest
 * of the app uses to decide whether a change is worth claiming.
 */
object TrendModel {

    /**
     * How quickly the true rate may drift, in kg²/day³. At this value the rate can
     * move about 0.1 kg/week over a fortnight, which matches how bodies respond to a
     * change in diet or training while still averaging over roughly two weeks of noise.
     */
    private const val SLOPE_DRIFT = 1.5e-5

    /** Prior on the starting rate: 0.1 kg/day either way. */
    private const val SLOPE_PRIOR_VARIANCE = 0.01

    private const val MIN_DAYS = 3
    private const val DEFAULT_NOISE_SD = 0.5
    private const val MIN_NOISE_SD = 0.1
    private const val MAX_NOISE_SD = 1.5

    /** Readings further than this from the prediction are down-weighted rather than believed. */
    private const val OUTLIER_Z = 3.0

    private const val MAD_TO_SD = 1.4826
    private val SQRT_2 = sqrt(2.0)

    private class Gaussian2(
        val level: Double,
        val slope: Double,
        val p00: Double,
        val p01: Double,
        val p11: Double,
    )

    /** [unitsPerKg] brings metrics on other scales, such as kcal, into the range the model is tuned for. */
    fun fit(input: List<DayValue>, unitsPerKg: Double = 1.0): TrendFit? {
        val scaled = fitKg(input.map { it.copy(value = it.value / unitsPerKg) }) ?: return null
        if (unitsPerKg == 1.0) return scaled
        return TrendFit(
            scaled.days.map {
                it.copy(
                    observed = it.observed * unitsPerKg,
                    level = it.level * unitsPerKg,
                    levelSd = it.levelSd * unitsPerKg,
                    slopePerDay = it.slopePerDay * unitsPerKg,
                )
            },
            scaled.slopePerDay * unitsPerKg,
            scaled.slopeSd * unitsPerKg,
            scaled.noiseSd * unitsPerKg,
        )
    }

    private fun fitKg(input: List<DayValue>): TrendFit? {
        val points = input.sortedBy { it.epochDay }
        if (points.size < MIN_DAYS) return null
        val noiseVariance = noiseSd(points).let { it * it }

        val filtered = ArrayList<Gaussian2>(points.size)
        val predicted = ArrayList<Gaussian2>(points.size)
        var state = Gaussian2(points[0].value, 0.0, noiseVariance, 0.0, SLOPE_PRIOR_VARIANCE)
        filtered += state
        predicted += state
        for (i in 1 until points.size) {
            val dt = (points[i].epochDay - points[i - 1].epochDay).toDouble()
            val prediction = predict(state, dt)
            predicted += prediction
            state = update(prediction, points[i].value, noiseVariance)
            filtered += state
        }

        val smoothed = arrayOfNulls<Gaussian2>(points.size)
        smoothed[points.lastIndex] = filtered.last()
        for (i in points.lastIndex - 1 downTo 0) {
            val dt = (points[i + 1].epochDay - points[i].epochDay).toDouble()
            smoothed[i] = rts(filtered[i], predicted[i + 1], smoothed[i + 1]!!, dt)
        }

        val days = points.indices.map { i ->
            val s = smoothed[i]!!
            SmoothedDay(points[i].epochDay, points[i].value, s.level, sqrt(max(s.p00, 0.0)), s.slope)
        }
        val end = filtered.last()
        return TrendFit(days, end.slope, sqrt(max(end.p11, 0.0)), sqrt(noiseVariance))
    }

    private fun predict(g: Gaussian2, dt: Double): Gaussian2 {
        val p00 = g.p00 + 2 * dt * g.p01 + dt * dt * g.p11 + SLOPE_DRIFT * dt * dt * dt / 3
        val p01 = g.p01 + dt * g.p11 + SLOPE_DRIFT * dt * dt / 2
        val p11 = g.p11 + SLOPE_DRIFT * dt
        return Gaussian2(g.level + dt * g.slope, g.slope, p00, p01, p11)
    }

    private fun update(g: Gaussian2, observed: Double, noiseVariance: Double): Gaussian2 {
        val innovation = observed - g.level
        val expected = g.p00 + noiseVariance
        // A bad reading widens its own noise instead of dragging the trend.
        val variance = if (innovation * innovation > OUTLIER_Z * OUTLIER_Z * expected) {
            max(noiseVariance, innovation * innovation / (OUTLIER_Z * OUTLIER_Z) - g.p00)
        } else {
            noiseVariance
        }
        val s = g.p00 + variance
        val k0 = g.p00 / s
        val k1 = g.p01 / s
        return Gaussian2(
            g.level + k0 * innovation,
            g.slope + k1 * innovation,
            (1 - k0) * g.p00,
            (1 - k0) * g.p01,
            g.p11 - k1 * g.p01,
        )
    }

    /** Rauch–Tung–Striebel backward step. */
    private fun rts(f: Gaussian2, next: Gaussian2, smoothedNext: Gaussian2, dt: Double): Gaussian2 {
        // P·Fᵀ
        val a00 = f.p00 + dt * f.p01
        val a01 = f.p01
        val a10 = f.p01 + dt * f.p11
        val a11 = f.p11
        val det = next.p00 * next.p11 - next.p01 * next.p01
        val i00 = next.p11 / det
        val i01 = -next.p01 / det
        val i11 = next.p00 / det
        // Gain C = P·Fᵀ·P'⁻¹
        val c00 = a00 * i00 + a01 * i01
        val c01 = a00 * i01 + a01 * i11
        val c10 = a10 * i00 + a11 * i01
        val c11 = a10 * i01 + a11 * i11
        val dl = smoothedNext.level - next.level
        val ds = smoothedNext.slope - next.slope
        val d00 = smoothedNext.p00 - next.p00
        val d01 = smoothedNext.p01 - next.p01
        val d11 = smoothedNext.p11 - next.p11
        // C·Δ
        val e00 = c00 * d00 + c01 * d01
        val e01 = c00 * d01 + c01 * d11
        val e10 = c10 * d00 + c11 * d01
        val e11 = c10 * d01 + c11 * d11
        return Gaussian2(
            f.level + c00 * dl + c01 * ds,
            f.slope + c10 * dl + c11 * ds,
            f.p00 + e00 * c00 + e01 * c01,
            f.p01 + e00 * c10 + e01 * c11,
            f.p11 + e10 * c10 + e11 * c11,
        )
    }

    /** Robust noise from day-to-day jumps; each jump carries noise from both its ends. */
    private fun noiseSd(points: List<DayValue>): Double {
        val jumps = points.zipWithNext()
            .filter { (a, b) -> b.epochDay - a.epochDay <= 3 }
            .map { (a, b) -> b.value - a.value }
        if (jumps.size < 5) return DEFAULT_NOISE_SD
        val center = median(jumps)
        val spread = median(jumps.map { abs(it - center) })
        return (MAD_TO_SD * spread / SQRT_2).coerceIn(MIN_NOISE_SD, MAX_NOISE_SD)
    }
}

internal fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
}
