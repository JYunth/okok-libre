package app.okoklibre.ui.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.viewModelScope
import app.okoklibre.R
import app.okoklibre.data.Backup
import app.okoklibre.data.BackupCodec
import app.okoklibre.data.CsvExporter
import app.okoklibre.data.RoleType
import app.okoklibre.data.InvalidBackupException
import app.okoklibre.data.matchSpreadsheetOwners
import app.okoklibre.data.sameLocalPerson
import app.okoklibre.ui.AppState
import app.okoklibre.ui.LocalAppState
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.DestructiveAction
import app.okoklibre.ui.design.ListRow
import app.okoklibre.ui.design.Section
import app.okoklibre.ui.design.SectionGap
import app.okoklibre.ui.theme.LocalAppColors
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CSV_TYPE = "text/csv"
private const val PEOPLE_FILE = "okok-people.csv"
private const val EVERYONE_READINGS_FILE = "okok-everyone-readings.csv"
private val BACKUP_TYPES = arrayOf("application/json", "text/*", "application/octet-stream")
private val SPREADSHEET_TYPES = arrayOf("text/*", "text/comma-separated-values", "application/octet-stream")

/**
 * The file work. It sits here so a rotation can't lose a result, and each job runs to its
 * end even if the person leaves: a restore stopped half way is worse than an unseen message.
 */
class DataViewModel(private val appState: AppState, private val app: Application) : ViewModel() {
    private val _jobs = MutableStateFlow(0)
    val jobs: StateFlow<Int> = _jobs.asStateFlow()

    private val _pendingRestore = MutableStateFlow<Backup?>(null)

    /** A backup that read cleanly and is waiting for the person's yes. */
    val pendingRestore: StateFlow<Backup?> = _pendingRestore.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** A channel, because a result that lands mid-rotation still has to be shown. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun saveBackup(uri: Uri) = launchJob(R.string.data_backup_save_failed) {
        val snapshot = appState.backupRepository.snapshot()
        // A reading deleted since launch keeps its notes until the next purge. Left in, they
        // point at a reading the file doesn't hold, and validation refuses the whole backup.
        val kept = snapshot.records.mapTo(HashSet()) { it.id }
        val backup = snapshot.copy(statuses = snapshot.statuses.filter { it.weightRecordId in kept })
        checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { BackupCodec.write(backup, it) }
        app.getString(R.string.data_backup_saved)
    }

    fun openBackup(uri: Uri) = launchJob(R.string.data_open_failed) {
        _pendingRestore.value = checkNotNull(app.contentResolver.openInputStream(uri)).use { BackupCodec.read(it) }
        null
    }

    fun dismissRestore() {
        _pendingRestore.value = null
    }

    fun confirmRestore() {
        val backup = _pendingRestore.value ?: return
        _pendingRestore.value = null
        launchJob(R.string.data_restore_failed) {
            val replaced = appState.reminderRepository.allReminders()
            appState.backupRepository.restore(backup)
            // An alarm outlives its row, and could ring for a reminder the backup has switched off.
            replaced.forEach { appState.reminderScheduler.cancel(it.id) }
            appState.reminderScheduler.scheduleAll(appState.reminderRepository.enabledReminders())
            app.getString(R.string.data_restored)
        }
    }

    fun exportPerson(uri: Uri) = launchJob(R.string.data_export_failed) {
        val role = checkNotNull(appState.profileSession.activeRoleNow())
        val records = appState.weightRepository.records(role.id)
        CsvExporter.exportRecords(app.contentResolver, uri, records)
        counted(R.plurals.data_saved_readings, records.size)
    }

    fun exportPeople(uri: Uri) = launchJob(R.string.data_export_failed) {
        val roles = appState.roleRepository.allRoles()
        CsvExporter.exportRoles(app.contentResolver, uri, roles)
        counted(R.plurals.data_saved_people, roles.size)
    }

    fun exportEveryonesReadings(uri: Uri) = launchJob(R.string.data_export_failed) {
        val records = appState.roleRepository.allRoles().flatMap { appState.weightRepository.records(it.id) }
        CsvExporter.exportRecords(app.contentResolver, uri, records)
        counted(R.plurals.data_saved_readings, records.size)
    }

    /** The two spreadsheets have different columns, so the header says which one this is. */
    fun importSpreadsheet(uri: Uri) = launchJob(R.string.data_import_failed) {
        val bytes = checkNotNull(app.contentResolver.openInputStream(uri)).use { it.readBytes() }
        val text = String(bytes, Charsets.UTF_8)
        when (text.lineSequence().firstOrNull()?.removePrefix("\uFEFF")?.trim()) {
            CsvExporter.ROLE_HEADER -> importPeople(text)
            CsvExporter.RECORD_HEADER -> importReadings(text)
            else -> app.getString(R.string.data_import_not_recognised)
        }
    }

    /** Existing people are matched by name and birthday. An id from another phone proves nothing. */
    private suspend fun importPeople(text: String): String {
        val result = CsvExporter.importRoles(text.byteInputStream(Charsets.UTF_8))
        val roles = appState.roleRepository
        result.rows.forEach { row ->
            val existing = sameLocalPerson(row, roles.allRoles())
            if (existing != null) {
                roles.updateRole(existing.copy(sex = row.sex, heightCm = row.heightCm))
            } else {
                roles.createRole(row.nickname, row.sex, row.birthdayEpochDay, row.heightCm, RoleType.MEMBER)
            }
        }
        return importSummary(
            counted(R.plurals.data_imported_people, result.rows.size),
            R.plurals.data_skipped_people,
            result.errors.size,
        )
    }

    /** Rows no profile fits are skipped, and readings already here are left exactly as they are. */
    private suspend fun importReadings(text: String): String {
        val result = CsvExporter.importRecords(text.byteInputStream(Charsets.UTF_8))
        val zone = ZoneId.systemDefault()
        val owners = matchSpreadsheetOwners(result.rows, appState.roleRepository.allRoles(), zone)
        var imported = 0
        var skipped = result.errors.size
        result.rows.forEach { record ->
            val owner = owners[record.roleId]
            if (owner != null && appState.weightRepository.insertIfNew(record.copy(roleId = owner.id), zone)) {
                imported++
            } else {
                skipped++
            }
        }
        return importSummary(
            counted(R.plurals.data_imported_readings, imported),
            R.plurals.data_skipped_readings,
            skipped,
        )
    }

    private fun importSummary(done: String, skippedPlural: Int, skipped: Int): String =
        if (skipped == 0) done else app.resources.getQuantityString(skippedPlural, skipped, done, skipped)

    private fun counted(plural: Int, count: Int): String = app.resources.getQuantityString(plural, count, count)

    private fun launchJob(failure: Int, work: suspend () -> String?) {
        viewModelScope.launch {
            _jobs.update { it + 1 }
            val message = try {
                withContext(NonCancellable + Dispatchers.IO) { work() }
            } catch (e: InvalidBackupException) {
                e.message
            } catch (e: Exception) {
                app.getString(failure)
            }
            _jobs.update { it - 1 }
            message?.let { _messages.trySend(it) }
        }
    }
}

/** Saving and restoring everything, and spreadsheets for reading the numbers elsewhere. */
@Composable
fun DataScreen(onBack: () -> Unit) {
    val appState = LocalAppState.current
    val application = LocalContext.current.applicationContext as Application
    val vm: DataViewModel = appViewModel { DataViewModel(it, application) }
    val person by appState.profileSession.activeRole.collectAsStateWithLifecycle(null)
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = LocalAppColors.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noPicker = stringResource(R.string.data_no_picker)
    val idle = jobs == 0

    LaunchedEffect(lifecycle) {
        // Held back while a file picker covers the screen, so a failure can't time out unseen.
        vm.messages.flowWithLifecycle(lifecycle).collect {
            snackbars.showSnackbar(it, duration = SnackbarDuration.Long)
        }
    }

    fun pick(open: () -> Unit) {
        try {
            open()
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbars.showSnackbar(noPicker, duration = SnackbarDuration.Long) }
        }
    }

    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::saveBackup) }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::openBackup) }
    val exportPerson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CSV_TYPE),
    ) { uri -> uri?.let(vm::exportPerson) }
    val exportEveryonesReadings = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CSV_TYPE),
    ) { uri -> uri?.let(vm::exportEveryonesReadings) }
    // People and readings have different columns, so everyone takes two files, asked for in turn.
    val exportPeople = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CSV_TYPE),
    ) { uri ->
        if (uri != null) {
            vm.exportPeople(uri)
            pick { exportEveryonesReadings.launch(EVERYONE_READINGS_FILE) }
        }
    }
    val importSpreadsheet = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importSpreadsheet) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppBar(title = stringResource(R.string.you_data), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Section(
                    title = stringResource(R.string.data_section_backup),
                    caption = stringResource(R.string.data_backup_caption),
                ) {
                    ActionRow(stringResource(R.string.data_save_backup), idle) {
                        pick { saveBackup.launch("okok-libre-backup-${LocalDate.now()}.json") }
                    }
                }
                Spacer(Modifier.height(SectionGap))
                Section(title = stringResource(R.string.data_section_spreadsheet)) {
                    person?.let { role ->
                        ActionRow(stringResource(R.string.data_export_person, role.nickname), idle) {
                            val name = fileSafe(role.nickname).ifEmpty { "${role.id}" }
                            pick { exportPerson.launch("okok-$name-readings.csv") }
                        }
                    }
                    ActionRow(
                        stringResource(R.string.data_export_everyone),
                        idle,
                        subtitle = stringResource(R.string.data_export_everyone_hint),
                    ) { pick { exportPeople.launch(PEOPLE_FILE) } }
                    ActionRow(
                        stringResource(R.string.data_import_spreadsheet),
                        idle,
                        subtitle = stringResource(R.string.data_import_hint),
                    ) { pick { importSpreadsheet.launch(SPREADSHEET_TYPES) } }
                }
                DestructiveAction(
                    stringResource(R.string.data_restore_backup),
                    onClick = { if (idle) pick { openBackup.launch(BACKUP_TYPES) } },
                )
            }
            if (!idle) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp),
                    color = colors.ink,
                    trackColor = colors.hairline,
                )
            }
        }
    }

    pendingRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = vm::dismissRestore,
            title = { Text(stringResource(R.string.data_restore_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.data_restore_body,
                        pluralStringResource(R.plurals.data_people_count, backup.roles.size, backup.roles.size),
                        pluralStringResource(R.plurals.data_readings_count, backup.records.size, backup.records.size),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmRestore) {
                    Text(stringResource(R.string.data_restore_confirm), color = colors.attention)
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissRestore) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ActionRow(title: String, enabled: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick.takeIf { enabled },
        padding = PaddingValues(vertical = 10.dp),
    )
}

/** A nickname as a file name can carry it: letters and digits stay, the rest become dashes. */
private fun fileSafe(name: String): String = name.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
