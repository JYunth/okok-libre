package app.okoklibre.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the app knows about who it is showing. [Loading] only exists before the first read. */
sealed interface ActiveProfile {
    data object Loading : ActiveProfile
    data object None : ActiveProfile
    data class Selected(val role: RoleEntity) : ActiveProfile
}

/** The persisted choice if it still exists, else the main profile, else anyone. */
fun resolveActiveRole(roles: List<RoleEntity>, persistedId: Long?): RoleEntity? =
    roles.firstOrNull { it.id == persistedId }
        ?: roles.firstOrNull { it.roleType == RoleType.MAIN }
        ?: roles.firstOrNull()

/**
 * The single answer to "whose data is on screen". Every screen reads this instead
 * of keeping its own selection, so a switch applies everywhere and survives restarts.
 */
class ProfileSession(
    private val settings: SettingsRepository,
    roleRepository: RoleRepository,
    scope: CoroutineScope,
) {
    val roles: StateFlow<List<RoleEntity>> = roleRepository.roles
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val active: StateFlow<ActiveProfile> = combine(roleRepository.roles, settings.activeRoleId) { list, id ->
        resolveActiveRole(list, id)?.let(ActiveProfile::Selected) ?: ActiveProfile.None
    }.stateIn(scope, SharingStarted.Eagerly, ActiveProfile.Loading)

    /** The active profile, or null while loading or when the household is empty. */
    val activeRole: Flow<RoleEntity?> = active.map { (it as? ActiveProfile.Selected)?.role }

    suspend fun activeRoleNow(): RoleEntity? = activeRole.first { active.value != ActiveProfile.Loading }

    suspend fun switchTo(roleId: Long) = settings.setActiveRoleId(roleId)

    /** A flow of [block] for the active profile, restarted on every switch. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun <T> perProfile(empty: T, block: (RoleEntity) -> Flow<T>): Flow<T> =
        activeRole.distinctUntilChanged().flatMapLatest { role -> role?.let(block) ?: flowOf(empty) }
}
