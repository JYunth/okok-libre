package app.okoklibre.ble.protocol

/**
 * OKOKCloudV4 protocol codec (heart-rate capable).
 *
 * Uplink frames: 0xC5 (fat + heart rate, 11 bytes) and 0xC4 (same minus the
 * heart-rate byte, 10 bytes). Weight and impedance are little-endian; the unix
 * timestamp is a big-endian 32-bit value present on locked/history frames.
 * Property byte: bit7 = heart-rate measurement in progress, bit6 = impedance
 * measurement in progress, bits 4:3 = unit; decimals use the cloud mapping on
 * bits 2:1 for parsing. State is decided by the
 * characteristic exactly as in [OkokCloudCodec].
 *
 * Stateless; safe to share.
 */
class OkokCloudV4Codec(
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun parse(characteristicUuid: String, data: ByteArray): ParseResult {
        if (data.isEmpty()) return ParseResult.Unknown
        val hasHeartRate = when (data[0]) {
            FRAME_C5 -> true
            FRAME_C4 -> false
            else -> return ParseResult.Unknown
        }
        if (data.size < if (hasHeartRate) 11 else 10) return ParseResult.Unknown
        val property = data[1].toInt() and 0xFF
        val weightKg = WeightDecoder.decodeToKg(
            data[6], data[7], property,
            PropertyByte.decimalsCloud(property), WeightDecoder.Endianness.LITTLE,
        ) ?: return ParseResult.Unknown
        val impedanceOhm = le16(data, 8) * 0.1
        val heartRateBpm = if (hasHeartRate) {
            (data[10].toInt() and 0xFF).takeIf { it > 0 }
        } else {
            null
        }
        val isLive = GattUuids.isLive(characteristicUuid)
        val isHistory = GattUuids.isHistory(characteristicUuid)
        val timestamp = if (isLive) null else be32u(data, 2)
        if (isHistory && timestamp != null &&
            !isHistoryTimestampValid(timestamp, nowEpochSec())
        ) {
            return ParseResult.Unknown
        }
        val measurement = Measurement(
            weightKg = weightKg,
            lockFlag = !isLive,
            impedanceOhm = impedanceOhm,
            heartRateBpm = heartRateBpm,
            timestampEpochSec = timestamp,
            scaleProperty = property,
            decimals = PropertyByte.decimalsCloud(property),
            isHistory = isHistory,
            heartRateMeasuring = hasHeartRate && property and 0x80 != 0,
            impedanceMeasuring = hasHeartRate && property and 0x40 != 0,
            scaleWeightString = if (isLive) {
                null
            } else {
                WeightDecoder.scaleWeightString(
                    data[6], data[7], property,
                    PropertyByte.decimalsCloud(property), WeightDecoder.Endianness.LITTLE,
                )
            },
            rawFrame = if (isLive) null else data,
        )
        return when {
            isLive -> ParseResult.LiveMeasurement(measurement)
            isHistory -> ParseResult.HistoryRecord(measurement)
            else -> ParseResult.LockedMeasurement(measurement)
        }
    }

    private fun le16(d: ByteArray, offset: Int): Int =
        ((d[offset + 1].toInt() and 0xFF) shl 8) or (d[offset].toInt() and 0xFF)

    private fun be32u(d: ByteArray, offset: Int): Long =
        (((d[offset].toInt() and 0xFF).toLong() shl 24) or
            ((d[offset + 1].toInt() and 0xFF).toLong() shl 16) or
            ((d[offset + 2].toInt() and 0xFF).toLong() shl 8) or
            (d[offset + 3].toInt() and 0xFF).toLong())

    companion object {
        const val FRAME_C4: Byte = 0xC4.toByte()
        const val FRAME_C5: Byte = 0xC5.toByte()

        /** Connect sequence step 1 — 4-byte big-endian unix-time write to 1805/2a08. */
        fun buildTimeWrite(epochSec: Long): ByteArray = byteArrayOf(
            (epochSec shr 24).toByte(),
            (epochSec shr 16).toByte(),
            (epochSec shr 8).toByte(),
            epochSec.toByte(),
        )

        /** Connect sequence step 2 — `{0x01, unit}` unit write to fff2. */
        fun buildUnitWrite(unit: WeightUnit): ByteArray =
            byteArrayOf(0x01, unit.writeCode.toByte())

        /**
         * Connect sequence step 3 — 9-byte `0xA0` user profile write to fff2:
         * `[0]=0xA0 [1..4]=roleId BE [5]=sexAge (bit7=male, low7=age)
         * [6]=height cm [7..8]=weight goal BE short (kg×10; send 0)`.
         */
        fun buildUserProfile(
            roleId: Int,
            male: Boolean,
            age: Int,
            heightCm: Int,
            goalKgTimes10: Int = 0,
        ): ByteArray = byteArrayOf(
            0xA0.toByte(),
            (roleId shr 24).toByte(),
            (roleId shr 16).toByte(),
            (roleId shr 8).toByte(),
            roleId.toByte(),
            if (male) (age or 0x80).toByte() else (age and 0x7F).toByte(),
            heightCm.toByte(),
            (goalKgTimes10 shr 8).toByte(),
            goalKgTimes10.toByte(),
        )
    }
}
