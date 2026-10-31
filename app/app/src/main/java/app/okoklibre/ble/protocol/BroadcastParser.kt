package app.okoklibre.ble.protocol

/**
 * Parsed BLE advertising manufacturer-data payload.
 *
 * [weightKg] / [impedanceOhm] / [measureSeqNo] are only present when the frame
 * carries a (locked) measurement; broadcast frames with lockFlag = 0 carry none.
 * [scaleProperty] is the raw property byte as advertised (no re-mapping).
 */
data class ScaleAdvertisement(
    val mac: String,
    val protocolType: ScaleProtocolType,
    val version: Int,
    val deviceType: Int,
    val scaleProperty: Int,
    val weightKg: Double? = null,
    val impedanceOhm: Double? = null,
    val productId: Int = 0,
    val measureSeqNo: Int? = null,
)

/**
 * Parses manufacturer-data payloads (bytes after AD type 0xFF) into
 * [ScaleAdvertisement]. Returns null for unknown headers or truncated frames.
 *
 * Supported shapes:
 *  - 0xCA version 0x10 / 0x11 → [ScaleProtocolType.OKOK_CLASSIC]
 *  - 0xCA version 0x20        → [ScaleProtocolType.OKOK_CLASSIC]
 *  - 0xC0                     → [ScaleProtocolType.OKOK_CLASSIC]
 *  - 0xFF 0xF0 xx             → OKOKCloud, or OKOKCloudV4 when xx ∈ {0x10, 0x11}
 */
object BroadcastParser {

    /** productId forced when the advertised id bytes are FA FB FC FD. */
    const val PRODUCT_ID_LEGACY_FA = 997264800

    /** productId that forces scaleProperty = 4 (kg, 1 decimal). */
    const val PRODUCT_ID_FORCE_PROPERTY_4 = 1104000001

    fun parse(mac: String, manufacturerData: ByteArray): ScaleAdvertisement? {
        if (manufacturerData.size < 2) return null
        return when (manufacturerData[0].toInt() and 0xFF) {
            0xCA -> parseCa(mac, manufacturerData)
            0xC0 -> parseC0(mac, manufacturerData)
            0xFF -> parseCloud(mac, manufacturerData)
            else -> null
        }
    }

    /** 0xCA, versions 0x10 / 0x11 / 0x20. */
    private fun parseCa(mac: String, d: ByteArray): ScaleAdvertisement? {
        if (d.size < 3) return null
        return when (val version = d[1].toInt() and 0xFF) {
            0x10, 0x11 -> parseCaV10V11(mac, d, version)
            0x20 -> parseCaV20(mac, d)
            else -> null
        }
    }

    /**
     * V0x10 → [3]=deviceType [4]=scaleProperty; v0x11 → swapped.
     * deviceType != 0 → weight [5..6] BE, productId [7..10] BE.
     * deviceType == 0 (plain weigher): v0x10 weight = [8..9] BE / 10 kg.
     */
    private fun parseCaV10V11(mac: String, d: ByteArray, version: Int): ScaleAdvertisement? {
        if (d.size < 11) return null
        val deviceType: Int
        val property: Int
        if (version == 0x10) {
            deviceType = d[3].toInt() and 0xFF
            property = d[4].toInt() and 0xFF
        } else {
            property = d[3].toInt() and 0xFF
            deviceType = d[4].toInt() and 0xFF
        }
        var weightKg: Double? = null
        var productId = 0
        if (deviceType != 0) {
            weightKg = classicWeight(d[5], d[6], property)
            productId = be32(d, 7)
        } else if (version == 0x10) {
            weightKg = be16(d, 8) / 10.0
        } else {
            weightKg = classicWeight(d[5], d[6], property)
            productId = be32(d, 7)
        }
        return ScaleAdvertisement(
            mac = mac,
            protocolType = ScaleProtocolType.OKOK_CLASSIC,
            version = version,
            deviceType = deviceType,
            scaleProperty = property,
            weightKg = weightKg,
            productId = productId,
        )
    }

    /** Measurement fields only when cmdId (property bit0) > 0. */
    private fun parseCaV20(mac: String, d: ByteArray): ScaleAdvertisement? {
        if (d.size < 9) return null
        val productId = be32(d, 3)
        val deviceType = if (d[7].toInt() == 0) 0 else 2
        val property = d[8].toInt() and 0xFF
        var seqNo: Int? = null
        var weightKg: Double? = null
        var impedanceOhm: Double? = null
        if (PropertyByte.lockFlag(property)) {
            if (d.size < 14) return null
            seqNo = d[9].toInt() and 0xFF
            weightKg = classicWeight(d[10], d[11], property)
            impedanceOhm = be16(d, 12) / 10.0
        }
        return ScaleAdvertisement(
            mac = mac,
            protocolType = ScaleProtocolType.OKOK_CLASSIC,
            version = 0x20,
            deviceType = deviceType,
            scaleProperty = property,
            weightKg = weightKg,
            impedanceOhm = impedanceOhm,
            productId = productId,
            measureSeqNo = seqNo,
        )
    }

    /** DeviceType = bit5 of scaleProperty (0→0, 1→2). */
    private fun parseC0(mac: String, d: ByteArray): ScaleAdvertisement? {
        if (d.size < 9) return null
        val property = d[8].toInt() and 0xFF
        val productId = be16(d, 6)
        val deviceType = if (property and 0b10_0000 == 0) 0 else 2
        var seqNo: Int? = null
        var weightKg: Double? = null
        var impedanceOhm: Double? = null
        if (PropertyByte.lockFlag(property)) {
            seqNo = d[1].toInt() and 0xFF
            weightKg = classicWeight(d[2], d[3], property)
            impedanceOhm = be16(d, 4) / 10.0
        }
        return ScaleAdvertisement(
            mac = mac,
            protocolType = ScaleProtocolType.OKOK_CLASSIC,
            version = 1,
            deviceType = deviceType,
            scaleProperty = property,
            weightKg = weightKg,
            impedanceOhm = impedanceOhm,
            productId = productId,
            measureSeqNo = seqNo,
        )
    }

    /**
     * Weight [4..5] LE; v0x10/0x11 (CloudV4) uses the cloudV4 decimal
     * mapping, other versions the cloud mapping. productId special cases force
     * the property byte to 4 before the weight is decoded.
     */
    private fun parseCloud(mac: String, d: ByteArray): ScaleAdvertisement? {
        if (d.size < 10) return null
        if ((d[1].toInt() and 0xFF) != 0xF0) return null
        val version = d[2].toInt() and 0xFF
        var property = d[3].toInt() and 0xFF
        var productId = be32(d, 6)
        if (d[6] == 0xFA.toByte() && d[7] == 0xFB.toByte() &&
            d[8] == 0xFC.toByte() && d[9] == 0xFD.toByte()
        ) {
            productId = PRODUCT_ID_LEGACY_FA
            property = 4
        } else if (productId == PRODUCT_ID_FORCE_PROPERTY_4) {
            property = 4
        }
        val decimals = if (version == 0x10 || version == 0x11) {
            PropertyByte.decimalsCloudV4(property)
        } else {
            PropertyByte.decimalsCloud(property)
        }
        val weightKg = WeightDecoder.decodeToKg(
            d[4], d[5], property, decimals, WeightDecoder.Endianness.LITTLE,
        )
        return ScaleAdvertisement(
            mac = mac,
            protocolType = if (version == 0x10 || version == 0x11) {
                ScaleProtocolType.OKOK_CLOUD_V4
            } else {
                ScaleProtocolType.OKOK_CLOUD
            },
            version = version,
            deviceType = 1,
            scaleProperty = property,
            weightKg = weightKg,
            productId = productId,
        )
    }

    private fun classicWeight(hi: Byte, lo: Byte, property: Int): Double? =
        WeightDecoder.decodeToKg(hi, lo, property, PropertyByte.decimalsClassic(property))

    private fun be16(d: ByteArray, offset: Int): Int =
        ((d[offset].toInt() and 0xFF) shl 8) or (d[offset + 1].toInt() and 0xFF)
    private fun be32(d: ByteArray, offset: Int): Int =
        ((d[offset].toInt() and 0xFF) shl 24) or
            ((d[offset + 1].toInt() and 0xFF) shl 16) or
            ((d[offset + 2].toInt() and 0xFF) shl 8) or
            (d[offset + 3].toInt() and 0xFF)
}

/**
 * Display digit precision implied by the frame's protocol family (used when a
 * broadcast frame is turned into a [Measurement] outside a codec).
 */
fun ScaleAdvertisement.decimals(): Int = when (protocolType) {
    ScaleProtocolType.OKOK_CLASSIC -> PropertyByte.decimalsClassic(scaleProperty)
    ScaleProtocolType.OKOK_CLOUD -> PropertyByte.decimalsCloud(scaleProperty)
    ScaleProtocolType.OKOK_CLOUD_V4 -> PropertyByte.decimalsCloudV4(scaleProperty)
}
