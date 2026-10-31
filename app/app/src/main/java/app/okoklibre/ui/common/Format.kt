package app.okoklibre.ui.common

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.okoklibre.R
import app.okoklibre.algo.Grading
import app.okoklibre.algo.UnitConverter
import app.okoklibre.algo.WeightUnit
import app.okoklibre.ble.protocol.PropertyByte
import app.okoklibre.data.LengthUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Display precision for a unit when no scaleProperty is known: 2 dp, except lb (1 dp). */
fun defaultWeightDigits(unit: WeightUnit): Int = if (unit == WeightUnit.LB) 1 else 2

/**
 * Formats a kg weight for display at the scale's digit precision. Pass the
 * measurement's/record's digit count (PropertyByte.displayDigits of the stored
 * scaleProperty) as [digits] when available; defaults to
 * [defaultWeightDigits]. "st:lb" output ignores [digits].
 */
fun formatWeightKg(kg: Double, unit: WeightUnit, digits: Int = defaultWeightDigits(unit)): String =
    UnitConverter.formatWeight(kg, unit, digits)

/** Formats a stored record's weight at the digit precision of its scaleProperty. */
fun formatRecordWeightKg(kg: Double, unit: WeightUnit, scaleProperty: Int): String =
    formatWeightKg(kg, unit, PropertyByte.displayDigits(scaleProperty))

@Composable
fun weightUnitLabel(unit: WeightUnit): String = stringResource(
    when (unit) {
        WeightUnit.KG -> R.string.unit_kg
        WeightUnit.LB -> R.string.unit_lb
        WeightUnit.ST_LB -> R.string.unit_st_lb
        WeightUnit.JIN -> R.string.unit_jin
    },
)

/** Parses user-entered weight text in [unit] back to kg; null when invalid. */
fun parseWeightToKg(text: String, unit: WeightUnit): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val kg = when (unit) {
        WeightUnit.KG -> trimmed.toDoubleOrNull()
        WeightUnit.LB -> trimmed.toDoubleOrNull()?.let { UnitConverter.lbToKg(it) }
        WeightUnit.JIN -> trimmed.toDoubleOrNull()?.let { UnitConverter.jinToKg(it) }
        WeightUnit.ST_LB -> UnitConverter.stLbToKg(trimmed).takeIf { it > 0.0 }
    }
    return kg?.takeIf { it > 0.0 && it < 500.0 }
}

fun formatHeightCm(cm: Double, unit: LengthUnit): String = when (unit) {
    LengthUnit.CM -> "${cm.toInt()} cm"
    LengthUnit.IMPERIAL -> UnitConverter.formatFeetInches(cm)
}

/** Parses height input in cm or feet/inches back to whole cm; null when invalid. */
fun parseHeightToCm(cmText: String, feetText: String, inchesText: String, unit: LengthUnit): Int? =
    when (unit) {
        LengthUnit.CM -> cmText.trim().toIntOrNull()
        LengthUnit.IMPERIAL -> {
            val feet = feetText.trim().toIntOrNull()
            val inches = inchesText.trim().toIntOrNull() ?: 0
            if (feet == null || inches !in 0..11) null else UnitConverter.feetInchesToCm(feet, inches).toInt()
        }
    }?.takeIf { it in 80..250 }

@Composable
fun regionLabel(region: Grading.Region): String = stringResource(
    when (region) {
        Grading.Region.CHINA -> R.string.region_china
        Grading.Region.SOUTHEAST_ASIA -> R.string.region_southeast_asia
        Grading.Region.OTHER -> R.string.region_other
    },
)

@Composable
fun levelLabel(level: Grading.Level): String = stringResource(
    when (level) {
        Grading.Level.THIN -> R.string.level_thin
        Grading.Level.LOW -> R.string.level_low
        Grading.Level.STANDARD -> R.string.level_standard
        Grading.Level.HIGH -> R.string.level_high
        Grading.Level.OVERWEIGHT -> R.string.level_overweight
        Grading.Level.VERY_HIGH -> R.string.level_very_high
        Grading.Level.OBESE -> R.string.level_obese
        Grading.Level.MILD -> R.string.level_mild
        Grading.Level.MODERATE -> R.string.level_moderate
        Grading.Level.SEVERE -> R.string.level_severe
        Grading.Level.EXTREME -> R.string.level_extreme
    },
)

fun formatTimestamp(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(epochSec).atZone(zone)
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

fun formatEpochDay(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

fun formatTimeOfDay(minutesOfDay: Int): String =
    String.format(java.util.Locale.US, "%02d:%02d", minutesOfDay / 60, minutesOfDay % 60)

/** A signed weight with a true minus sign, at one decimal unless told otherwise. */
fun signedWeight(kg: Double, unit: WeightUnit, digits: Int = 1): String {
    val magnitude = formatWeightKg(abs(kg), unit, digits)
    return when {
        magnitude.none { it in '1'..'9' } -> magnitude
        kg < 0 -> "−$magnitude"
        else -> "+$magnitude"
    }
}

private fun localizedPattern(skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton))

/** "9 Jan", or "9 Jan 2025" outside [currentYear]; follows the locale's order. */
fun shortDate(epochDay: Long, currentYear: Int = LocalDate.now().year): String {
    val date = LocalDate.ofEpochDay(epochDay)
    return date.format(localizedPattern(if (date.year == currentYear) "dMMM" else "dMMMyyyy"))
}

/** The time of a reading in the phone's own 12 or 24 hour style. */
@Composable
fun clockTime(epochSec: Long): String = clockTime(LocalContext.current, epochSec)

fun clockTime(context: Context, epochSec: Long): String =
    DateFormat.getTimeFormat(context).format(Date(epochSec * 1000))

/** "Today, 7:12", "Yesterday, 19:04", "9 Jan, 7:12". */
@Composable
fun readingWhen(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val time = clockTime(epochSec)
    return when (day) {
        today -> stringResource(R.string.when_today, time)
        today.minusDays(1) -> stringResource(R.string.when_yesterday, time)
        else -> "${shortDate(day.toEpochDay())}, $time"
    }
}
