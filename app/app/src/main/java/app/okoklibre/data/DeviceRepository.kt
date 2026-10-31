package app.okoklibre.data

import app.okoklibre.ble.protocol.ScaleProtocolType
import kotlinx.coroutines.flow.Flow

/** Paired scale devices ("My Device" screen). */
class DeviceRepository(
    private val deviceDao: ScaleDeviceDao,
) {

    val devices: Flow<List<ScaleDeviceEntity>> get() = deviceDao.observeAll()

    suspend fun allDevices(): List<ScaleDeviceEntity> = deviceDao.all()

    suspend fun device(mac: String): ScaleDeviceEntity? = deviceDao.byMac(mac)

    /** Registers a newly bound scale, or updates the stored metadata of a known one. */
    suspend fun register(
        mac: String,
        name: String,
        typeId: Int,
        productId: Long? = null,
        protocolVersion: Int,
        protocolType: ScaleProtocolType,
        seenEpochSec: Long = System.currentTimeMillis() / 1000,
    ) = deviceDao.upsert(
        ScaleDeviceEntity(
            mac = mac,
            name = name,
            typeId = typeId,
            productId = productId,
            protocolVersion = protocolVersion,
            protocolType = protocolType,
            lastSeenEpochSec = seenEpochSec,
        ),
    )

    /** Bumps lastSeen without touching the rest of the row. */
    suspend fun seen(mac: String, epochSec: Long = System.currentTimeMillis() / 1000) =
        deviceDao.touch(mac, epochSec)

    /** Persists a detected device type without touching the rest of the row. */
    suspend fun updateTypeId(mac: String, typeId: Int) = deviceDao.updateTypeId(mac, typeId)

    /** Unbinds a scale. */
    suspend fun remove(mac: String) = deviceDao.delete(mac)

    companion object {
        /** 0 = weigher (weight only), 1 = fat scale, 2 = impedance-only. */
        const val TYPE_WEIGHER = 0
        const val TYPE_FAT = 1
        const val TYPE_IMPEDANCE_ONLY = 2

        fun create(db: AppDatabase): DeviceRepository = DeviceRepository(db.scaleDeviceDao())
    }
}
