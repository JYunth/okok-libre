package app.okoklibre.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.okoklibre.data.ReminderEntity
import java.time.ZonedDateTime

/**
 * Arms exact alarms for weekly weigh-in reminders. Uses SCHEDULE_EXACT_ALARM
 * (declared in the manifest); on Android 12+ it degrades to inexact alarms if
 * the user revoked exact-alarm permission.
 *
 * Weekly repetition is emulated: [ReminderReceiver] re-arms the next fire
 * time after each alarm goes off, and [BootReceiver] re-arms everything after
 * reboot.
 */
class ReminderScheduler(
    private val context: Context,
) {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Arms the next occurrence of [reminder]; no-op when disabled or no weekday selected. */
    fun schedule(reminder: ReminderEntity, now: ZonedDateTime = ZonedDateTime.now()) {
        cancel(reminder.id)
        if (!reminder.enabled) return
        val triggerAt = ReminderTimes.nextFireEpochMillis(
            reminder.timeMinutesOfDay,
            reminder.daysOfWeek(),
            now,
        ) ?: return
        val intent = pendingIntent(reminder.id, PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
    }

    fun scheduleAll(reminders: List<ReminderEntity>, now: ZonedDateTime = ZonedDateTime.now()) {
        reminders.forEach { schedule(it, now) }
    }

    fun cancel(reminderId: Long) {
        alarmManager.cancel(pendingIntent(reminderId, PendingIntent.FLAG_UPDATE_CURRENT))
    }

    private fun pendingIntent(reminderId: Long, flags: Int): PendingIntent {
        val intent = Intent(ReminderReceiver.ACTION_REMIND_WEIGH_IN)
            .setPackage(context.packageName)
            .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
        return PendingIntent.getBroadcast(
            context,
            (reminderId xor (reminderId ushr 32)).toInt(),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
