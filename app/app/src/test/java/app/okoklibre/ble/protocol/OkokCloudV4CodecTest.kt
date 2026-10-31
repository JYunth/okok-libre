package app.okoklibre.ble.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OkokCloudV4CodecTest {

    private val fff1 = GattUuids.CHAR_LIVE_FFF1
    private val char2a9c = GattUuids.CHAR_LOCKED_2A9C
    private val fa9c = GattUuids.CHAR_HISTORY_FA9C

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun codec(nowEpochSec: Long = 1_800_000_000L) = OkokCloudV4Codec(nowEpochSec = { nowEpochSec })

    /**
     * C5 frame: property 0x04 (kg, cloud mapping → 1 decimal, no flags) |
     * time BE 1700000000 (65 53 F1 00) | weight LE 650 (8A 02) → 65.0 kg |
     * impedance LE 5100 (EC 13) → 510.0 Ω | heart rate 72 bpm.
     */
    private fun c5Frame() = bytes(
        0xC5, 0x04,
        0x65, 0x53, 0xF1, 0x00,
        0x8A, 0x02,
        0xEC, 0x13,
        0x48,
    )

    @Test
    fun `C5 locked frame on 2a9c decodes all fields`() {
        val frame = c5Frame()
        val m = (codec().parse(char2a9c, frame) as ParseResult.LockedMeasurement).measurement
        assertEquals(65.0, m.weightKg, 1e-9)
        assertEquals(510.0, m.impedanceOhm, 1e-9)
        assertEquals(72, m.heartRateBpm)
        assertEquals(1700000000L, m.timestampEpochSec)
        assertTrue(m.lockFlag)
        assertFalse(m.isHistory)
        assertFalse(m.heartRateMeasuring)
        assertFalse(m.impedanceMeasuring)
        assertEquals(0x04, m.scaleProperty)
        assertEquals(frame.toList(), m.rawFrame?.toList())
        assertEquals("65.0", m.scaleWeightString)
    }

    @Test
    fun `C5 live frame on fff1 is unlocked with no timestamp`() {
        val m = (codec().parse(fff1, c5Frame()) as ParseResult.LiveMeasurement).measurement
        assertFalse(m.lockFlag)
        assertNull(m.timestampEpochSec)
        assertFalse(m.isHistory)
        assertEquals(65.0, m.weightKg, 1e-9)
        assertEquals(72, m.heartRateBpm)
    }

    @Test
    fun `C5 property bits 7 and 6 flag in-progress measurements`() {
        val frame = c5Frame().also { it[1] = 0xC4.toByte() } // bit7 + bit6 + 1 decimal
        val m = (codec().parse(fff1, frame) as ParseResult.LiveMeasurement).measurement
        assertTrue(m.heartRateMeasuring)
        assertTrue(m.impedanceMeasuring)
        assertEquals(65.0, m.weightKg, 1e-9) // decimals still read from bits 2:1
    }

    @Test
    fun `C5 zero heart rate maps to null`() {
        val frame = c5Frame().also { it[10] = 0x00 }
        val m = (codec().parse(char2a9c, frame) as ParseResult.LockedMeasurement).measurement
        assertNull(m.heartRateBpm)
    }

    @Test
    fun `C4 frame has no heart rate`() {
        val frame = bytes(
            0xC4, 0x04,
            0x65, 0x53, 0xF1, 0x00,
            0x8A, 0x02,
            0xEC, 0x13,
        )
        val m = (codec().parse(char2a9c, frame) as ParseResult.LockedMeasurement).measurement
        assertEquals(65.0, m.weightKg, 1e-9)
        assertEquals(510.0, m.impedanceOhm, 1e-9)
        assertNull(m.heartRateBpm)
        assertFalse(m.heartRateMeasuring)
        assertFalse(m.impedanceMeasuring)
        assertEquals(1700000000L, m.timestampEpochSec)
    }

    @Test
    fun `history frame on fa9c validated against acceptance window`() {
        // now fixed at 1700000100: frame timestamp 1700000000 is inside the window
        val m = (codec(nowEpochSec = 1700000100L).parse(fa9c, c5Frame()) as ParseResult.HistoryRecord).measurement
        assertTrue(m.isHistory)
        assertEquals(1700000000L, m.timestampEpochSec)
        // future timestamp beyond now + 5 min → dropped
        val future = c5Frame().also {
            val t = 1700000100L + 301
            it[2] = (t shr 24).toByte(); it[3] = (t shr 16).toByte()
            it[4] = (t shr 8).toByte(); it[5] = t.toByte()
        }
        assertEquals(ParseResult.Unknown, codec(nowEpochSec = 1700000100L).parse(fa9c, future))
    }

    @Test
    fun `malformed frames return Unknown`() {
        assertEquals(ParseResult.Unknown, codec().parse(char2a9c, bytes(0xC6, 0x04))) // bad header
        assertEquals(ParseResult.Unknown, codec().parse(char2a9c, bytes(0xC5, 0x04, 0x00))) // short
        assertEquals(ParseResult.Unknown, codec().parse(char2a9c, c5Frame().also { it[6] = 0x00; it[7] = 0x00 })) // zero weight
    }

    @Test
    fun `time write is 4-byte big-endian unix time`() {
        assertArrayEquals(bytes(0x65, 0x53, 0xF1, 0x00), OkokCloudV4Codec.buildTimeWrite(1700000000L))
    }

    @Test
    fun `unit write codes`() {
        assertArrayEquals(bytes(0x01, 0x00), OkokCloudV4Codec.buildUnitWrite(WeightUnit.KG))
        assertArrayEquals(bytes(0x01, 0x02), OkokCloudV4Codec.buildUnitWrite(WeightUnit.JIN))
        assertArrayEquals(bytes(0x01, 0x01), OkokCloudV4Codec.buildUnitWrite(WeightUnit.LB))
        assertArrayEquals(bytes(0x01, 0x03), OkokCloudV4Codec.buildUnitWrite(WeightUnit.ST))
    }

    @Test
    fun `user profile write`() {
        // 0xA0 | roleId BE 42 | sexAge male 30 → 0x9E | 175 cm | goal 0
        assertArrayEquals(
            bytes(0xA0, 0x00, 0x00, 0x00, 0x2A, 0x9E, 0xAF, 0x00, 0x00),
            OkokCloudV4Codec.buildUserProfile(roleId = 42, male = true, age = 30, heightCm = 175),
        )
        // female age 25 → sexAge 0x19; goal 65.0 kg → 650 = 0x028A
        assertArrayEquals(
            bytes(0xA0, 0x00, 0x00, 0x00, 0x2A, 0x19, 0xAF, 0x02, 0x8A),
            OkokCloudV4Codec.buildUserProfile(roleId = 42, male = false, age = 25, heightCm = 175, goalKgTimes10 = 650),
        )
    }
}
