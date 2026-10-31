package app.okoklibre.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.okoklibre.R
import app.okoklibre.data.LengthUnit
import app.okoklibre.data.RoleEntity
import app.okoklibre.ui.LocalAppState
import app.okoklibre.ui.Routes
import app.okoklibre.ui.common.formatHeightCm
import app.okoklibre.ui.common.formatWeightKg
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.Avatar
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.Hairline
import app.okoklibre.ui.design.ListRow
import app.okoklibre.ui.theme.LocalAppColors
import java.time.LocalDate
import java.time.Period

/** The person on screen, then everything that can be set. */
@Composable
fun YouScreen(nav: NavHostController) {
    val appState = LocalAppState.current
    val body by appState.bodyData.state.collectAsStateWithLifecycle()
    val lengthUnit by appState.settingsRepository.lengthUnit.collectAsStateWithLifecycle(LengthUnit.CM)
    val state = body ?: return
    val role = state.role
    val goal = state.goal?.let { "${formatWeightKg(it.targetWeightKg, state.unit, 1)} ${weightUnitLabel(state.unit)}" }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        if (role != null) {
            val age = Period.between(LocalDate.ofEpochDay(role.birthdayEpochDay), state.today).years
            PersonHeading(
                role = role,
                summary = pluralStringResource(
                    R.plurals.you_summary,
                    age,
                    age,
                    formatHeightCm(role.heightCm.toDouble(), lengthUnit),
                ),
                onClick = { nav.navigate(Routes.roleEdit(role.id)) },
            )
        } else {
            ListRow(stringResource(R.string.you_add_person), onClick = { nav.navigate(Routes.roleEdit()) })
        }
        GroupBreak()
        ListRow(stringResource(R.string.you_goal), value = goal, onClick = { nav.navigate(Routes.GOALS) })
        ListRow(stringResource(R.string.reminders_title), onClick = { nav.navigate(Routes.REMINDERS) })
        GroupBreak()
        ListRow(stringResource(R.string.you_people), onClick = { nav.navigate(Routes.PEOPLE) })
        ListRow(stringResource(R.string.you_scales), onClick = { nav.navigate(Routes.DEVICES) })
        GroupBreak()
        ListRow(stringResource(R.string.units_title), onClick = { nav.navigate(Routes.UNITS) })
        ListRow(stringResource(R.string.you_appearance), onClick = { nav.navigate(Routes.APPEARANCE) })
        GroupBreak()
        ListRow(stringResource(R.string.you_data), onClick = { nav.navigate(Routes.DATA) })
        ListRow(stringResource(R.string.about_title), onClick = { nav.navigate(Routes.ABOUT) })
    }
}

@Composable
private fun PersonHeading(role: RoleEntity, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.edit), role = Role.Button, onClick = onClick)
            .padding(horizontal = Gutter, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Avatar(role, 56.dp)
        Column(Modifier.weight(1f)) {
            Text(
                role.nickname,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = LocalAppColors.current.muted)
        }
    }
}

@Composable
private fun GroupBreak() {
    Hairline(Modifier.padding(horizontal = Gutter, vertical = 8.dp))
}
