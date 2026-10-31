package app.okoklibre.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BroadcastParserTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `0xCA v10 fat scale with weight and productId`() {
        // CA 10 09 01 01 02 8A 00 00 30 39
        // deviceType=1, property=0x01 (kg, 1 decimal, locked), weight BE 650 → 65.0 kg
        val adv = BroadcastParser.parse(mac, bytes(0xCA, 0x10, 0x09, 0x01, 0x01, 0x02, 0x8A, 0x00, 0x00, 0x30, 0x39))!!
        assertEquals(mac, adv.mac)
        assertEquals(ScaleProtocolType.OKOK_CLASSIC, adv.protocolType)
        assertEquals(0x10, adv.version)
        assertEquals(1, adv.deviceType)
        assertEquals(0x01, adv.scaleProperty)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
        assertEquals(12345, adv.productId)
        assertNull(adv.impedanceOhm)
        assertNull(adv.measureSeqNo)
    }

    @Test
    fun `0xCA v11 swaps deviceType and scaleProperty`() {
        // CA 11 09 01 01 02 8A 00 00 30 39 → property=[3], deviceType=[4]=1
        val adv = BroadcastParser.parse(mac, bytes(0xCA, 0x11, 0x09, 0x01, 0x01, 0x02, 0x8A, 0x00, 0x00, 0x30, 0x39))!!
        assertEquals(0x11, adv.version)
        assertEquals(1, adv.deviceType)
        assertEquals(0x01, adv.scaleProperty)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
        assertEquals(12345, adv.productId)
    }

    @Test
    fun `0xCA v10 plain weigher reads weight from bytes 8-9`() {
        // deviceType=0 → weight = BE([8..9]) / 10 kg, no productId
        val adv = BroadcastParser.parse(mac, bytes(0xCA, 0x10, 0x09, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x8A, 0x00))!!
        assertEquals(0, adv.deviceType)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
        assertEquals(0, adv.productId)
    }

    @Test
    fun `0xCA v11 plain weigher still parses weight from bytes 5-6`() {
        val adv = BroadcastParser.parse(mac, bytes(0xCA, 0x11, 0x09, 0x01, 0x00, 0x02, 0x8A, 0x00, 0x00, 0x30, 0x39))!!
        assertEquals(0, adv.deviceType)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
        assertEquals(12345, adv.productId)
    }

    @Test
    fun `0xCA v20 locked frame carries sequence weight and impedance`() {
        // CA 20 0C [00 01 30 39]=productId 77881 [7]=1→type2 [8]=0x01 property
        // [9]=07 seq [10..11]=02 8A → 65.0 kg [12..13]=13 EC → 510.0 Ω
        val adv = BroadcastParser.parse(
            mac,
            bytes(0xCA, 0x20, 0x0C, 0x00, 0x01, 0x30, 0x39, 0x01, 0x01, 0x07, 0x02, 0x8A, 0x13, 0xEC),
        )!!
        assertEquals(ScaleProtocolType.OKOK_CLASSIC, adv.protocolType)
        assertEquals(0x20, adv.version)
        assertEquals(2, adv.deviceType)
        assertEquals(77881, adv.productId)
        assertEquals(7, adv.measureSeqNo)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
        assertEquals(510.0, adv.impedanceOhm!!, 1e-9)
    }

    @Test
    fun `0xCA v20 unlocked frame carries no measurement`() {
        val adv = BroadcastParser.parse(
            mac,
            bytes(0xCA, 0x20, 0x09, 0x00, 0x01, 0x30, 0x39, 0x00, 0x00),
        )!!
        assertEquals(0, adv.deviceType) // byte[7] == 0 → type 0
        assertNull(adv.weightKg)
        assertNull(adv.impedanceOhm)
        assertNull(adv.measureSeqNo)
    }

    @Test
    fun `0xC0 locked frame`() {
        // C0 09 02 8A 13 EC 30 39 21
        // seq=9, weight BE 650 → 65.0 kg, impedance BE 5100 → 510.0 Ω,
        // productId BE16 0x3039 = 12345, property 0x21 (bit5 → deviceType 2, locked)
        val adv = BroadcastParser.parse(mac, bytes(0xC0, 0x09, 0x02, 0x8A, 0x13, 0xEC, 0x30, 0x39, 0x21))!!
        assertEquals(ScaleProtocolType.OKOK_CLASSIC, adv.protocolType)
        assertEquals(1, adv.version)
        assertEquals(2, adv.deviceType)
        assertEquals(0x21, adv.scaleProperty)
        assertEquals(9, adv.measureSeqNo)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
        assertEquals(510.0, adv.impedanceOhm!!, 1e-9)
        assertEquals(12345, adv.productId)
    }

    @Test
    fun `0xC0 unlocked frame and device type 0`() {
        // property 0x00: lockFlag 0, bit5 0 → deviceType 0, no measurement
        val adv = BroadcastParser.parse(mac, bytes(0xC0, 0x09, 0x02, 0x8A, 0x13, 0xEC, 0x30, 0x39, 0x00))!!
        assertEquals(0, adv.deviceType)
        assertNull(adv.weightKg)
        assertNull(adv.measureSeqNo)
    }

    @Test
    fun `0xFF cloud frame with little-endian weight`() {
        // FF F0 09 04 8A 02 00 BC 61 4E
        // version 0x09 → OKOK_CLOUD, property 0x04 (kg, cloud mapping → 1 decimal),
        // weight LE 650 → 65.0 kg, productId 0x00BC614E = 12345678
        val adv = BroadcastParser.parse(mac, bytes(0xFF, 0xF0, 0x09, 0x04, 0x8A, 0x02, 0x00, 0xBC, 0x61, 0x4E))!!
        assertEquals(ScaleProtocolType.OKOK_CLOUD, adv.protocolType)
        assertEquals(0x09, adv.version)
        assertEquals(1, adv.deviceType)
        assertEquals(0x04, adv.scaleProperty)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
        assertEquals(12345678, adv.productId)
    }

    @Test
    fun `0xFF v4 frame uses cloudV4 decimal mapping`() {
        // FF F0 10 40 8A 02 ... version 0x10 → OKOK_CLOUD_V4,
        // property 0x40: cloudV4 bits 6:5 = 10 → 1 decimal → 65.0 kg
        val adv = BroadcastParser.parse(mac, bytes(0xFF, 0xF0, 0x10, 0x40, 0x8A, 0x02, 0x00, 0xBC, 0x61, 0x4E))!!
        assertEquals(ScaleProtocolType.OKOK_CLOUD_V4, adv.protocolType)
        assertEquals(0x10, adv.version)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
    }

    @Test
    fun `0xFF v4 version 0x11 with zero decimals`() {
        // property 0x20: cloudV4 bits 6:5 = 01 → 0 decimals; weight LE 65 → 65 kg
        val adv = BroadcastParser.parse(mac, bytes(0xFF, 0xF0, 0x11, 0x20, 0x41, 0x00, 0x00, 0xBC, 0x61, 0x4E))!!
        assertEquals(ScaleProtocolType.OKOK_CLOUD_V4, adv.protocolType)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
    }

    @Test
    fun `0xFF productId FA FB FC FD forces id 997264800 and property 4`() {
        // property byte 0x00 would decode with 2 decimals; forced 4 → kg, 1 decimal
        val adv = BroadcastParser.parse(mac, bytes(0xFF, 0xF0, 0x09, 0x00, 0x8A, 0x02, 0xFA, 0xFB, 0xFC, 0xFD))!!
        assertEquals(997264800, adv.productId)
        assertEquals(4, adv.scaleProperty)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
    }

    @Test
    fun `0xFF productId 1104000001 forces property 4`() {
        // 1104000001 = 0x41CDB401
        val adv = BroadcastParser.parse(mac, bytes(0xFF, 0xF0, 0x09, 0x00, 0x8A, 0x02, 0x41, 0xCD, 0xB4, 0x01))!!
        assertEquals(1104000001, adv.productId)
        assertEquals(4, adv.scaleProperty)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
    }

    @Test
    fun `unknown or truncated frames return null`() {
        assertNull(BroadcastParser.parse(mac, bytes()))
        assertNull(BroadcastParser.parse(mac, bytes(0xCA)))
        assertNull(BroadcastParser.parse(mac, bytes(0x58, 0x01, 0x00))) // ALIBABA, out of scope
        assertNull(BroadcastParser.parse(mac, bytes(0xFF, 0xF1, 0x09))) // FF but not F0
        assertNull(BroadcastParser.parse(mac, bytes(0xCA, 0x30, 0x00))) // unknown CA version
        assertNull(BroadcastParser.parse(mac, bytes(0xCA, 0x10, 0x09, 0x01))) // truncated
        assertNull(BroadcastParser.parse(mac, bytes(0xC0, 0x09, 0x02))) // truncated
    }
}
