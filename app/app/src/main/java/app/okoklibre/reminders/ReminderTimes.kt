package app.okoklibre.reminders

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Pure next-fire-time computation for weekly-repeat reminders, kept
 * Android-free so it is unit-testable on the JVM.
 */
object ReminderTimes {

    /**
     * The next time a weekly reminder fires: [minutesOfDay] (0..1439) on the
     * earliest enabled weekday that is still strictly in the future relative
     * to [now]. Null when no weekday is enabled.
     */
    fun nextFireEpochMillis(
        minutesOfDay: Int,
        days: Set<DayOfWeek>,
        now: ZonedDateTime,
    ): Long? {
        if (days.isEmpty()) return null
        val time = LocalTime.of(minutesOfDay / 60, minutesOfDay % 60)
        val today = now.toLocalDate()
        for (offset in 0L..7L) {
            val date = today.plusDays(offset)
            if (date.dayOfWeek !in days) continue
            val candidate = date.atTime(time).atZone(now.zone)
            if (candidate.isAfter(now)) return candidate.toInstant().toEpochMilli()
        }
        return null
    }
}
