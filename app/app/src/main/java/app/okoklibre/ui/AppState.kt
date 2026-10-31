package app.okoklibre.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.okoklibre.BuildHooks
import app.okoklibre.ble.MeasurementCoordinator
import app.okoklibre.ble.ScaleManager
import app.okoklibre.ble.WeighingSource
import app.okoklibre.data.AppDatabase
import app.okoklibre.data.AvatarStore
import app.okoklibre.data.BackupRepository
import app.okoklibre.data.DatabaseProvider
import app.okoklibre.data.DeviceRepository
import app.okoklibre.data.RoleEntity
import app.okoklibre.data.GoalRepository
import app.okoklibre.data.ProfileSession
import app.okoklibre.data.ReminderRepository
import app.okoklibre.data.RoleRepository
import app.okoklibre.data.SettingsRepository
import app.okoklibre.data.StatusRepository
import app.okoklibre.data.TmpWeightRepository
import app.okoklibre.data.WeightRepository
import app.okoklibre.insights.BodyData
import app.okoklibre.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide holder for everything the UI layer needs: repositories,
 * settings, BLE scale manager/coordinator and the reminder scheduler.
 *
 * Access from composables via [LocalAppState], or from non-compose code via
 * [AppState.get]. Part 2 screens should take dependencies from here instead
 * of constructing their own.
 */
class AppState private constructor(
    val database: AppDatabase,
    val settingsRepository: SettingsRepository,
    val roleRepository: RoleRepository,
    val weightRepository: WeightRepository,
    val deviceRepository: DeviceRepository,
    val goalRepository: GoalRepository,
    val reminderRepository: ReminderRepository,
    val tmpWeightRepository: TmpWeightRepository,
    val statusRepository: StatusRepository,
    val scaleManager: ScaleManager,
    val weighingSource: WeighingSource,
    val reminderScheduler: ReminderScheduler,
    val profileSession: ProfileSession,
    val avatarStore: AvatarStore,
    val backupRepository: BackupRepository,
    val bodyData: BodyData,
    /** Outlives any one screen, for work a screen starts but mustn't cut short. */
    val backgroundScope: CoroutineScope,
) {
    /** Removes a profile and everything hanging off it, including what lives outside the database. */
    suspend fun deleteProfile(role: RoleEntity) {
        reminderRepository.remindersOf(role.id).forEach { reminderScheduler.cancel(it.id) }
        avatarStore.delete(role)
        roleRepository.deleteRole(role)
    }

    companion object {
        @Volatile
        private var instance: AppState? = null

        fun get(context: Context): AppState =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): AppState {
            val db = DatabaseProvider.get(context)
            val scaleManager = ScaleManager.getInstance(context)
            val settingsRepository = SettingsRepository.create(context)
            val roleRepository = RoleRepository.create(db)
            val weightRepository = WeightRepository.create(db)
            val avatarStore = AvatarStore(context)
            val goalRepository = GoalRepository.create(db)
            val deviceRepository = DeviceRepository.create(db)
            // Persist device-type detection results per MAC.
            val detectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scaleManager.onDeviceTypeDetected = { mac, typeId ->
                detectionScope.launch { deviceRepository.updateTypeId(mac, typeId) }
            }
            val session = ProfileSession(settingsRepository, roleRepository, detectionScope)
            return AppState(
                database = db,
                settingsRepository = settingsRepository,
                roleRepository = roleRepository,
                weightRepository = weightRepository,
                deviceRepository = deviceRepository,
                goalRepository = goalRepository,
                reminderRepository = ReminderRepository.create(db),
                tmpWeightRepository = TmpWeightRepository.create(db),
                statusRepository = StatusRepository.create(db),
                scaleManager = scaleManager,
                weighingSource = BuildHooks.weighingSource(context, MeasurementCoordinator(scaleManager)),
                reminderScheduler = ReminderScheduler(context),
                profileSession = session,
                avatarStore = avatarStore,
                backupRepository = BackupRepository(db, settingsRepository, weightRepository, avatarStore),
                bodyData = BodyData(session, weightRepository, goalRepository, settingsRepository, detectionScope),
                backgroundScope = detectionScope,
            )
        }
    }
}

val LocalAppState = staticCompositionLocalOf<AppState> {
    error("LocalAppState not provided — wrap the root composable in CompositionLocalProvider")
}

/**
 * Creates (or retrieves) a [ViewModel] backed by [AppState] without a DI
 * framework. Usage: `val vm: HomeViewModel = appViewModel { HomeViewModel(it) }`.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    noinline create: (AppState) -> VM,
): VM {
    val appState = LocalAppState.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(appState) } })
}
