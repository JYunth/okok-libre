package app.okoklibre.ui.family

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.okoklibre.R
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.RoleEntity
import app.okoklibre.data.WeightRecordEntity
import app.okoklibre.ui.AppState
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.formatRecordWeightKg
import app.okoklibre.ui.common.readingWhen
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.Avatar
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.ListRow
import app.okoklibre.ui.theme.LocalAppColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** One person and the last thing the scale said about them. */
data class PersonRow(val role: RoleEntity, val latest: WeightRecordEntity?)

@OptIn(ExperimentalCoroutinesApi::class)
class PeopleViewModel(appState: AppState) : ViewModel() {

    /** Null until the first read. */
    val people: StateFlow<List<PersonRow>?> = appState.profileSession.roles
        .mapLatest { roles -> roles.map { PersonRow(it, appState.weightRepository.latest(it.id)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeId: StateFlow<Long?> = appState.profileSession.activeRole.map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val unit: StateFlow<WeightUnit> = appState.settingsRepository.weightUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeightUnit.KG)
}

/** Everyone on this phone. A row opens that person's details. */
@Composable
fun PeopleScreen(onEditRole: (Long) -> Unit, onBack: () -> Unit) {
    val vm: PeopleViewModel = appViewModel { PeopleViewModel(it) }
    val people by vm.people.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    val rows = people

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppBar(stringResource(R.string.people_list_title), onBack = onBack) },
    ) { padding ->
        when {
            rows == null -> Unit
            rows.isEmpty() -> EmptyState(
                stringResource(R.string.people_empty_title),
                stringResource(R.string.family_empty),
                Modifier.padding(padding),
                action = stringResource(R.string.people_add),
                onAction = { onEditRole(-1L) },
            )
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(rows, key = { it.role.id }) { row ->
                    Person(
                        row = row,
                        active = row.role.id == activeId,
                        unit = unit,
                        onClick = { onEditRole(row.role.id) },
                    )
                }
                item {
                    ListRow(
                        title = stringResource(R.string.people_add),
                        leading = {
                            Box(Modifier.size(AvatarSize), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Add, null, tint = LocalAppColors.current.muted)
                            }
                        },
                        onClick = { onEditRole(-1L) },
                    )
                }
            }
        }
    }
}

private val AvatarSize = 40.dp

@Composable
private fun Person(row: PersonRow, active: Boolean, unit: WeightUnit, onClick: () -> Unit) {
    val latest = row.latest
    ListRow(
        title = row.role.nickname,
        subtitle = if (latest != null) {
            stringResource(
                R.string.people_latest,
                formatRecordWeightKg(latest.weightKg, unit, latest.scaleProperty),
                weightUnitLabel(unit),
                readingWhen(latest.timestampEpochSec),
            )
        } else {
            stringResource(R.string.people_no_readings)
        },
        value = if (active) stringResource(R.string.people_active) else null,
        valueColor = LocalAppColors.current.accent,
        leading = { Avatar(row.role, AvatarSize) },
        onClick = onClick,
    )
}
