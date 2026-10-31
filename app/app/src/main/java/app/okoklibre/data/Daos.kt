package app.okoklibre.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RoleDao {
    @Insert
    suspend fun insert(role: RoleEntity): Long

    /** Never REPLACE here: it deletes the row first, and ON DELETE CASCADE takes the history with it. */
    @Upsert
    suspend fun upsert(role: RoleEntity)

    @Update
    suspend fun update(role: RoleEntity)

    @Delete
    suspend fun delete(role: RoleEntity)

    @Query("SELECT * FROM roles ORDER BY roleType = 'MAIN' DESC, id ASC")
    fun observeAll(): Flow<List<RoleEntity>>

    @Query("SELECT * FROM roles ORDER BY roleType = 'MAIN' DESC, id ASC")
    suspend fun all(): List<RoleEntity>

    @Query("SELECT * FROM roles WHERE id = :id")
    suspend fun byId(id: Long): RoleEntity?

    @Query("SELECT * FROM roles WHERE roleType = 'MAIN' LIMIT 1")
    suspend fun mainRole(): RoleEntity?

    @Query("SELECT * FROM roles WHERE roleType = 'MAIN' LIMIT 1")
    fun observeMainRole(): Flow<RoleEntity?>

    @Query("SELECT COUNT(*) FROM roles")
    suspend fun count(): Int

    @Query("UPDATE roles SET roleType = 'MAIN' WHERE id = (SELECT id FROM roles ORDER BY id ASC LIMIT 1)")
    suspend fun promoteOldestToMain()

    /** Deletes a profile; if it was the main one, the oldest remaining profile takes over. */
    @Transaction
    suspend fun deleteAndPromote(role: RoleEntity) {
        delete(role)
        if (mainRole() == null) promoteOldestToMain()
    }

    @Query("DELETE FROM roles")
    suspend fun clear()
}

@Dao
interface WeightRecordDao {
    @Insert
    suspend fun insert(record: WeightRecordEntity): Long

    @Update
    suspend fun update(record: WeightRecordEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM weight_records WHERE timestampEpochSec = :at AND weightKg = :weightKg)")
    suspend fun exists(at: Long, weightKg: Double): Boolean

    /** Any row at this exact instant, soft-deleted or not (the pair is unique). */
    @Query("SELECT * FROM weight_records WHERE roleId = :roleId AND timestampEpochSec = :timestampEpochSec")
    suspend fun atInstant(roleId: Long, timestampEpochSec: Long): WeightRecordEntity?

    @Query("SELECT * FROM weight_records WHERE id = :id")
    suspend fun byId(id: Long): WeightRecordEntity?

    @Query(
        "SELECT * FROM weight_records WHERE roleId = :roleId AND deleted = 0 " +
            "ORDER BY timestampEpochSec DESC LIMIT 1",
    )
    suspend fun latest(roleId: Long): WeightRecordEntity?

    @Query(
        "SELECT * FROM weight_records WHERE roleId = :roleId AND deleted = 0 " +
            "ORDER BY timestampEpochSec DESC LIMIT 1",
    )
    fun observeLatest(roleId: Long): Flow<WeightRecordEntity?>

    /** Latest non-deleted record of every role (role-attribution input). */
    @Query(
        "SELECT w.* FROM weight_records w INNER JOIN (" +
            "SELECT roleId, MAX(timestampEpochSec) AS mt FROM weight_records " +
            "WHERE deleted = 0 GROUP BY roleId" +
            ") m ON w.roleId = m.roleId AND w.timestampEpochSec = m.mt",
    )
    suspend fun latestPerRole(): List<WeightRecordEntity>

    @Query(
        "SELECT * FROM weight_records WHERE roleId = :roleId AND deleted = 0 " +
            "ORDER BY timestampEpochSec DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun page(roleId: Long, limit: Int, offset: Int): List<WeightRecordEntity>

    @Query(
        "SELECT * FROM weight_records WHERE roleId = :roleId AND deleted = 0 " +
            "ORDER BY timestampEpochSec DESC",
    )
    suspend fun all(roleId: Long): List<WeightRecordEntity>

    @Query(
        "SELECT * FROM weight_records WHERE roleId = :roleId AND deleted = 0 " +
            "ORDER BY timestampEpochSec DESC",
    )
    fun observeAll(roleId: Long): Flow<List<WeightRecordEntity>>

    /** Records with startEpochSec <= timestamp < endEpochSec, ascending. */
    @Query(
        "SELECT * FROM weight_records WHERE roleId = :roleId AND deleted = 0 " +
            "AND timestampEpochSec >= :startEpochSec AND timestampEpochSec < :endEpochSec " +
            "ORDER BY timestampEpochSec ASC",
    )
    suspend fun between(roleId: Long, startEpochSec: Long, endEpochSec: Long): List<WeightRecordEntity>

    @Query("UPDATE weight_records SET deleted = 1 WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<Long>)

    @Query("UPDATE weight_records SET deleted = 0 WHERE id IN (:ids)")
    suspend fun restore(ids: List<Long>)

    @Query("DELETE FROM weight_records WHERE deleted = 1")
    suspend fun purgeDeleted()

    @Query("SELECT * FROM weight_records WHERE deleted = 0 ORDER BY roleId, timestampEpochSec")
    suspend fun allLive(): List<WeightRecordEntity>

    @Query("SELECT * FROM weight_records WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<WeightRecordEntity>

    @Query("DELETE FROM weight_records WHERE id IN (:ids)")
    suspend fun hardDelete(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM weight_records WHERE roleId = :roleId AND deleted = 0")
    suspend fun count(roleId: Long): Int
}

@Dao
interface DayAggregateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(aggregate: DayAggregateEntity)

    @Query("DELETE FROM day_aggregates WHERE roleId = :roleId AND dateEpochDay = :dateEpochDay")
    suspend fun delete(roleId: Long, dateEpochDay: Long)

    @Query("DELETE FROM day_aggregates")
    suspend fun clear()

    @Query("SELECT * FROM day_aggregates WHERE roleId = :roleId AND dateEpochDay = :dateEpochDay")
    suspend fun byDay(roleId: Long, dateEpochDay: Long): DayAggregateEntity?

    /** Aggregates with startEpochDay <= day <= endEpochDay, ascending. */
    @Query(
        "SELECT * FROM day_aggregates WHERE roleId = :roleId " +
            "AND dateEpochDay >= :startEpochDay AND dateEpochDay <= :endEpochDay " +
            "ORDER BY dateEpochDay ASC",
    )
    suspend fun between(roleId: Long, startEpochDay: Long, endEpochDay: Long): List<DayAggregateEntity>

    @Query(
        "SELECT * FROM day_aggregates WHERE roleId = :roleId " +
            "AND dateEpochDay >= :startEpochDay AND dateEpochDay <= :endEpochDay " +
            "ORDER BY dateEpochDay ASC",
    )
    fun observeBetween(roleId: Long, startEpochDay: Long, endEpochDay: Long): Flow<List<DayAggregateEntity>>
}

@Dao
interface ScaleDeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: ScaleDeviceEntity)

    @Query("SELECT * FROM scale_devices ORDER BY lastSeenEpochSec DESC")
    fun observeAll(): Flow<List<ScaleDeviceEntity>>

    @Query("SELECT * FROM scale_devices ORDER BY lastSeenEpochSec DESC")
    suspend fun all(): List<ScaleDeviceEntity>

    @Query("SELECT * FROM scale_devices WHERE mac = :mac")
    suspend fun byMac(mac: String): ScaleDeviceEntity?

    @Query("UPDATE scale_devices SET lastSeenEpochSec = :epochSec WHERE mac = :mac")
    suspend fun touch(mac: String, epochSec: Long)

    @Query("UPDATE scale_devices SET typeId = :typeId WHERE mac = :mac")
    suspend fun updateTypeId(mac: String, typeId: Int)

    @Query("DELETE FROM scale_devices WHERE mac = :mac")
    suspend fun delete(mac: String)

    @Query("DELETE FROM scale_devices")
    suspend fun clear()
}

@Dao
interface TmpWeightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tmp: TmpWeightEntity): Long

    @Query("SELECT * FROM tmp_weights ORDER BY timestampEpochSec DESC")
    fun observeAll(): Flow<List<TmpWeightEntity>>

    @Query("SELECT * FROM tmp_weights ORDER BY timestampEpochSec DESC")
    suspend fun all(): List<TmpWeightEntity>

    @Query("SELECT * FROM tmp_weights WHERE id = :id")
    suspend fun byId(id: Long): TmpWeightEntity?

    /** A scale can resend its history; the same reading must not queue twice. */
    @Query(
        "SELECT * FROM tmp_weights WHERE mac = :mac AND timestampEpochSec = :timestampEpochSec " +
            "AND weightKg = :weightKg LIMIT 1",
    )
    suspend fun find(mac: String, timestampEpochSec: Long, weightKg: Double): TmpWeightEntity?

    @Query("DELETE FROM tmp_weights WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tmp_weights")
    suspend fun clear()
}

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun byId(id: Long): GoalEntity?

    @Query("SELECT * FROM goals ORDER BY id")
    suspend fun all(): List<GoalEntity>

    @Query("SELECT * FROM goals WHERE roleId = :roleId AND active = 1 ORDER BY id DESC LIMIT 1")
    suspend fun active(roleId: Long): GoalEntity?

    @Query("SELECT * FROM goals WHERE roleId = :roleId AND active = 1 ORDER BY id DESC LIMIT 1")
    fun observeActive(roleId: Long): Flow<GoalEntity?>

    @Query("SELECT * FROM goals WHERE roleId = :roleId ORDER BY id DESC")
    fun observeHistory(roleId: Long): Flow<List<GoalEntity>>

    @Query("UPDATE goals SET active = 0 WHERE roleId = :roleId AND active = 1")
    suspend fun deactivateAll(roleId: Long)

    @Query("DELETE FROM goals")
    suspend fun clear()
}

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity): Long

    @Delete
    suspend fun delete(reminder: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders ORDER BY timeMinutesOfDay ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders ORDER BY timeMinutesOfDay ASC")
    suspend fun all(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE enabled = 1")
    suspend fun enabled(): List<ReminderEntity>

    /** A reminder with no owner is listed for everyone, so it can always be switched off. */
    @Query("SELECT * FROM reminders WHERE roleId = :roleId OR roleId IS NULL ORDER BY timeMinutesOfDay ASC")
    fun observeFor(roleId: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE roleId = :roleId")
    suspend fun forRole(roleId: Long): List<ReminderEntity>

    @Query("DELETE FROM reminders")
    suspend fun clear()
}

@Dao
interface WeightStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(status: WeightStatusEntity)

    @Query("SELECT * FROM weight_status WHERE weightRecordId = :weightRecordId")
    suspend fun byRecordId(weightRecordId: Long): WeightStatusEntity?

    @Query("SELECT * FROM weight_status WHERE weightRecordId = :weightRecordId")
    fun observeByRecordId(weightRecordId: Long): Flow<WeightStatusEntity?>

    @Query("DELETE FROM weight_status WHERE weightRecordId = :weightRecordId")
    suspend fun delete(weightRecordId: Long)

    @Query("SELECT * FROM weight_status")
    suspend fun all(): List<WeightStatusEntity>

    @Query(
        "SELECT s.* FROM weight_status s INNER JOIN weight_records r ON r.id = s.weightRecordId " +
            "WHERE r.roleId = :roleId AND r.deleted = 0",
    )
    fun observeForRole(roleId: Long): Flow<List<WeightStatusEntity>>
}
