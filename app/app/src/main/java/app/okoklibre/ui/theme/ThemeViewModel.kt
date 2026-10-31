package app.okoklibre.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.okoklibre.data.ActiveProfile
import app.okoklibre.data.ThemeMode
import app.okoklibre.ui.AppState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ThemeState(val mode: ThemeMode = ThemeMode.SYSTEM, val personIndex: Int = 0)

/** What the root theme needs: light or dark, and whose colour the app wears. */
class ThemeViewModel(appState: AppState) : ViewModel() {

    val settings: StateFlow<ThemeState> = combine(
        appState.settingsRepository.themeMode,
        appState.profileSession.active,
    ) { mode, active ->
        ThemeState(mode, (active as? ActiveProfile.Selected)?.role?.colorIndex ?: 0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeState())
}
