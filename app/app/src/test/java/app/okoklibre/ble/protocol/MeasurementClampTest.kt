package app.okoklibre.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementClampTest {

    @Test
    fun `fat clamped to 5-45 percent`() {
        assertEquals(21.5, Measurement.fatFromRaw(215), 1e-9)
        assertEquals(5.0, Measurement.fatFromRaw(50), 1e-9)
        assertEquals(45.0, Measurement.fatFromRaw(450), 1e-9)
        assertEquals(0.0, Measurement.fatFromRaw(49), 1e-9) // 4.9 < 5
        assertEquals(0.0, Measurement.fatFromRaw(451), 1e-9) // 45.1 > 45
        assertEquals(0.0, Measurement.fatFromRaw(65535), 1e-9) // unset sentinel
    }

    @Test
    fun `water clamped to 20-85 with 100 mapped to 0`() {
        assertEquals(55.0, Measurement.waterFromRaw(550), 1e-9)
        assertEquals(20.0, Measurement.waterFromRaw(200), 1e-9)
        assertEquals(85.0, Measurement.waterFromRaw(850), 1e-9)
        assertEquals(0.0, Measurement.waterFromRaw(199), 1e-9)
        assertEquals(0.0, Measurement.waterFromRaw(851), 1e-9)
        assertEquals(0.0, Measurement.waterFromRaw(1000), 1e-9) // 100 → 0
    }

    @Test
    fun `muscle below 100 kept`() {
        assertEquals(45.0, Measurement.muscleFromRaw(450), 1e-9)
        assertEquals(99.9, Measurement.muscleFromRaw(999), 1e-9)
        assertEquals(0.0, Measurement.muscleFromRaw(1000), 1e-9)
    }

    @Test
    fun `bmr above 5000 divided by 10`() {
        assertEquals(1600, Measurement.bmrFromRaw(1600))
        assertEquals(4999, Measurement.bmrFromRaw(4999))
        assertEquals(500, Measurement.bmrFromRaw(5000))
        assertEquals(0, Measurement.bmrFromRaw(65535)) // unset sentinel
    }

    @Test
    fun `visceral clamped to 59 max`() {
        assertEquals(9.0, Measurement.visceralFromRaw(90), 1e-9)
        assertEquals(59.0, Measurement.visceralFromRaw(590), 1e-9)
        assertEquals(0.0, Measurement.visceralFromRaw(600), 1e-9)
        assertEquals(0.0, Measurement.visceralFromRaw(25600), 1e-9) // 0x6400 sentinel
    }

    @Test
    fun `bone clamped to 1-4 kg`() {
        assertEquals(2.5, Measurement.boneFromRaw(25), 1e-9)
        assertEquals(1.0, Measurement.boneFromRaw(10), 1e-9)
        assertEquals(4.0, Measurement.boneFromRaw(40), 1e-9)
        assertEquals(4.0, Measurement.boneFromRaw(41), 1e-9) // clamped down to 4
        assertEquals(0.0, Measurement.boneFromRaw(9), 1e-9) // below 1
        assertEquals(0.0, Measurement.boneFromRaw(65535), 1e-9) // unset sentinel
    }
}
