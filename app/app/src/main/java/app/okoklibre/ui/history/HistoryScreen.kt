package app.okoklibre.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.okoklibre.R
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.WeightRecordEntity
import app.okoklibre.insights.BodyState
import app.okoklibre.insights.hasComposition
import app.okoklibre.ui.Routes
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.clockTime
import app.okoklibre.ui.common.formatRecordWeightKg
import app.okoklibre.ui.common.parseWeightToKg
import app.okoklibre.ui.common.shortDate
import app.okoklibre.ui.common.signedWeight
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.Hairline
import app.okoklibre.ui.design.OptionRow
import app.okoklibre.ui.design.PrimaryButton
import app.okoklibre.ui.theme.LocalAppColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: NavHostController) {
    val vm: HistoryViewModel = appViewModel { HistoryViewModel(it) }
    val body by vm.body.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val state = body ?: return
    val snackbars = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    val deletedText = stringResource(R.string.history_deleted)
    val undoText = stringResource(R.string.undo)

    LaunchedEffect(Unit) {
        vm.deleted.collect { deleted ->
            val result = snackbars.showSnackbar(deletedText, undoText, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) vm.undo(deleted)
        }
    }
    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            if (selection.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Gutter, end = 8.dp).padding(top = 20.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.tab_history),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.history_add_manual))
                    }
                }
                OptionRow(
                    options = HistoryView.entries,
                    selected = view,
                    label = {
                        stringResource(if (it == HistoryView.LIST) R.string.history_list else R.string.history_calendar)
                    },
                    onSelect = vm::setView,
                    modifier = Modifier.padding(horizontal = Gutter - 4.dp),
                )
            } else {
                SelectionBar(
                    count = selection.size,
                    onClose = vm::clearSelection,
                    onDelete = vm::deleteSelected,
                    onCompare = selection.takeIf { it.size == 2 }?.let { ids ->
                        {
                            val (a, b) = ids.toList()
                            vm.clearSelection()
                            nav.navigate(Routes.compare(a, b))
                        }
                    },
                )
            }
            when {
                state.records.isEmpty() -> Column {
                    if (pending > 0) PendingRow(pending) { nav.navigate(Routes.TMP_WEIGHTS) }
                    EmptyState(
                        stringResource(R.string.history_empty_title),
                        stringResource(R.string.history_empty_body),
                    )
                }
                view == HistoryView.CALENDAR -> CalendarView(
                    state = state,
                    month = month,
                    selectedDay = selectedDay,
                    onShift = vm::shiftMonth,
                    onSelectDay = vm::selectDay,
                    onOpen = { nav.navigate(Routes.reading(it)) },
                )
                else -> ReadingList(
                    state = state,
                    pending = pending,
                    selection = selection,
                    onOpen = { nav.navigate(Routes.reading(it)) },
                    onToggle = vm::toggle,
                    onOpenPending = { nav.navigate(Routes.TMP_WEIGHTS) },
                )
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }

    if (adding) {
        AddReadingSheet(
            unit = state.unit,
            onDismiss = { adding = false },
            onAdd = { kg, ts ->
                adding = false
                vm.addManual(kg, ts)
            },
        )
    }
}

@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onDelete: () -> Unit, onCompare: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 16.dp, bottom = 8.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
        Text(
            stringResource(R.string.history_selected_count, count),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (onCompare != null) {
            TextButton(onClick = onCompare) { Text(stringResource(R.string.compare_title)) }
        }
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.delete), color = LocalAppColors.current.attention)
        }
    }
}

@Composable
private fun PendingRow(pending: Int, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Gutter, vertical = 14.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.today_pending, pending, pending),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.today_pending_hint),
            style = MaterialTheme.typography.bodySmall,
            color = LocalAppColors.current.muted,
        )
    }
    Hairline(Modifier.padding(horizontal = Gutter))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadingList(
    state: BodyState,
    pending: Int,
    selection: Set<Long>,
    onOpen: (Long) -> Unit,
    onToggle: (Long) -> Unit,
    onOpenPending: () -> Unit,
) {
    val colors = LocalAppColors.current
    val formatter = remember { DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()) }
    val groups = remember(state.records) {
        val previous = state.records.drop(1) + (null as WeightRecordEntity?)
        state.records.zip(previous).groupBy { (record, _) -> YearMonth.from(HistoryViewModel.dayOf(record)) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (pending > 0) item { PendingRow(pending, onOpenPending) }
        groups.forEach { (month, rows) ->
            item(key = "m$month") {
                Text(
                    month.format(formatter),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.muted,
                    modifier = Modifier.padding(horizontal = Gutter).padding(top = 20.dp, bottom = 4.dp),
                )
            }
            items(rows, key = { (record, _) -> record.id }) { (record, previous) ->
                val selected = record.id in selection
                val background by animateColorAsState(if (selected) colors.raised else Color.Transparent, label = "row")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(background)
                        .combinedClickable(
                            onClick = { if (selection.isNotEmpty()) onToggle(record.id) else onOpen(record.id) },
                            onLongClick = { onToggle(record.id) },
                        )
                        .heightIn(min = 60.dp)
                        .padding(horizontal = Gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            shortDate(HistoryViewModel.dayOf(record).toEpochDay()),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            buildString {
                                append(clockTime(record.timestampEpochSec))
                                if (record.hasComposition) append(" · ${"%.1f".format(record.bodyFatPct)}% fat")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${formatRecordWeightKg(record.weightKg, state.unit, record.scaleProperty)} ${weightUnitLabel(state.unit)}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (previous != null) {
                            Text(
                                signedWeight(record.weightKg - previous.weightKg, state.unit),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarView(
    state: BodyState,
    month: YearMonth,
    selectedDay: Long?,
    onShift: (Long) -> Unit,
    onSelectDay: (Long) -> Unit,
    onOpen: (Long) -> Unit,
) {
    val colors = LocalAppColors.current
    val byDay = remember(state.records) {
        state.records.groupBy { HistoryViewModel.dayOf(it).toEpochDay() }
    }
    val formatter = remember { DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()) }
    val first = month.atDay(1)
    val leading = first.dayOfWeek.value - 1
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onShift(-1) }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.history_prev_month))
                }
                Text(
                    month.format(formatter),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onShift(1) }, enabled = month < YearMonth.now()) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.history_next_month))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 4.dp, bottom = 4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    Text(
                        day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val cells = (List(leading) { null } + (1..month.lengthOfMonth()).toList())
            Column(Modifier.padding(horizontal = Gutter)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (day != null) {
                                    val epochDay = month.atDay(day).toEpochDay()
                                    val hasReading = epochDay in byDay
                                    val isSelected = epochDay == selectedDay
                                    Box(
                                        Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) colors.accent else Color.Transparent)
                                            .then(
                                                if (hasReading) {
                                                    Modifier.clickable { onSelectDay(epochDay) }
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "$day",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = when {
                                                isSelected -> colors.onAccent
                                                hasReading -> colors.ink
                                                else -> colors.faint
                                            },
                                        )
                                        if (hasReading && !isSelected) {
                                            Box(
                                                Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .padding(bottom = 4.dp)
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(colors.accent),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        repeat(7 - week.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        val readings = selectedDay?.let { byDay[it] }.orEmpty()
        items(readings, key = { it.id }) { record ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(record.id) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = Gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(clockTime(record.timestampEpochSec), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    "${formatRecordWeightKg(record.weightKg, state.unit, record.scaleProperty)} ${weightUnitLabel(state.unit)}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReadingSheet(unit: WeightUnit, onDismiss: () -> Unit, onAdd: (Double, Long) -> Unit) {
    val colors = LocalAppColors.current
    val zone = ZoneId.systemDefault()
    var text by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now(zone).toEpochDay()) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val kg = parseWeightToKg(text.replace(',', '.'), unit)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.ground) {
        Column(
            Modifier.navigationBarsPadding().imePadding().padding(horizontal = Gutter).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.history_add_manual), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.history_add_weight)) },
                suffix = { Text(weightUnitLabel(unit)) },
                singleLine = true,
                // A number pad has no colon, and st:lb can't be typed without one.
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (unit == WeightUnit.ST_LB) KeyboardType.Text else KeyboardType.Decimal,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { pickingDate = true }) { Text(shortDate(date)) }
            PrimaryButton(
                stringResource(R.string.save),
                enabled = kg != null,
                onClick = {
                    val time = if (date == LocalDate.now(zone).toEpochDay()) LocalTime.now(zone) else LocalTime.of(8, 0)
                    kg?.let { onAdd(it, LocalDate.ofEpochDay(date).atTime(time).atZone(zone).toEpochSecond()) }
                },
            )
        }
    }

    if (pickingDate) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = remember {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) =
                        utcTimeMillis / 86_400_000L <= LocalDate.now(zone).toEpochDay()
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { date = it / 86_400_000L }
                    pickingDate = false
                }) { Text(stringResource(R.string.done)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) { DatePicker(picker) }
    }
}
