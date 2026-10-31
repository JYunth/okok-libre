package app.okoklibre.data

import kotlinx.coroutines.flow.Flow

/** Progress of an active weight goal against the latest recorded weight. */
data class GoalProgress(
    val goal: GoalEntity,
    /** Null when the role has no records yet. */
    val currentWeightKg: Double?,
    /** 0..100; 100 once the target is reached (in either direction). */
    val progressPct: Double,
    /** Signed distance to target in kg (negative = overshot, positive = to go). */
    val remainingKg: Double,
    val achieved: Boolean,
)

/** Pure goal-progress math, separated for JVM tests. */
fun computeGoalProgress(goal: GoalEntity, currentWeightKg: Double?): GoalProgress {
    if (currentWeightKg == null) {
        return GoalProgress(goal, null, 0.0, goal.targetWeightKg - goal.initWeightKg, false)
    }
    val total = goal.initWeightKg - goal.targetWeightKg
    val achieved = if (goal.targetWeightKg <= goal.initWeightKg) {
        currentWeightKg <= goal.targetWeightKg
    } else {
        currentWeightKg >= goal.targetWeightKg
    }
    val pct = when {
        achieved -> 100.0
        total == 0.0 -> 0.0
        else -> ((goal.initWeightKg - currentWeightKg) / total * 100.0).coerceIn(0.0, 100.0)
    }
    return GoalProgress(
        goal = goal,
        currentWeightKg = currentWeightKg,
        progressPct = pct,
        remainingKg = goal.targetWeightKg - currentWeightKg,
        achieved = achieved,
    )
}

class GoalRepository(
    private val goalDao: GoalDao,
    private val recordDao: WeightRecordDao,
) {

    fun activeGoal(roleId: Long): Flow<GoalEntity?> = goalDao.observeActive(roleId)

    suspend fun activeGoalNow(roleId: Long): GoalEntity? = goalDao.active(roleId)

    /** All goals of a role, newest first (goal history screen). */
    fun history(roleId: Long): Flow<List<GoalEntity>> = goalDao.observeHistory(roleId)

    /** Starts a new goal; any previously active goal of the role is deactivated. */
    suspend fun startGoal(
        roleId: Long,
        initWeightKg: Double,
        targetWeightKg: Double,
        startEpochDay: Long,
    ): Long {
        goalDao.deactivateAll(roleId)
        return goalDao.insert(
            GoalEntity(
                roleId = roleId,
                initWeightKg = initWeightKg,
                targetWeightKg = targetWeightKg,
                startEpochDay = startEpochDay,
            ),
        )
    }

    /** Marks the goal finished (reached or abandoned) with the closing weight. */
    suspend fun completeGoal(goalId: Long, endWeightKg: Double, endEpochDay: Long) {
        val goal = goalDao.byId(goalId) ?: return
        goalDao.update(
            goal.copy(active = false, endWeightKg = endWeightKg, endEpochDay = endEpochDay),
        )
    }

    /** Abandons the goal without recording an end state. */
    suspend fun cancelGoal(goalId: Long) {
        val goal = goalDao.byId(goalId) ?: return
        goalDao.update(goal.copy(active = false))
    }

    /** Progress of the role's active goal against their latest weight; null without an active goal. */
    suspend fun progress(roleId: Long): GoalProgress? {
        val goal = goalDao.active(roleId) ?: return null
        return computeGoalProgress(goal, recordDao.latest(roleId)?.weightKg)
    }

    companion object {
        fun create(db: AppDatabase): GoalRepository = GoalRepository(db.goalDao(), db.weightRecordDao())
    }
}
