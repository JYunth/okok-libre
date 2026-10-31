package app.okoklibre.ui.reminders

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.okoklibre.R
import app.okoklibre.data.ActiveProfile
import app.okoklibre.data.ReminderEntity
import app.okoklibre.ui.LocalAppState
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.DestructiveAction
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.PrimaryButton
import app.okoklibre.ui.design.Section
import app.okoklibre.ui.design.SectionGap
import app.okoklibre.ui.theme.LocalAppColors
import java.time.DayOfWeek
import kotlinx.coroutines.launch

private const val DEFAULT_MINUTES_OF_DAY = 8 * 60

/**
 * Reminder editor ([reminderId] -1 = new). Saves through ReminderRepository
 * and (re-)arms the alarm via ReminderScheduler.
 */
@Composable
fun ReminderEditScreen(reminderId: Long, onDone: () -> Unit) {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()
    val active by appState.profileSession.active.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    val isNew = reminderId < 0

    var existing by remember { mutableStateOf<ReminderEntity?>(null) }
    var loaded by remember { mutableStateOf(isNew) }
    LaunchedEffect(reminderId) {
        if (!isNew) {
            existing = appState.reminderRepository.reminder(reminderId)
            loaded = true
        }
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(
                stringResource(if (isNew) R.string.reminder_add else R.string.reminder_edit_title),
                onDone,
            )
        },
    ) { padding ->
        val current = existing
        // An edit stays with its owner; only a new reminder takes the active person.
        val ownerId = current?.roleId ?: (active as? ActiveProfile.Selected)?.role?.id
        when {
            !loaded || active == ActiveProfile.Loading -> Unit
            !isNew && current == null -> EmptyState(
                stringResource(R.string.reminder_gone_title),
                stringResource(R.string.reminder_gone_body),
                Modifier.padding(padding),
            )
            ownerId == null -> EmptyState(
                stringResource(R.string.goal_no_role),
                null,
                Modifier.padding(padding),
            )
            else -> ReminderForm(
                existing = current,
                ownerId = ownerId,
                onDone = onDone,
                onDelete = { confirmDelete = true },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.reminder_delete_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        existing?.let {
                            appState.reminderRepository.delete(it)
                            appState.reminderScheduler.cancel(it.id)
                        }
                        onDone()
                    }
                }) { Text(stringResource(R.string.delete), color = colors.attention) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Composed only once [existing] is known: the time picker keeps the first time it's given. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReminderForm(
    existing: ReminderEntity?,
    ownerId: Long,
    onDone: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appState = LocalAppState.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val minutes = existing?.timeMinutesOfDay ?: DEFAULT_MINUTES_OF_DAY
    val timeState = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60)
    var days by rememberSaveable { mutableStateOf(existing?.daysOfWeek() ?: setOf(DayOfWeek.MONDAY)) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun save() {
        saving = true
        scope.launch {
            val savedId = appState.reminderRepository.save(
                ReminderEntity(
                    id = existing?.id ?: 0L,
                    roleId = ownerId,
                    timeMinutesOfDay = timeState.hour * 60 + timeState.minute,
                    enabled = true,
                    mon = DayOfWeek.MONDAY in days,
                    tue = DayOfWeek.TUESDAY in days,
                    wed = DayOfWeek.WEDNESDAY in days,
                    thu = DayOfWeek.THURSDAY in days,
                    fri = DayOfWeek.FRIDAY in days,
                    sat = DayOfWeek.SATURDAY in days,
                    sun = DayOfWeek.SUNDAY in days,
                ),
            )
            appState.reminderRepository.reminder(savedId)?.let {
                appState.reminderScheduler.schedule(it)
            }
            onDone()
        }
    }

    // Saves whatever the answer: a refusal only stops the alert, and the list explains that.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { save() }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Gutter), contentAlignment = Alignment.Center) {
            // The picker sets its digits in displayLarge, which here is tuned for the big readout.
            MaterialTheme(
                typography = MaterialTheme.typography.copy(
                    displayLarge = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 56.sp,
                        lineHeight = 64.sp,
                        letterSpacing = 0.sp,
                    ),
                ),
            ) {
                TimePicker(state = timeState)
            }
        }
        Spacer(Modifier.height(SectionGap))
        Section(title = stringResource(R.string.reminder_every_week)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in days,
                        onClick = { days = if (day in days) days - day else days + day },
                        label = { Text(stringResource(dayLabel(day))) },
                    )
                }
            }
            if (attempted && days.isEmpty()) {
                Text(
                    stringResource(R.string.reminder_need_day),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalAppColors.current.attention,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(SectionGap))
        PrimaryButton(
            stringResource(R.string.save),
            busy = saving,
            modifier = Modifier.padding(horizontal = Gutter),
            onClick = {
                attempted = true
                if (days.isNotEmpty()) {
                    if (needsNotificationPermission(context)) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        save()
                    }
                }
            },
        )
        if (existing != null) {
            DestructiveAction(stringResource(R.string.reminder_delete), onClick = onDelete)
        }
    }
}
