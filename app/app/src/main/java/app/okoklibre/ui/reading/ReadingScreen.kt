package app.okoklibre.ui.reading

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.okoklibre.R
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.RecordSource
import app.okoklibre.data.StatusRepository
import app.okoklibre.data.WeightRecordEntity
import app.okoklibre.data.WeightStatusEntity
import app.okoklibre.insights.AttributionCause
import app.okoklibre.insights.ChangeAttribution
import app.okoklibre.insights.ScoreComponent
import app.okoklibre.insights.hasComposition
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.formatRecordWeightKg
import app.okoklibre.ui.common.formatWeightKg
import app.okoklibre.ui.common.readingWhen
import app.okoklibre.ui.common.signedWeight
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.Avatar
import app.okoklibre.ui.design.BarPart
import app.okoklibre.ui.design.DestructiveAction
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.Hairline
import app.okoklibre.ui.design.KeyRow
import app.okoklibre.ui.design.ListRow
import app.okoklibre.ui.design.Ruler
import app.okoklibre.ui.design.Section
import app.okoklibre.ui.design.SectionGap
import app.okoklibre.ui.design.ShareBar
import app.okoklibre.ui.design.Tissue
import app.okoklibre.ui.theme.LocalAppColors
import app.okoklibre.ui.today.formatPoints
import app.okoklibre.ui.today.leverName
import kotlin.math.abs

private const val SPECIAL_MENSTRUATION = "MENSTRUATION"
private const val SPECIAL_MEDICATION = "MEDICATION"
private const val SPECIAL_POOR_SLEEP = "POOR_SLEEP"

/**
 * One reading in full. [fresh] means it was just weighed, so whose it is stays front and
 * centre: on a shared phone that's the one thing worth correcting before anything else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    recordId: Long,
    fresh: Boolean,
    onBack: () -> Unit,
    onMoved: (Long) -> Unit,
) {
    val vm: ReadingViewModel = appViewModel(key = "reading-$recordId") { ReadingViewModel(it, recordId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val notFound by vm.notFound.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    val region by vm.region.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val roles by vm.roles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    var moving by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                ReadingEvent.Removed -> onBack()
                is ReadingEvent.Moved -> onMoved(event.newId)
            }
        }
    }

    val shareTitle = stringResource(R.string.reading_share)
    val current = ui
    val rows = current?.let { rememberMetricRows(it.record, it.result, unit, region) }.orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(title = null, onBack = onBack) {
                if (current != null) {
                    IconButton(onClick = {
                        val text = rows.joinToString("\n") { "${it.title}: ${it.value}" }
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, shareTitle))
                    }) {
                        Icon(Icons.Outlined.Share, shareTitle)
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        when {
            notFound -> EmptyState(
                stringResource(R.string.reading_gone_title),
                stringResource(R.string.reading_gone_body),
                Modifier.padding(padding),
            )
            current != null -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item { Headline(current, unit, fresh, roles.size > 1) { moving = true } }
                if (current.record.hasComposition) {
                    item { Spacer(Modifier.height(SectionGap)) }
                    item { Makeup(current, unit) }
                    // No score is computed for under-18s.
                    if (current.result.bodyScore > 0) {
                        item { Spacer(Modifier.height(SectionGap)) }
                        item { Score(current) }
                    }
                } else {
                    item { WeightOnlyNote() }
                }
                item { Spacer(Modifier.height(SectionGap)) }
                item {
                    Text(
                        stringResource(R.string.reading_all_metrics),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = Gutter).padding(bottom = 4.dp),
                    )
                }
                items(rows, key = { it.title }) { row -> MetricRowView(row) }
                item { Spacer(Modifier.height(SectionGap)) }
                if (current.record.hasComposition) {
                    item { Control(current, unit) }
                    item { Spacer(Modifier.height(SectionGap)) }
                }
                item {
                    Notes(status, recordId, vm::updateStatus)
                }
                item { Spacer(Modifier.height(SectionGap)) }
                item { Details(current.record) }
                item {
                    DestructiveAction(stringResource(R.string.reading_delete), onClick = { confirmingDelete = true })
                }
            }
        }
    }

    if (moving) {
        ModalBottomSheet(onDismissRequest = { moving = false }, containerColor = LocalAppColors.current.ground) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    stringResource(R.string.reading_move_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = Gutter),
                )
                Text(
                    stringResource(R.string.reading_move_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalAppColors.current.muted,
                    modifier = Modifier.padding(horizontal = Gutter).padding(top = 4.dp, bottom = 8.dp),
                )
                roles.filter { it.id != current?.record?.roleId }.forEach { role ->
                    ListRow(
                        title = role.nickname,
                        leading = { Avatar(role, 32.dp) },
                        onClick = {
                            moving = false
                            vm.moveTo(role)
                        },
                    )
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.reading_delete_title)) },
            text = { Text(stringResource(R.string.reading_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    vm.delete()
                }) { Text(stringResource(R.string.delete), color = LocalAppColors.current.attention) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun Headline(ui: ReadingUi, unit: WeightUnit, fresh: Boolean, canMove: Boolean, onMove: () -> Unit) {
    val colors = LocalAppColors.current
    val record = ui.record
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
        ui.owner?.let { owner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(owner, 26.dp)
                Text(
                    owner.nickname,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
                if (canMove) {
                    TextButton(onClick = onMove) {
                        Text(
                            if (fresh) {
                                stringResource(R.string.reading_not_you, owner.nickname)
                            } else {
                                stringResource(R.string.reading_move)
                            },
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
            Text(
                formatRecordWeightKg(record.weightKg, unit, record.scaleProperty),
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                weightUnitLabel(unit),
                style = MaterialTheme.typography.titleLarge,
                color = colors.muted,
                modifier = Modifier.padding(start = 6.dp, bottom = 14.dp),
            )
        }
        Text(readingWhen(record.timestampEpochSec), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        ui.change?.let { change ->
            Text(
                changeSentence(change, unit),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun changeSentence(change: ChangeAttribution, unit: WeightUnit): String {
    val label = weightUnitLabel(unit)
    val weight = "${signedWeight(change.deltaWeightKg, unit)} $label"
    val fat = change.deltaFatMassKg?.let { "${signedWeight(it, unit)} $label" }.orEmpty()
    val muscle = change.deltaMuscleKg?.let { "${signedWeight(it, unit)} $label" }.orEmpty()
    return when (change.cause) {
        AttributionCause.HYDRATION_NOISE -> stringResource(R.string.change_water, weight)
        AttributionCause.FAT_CHANGE -> stringResource(R.string.change_fat, weight, fat)
        AttributionCause.MUSCLE_CHANGE -> stringResource(R.string.change_muscle, weight, muscle)
        AttributionCause.MIXED -> stringResource(R.string.change_mixed, weight, fat, muscle)
        AttributionCause.UNCLEAR, AttributionCause.WEIGHT_ONLY -> stringResource(R.string.change_plain, weight)
    }
}

@Composable
private fun WeightOnlyNote() {
    val colors = LocalAppColors.current
    Text(
        stringResource(R.string.note_weight_only_body),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.muted,
        modifier = Modifier.padding(horizontal = Gutter).padding(top = 16.dp),
    )
}

/** What the weight is made of. Fat, muscle and bone are the whole of it. */
@Composable
private fun Makeup(ui: ReadingUi, unit: WeightUnit) {
    val colors = LocalAppColors.current
    val r = ui.result
    val total = (r.fatMassKg + r.muscleMassKg + r.boneMassKg).coerceAtLeast(0.1)
    val label = weightUnitLabel(unit)
    fun kg(value: Double) = "${formatWeightKg(value, unit, 1)} $label"
    val fat = Tissue.fat(colors)
    val muscle = Tissue.muscle(colors)
    val bone = Tissue.bone(colors)
    Section(title = stringResource(R.string.reading_makeup)) {
        ShareBar(
            listOf(
                BarPart((r.muscleMassKg / total).toFloat(), muscle),
                BarPart((r.fatMassKg / total).toFloat(), fat),
                BarPart((r.boneMassKg / total).toFloat(), bone),
            ),
        )
        Spacer(Modifier.height(10.dp))
        KeyRow(muscle, stringResource(R.string.metric_muscle), kg(r.muscleMassKg))
        KeyRow(fat, stringResource(R.string.metric_fat_weight), kg(r.fatMassKg))
        KeyRow(bone, stringResource(R.string.metric_bone), kg(r.boneMassKg))
    }
}

@Composable
private fun scoreComponentName(component: ScoreComponent): String = stringResource(
    when (component) {
        ScoreComponent.BMI -> R.string.metric_bmi
        ScoreComponent.FAT -> R.string.metric_body_fat
        ScoreComponent.MUSCLE -> R.string.metric_muscle
        ScoreComponent.VISCERAL -> R.string.metric_visceral_fat
    },
)

@Composable
private fun Score(ui: ReadingUi) {
    val colors = LocalAppColors.current
    val score = ui.result.bodyScore
    Section(title = stringResource(R.string.report_score)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$score", style = MaterialTheme.typography.displayMedium)
            Text(
                stringResource(R.string.reading_of_100),
                style = MaterialTheme.typography.titleMedium,
                color = colors.muted,
                modifier = Modifier.padding(start = 6.dp, bottom = 9.dp),
            )
        }
        ui.result.bodyShape?.let { shape ->
            Text(shape.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
        }
        ui.scoreShift?.let { shift ->
            Text(
                stringResource(
                    if (shift.deltaScore > 0) R.string.reading_score_up else R.string.reading_score_down,
                    abs(shift.deltaScore),
                    scoreComponentName(shift.component),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val levers = ui.health.levers.filter { it.points >= 0.1 }
        if (levers.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            levers.forEach { lever ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        leverName(lever.lever),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.reading_points, formatPoints(lever.points)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricRowView(row: MetricRow) {
    val colors = LocalAppColors.current
    var open by rememberSaveable(row.title) { mutableStateOf(false) }
    val canOpen = row.rangeText != null || row.about != null
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (canOpen) Modifier.clickable { open = !open } else Modifier)
            .padding(horizontal = Gutter, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (row.levelText != null) {
                Text(
                    row.levelText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.concern) colors.attention else colors.muted,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Text(row.value, style = MaterialTheme.typography.titleMedium)
        }
        if (row.low != null && row.high != null) {
            Ruler(
                value = row.raw,
                low = row.low,
                high = row.high,
                concern = row.concern,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 8.dp)) {
                row.rangeText?.let {
                    Text(
                        stringResource(R.string.reading_standard_range, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
                row.about?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** The original's weight, fat and muscle control numbers: how far from the standard body. */
@Composable
private fun Control(ui: ReadingUi, unit: WeightUnit) {
    val colors = LocalAppColors.current
    val r = ui.result
    val label = weightUnitLabel(unit)
    Section(
        title = stringResource(R.string.reading_control_title),
        caption = stringResource(
            R.string.reading_control_caption,
            "${formatWeightKg(r.idealWeightKg, unit, 1)} $label",
        ),
    ) {
        KeyRow(colors.faint, stringResource(R.string.metric_weight), "${signedWeight(r.weightControlKg, unit)} $label")
        KeyRow(colors.faint, stringResource(R.string.metric_fat_weight), "${signedWeight(r.fatControlKg, unit)} $label")
        KeyRow(colors.faint, stringResource(R.string.metric_muscle), "${signedWeight(r.muscleControlKg, unit)} $label")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(
    title: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.bodySmall,
        color = LocalAppColors.current.muted,
        modifier = Modifier.padding(top = 12.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, (label, selected) ->
            FilterChip(selected = selected, onClick = { onSelect(index) }, label = { Text(label) })
        }
    }
}

@Composable
private fun Notes(
    saved: WeightStatusEntity?,
    recordId: Long,
    onChange: ((WeightStatusEntity) -> WeightStatusEntity) -> Unit,
) {
    // The defaults are real codes (0 is "easy"), so an untouched reading must show nothing chosen.
    val status = saved ?: WeightStatusEntity(weightRecordId = recordId, exerciseIntensity = -1, defecationNum = -1)
    Section(title = stringResource(R.string.reading_notes)) {
        ChoiceRow(
            stringResource(R.string.record_mood),
            listOf(R.string.mood_1, R.string.mood_2, R.string.mood_3, R.string.mood_4, R.string.mood_5)
                .mapIndexed { i, res -> stringResource(res) to (status.mood == i + 1) },
        ) { i -> onChange { s -> s.copy(mood = if (s.mood == i + 1) 0 else i + 1) } }
        val intensities = listOf(
            StatusRepository.EXERCISE_EASY to R.string.exercise_easy,
            StatusRepository.EXERCISE_SOFT to R.string.exercise_soft,
            StatusRepository.EXERCISE_FIERCE to R.string.exercise_fierce,
        )
        ChoiceRow(
            stringResource(R.string.record_exercise),
            intensities.map { (code, res) -> stringResource(res) to (status.exerciseIntensity == code) },
        ) { i -> onChange { s -> s.copy(exerciseIntensity = intensities[i].first) } }
        ChoiceRow(
            stringResource(R.string.record_defecation),
            (0..3).map { "$it" to (status.defecationNum == it) },
        ) { i -> onChange { s -> s.copy(defecationNum = i) } }
        val specials = listOf(
            SPECIAL_MENSTRUATION to R.string.special_menstruation,
            SPECIAL_MEDICATION to R.string.special_medication,
            SPECIAL_POOR_SLEEP to R.string.special_poor_sleep,
        )
        ChoiceRow(
            stringResource(R.string.record_special),
            specials.map { (key, res) -> stringResource(res) to (status.specialCase == key) },
        ) { i -> onChange { s -> s.copy(specialCase = specials[i].first.takeIf { it != s.specialCase }) } }
        var remark by rememberSaveable(status.weightRecordId, status.remark) { mutableStateOf(status.remark.orEmpty()) }
        OutlinedTextField(
            value = remark,
            onValueChange = { remark = it },
            label = { Text(stringResource(R.string.record_remark)) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        if (remark != status.remark.orEmpty()) {
            TextButton(
                onClick = { onChange { s -> s.copy(remark = remark.ifBlank { null }) } },
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun Details(record: WeightRecordEntity) {
    val colors = LocalAppColors.current
    Section(title = stringResource(R.string.reading_details)) {
        val source = stringResource(
            if (record.source == RecordSource.OFFLINE_HISTORY) R.string.record_source_offline else R.string.record_source_live,
        )
        KeyRow(colors.faint, stringResource(R.string.reading_source), source)
        if (record.impedanceOhm > 0.0) {
            KeyRow(colors.faint, stringResource(R.string.record_impedance), "${record.impedanceOhm.toInt()} Ω")
        }
        KeyRow(colors.faint, stringResource(R.string.reading_recorded_height), "${record.heightCm.toInt()} cm")
        KeyRow(colors.faint, stringResource(R.string.reading_recorded_age), "${record.ageYears}")
    }
}
