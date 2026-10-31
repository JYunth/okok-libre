package app.okoklibre.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val name = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun seedVersion3() {
        helper.createDatabase(name, 3).use { db ->
            db.execSQL(
                "INSERT INTO roles (id, nickname, sex, birthdayEpochDay, heightCm, roleType, createdAtEpochSec) " +
                    "VALUES (1, 'Asha', 'FEMALE', 7000, 165, 'MAIN', 1), (2, 'Ravi', 'MALE', 6000, 178, 'MEMBER', 2)",
            )
            db.execSQL(
                "INSERT INTO weight_records (id, roleId, weightKg, timestampEpochSec, bmi, bodyFatPct, boneKg, " +
                    "musclePct, waterPct, bmrKcal, bodyAge, visceralFat, score, idealWeightKg, heightCm, sex, " +
                    "ageYears, impedanceOhm, scaleProperty, deleted, source) VALUES " +
                    "(5, 1, 62.0, 1000, 22.7, 28.0, 2.5, 65.0, 50.0, 1300, 33, 4.5, 80, 57.0, 165, 'FEMALE', " +
                    "34, 500, 0, 0, 'LIVE')",
            )
            db.execSQL(
                "INSERT INTO reminders (id, timeMinutesOfDay, enabled, mon, tue, wed, thu, fri, sat, sun) " +
                    "VALUES (9, 430, 1, 1, 1, 1, 1, 1, 0, 0)",
            )
        }
    }

    @Test
    fun threeToFourValidatesAgainstTheExportedSchema() {
        seedVersion3()
        helper.runMigrationsAndValidate(name, 4, true, AppDatabase.MIGRATION_3_4).close()
    }

    @Test
    fun threeToFourKeepsEverythingAndAssignsOwners() = runTest {
        seedVersion3()
        helper.runMigrationsAndValidate(name, 4, true, AppDatabase.MIGRATION_3_4).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            name,
        ).addMigrations(AppDatabase.MIGRATION_3_4).build()
        try {
            val roles = db.roleDao().all()
            assertEquals(listOf("Asha", "Ravi"), roles.map { it.nickname })
            assertNotEquals(roles[0].colorIndex, roles[1].colorIndex)
            assertEquals(62.0, db.weightRecordDao().all(1).single().weightKg, 0.0)
            val reminder = db.reminderDao().all().single()
            assertEquals(1L, reminder.roleId)
            assertEquals(430, reminder.timeMinutesOfDay)
        } finally {
            db.close()
        }
    }
}
