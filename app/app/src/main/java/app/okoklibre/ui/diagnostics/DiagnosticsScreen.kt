package app.okoklibre.ui.diagnostics

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.okoklibre.R
import app.okoklibre.diagnostics.DiagnosticLog
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.theme.LocalAppColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Live view of [DiagnosticLog]: monospace lines, autoscrolling to the newest
 * entry, with Copy (whole log to clipboard) and Clear actions.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val entries by DiagnosticLog.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    val copiedText = stringResource(R.string.diagnostics_copied)

    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm:ss.SSS") }
    val zone = remember { ZoneId.systemDefault() }

    // Keyed on the list, not its size: once the buffer is full the size stops changing.
    LaunchedEffect(entries) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(stringResource(R.string.diagnostics_title), onBack) {
                if (entries.isNotEmpty()) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(DiagnosticLog.formatAll()))
                        scope.launch { snackbars.showSnackbar(copiedText) }
                    }) { Text(stringResource(R.string.diagnostics_copy)) }
                    TextButton(onClick = { DiagnosticLog.clear() }) {
                        Text(stringResource(R.string.diagnostics_clear))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(
                stringResource(R.string.diagnostics_empty_title),
                stringResource(R.string.diagnostics_empty_body),
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Gutter, vertical = 8.dp),
            ) {
                items(entries) { entry ->
                    Text(
                        formatEntry(entry, timeFmt, zone, colors.muted),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

private fun formatEntry(
    entry: DiagnosticLog.Entry,
    timeFmt: DateTimeFormatter,
    zone: ZoneId,
    muted: Color,
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = muted)) {
        append(Instant.ofEpochMilli(entry.timestampMs).atZone(zone).format(timeFmt))
        append(' ')
        append(entry.tag)
        append(": ")
    }
    append(entry.message)
}
