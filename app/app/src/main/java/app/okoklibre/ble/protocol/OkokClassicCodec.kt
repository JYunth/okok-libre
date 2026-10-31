package app.okoklibre.ble.protocol

import java.time.LocalDateTime

/**
 * OKOK classic (0xCA) protocol codec.
 *
 * Uplink: protocol version 0x10 single-frame real-time data, and version 0x11
 * sub-commands (0/1 locked simple-scale data, 0x12 current multi-packet record,
 * 0x13 history multi-packet record, 0x14 user-match). Incomplete multi-packet
 * sequences yield [ParseResult.Unknown] until the last packet completes them.
 *
 * Downlink builders live in the companion object.
 *
 * The codec is stateful (multi-packet reassembly buffers); create one instance
 * per connected scale.
 */
class OkokClassicCodec(
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val currentAssembler = MultiPacketAssembler()
    private val historyAssembler = MultiPacketAssembler()
    private val userMatchAssembler = MultiPacketAssembler()

    fun parse(characteristicUuid: String, data: ByteArray): ParseResult {
        if (data.size < 4 || data[0] != FRAME_START) return ParseResult.Unknown
        return when (data[1].toInt() and 0xFF) {
            0x10 -> parseV10(data)
            0x11 -> parseV11(data)
            else -> ParseResult.Unknown
        }
    }

    /** Version 0x10 real-time frame (needs 18 bytes). */
    private fun parseV10(d: ByteArray): ParseResult {
        if (d.size < 18) return ParseResult.Unknown
        val property = d[4].toInt() and 0xFF
        val weightKg = WeightDecoder.decodeToKg(
            d[5], d[6], property, PropertyByte.decimalsClassic(property),
        ) ?: return ParseResult.Unknown
        val locked = PropertyByte.lockFlag(property)
        val decimals = PropertyByte.decimalsClassic(property)
        val measurement = if (locked) {
            Measurement(
                weightKg = weightKg,
                lockFlag = true,
                fatPct = Measurement.fatFromRaw(be16(d, 7)),
                waterPct = Measurement.waterFromRaw(be16(d, 9)),
                musclePct = Measurement.muscleFromRaw(be16(d, 11)),
                bmrKcal = Measurement.bmrFromRaw(be16(d, 13)),
                visceralFat = Measurement.visceralFromRaw(be16(d, 15)),
                oriVisceralFat = be16(d, 15),
                boneKg = Measurement.boneFromRaw(d[17].toInt() and 0xFF),
                scaleProperty = property,
                decimals = decimals,
                scaleWeightString = WeightDecoder.scaleWeightString(d[5], d[6], property, decimals),
                rawFrame = d,
            )
        } else {
            // Composition fields are only valid once the weight is locked.
            Measurement(weightKg = weightKg, lockFlag = false, scaleProperty = property, decimals = decimals)
        }
        return if (locked) ParseResult.LockedMeasurement(measurement) else ParseResult.LiveMeasurement(measurement)
    }

    /** Version 0x11, sub-command in [3]. */
    private fun parseV11(d: ByteArray): ParseResult {
        return when (val subCmd = d[3].toInt() and 0xFF) {
            0, 1 -> parseV11Simple(d, subCmd)
            0x12 -> parseV11Record(d, currentAssembler, isHistory = false)
            0x13 -> parseV11Record(d, historyAssembler, isHistory = true)
            0x14 -> parseV11UserMatch(d)
            else -> ParseResult.Unknown
        }
    }

    /** v11 sub-cmd 0/1: locked simple-scale data; lockFlag = sub-cmd. */
    private fun parseV11Simple(d: ByteArray, subCmd: Int): ParseResult {
        if (d.size < 12) return ParseResult.Unknown
        val property = d[11].toInt() and 0xFF
        val weightKg = WeightDecoder.decodeToKg(
            d[5], d[6], property, PropertyByte.decimalsClassic(property),
        ) ?: return ParseResult.Unknown
        val locked = subCmd == 1
        val measurement = Measurement(
            weightKg = weightKg,
            lockFlag = locked,
            scaleProperty = property,
            decimals = PropertyByte.decimalsClassic(property),
            scaleWeightString = if (locked) {
                WeightDecoder.scaleWeightString(d[5], d[6], property, PropertyByte.decimalsClassic(property))
            } else {
                null
            },
            rawFrame = if (locked) d else null,
        )
        return if (locked) ParseResult.LockedMeasurement(measurement) else ParseResult.LiveMeasurement(measurement)
    }

    /** v11 sub-cmd 0x12 / 0x13: multi-packet full record (23 bytes reassembled). */
    private fun parseV11Record(
        d: ByteArray,
        assembler: MultiPacketAssembler,
        isHistory: Boolean,
    ): ParseResult {
        val payload = packetPayload(d) ?: return ParseResult.Unknown
        val rec = assembler.add(d[4].toInt() and 0xFF, payload) ?: return ParseResult.Unknown
        if (rec.size < 23) return ParseResult.Unknown
        val property = rec[4].toInt() and 0xFF
        val weightKg = WeightDecoder.decodeToKg(
            rec[10], rec[11], property, PropertyByte.decimalsClassic(property),
        ) ?: return ParseResult.Unknown
        val timestamp = be32u(rec, 0)
        if (isHistory && !isHistoryTimestampValid(timestamp, nowEpochSec())) {
            return ParseResult.Unknown
        }
        // Muscle renormalization: if
        // muscle% / weightKg * 100 ≥ 50, fall back to the RAW muscle percent
        // (capped at 50); otherwise use the ratio. Truncated to one decimal
        // as in the original ((int)(x*10)/10).
        val musclePct = Measurement.muscleFromRaw(be16(rec, 16))
        var muscle = musclePct / weightKg * 100.0
        if (muscle >= 50.0) {
            muscle = if (musclePct >= 50.0) 50.0 else musclePct
        }
        val muscleFinal = (muscle * 10.0).toInt() / 10.0
        val measurement = Measurement(
            weightKg = weightKg,
            lockFlag = true, // reassembled records are always locked
            fatPct = Measurement.fatFromRaw(be16(rec, 12)),
            waterPct = Measurement.waterFromRaw(be16(rec, 14)),
            musclePct = muscleFinal,
            bmrKcal = Measurement.bmrFromRaw(be16(rec, 18)),
            visceralFat = Measurement.visceralFromRaw(be16(rec, 20)),
            oriVisceralFat = be16(rec, 20),
            boneKg = Measurement.boneFromRaw(rec[22].toInt() and 0xFF),
            timestampEpochSec = timestamp, // meaningful for 0x13 history records
            roleId = be32(rec, 6),
            scaleProperty = property,
            decimals = PropertyByte.decimalsClassic(property),
            isHistory = isHistory,
            scaleWeightString = WeightDecoder.scaleWeightString(
                rec[10], rec[11], property, PropertyByte.decimalsClassic(property),
            ),
            rawFrame = rec,
        )
        return if (isHistory) ParseResult.HistoryRecord(measurement) else ParseResult.LockedMeasurement(measurement)
    }

    /** v11 sub-cmd 0x14: user-match response, possibly multi-packet. */
    private fun parseV11UserMatch(d: ByteArray): ParseResult {
        val payload = packetPayload(d) ?: return ParseResult.Unknown
        val rec = userMatchAssembler.add(d[4].toInt() and 0xFF, payload) ?: return ParseResult.Unknown
        if (rec.size < 3) return ParseResult.Unknown
        val property = rec[2].toInt() and 0xFF
        val weightKg = WeightDecoder.decodeToKg(
            rec[0], rec[1], property, PropertyByte.decimalsClassic(property),
        ) ?: return ParseResult.Unknown
        val candidates = ArrayList<Int>((rec.size - 3) / 4)
        var i = 3
        while (i + 4 <= rec.size) {
            candidates.add(be32(rec, i))
            i += 4
        }
        return ParseResult.UserMatch(candidates, weightKg, property)
    }

    /** Multi-packet frames carry [len - 2] payload bytes starting at offset 5. */
    private fun packetPayload(d: ByteArray): ByteArray? {
        if (d.size < 5) return null
        val payloadLen = (d[2].toInt() and 0xFF) - 2
        if (payloadLen < 0 || d.size < 5 + payloadLen) return null
        return d.copyOfRange(5, 5 + payloadLen)
    }

    private fun be16(d: ByteArray, offset: Int): Int =
        ((d[offset].toInt() and 0xFF) shl 8) or (d[offset + 1].toInt() and 0xFF)

    private fun be32(d: ByteArray, offset: Int): Int =
        ((d[offset].toInt() and 0xFF) shl 24) or
            ((d[offset + 1].toInt() and 0xFF) shl 16) or
            ((d[offset + 2].toInt() and 0xFF) shl 8) or
            (d[offset + 3].toInt() and 0xFF)

    private fun be32u(d: ByteArray, offset: Int): Long = be32(d, offset).toLong() and 0xFFFFFFFFL

    /** User profile row for the v11 all-users sync (8 wire bytes each). */
    data class RoleProfile(
        val roleId: Int,
        val male: Boolean,
        val age: Int,
        val heightCm: Int,
        /** kg × 10, sent as a big-endian short. */
        val weightKgTimes10: Int,
    )

    companion object {
        const val FRAME_START: Byte = 0xCA.toByte()

        /** XOR checksum of bytes [from..to] inclusive — ends every downlink frame. */
        fun xor(bytes: ByteArray, from: Int, to: Int): Byte {
            var result = bytes[from]
            for (i in from + 1..to) result = (result.toInt() xor bytes[i].toInt()).toByte()
            return result
        }

        /**
         * V10 role info + time sync, 20 bytes
         * (written 4× at 150 ms after connect).
         */
        fun buildV10RoleInfo(
            roleId: Int,
            male: Boolean,
            age: Int,
            heightCm: Int,
            time: LocalDateTime,
        ): ByteArray {
            val out = ByteArray(20)
            out[0] = FRAME_START
            out[1] = 0x10
            out[2] = 0x0E
            out[3] = 0x01
            out[4] = (time.year % 100).toByte()
            out[5] = time.monthValue.toByte()
            out[6] = time.dayOfMonth.toByte()
            out[7] = time.hour.toByte()
            out[8] = time.minute.toByte()
            out[9] = time.second.toByte()
            putIntBE(out, roleId, 10)
            out[14] = (if (male) 1 else 0).toByte()
            out[15] = age.toByte()
            out[16] = heightCm.toByte()
            out[17] = xor(out, 1, 16)
            out[18] = 0
            out[19] = 0
            return out
        }

        /**
         * V11 all-users sync. Payload: unix time BE + 2 zero bytes +
         * 8 bytes per role (roleId BE | sexAge | height | weight BE), chunked
         * into packets of at most 14 payload bytes; packet field =
         * (index << 4) | total; last byte = XOR of bytes 1..lastPayloadByte.
         */
        fun buildV11AllUsersSync(users: List<RoleProfile>, epochSec: Long): List<ByteArray> {
            require(users.isNotEmpty()) { "at least one user required" }
            val payload = ByteArray(6 + users.size * 8)
            putIntBE(payload, epochSec.toInt(), 0)
            payload[4] = 0
            payload[5] = 0
            users.forEachIndexed { i, u ->
                val o = 6 + i * 8
                putIntBE(payload, u.roleId, o)
                payload[o + 4] = sexAge(u.male, u.age)
                payload[o + 5] = u.heightCm.toByte()
                putShortBE(payload, u.weightKgTimes10, o + 6)
            }
            val total = (payload.size + 13) / 14
            return (0 until total).map { i ->
                val chunk = payload.copyOfRange(i * 14, minOf((i + 1) * 14, payload.size))
                val frame = ByteArray(chunk.size + 6)
                frame[0] = FRAME_START
                frame[1] = 0x11
                frame[2] = (chunk.size + 2).toByte()
                frame[3] = 0x10
                frame[4] = (((i + 1) shl 4) or total).toByte()
                chunk.copyInto(frame, 5)
                frame[frame.size - 1] = xor(frame, 1, frame.size - 2)
                frame
            }
        }

        /** V11 history retrieve (sent after a locked v11 measurement). */
        fun buildV11HistoryRetrieve(): ByteArray {
            val out = ByteArray(20)
            out[0] = FRAME_START
            out[1] = 0x11
            out[2] = 0x02
            out[3] = 0x11
            out[4] = 0x01
            out[5] = xor(out, 1, 4)
            return out
        }

        /** Select user (answer to a 0x14 user-match response). */
        fun buildSelectUser(roleId: Int): ByteArray {
            val out = ByteArray(9)
            out[0] = FRAME_START
            out[1] = 0x11
            out[2] = 0x05
            out[3] = 0x15
            putIntBE(out, roleId, 4)
            out[8] = xor(out, 1, 7)
            return out
        }

        private fun sexAge(male: Boolean, age: Int): Byte =
            if (male) (age or 0x80).toByte() else (age and 0x7F).toByte()

        private fun putIntBE(target: ByteArray, value: Int, offset: Int) {
            target[offset] = (value shr 24).toByte()
            target[offset + 1] = (value shr 16).toByte()
            target[offset + 2] = (value shr 8).toByte()
            target[offset + 3] = value.toByte()
        }

        private fun putShortBE(target: ByteArray, value: Int, offset: Int) {
            target[offset] = (value shr 8).toByte()
            target[offset + 1] = value.toByte()
        }
    }
}
