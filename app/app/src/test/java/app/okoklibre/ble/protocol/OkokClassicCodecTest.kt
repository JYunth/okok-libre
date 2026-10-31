package app.okoklibre.ble.protocol

import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OkokClassicCodecTest {

    private val fff1 = GattUuids.CHAR_LIVE_FFF1

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun codec(nowEpochSec: Long = 1_800_000_000L) = OkokClassicCodec(nowEpochSec = { nowEpochSec })

    // ------------------------------------------------------------------
    // Uplink: v10 real-time frame
    // ------------------------------------------------------------------

    /** CA 10 0E 00 01 | 02 8A (65.0 kg) | 00 D7 fat | 02 26 water | 01 C2 muscle | 05 DC bmr | 00 5A visceral | 19 bone */
    private fun v10LockedFrame() = bytes(
        0xCA, 0x10, 0x0E, 0x00, 0x01,
        0x02, 0x8A,
        0x00, 0xD7, // fat 215 → 21.5%
        0x02, 0x26, // water 550 → 55.0%
        0x01, 0xC2, // muscle 450 → 45.0%
        0x05, 0xDC, // bmr 1500
        0x00, 0x5A, // visceral 90 → 9.0
        0x19, // bone 25 → 2.5 kg
    )

    @Test
    fun `v10 locked frame decodes all composition fields`() {
        val result = codec().parse(fff1, v10LockedFrame())
        val m = (result as ParseResult.LockedMeasurement).measurement
        assertEquals(65.0, m.weightKg, 1e-9)
        assertTrue(m.lockFlag)
        assertEquals(21.5, m.fatPct, 1e-9)
        assertEquals(55.0, m.waterPct, 1e-9)
        assertEquals(45.0, m.musclePct, 1e-9)
        assertEquals(1500, m.bmrKcal)
        assertEquals(9.0, m.visceralFat, 1e-9)
        assertEquals(90, m.oriVisceralFat)
        assertEquals(2.5, m.boneKg, 1e-9)
        assertEquals(0x01, m.scaleProperty)
        assertFalse(m.isHistory)
    }

    @Test
    fun `v10 unlocked frame reports weight only`() {
        val frame = v10LockedFrame().also { it[4] = 0x00 } // lockFlag = 0
        val result = codec().parse(fff1, frame)
        val m = (result as ParseResult.LiveMeasurement).measurement
        assertEquals(65.0, m.weightKg, 1e-9)
        assertFalse(m.lockFlag)
        assertEquals(0.0, m.fatPct, 1e-9)
        assertEquals(0.0, m.waterPct, 1e-9)
    }

    @Test
    fun `v10 visceral sentinel 0x6400 preserved raw and clamped to zero`() {
        val frame = v10LockedFrame().also { it[15] = 0x64; it[16] = 0x00 } // 25600
        val m = (codec().parse(fff1, frame) as ParseResult.LockedMeasurement).measurement
        assertEquals(25600, m.oriVisceralFat)
        assertEquals(0.0, m.visceralFat, 1e-9)
    }

    @Test
    fun `v10 locked frame carries raw frame hex and the scale's own weight string`() {
        val frame = v10LockedFrame()
        val m = (codec().parse(fff1, frame) as ParseResult.LockedMeasurement).measurement
        assertEquals(frame.toList(), m.rawFrame?.toList())
        assertEquals(frame.joinToString("") { "%02x".format(it) }, m.rawFrameHex)
        // property 0x01 → kg, classic 1 decimal → "65.0"
        assertEquals("65.0", m.scaleWeightString)
    }

    @Test
    fun `v10 unlocked frame carries neither raw frame nor scale weight string`() {
        val frame = v10LockedFrame().also { it[4] = 0x00 }
        val m = (codec().parse(fff1, frame) as ParseResult.LiveMeasurement).measurement
        assertNull(m.rawFrame)
        assertNull(m.rawFrameHex)
        assertNull(m.scaleWeightString)
    }

    @Test
    fun `v10 rejects malformed frames`() {
        assertEquals(ParseResult.Unknown, codec().parse(fff1, bytes(0xCB, 0x10, 0x0E))) // bad header
        assertEquals(ParseResult.Unknown, codec().parse(fff1, bytes(0xCA, 0x10, 0x0E, 0x00))) // short
        assertEquals(ParseResult.Unknown, codec().parse(fff1, bytes(0xCA, 0x99, 0x0E, 0x00))) // bad version
        val zeroWeight = v10LockedFrame().also { it[5] = 0x00; it[6] = 0x00 }
        assertEquals(ParseResult.Unknown, codec().parse(fff1, zeroWeight))
    }

    // ------------------------------------------------------------------
    // Uplink: v11 sub-cmds 0/1
    // ------------------------------------------------------------------

    @Test
    fun `v11 simple locked frame`() {
        // CA 11 09 01 00 02 8A 00 00 00 00 01 → lockFlag=[3]=1, weight [5..6], property [11]
        val frame = bytes(0xCA, 0x11, 0x09, 0x01, 0x00, 0x02, 0x8A, 0x00, 0x00, 0x00, 0x00, 0x01)
        val m = (codec().parse(fff1, frame) as ParseResult.LockedMeasurement).measurement
        assertEquals(65.0, m.weightKg, 1e-9)
        assertTrue(m.lockFlag)
        assertEquals(0x01, m.scaleProperty)
    }

    @Test
    fun `v11 simple unlocked frame`() {
        val frame = bytes(0xCA, 0x11, 0x09, 0x00, 0x00, 0x02, 0x8A, 0x00, 0x00, 0x00, 0x00, 0x01)
        val m = (codec().parse(fff1, frame) as ParseResult.LiveMeasurement).measurement
        assertEquals(65.0, m.weightKg, 1e-9)
        assertFalse(m.lockFlag)
    }

    // ------------------------------------------------------------------
    // Uplink: v11 sub-cmd 0x12 / 0x13 multi-packet records
    // ------------------------------------------------------------------

    /**
     * 23-byte reassembled record:
     * time 1700000000 (65 53 F1 00), property 01, roleId 42, weight 650 (65.0 kg),
     * fat 215 (21.5%), water 550 (55.0%), muscle 300 (30.0%), bmr 1600,
     * visceral 80 (8.0), bone 24 (2.4 kg).
     * Muscle renormalization: 30.0 / 65.0 * 100 = 46.15 → trunc 46.1.
     */
    private val record = bytes(
        0x65, 0x53, 0xF1, 0x00,
        0x01, 0x00,
        0x00, 0x00, 0x00, 0x2A,
        0x02, 0x8A,
        0x00, 0xD7,
        0x02, 0x26,
        0x01, 0x2C,
        0x06, 0x40,
        0x00, 0x50,
        0x18,
    )

    private fun recordFrames(subCmd: Int): Pair<ByteArray, ByteArray> {
        val p1 = record.copyOfRange(0, 14)
        val p2 = record.copyOfRange(14, 23)
        val f1 = bytes(0xCA, 0x11, 0x10, subCmd, 0x12) + p1 // index 1 of 2, len = 14 + 2
        val f2 = bytes(0xCA, 0x11, 0x0B, subCmd, 0x22) + p2 // index 2 of 2, len = 9 + 2
        return f1 to f2
    }

    @Test
    fun `v11 0x12 current record reassembles out of order`() {
        val codec = codec()
        val (f1, f2) = recordFrames(0x12)
        assertEquals(ParseResult.Unknown, codec.parse(fff1, f2)) // last packet first: keep waiting
        val result = codec.parse(fff1, f1)
        val m = (result as ParseResult.LockedMeasurement).measurement
        assertEquals(65.0, m.weightKg, 1e-9)
        assertTrue(m.lockFlag) // forced locked
        assertEquals(42, m.roleId)
        assertEquals(1700000000L, m.timestampEpochSec)
        assertEquals(21.5, m.fatPct, 1e-9)
        assertEquals(55.0, m.waterPct, 1e-9)
        assertEquals(46.1, m.musclePct, 1e-9) // ratio branch: 30/65*100 = 46.15 → trunc 46.1
        assertEquals(1600, m.bmrKcal)
        assertEquals(8.0, m.visceralFat, 1e-9)
        assertEquals(80, m.oriVisceralFat)
        assertEquals(2.4, m.boneKg, 1e-9)
        assertFalse(m.isHistory)
    }

    @Test
    fun `v11 0x12 muscle renormalization ratio at least 50 falls back to raw muscle`() {
        val codec = codec()
        val rec = record.copyOf().also { it[16] = 0x01; it[17] = 0xC2.toByte() } // muscle 450 → 45%
        val f1 = bytes(0xCA, 0x11, 0x10, 0x12, 0x12) + rec.copyOfRange(0, 14)
        val f2 = bytes(0xCA, 0x11, 0x0B, 0x12, 0x22) + rec.copyOfRange(14, 23)
        assertEquals(ParseResult.Unknown, codec.parse(fff1, f1))
        // 45.0/65.0*100 = 69.2 ≥ 50 → fall back to the RAW 45.0
        val m = (codec.parse(fff1, f2) as ParseResult.LockedMeasurement).measurement
        assertEquals(45.0, m.musclePct, 1e-9)
    }

    @Test
    fun `v11 0x12 muscle renormalization caps raw muscle at 50 percent`() {
        val codec = codec()
        // muscle 520 → 52.0%; ratio 52/65*100 = 80 ≥ 50 → fallback to 52.0 ≥ 50 → 50.0
        val rec = record.copyOf().also { it[16] = 0x02; it[17] = 0x08.toByte() }
        val f1 = bytes(0xCA, 0x11, 0x10, 0x12, 0x12) + rec.copyOfRange(0, 14)
        val f2 = bytes(0xCA, 0x11, 0x0B, 0x12, 0x22) + rec.copyOfRange(14, 23)
        codec.parse(fff1, f1) // first packet: keep waiting
        val m = (codec.parse(fff1, f2) as ParseResult.LockedMeasurement).measurement
        assertEquals(50.0, m.musclePct, 1e-9)
    }

    @Test
    fun `v11 0x13 history record carries timestamp and history flag`() {
        val codec = codec()
        val (f1, f2) = recordFrames(0x13)
        assertEquals(ParseResult.Unknown, codec.parse(fff1, f1))
        val m = (codec.parse(fff1, f2) as ParseResult.HistoryRecord).measurement
        assertTrue(m.isHistory)
        assertEquals(1700000000L, m.timestampEpochSec)
        assertEquals(65.0, m.weightKg, 1e-9)
        // The reassembled record (not the individual packets) is the raw frame.
        assertEquals(record.toList(), m.rawFrame?.toList())
        assertEquals("65.0", m.scaleWeightString)
    }

    @Test
    fun `v11 0x13 history record with pre-2010 timestamp is dropped`() {
        val codec = codec()
        // 900000000 = 0x35A4E900 → 1998-07-…, outside the acceptance window
        val rec = record.copyOf().also {
            it[0] = 0x35; it[1] = 0xA4.toByte(); it[2] = 0xE9.toByte(); it[3] = 0x00
        }
        val f1 = bytes(0xCA, 0x11, 0x10, 0x13, 0x12) + rec.copyOfRange(0, 14)
        val f2 = bytes(0xCA, 0x11, 0x0B, 0x13, 0x22) + rec.copyOfRange(14, 23)
        codec.parse(fff1, f1)
        assertEquals(ParseResult.Unknown, codec.parse(fff1, f2))
    }

    // ------------------------------------------------------------------
    // Uplink: v11 sub-cmd 0x14 user-match
    // ------------------------------------------------------------------

    @Test
    fun `v11 0x14 user match single packet`() {
        // payload: weight 02 8A, property 01, roleIds 42, 43
        val frame = bytes(
            0xCA, 0x11, 0x0D, 0x14, 0x11,
            0x02, 0x8A, 0x01, 0x00, 0x00, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x2B,
        )
        val match = codec().parse(fff1, frame) as ParseResult.UserMatch
        assertEquals(listOf(42, 43), match.candidates)
        assertEquals(65.0, match.weightKg, 1e-9)
        assertEquals(0x01, match.scaleProperty)
    }

    @Test
    fun `v11 0x14 user match multi packet out of order`() {
        val codec = codec()
        // payload 15 bytes: weight 02 8A, property 01, roleIds 42, 43, 44; split 10 + 5
        val f1 = bytes(0xCA, 0x11, 0x0C, 0x14, 0x12, 0x02, 0x8A, 0x01, 0x00, 0x00, 0x00, 0x2A, 0x00, 0x00, 0x00)
        val f2 = bytes(0xCA, 0x11, 0x07, 0x14, 0x22, 0x2B, 0x00, 0x00, 0x00, 0x2C)
        assertEquals(ParseResult.Unknown, codec.parse(fff1, f2))
        val match = codec.parse(fff1, f1) as ParseResult.UserMatch
        assertEquals(listOf(42, 43, 44), match.candidates)
        assertEquals(65.0, match.weightKg, 1e-9)
    }

    @Test
    fun `v11 unknown sub command`() {
        assertEquals(ParseResult.Unknown, codec().parse(fff1, bytes(0xCA, 0x11, 0x02, 0x77, 0x00, 0x00)))
    }

    // ------------------------------------------------------------------
    // Downlink builders
    // ------------------------------------------------------------------

    @Test
    fun `xor checksum helper`() {
        val d = bytes(0xCA, 0x11, 0x05, 0x15, 0x00, 0x00, 0x00, 0x2A, 0x00)
        assertEquals(0x2B.toByte(), OkokClassicCodec.xor(d, 1, 7))
        assertEquals(0xCA.toByte(), OkokClassicCodec.xor(d, 0, 0))
    }

    @Test
    fun `v10 role info builder`() {
        val out = OkokClassicCodec.buildV10RoleInfo(
            roleId = 0x01020304,
            male = true,
            age = 30,
            heightCm = 175,
            time = LocalDateTime.of(2026, 10, 8, 12, 34, 56),
        )
        // XOR of bytes 1..16 computed by hand: 0xA5
        assertArrayEquals(
            bytes(
                0xCA, 0x10, 0x0E, 0x01,
                0x1A, 0x0A, 0x08, 0x0C, 0x22, 0x38, // 26-10-08 12:34:56
                0x01, 0x02, 0x03, 0x04, // roleId
                0x01, 0x1E, 0xAF, // male, 30, 175 cm
                0xA5, 0x00, 0x00,
            ),
            out,
        )
    }

    @Test
    fun `v10 role info builder female`() {
        val out = OkokClassicCodec.buildV10RoleInfo(
            roleId = 1,
            male = false,
            age = 25,
            heightCm = 160,
            time = LocalDateTime.of(2026, 1, 2, 3, 4, 5),
        )
        assertEquals(0x00.toByte(), out[14]) // female
        assertEquals(25.toByte(), out[15])
        assertEquals(160.toByte(), out[16])
        assertEquals(OkokClassicCodec.xor(out, 1, 16), out[17])
        assertEquals(20, out.size)
    }

    @Test
    fun `v11 all-users sync single user`() {
        val frames = OkokClassicCodec.buildV11AllUsersSync(
            users = listOf(OkokClassicCodec.RoleProfile(42, male = true, age = 30, heightCm = 175, weightKgTimes10 = 650)),
            epochSec = 1700000000L,
        )
        assertEquals(1, frames.size)
        // XOR of bytes 1..18 computed by hand: 0x54
        assertArrayEquals(
            bytes(
                0xCA, 0x11, 0x10, 0x10, 0x11,
                0x65, 0x53, 0xF1, 0x00, 0x00, 0x00, // time + 2 zeros
                0x00, 0x00, 0x00, 0x2A, 0x9E, 0xAF, 0x02, 0x8A, // role
                0x54,
            ),
            frames[0],
        )
    }

    @Test
    fun `v11 all-users sync three users chunks into 14-byte packets`() {
        val frames = OkokClassicCodec.buildV11AllUsersSync(
            users = listOf(
                OkokClassicCodec.RoleProfile(1, male = true, age = 30, heightCm = 175, weightKgTimes10 = 600),
                OkokClassicCodec.RoleProfile(2, male = false, age = 25, heightCm = 160, weightKgTimes10 = 500),
                OkokClassicCodec.RoleProfile(3, male = true, age = 40, heightCm = 180, weightKgTimes10 = 800),
            ),
            epochSec = 1700000000L,
        )
        assertEquals(3, frames.size)
        assertArrayEquals(
            bytes(
                0xCA, 0x11, 0x10, 0x10, 0x13,
                0x65, 0x53, 0xF1, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x9E, 0xAF, 0x02, 0x58,
                0xAF,
            ),
            frames[0],
        )
        assertArrayEquals(
            bytes(
                0xCA, 0x11, 0x10, 0x10, 0x23,
                0x00, 0x00, 0x00, 0x02, 0x19, 0xA0, 0x01, 0xF4, 0x00, 0x00, 0x00, 0x03, 0xA8, 0xB4,
                0x63,
            ),
            frames[1],
        )
        assertArrayEquals(
            bytes(0xCA, 0x11, 0x04, 0x10, 0x33, 0x03, 0x20, 0x15),
            frames[2],
        )
    }

    @Test
    fun `v11 history retrieve builder`() {
        val out = OkokClassicCodec.buildV11HistoryRetrieve()
        assertEquals(20, out.size)
        assertArrayEquals(
            bytes(0xCA, 0x11, 0x02, 0x11, 0x01, 0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            out,
        )
    }

    @Test
    fun `select user builder`() {
        assertArrayEquals(
            bytes(0xCA, 0x11, 0x05, 0x15, 0x00, 0x00, 0x00, 0x2A, 0x2B),
            OkokClassicCodec.buildSelectUser(42),
        )
    }
}
