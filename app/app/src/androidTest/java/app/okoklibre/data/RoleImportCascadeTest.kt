package app.okoklibre.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.okoklibre.algo.Sex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Re-importing a profile that already exists must not touch its history. Room's
 * REPLACE deletes the old row first, which fires ON DELETE CASCADE on every child.
 */
@RunWith(AndroidJUnit4::class)
class RoleImportCascadeTest {
    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun reimportingAProfileKeepsItsRecords() = runTest {
        val role = RoleEntity(
            id = 1, nickname = "Asha", sex = Sex.FEMALE, birthdayEpochDay = 7000,
            heightCm = 165, roleType = RoleType.MAIN, createdAtEpochSec = 1,
        )
        db.roleDao().upsert(role)
        db.weightRecordDao().insert(
            WeightRecordEntity(
                roleId = 1, weightKg = 60.0, timestampEpochSec = 1000, bmi = 22.0,
                bodyFatPct = 25.0, boneKg = 2.4, musclePct = 40.0, waterPct = 50.0,
                bmrKcal = 1300.0, bodyAge = 30, visceralFat = 4.0, score = 80,
                idealWeightKg = 57.0, heightCm = 165.0, sex = Sex.FEMALE, ageYears = 30,
                impedanceOhm = 500.0, scaleProperty = 0,
            ),
        )

        db.roleDao().upsert(role.copy(nickname = "Asha R"))

        assertEquals(1, db.weightRecordDao().count(1))
    }
}
