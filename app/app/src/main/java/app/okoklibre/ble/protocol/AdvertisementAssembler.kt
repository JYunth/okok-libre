package app.okoklibre.ble.protocol

/**
 * Rebuilds the original OKOK manufacturer-data byte stream from Android's
 * split representation.
 *
 * Android's `ScanRecord.getManufacturerSpecificData()` uses the 2-byte company
 * ID as the sparse-array key and strips it from the value, but the OKOK
 * protocol encodes its frame header in the company ID itself (0x10CA/0x11CA/
 * 0x20CA, 0xC0xx, 0xF0FF). The original app walks raw AD structures and keeps
 * the company ID in the parsed payload, so [BroadcastParser] expects bytes
 * starting at the 0xCA/0xC0/0xFF header. This helper prepends the company ID
 * back (little-endian, as on the air) before parsing.
 */
object AdvertisementAssembler {

    fun fromAndroidManufacturerData(companyId: Int, payload: ByteArray): ByteArray =
        byteArrayOf((companyId and 0xFF).toByte(), ((companyId shr 8) and 0xFF).toByte()) + payload
}
