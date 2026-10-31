package app.okoklibre.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.okoklibre.algo.Sex
import app.okoklibre.ble.protocol.ScaleProtocolType
import kotlinx.serialization.Serializable

enum class RoleType { MAIN, MEMBER }

/** How a record came in: a live weigh-in or a claim out of the scale's offline history. */
enum class RecordSource { LIVE, OFFLINE_HISTORY }

/** A household user profile. */
@Serializable
@Entity(tableName = "roles")
data class RoleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nickname: String,
    /** Stored as the enum name ("MALE"/"FEMALE"). */
    val sex: Sex,
    /** Birthday as [java.time.LocalDate.toEpochDay]. */
    val birthdayEpochDay: Long,
    val heightCm: Int,
    val weightGoalKg: Double? = null,
    /** Local image path; avatars are never uploaded. */
    val avatarPath: String? = null,
    val roleType: RoleType,
    val createdAtEpochSec: Long,
    /** Index into the household palette. The whole app wears this colour while the person is active. */
    @ColumnInfo(defaultValue = "0") val colorIndex: Int = 0,
)

/**
 * A single weighing with the full computed body-composition snapshot.
 */
@Serializable
@Entity(
    tableName = "weight_records",
    foreignKeys = [
        ForeignKey(
            entity = RoleEntity::class,
            parentColumns = ["id"],
            childColumns = ["roleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["roleId", "timestampEpochSec"], unique = true),
    ],
)
data class WeightRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roleId: Long,
    val weightKg: Double,
    val timestampEpochSec: Long,
    val bmi: Double,
    val bodyFatPct: Double,
    val boneKg: Double,
    val musclePct: Double,
    val waterPct: Double,
    val bmrKcal: Double,
    val bodyAge: Int,
    val visceralFat: Double,
    val score: Int,
    val idealWeightKg: Double,
    val heightCm: Double,
    val sex: Sex,
    val ageYears: Int,
    /** Impedance actually used (after gating/smoothing); 0 = no impedance metrics. */
    val impedanceOhm: Double,
    /** The scale property byte as received on the wire (0 for manual entries). */
    val scaleProperty: Int,
    val scaleWeightString: String? = null,
    val productId: Long? = null,
    /** May contain "2:<bpm>" for heart rate, as the scale reports it. */
    val remark: String? = null,
    val heartRateBpm: Int? = null,
    /** Hex of the raw locked/history frame that produced this record, when known. */
    val rawFrameHex: String? = null,
    /** Where the record came from (live weigh-in vs. claimed offline history). */
    @ColumnInfo(defaultValue = "LIVE") val source: RecordSource = RecordSource.LIVE,
    val deleted: Boolean = false,
)

/**
 * Per-role, per-day arithmetic means of the day's measurements.
 * A field of 0.0 means "no data" for that day.
 */
@Serializable
@Entity(
    tableName = "day_aggregates",
    primaryKeys = ["roleId", "dateEpochDay"],
    foreignKeys = [
        ForeignKey(
            entity = RoleEntity::class,
            parentColumns = ["id"],
            childColumns = ["roleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DayAggregateEntity(
    val roleId: Long,
    /** The day as [java.time.LocalDate.toEpochDay]. */
    val dateEpochDay: Long,
    val weightKg: Double,
    val bmi: Double,
    val bodyFatPct: Double,
    val boneKg: Double,
    val musclePct: Double,
    val waterPct: Double,
    val bmrKcal: Double,
    val bodyAge: Double,
    val visceralFat: Double,
)

/** A paired scale. */
@Serializable
@Entity(tableName = "scale_devices")
data class ScaleDeviceEntity(
    @PrimaryKey val mac: String,
    val name: String,
    /** 0 = weigher (weight only), 1 = fat scale, 2 = impedance-only. */
    val typeId: Int,
    val productId: Long? = null,
    val protocolVersion: Int,
    val protocolType: ScaleProtocolType,
    val lastSeenEpochSec: Long,
)

/**
 * Offline (history) records pulled from a scale, waiting to be claimed by a
 * role. Classic 0x13 records carry the full
 * scale-computed composition block, kept here so a
 * claimed record produces a full report via the scale-computed path.
 */
@Serializable
@Entity(tableName = "tmp_weights")
data class TmpWeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val weightKg: Double,
    val timestampEpochSec: Long,
    val impedanceOhm: Double = 0.0,
    val scaleProperty: Int = 0,
    val scaleWeightString: String? = null,
    val productId: Long? = null,
    @ColumnInfo(defaultValue = "0.0") val fatPct: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val waterPct: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val musclePct: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val bmrKcal: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val visceralFat: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val boneKg: Double = 0.0,
    /** Heart rate from CloudV4 history frames (same "2:<bpm>" form). */
    val heartRateBpm: Int? = null,
    /** Scale-side role id from classic 0x13 records (BE at record offset 6), when known. */
    val scaleRoleId: Int? = null,
    /** Hex of the raw reassembled 0x13 record, when known. */
    val rawFrameHex: String? = null,
)

/** A weight goal. */
@Serializable
@Entity(
    tableName = "goals",
    foreignKeys = [
        ForeignKey(
            entity = RoleEntity::class,
            parentColumns = ["id"],
            childColumns = ["roleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("roleId")],
)
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roleId: Long,
    val initWeightKg: Double,
    val targetWeightKg: Double,
    /** Start day as [java.time.LocalDate.toEpochDay]. */
    val startEpochDay: Long,
    val endEpochDay: Long? = null,
    val endWeightKg: Double? = null,
    val active: Boolean = true,
)

/** A weekly-repeat weigh-in reminder, owned by one profile. */
@Serializable
@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = RoleEntity::class,
            parentColumns = ["id"],
            childColumns = ["roleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("roleId")],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Who the reminder nudges; null only for a row restored without an owner. */
    val roleId: Long? = null,
    /** Minutes after midnight, 0..1439. */
    val timeMinutesOfDay: Int,
    val enabled: Boolean = true,
    val mon: Boolean = false,
    val tue: Boolean = false,
    val wed: Boolean = false,
    val thu: Boolean = false,
    val fri: Boolean = false,
    val sat: Boolean = false,
    val sun: Boolean = false,
) {
    /** Enabled weekdays as a set of ISO [java.time.DayOfWeek]. */
    fun daysOfWeek(): Set<java.time.DayOfWeek> = buildSet {
        if (mon) add(java.time.DayOfWeek.MONDAY)
        if (tue) add(java.time.DayOfWeek.TUESDAY)
        if (wed) add(java.time.DayOfWeek.WEDNESDAY)
        if (thu) add(java.time.DayOfWeek.THURSDAY)
        if (fri) add(java.time.DayOfWeek.FRIDAY)
        if (sat) add(java.time.DayOfWeek.SATURDAY)
        if (sun) add(java.time.DayOfWeek.SUNDAY)
    }
}

/**
 * Per-record wellbeing tags attached from the history UI.
 */
@Serializable
@Entity(
    tableName = "weight_status",
    foreignKeys = [
        ForeignKey(
            entity = WeightRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["weightRecordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WeightStatusEntity(
    @PrimaryKey val weightRecordId: Long,
    /** Mood code as in the original (1..5). */
    val mood: Int = 0,
    /** 0 = easy, 1 = soft, 2 = fierce. */
    val exerciseIntensity: Int = 0,
    val defecationNum: Int = 0,
    val specialCase: String? = null,
    val remark: String? = null,
)
