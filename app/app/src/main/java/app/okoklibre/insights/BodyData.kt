package app.okoklibre.insights

import app.okoklibre.algo.Grading
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.ActiveProfile
import app.okoklibre.data.GoalEntity
import app.okoklibre.data.GoalRepository
import app.okoklibre.data.ProfileSession
import app.okoklibre.data.RoleEntity
import app.okoklibre.data.SettingsRepository
import app.okoklibre.data.WeightRecordEntity
import app.okoklibre.data.WeightRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Everything the app knows about the person on screen, always consistent with itself. */
data class BodyState(
    val role: RoleEntity?,
    /** Newest first. */
    val records: List<WeightRecordEntity>,
    val goal: GoalEntity?,
    val report: BodyReport,
    val unit: WeightUnit,
    val region: Grading.Region,
    val today: LocalDate,
)

/**
 * One live analysis of the active profile, shared by every screen. The role, its
 * readings and its goal switch together, so no screen can show one person's numbers
 * under another person's name, even for a frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BodyData(
    session: ProfileSession,
    weights: WeightRepository,
    goals: GoalRepository,
    settings: SettingsRepository,
    scope: CoroutineScope,
) {
    private class Owned(val role: RoleEntity?, val records: List<WeightRecordEntity>, val goal: GoalEntity?)

    private val today = MutableStateFlow(LocalDate.now())

    private val owned = session.active
        .filter { it != ActiveProfile.Loading }
        .map { (it as? ActiveProfile.Selected)?.role }
        .distinctUntilChanged()
        .flatMapLatest { role ->
            if (role == null) {
                flowOf(Owned(null, emptyList(), null))
            } else {
                combine(weights.recordsFlow(role.id), goals.activeGoal(role.id)) { records, goal ->
                    Owned(role, records, goal)
                }
            }
        }

    val state: StateFlow<BodyState?> =
        combine(owned, settings.weightUnit, settings.bmiRegion, today) { o, unit, region, day ->
            BodyState(
                role = o.role,
                records = o.records,
                goal = o.goal,
                report = BodyAnalysis.analyze(o.records, o.goal, region, day, ZoneId.systemDefault()),
                unit = unit,
                region = region,
                today = day,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Called on resume, so "days since" and the like are right after a night in the background. */
    fun refreshDay() {
        today.value = LocalDate.now()
    }
}
