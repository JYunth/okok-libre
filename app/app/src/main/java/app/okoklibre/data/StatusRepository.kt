package app.okoklibre.data

import kotlinx.coroutines.flow.Flow

/** Per-record wellbeing tags (mood, exercise intensity, ...). */
class StatusRepository(
    private val statusDao: WeightStatusDao,
) {

    suspend fun statusFor(weightRecordId: Long): WeightStatusEntity? = statusDao.byRecordId(weightRecordId)

    fun statusFlow(weightRecordId: Long): Flow<WeightStatusEntity?> = statusDao.observeByRecordId(weightRecordId)

    fun statusesFor(roleId: Long): Flow<List<WeightStatusEntity>> = statusDao.observeForRole(roleId)

    suspend fun save(status: WeightStatusEntity) = statusDao.upsert(status)

    suspend fun deleteFor(weightRecordId: Long) = statusDao.delete(weightRecordId)

    companion object {
        /** Exercise-intensity codes (original app). */
        const val EXERCISE_EASY = 0
        const val EXERCISE_SOFT = 1
        const val EXERCISE_FIERCE = 2

        fun create(db: AppDatabase): StatusRepository = StatusRepository(db.weightStatusDao())
    }
}
