package app.okoklibre.data

import app.okoklibre.ble.protocol.Measurement
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * Offline (history) records pulled from a scale. They sit in the tmp table
 * until the user claims them for a role (WeightMatching flow) or deletes them.
 */
class TmpWeightRepository(
    private val tmpDao: TmpWeightDao,
    private val roleRepository: RoleRepository,
    private val weightRepository: WeightRepository,
) {

    val pending: Flow<List<TmpWeightEntity>> get() = tmpDao.observeAll()

    suspend fun allPending(): List<TmpWeightEntity> = tmpDao.all()

    suspend fun add(
        mac: String,
        weightKg: Double,
        timestampEpochSec: Long,
        impedanceOhm: Double = 0.0,
        scaleProperty: Int = 0,
        scaleWeightString: String? = null,
        productId: Long? = null,
        fatPct: Double = 0.0,
        waterPct: Double = 0.0,
        musclePct: Double = 0.0,
        bmrKcal: Double = 0.0,
        visceralFat: Double = 0.0,
        boneKg: Double = 0.0,
        heartRateBpm: Int? = null,
        scaleRoleId: Int? = null,
        rawFrameHex: String? = null,
    ): Long = tmpDao.find(mac, timestampEpochSec, weightKg)?.id ?: tmpDao.insert(
        TmpWeightEntity(
            mac = mac,
            weightKg = weightKg,
            timestampEpochSec = timestampEpochSec,
            impedanceOhm = impedanceOhm,
            scaleProperty = scaleProperty,
            scaleWeightString = scaleWeightString,
            productId = productId,
            fatPct = fatPct,
            waterPct = waterPct,
            musclePct = musclePct,
            bmrKcal = bmrKcal,
            visceralFat = visceralFat,
            boneKg = boneKg,
            heartRateBpm = heartRateBpm,
            scaleRoleId = scaleRoleId,
            rawFrameHex = rawFrameHex,
        ),
    )

    /** Convenience overload capturing everything a history [Measurement] carries. */
    suspend fun add(mac: String, measurement: Measurement, productId: Long? = null): Long = add(
        mac = mac,
        weightKg = measurement.weightKg,
        timestampEpochSec = measurement.timestampEpochSec ?: System.currentTimeMillis() / 1000,
        impedanceOhm = measurement.impedanceOhm,
        scaleProperty = measurement.scaleProperty,
        scaleWeightString = measurement.scaleWeightString,
        productId = productId,
        fatPct = measurement.fatPct,
        waterPct = measurement.waterPct,
        musclePct = measurement.musclePct,
        bmrKcal = measurement.bmrKcal.toDouble(),
        visceralFat = measurement.visceralFat,
        boneKg = measurement.boneKg,
        heartRateBpm = measurement.heartRateBpm,
        scaleRoleId = measurement.roleId,
        rawFrameHex = measurement.rawFrameHex,
    )

    /**
     * Stores a pulled history record; when its scale-side role id maps to a
     * known role (auto-attribution: the id matches the app
     * role row id sent in the all-users sync), claims it immediately and
     * returns the new record id. Unknown/absent role id → left pending in the
     * tmp table for manual claiming (returns null).
     */
    suspend fun addOrAutoClaim(
        mac: String,
        measurement: Measurement,
        productId: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        // Scales can resend history, and a claimed reading no longer sits in the tmp table.
        val at = measurement.timestampEpochSec
        if (at != null && weightRepository.hasReading(at, measurement.weightKg)) return null
        val tmpId = add(mac, measurement, productId)
        val role = measurement.roleId?.let { roleRepository.role(it.toLong()) } ?: return null
        return claim(tmpId, role.id, zone)
    }

    /**
     * Claims a tmp record for [roleId]: computes the body composition for that
     * role (impedance path, or the scale-computed path when the history record
     * carried a composition block), inserts a real weight record and removes
     * the tmp row. Returns the new record id, or null when the tmp row or role
     * no longer exists.
     */
    suspend fun claim(tmpId: Long, roleId: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val tmp = tmpDao.byId(tmpId) ?: return null
        val role = roleRepository.role(roleId) ?: return null
        val recordId = weightRepository.insertScaleComputed(
            role = role,
            measurement = Measurement(
                weightKg = tmp.weightKg,
                lockFlag = true,
                fatPct = tmp.fatPct,
                waterPct = tmp.waterPct,
                musclePct = tmp.musclePct,
                bmrKcal = tmp.bmrKcal.toInt(),
                visceralFat = tmp.visceralFat,
                boneKg = tmp.boneKg,
                impedanceOhm = tmp.impedanceOhm,
                heartRateBpm = tmp.heartRateBpm,
                timestampEpochSec = tmp.timestampEpochSec,
                scaleProperty = tmp.scaleProperty,
                isHistory = true,
                scaleWeightString = tmp.scaleWeightString,
                rawFrame = tmp.rawFrameHex?.hexToBytes(),
            ),
            productId = tmp.productId,
            source = RecordSource.OFFLINE_HISTORY,
            zone = zone,
        )
        tmpDao.delete(tmpId)
        return recordId
    }

    suspend fun delete(tmpId: Long) = tmpDao.delete(tmpId)

    suspend fun clear() = tmpDao.clear()

    companion object {
        fun create(db: AppDatabase): TmpWeightRepository = TmpWeightRepository(
            db.tmpWeightDao(),
            RoleRepository.create(db),
            WeightRepository.create(db),
        )
    }
}

private fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
