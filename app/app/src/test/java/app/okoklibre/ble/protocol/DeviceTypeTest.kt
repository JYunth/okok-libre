package app.okoklibre.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** Device-type detection from a locked frame's raw values. */
class DeviceTypeTest {

    private fun measurement(
        fatPct: Double = 0.0,
        waterPct: Double = 0.0,
        musclePct: Double = 0.0,
        oriVisceralFat: Int = 0,
    ) = Measurement(
        weightKg = 65.0,
        lockFlag = true,
        fatPct = fatPct,
        waterPct = waterPct,
        musclePct = musclePct,
        oriVisceralFat = oriVisceralFat,
        scaleProperty = 0x01,
    )

    @Test
    fun `visceral sentinel means impedance-only scale even with fat clamped to zero`() {
        // The previously unreachable case: oriVisceral 25600 → fat/visceral
        // clamped to 0 by Measurement, so a fatPct>0 gate never fires.
        assertEquals(DeviceType.IMPEDANCE_ONLY, DeviceType.detectFromLocked(measurement(oriVisceralFat = 25600)))
    }

    @Test
    fun `scale-computed composition fields mean fat scale`() {
        assertEquals(DeviceType.FAT_SCALE, DeviceType.detectFromLocked(measurement(fatPct = 21.5)))
        assertEquals(DeviceType.FAT_SCALE, DeviceType.detectFromLocked(measurement(waterPct = 55.0)))
        assertEquals(DeviceType.FAT_SCALE, DeviceType.detectFromLocked(measurement(musclePct = 45.0)))
    }

    @Test
    fun `no composition block means plain weigher`() {
        assertEquals(DeviceType.WEIGHER, DeviceType.detectFromLocked(measurement()))
    }

    @Test
    fun `sentinel wins over composition fields`() {
        assertEquals(
            DeviceType.IMPEDANCE_ONLY,
            DeviceType.detectFromLocked(measurement(fatPct = 21.5, oriVisceralFat = 25600)),
        )
    }
}
