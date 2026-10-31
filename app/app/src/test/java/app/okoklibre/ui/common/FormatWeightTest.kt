package app.okoklibre.ui.common

import app.okoklibre.algo.WeightUnit
import app.okoklibre.ble.protocol.PropertyByte
import org.junit.Assert.assertEquals
import org.junit.Test

/** Weight display precision (FIX: scale reports 2 dp; the UI must not round to 1 dp). */
class FormatWeightTest {

    @Test
    fun `kg defaults to 2 decimals`() {
        assertEquals("44.15", formatWeightKg(44.15, WeightUnit.KG))
        assertEquals("70.00", formatWeightKg(70.0, WeightUnit.KG))
    }

    @Test
    fun `explicit digits are honored`() {
        // 44.15 as a double is 44.14999…, so 1 dp renders "44.1"
        assertEquals("44.1", formatWeightKg(44.15, WeightUnit.KG, 1))
        assertEquals("44.2", formatWeightKg(44.18, WeightUnit.KG, 1))
        assertEquals("44", formatWeightKg(44.15, WeightUnit.KG, 0))
    }

    @Test
    fun `lb defaults to 1 decimal but honors the property`() {
        assertEquals("154.4", formatWeightKg(70.0, WeightUnit.LB))
        assertEquals("154.40", formatWeightKg(70.0, WeightUnit.LB, 2))
    }

    @Test
    fun `st lb ignores digits`() {
        assertEquals("11:0.4", formatWeightKg(70.0, WeightUnit.ST_LB))
        assertEquals("11:0.4", formatWeightKg(70.0, WeightUnit.ST_LB, 0))
    }

    @Test
    fun `record weight follows the stored scale property`() {
        // scaleProperty 0 (manual entry) → 2 dp default
        assertEquals("44.15", formatRecordWeightKg(44.15, WeightUnit.KG, 0))
        // locked 2 dp classic property (bits 2:1 = 10, lock bit set)
        assertEquals("44.15", formatRecordWeightKg(44.15, WeightUnit.KG, 0x05))
        // locked 1 dp classic property
        assertEquals("44.1", formatRecordWeightKg(44.15, WeightUnit.KG, 0x01))
        // locked 0 dp classic property (bits 2:1 = 01)
        assertEquals("44", formatRecordWeightKg(44.15, WeightUnit.KG, 0x03))
        // matches the raw PropertyByte helper
        assertEquals(
            formatWeightKg(44.15, WeightUnit.KG, PropertyByte.displayDigits(0x05)),
            formatRecordWeightKg(44.15, WeightUnit.KG, 0x05),
        )
    }
}
