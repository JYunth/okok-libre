package app.okoklibre.reminders

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderTimesTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun now(
        year: Int = 2026,
        month: Int = 10,
        day: Int = 8, // a Thursday
        hour: Int = 9,
        minute: Int = 0,
    ): ZonedDateTime = LocalDateTime.of(year, month, day, hour, minute).atZone(zone)

    @Test
    fun `later today when the day is enabled and time not passed`() {
        val fire = ReminderTimes.nextFireEpochMillis(10 * 60, setOf(DayOfWeek.THURSDAY), now())
        val expected = now(hour = 10)
        assertEquals(expected.toInstant().toEpochMilli(), fire)
    }

    @Test
    fun `same time as now counts as passed and rolls to next week`() {
        val fire = ReminderTimes.nextFireEpochMillis(9 * 60, setOf(DayOfWeek.THURSDAY), now())
        val expected = now(day = 15, hour = 9)
        assertEquals(expected.toInstant().toEpochMilli(), fire)
    }

    @Test
    fun `time already passed today picks next enabled day`() {
        // Thursday 09:00, reminder 07:30 on Thu + Sat -> Saturday.
        val fire = ReminderTimes.nextFireEpochMillis(
            7 * 60 + 30,
            setOf(DayOfWeek.THURSDAY, DayOfWeek.SATURDAY),
            now(),
        )
        val expected = now(day = 10, hour = 7, minute = 30)
        assertEquals(expected.toInstant().toEpochMilli(), fire)
    }

    @Test
    fun `wraps across the week boundary`() {
        // Thursday 09:00, only Monday enabled -> next Monday (offset 4).
        val fire = ReminderTimes.nextFireEpochMillis(8 * 60, setOf(DayOfWeek.MONDAY), now())
        val expected = now(day = 12, hour = 8)
        assertEquals(expected.toInstant().toEpochMilli(), fire)
    }

    @Test
    fun `earliest of multiple future days wins`() {
        // Thursday 09:00, enabled Fri/Sun/Mon at 06:15 -> Friday.
        val fire = ReminderTimes.nextFireEpochMillis(
            6 * 60 + 15,
            setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY),
            now(),
        )
        val expected = now(day = 9, hour = 6, minute = 15)
        assertEquals(expected.toInstant().toEpochMilli(), fire)
    }

    @Test
    fun `no enabled days means no alarm`() {
        assertNull(ReminderTimes.nextFireEpochMillis(600, emptySet(), now()))
    }

    @Test
    fun `end-of-day reminder later today`() {
        val fire = ReminderTimes.nextFireEpochMillis(23 * 60 + 59, setOf(DayOfWeek.THURSDAY), now())
        val expected = now(hour = 23, minute = 59)
        assertEquals(expected.toInstant().toEpochMilli(), fire)
    }

    @Test
    fun `crosses month boundary`() {
        // Saturday 2026-10-31 23:00, reminder Sundays 06:00 -> 2026-11-01.
        val fire = ReminderTimes.nextFireEpochMillis(
            6 * 60,
            setOf(DayOfWeek.SUNDAY),
            now(day = 31, hour = 23),
        )
        val expected = now(month = 11, day = 1, hour = 6)
        assertEquals(expected.toInstant().toEpochMilli(), fire)
    }
}
