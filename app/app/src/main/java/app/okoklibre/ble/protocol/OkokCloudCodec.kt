package app.okoklibre.ble.protocol

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * OKOKCloud protocol codec.
 *
 * Uplink frames (min 14 bytes; 16 needed for the property byte):
 * `[0]=0x02 [1]=0x00` weight-only scale, `[0]=0x06 [1]=0x03|0x23` fat scale;
 * all multi-byte fields little-endian; the measurement timestamp is only
 * present (and only meaningful) on locked/history frames. The frame state is
 * decided by the characteristic: fff1 → live, 2a9c → locked, fa9c → history.
 *
 * Stateless; safe to share.
 */
class OkokCloudCodec(
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /**
     * @param characteristicUuid UUID of the characteristic the frame arrived on
     * @param data raw characteristic value
     * @param productId bound device product id (special ids force property = 4)
     */
    fun parse(characteristicUuid: String, data: ByteArray, productId: Int = 0): ParseResult {
        if (data.size < 16) return ParseResult.Unknown
        val fatScale = when {
            data[0] == 0x02.toByte() && data[1] == 0x00.toByte() -> false
            data[0] == 0x06.toByte() && (data[1] == 0x03.toByte() || data[1] == 0x23.toByte()) -> true
            else -> return ParseResult.Unknown
        }
        val property = if (productId == BroadcastParser.PRODUCT_ID_LEGACY_FA ||
            productId == BroadcastParser.PRODUCT_ID_FORCE_PROPERTY_4
        ) {
            4
        } else {
            data[15].toInt() and 0xFF
        }
        val weightKg = WeightDecoder.decodeToKg(
            data[11], data[12], property,
            PropertyByte.decimalsCloud(property), WeightDecoder.Endianness.LITTLE,
        ) ?: return ParseResult.Unknown
        val impedanceOhm = if (fatScale) le16(data, 9) * 0.1 else 0.0
        val isLive = GattUuids.isLive(characteristicUuid)
        val isHistory = GattUuids.isHistory(characteristicUuid)
        val timestamp = if (isLive) null else frameTimestamp(data) ?: return ParseResult.Unknown
        if (isHistory && timestamp != null &&
            !isHistoryTimestampValid(timestamp, nowEpochSec())
        ) {
            return ParseResult.Unknown
        }
        val measurement = Measurement(
            weightKg = weightKg,
            lockFlag = !isLive,
            impedanceOhm = impedanceOhm,
            timestampEpochSec = timestamp,
            scaleProperty = property,
            decimals = PropertyByte.decimalsCloud(property),
            isHistory = isHistory,
            scaleWeightString = if (isLive) {
                null
            } else {
                WeightDecoder.scaleWeightString(
                    data[11], data[12], property,
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

    /** [2..3]=year LE, [4]=month, [5]=day, [6]=h, [7]=m, [8]=s — local time. */
    private fun frameTimestamp(d: ByteArray): Long? {
        val year = le16(d, 2)
        val month = d[4].toInt() and 0xFF
        val day = d[5].toInt() and 0xFF
        val hour = d[6].toInt() and 0xFF
        val minute = d[7].toInt() and 0xFF
        val second = d[8].toInt() and 0xFF
        return try {
            LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZoneId.systemDefault())
                .toEpochSecond()
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun le16(d: ByteArray, offset: Int): Int =
        ((d[offset + 1].toInt() and 0xFF) shl 8) or (d[offset].toInt() and 0xFF)

    companion object {
        /**
         * Connect sequence step 2 — 7-byte time write to 1805/2a08:
         * `[yearLo, yearHi, month(1-12), day, hour, min, sec]` (little-endian year).
         */
        fun buildTimeWrite(time: LocalDateTime): ByteArray = byteArrayOf(
            (time.year and 0xFF).toByte(),
            (time.year shr 8).toByte(),
            time.monthValue.toByte(),
            time.dayOfMonth.toByte(),
            time.hour.toByte(),
            time.minute.toByte(),
            time.second.toByte(),
        )

        /** Connect sequence step 3 — `{0x01, unit}` unit write to fff2. */
        fun buildUnitWrite(unit: WeightUnit): ByteArray =
            byteArrayOf(0x01, unit.writeCode.toByte())
    }
}
