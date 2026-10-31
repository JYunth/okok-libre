package app.okoklibre.ui.trends

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.okoklibre.R
import app.okoklibre.algo.BodyCompositionEngine
import app.okoklibre.algo.WeightUnit
import app.okoklibre.algo.round1
import app.okoklibre.data.WeightRecordEntity
import app.okoklibre.insights.BodyState
import app.okoklibre.insights.TrendFit
import app.okoklibre.insights.TrendModel
import app.okoklibre.insights.dailySeries
import app.okoklibre.insights.fatMassKg
import app.okoklibre.insights.hasComposition
import app.okoklibre.insights.leanMassKg
import app.okoklibre.insights.muscleMassKg
import app.okoklibre.ui.AppState
import app.okoklibre.ui.common.formatWeightKg
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

enum class MetricKind { WEIGHT, PERCENT, DECIMAL, WHOLE }

/** Everything a person can chart. Weight-only readings count as "no value" for the rest. */
enum class Metric(
    @StringRes val label: Int,
    val kind: MetricKind,
    val value: (WeightRecordEntity) -> Double,
) {
    WEIGHT(R.string.metric_weight, MetricKind.WEIGHT, { it.weightKg }),
    MUSCLE(R.string.metric_muscle, MetricKind.WEIGHT, { it.muscleMassKg }),
    FAT_MASS(R.string.metric_fat_weight, MetricKind.WEIGHT, { it.fatMassKg }),
    BODY_FAT(R.string.metric_body_fat, MetricKind.PERCENT, { it.bodyFatPct }),
    LEAN(R.string.metric_lean_body_mass, MetricKind.WEIGHT, { it.leanMassKg }),
    WATER(R.string.metric_water, MetricKind.PERCENT, { it.waterPct }),
    VISCERAL(R.string.metric_visceral_fat, MetricKind.DECIMAL, { it.visceralFat }),
    BMI(R.string.metric_bmi, MetricKind.DECIMAL, { it.bmi }),
    SCORE(R.string.report_score, MetricKind.WHOLE, { it.score.toDouble() }),
    BMR(R.string.metric_bmr, MetricKind.WHOLE, { it.bmrKcal }),
    PROTEIN(R.string.metric_protein, MetricKind.PERCENT, ::proteinPct),
    BONE(R.string.metric_bone, MetricKind.WEIGHT, { it.boneKg }),
    BODY_AGE(R.string.metric_body_age, MetricKind.WHOLE, { it.bodyAge.toDouble() }),
    ;

    fun format(value: Double, unit: WeightUnit): String = when (kind) {
        MetricKind.WEIGHT -> formatWeightKg(value, unit, 1)
        MetricKind.PERCENT, MetricKind.DECIMAL -> "%.1f".format(value)
        MetricKind.WHOLE -> value.roundToInt().toString()
    }

    /** About 13 kcal of BMR per kg of body, which keeps its noise in the model's range. */
    val unitsPerKg: Double get() = if (this == BMR) 13.0 else 1.0

    /** Composition metrics read 0 on a weight-only record, and 0 there means unknown. */
    val needsComposition: Boolean get() = this != WEIGHT && this != BMI
}

/** Protein isn't stored, so it's derived here by the same rules the report uses. */
private fun proteinPct(r: WeightRecordEntity): Double = when {
    !r.hasComposition -> 0.0
    r.impedanceOhm > 0.0 -> if (r.ageYears > 17) round1(round1(r.musclePct) - r.waterPct) else 0.0
    else -> BodyCompositionEngine.calProteinNoImpedance(r.weightKg, r.sex, r.bodyFatPct, r.waterPct)
}

/** Tagged with whose it is: the fit runs after a profile switch and must not outlive it. */
data class MetricFit(val roleId: Long?, val metric: Metric, val fit: TrendFit?)

enum class ChartRange(@StringRes val label: Int, val days: Long?) {
    MONTH(R.string.range_month, 30),
    QUARTER(R.string.range_quarter, 90),
    HALF(R.string.range_half, 182),
    YEAR(R.string.range_year, 365),
    ALL(R.string.range_all, null),
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModel(private val appState: AppState) : ViewModel() {

    val body: StateFlow<BodyState?> = appState.bodyData.state

    private val _metric = MutableStateFlow(Metric.WEIGHT)
    val metric: StateFlow<Metric> = _metric.asStateFlow()

    private val _range = MutableStateFlow(ChartRange.QUARTER)
    val range: StateFlow<ChartRange> = _range.asStateFlow()

    /** The chosen metric smoothed over the whole history, so the range only changes the view. */
    val fit: StateFlow<MetricFit?> = combine(body, metric) { state, metric -> state to metric }
        .mapLatest { (state, metric) ->
            state?.let { MetricFit(it.role?.id, metric, fitFor(it, metric)) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun fitFor(state: BodyState, metric: Metric): TrendFit? {
        if (metric == Metric.WEIGHT) return state.report.weight
        val usable = state.records.filter { (!metric.needsComposition || it.hasComposition) && metric.value(it) > 0.0 }
        return TrendModel.fit(dailySeries(usable, ZoneId.systemDefault(), metric.value), metric.unitsPerKg)
    }

    fun select(metric: Metric) {
        _metric.value = metric
    }

    fun select(range: ChartRange) {
        _range.value = range
    }
}
