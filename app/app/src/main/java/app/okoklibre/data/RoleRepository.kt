package app.okoklibre.data

import app.okoklibre.algo.Sex
import kotlinx.coroutines.flow.Flow

/** CRUD over household roles (main profile + family members). */
class RoleRepository(
    private val roleDao: RoleDao,
    private val recordDao: WeightRecordDao,
) {

    val roles: Flow<List<RoleEntity>> get() = roleDao.observeAll()

    val mainRole: Flow<RoleEntity?> get() = roleDao.observeMainRole()

    suspend fun allRoles(): List<RoleEntity> = roleDao.all()

    suspend fun role(id: Long): RoleEntity? = roleDao.byId(id)

    suspend fun mainRoleNow(): RoleEntity? = roleDao.mainRole()

    suspend fun roleCount(): Int = roleDao.count()

    /** Creates a profile with the next unused colour. */
    suspend fun createRole(
        nickname: String,
        sex: Sex,
        birthdayEpochDay: Long,
        heightCm: Int,
        roleType: RoleType,
        weightGoalKg: Double? = null,
        avatarPath: String? = null,
        createdAtEpochSec: Long = System.currentTimeMillis() / 1000,
    ): Long = roleDao.insert(
        RoleEntity(
            colorIndex = nextColorIndex(),
            nickname = nickname,
            sex = sex,
            birthdayEpochDay = birthdayEpochDay,
            heightCm = heightCm,
            weightGoalKg = weightGoalKg,
            avatarPath = avatarPath,
            roleType = roleType,
            createdAtEpochSec = createdAtEpochSec,
        ),
    )

    suspend fun updateRole(role: RoleEntity) = roleDao.update(role)

    /** Deletes the role and, via FK cascade, its records, aggregates, goals and reminders. */
    suspend fun deleteRole(role: RoleEntity) = roleDao.deleteAndPromote(role)

    private suspend fun nextColorIndex(): Int {
        val used = roleDao.all().map { it.colorIndex }.toSet()
        return generateSequence(0) { it + 1 }.first { it !in used }
    }

    /** Who a locked [weightKg] belongs to, given who the app is showing right now. */
    suspend fun attribute(weightKg: Double, activeRoleId: Long?): Attribution =
        attributeReading(
            weightKg = weightKg,
            roles = roleDao.all(),
            lastWeightByRole = recordDao.latestPerRole().associate { it.roleId to it.weightKg },
            activeRoleId = activeRoleId,
        )

    companion object {
        fun create(db: AppDatabase): RoleRepository = RoleRepository(db.roleDao(), db.weightRecordDao())
    }
}
