package app.okoklibre.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.okoklibre.algo.Sex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** One household, one database: what belongs to one person must never reach another. */
@RunWith(AndroidJUnit4::class)
class ProfileIsolationTest {
    private val zone = ZoneOffset.UTC
    private val instant = 1_780_000_000L

    private lateinit var db: AppDatabase
    private lateinit var roles: RoleRepository
    private lateinit var weights: WeightRepository
    private lateinit var goals: GoalRepository
    private lateinit var settings: SettingsRepository
    private lateinit var backups: BackupRepository
    private var asha = 0L
    private var ravi = 0L

    @Before
    fun open() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        roles = RoleRepository.create(db)
        weights = WeightRepository.create(db)
        goals = GoalRepository.create(db)
        settings = SettingsRepository.create(context)
        backups = BackupRepository(db, settings, weights, AvatarStore(context))
        asha = roles.createRole("Asha", Sex.FEMALE, 7000, 165, RoleType.MAIN)
        ravi = roles.createRole("Ravi", Sex.MALE, 6000, 178, RoleType.MEMBER)
    }

    @After
    fun close() = db.close()

    private suspend fun weigh(id: Long, kg: Double, at: Long = instant): Long =
        weights.insertComputed(roles.role(id)!!, kg, at, impedanceOhm = 500.0, zone = zone)

    @Test
    fun everyProfileGetsItsOwnColour() = runTest {
        assertTrue(roles.role(asha)!!.colorIndex != roles.role(ravi)!!.colorIndex)
    }

    @Test
    fun readingsStayWithTheirOwner() = runTest {
        weigh(asha, 62.0)
        weigh(ravi, 80.0)
        assertEquals(listOf(62.0), weights.records(asha).map { it.weightKg })
        assertEquals(listOf(80.0), weights.records(ravi).map { it.weightKg })
    }

    @Test
    fun twoPeopleCanWeighInAtTheSameSecond() = runTest {
        weigh(asha, 62.0)
        weigh(ravi, 80.0)
        assertEquals(1, weights.recordCount(asha))
        assertEquals(1, weights.recordCount(ravi))
    }

    @Test
    fun goalsAreIsolated() = runTest {
        goals.startGoal(asha, 62.0, 58.0, 100)
        assertEquals(58.0, goals.activeGoal(asha).first()!!.targetWeightKg, 0.0)
        assertNull(goals.activeGoal(ravi).first())
        goals.startGoal(ravi, 80.0, 75.0, 100)
        assertEquals(58.0, goals.activeGoal(asha).first()!!.targetWeightKg, 0.0)
    }

    @Test
    fun remindersAreIsolated() = runTest {
        val reminders = ReminderRepository.create(db)
        reminders.save(ReminderEntity(roleId = asha, timeMinutesOfDay = 420))
        assertEquals(1, reminders.remindersFor(asha).first().size)
        assertTrue(reminders.remindersFor(ravi).first().isEmpty())
    }

    @Test
    fun deletingAPersonTakesOnlyTheirData() = runTest {
        val record = weigh(ravi, 80.0)
        weigh(asha, 62.0)
        db.weightStatusDao().upsert(WeightStatusEntity(weightRecordId = record, mood = 3))
        goals.startGoal(ravi, 80.0, 75.0, 100)
        ReminderRepository.create(db).save(ReminderEntity(roleId = ravi, timeMinutesOfDay = 420))

        roles.deleteRole(roles.role(ravi)!!)

        assertTrue(weights.records(ravi).isEmpty())
        assertNull(db.weightStatusDao().byRecordId(record))
        assertNull(goals.activeGoal(ravi).first())
        assertTrue(db.reminderDao().forRole(ravi).isEmpty())
        assertEquals(1, weights.records(asha).size)
    }

    @Test
    fun deletingTheMainProfilePromotesSomeoneElse() = runTest {
        weigh(ravi, 80.0)
        roles.deleteRole(roles.role(asha)!!)
        assertEquals(RoleType.MAIN, roles.role(ravi)!!.roleType)
        assertEquals(1, weights.records(ravi).size)
    }

    @Test
    fun movingAReadingLeavesNoTraceOnTheOldProfile() = runTest {
        val id = weigh(asha, 80.0)
        val day = WeightRepository.epochDayOf(instant, zone)
        val moved = weights.moveRecord(id, roles.role(ravi)!!, zone)!!
        assertTrue(weights.records(asha).isEmpty())
        assertNull(db.dayAggregateDao().byDay(asha, day))
        assertEquals(ravi, weights.record(moved)!!.roleId)
        assertEquals(178.0, weights.record(moved)!!.heightCm, 0.0)
        assertEquals(80.0, db.dayAggregateDao().byDay(ravi, day)!!.weightKg, 0.0)
    }

    @Test
    fun attributionUsesLiveHistoryOnly() = runTest {
        weigh(asha, 62.0)
        val mistaken = weigh(asha, 79.0, instant + 60)
        weigh(ravi, 80.0, instant + 120)
        weights.softDelete(listOf(mistaken), zone)
        assertEquals(Attribution.Save(roles.role(ravi)!!, switched = true), roles.attribute(79.5, asha))
    }

    @Test
    fun aBackupRestoresEveryoneExactly() = runTest {
        val id = weigh(asha, 62.0)
        weigh(ravi, 80.0, instant + 86_400)
        db.weightStatusDao().upsert(WeightStatusEntity(weightRecordId = id, mood = 4, remark = "note"))
        goals.startGoal(asha, 62.0, 58.0, 100)
        ReminderRepository.create(db).save(ReminderEntity(roleId = ravi, timeMinutesOfDay = 420, mon = true))
        val out = ByteArrayOutputStream()
        BackupCodec.write(backups.snapshot(), out)

        roles.deleteRole(roles.role(ravi)!!)
        weigh(asha, 99.0, instant + 2 * 86_400)

        backups.restore(BackupCodec.read(ByteArrayInputStream(out.toByteArray())), zone)

        assertEquals(listOf("Asha", "Ravi"), roles.allRoles().map { it.nickname })
        assertEquals(listOf(62.0), weights.records(asha).map { it.weightKg })
        assertEquals(listOf(80.0), weights.records(ravi).map { it.weightKg })
        assertEquals("note", db.weightStatusDao().byRecordId(id)!!.remark)
        assertEquals(58.0, goals.activeGoal(asha).first()!!.targetWeightKg, 0.0)
        assertEquals(ravi, db.reminderDao().forRole(ravi).single().roleId)
        val day = WeightRepository.epochDayOf(instant, zone)
        assertEquals(62.0, db.dayAggregateDao().byDay(asha, day)!!.weightKg, 0.0)
    }

    @Test
    fun aRestoreThatFailsPartWayChangesNothing() = runTest {
        weigh(asha, 62.0)
        val good = backups.snapshot()
        val broken = good.copy(records = good.records + good.records.first().copy(id = 777))

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { backups.restore(broken, zone) }
        }

        assertEquals(listOf("Asha", "Ravi"), roles.allRoles().map { it.nickname })
        assertEquals(1, weights.recordCount(asha))
    }

    @Test
    fun aBackupWithDeletedReadingsStillValidates() = runTest {
        val id = weigh(asha, 62.0)
        db.weightStatusDao().upsert(WeightStatusEntity(weightRecordId = id, mood = 2))
        weights.softDelete(listOf(id), zone)
        val backup = backups.snapshot()
        BackupCodec.validate(backup)
        assertFalse(backup.statuses.any { it.weightRecordId == id })
    }
}
