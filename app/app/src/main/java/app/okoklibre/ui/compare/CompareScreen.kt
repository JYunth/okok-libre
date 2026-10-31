package app.okoklibre.ui.compare

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.okoklibre.R
import app.okoklibre.algo.CompositionResult
import app.okoklibre.data.WeightRecordEntity
import app.okoklibre.insights.epochDay
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.clockTime
import app.okoklibre.ui.common.shortDate
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.Hairline
import app.okoklibre.ui.reading.MetricRow
import app.okoklibre.ui.reading.rememberMetricRows
import app.okoklibre.ui.theme.LocalAppColors
import java.time.ZoneId

private val ColumnGap = 12.dp

/** Narrower than this and a metric name would break mid-word, so the figures drop to their own line. */
private val MinNameWidth = 96.dp

/** Two readings in one table, oldest on the left. */
@Composable
fun CompareScreen(firstId: Long, secondId: Long, onBack: () -> Unit) {
    val vm: CompareViewModel = appViewModel(key = "compare-$firstId-$secondId") {
        CompareViewModel(it, firstId, secondId)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.compare_share)
    val current = ui
    val rows = current?.let { rememberCompareRows(it) }.orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(stringResource(R.string.compare_title), onBack = onBack) {
                if (current != null) {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, shareText(context, current, rows))
                        context.startActivity(Intent.createChooser(send, shareTitle))
                    }) {
                        Icon(Icons.Outlined.Share, shareTitle)
                    }
                }
            }
        },
    ) { padding ->
        when {
            missing -> EmptyState(
                stringResource(R.string.compare_missing_title),
                stringResource(R.string.compare_missing_body),
                Modifier.padding(padding),
            )
            current != null -> CompareTable(current, rows, Modifier.padding(padding))
        }
    }
}

@Composable
private fun rememberCompareRows(ui: CompareUi): List<CompareRow> {
    val leanTitle = stringResource(R.string.metric_lean_body_mass)
    val weightLabel = weightUnitLabel(ui.unit)
    // A weight-only reading reports its whole weight as lean mass. That's no measurement,
    // and setting it against a real one would invent a change of many kilos.
    fun measured(rows: List<MetricRow>, result: CompositionResult) =
        if (result.hasBodyComposition) rows else rows.filter { it.title != leanTitle }

    val older = measured(rememberMetricRows(ui.older, ui.olderResult, ui.unit, ui.region), ui.olderResult)
    val newer = measured(rememberMetricRows(ui.newer, ui.newerResult, ui.unit, ui.region), ui.newerResult)
    return remember(older, newer, ui.unit, weightLabel) { compareRows(older, newer, ui.unit, weightLabel) }
}

private fun shareText(context: Context, ui: CompareUi, rows: List<CompareRow>): String {
    val zone = ZoneId.systemDefault()
    val none = context.getString(R.string.compare_no_value)
    val heading = context.getString(
        R.string.compare_share_heading,
        shortDate(ui.older.epochDay(zone)),
        shortDate(ui.newer.epochDay(zone)),
    )
    return rows.joinToString("\n", prefix = "$heading\n") { row ->
        context.getString(
            R.string.compare_row_text,
            row.title,
            row.spelled(row.older, none),
            row.spelled(row.newer, none),
            row.spelled(row.change, none),
        )
    }
}

/** The date heads a column, plus the time when both readings are from the same day. */
private fun columnHead(context: Context, record: WeightRecordEntity, sameDay: Boolean, zone: ZoneId): String {
    val date = shortDate(record.epochDay(zone))
    return if (sameDay) "$date\n${clockTime(context, record.timestampEpochSec)}" else date
}

@Composable
private fun CompareTable(ui: CompareUi, rows: List<CompareRow>, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val cellStyle = MaterialTheme.typography.bodyMedium
    val headStyle = MaterialTheme.typography.labelMedium
    val none = stringResource(R.string.compare_no_value)
    val zone = ZoneId.systemDefault()
    val sameDay = ui.older.epochDay(zone) == ui.newer.epochDay(zone)
    val heads = listOf(
        columnHead(context, ui.older, sameDay, zone),
        columnHead(context, ui.newer, sameDay, zone),
        stringResource(R.string.compare_change),
    )

    // Sized to the widest cell, so a font scale or an st:lb figure can't clip a number.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellWidth = remember(rows, heads, density, cellStyle, headStyle) {
        val widest = maxOf(
            heads.maxOf { measurer.measure(it, headStyle).size.width },
            rows.flatMap { listOfNotNull(it.older, it.newer, it.change) }
                .maxOfOrNull { measurer.measure(it, cellStyle).size.width } ?: 0,
        )
        with(density) { widest.toDp() } + 1.dp
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val stacked = (cellWidth + ColumnGap) * 3 > maxWidth - Gutter * 2 - MinNameWidth
        Column(Modifier.fillMaxSize()) {
            TableLine(
                name = AnnotatedString(""),
                cells = heads.map { it to colors.muted },
                cellWidth = cellWidth,
                stacked = stacked,
                style = headStyle,
            )
            Hairline(Modifier.padding(horizontal = Gutter))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
                items(rows, key = { it.title }) { row ->
                    val description = stringResource(
                        R.string.compare_row_text,
                        row.title,
                        row.spelled(row.older, none),
                        row.spelled(row.newer, none),
                        row.spelled(row.change, none),
                    )
                    TableLine(
                        name = buildAnnotatedString {
                            append(row.title)
                            if (row.unit.isNotEmpty()) {
                                withStyle(SpanStyle(color = colors.muted)) { append(" ${row.unit}") }
                            }
                        },
                        cells = listOf(
                            (row.older ?: none) to colors.muted,
                            (row.newer ?: none) to colors.ink,
                            (row.change ?: none) to colors.ink,
                        ),
                        cellWidth = cellWidth,
                        stacked = stacked,
                        style = cellStyle,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                    )
                }
            }
        }
    }
}

@Composable
private fun TableLine(
    name: AnnotatedString,
    cells: List<Pair<String, Color>>,
    cellWidth: Dp,
    stacked: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val figures: @Composable () -> Unit = {
        cells.forEach { (text, color) ->
            Text(
                text,
                style = style,
                color = color,
                textAlign = TextAlign.End,
                softWrap = false,
                modifier = Modifier.width(cellWidth),
            )
        }
    }
    val line = modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp)
    if (stacked) {
        Column(line) {
            if (name.isNotEmpty()) Text(name, style = style)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ColumnGap, Alignment.End)) {
                figures()
            }
        }
    } else {
        Row(
            line,
            horizontalArrangement = Arrangement.spacedBy(ColumnGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = style, modifier = Modifier.weight(1f))
            figures()
        }
    }
}
