package app.okoklibre.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.okoklibre.algo.Grading
import app.okoklibre.algo.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class LengthUnit { CM, IMPERIAL }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The preferences a backup carries. Enum values are stored by name, as in DataStore. */
@kotlinx.serialization.Serializable
data class SettingsSnapshot(
    val weightUnit: String? = null,
    val lengthUnit: String? = null,
    val bmiRegion: String? = null,
    val themeMode: String? = null,
    val activeRoleId: Long? = null,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "okoklibre_settings",
)

/** App settings backed by DataStore preferences. All getters are Flows, all setters suspend. */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {

    /** Display weight unit. ST maps to [WeightUnit.ST_LB]. */
    val weightUnit: Flow<WeightUnit> = dataStore.value(KEY_WEIGHT_UNIT, WeightUnit.KG)

    val lengthUnit: Flow<LengthUnit> = dataStore.value(KEY_LENGTH_UNIT, LengthUnit.CM)

    /** BMI standard region for grading tables. */
    val bmiRegion: Flow<Grading.Region> = dataStore.value(KEY_BMI_REGION, Grading.Region.OTHER)

    val themeMode: Flow<ThemeMode> = dataStore.value(KEY_THEME_MODE, ThemeMode.SYSTEM)

    val onboardingDone: Flow<Boolean> = dataStore.value(KEY_ONBOARDING_DONE, false)

    /** The profile the whole app is showing; see [ProfileSession]. */
    val activeRoleId: Flow<Long?> = dataStore.data.map { prefs -> prefs[KEY_ACTIVE_ROLE] }

    suspend fun setWeightUnit(unit: WeightUnit) = dataStore.put(KEY_WEIGHT_UNIT, unit.name)

    suspend fun setLengthUnit(unit: LengthUnit) = dataStore.put(KEY_LENGTH_UNIT, unit.name)

    suspend fun setBmiRegion(region: Grading.Region) = dataStore.put(KEY_BMI_REGION, region.name)

    suspend fun setThemeMode(mode: ThemeMode) = dataStore.put(KEY_THEME_MODE, mode.name)

    suspend fun setOnboardingDone(done: Boolean) = dataStore.put(KEY_ONBOARDING_DONE, done)

    suspend fun setActiveRoleId(id: Long) = dataStore.put(KEY_ACTIVE_ROLE, id)

    suspend fun snapshot(): SettingsSnapshot {
        val prefs = dataStore.data.first()
        return SettingsSnapshot(
            weightUnit = prefs[KEY_WEIGHT_UNIT],
            lengthUnit = prefs[KEY_LENGTH_UNIT],
            bmiRegion = prefs[KEY_BMI_REGION],
            themeMode = prefs[KEY_THEME_MODE],
            activeRoleId = prefs[KEY_ACTIVE_ROLE],
        )
    }

    suspend fun restore(snapshot: SettingsSnapshot) {
        dataStore.edit { prefs ->
            snapshot.weightUnit?.let { prefs[KEY_WEIGHT_UNIT] = it }
            snapshot.lengthUnit?.let { prefs[KEY_LENGTH_UNIT] = it }
            snapshot.bmiRegion?.let { prefs[KEY_BMI_REGION] = it }
            snapshot.themeMode?.let { prefs[KEY_THEME_MODE] = it }
            snapshot.activeRoleId?.let { prefs[KEY_ACTIVE_ROLE] = it }
            prefs[KEY_ONBOARDING_DONE] = true
        }
    }

    private fun <T> DataStore<Preferences>.value(
        key: Preferences.Key<String>,
        default: T,
    ): Flow<T> where T : Enum<T> = data.map { prefs ->
        prefs[key]
            ?.let { raw -> runCatching { enumValueOf<T>(default, raw) }.getOrNull() }
            ?: default
    }

    private fun DataStore<Preferences>.value(
        key: Preferences.Key<Boolean>,
        default: Boolean,
    ): Flow<Boolean> = data.map { prefs -> prefs[key] ?: default }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Enum<T>> enumValueOf(default: T, name: String): T =
        java.lang.Enum.valueOf(default.declaringJavaClass as Class<T>, name)

    private suspend fun <T> DataStore<Preferences>.put(key: Preferences.Key<T>, value: T) {
        edit { it[key] = value }
    }

    companion object {
        private val KEY_WEIGHT_UNIT = stringPreferencesKey("weight_unit")
        private val KEY_LENGTH_UNIT = stringPreferencesKey("length_unit")
        private val KEY_BMI_REGION = stringPreferencesKey("bmi_region")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_ACTIVE_ROLE = longPreferencesKey("active_role_id")

        fun create(context: Context): SettingsRepository = SettingsRepository(context.settingsDataStore)
    }
}
