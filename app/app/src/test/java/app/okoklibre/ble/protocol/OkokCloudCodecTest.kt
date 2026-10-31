package app.okoklibre.ble.protocol

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OkokCloudCodecTest {

    private val fff1 = GattUuids.CHAR_LIVE_FFF1
    private val char2a9c = GattUuids.CHAR_LOCKED_2A9C
    private val fa9c = GattUuids.CHAR_HISTORY_FA9C

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun codec(nowEpochSec: Long = 1_800_000_000L) = OkokCloudCodec(nowEpochSec = { nowEpochSec })

    /**
     * Fat-scale frame: 06 03 | 2023-11-14 12:30:45 (year LE E7 07) |
     * impedance LE 5100 (EC 13) → 510.0 Ω | weight LE 650 (8A 02) → 65.0 kg |
     * property 0x04 (kg, cloud mapping → 1 decimal).
     */
    private fun fatFrame() = bytes(
        0x06, 0x03,
        0xE7, 0x07, 0x0B, 0x0E, 0x0C, 0x1E, 0x2D,
        0xEC, 0x13,
        0x8A, 0x02,
        0x00, 0x00,
        0x04,
    )

    private val frameEpochSec: Long =
        LocalDateTime.of(2023, 11, 14, 12, 30, 45).atZone(ZoneId.systemDefault()).toEpochSecond()

    @Test
    fun `locked frame on 2a9c decodes timestamp weight and impedance`() {
        val m = (codec().parse(char2a9c, fatFrame()) as ParseResult.LockedMeasurement).measurement
        assertEquals(65.0, m.weightKg, 1e-9)
        assertEquals(510.0, m.impedanceOhm, 1e-9)
        assertTrue(m.lockFlag)
        assertFalse(m.isHistory)
        assertEquals(frameEpochSec, m.timestampEpochSec)
        assertEquals(0x04, m.scaleProperty)
    }

    @Test
    fun `live frame on fff1 is unlocked with no timestamp`() {
        val m = (codec().parse(fff1, fatFrame()) as ParseResult.LiveMeasurement).measurement
        assertFalse(m.lockFlag)
        assertNull(m.timestampEpochSec)
        assertFalse(m.isHistory)
        assertEquals(65.0, m.weightKg, 1e-9)
        assertEquals(510.0, m.impedanceOhm, 1e-9)
    }

    @Test
    fun `history frame on fa9c is flagged`() {
        val m = (codec().parse(fa9c, fatFrame()) as ParseResult.HistoryRecord).measurement
        assertTrue(m.isHistory)
        assertTrue(m.lockFlag)
        assertEquals(frameEpochSec, m.timestampEpochSec)
    }

    @Test
    fun `locked frame carries raw frame hex and scale weight string, live does not`() {
        val frame = fatFrame()
        val locked = (codec().parse(char2a9c, frame) as ParseResult.LockedMeasurement).measurement
        assertEquals(frame.toList(), locked.rawFrame?.toList())
        // property 0x04 → kg, cloud mapping 1 decimal → "65.0"
        assertEquals("65.0", locked.scaleWeightString)
        val live = (codec().parse(fff1, frame) as ParseResult.LiveMeasurement).measurement
        assertNull(live.rawFrame)
        assertNull(live.scaleWeightString)
    }

    @Test
    fun `history frame outside acceptance window is dropped`() {
        // year 2009 (0x07D9) → before 2010-01-01
        val frame = fatFrame().also { it[2] = 0xD9.toByte(); it[3] = 0x07 }
        assertEquals(ParseResult.Unknown, codec().parse(fa9c, frame))
    }

    @Test
    fun `weight-only frame has no impedance`() {
        val frame = fatFrame().also { it[0] = 0x02; it[1] = 0x00; it[9] = 0x00; it[10] = 0x00 }
        val m = (codec().parse(char2a9c, frame) as ParseResult.LockedMeasurement).measurement
        assertEquals(65.0, m.weightKg, 1e-9)
        assertEquals(0.0, m.impedanceOhm, 1e-9)
    }

    @Test
    fun `fat scale sub-type 0x23 accepted`() {
        val frame = fatFrame().also { it[1] = 0x23 }
        val m = (codec().parse(char2a9c, frame) as ParseResult.LockedMeasurement).measurement
        assertEquals(510.0, m.impedanceOhm, 1e-9)
    }

    @Test
    fun `special product ids force property byte to 4`() {
        // wire property 0x00 (kg, cloud mapping → 2 decimals) would decode 650 as 6.50 kg;
        // forced property 4 (kg, 1 decimal) decodes 65.0 kg
        val frame = fatFrame().also { it[15] = 0x00 }
        for (productId in listOf(997264800, 1104000001)) {
            val m = (codec().parse(char2a9c, frame, productId) as ParseResult.LockedMeasurement).measurement
            assertEquals(4, m.scaleProperty)
            assertEquals(65.0, m.weightKg, 1e-9)
        }
        // a normal product id keeps the wire property
        val m = (codec().parse(char2a9c, frame, 1234) as ParseResult.LockedMeasurement).measurement
        assertEquals(0x00, m.scaleProperty)
        assertEquals(6.5, m.weightKg, 1e-9)
    }

    @Test
    fun `malformed frames return Unknown`() {
        assertEquals(ParseResult.Unknown, codec().parse(char2a9c, bytes(0x06, 0x03, 0x00))) // short
        assertEquals(ParseResult.Unknown, codec().parse(char2a9c, fatFrame().also { it[0] = 0x03 })) // bad type
        assertEquals(ParseResult.Unknown, codec().parse(char2a9c, fatFrame().also { it[1] = 0x09 })) // bad sub-type
        // zero weight
        assertEquals(ParseResult.Unknown, codec().parse(char2a9c, fatFrame().also { it[11] = 0x00; it[12] = 0x00 }))
    }

    @Test
    fun `time write is 7 bytes with little-endian year`() {
        assertArrayEquals(
            bytes(0xEA, 0x07, 0x0A, 0x08, 0x0C, 0x22, 0x38),
            OkokCloudCodec.buildTimeWrite(LocalDateTime.of(2026, 10, 8, 12, 34, 56)),
        )
    }

    @Test
    fun `unit write codes`() {
        assertArrayEquals(bytes(0x01, 0x00), OkokCloudCodec.buildUnitWrite(WeightUnit.KG))
        assertArrayEquals(bytes(0x01, 0x01), OkokCloudCodec.buildUnitWrite(WeightUnit.LB))
        assertArrayEquals(bytes(0x01, 0x02), OkokCloudCodec.buildUnitWrite(WeightUnit.JIN))
        assertArrayEquals(bytes(0x01, 0x03), OkokCloudCodec.buildUnitWrite(WeightUnit.ST))
    }
}
