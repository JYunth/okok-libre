package app.okoklibre.data

import app.okoklibre.algo.BodyCompositionEngine
import app.okoklibre.algo.CompositionInput
import app.okoklibre.algo.ScaleComputedInput
import app.okoklibre.ble.protocol.Measurement
import androidx.room.withTransaction
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/** Calendar-age computation, kept here so both insert paths share it. */
fun ageYearsAt(birthdayEpochDay: Long, timestampEpochSec: Long, zone: ZoneId): Int =
    Period.between(
        LocalDate.ofEpochDay(birthdayEpochDay),
        Instant.ofEpochSecond(timestampEpochSec).atZone(zone).toLocalDate(),
    ).years

class WeightRepository(
    private val recordDao: WeightRecordDao,
    private val aggregateDao: DayAggregateDao,
    private val statusDao: WeightStatusDao,
    private val transaction: suspend (suspend () -> Long?) -> Long? = { it() },
) {

    // --- reads ---

    suspend fun record(id: Long): WeightRecordEntity? = recordDao.byId(id)

    suspend fun latest(roleId: Long): WeightRecordEntity? = recordDao.latest(roleId)

    fun latestFlow(roleId: Long): Flow<WeightRecordEntity?> = recordDao.observeLatest(roleId)

    fun recordsFlow(roleId: Long): Flow<List<WeightRecordEntity>> = recordDao.observeAll(roleId)

    suspend fun records(roleId: Long): List<WeightRecordEntity> = recordDao.all(roleId)

    suspend fun recordsPage(roleId: Long, limit: Int, offset: Int): List<WeightRecordEntity> =
        recordDao.page(roleId, limit, offset)

    suspend fun recordCount(roleId: Long): Int = recordDao.count(roleId)

    /** All records of one calendar day (ascending). */
    suspend fun recordsForDay(
        roleId: Long,
        epochDay: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<WeightRecordEntity> = recordDao.between(
        roleId,
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toEpochSecond(),
        LocalDate.ofEpochDay(epochDay).plusDays(1).atStartOfDay(zone).toEpochSecond(),
    )

    /** Records between two calendar days, inclusive (ascending). */
    suspend fun recordsBetween(
        roleId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<WeightRecordEntity> = recordDao.between(
        roleId,
        LocalDate.ofEpochDay(startEpochDay).atStartOfDay(zone).toEpochSecond(),
        LocalDate.ofEpochDay(endEpochDay).plusDays(1).atStartOfDay(zone).toEpochSecond(),
    )

    fun dayAggregatesFlow(
        roleId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<List<DayAggregateEntity>> = aggregateDao.observeBetween(roleId, startEpochDay, endEpochDay)

    suspend fun dayAggregates(
        roleId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
    ): List<DayAggregateEntity> = aggregateDao.between(roleId, startEpochDay, endEpochDay)

    // --- writes ---

    /**
     * Computes the full body-composition snapshot with [BodyCompositionEngine]
     * (with impedance smoothing against the role's previous record), stores the
     * record and refreshes its day aggregate. Returns the new row id.
     */
    suspend fun insertComputed(
        role: RoleEntity,
        weightKg: Double,
        timestampEpochSec: Long,
        impedanceOhm: Double = 0.0,
        scaleProperty: Int = 0,
        scaleWeightString: String? = null,
        productId: Long? = null,
        remark: String? = null,
        heartRateBpm: Int? = null,
        rawFrameHex: String? = null,
        source: RecordSource = RecordSource.LIVE,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val previous = recordDao.latest(role.id)
        // An offline reading can be older than the latest record; smoothing only looks backwards.
        val minutesSincePrevious = previous?.let { (timestampEpochSec - it.timestampEpochSec) / 60 }
            ?.takeIf { it >= 0 }

        val result = BodyCompositionEngine.compute(
            CompositionInput(
                heightCm = role.heightCm.toDouble(),
                weightKg = weightKg,
                sex = role.sex,
                ageYears = ageYearsAt(role.birthdayEpochDay, timestampEpochSec, zone),
                impedanceOhm = impedanceOhm,
                previousImpedanceOhm = previous?.impedanceOhm?.takeIf { it > 0.0 },
                minutesSincePrevious = minutesSincePrevious,
            ),
        )

        // Heart rate can arrive inside the remark as "2:<bpm>".
        val bpm = heartRateBpm ?: remark?.let { Regex("^2:(\\d+)$").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val record = WeightRecordEntity(
            roleId = role.id,
            weightKg = weightKg,
            timestampEpochSec = timestampEpochSec,
            bmi = result.bmi,
            bodyFatPct = result.bodyFatPct,
            boneKg = result.boneMassKg,
            musclePct = result.muscleRatePct,
            waterPct = result.waterPct,
            bmrKcal = result.bmrKcal,
            bodyAge = result.bodyAge,
            visceralFat = result.visceralFat,
            score = result.bodyScore,
            idealWeightKg = result.idealWeightKg,
            heightCm = role.heightCm.toDouble(),
            sex = role.sex,
            ageYears = ageYearsAt(role.birthdayEpochDay, timestampEpochSec, zone),
            impedanceOhm = result.effectiveImpedanceOhm,
            scaleProperty = scaleProperty,
            scaleWeightString = scaleWeightString,
            productId = productId,
            remark = remark,
            heartRateBpm = bpm,
            rawFrameHex = rawFrameHex,
            source = source,
        )
        return insert(record, zone)
    }

    /**
     * Persists a locked scale [measurement], picking the composition path per
     * device class:
     *
     * - **impedance path** (`impedanceOhm > 0`): everything is computed
     *   app-side via [insertComputed] — unchanged behavior;
     * - **scale-computed path** (no impedance but the frame carries
     *   scale-computed composition — classic type-1 fat scales): fat/water/
     *   muscle/BMR/visceral/bone are stored directly from the measurement and
     *   the remaining derived metrics (BMI, fat mass, ideal weight, obesity
     *   degree, lean body mass, body age, protein, score, shape) come from
     *   [BodyCompositionEngine.fromScaleComputed]. As in the original, invalid fields carry the 0.0 "no data" sentinel —
     *   including fat clamped out of [5, 45] — so the discriminator is the
     *   presence of ANY composition field, not `fatPct > 0` (a composition
     *   frame with clamped-out fat must still persist its water/muscle/BMR/
     *   visceral/bone);
     * - otherwise a weight-only record via [insertComputed].
     */
    suspend fun insertScaleComputed(
        role: RoleEntity,
        measurement: Measurement,
        productId: Long? = null,
        source: RecordSource = RecordSource.LIVE,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val ts = measurement.timestampEpochSec ?: System.currentTimeMillis() / 1000
        val hasScaleComposition = measurement.fatPct > 0.0 || measurement.waterPct > 0.0 ||
            measurement.musclePct > 0.0 || measurement.bmrKcal > 0 ||
            measurement.visceralFat > 0.0 || measurement.boneKg > 0.0
        if (measurement.impedanceOhm > 0.0 || !hasScaleComposition) {
            return insertComputed(
                role = role,
                weightKg = measurement.weightKg,
                timestampEpochSec = ts,
                impedanceOhm = measurement.impedanceOhm,
                scaleProperty = measurement.scaleProperty,
                scaleWeightString = measurement.scaleWeightString,
                productId = productId,
                heartRateBpm = measurement.heartRateBpm,
                rawFrameHex = measurement.rawFrameHex,
                source = source,
                zone = zone,
            )
        }
        val ageYears = ageYearsAt(role.birthdayEpochDay, ts, zone)
        val result = BodyCompositionEngine.fromScaleComputed(
            ScaleComputedInput(
                heightCm = role.heightCm.toDouble(),
                weightKg = measurement.weightKg,
                sex = role.sex,
                ageYears = ageYears,
                fatPct = measurement.fatPct,
                waterPct = measurement.waterPct,
                musclePct = measurement.musclePct,
                bmrKcal = measurement.bmrKcal.toDouble(),
                visceralFat = measurement.visceralFat,
                boneKg = measurement.boneKg,
            ),
        )
        val record = WeightRecordEntity(
            roleId = role.id,
            weightKg = measurement.weightKg,
            timestampEpochSec = ts,
            bmi = result.bmi,
            bodyFatPct = result.bodyFatPct,
            boneKg = result.boneMassKg,
            musclePct = result.muscleRatePct,
            waterPct = result.waterPct,
            bmrKcal = result.bmrKcal,
            bodyAge = result.bodyAge,
            visceralFat = result.visceralFat,
            score = result.bodyScore,
            idealWeightKg = result.idealWeightKg,
            heightCm = role.heightCm.toDouble(),
            sex = role.sex,
            ageYears = ageYears,
            impedanceOhm = 0.0,
            scaleProperty = measurement.scaleProperty,
            scaleWeightString = measurement.scaleWeightString,
            productId = productId,
            heartRateBpm = measurement.heartRateBpm,
            rawFrameHex = measurement.rawFrameHex,
            source = source,
        )
        return insert(record, zone)
    }

    /**
     * Stores a fully-populated record and refreshes its day aggregate. A record at the
     * same instant updates in place, so its status tags survive a re-import or re-claim.
     */
    suspend fun insert(record: WeightRecordEntity, zone: ZoneId = ZoneId.systemDefault()): Long {
        val existing = recordDao.atInstant(record.roleId, record.timestampEpochSec)
        val id = if (existing != null) {
            recordDao.update(record.copy(id = existing.id))
            existing.id
        } else {
            recordDao.insert(record.copy(id = 0))
        }
        refreshDayAggregate(record.roleId, epochDayOf(record.timestampEpochSec, zone), zone)
        return id
    }

    /**
     * Moves a reading to the person it really belongs to. Composition depends on height,
     * sex and age, so it is recomputed for [to] instead of being carried across.
     * Returns the record's new id, or null if it no longer exists.
     */
    suspend fun moveRecord(recordId: Long, to: RoleEntity, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val source = recordDao.byId(recordId)?.takeIf { !it.deleted } ?: return null
        if (source.roleId == to.id) return source.id
        return transaction { moveAcross(source, to, zone) }
    }

    private suspend fun moveAcross(source: WeightRecordEntity, to: RoleEntity, zone: ZoneId): Long {
        val hadScaleComposition = source.impedanceOhm <= 0.0 && source.bodyFatPct > 0.0
        val measurement = Measurement(
            weightKg = source.weightKg,
            lockFlag = true,
            fatPct = if (hadScaleComposition) source.bodyFatPct else 0.0,
            waterPct = if (hadScaleComposition) source.waterPct else 0.0,
            musclePct = if (hadScaleComposition) source.musclePct else 0.0,
            bmrKcal = if (hadScaleComposition) source.bmrKcal.toInt() else 0,
            visceralFat = if (hadScaleComposition) source.visceralFat else 0.0,
            boneKg = if (hadScaleComposition) source.boneKg else 0.0,
            impedanceOhm = source.impedanceOhm,
            heartRateBpm = source.heartRateBpm,
            // Never lands on a reading the new owner already has.
            timestampEpochSec = freeInstant(to.id, source.timestampEpochSec),
            scaleProperty = source.scaleProperty,
            isHistory = source.source == RecordSource.OFFLINE_HISTORY,
            scaleWeightString = source.scaleWeightString,
        )
        val movedId = insertScaleComputed(to, measurement, source.productId, source.source, zone)
        recordDao.byId(movedId)?.let {
            recordDao.update(it.copy(rawFrameHex = source.rawFrameHex, remark = source.remark))
        }
        statusDao.byRecordId(source.id)?.let { statusDao.upsert(it.copy(weightRecordId = movedId)) }
        hardDelete(listOf(source.id), zone)
        return movedId
    }

    /** The first free second at or after [timestampEpochSec], so hand-entered readings can't collide. */
    suspend fun freeInstant(roleId: Long, timestampEpochSec: Long): Long {
        var candidate = timestampEpochSec
        while (recordDao.atInstant(roleId, candidate) != null) candidate += 60
        return candidate
    }

    /** Stores [record] unless this person already has a reading at that instant. */
    suspend fun insertIfNew(record: WeightRecordEntity, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (recordDao.atInstant(record.roleId, record.timestampEpochSec) != null) return false
        insert(record, zone)
        return true
    }

    /** True when any profile already holds this reading, e.g. after a claim. */
    suspend fun hasReading(timestampEpochSec: Long, weightKg: Double): Boolean =
        recordDao.exists(timestampEpochSec, weightKg)

    /** Soft-deletes records (history multi-select delete) and refreshes affected aggregates. */
    suspend fun softDelete(ids: List<Long>, zone: ZoneId = ZoneId.systemDefault()) {
        if (ids.isEmpty()) return
        val affected = recordDao.byIds(ids).map { it.roleId to epochDayOf(it.timestampEpochSec, zone) }.distinct()
        recordDao.softDelete(ids)
        affected.forEach { (roleId, day) -> refreshDayAggregate(roleId, day, zone) }
    }

    /** Undoes [softDelete]. */
    suspend fun restore(ids: List<Long>, zone: ZoneId = ZoneId.systemDefault()) {
        if (ids.isEmpty()) return
        recordDao.restore(ids)
        recordDao.byIds(ids).map { it.roleId to epochDayOf(it.timestampEpochSec, zone) }.distinct()
            .forEach { (roleId, day) -> refreshDayAggregate(roleId, day, zone) }
    }

    /** Clears readings whose undo window has passed. Runs at startup. */
    suspend fun purgeDeleted() = recordDao.purgeDeleted()

    /** Permanently deletes records and refreshes affected aggregates. */
    suspend fun hardDelete(ids: List<Long>, zone: ZoneId = ZoneId.systemDefault()) {
        if (ids.isEmpty()) return
        val affected = recordDao.byIds(ids).map { it.roleId to epochDayOf(it.timestampEpochSec, zone) }.distinct()
        recordDao.hardDelete(ids)
        affected.forEach { (roleId, day) -> refreshDayAggregate(roleId, day, zone) }
    }

    /**
     * Recomputes one role's aggregate for one day from its non-deleted records
     *. Removes the aggregate row when the day has no records left.
     */
    suspend fun refreshDayAggregate(
        roleId: Long,
        epochDay: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val records = recordsForDay(roleId, epochDay, zone)
        val aggregate = DayAggregation.compute(roleId, epochDay, records)
        if (aggregate == null) {
            aggregateDao.delete(roleId, epochDay)
        } else {
            aggregateDao.upsert(aggregate)
        }
    }

    companion object {
        fun create(db: AppDatabase): WeightRepository =
            WeightRepository(
                db.weightRecordDao(),
                db.dayAggregateDao(),
                db.weightStatusDao(),
                { block -> db.withTransaction { block() } },
            )

        fun epochDayOf(timestampEpochSec: Long, zone: ZoneId): Long =
            Instant.ofEpochSecond(timestampEpochSec).atZone(zone).toLocalDate().toEpochDay()
    }
}
