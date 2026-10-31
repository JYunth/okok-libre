package app.okoklibre.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.okoklibre.data.GoalEntity
import app.okoklibre.insights.BodyState
import app.okoklibre.ui.AppState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Goals of whoever is active, so a switch of person changes everything here at once. */
class GoalsViewModel(private val appState: AppState) : ViewModel() {

    val body: StateFlow<BodyState?> = appState.bodyData.state

    /** Their finished goals, newest first. Null until the first read. */
    val history: StateFlow<List<GoalEntity>?> = appState.profileSession
        .perProfile<List<GoalEntity>?>(emptyList()) { role ->
            appState.goalRepository.history(role.id).map { goals -> goals.filter { !it.active } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun startGoal(targetKg: Double) {
        val state = body.value ?: return
        val role = state.role ?: return
        val startKg = state.report.latest?.weightKg ?: return
        viewModelScope.launch {
            appState.goalRepository.startGoal(role.id, startKg, targetKg, state.today.toEpochDay())
        }
    }

    fun finishGoal() {
        val state = body.value ?: return
        val goal = state.goal ?: return
        val endKg = state.report.latest?.weightKg ?: return
        viewModelScope.launch {
            appState.goalRepository.completeGoal(goal.id, endKg, state.today.toEpochDay())
        }
    }

    fun endGoal() {
        val goal = body.value?.goal ?: return
        viewModelScope.launch { appState.goalRepository.cancelGoal(goal.id) }
    }
}
