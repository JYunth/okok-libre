package app.okoklibre.ble.protocol

import app.okoklibre.ble.protocol.WeightDecoder.Endianness.BIG
import app.okoklibre.ble.protocol.WeightDecoder.Endianness.LITTLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeightDecoderTest {

    // Property bytes: bits 4:3 unit (00 kg, 01 jin, 10 lb, 11 st), classic
    // decimals bits 2:1 (00/11 → 1, 01 → 0, 10 → 2).
    private val propKgDec0 = 0x02
    private val propKgDec1 = 0x00
    private val propKgDec2 = 0x04
    private val propJinDec1 = 0x08
    private val propLbDec1 = 0x10
    private val propStDec2 = 0x1C

    @Test
    fun `kg big-endian with one decimal`() {
        // raw 650 → 65.0 kg
        assertEquals(65.0, WeightDecoder.decodeToKg(0x02, 0x8A.toByte(), propKgDec1, 1, BIG)!!, 1e-9)
    }

    @Test
    fun `kg zero decimals`() {
        // raw 65 → 65 kg
        assertEquals(65.0, WeightDecoder.decodeToKg(0x00, 0x41, propKgDec0, 0, BIG)!!, 1e-9)
    }

    @Test
    fun `kg two decimals`() {
        // raw 6500 → 65.00 kg
        assertEquals(65.0, WeightDecoder.decodeToKg(0x19, 0x64, propKgDec2, 2, BIG)!!, 1e-9)
    }

    @Test
    fun `little-endian byte order`() {
        assertEquals(65.0, WeightDecoder.decodeToKg(0x8A.toByte(), 0x02, propKgDec1, 1, LITTLE)!!, 1e-9)
    }

    @Test
    fun `jin converted to kg at half rate`() {
        // raw 1300 → 130.0 jin → 65.0 kg
        assertEquals(65.0, WeightDecoder.decodeToKg(0x05, 0x14, propJinDec1, 1, BIG)!!, 1e-9)
    }

    @Test
    fun `lb converted to kg`() {
        // raw 1433 → 143.3 lb → 143.3 * 0.4535924 = 64.9998 kg → 65.0 (1 decimal)
        assertEquals(65.0, WeightDecoder.decodeToKg(0x05, 0x99.toByte(), propLbDec1, 1, BIG)!!, 1e-9)
    }

    @Test
    fun `stone-pound converted to kg`() {
        // hi byte = 10 st, lo byte = 50 (5.0 lb) → 145 lb → 65.770898 kg → 65.77
        assertEquals(65.77, WeightDecoder.decodeToKg(0x0A, 0x32, propStDec2, 2, BIG)!!, 1e-9)
    }

    @Test
    fun `stone with zero pounds`() {
        // 10 st 0.0 lb → 140 lb → 63.502936 kg → 63.50
        assertEquals(63.5, WeightDecoder.decodeToKg(0x0A, 0x00, propStDec2, 2, BIG)!!, 1e-9)
    }

    @Test
    fun `zero weight rejected`() {
        assertNull(WeightDecoder.decodeToKg(0x00, 0x00, propKgDec1, 1, BIG))
    }

    @Test
    fun `weight at or above 600 kg rejected`() {
        // raw 6000, 1 decimal → 600.0 kg → scaled 6000 → reject
        assertNull(WeightDecoder.decodeToKg(0x17, 0x70, propKgDec1, 1, BIG))
        // raw 5999 → 599.9 kg → scaled 5999 → accepted
        assertEquals(599.9, WeightDecoder.decodeToKg(0x17, 0x6F, propKgDec1, 1, BIG)!!, 1e-9)
    }
}
