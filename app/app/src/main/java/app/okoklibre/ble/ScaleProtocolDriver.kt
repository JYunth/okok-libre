package app.okoklibre.ble

import app.okoklibre.ble.protocol.ParseResult
import app.okoklibre.ble.protocol.ScaleAdvertisement
import app.okoklibre.ble.protocol.ScaleProtocolType
import app.okoklibre.ble.protocol.WeightUnit

/** A family-member profile synced to the scale (role-info / all-users / 0xA0 profile writes). */
data class ScaleUserProfile(
    val roleId: Int,
    val male: Boolean,
    val age: Int,
    val heightCm: Int,
    /** Current weight in kg; sent to the scale as kg×10 where the protocol needs it. */
    val weightKg: Double,
)

/** Per-connection parameters for [ScaleProtocolDriver.runConnectSequence]. */
data class ConnectConfig(
    val roles: List<ScaleUserProfile>,
    val unit: WeightUnit,
    /** Advertised product id (Cloud special ids remap the property byte in the codec). */
    val productId: Int = 0,
)

/**
 * Per-protocol connect sequence and frame parsing.
 * One instance per GATT connection (the classic codec holds multi-packet
 * reassembly state).
 */
interface ScaleProtocolDriver {

    val protocolType: ScaleProtocolType

    /**
     * Runs the protocol's serialized GATT setup (notify/indicate enables, time
     * sync, unit sync, role/profile writes) on an already-connected client.
     * Returns false when the essential notification channel could not be armed.
     */
    suspend fun runConnectSequence(gatt: ScaleGattClient, config: ConnectConfig): Boolean

    /** Route a characteristic value through the protocol codec. */
    fun parse(characteristicUuid: String, data: ByteArray): ParseResult

    /** Called after the first locked measurement (classic v11: history retrieve). */
    suspend fun onLockedMeasurement(gatt: ScaleGattClient) {}

    /** Answer a 0x14 user-match response (classic v11 Select-User command). */
    suspend fun selectUser(gatt: ScaleGattClient, roleId: Int) {}

    companion object {
        fun create(adv: ScaleAdvertisement): ScaleProtocolDriver = when (adv.protocolType) {
            ScaleProtocolType.OKOK_CLASSIC -> ClassicScaleDriver(adv.version)
            ScaleProtocolType.OKOK_CLOUD -> CloudScaleDriver(adv.productId)
            ScaleProtocolType.OKOK_CLOUD_V4 -> CloudV4ScaleDriver()
        }
    }
}
