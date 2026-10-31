package app.okoklibre.ble.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvertisementAssemblerTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `company id is prepended little-endian`() {
        val frame = AdvertisementAssembler.fromAndroidManufacturerData(0x10CA, bytes(0x09, 0x01))
        assertArrayEquals(bytes(0xCA, 0x10, 0x09, 0x01), frame)
    }

    @Test
    fun `0x10CA classic v10 frame parses after reconstruction`() {
        // Android reports key=0x10CA and strips CA 10 from the value; the
        // reconstructed frame must be the full BroadcastParser input.
        val payload = bytes(0x09, 0x01, 0x01, 0x02, 0x8A, 0x00, 0x00, 0x30, 0x39)
        val adv = BroadcastParser.parse(
            mac,
            AdvertisementAssembler.fromAndroidManufacturerData(0x10CA, payload),
        )!!
        assertEquals(ScaleProtocolType.OKOK_CLASSIC, adv.protocolType)
        assertEquals(0x10, adv.version)
        assertEquals(1, adv.deviceType)
        assertEquals(0x01, adv.scaleProperty)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
        assertEquals(12345, adv.productId)
    }

    @Test
    fun `0x20CA classic v20 wake frame without measurement still parses`() {
        val payload = bytes(0x09, 0x00, 0x01, 0x30, 0x39, 0x00, 0x00)
        val adv = BroadcastParser.parse(
            mac,
            AdvertisementAssembler.fromAndroidManufacturerData(0x20CA, payload),
        )!!
        assertEquals(0x20, adv.version)
        assertNull(adv.weightKg)
    }

    @Test
    fun `0xF0FF cloud frame parses after reconstruction`() {
        // FF F0 09 04 8A 02 00 BC 61 4E — version 0x09, LE weight 65.0 kg
        val payload = bytes(0x09, 0x04, 0x8A, 0x02, 0x00, 0xBC, 0x61, 0x4E)
        val adv = BroadcastParser.parse(
            mac,
            AdvertisementAssembler.fromAndroidManufacturerData(0xF0FF, payload),
        )!!
        assertEquals(ScaleProtocolType.OKOK_CLOUD, adv.protocolType)
        assertEquals(65.0, adv.weightKg!!, 1e-9)
        assertEquals(12345678, adv.productId)
    }

    @Test
    fun `non-scale company id still returns null`() {
        val payload = bytes(0x09, 0x01, 0x01, 0x02, 0x8A, 0x00, 0x00, 0x30, 0x39)
        // 0x004C = Apple — reconstructed header 4C 00 is not an OKOK frame
        assertNull(
            BroadcastParser.parse(
                mac,
                AdvertisementAssembler.fromAndroidManufacturerData(0x004C, payload),
            ),
        )
    }
}
