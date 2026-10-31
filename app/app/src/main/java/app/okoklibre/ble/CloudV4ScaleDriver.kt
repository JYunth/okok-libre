package app.okoklibre.ble

import app.okoklibre.ble.protocol.GattUuids
import app.okoklibre.ble.protocol.OkokCloudV4Codec
import app.okoklibre.ble.protocol.ParseResult
import app.okoklibre.ble.protocol.ScaleProtocolType
import app.okoklibre.diagnostics.DiagnosticLog
import kotlinx.coroutines.delay

/**
 * OKOKCloudV4 (heart-rate capable) connect sequence:
 * 4-byte BE unix-time write to 2a08 → `{0x01, unit}` to fff2 → 0xA0 9-byte
 * user profile to fff2 → enable fa9c indicate, fff1 notify, 2a9c indicate,
 * sequentially with ~1 s spacing.
 */
class CloudV4ScaleDriver : ScaleProtocolDriver {

    override val protocolType = ScaleProtocolType.OKOK_CLOUD_V4

    private val codec = OkokCloudV4Codec()

    override suspend fun runConnectSequence(gatt: ScaleGattClient, config: ConnectConfig): Boolean {
        DiagnosticLog.log(TAG, "setup start (roles=${config.roles.size})")
        gatt.writeCharacteristic(
            GattUuids.CHAR_CURRENT_TIME_2A08,
            OkokCloudV4Codec.buildTimeWrite(System.currentTimeMillis() / 1000),
        )
        gatt.writeCharacteristic(
            GattUuids.CHAR_WRITE_FFF2,
            OkokCloudV4Codec.buildUnitWrite(config.unit),
        )
        config.roles.firstOrNull()?.let { role ->
            gatt.writeCharacteristic(
                GattUuids.CHAR_WRITE_FFF2,
                OkokCloudV4Codec.buildUserProfile(role.roleId, role.male, role.age, role.heightCm),
            )
        }
        gatt.enableIndications(GattUuids.CHAR_HISTORY_FA9C)
        delay(ENABLE_SPACING_MS)
        val liveOk = gatt.enableNotifications(GattUuids.CHAR_LIVE_FFF1)
        delay(ENABLE_SPACING_MS)
        gatt.enableIndications(GattUuids.CHAR_LOCKED_2A9C)
        DiagnosticLog.log(TAG, "setup done (time+unit+profile written, fa9c/fff1/2a9c armed)")
        return liveOk
    }

    override fun parse(characteristicUuid: String, data: ByteArray): ParseResult =
        codec.parse(characteristicUuid, data)

    companion object {
        private const val TAG = "CloudV4Driver"
        const val ENABLE_SPACING_MS = 1_000L
    }
}
