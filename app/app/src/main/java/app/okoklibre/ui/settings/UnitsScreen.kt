package app.okoklibre.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.okoklibre.R
import app.okoklibre.algo.Grading
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.LengthUnit
import app.okoklibre.ui.LocalAppState
import app.okoklibre.ui.common.regionLabel
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.ListRow
import app.okoklibre.ui.design.Section
import app.okoklibre.ui.design.SectionGap
import app.okoklibre.ui.theme.LocalAppColors
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun UnitsScreen(onBack: () -> Unit) {
    val settings = LocalAppState.current.settingsRepository
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    val failed = stringResource(R.string.settings_save_failed)
    val weightUnit by settings.weightUnit.collectAsStateWithLifecycle(WeightUnit.KG)
    val lengthUnit by settings.lengthUnit.collectAsStateWithLifecycle(LengthUnit.CM)
    val bmiRegion by settings.bmiRegion.collectAsStateWithLifecycle(Grading.Region.OTHER)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppBar(title = stringResource(R.string.units_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section(title = stringResource(R.string.units_weight)) {
                RadioRows(
                    options = WeightUnit.entries,
                    selected = weightUnit,
                    label = { weightUnitName(it) },
                    onSelect = { unit -> scope.saveSetting(snackbars, failed) { settings.setWeightUnit(unit) } },
                )
            }
            Spacer(Modifier.height(SectionGap))
            Section(title = stringResource(R.string.units_height)) {
                RadioRows(
                    options = LengthUnit.entries,
                    selected = lengthUnit,
                    label = { lengthUnitName(it) },
                    onSelect = { unit -> scope.saveSetting(snackbars, failed) { settings.setLengthUnit(unit) } },
                )
            }
            Spacer(Modifier.height(SectionGap))
            Section(
                title = stringResource(R.string.units_bmi_region),
                caption = stringResource(R.string.units_bmi_region_caption),
            ) {
                RadioRows(
                    options = Grading.Region.entries,
                    selected = bmiRegion,
                    label = { regionLabel(it) },
                    onSelect = { region -> scope.saveSetting(snackbars, failed) { settings.setBmiRegion(region) } },
                )
            }
            Spacer(Modifier.height(SectionGap))
        }
    }
}

@Composable
private fun weightUnitName(unit: WeightUnit): String = stringResource(
    when (unit) {
        WeightUnit.KG -> R.string.units_kilograms
        WeightUnit.LB -> R.string.units_pounds
        WeightUnit.ST_LB -> R.string.units_stone
        WeightUnit.JIN -> R.string.units_jin
    },
)

@Composable
private fun lengthUnitName(unit: LengthUnit): String = stringResource(
    when (unit) {
        LengthUnit.CM -> R.string.units_centimetres
        LengthUnit.IMPERIAL -> R.string.units_feet_inches
    },
)

/**
 * Choices that apply the moment they're picked. The whole row is the target and the radio
 * only shows the state. Ink, not the accent: these settings belong to the phone, not a person.
 */
@Composable
internal fun <T> RadioRows(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            val isSelected = option == selected
            ListRow(
                title = label(option),
                modifier = Modifier.selectable(
                    selected = isSelected,
                    role = Role.RadioButton,
                    onClick = { onSelect(option) },
                ),
                leading = {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = colors.ink, unselectedColor = colors.muted),
                    )
                },
                padding = PaddingValues(vertical = 10.dp),
            )
        }
    }
}

/** DataStore reports a write it couldn't finish as an [IOException]. */
internal fun CoroutineScope.saveSetting(
    snackbars: SnackbarHostState,
    failed: String,
    write: suspend () -> Unit,
) {
    launch {
        try {
            write()
        } catch (e: IOException) {
            snackbars.showSnackbar(failed)
        }
    }
}
