package app.okoklibre.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.okoklibre.algo.Grading
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.LengthUnit
import app.okoklibre.data.RoleType
import app.okoklibre.ui.AppState
import app.okoklibre.ui.family.PersonForm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class OnboardingStep { WELCOME, YOU, UNITS }

/** Everything collected before the first person exists. */
data class OnboardingDraft(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val person: PersonForm = PersonForm(),
    val showErrors: Boolean = false,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val lengthUnit: LengthUnit = LengthUnit.CM,
    val bmiRegion: Grading.Region = Grading.Region.OTHER,
    val saving: Boolean = false,
    val failed: Boolean = false,
)

class OnboardingViewModel(private val appState: AppState) : ViewModel() {

    /** Snapshot state rather than a StateFlow: a text field must get its value back within the frame. */
    var draft by mutableStateOf(OnboardingDraft())
        private set

    init {
        // Prefill the unit choices with whatever is already persisted.
        viewModelScope.launch {
            val s = appState.settingsRepository
            val weightUnit = s.weightUnit.first()
            val lengthUnit = s.lengthUnit.first()
            val bmiRegion = s.bmiRegion.first()
            draft = draft.copy(weightUnit = weightUnit, lengthUnit = lengthUnit, bmiRegion = bmiRegion)
        }
    }

    fun update(block: OnboardingDraft.() -> OnboardingDraft) {
        draft = draft.block()
    }

    /** The height page and the units page set the same unit, so the two can never disagree. */
    fun chooseLengthUnit(unit: LengthUnit) {
        val person = draft.person
        draft = draft.copy(lengthUnit = unit, person = person.copy(height = person.height.refilled()))
    }

    fun next() {
        draft = when {
            draft.step == OnboardingStep.WELCOME -> draft.copy(step = OnboardingStep.YOU)
            draft.step == OnboardingStep.UNITS -> draft
            draft.person.isComplete() -> draft.copy(step = OnboardingStep.UNITS)
            else -> draft.copy(showErrors = true)
        }
    }

    fun back() {
        if (draft.saving) return
        val previous = OnboardingStep.entries.getOrNull(draft.step.ordinal - 1) ?: return
        draft = draft.copy(step = previous)
    }

    /** Creates the main role, persists the chosen units and makes that person the active one. */
    fun finish(onFinished: () -> Unit) {
        val d = draft
        if (d.saving) return
        val sex = d.person.sex ?: return
        val birthday = d.person.birthdayEpochDay ?: return
        val height = d.person.height.cm ?: return
        draft = d.copy(saving = true, failed = false)
        viewModelScope.launch {
            val saved = runCatching {
                val id = appState.roleRepository.createRole(
                    nickname = d.person.name.trim(),
                    sex = sex,
                    birthdayEpochDay = birthday,
                    heightCm = height,
                    roleType = RoleType.MAIN,
                )
                with(appState.settingsRepository) {
                    setWeightUnit(d.weightUnit)
                    setLengthUnit(d.lengthUnit)
                    setBmiRegion(d.bmiRegion)
                    setOnboardingDone(true)
                }
                appState.profileSession.switchTo(id)
            }
            if (saved.isSuccess) {
                onFinished()
            } else {
                draft = draft.copy(saving = false, failed = true)
            }
        }
    }
}
