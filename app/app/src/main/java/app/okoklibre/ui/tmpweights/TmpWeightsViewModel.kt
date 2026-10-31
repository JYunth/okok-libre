package app.okoklibre.ui.tmpweights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.RoleEntity
import app.okoklibre.data.TmpWeightEntity
import app.okoklibre.ui.AppState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How a claim ended, for the snackbar. */
sealed interface ClaimOutcome {
    data class Claimed(val recordId: Long, val ownerName: String) : ClaimOutcome

    /** The reading or the person went away between the tap and the write. */
    data object Gone : ClaimOutcome
}

class TmpWeightsViewModel(private val appState: AppState) : ViewModel() {

    /** Null until the first read, so the empty state doesn't flash. */
    val pending: StateFlow<List<TmpWeightEntity>?> = appState.tmpWeightRepository.pending
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val roles: StateFlow<List<RoleEntity>> = appState.profileSession.roles

    val weightUnit: StateFlow<WeightUnit> = appState.settingsRepository.weightUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeightUnit.KG)

    private val _claims = MutableSharedFlow<ClaimOutcome>(extraBufferCapacity = 1)
    val claims: SharedFlow<ClaimOutcome> = _claims.asSharedFlow()

    /** The claimer becomes the active person, so opening the reading lands on their own data. */
    fun claim(tmpId: Long, role: RoleEntity) {
        viewModelScope.launch {
            val recordId = appState.tmpWeightRepository.claim(tmpId, role.id)
            if (recordId == null) {
                _claims.emit(ClaimOutcome.Gone)
            } else {
                appState.profileSession.switchTo(role.id)
                _claims.emit(ClaimOutcome.Claimed(recordId, role.nickname))
            }
        }
    }

    fun delete(tmpId: Long) {
        viewModelScope.launch { appState.tmpWeightRepository.delete(tmpId) }
    }
}
