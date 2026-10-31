package app.okoklibre.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.okoklibre.R
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.Hairline
import app.okoklibre.ui.design.ListRow
import app.okoklibre.ui.design.SectionGap
import app.okoklibre.ui.theme.LocalAppColors
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LICENCE_ASSET = "licenses/Geist-OFL.txt"

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    val licenceFailed = stringResource(R.string.about_licence_failed)
    var licence by rememberSaveable { mutableStateOf<String?>(null) }
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: PackageManager.NameNotFoundException) {
            "?"
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppBar(title = stringResource(R.string.about_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Gutter)) {
                // The offset undoes the mark's splash-screen margin so it sits on the gutter. The
                // tint is needed because the resource follows the system's dark mode, not the app's.
                Image(
                    painterResource(R.drawable.ic_splash_mark),
                    contentDescription = null,
                    modifier = Modifier.offset(x = (-26).dp).size(96.dp),
                    colorFilter = ColorFilter.tint(colors.ink),
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Text(
                    stringResource(R.string.about_version, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
                Text(
                    stringResource(R.string.about_free_offline),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    stringResource(R.string.about_estimates),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(SectionGap))
            Hairline(Modifier.padding(horizontal = Gutter))
            ListRow(
                stringResource(R.string.about_licences_row),
                onClick = {
                    scope.launch {
                        try {
                            licence = withContext(Dispatchers.IO) {
                                context.assets.open(LICENCE_ASSET).bufferedReader().use { it.readText() }
                            }
                        } catch (e: IOException) {
                            snackbars.showSnackbar(licenceFailed)
                        }
                    }
                },
            )
            ListRow(stringResource(R.string.about_diagnostics), onClick = onOpenDiagnostics)
        }
    }

    licence?.let { text ->
        AlertDialog(
            onDismissRequest = { licence = null },
            title = { Text(stringResource(R.string.about_licences_title)) },
            text = {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { licence = null }) { Text(stringResource(R.string.done)) }
            },
        )
    }
}
