package app.okoklibre.algo

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** App weight-unit codes and the scale's wire unit codes. */
enum class WeightUnit(val appCode: Int, val wireCode: Int) {
    KG(1400, 0),
    LB(1401, 1),
    JIN(1402, 2),
    ST_LB(1403, 3),
    ;

    companion object {
        fun fromAppCode(code: Int): WeightUnit? = entries.firstOrNull { it.appCode == code }
        fun fromWireCode(code: Int): WeightUnit? = entries.firstOrNull { it.wireCode == code }
    }
}

/**
 * Unit conversions.
 */
object UnitConverter {

    const val KG_PER_LB = 0.4535924
    const val CM_TO_INCH = 0.394 // the original's factor; deliberately not 0.3937

    // jin

    fun kgToJin(kg: Double): Double = kg * 2.0
    fun jinToKg(jin: Double): Double = jin * 0.5

    // lb

    /** Exact conversion used by the in-app display path. */
    fun kgToLbExact(kg: Double): Double = kg / KG_PER_LB

    fun lbToKg(lb: Double): Double = lb * KG_PER_LB

    /**
     * The scale's lb display quirk: `((int)((kg·10)·11023/5000) + 1)/2·2/10.0`,
     * rounding the value up to the next 0.2 lb step.
     */
    fun kgToLb(kg: Double): Double {
        val scaled = ((kg * 10.0) * 11023.0 / 5000.0).toInt()
        return ((scaled + 1) / 2 * 2) / 10.0
    }

    // st:lb

    /** "st:lb" string using the scale's lb quirk (KG2ST). */
    fun kgToStLb(kg: Double): String = lbToStLb(kgToLb(kg))

    /** "st:lb" string using the exact lb conversion (KG2ST_App). */
    fun kgToStLbExact(kg: Double): String = lbToStLb(kgToLbExact(kg))

    /** floor st, round1 lb remainder. */
    fun lbToStLb(lb: Double): String {
        val st = (lb / 14.0).toInt()
        return "$st:${round1(lb % 14.0)}"
    }

    /** Parses "st:lb" back to lb (`14·st + lb`); 0.0 on malformed input. */
    fun stLbToLb(stLb: String): Double {
        val parts = stLb.trim().split(":")
        if (parts.size != 2) return 0.0
        return try {
            parts[0].toInt() * 14 + parts[1].toDouble()
        } catch (e: NumberFormatException) {
            0.0
        }
    }

    /** Parses "st:lb" back to kg, round3 HALF_UP; 0.0 on malformed input. */
    fun stLbToKg(stLb: String): Double {
        val parts = stLb.trim().split(":")
        if (parts.size != 2) return 0.0
        return try {
            round3(lbToKg(parts[0].toInt() * 14 + parts[1].toDouble()))
        } catch (e: NumberFormatException) {
            0.0
        }
    }

    // length (cm ↔ ft/in), using the original's 0.394 factor

    fun cmToFeet(cm: Double): Double = cm * CM_TO_INCH / 12.0

    fun cmToInches(cm: Double): Double = cm * CM_TO_INCH

    fun inchesToCm(inches: Double): Double = inches / CM_TO_INCH

    fun feetToCm(feet: Double): Double = feet * 12.0 / CM_TO_INCH

    fun feetInchesToCm(feet: Int, inches: Int): Double = (feet * 12.0 + inches) / CM_TO_INCH

    /** Splits cm into (feet, inches); inches = round((ft − feet)·12). */
    fun cmToFeetInches(cm: Double): Pair<Int, Int> {
        val ft = cmToFeet(cm)
        val feet = ft.toInt()
        val inches = Math.round((ft - feet) * 12.0).toInt()
        // Rounding can land on a whole foot; 6'0" reads right where 5'12" doesn't.
        return if (inches == 12) (feet + 1) to 0 else feet to inches
    }

    /** Formats cm as `5'7"`. */
    fun formatFeetInches(cm: Double): String {
        val (feet, inches) = cmToFeetInches(cm)
        return "$feet'$inches\""
    }

    /**
     * Weight display by scale digit precision: 0 dp → int (truncated),
     * 1 dp → `#0.0`, 2 dp → `#0.00`, decimal separator always `.`
     * (DecimalFormat default rounding, as in the original).
     */
    fun formatWeight(value: Double, digitPrecision: Int): String {
        val symbols = DecimalFormatSymbols(Locale.ENGLISH)
        return when (digitPrecision) {
            0 -> value.toInt().toString()
            1 -> DecimalFormat("#0.0", symbols).format(value)
            else -> DecimalFormat("#0.00", symbols).format(value)
        }
    }

    /** Converts and formats a kg weight into the given display unit. */
    fun formatWeight(kg: Double, unit: WeightUnit, digitPrecision: Int): String = when (unit) {
        WeightUnit.KG -> formatWeight(kg, digitPrecision)
        WeightUnit.JIN -> formatWeight(kgToJin(kg), digitPrecision)
        WeightUnit.LB -> formatWeight(kgToLb(kg), digitPrecision)
        WeightUnit.ST_LB -> kgToStLb(kg)
    }
}
