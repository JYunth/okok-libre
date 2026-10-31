package app.okoklibre.data

import app.okoklibre.algo.Sex
import app.okoklibre.ble.protocol.ScaleProtocolType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCodecTest {

    private val role = RoleEntity(
        id = 4, nickname = "Asha, \"the\" boss", sex = Sex.FEMALE, birthdayEpochDay = 7000, heightCm = 165,
        roleType = RoleType.MAIN, createdAtEpochSec = 9, colorIndex = 2,
    )
    private val record = WeightRecordEntity(
        id = 11, roleId = 4, weightKg = 60.25, timestampEpochSec = 1000, bmi = 22.1, bodyFatPct = 25.5,
        boneKg = 2.4, musclePct = 40.0, waterPct = 50.0, bmrKcal = 1300.0, bodyAge = 30, visceralFat = 4.5,
        score = 80, idealWeightKg = 57.0, heightCm = 165.0, sex = Sex.FEMALE, ageYears = 30, impedanceOhm = 500.0,
        scaleProperty = 3, rawFrameHex = "cafe", source = RecordSource.OFFLINE_HISTORY,
    )
    private val full = Backup(
        createdAtEpochSec = 1,
        roles = listOf(role),
        records = listOf(record),
        statuses = listOf(WeightStatusEntity(weightRecordId = 11, mood = 3, remark = "after run")),
        goals = listOf(GoalEntity(id = 2, roleId = 4, initWeightKg = 62.0, targetWeightKg = 58.0, startEpochDay = 3)),
        reminders = listOf(ReminderEntity(id = 5, roleId = 4, timeMinutesOfDay = 420, mon = true)),
        devices = listOf(
            ScaleDeviceEntity("AA:BB", "Scale", 1, 7L, 17, ScaleProtocolType.entries.first(), 12L),
        ),
        pending = listOf(TmpWeightEntity(id = 8, mac = "AA:BB", weightKg = 70.0, timestampEpochSec = 2)),
        avatars = mapOf(4L to "AAAA"),
        settings = SettingsSnapshot(weightUnit = "LB", activeRoleId = 4),
    )

    private fun roundTrip(backup: Backup): Backup {
        val out = ByteArrayOutputStream()
        BackupCodec.write(backup, out)
        return BackupCodec.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun everythingSurvivesARoundTrip() {
        assertEquals(full, roundTrip(full))
    }

    @Test
    fun garbageIsRefusedWithAPlainReason() {
        val error = assertThrows(InvalidBackupException::class.java) {
            BackupCodec.read(ByteArrayInputStream("id,nickname\n1,Asha".toByteArray()))
        }
        assertEquals("This isn't an OKOK Libre backup file.", error.message)
    }

    @Test
    fun futureFormatsAreRefused() {
        assertThrows(InvalidBackupException::class.java) { BackupCodec.validate(full.copy(format = Backup.FORMAT + 1)) }
    }

    @Test
    fun orphanedRecordsAreRefused() {
        assertThrows(InvalidBackupException::class.java) {
            BackupCodec.validate(full.copy(records = listOf(record.copy(roleId = 99))))
        }
    }

    @Test
    fun emptyBackupsAreRefused() {
        assertThrows(InvalidBackupException::class.java) { BackupCodec.validate(full.copy(roles = emptyList())) }
    }
}
