package app.okoklibre.data

import app.okoklibre.algo.Sex
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadsheetImportTest {
    private val zone = ZoneOffset.UTC
    private val at = LocalDate.of(2026, 6, 1).atStartOfDay(zone).toEpochSecond()

    private fun role(id: Long, name: String, sex: Sex, height: Int, born: LocalDate) = RoleEntity(
        id = id, nickname = name, sex = sex, birthdayEpochDay = born.toEpochDay(), heightCm = height,
        roleType = RoleType.MEMBER, createdAtEpochSec = 0,
    )

    private fun reading(ownerId: Long, sex: Sex, height: Double, age: Int) = WeightRecordEntity(
        roleId = ownerId, weightKg = 70.0, timestampEpochSec = at, bmi = 24.0, bodyFatPct = 20.0, boneKg = 3.0,
        musclePct = 70.0, waterPct = 55.0, bmrKcal = 1500.0, bodyAge = 30, visceralFat = 5.0, score = 80,
        idealWeightKg = 65.0, heightCm = height, sex = sex, ageYears = age, impedanceOhm = 500.0, scaleProperty = 0,
    )

    private val bob = role(1, "Bob", Sex.MALE, 180, LocalDate.of(1985, 3, 1))
    private val alice = role(2, "Alice", Sex.FEMALE, 165, LocalDate.of(1992, 5, 1))

    @Test
    fun aMatchingIdIsStillCheckedAgainstTheBody() {
        // Exported where id 1 was a woman of 165 cm; here id 1 is Bob.
        val owners = matchSpreadsheetOwners(listOf(reading(1, Sex.FEMALE, 165.0, 34)), listOf(bob, alice), zone)
        assertEquals(alice, owners[1])
    }

    @Test
    fun readingsThatFitNobodyAreSkipped() {
        val owners = matchSpreadsheetOwners(listOf(reading(7, Sex.FEMALE, 150.0, 60)), listOf(bob, alice), zone)
        assertTrue(owners.isEmpty())
    }

    @Test
    fun twoEquallyGoodMatchesAreRefusedNotGuessed() {
        val twin = role(3, "Ann", Sex.FEMALE, 165, LocalDate.of(1992, 5, 1))
        val owners = matchSpreadsheetOwners(listOf(reading(9, Sex.FEMALE, 165.0, 34)), listOf(alice, twin), zone)
        assertTrue(owners.isEmpty())
    }

    @Test
    fun theSameIdWinsATie() {
        val twin = role(3, "Ann", Sex.FEMALE, 165, LocalDate.of(1992, 5, 1))
        val owners = matchSpreadsheetOwners(listOf(reading(3, Sex.FEMALE, 165.0, 34)), listOf(alice, twin), zone)
        assertEquals(twin, owners[3])
    }

    @Test
    fun peopleMatchByNameAndBirthdayNotId() {
        val fromOtherPhone = bob.copy(id = 2)
        assertEquals(bob, sameLocalPerson(fromOtherPhone, listOf(bob, alice)))
        assertNull(sameLocalPerson(bob.copy(id = 1, nickname = "Robert"), listOf(bob, alice)))
    }
}
