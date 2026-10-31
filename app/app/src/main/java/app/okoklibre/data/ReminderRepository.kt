package app.okoklibre.data

import kotlinx.coroutines.flow.Flow

/** Weekly weigh-in reminders; scheduling lives in app.okoklibre.reminders. */
class ReminderRepository(
    private val reminderDao: ReminderDao,
) {

    val reminders: Flow<List<ReminderEntity>> get() = reminderDao.observeAll()

    fun remindersFor(roleId: Long): Flow<List<ReminderEntity>> = reminderDao.observeFor(roleId)

    suspend fun remindersOf(roleId: Long): List<ReminderEntity> = reminderDao.forRole(roleId)

    suspend fun allReminders(): List<ReminderEntity> = reminderDao.all()

    suspend fun enabledReminders(): List<ReminderEntity> = reminderDao.enabled()

    suspend fun reminder(id: Long): ReminderEntity? = reminderDao.byId(id)

    suspend fun save(reminder: ReminderEntity): Long = reminderDao.upsert(reminder)

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val reminder = reminderDao.byId(id) ?: return
        reminderDao.upsert(reminder.copy(enabled = enabled))
    }

    suspend fun delete(reminder: ReminderEntity) = reminderDao.delete(reminder)

    companion object {
        fun create(db: AppDatabase): ReminderRepository = ReminderRepository(db.reminderDao())
    }
}
