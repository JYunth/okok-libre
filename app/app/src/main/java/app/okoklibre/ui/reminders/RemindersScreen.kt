package app.okoklibre.ui.reminders

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.okoklibre.R
import app.okoklibre.data.ActiveProfile
import app.okoklibre.data.ReminderEntity
import app.okoklibre.ui.AppState
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.Hairline
import app.okoklibre.ui.design.ListRow
import app.okoklibre.ui.theme.LocalAppColors
import java.time.DayOfWeek
import java.util.Calendar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RemindersViewModel(private val appState: AppState) : ViewModel() {
    val active: StateFlow<ActiveProfile> = appState.profileSession.active

    /** The active person's reminders; null until read, or when there is nobody to own them. */
    val reminders: StateFlow<List<ReminderEntity>?> = appState.profileSession
        .perProfile<List<ReminderEntity>?>(null) { appState.reminderRepository.remindersFor(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setEnabled(reminder: ReminderEntity, enabled: Boolean) {
        viewModelScope.launch {
            appState.reminderRepository.setEnabled(reminder.id, enabled)
            val updated = reminder.copy(enabled = enabled)
            if (enabled) {
                appState.reminderScheduler.schedule(updated)
            } else {
                appState.reminderScheduler.cancel(reminder.id)
            }
        }
    }
}

@Composable
fun RemindersScreen(onEdit: (Long) -> Unit, onBack: () -> Unit) {
    val viewModel: RemindersViewModel = appViewModel { RemindersViewModel(it) }
    val active by viewModel.active.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val role = (active as? ActiveProfile.Selected)?.role

    var notificationsOn by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsOn = notificationsAllowed(context) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsOn = notificationsAllowed(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(
                title = role?.let { stringResource(R.string.reminders_for_person, it.nickname) }
                    ?: stringResource(R.string.reminders_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        val list = reminders
        when {
            active == ActiveProfile.None -> EmptyState(
                stringResource(R.string.goal_no_role),
                null,
                Modifier.padding(padding),
            )
            role == null || list == null -> Unit
            list.isEmpty() -> EmptyState(
                stringResource(R.string.reminders_empty_title, role.nickname),
                stringResource(R.string.reminders_empty_body),
                Modifier.padding(padding),
                action = stringResource(R.string.reminder_add),
                onAction = { onEdit(-1L) },
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (!notificationsOn && list.any { it.enabled }) {
                    item(key = "notifications") {
                        ListRow(
                            title = stringResource(R.string.reminders_notifications_off),
                            subtitle = stringResource(R.string.reminder_notification_permission),
                            value = stringResource(R.string.app_settings_open),
                            valueColor = colors.accent,
                            onClick = { context.startActivity(notificationSettings(context)) },
                        )
                        Hairline(Modifier.padding(horizontal = Gutter))
                    }
                }
                items(list, key = { it.id }) { reminder ->
                    ReminderRow(
                        reminder = reminder,
                        onClick = { onEdit(reminder.id) },
                        onToggle = { enabled ->
                            if (enabled && needsNotificationPermission(context)) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.setEnabled(reminder, enabled)
                        },
                    )
                }
                item(key = "add") {
                    ListRow(
                        title = stringResource(R.string.reminder_add),
                        leading = { Icon(Icons.Outlined.Add, null, tint = colors.muted) },
                        onClick = { onEdit(-1L) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderRow(
    reminder: ReminderEntity,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val time = clockLabel(reminder.timeMinutesOfDay)
    ListRow(
        title = time,
        subtitle = weekdaySummary(reminder),
        trailing = {
            Switch(
                checked = reminder.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = time },
            )
        },
        onClick = onClick,
    )
}

/** In the phone's own 12 or 24 hour style, so the list agrees with the time picker. */
@Composable
private fun clockLabel(minutesOfDay: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minutesOfDay / 60)
        set(Calendar.MINUTE, minutesOfDay % 60)
    }
    return DateFormat.getTimeFormat(LocalContext.current).format(calendar.time)
}

@Composable
internal fun weekdaySummary(reminder: ReminderEntity): String {
    val days = reminder.daysOfWeek()
    val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    return when {
        days.isEmpty() -> stringResource(R.string.reminder_need_day)
        days.size == DayOfWeek.entries.size -> stringResource(R.string.reminders_every_day)
        days == weekend -> stringResource(R.string.reminders_weekends)
        days.size == 5 && days.none { it in weekend } -> stringResource(R.string.reminders_weekdays)
        else -> DayOfWeek.entries.filter { it in days }.map { stringResource(dayLabel(it)) }.joinToString(", ")
    }
}

internal fun dayLabel(day: DayOfWeek): Int = when (day) {
    DayOfWeek.MONDAY -> R.string.day_mon
    DayOfWeek.TUESDAY -> R.string.day_tue
    DayOfWeek.WEDNESDAY -> R.string.day_wed
    DayOfWeek.THURSDAY -> R.string.day_thu
    DayOfWeek.FRIDAY -> R.string.day_fri
    DayOfWeek.SATURDAY -> R.string.day_sat
    DayOfWeek.SUNDAY -> R.string.day_sun
}

/** No need to remember having asked: Android stops prompting once the person has refused. */
internal fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

private fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun notificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
