package app.okoklibre.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyByteTest {

    @Test
    fun `unit extracted from bits 4-3`() {
        assertEquals(WeightUnit.KG, PropertyByte.unit(0x00))
        assertEquals(WeightUnit.JIN, PropertyByte.unit(0x08))
        assertEquals(WeightUnit.LB, PropertyByte.unit(0x10))
        assertEquals(WeightUnit.ST, PropertyByte.unit(0x18))
        // surrounding bits do not matter
        assertEquals(WeightUnit.ST, PropertyByte.unit(0xFF))
        assertEquals(WeightUnit.KG, PropertyByte.unit(0xE7))
    }

    @Test
    fun `lock flag is bit 0`() {
        assertTrue(PropertyByte.lockFlag(0x01))
        assertTrue(PropertyByte.lockFlag(0xFF))
        assertFalse(PropertyByte.lockFlag(0x00))
        assertFalse(PropertyByte.lockFlag(0xFE))
    }

    @Test
    fun `classic digit mapping bits 2-1 - 01 to 0, 10 to 2, else 1`() {
        assertEquals(1, PropertyByte.decimalsClassic(0x00)) // 00
        assertEquals(0, PropertyByte.decimalsClassic(0x02)) // 01
        assertEquals(2, PropertyByte.decimalsClassic(0x04)) // 10
        assertEquals(1, PropertyByte.decimalsClassic(0x06)) // 11
    }

    @Test
    fun `cloud digit mapping bits 2-1 - 01 to 0, 10 to 1, else 2`() {
        assertEquals(2, PropertyByte.decimalsCloud(0x00)) // 00
        assertEquals(0, PropertyByte.decimalsCloud(0x02)) // 01
        assertEquals(1, PropertyByte.decimalsCloud(0x04)) // 10
        assertEquals(2, PropertyByte.decimalsCloud(0x06)) // 11
    }

    @Test
    fun `cloudV4 digit mapping bits 6-5 - 01 to 0, 10 to 1, else 2`() {
        assertEquals(2, PropertyByte.decimalsCloudV4(0x00)) // 00
        assertEquals(0, PropertyByte.decimalsCloudV4(0x20)) // 01
        assertEquals(1, PropertyByte.decimalsCloudV4(0x40)) // 10
        assertEquals(2, PropertyByte.decimalsCloudV4(0x60)) // 11
    }

    @Test
    fun `display digits default to 2 when no property was recorded`() {
        assertEquals(2, PropertyByte.displayDigits(0)) // manual entry
        assertEquals(2, PropertyByte.displayDigits(-1))
    }

    @Test
    fun `display digits follow the classic mapping once a property exists`() {
        assertEquals(1, PropertyByte.displayDigits(0x01)) // locked, 00 bits
        assertEquals(0, PropertyByte.displayDigits(0x03)) // locked, 01 bits
        assertEquals(2, PropertyByte.displayDigits(0x05)) // locked, 10 bits (2 dp scale)
        assertEquals(1, PropertyByte.displayDigits(0x07)) // locked, 11 bits
    }
}
