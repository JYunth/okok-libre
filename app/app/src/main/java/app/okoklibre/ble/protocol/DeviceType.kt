package app.okoklibre.ble.protocol

/**
 * Device-type detection (classic 0xCA scales), pure decision function.
 *
 * On the first locked frame: the raw visceral wire value 0x6400 (25600) is the
 * sentinel for "scale did not compute fat" → type 2 (weight + impedance only,
 * the app computes composition). Otherwise, if the frame carries composition
 * fields (fat/water/muscle non-zero), the scale computes on-device → type 1.
 * Frames with no composition block at all (v11 sub-cmd 0/1 simple scales) →
 * type 0 (plain weigher).
 *
 * Detection must run on the raw frame values: for type-2 scales the sentinel
 * makes [Measurement.visceralFat] clamp to 0 and fat is 0 too, so gating on
 * `fatPct > 0` first makes type 2 unreachable.
 */
object DeviceType {
    const val WEIGHER = 0
    const val FAT_SCALE = 1
    const val IMPEDANCE_ONLY = 2

    /** oriVisceral sentinel: scale did not compute fat. */
    const val ORI_VISCERAL_SENTINEL = 25600

    fun detectFromLocked(m: Measurement): Int = when {
        m.oriVisceralFat == ORI_VISCERAL_SENTINEL -> IMPEDANCE_ONLY
        m.fatPct > 0.0 || m.waterPct > 0.0 || m.musclePct > 0.0 -> FAT_SCALE
        else -> WEIGHER
    }
}
