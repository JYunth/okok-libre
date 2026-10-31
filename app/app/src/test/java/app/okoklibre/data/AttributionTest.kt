package app.okoklibre.data

import app.okoklibre.algo.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionTest {

    private fun role(id: Long, type: RoleType = RoleType.MEMBER) = RoleEntity(
        id = id, nickname = "p$id", sex = Sex.MALE, birthdayEpochDay = 0, heightCm = 170,
        roleType = type, createdAtEpochSec = 0,
    )

    private val asha = role(1, RoleType.MAIN)
    private val ravi = role(2)
    private val mira = role(3)
    private val everyone = listOf(asha, ravi, mira)

    @Test
    fun onlyProfileAlwaysGetsIt() {
        assertEquals(Attribution.Save(asha, false), attributeReading(120.0, listOf(asha), mapOf(1L to 60.0), 1))
    }

    @Test
    fun activePersonWithinThreeKilosGetsIt() {
        val last = mapOf(1L to 60.0, 2L to 90.0, 3L to 45.0)
        assertEquals(Attribution.Save(asha, false), attributeReading(61.0, everyone, last, 1))
    }

    @Test
    fun aForgottenSwitchFindsTheRightPerson() {
        val last = mapOf(1L to 60.0, 2L to 90.0, 3L to 45.0)
        assertEquals(Attribution.Save(ravi, true), attributeReading(89.0, everyone, last, 1))
    }

    @Test
    fun closeHouseholdAsksWithTheActivePersonFirst() {
        val last = mapOf(1L to 60.0, 2L to 61.0, 3L to 45.0)
        val result = attributeReading(60.5, everyone, last, 2) as Attribution.Confirm
        assertEquals(listOf(ravi, asha), result.candidates)
    }

    @Test
    fun nobodyCloseMeansAsk() {
        val last = mapOf(1L to 60.0, 2L to 90.0, 3L to 45.0)
        assertEquals(Attribution.Ask, attributeReading(75.0, everyone, last, 1))
    }

    @Test
    fun aNewActiveProfileGetsItsFirstReading() {
        val last = mapOf(1L to 60.0, 2L to 90.0)
        assertEquals(Attribution.Save(mira, false), attributeReading(45.0, everyone, last, 3))
    }

    @Test
    fun aNewActiveProfileStillConfirmsAgainstSomeoneClose() {
        val last = mapOf(1L to 60.0, 2L to 90.0)
        val result = attributeReading(60.0, everyone, last, 3) as Attribution.Confirm
        assertEquals(listOf(mira, asha), result.candidates)
    }

    @Test
    fun aReadingNeverLandsOnAStrangerSilently() {
        val last = mapOf(1L to 60.0, 2L to 61.0)
        assertTrue(attributeReading(60.5, everyone, last, 1) is Attribution.Confirm)
    }

    @Test
    fun aDeletedProfilesHistoryIsIgnored() {
        val last = mapOf(1L to 60.0, 99L to 75.0)
        assertEquals(Attribution.Ask, attributeReading(75.0, listOf(asha, ravi), last, 1))
    }

    @Test
    fun resolvingActiveFallsBackSafely() {
        assertEquals(ravi, resolveActiveRole(everyone, 2))
        assertEquals(asha, resolveActiveRole(everyone, 99))
        assertEquals(asha, resolveActiveRole(everyone, null))
        assertEquals(ravi, resolveActiveRole(listOf(ravi), 99))
        assertEquals(null, resolveActiveRole(emptyList(), 1))
    }
}
