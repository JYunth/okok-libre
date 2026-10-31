package app.okoklibre.ble

import app.okoklibre.ble.protocol.GattUuids
import app.okoklibre.ble.protocol.OkokClassicCodec
import app.okoklibre.ble.protocol.ParseResult
import app.okoklibre.ble.protocol.ScaleProtocolType
import app.okoklibre.diagnostics.DiagnosticLog
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * OKOK classic (0xCA) connect sequence.
 *
 * Enable fff1 notify, then role-info:
 *  - protocol version 0x10: single role-info frame written 4× at 150 ms;
 *  - otherwise (0x11 / 0x20 / 0xC0-derived): v11 all-users sync, 2 full rounds
 *    with 200 ms spacing.
 *
 * After the first locked measurement, v11 devices get the history-retrieve
 * command. 0x14 user-match responses are answered with [selectUser].
 */
class ClassicScaleDriver(private val version: Int) : ScaleProtocolDriver {

    override val protocolType = ScaleProtocolType.OKOK_CLASSIC

    private val codec = OkokClassicCodec()

    private val isV10 = version == 0x10

    override suspend fun runConnectSequence(gatt: ScaleGattClient, config: ConnectConfig): Boolean {
        DiagnosticLog.log(TAG, "setup start (version=$version, roles=${config.roles.size})")
        if (!gatt.enableNotifications(GattUuids.CHAR_LIVE_FFF1)) {
            DiagnosticLog.log(TAG, "setup aborted: fff1 notify enable failed")
            return false
        }
        if (isV10) {
            val role = config.roles.firstOrNull() ?: return true
            val frame = OkokClassicCodec.buildV10RoleInfo(
                roleId = role.roleId,
                male = role.male,
                age = role.age,
                heightCm = role.heightCm,
                time = LocalDateTime.now(),
            )
            repeat(V10_ROLE_INFO_REPEATS) {
                gatt.writeCharacteristic(GattUuids.CHAR_WRITE_FFF2, frame)
                delay(V10_ROLE_INFO_INTERVAL_MS)
            }
            DiagnosticLog.log(TAG, "v10 role-info written ${V10_ROLE_INFO_REPEATS}x")
        } else {
            val users = config.roles.map {
                OkokClassicCodec.RoleProfile(
                    roleId = it.roleId,
                    male = it.male,
                    age = it.age,
                    heightCm = it.heightCm,
                    weightKgTimes10 = (it.weightKg * 10).roundToInt(),
                )
            }
            if (users.isEmpty()) return true
            repeat(V11_SYNC_ROUNDS) {
                OkokClassicCodec.buildV11AllUsersSync(users, System.currentTimeMillis() / 1000)
                    .forEach { frame ->
                        gatt.writeCharacteristic(GattUuids.CHAR_WRITE_FFF2, frame)
                        delay(V11_SYNC_INTERVAL_MS)
                    }
            }
            DiagnosticLog.log(TAG, "v11 all-users sync written (${users.size} users)")
        }
        return true
    }

    override fun parse(characteristicUuid: String, data: ByteArray): ParseResult =
        codec.parse(characteristicUuid, data)

    override suspend fun onLockedMeasurement(gatt: ScaleGattClient) {
        if (isV10) return
        DiagnosticLog.log(TAG, "requesting v11 history")
        gatt.writeCharacteristic(GattUuids.CHAR_WRITE_FFF2, OkokClassicCodec.buildV11HistoryRetrieve())
    }

    override suspend fun selectUser(gatt: ScaleGattClient, roleId: Int) {
        if (isV10) return
        gatt.writeCharacteristic(GattUuids.CHAR_WRITE_FFF2, OkokClassicCodec.buildSelectUser(roleId))
    }

    companion object {
        private const val TAG = "ClassicDriver"
        const val V10_ROLE_INFO_REPEATS = 4
        const val V10_ROLE_INFO_INTERVAL_MS = 150L
        const val V11_SYNC_ROUNDS = 2
        const val V11_SYNC_INTERVAL_MS = 200L
    }
}
