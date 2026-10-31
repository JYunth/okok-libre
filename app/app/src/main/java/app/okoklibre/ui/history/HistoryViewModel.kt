package app.okoklibre.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.okoklibre.data.WeightRecordEntity
import app.okoklibre.insights.BodyState
import app.okoklibre.ui.AppState
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HistoryView { LIST, CALENDAR }

/** Ids the last delete hid, so the snackbar can bring them back. */
data class Deleted(val ids: List<Long>)

class HistoryViewModel(private val appState: AppState) : ViewModel() {

    val body: StateFlow<BodyState?> = appState.bodyData.state

    val pending: StateFlow<Int> = appState.tmpWeightRepository.pending.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _view = MutableStateFlow(HistoryView.LIST)
    val view: StateFlow<HistoryView> = _view.asStateFlow()

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selectedDay = MutableStateFlow<Long?>(null)
    val selectedDay: StateFlow<Long?> = _selectedDay.asStateFlow()

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _deleted = MutableSharedFlow<Deleted>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Deleted> = _deleted.asSharedFlow()

    init {
        // This view model outlives a profile switch, and a selection must not.
        viewModelScope.launch {
            appState.profileSession.activeRole.map { it?.id }.distinctUntilChanged().drop(1).collect {
                _selection.value = emptySet()
                _selectedDay.value = null
            }
        }
    }

    fun setView(view: HistoryView) {
        _view.value = view
        _selection.value = emptySet()
    }

    fun shiftMonth(by: Long) {
        _month.value = _month.value.plusMonths(by)
        _selectedDay.value = null
    }

    fun selectDay(day: Long) {
        _selectedDay.value = day.takeIf { it != _selectedDay.value }
    }

    fun toggle(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun deleteSelected() {
        val ids = _selection.value.toList()
        _selection.value = emptySet()
        viewModelScope.launch {
            appState.weightRepository.softDelete(ids)
            _deleted.emit(Deleted(ids))
        }
    }

    fun undo(deleted: Deleted) {
        viewModelScope.launch { appState.weightRepository.restore(deleted.ids) }
    }

    fun addManual(weightKg: Double, timestampEpochSec: Long) {
        viewModelScope.launch {
            val role = appState.profileSession.activeRoleNow() ?: return@launch
            val at = appState.weightRepository.freeInstant(role.id, timestampEpochSec)
            appState.weightRepository.insertComputed(role, weightKg, at)
        }
    }

    companion object {
        fun dayOf(record: WeightRecordEntity, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
            Instant.ofEpochSecond(record.timestampEpochSec).atZone(zone).toLocalDate()
    }
}
