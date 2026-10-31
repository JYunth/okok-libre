package app.okoklibre.data

import androidx.room.withTransaction
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/** Everything on the phone, as one portable file. Day aggregates are rebuilt on restore. */
@Serializable
data class Backup(
    val format: Int = FORMAT,
    val createdAtEpochSec: Long,
    val roles: List<RoleEntity>,
    val records: List<WeightRecordEntity>,
    val statuses: List<WeightStatusEntity>,
    val goals: List<GoalEntity>,
    val reminders: List<ReminderEntity>,
    val devices: List<ScaleDeviceEntity>,
    val pending: List<TmpWeightEntity>,
    /** Base64 JPEGs keyed by role id. */
    val avatars: Map<Long, String> = emptyMap(),
    val settings: SettingsSnapshot = SettingsSnapshot(),
) {
    companion object {
        const val FORMAT = 1
    }
}

/** A file we refuse to restore, and why, in words the person can act on. */
class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun write(backup: Backup, out: OutputStream) = json.encodeToStream(backup, out)

    /** Decodes and validates. Nothing is touched until this has succeeded. */
    @OptIn(ExperimentalSerializationApi::class)
    fun read(input: InputStream): Backup {
        val backup = try {
            json.decodeFromStream<Backup>(input)
        } catch (e: SerializationException) {
            throw InvalidBackupException("This isn't an OKOK Libre backup file.", e)
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException("This isn't an OKOK Libre backup file.", e)
        }
        validate(backup)
        return backup
    }

    fun validate(backup: Backup) {
        if (backup.format > Backup.FORMAT) {
            throw InvalidBackupException("This backup came from a newer version of OKOK Libre. Update the app first.")
        }
        if (backup.roles.isEmpty()) throw InvalidBackupException("This backup has no profiles in it.")
        val roleIds = backup.roles.map { it.id }.toSet()
        val recordIds = backup.records.map { it.id }.toSet()
        val orphaned = backup.records.any { it.roleId !in roleIds } ||
            backup.goals.any { it.roleId !in roleIds } ||
            backup.reminders.any { it.roleId != null && it.roleId !in roleIds } ||
            backup.statuses.any { it.weightRecordId !in recordIds }
        if (orphaned) throw InvalidBackupException("This backup is damaged: some data points at a missing profile.")
    }
}

class BackupRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val weights: WeightRepository,
    private val avatars: AvatarStore,
) {
    suspend fun snapshot(nowEpochSec: Long = System.currentTimeMillis() / 1000): Backup {
        val roles = db.roleDao().all()
        val records = db.weightRecordDao().allLive()
        val recordIds = records.mapTo(HashSet()) { it.id }
        return Backup(
            createdAtEpochSec = nowEpochSec,
            roles = roles,
            records = records,
            // Tags on a reading deleted this session go with it.
            statuses = db.weightStatusDao().all().filter { it.weightRecordId in recordIds },
            goals = db.goalDao().all(),
            reminders = db.reminderDao().all(),
            devices = db.scaleDeviceDao().all(),
            pending = db.tmpWeightDao().all(),
            avatars = roles.mapNotNull { role -> avatars.encoded(role)?.let { role.id to it } }.toMap(),
            settings = settings.snapshot(),
        )
    }

    /**
     * Replaces everything on the phone. All database writes share one transaction,
     * so a failure part-way leaves the existing data exactly as it was.
     */
    suspend fun restore(backup: Backup, zone: ZoneId = ZoneId.systemDefault()) {
        BackupCodec.validate(backup)
        db.withTransaction {
            db.roleDao().clear()
            db.dayAggregateDao().clear()
            db.reminderDao().clear()
            db.scaleDeviceDao().clear()
            db.tmpWeightDao().clear()

            backup.roles.forEach { db.roleDao().insert(it.copy(avatarPath = null)) }
            backup.records.forEach { db.weightRecordDao().insert(it) }
            backup.statuses.forEach { db.weightStatusDao().upsert(it) }
            backup.goals.forEach { db.goalDao().insert(it) }
            backup.reminders.forEach { db.reminderDao().upsert(it) }
            backup.devices.forEach { db.scaleDeviceDao().upsert(it) }
            backup.pending.forEach { db.tmpWeightDao().insert(it) }
            backup.records
                .map { it.roleId to WeightRepository.epochDayOf(it.timestampEpochSec, zone) }
                .distinct()
                .forEach { (roleId, day) -> weights.refreshDayAggregate(roleId, day, zone) }
        }
        backup.avatars.forEach { (roleId, encoded) ->
            val role = db.roleDao().byId(roleId) ?: return@forEach
            db.roleDao().update(role.copy(avatarPath = avatars.write(roleId, encoded)))
        }
        settings.restore(backup.settings)
    }
}
