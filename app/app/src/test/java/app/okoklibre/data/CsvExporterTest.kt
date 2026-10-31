package app.okoklibre.data

import app.okoklibre.algo.Sex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExporterTest {

    private fun sampleRoles() = listOf(
        RoleEntity(
            id = 1,
            nickname = "Alex",
            sex = Sex.MALE,
            birthdayEpochDay = 10000,
            heightCm = 178,
            weightGoalKg = 75.5,
            avatarPath = null,
            roleType = RoleType.MAIN,
            createdAtEpochSec = 1_700_000_000,
        ),
        RoleEntity(
            id = 2,
            nickname = "Tricky, \"quoted\"\nname",
            sex = Sex.FEMALE,
            birthdayEpochDay = 15000,
            heightCm = 165,
            weightGoalKg = null,
            avatarPath = "/data/avatars/a,b.png",
            roleType = RoleType.MEMBER,
            createdAtEpochSec = 1_700_000_100,
        ),
    )

    private fun sampleRecords() = listOf(
        WeightRecordEntity(
            id = 10,
            roleId = 1,
            weightKg = 80.25,
            timestampEpochSec = 1_700_100_000,
            bmi = 25.3,
            bodyFatPct = 20.5,
            boneKg = 3.1,
            musclePct = 42.0,
            waterPct = 55.0,
            bmrKcal = 1750.0,
            bodyAge = 30,
            visceralFat = 9.0,
            score = 80,
            idealWeightKg = 68.6,
            heightCm = 178.0,
            sex = Sex.MALE,
            ageYears = 29,
            impedanceOhm = 460.0,
            scaleProperty = 12,
            scaleWeightString = "80.2",
            productId = 1812L,
            remark = "2:72",
            heartRateBpm = 72,
            deleted = false,
        ),
        WeightRecordEntity(
            id = 11,
            roleId = 2,
            weightKg = 60.0,
            timestampEpochSec = 1_700_200_000,
            bmi = 22.0,
            bodyFatPct = 28.0,
            boneKg = 2.1,
            musclePct = 36.5,
            waterPct = 50.5,
            bmrKcal = 1350.5,
            bodyAge = 0,
            visceralFat = 0.0,
            score = 0,
            idealWeightKg = 57.0,
            heightCm = 165.0,
            sex = Sex.FEMALE,
            ageYears = 16,
            impedanceOhm = 0.0,
            scaleProperty = 0,
            scaleWeightString = null,
            productId = null,
            remark = "manual, entry \"approx\"\r\nsecond line",
            heartRateBpm = null,
            deleted = true,
        ),
    )

    @Test
    fun `roles round-trip`() {
        val out = ByteArrayOutputStream()
        CsvExporter.exportRoles(sampleRoles(), out)
        val result = CsvExporter.importRoles(ByteArrayInputStream(out.toByteArray()))

        assertTrue(result.errors.toString(), result.ok)
        assertEquals(sampleRoles(), result.rows)
    }

    @Test
    fun `records round-trip`() {
        val out = ByteArrayOutputStream()
        CsvExporter.exportRecords(sampleRecords(), out)
        val result = CsvExporter.importRecords(ByteArrayInputStream(out.toByteArray()))

        assertTrue(result.errors.toString(), result.ok)
        assertEquals(sampleRecords(), result.rows)
    }

    @Test
    fun `export writes header first`() {
        val out = ByteArrayOutputStream()
        CsvExporter.exportRoles(sampleRoles(), out)
        val text = out.toString(Charsets.UTF_8)
        assertTrue(text.startsWith(CsvExporter.ROLE_HEADER + "\r\n"))
        assertTrue(text.endsWith("\r\n"))
    }

    @Test
    fun `import rejects wrong column count`() {
        val csv = CsvExporter.ROLE_HEADER + "\r\n1,Alex,MALE,10000\r\n"
        val result = CsvExporter.importRoles(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(0, result.rows.size)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("expected 9 columns"))
    }

    @Test
    fun `import rejects malformed numbers and enums but keeps valid rows`() {
        val csv = buildString {
            append(CsvExporter.ROLE_HEADER).append("\r\n")
            append("1,Alex,MALE,10000,178,75.5,,MAIN,1700000000\r\n")
            append("2,Bot,MALE,notanumber,178,,,MEMBER,1700000000\r\n")
            append("3,Bob,NONBINARY,10000,178,,,MEMBER,1700000000\r\n")
        }
        val result = CsvExporter.importRoles(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(1, result.rows.size)
        assertEquals("Alex", result.rows[0].nickname)
        assertEquals(2, result.errors.size)
        assertTrue(result.errors.all { it.startsWith("line ") })
    }

    @Test
    fun `import flags unexpected header`() {
        val csv = "wrong,header\r\n1,Alex,MALE,10000,178,75.5,,MAIN,1700000000\r\n"
        val result = CsvExporter.importRoles(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(1, result.rows.size)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("unexpected header"))
    }

    @Test
    fun `import of empty file reports error`() {
        val result = CsvExporter.importRoles(ByteArrayInputStream(ByteArray(0)))
        assertFalse(result.ok)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `nullable fields map to empty cells and back`() {
        val role = sampleRoles()[1].copy(nickname = "Plain", avatarPath = null)
        val out = ByteArrayOutputStream()
        CsvExporter.exportRoles(listOf(role), out)
        val text = out.toString(Charsets.UTF_8)

        // weightGoalKg and avatarPath columns are empty
        val dataLine = text.lines()[1]
        assertEquals("2,Plain,FEMALE,15000,165,,,MEMBER,1700000100", dataLine)

        val result = CsvExporter.importRoles(ByteArrayInputStream(out.toByteArray()))
        assertEquals(role, result.rows.single())
        assertNull(result.rows.single().weightGoalKg)
    }
}
