package app.okoklibre.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RoleEntity::class,
        WeightRecordEntity::class,
        DayAggregateEntity::class,
        ScaleDeviceEntity::class,
        TmpWeightEntity::class,
        GoalEntity::class,
        ReminderEntity::class,
        WeightStatusEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun roleDao(): RoleDao
    abstract fun weightRecordDao(): WeightRecordDao
    abstract fun dayAggregateDao(): DayAggregateDao
    abstract fun scaleDeviceDao(): ScaleDeviceDao
    abstract fun tmpWeightDao(): TmpWeightDao
    abstract fun goalDao(): GoalDao
    abstract fun reminderDao(): ReminderDao
    abstract fun weightStatusDao(): WeightStatusDao

    companion object {
        /**
         * 1 → 2: capture-everything columns. `weight_records` gains the raw
         * frame hex; `tmp_weights` gains the classic 0x13 scale-computed
         * composition block plus the raw record hex.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE weight_records ADD COLUMN rawFrameHex TEXT")
                db.execSQL("ALTER TABLE tmp_weights ADD COLUMN fatPct REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE tmp_weights ADD COLUMN waterPct REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE tmp_weights ADD COLUMN musclePct REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE tmp_weights ADD COLUMN bmrKcal REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE tmp_weights ADD COLUMN visceralFat REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE tmp_weights ADD COLUMN boneKg REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE tmp_weights ADD COLUMN rawFrameHex TEXT")
            }
        }

        /**
         * 2 → 3: `tmp_weights` gains the CloudV4 heart rate and the classic
         * 0x13 scale-side role id; `weight_records` gains the source marker
         * (LIVE vs OFFLINE_HISTORY).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tmp_weights ADD COLUMN heartRateBpm INTEGER")
                db.execSQL("ALTER TABLE tmp_weights ADD COLUMN scaleRoleId INTEGER")
                db.execSQL("ALTER TABLE weight_records ADD COLUMN source TEXT NOT NULL DEFAULT 'LIVE'")
            }
        }

        /**
         * 3 → 4: every profile gets a fixed colour, and reminders gain an owner so
         * they are per-profile like everything else. Existing reminders go to the
         * main profile, the only person the old screens could have meant.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE roles ADD COLUMN colorIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE roles SET colorIndex = " +
                        "(SELECT COUNT(*) FROM roles AS earlier WHERE earlier.id < roles.id)",
                )
                db.execSQL(
                    "CREATE TABLE reminders_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "roleId INTEGER, " +
                        "timeMinutesOfDay INTEGER NOT NULL, " +
                        "enabled INTEGER NOT NULL, " +
                        "mon INTEGER NOT NULL, tue INTEGER NOT NULL, wed INTEGER NOT NULL, " +
                        "thu INTEGER NOT NULL, fri INTEGER NOT NULL, sat INTEGER NOT NULL, " +
                        "sun INTEGER NOT NULL, " +
                        "FOREIGN KEY(roleId) REFERENCES roles(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "INSERT INTO reminders_new " +
                        "SELECT id, (SELECT id FROM roles WHERE roleType = 'MAIN' LIMIT 1), " +
                        "timeMinutesOfDay, enabled, mon, tue, wed, thu, fri, sat, sun FROM reminders",
                )
                db.execSQL("DROP TABLE reminders")
                db.execSQL("ALTER TABLE reminders_new RENAME TO reminders")
                db.execSQL("CREATE INDEX index_reminders_roleId ON reminders(roleId)")
            }
        }
    }
}

/**
 * Process-wide holder for the database. The app is small and has no DI
 * framework; call [get] with any context (the application context is used).
 */
object DatabaseProvider {

    const val DATABASE_NAME = "okoklibre.db"

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: Room
                .databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
                .build()
                .also { instance = it }
        }

    /** Test hook: installs a prebuilt (e.g. in-memory) database. */
    fun setInstanceForTesting(database: AppDatabase?) {
        synchronized(this) { instance = database }
    }
}
