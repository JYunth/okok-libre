package app.okoklibre.ui.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.okoklibre.algo.CompositionResult
import app.okoklibre.algo.Grading
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.WeightRecordEntity
import app.okoklibre.ui.AppState
import app.okoklibre.ui.common.formatWeightKg
import app.okoklibre.ui.reading.MetricRow
import app.okoklibre.ui.reading.recompute
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Two readings, oldest first, with everything needed to set them side by side. */
data class CompareUi(
    val older: WeightRecordEntity,
    val newer: WeightRecordEntity,
    val olderResult: CompositionResult,
    val newerResult: CompositionResult,
    val unit: WeightUnit,
    val region: Grading.Region,
)

class CompareViewModel(appState: AppState, firstId: Long, secondId: Long) : ViewModel() {

    private val _ui = MutableStateFlow<CompareUi?>(null)
    val ui: StateFlow<CompareUi?> = _ui.asStateFlow()

    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    init {
        viewModelScope.launch {
            val one = appState.weightRepository.record(firstId)?.takeIf { !it.deleted }
            val other = appState.weightRepository.record(secondId)?.takeIf { !it.deleted }
            if (one == null || other == null) {
                _missing.value = true
                return@launch
            }
            val (older, newer) = listOf(one, other).sortedBy { it.timestampEpochSec }
            val unit = appState.settingsRepository.weightUnit.first()
            val region = appState.settingsRepository.bmiRegion.first()
            _ui.value = withContext(Dispatchers.Default) {
                CompareUi(older, newer, recompute(older), recompute(newer), unit, region)
            }
        }
    }
}

/** One line of the table. A side is null when that reading didn't measure the metric. */
data class CompareRow(
    val title: String,
    /** Shown once beside the title, which leaves the columns bare figures that fit a phone. */
    val unit: String,
    val older: String?,
    val newer: String?,
    val change: String?,
) {
    /** A cell with its unit back on, for reading aloud or sending elsewhere. */
    fun spelled(figure: String?, none: String): String = figure?.let { "$it $unit".trim() } ?: none
}

private val MetricRow.figure: String get() = value.substringBeforeLast(' ')
private val MetricRow.unit: String get() = value.substringAfterLast(' ', "")

/**
 * Lines two readings up by metric, not by position: a weight-only reading has fewer rows,
 * and pairing the lists by index would set body fat against some unrelated figure.
 */
fun compareRows(
    older: List<MetricRow>,
    newer: List<MetricRow>,
    unit: WeightUnit,
    weightLabel: String,
): List<CompareRow> {
    val before = older.associateBy { it.title }
    val after = newer.associateBy { it.title }
    return mergeInOrder(older.map { it.title }, newer.map { it.title }).map { title ->
        val from = before[title]
        val to = after[title]
        CompareRow(
            title = title,
            unit = (to ?: from)?.unit.orEmpty(),
            older = from?.figure,
            newer = to?.figure,
            change = if (from != null && to != null) change(from, to, unit, weightLabel) else null,
        )
    }
}

/** Both lists follow the report's order, so walking them together keeps that order. */
private fun mergeInOrder(older: List<String>, newer: List<String>): List<String> {
    val merged = mutableListOf<String>()
    val rest = ArrayDeque(older)
    newer.forEach { title ->
        if (title in rest) {
            while (rest.first() != title) merged += rest.removeFirst()
            rest.removeFirst()
        }
        merged += title
    }
    return merged + rest
}

/** [MetricRow.raw] is always kg for a weight, so only those go through the display unit. */
private fun change(from: MetricRow, to: MetricRow, unit: WeightUnit, weightLabel: String): String {
    val delta = to.raw - from.raw
    val digits = decimals(to.figure)
    val size = if (to.unit == weightLabel) {
        formatWeightKg(abs(delta), unit, digits)
    } else {
        "%.${digits}f".format(abs(delta))
    }
    return when {
        size.none { it in '1'..'9' } -> size
        delta < 0 -> "−$size"
        else -> "+$size"
    }
}

private fun decimals(figure: String): Int =
    figure.takeLastWhile(Char::isDigit).length.takeIf { it < figure.length } ?: 0
