package app.okoklibre

import android.app.Application
import app.okoklibre.data.DatabaseProvider
import app.okoklibre.data.ReminderRepository
import app.okoklibre.data.WeightRepository
import app.okoklibre.reminders.ReminderReceiver
import app.okoklibre.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OkokLibreApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Create the reminder channel, re-arm reminders (the BootReceiver covers reboots)
        // and clear readings whose undo window closed with the last session.
        val db = DatabaseProvider.get(this)
        ReminderReceiver.ensureChannel(this)
        appScope.launch {
            val reminders = ReminderRepository.create(db).enabledReminders()
            ReminderScheduler(this@OkokLibreApp).scheduleAll(reminders)
            WeightRepository.create(db).purgeDeleted()
        }
    }
}
