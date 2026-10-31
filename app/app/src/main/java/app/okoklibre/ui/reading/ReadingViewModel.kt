package app.okoklibre.ui.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.okoklibre.algo.BodyCompositionEngine
import app.okoklibre.algo.CompositionInput
import app.okoklibre.algo.CompositionResult
import app.okoklibre.algo.Grading
import app.okoklibre.algo.ScaleComputedInput
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.RoleEntity
import app.okoklibre.data.WeightRecordEntity
import app.okoklibre.data.WeightStatusEntity
import app.okoklibre.insights.BodyAnalysis
import app.okoklibre.insights.ChangeAttribution
import app.okoklibre.insights.HealthAnswer
import app.okoklibre.insights.InsightEngine
import app.okoklibre.insights.ScoreAttribution
import app.okoklibre.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rebuilds the full snapshot of a stored record, by whichever path produced it. */
fun recompute(record: WeightRecordEntity): CompositionResult =
    if (record.impedanceOhm <= 0.0 && record.bodyFatPct > 0.0) {
        BodyCompositionEngine.fromScaleComputed(
            ScaleComputedInput(
                heightCm = record.heightCm,
                weightKg = record.weightKg,
                sex = record.sex,
                ageYears = record.ageYears,
                fatPct = record.bodyFatPct,
                waterPct = record.waterPct,
                musclePct = record.musclePct,
                bmrKcal = record.bmrKcal,
                visceralFat = record.visceralFat,
                boneKg = record.boneKg,
            ),
        )
    } else {
        BodyCompositionEngine.compute(
            CompositionInput(
                heightCm = record.heightCm,
                weightKg = record.weightKg,
                sex = record.sex,
                ageYears = record.ageYears,
                impedanceOhm = record.impedanceOhm,
            ),
        )
    }

/** One reading and everything said about it. */
data class ReadingUi(
    val record: WeightRecordEntity,
    val owner: RoleEntity?,
    val result: CompositionResult,
    val previous: WeightRecordEntity?,
    val change: ChangeAttribution?,
    val scoreShift: ScoreAttribution?,
    val health: HealthAnswer,
)

sealed interface ReadingEvent {
    data object Removed : ReadingEvent
    data class Moved(val newId: Long, val to: RoleEntity) : ReadingEvent
}

class ReadingViewModel(
    private val appState: AppState,
    recordId: Long,
) : ViewModel() {

    private val _ui = MutableStateFlow<ReadingUi?>(null)
    val ui: StateFlow<ReadingUi?> = _ui.asStateFlow()

    private val _notFound = MutableStateFlow(false)
    val notFound: StateFlow<Boolean> = _notFound.asStateFlow()

    private val _events = MutableSharedFlow<ReadingEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ReadingEvent> = _events.asSharedFlow()

    val roles: StateFlow<List<RoleEntity>> = appState.profileSession.roles

    val unit: StateFlow<WeightUnit> = appState.settingsRepository.weightUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeightUnit.KG)

    val region: StateFlow<Grading.Region> = appState.settingsRepository.bmiRegion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Grading.Region.OTHER)

    val status: StateFlow<WeightStatusEntity?> = appState.statusRepository.statusFlow(recordId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val currentId = recordId

    init {
        viewModelScope.launch { load(recordId) }
    }

    private suspend fun load(id: Long) {
        val record = appState.weightRepository.record(id)
        if (record == null || record.deleted) {
            _notFound.value = true
            return
        }
        val owner = appState.roleRepository.role(record.roleId)
        val region = appState.settingsRepository.bmiRegion.first()
        _ui.value = withContext(Dispatchers.Default) {
            val previous = appState.weightRepository.records(record.roleId)
                .filter { it.timestampEpochSec < record.timestampEpochSec }
                .maxByOrNull { it.timestampEpochSec }
            ReadingUi(
                record = record,
                owner = owner,
                result = recompute(record),
                previous = previous,
                change = previous?.let { InsightEngine.changeAttribution(it, record) },
                scoreShift = previous?.let { InsightEngine.scoreAttribution(it, record) },
                health = BodyAnalysis.health(record, region),
            )
        }
    }

    fun moveTo(role: RoleEntity) {
        viewModelScope.launch {
            val movedId = appState.weightRepository.moveRecord(currentId, role) ?: return@launch
            appState.profileSession.switchTo(role.id)
            _events.emit(ReadingEvent.Moved(movedId, role))
        }
    }

    fun updateStatus(transform: (WeightStatusEntity) -> WeightStatusEntity) {
        viewModelScope.launch {
            val current = status.value
                ?: WeightStatusEntity(weightRecordId = currentId, exerciseIntensity = -1, defecationNum = -1)
            appState.statusRepository.save(transform(current))
        }
    }

    fun delete() {
        viewModelScope.launch {
            appState.weightRepository.hardDelete(listOf(currentId))
            _events.emit(ReadingEvent.Removed)
        }
    }
}
