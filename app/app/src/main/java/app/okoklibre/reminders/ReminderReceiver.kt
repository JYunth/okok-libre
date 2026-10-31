package app.okoklibre.reminders

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.okoklibre.R
import app.okoklibre.data.DatabaseProvider
import app.okoklibre.data.RoleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires the "Time to weigh in" notification when a reminder alarm goes off,
 * then re-arms the reminder's next weekly occurrence.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND_WEIGH_IN) return
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = DatabaseProvider.get(context)
                val reminder = db.reminderDao().byId(reminderId)
                if (reminder != null) {
                    val role = reminder.roleId?.let { db.roleDao().byId(it) }
                    postNotification(context, reminderId, role)
                    if (reminder.enabled) ReminderScheduler(context).schedule(reminder)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun postNotification(context: Context, reminderId: Long, role: RoleEntity?) {
        ensureChannel(context)
        // Opening from the notification shows the person it was for, not whoever was last active.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_ROLE_ID, role?.id ?: -1L)
        val requestCode = (reminderId xor (reminderId ushr 32)).toInt()
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, requestCode, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val title = role?.let { context.getString(R.string.reminder_title_for, it.nickname) }
            ?: context.getString(R.string.reminder_title)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.reminder_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
        try {
            @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is requested at runtime by the UI.
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BASE + requestCode, notification)
        } catch (e: SecurityException) {
            // Notification permission revoked; the alarm chain still re-arms.
        }
    }

    companion object {
        const val ACTION_REMIND_WEIGH_IN = "app.okoklibre.REMIND_WEIGH_IN"
        const val EXTRA_REMINDER_ID = "app.okoklibre.extra.REMINDER_ID"
        const val EXTRA_ROLE_ID = "app.okoklibre.extra.ROLE_ID"
        const val CHANNEL_ID = "weigh_in_reminders"
        const val NOTIFICATION_ID_BASE = 4200

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.reminder_channel),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
        }
    }
}
