package app.okoklibre.ble.protocol

/**
 * Weight display unit carried in the property byte.
 *
 * [writeCode] is the code used in the `{0x01, unit}` downlink unit write
 * (CreateWriteDataUtil.getSyncUnit): 0=kg, 1=lb, 2=jin, 3=st.
 */
enum class WeightUnit(val writeCode: Int) {
    KG(0),
    JIN(2),
    LB(1),
    ST(3),
}

/** Helpers for the shared scaleProperty byte semantics. */
object PropertyByte {

    /** bits 4:3 → 00 kg, 01 jin, 10 lb, 11 st. */
    fun unit(property: Int): WeightUnit = when ((property shr 3) and 0b11) {
        0b01 -> WeightUnit.JIN
        0b10 -> WeightUnit.LB
        0b11 -> WeightUnit.ST
        else -> WeightUnit.KG
    }

    /** bit0 → 0 measuring, 1 locked/stable. */
    fun lockFlag(property: Int): Boolean = property and 0b1 != 0

    /** Classic `getDigit` — bits 2:1: 01→0, 10→2, else→1. */
    fun decimalsClassic(property: Int): Int = when ((property shr 1) and 0b11) {
        0b01 -> 0
        0b10 -> 2
        else -> 1
    }

    /** Cloud `getCloudDigit` — bits 2:1: 01→0, 10→1, else→2. */
    fun decimalsCloud(property: Int): Int = when ((property shr 1) and 0b11) {
        0b01 -> 0
        0b10 -> 1
        else -> 2
    }

    /** CloudV4 `getCloudV4Digit` — bits 6:5: 01→0, 10→1, else→2. */
    fun decimalsCloudV4(property: Int): Int = when ((property shr 5) and 0b11) {
        0b01 -> 0
        0b10 -> 1
        else -> 2
    }

    /**
     * Display digit precision for a stored scaleProperty (weight records).
     * Locked classic frames always carry bit0 = 1, so a 0 means "no property
     * recorded" (manual entry) → default 2 dp, the precision modern OKOK
     * scales report. Otherwise the classic bits-2:1 mapping is used.
     */
    fun displayDigits(property: Int): Int = if (property <= 0) 2 else decimalsClassic(property)
}
