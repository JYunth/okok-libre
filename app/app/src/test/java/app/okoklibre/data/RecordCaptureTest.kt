package app.okoklibre.data

import app.okoklibre.algo.Sex
import app.okoklibre.ble.protocol.Measurement
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the record-capture gaps: the scale-computed gate must be
 * sentinel-aware (0.0 = no data), the source marker must
 * distinguish live weigh-ins from claimed offline history, and the tmp table
 * must carry heart rate + scale role id through add → claim.
 */
class RecordCaptureTest {

    private val role = RoleEntity(
        id = 42,
        nickname = "Test",
        sex = Sex.MALE,
        birthdayEpochDay = LocalDate.of(1990, 1, 1).toEpochDay(),
        heightCm = 175,
        roleType = RoleType.MAIN,
        createdAtEpochSec = 0,
    )

    private class Harness {
        val recordDao = FakeWeightRecordDao()
        val aggregateDao = FakeDayAggregateDao()
        val roleDao = FakeRoleDao()
        val tmpDao = FakeTmpWeightDao()
        val statusDao = FakeWeightStatusDao()
        val weight = WeightRepository(recordDao, aggregateDao, statusDao)
        val tmp = TmpWeightRepository(tmpDao, RoleRepository(roleDao, recordDao), weight)
    }

    private fun harnessWithRole(): Harness {
        val h = Harness()
        runBlocking { h.roleDao.upsert(role) }
        return h
    }

    // ---------- gap 3: sentinel-aware scale-computed gate ----------

    @Test
    fun `composition frame with clamped-out fat keeps the frame's other fields`() = runBlocking {
        val h = harnessWithRole()
        // fat raw 48 (4.8%) is outside [5,45] → 0.0 sentinel, but the same
        // frame carries valid water/muscle/BMR/visceral/bone that must persist.
        val m = Measurement(
            weightKg = 65.0,
            lockFlag = true,
            fatPct = 0.0,
            waterPct = 55.0,
            musclePct = 46.1,
            bmrKcal = 1600,
            visceralFat = 8.0,
            boneKg = 2.4,
            scaleProperty = 0x01,
            rawFrame = byteArrayOf(0xCA.toByte(), 0x11),
        )
        val id = h.weight.insertScaleComputed(role = role, measurement = m)
        val r = h.recordDao.byId(id)!!
        assertEquals(0.0, r.bodyFatPct, 0.0) // fat itself stays the 0.0 sentinel
        assertEquals(55.0, r.waterPct, 0.0)
        assertEquals(46.1, r.musclePct, 0.0)
        assertEquals(1600.0, r.bmrKcal, 0.0)
        assertEquals(8.0, r.visceralFat, 0.0)
        assertEquals(2.4, r.boneKg, 0.0)
        assertNotNull(r.rawFrameHex) // raw frame stored on all paths
    }

    @Test
    fun `genuine weight-only frame stays weight-only`() = runBlocking {
        val h = harnessWithRole()
        val m = Measurement(
            weightKg = 65.0,
            lockFlag = true,
            scaleProperty = 0x01,
            rawFrame = byteArrayOf(0xCA.toByte(), 0x11),
        )
        val id = h.weight.insertScaleComputed(role = role, measurement = m)
        val r = h.recordDao.byId(id)!!
        assertEquals(0.0, r.bodyFatPct, 0.0)
        assertEquals(0.0, r.waterPct, 0.0)
        assertEquals(0.0, r.musclePct, 0.0)
        assertEquals(0.0, r.bmrKcal, 0.0)
        assertEquals(0.0, r.visceralFat, 0.0)
        assertEquals(0.0, r.boneKg, 0.0)
        assertNotNull(r.rawFrameHex)
    }

    @Test
    fun `impedance frame still takes the computed path`() = runBlocking {
        val h = harnessWithRole()
        val m = Measurement(weightKg = 70.0, lockFlag = true, impedanceOhm = 500.0, scaleProperty = 0x01)
        val id = h.weight.insertScaleComputed(role = role, measurement = m)
        val r = h.recordDao.byId(id)!!
        assertTrue(r.impedanceOhm > 0.0)
        assertTrue(r.bodyFatPct > 0.0) // computed app-side from impedance
    }

    // ---------- gap 5: record source marker ----------

    @Test
    fun `live measurement is marked LIVE`() = runBlocking {
        val h = harnessWithRole()
        val id = h.weight.insertScaleComputed(
            role = role,
            measurement = Measurement(weightKg = 65.0, lockFlag = true, scaleProperty = 0x01),
        )
        assertEquals(RecordSource.LIVE, h.recordDao.byId(id)!!.source)
    }

    @Test
    fun `claimed history record is marked OFFLINE_HISTORY`() = runBlocking {
        val h = harnessWithRole()
        val tmpId = h.tmp.add(
            mac = "AA:BB:CC:DD:EE:FF",
            measurement = Measurement(
                weightKg = 65.0,
                lockFlag = true,
                timestampEpochSec = 1_700_000_000,
                scaleProperty = 0x01,
                rawFrame = byteArrayOf(0xCA.toByte(), 0x13),
            ),
        )
        val recordId = h.tmp.claim(tmpId, role.id)!!
        assertEquals(RecordSource.OFFLINE_HISTORY, h.recordDao.byId(recordId)!!.source)
    }

    // ---------- gaps 1 + 2: heart rate and scale roleId through the tmp table ----------

    @Test
    fun `add captures heart rate and scale role id from the measurement`() = runBlocking {
        val h = Harness()
        h.tmp.add(
            mac = "AA:BB:CC:DD:EE:FF",
            measurement = Measurement(
                weightKg = 65.0,
                lockFlag = true,
                timestampEpochSec = 1_700_000_000,
                heartRateBpm = 72,
                roleId = 42,
                scaleProperty = 0x01,
            ),
        )
        val row = h.tmpDao.all().single()
        assertEquals(72, row.heartRateBpm)
        assertEquals(42, row.scaleRoleId)
    }

    @Test
    fun `claim threads heart rate into the final record`() = runBlocking {
        val h = harnessWithRole()
        val tmpId = h.tmp.add(
            mac = "AA:BB:CC:DD:EE:FF",
            measurement = Measurement(
                weightKg = 65.0,
                lockFlag = true,
                timestampEpochSec = 1_700_000_000,
                heartRateBpm = 72,
                roleId = 42,
                scaleProperty = 0x01,
                rawFrame = byteArrayOf(0xC5.toByte()),
            ),
        )
        val recordId = h.tmp.claim(tmpId, role.id)!!
        val r = h.recordDao.byId(recordId)!!
        assertEquals(72, r.heartRateBpm)
        assertEquals("c5", r.rawFrameHex) // the tmp row's raw frame survives the claim
    }

    // ---------- gap 2: known-role auto-attribution ----------

    @Test
    fun `addOrAutoClaim claims immediately when the scale role id is known`() = runBlocking {
        val h = harnessWithRole()
        val recordId = h.tmp.addOrAutoClaim(
            mac = "AA:BB:CC:DD:EE:FF",
            measurement = Measurement(
                weightKg = 65.0,
                lockFlag = true,
                timestampEpochSec = 1_700_000_000,
                roleId = 42,
                scaleProperty = 0x01,
            ),
        )
        assertNotNull(recordId)
        val r = h.recordDao.byId(recordId!!)!!
        assertEquals(role.id, r.roleId)
        assertEquals(RecordSource.OFFLINE_HISTORY, r.source)
        assertTrue(h.tmpDao.all().isEmpty()) // claimed out of the tmp table
    }

    @Test
    fun `addOrAutoClaim leaves unknown role pending in the tmp table`() = runBlocking {
        val h = harnessWithRole()
        val recordId = h.tmp.addOrAutoClaim(
            mac = "AA:BB:CC:DD:EE:FF",
            measurement = Measurement(
                weightKg = 65.0,
                lockFlag = true,
                timestampEpochSec = 1_700_000_000,
                roleId = 77, // no such role
                scaleProperty = 0x01,
            ),
        )
        assertNull(recordId)
        assertEquals(1, h.tmpDao.all().size)
        assertEquals(77, h.tmpDao.all().single().scaleRoleId)
    }

    @Test
    fun `addOrAutoClaim leaves roleless history records pending`() = runBlocking {
        val h = harnessWithRole()
        val recordId = h.tmp.addOrAutoClaim(
            mac = "AA:BB:CC:DD:EE:FF",
            measurement = Measurement(
                weightKg = 65.0,
                lockFlag = true,
                timestampEpochSec = 1_700_000_000,
                scaleProperty = 0x01,
            ),
        )
        assertNull(recordId)
        assertEquals(1, h.tmpDao.all().size)
    }
}

// ------------------------------------------------------------------
// Minimal in-memory DAO fakes (JVM unit tests have no Room runtime).
// ------------------------------------------------------------------

class FakeRoleDao(initial: List<RoleEntity> = emptyList()) : RoleDao {
    val rows = initial.toMutableList()

    override suspend fun insert(role: RoleEntity): Long {
        rows += role
        return role.id
    }

    override suspend fun upsert(role: RoleEntity) {
        rows.removeAll { it.id == role.id }
        rows += role
    }

    override suspend fun promoteOldestToMain() {
        val oldest = rows.minByOrNull { it.id } ?: return
        rows.replaceAll { if (it.id == oldest.id) it.copy(roleType = RoleType.MAIN) else it }
    }

    override suspend fun clear() {
        rows.clear()
    }

    override suspend fun update(role: RoleEntity) {
        rows.replaceAll { if (it.id == role.id) role else it }
    }

    override suspend fun delete(role: RoleEntity) {
        rows.removeAll { it.id == role.id }
    }

    override fun observeAll(): Flow<List<RoleEntity>> = flow { emit(rows.toList()) }

    override suspend fun all(): List<RoleEntity> = rows.toList()

    override suspend fun byId(id: Long): RoleEntity? = rows.firstOrNull { it.id == id }

    override suspend fun mainRole(): RoleEntity? = rows.firstOrNull { it.roleType == RoleType.MAIN }

    override fun observeMainRole(): Flow<RoleEntity?> = flow { emit(mainRole()) }

    override suspend fun count(): Int = rows.size
}

class FakeWeightRecordDao : WeightRecordDao {
    val rows = mutableListOf<WeightRecordEntity>()
    private var nextId = 1L

    override suspend fun insert(record: WeightRecordEntity): Long {
        val id = if (record.id == 0L) nextId++ else record.id
        check(rows.none { it.roleId == record.roleId && it.timestampEpochSec == record.timestampEpochSec }) {
            "unique (roleId, timestampEpochSec) violated"
        }
        rows += record.copy(id = id)
        return id
    }

    override suspend fun update(record: WeightRecordEntity) {
        rows.replaceAll { if (it.id == record.id) record else it }
    }

    override suspend fun exists(at: Long, weightKg: Double): Boolean =
        rows.any { it.timestampEpochSec == at && it.weightKg == weightKg }

    override suspend fun atInstant(roleId: Long, timestampEpochSec: Long): WeightRecordEntity? =
        rows.firstOrNull { it.roleId == roleId && it.timestampEpochSec == timestampEpochSec }

    override suspend fun restore(ids: List<Long>) {
        rows.replaceAll { if (it.id in ids) it.copy(deleted = false) else it }
    }

    override suspend fun purgeDeleted() {
        rows.removeAll { it.deleted }
    }

    override suspend fun allLive(): List<WeightRecordEntity> = visible()

    override suspend fun byId(id: Long): WeightRecordEntity? = rows.firstOrNull { it.id == id }

    override suspend fun latest(roleId: Long): WeightRecordEntity? =
        visible().filter { it.roleId == roleId }.maxByOrNull { it.timestampEpochSec }

    override fun observeLatest(roleId: Long): Flow<WeightRecordEntity?> = flow { emit(latest(roleId)) }

    override suspend fun latestPerRole(): List<WeightRecordEntity> =
        visible().groupBy { it.roleId }.map { (_, v) -> v.maxBy { it.timestampEpochSec } }

    override fun observeAll(roleId: Long): Flow<List<WeightRecordEntity>> = flow { emit(all(roleId)) }

    override suspend fun page(roleId: Long, limit: Int, offset: Int): List<WeightRecordEntity> =
        all(roleId).drop(offset).take(limit)

    override suspend fun all(roleId: Long): List<WeightRecordEntity> =
        visible().filter { it.roleId == roleId }.sortedByDescending { it.timestampEpochSec }

    override suspend fun between(
        roleId: Long,
        startEpochSec: Long,
        endEpochSec: Long,
    ): List<WeightRecordEntity> = visible()
        .filter { it.roleId == roleId && it.timestampEpochSec >= startEpochSec && it.timestampEpochSec < endEpochSec }
        .sortedBy { it.timestampEpochSec }

    override suspend fun softDelete(ids: List<Long>) {
        rows.replaceAll { if (it.id in ids) it.copy(deleted = true) else it }
    }

    override suspend fun byIds(ids: List<Long>): List<WeightRecordEntity> = rows.filter { it.id in ids }

    override suspend fun hardDelete(ids: List<Long>) {
        rows.removeAll { it.id in ids }
    }

    override suspend fun count(roleId: Long): Int = visible().count { it.roleId == roleId }

    private fun visible() = rows.filter { !it.deleted }
}

class FakeDayAggregateDao : DayAggregateDao {
    val rows = mutableListOf<DayAggregateEntity>()

    override suspend fun upsert(aggregate: DayAggregateEntity) {
        rows.removeAll { it.roleId == aggregate.roleId && it.dateEpochDay == aggregate.dateEpochDay }
        rows += aggregate
    }

    override suspend fun delete(roleId: Long, dateEpochDay: Long) {
        rows.removeAll { it.roleId == roleId && it.dateEpochDay == dateEpochDay }
    }

    override suspend fun clear() {
        rows.clear()
    }

    override suspend fun byDay(roleId: Long, dateEpochDay: Long): DayAggregateEntity? =
        rows.firstOrNull { it.roleId == roleId && it.dateEpochDay == dateEpochDay }

    override suspend fun between(roleId: Long, startEpochDay: Long, endEpochDay: Long): List<DayAggregateEntity> =
        rows.filter { it.roleId == roleId && it.dateEpochDay in startEpochDay..endEpochDay }
            .sortedBy { it.dateEpochDay }

    override fun observeBetween(roleId: Long, startEpochDay: Long, endEpochDay: Long): Flow<List<DayAggregateEntity>> =
        flow { emit(between(roleId, startEpochDay, endEpochDay)) }
}

class FakeTmpWeightDao : TmpWeightDao {
    val rows = mutableListOf<TmpWeightEntity>()
    private var nextId = 1L

    override suspend fun insert(tmp: TmpWeightEntity): Long {
        val id = if (tmp.id == 0L) nextId++ else tmp.id
        rows += tmp.copy(id = id)
        return id
    }

    override fun observeAll(): Flow<List<TmpWeightEntity>> = flow { emit(rows.sortedByDescending { it.timestampEpochSec }) }

    override suspend fun all(): List<TmpWeightEntity> = rows.sortedByDescending { it.timestampEpochSec }

    override suspend fun byId(id: Long): TmpWeightEntity? = rows.firstOrNull { it.id == id }

    override suspend fun find(mac: String, timestampEpochSec: Long, weightKg: Double): TmpWeightEntity? =
        rows.firstOrNull { it.mac == mac && it.timestampEpochSec == timestampEpochSec && it.weightKg == weightKg }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun clear() {
        rows.clear()
    }
}

class FakeWeightStatusDao : WeightStatusDao {
    val rows = mutableMapOf<Long, WeightStatusEntity>()

    override suspend fun upsert(status: WeightStatusEntity) {
        rows[status.weightRecordId] = status
    }

    override suspend fun byRecordId(weightRecordId: Long): WeightStatusEntity? = rows[weightRecordId]

    override fun observeByRecordId(weightRecordId: Long): Flow<WeightStatusEntity?> = flow { emit(rows[weightRecordId]) }

    override suspend fun delete(weightRecordId: Long) {
        rows.remove(weightRecordId)
    }

    override suspend fun all(): List<WeightStatusEntity> = rows.values.toList()

    override fun observeForRole(roleId: Long): Flow<List<WeightStatusEntity>> = flow { emit(rows.values.toList()) }
}

class RecordSafetyTest {
    private val asha = RoleEntity(
        id = 1, nickname = "Asha", sex = Sex.FEMALE, birthdayEpochDay = 7000, heightCm = 165,
        roleType = RoleType.MAIN, createdAtEpochSec = 0,
    )
    private val ravi = asha.copy(id = 2, nickname = "Ravi", sex = Sex.MALE, heightCm = 182, roleType = RoleType.MEMBER)

    private val records = FakeWeightRecordDao()
    private val statuses = FakeWeightStatusDao()
    private val aggregates = FakeDayAggregateDao()
    private val repo = WeightRepository(records, aggregates, statuses)
    private val zone = ZoneOffset.UTC

    private companion object {
        const val INSTANT = 1_780_000_000L
    }

    @Test
    fun aSecondReadingAtTheSameInstantUpdatesInPlaceAndKeepsItsTags() = runBlocking {
        val id = repo.insertComputed(asha, 60.0, INSTANT, impedanceOhm = 500.0, zone = zone)
        statuses.upsert(WeightStatusEntity(weightRecordId = id, mood = 2))
        val again = repo.insertComputed(asha, 60.5, INSTANT, impedanceOhm = 500.0, zone = zone)
        assertEquals(id, again)
        assertEquals(60.5, records.byId(id)!!.weightKg, 1e-9)
        assertEquals(2, statuses.byRecordId(id)!!.mood)
    }

    @Test
    fun movingAReadingRecomputesForTheNewOwnerAndCarriesTags() = runBlocking {
        val id = repo.insertComputed(asha, 80.0, INSTANT, impedanceOhm = 480.0, zone = zone)
        val hers = records.byId(id)!!
        statuses.upsert(WeightStatusEntity(weightRecordId = id, mood = 4))

        val movedId = repo.moveRecord(id, ravi, zone)!!

        val his = records.byId(movedId)!!
        assertEquals(2L, his.roleId)
        assertEquals(182.0, his.heightCm, 1e-9)
        assertEquals(Sex.MALE, his.sex)
        assertTrue("fat must be recomputed", his.bodyFatPct != hers.bodyFatPct)
        assertNull(records.byId(id))
        assertEquals(4, statuses.byRecordId(movedId)!!.mood)
        assertTrue(records.all(1).isEmpty())
        val day = WeightRepository.epochDayOf(INSTANT, zone)
        assertNull(aggregates.byDay(1, day))
        assertEquals(80.0, aggregates.byDay(2, day)!!.weightKg, 1e-9)
    }

    @Test
    fun movingOntoATakenInstantKeepsBothReadings() = runBlocking {
        val hers = repo.insertComputed(asha, 80.0, INSTANT, impedanceOhm = 480.0, zone = zone)
        val his = repo.insertComputed(ravi, 81.0, INSTANT, impedanceOhm = 480.0, zone = zone)
        val moved = repo.moveRecord(hers, ravi, zone)!!
        assertEquals(2, records.all(2).size)
        assertEquals(81.0, records.byId(his)!!.weightKg, 1e-9)
        assertEquals(80.0, records.byId(moved)!!.weightKg, 1e-9)
    }

    @Test
    fun handEnteredReadingsOnTheSameDayDontCollide() = runBlocking {
        val first = repo.freeInstant(1, INSTANT)
        repo.insertComputed(asha, 60.0, first, zone = zone)
        val second = repo.freeInstant(1, INSTANT)
        repo.insertComputed(asha, 61.0, second, zone = zone)
        assertEquals(2, records.all(1).size)
    }

    @Test
    fun aReimportLeavesExistingReadingsUntouched() = runBlocking {
        val id = repo.insertComputed(asha, 60.0, INSTANT, impedanceOhm = 500.0, rawFrameHex = "cafe", zone = zone)
        val copy = records.byId(id)!!.copy(id = 99, rawFrameHex = null, weightKg = 1.0)
        assertEquals(false, repo.insertIfNew(copy, zone))
        assertEquals("cafe", records.byId(id)!!.rawFrameHex)
    }

    @Test
    fun aClaimedReadingIsNotQueuedAgainWhenTheScaleResends() = runBlocking {
        val roles = FakeRoleDao(listOf(asha))
        val tmpDao = FakeTmpWeightDao()
        val tmp = TmpWeightRepository(tmpDao, RoleRepository(roles, records), repo)
        val resent = Measurement(weightKg = 60.0, lockFlag = true, timestampEpochSec = INSTANT, scaleProperty = 0, impedanceOhm = 500.0)
        val tmpId = tmp.add("mac", resent)
        tmp.claim(tmpId, 1, zone)
        tmp.addOrAutoClaim("mac", resent)
        assertTrue(tmpDao.rows.isEmpty())
    }

    @Test
    fun undoingADeleteBringsTheDayBack() = runBlocking {
        val id = repo.insertComputed(asha, 60.0, INSTANT, impedanceOhm = 500.0, zone = zone)
        val day = WeightRepository.epochDayOf(INSTANT, zone)
        repo.softDelete(listOf(id), zone)
        assertNull(aggregates.byDay(1, day))
        repo.restore(listOf(id), zone)
        assertEquals(60.0, aggregates.byDay(1, day)!!.weightKg, 1e-9)
        assertEquals(1, records.all(1).size)
    }

    @Test
    fun anOlderOfflineReadingIsNotSmoothedAgainstTheFuture() = runBlocking {
        repo.insertComputed(asha, 60.0, INSTANT, impedanceOhm = 500.0, zone = zone)
        val older = repo.insertComputed(asha, 60.0, INSTANT - 1_000, impedanceOhm = 560.0, zone = zone)
        assertEquals(560.0, records.byId(older)!!.impedanceOhm, 1e-9)
    }
}
