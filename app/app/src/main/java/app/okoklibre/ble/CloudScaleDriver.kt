package app.okoklibre.ble

import app.okoklibre.ble.protocol.GattUuids
import app.okoklibre.ble.protocol.OkokCloudCodec
import app.okoklibre.ble.protocol.ParseResult
import app.okoklibre.ble.protocol.ScaleProtocolType
import app.okoklibre.diagnostics.DiagnosticLog
import java.time.LocalDateTime
import kotlinx.coroutines.delay

/**
 * OKOKCloud connect sequence:
 * enable fff1 notify + 2a9c indicate → 7-byte LE-year time write to 2a08 →
 * `{0x01, unit}` written to fff2 twice → 500 ms pause → enable fa9c indicate.
 *
 * [productId] comes from the advertisement; special ids force the property
 * byte inside the codec.
 */
class CloudScaleDriver(private val productId: Int = 0) : ScaleProtocolDriver {

    override val protocolType = ScaleProtocolType.OKOK_CLOUD

    private val codec = OkokCloudCodec()

    override suspend fun runConnectSequence(gatt: ScaleGattClient, config: ConnectConfig): Boolean {
        DiagnosticLog.log(TAG, "setup start (productId=${config.productId})")
        val notifyOk = gatt.enableNotifications(GattUuids.CHAR_LIVE_FFF1)
        gatt.enableIndications(GattUuids.CHAR_LOCKED_2A9C)
        gatt.writeCharacteristic(
            GattUuids.CHAR_CURRENT_TIME_2A08,
            OkokCloudCodec.buildTimeWrite(LocalDateTime.now()),
        )
        val unitFrame = OkokCloudCodec.buildUnitWrite(config.unit)
        gatt.writeCharacteristic(GattUuids.CHAR_WRITE_FFF2, unitFrame)
        gatt.writeCharacteristic(GattUuids.CHAR_WRITE_FFF2, unitFrame)
        delay(PRE_HISTORY_DELAY_MS)
        gatt.enableIndications(GattUuids.CHAR_HISTORY_FA9C)
        DiagnosticLog.log(TAG, "setup done (time+unit written, fff1/2a9c/fa9c armed)")
        return notifyOk
    }

    override fun parse(characteristicUuid: String, data: ByteArray): ParseResult =
        codec.parse(characteristicUuid, data, productId)

    companion object {
        private const val TAG = "CloudDriver"
        const val PRE_HISTORY_DELAY_MS = 500L
    }
}
