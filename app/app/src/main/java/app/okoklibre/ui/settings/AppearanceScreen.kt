package app.okoklibre.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.okoklibre.R
import app.okoklibre.data.ThemeMode
import app.okoklibre.ui.LocalAppState
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.theme.LocalAppColors

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val settings = LocalAppState.current.settingsRepository
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    val failed = stringResource(R.string.settings_save_failed)
    val themeMode by settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppBar(title = stringResource(R.string.you_appearance), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter),
        ) {
            RadioRows(
                options = ThemeMode.entries,
                selected = themeMode,
                label = { themeModeName(it) },
                onSelect = { mode -> scope.saveSetting(snackbars, failed) { settings.setThemeMode(mode) } },
            )
            Text(
                stringResource(R.string.appearance_person_colour),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalAppColors.current.muted,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun themeModeName(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_mode_system
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
    },
)
