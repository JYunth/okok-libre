package app.okoklibre.ui.family

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.okoklibre.R
import app.okoklibre.algo.Sex
import app.okoklibre.algo.UnitConverter
import app.okoklibre.data.LengthUnit
import app.okoklibre.data.RoleEntity
import app.okoklibre.data.RoleType
import app.okoklibre.ui.AppState
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.formatEpochDay
import app.okoklibre.ui.common.parseHeightToCm
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.Avatar
import app.okoklibre.ui.design.DestructiveAction
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.ListRow
import app.okoklibre.ui.design.OptionRow
import app.okoklibre.ui.design.PrimaryButton
import app.okoklibre.ui.design.SectionGap
import app.okoklibre.ui.theme.LocalAppColors
import app.okoklibre.ui.theme.PersonColorCount
import app.okoklibre.ui.theme.personColor
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NAME_MAX_LENGTH = 30
private const val EARLIEST_BIRTH_YEAR = 1900
private const val MILLIS_PER_DAY = 86_400_000L
private val DEFAULT_BIRTHDAY_MONTH: LocalDate = LocalDate.of(1995, 1, 1)

/** The preview's file id: never a real role, so backing out can't touch anyone's actual photo. */
private const val DRAFT_PHOTO_ID = -1L

/**
 * Height as typed. [cm] only moves on an edit, so switching units shows the same height
 * back instead of losing a centimetre to each whole-inch round trip.
 */
data class HeightEntry(
    val cm: Int? = null,
    val cmText: String = "",
    val feetText: String = "",
    val inchesText: String = "",
) {
    fun typed(
        unit: LengthUnit,
        cmText: String = this.cmText,
        feetText: String = this.feetText,
        inchesText: String = this.inchesText,
    ) = HeightEntry(parseHeightToCm(cmText, feetText, inchesText, unit), cmText, feetText, inchesText)

    /** The same height, ready to show in another unit. */
    fun refilled(): HeightEntry = cm?.let { of(it) } ?: HeightEntry()

    companion object {
        /** [UnitConverter.cmToFeetInches] can round up to 12 inches, which [parseHeightToCm] rejects. */
        fun of(cm: Int): HeightEntry {
            val (feet, inches) = UnitConverter.cmToFeetInches(cm.toDouble())
            val carried = inches == 12
            return HeightEntry(cm, "$cm", "${if (carried) feet + 1 else feet}", "${if (carried) 0 else inches}")
        }
    }
}

/**
 * The details every person has, as typed. Sex starts unset on purpose: it drives every
 * composition formula, so a default nobody looked at would quietly skew all their figures.
 */
data class PersonForm(
    val name: String = "",
    val sex: Sex? = null,
    val birthdayEpochDay: Long? = null,
    val height: HeightEntry = HeightEntry(),
) {
    fun birthdayIsPast(): Boolean = birthdayEpochDay?.let { it < LocalDate.now().toEpochDay() } == true

    fun isComplete(): Boolean = name.isNotBlank() && sex != null && birthdayIsPast() && height.cm != null
}

/** A photo nobody has saved yet. [version] changes per pick because the preview's file name doesn't. */
data class PhotoDraft(val uri: Uri, val previewPath: String, val version: Int)

enum class RoleLoad { LOADING, READY, MISSING }

private val NewPerson = RoleEntity(
    nickname = "",
    sex = Sex.MALE,
    birthdayEpochDay = 0,
    heightCm = 0,
    roleType = RoleType.MEMBER,
    createdAtEpochSec = 0,
)

data class RoleEditUi(
    val load: RoleLoad = RoleLoad.LOADING,
    /** The stored person, or a stand-in for a new one. Its colour is the current pick. */
    val person: RoleEntity = NewPerson,
    val form: PersonForm = PersonForm(),
    val lengthUnit: LengthUnit = LengthUnit.CM,
    val photo: PhotoDraft? = null,
    val photoFailed: Boolean = false,
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
    /** How many readings would go with the person; set while the delete dialog is up. */
    val deleteReadings: Int? = null,
    val finished: Boolean = false,
)

class RoleEditViewModel(private val appState: AppState, private val roleId: Long) : ViewModel() {

    val isNew: Boolean = roleId < 0

    val roles: StateFlow<List<RoleEntity>> = appState.profileSession.roles

    /** Snapshot state rather than a StateFlow: a text field must get its value back within the frame. */
    var ui by mutableStateOf(RoleEditUi())
        private set

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val lengthUnit = appState.settingsRepository.lengthUnit.first()
        ui = if (isNew) {
            val taken = appState.roleRepository.allRoles().map { it.colorIndex }
            val color = (0 until PersonColorCount).firstOrNull { it !in taken } ?: 0
            ui.copy(load = RoleLoad.READY, person = NewPerson.copy(colorIndex = color), lengthUnit = lengthUnit)
        } else {
            val role = appState.roleRepository.role(roleId)
            if (role == null) {
                ui.copy(load = RoleLoad.MISSING)
            } else {
                ui.copy(
                    load = RoleLoad.READY,
                    person = role,
                    form = PersonForm(role.nickname, role.sex, role.birthdayEpochDay, HeightEntry.of(role.heightCm)),
                    lengthUnit = lengthUnit,
                )
            }
        }
    }

    fun edit(form: PersonForm) {
        ui = ui.copy(form = form)
    }

    fun pickColor(index: Int) {
        ui = ui.copy(person = ui.person.copy(colorIndex = index))
    }

    /** Previews the photo without saving it, so Back keeps the old one and a new person can see theirs. */
    fun pickPhoto(uri: Uri) {
        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) {
                runCatching { appState.avatarStore.import(DRAFT_PHOTO_ID, uri) }.getOrNull()
            }
            ui = if (preview != null) {
                ui.copy(photo = PhotoDraft(uri, preview, (ui.photo?.version ?: 0) + 1), photoFailed = false)
            } else {
                ui.copy(photoFailed = true)
            }
        }
    }

    fun save() {
        val current = ui
        if (current.saving) return
        if (!current.form.isComplete()) {
            ui = current.copy(showErrors = true)
            return
        }
        ui = current.copy(saving = true, failed = false)
        viewModelScope.launch {
            val saved = runCatching { store(current) }
            ui = ui.copy(saving = false, failed = saved.isFailure, finished = saved.isSuccess)
        }
    }

    private suspend fun store(current: RoleEditUi) {
        val repository = appState.roleRepository
        val name = current.form.name.trim()
        val sex = current.form.sex ?: return
        val birthday = current.form.birthdayEpochDay ?: return
        val height = current.form.height.cm ?: return
        // A photo is filed under the role's id, so a new person has to exist before it can land.
        val id = if (isNew) repository.createRole(name, sex, birthday, height, RoleType.MEMBER) else roleId
        val photoPath = current.photo?.let { draft ->
            withContext(Dispatchers.IO) { runCatching { appState.avatarStore.import(id, draft.uri) }.getOrNull() }
        }
        val stored = checkNotNull(repository.role(id))
        repository.updateRole(
            stored.copy(
                nickname = name,
                sex = sex,
                birthdayEpochDay = birthday,
                heightCm = height,
                colorIndex = current.person.colorIndex,
                avatarPath = photoPath ?: stored.avatarPath,
            ),
        )
        if (isNew) appState.profileSession.switchTo(id)
    }

    fun askDelete() {
        viewModelScope.launch {
            ui = ui.copy(deleteReadings = appState.weightRepository.recordCount(roleId))
        }
    }

    fun dismissDelete() {
        ui = ui.copy(deleteReadings = null)
    }

    fun delete() {
        if (ui.saving || roles.value.size <= 1) return
        ui = ui.copy(saving = true, failed = false, deleteReadings = null)
        viewModelScope.launch {
            val deleted = runCatching {
                appState.roleRepository.role(roleId)?.let { appState.deleteProfile(it) }
            }
            ui = ui.copy(saving = false, failed = deleted.isFailure, finished = deleted.isSuccess)
        }
    }

    override fun onCleared() {
        ui.photo?.let { File(it.previewPath).delete() }
    }
}

/** One person's details ([roleId] -1 = someone new, who becomes active once saved). */
@Composable
fun RoleEditScreen(roleId: Long, onDone: () -> Unit) {
    val vm: RoleEditViewModel = appViewModel(key = "role-$roleId") { RoleEditViewModel(it, roleId) }
    val roles by vm.roles.collectAsStateWithLifecycle()
    val ui = vm.ui
    val colors = LocalAppColors.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::pickPhoto)
    }

    if (ui.finished) LaunchedEffect(Unit) { onDone() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(
                title = stringResource(if (vm.isNew) R.string.people_add else R.string.person_edit_title),
                onBack = onDone,
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
        when (ui.load) {
            RoleLoad.LOADING -> Unit
            RoleLoad.MISSING -> EmptyState(
                stringResource(R.string.person_missing_title),
                stringResource(R.string.person_missing_body),
                modifier,
            )
            RoleLoad.READY -> Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                val preview = ui.person.copy(
                    nickname = ui.form.name,
                    avatarPath = ui.photo?.previewPath ?: ui.person.avatarPath,
                )
                ListRow(
                    title = stringResource(
                        if (preview.avatarPath != null) R.string.person_photo_change else R.string.person_photo_add,
                    ),
                    // Avatar remembers by path and the preview's path repeats, so a new pick needs a fresh slot.
                    leading = { key(ui.photo?.version) { Avatar(preview, 56.dp) } },
                    onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                if (ui.photoFailed) {
                    ErrorLine(stringResource(R.string.person_photo_failed), Modifier.padding(horizontal = Gutter))
                }
                Spacer(Modifier.height(16.dp))
                PersonFields(
                    form = ui.form,
                    onChange = vm::edit,
                    lengthUnit = ui.lengthUnit,
                    showErrors = ui.showErrors,
                )
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
                    FieldLabel(stringResource(R.string.person_color))
                    ColorSwatches(selected = ui.person.colorIndex, onSelect = vm::pickColor)
                    if (ui.failed) ErrorLine(stringResource(R.string.person_save_failed))
                    Spacer(Modifier.height(SectionGap))
                    PrimaryButton(stringResource(R.string.save), onClick = vm::save, busy = ui.saving)
                }
                if (!vm.isNew && roles.size > 1) {
                    DestructiveAction(stringResource(R.string.person_delete), onClick = vm::askDelete)
                } else {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    ui.deleteReadings?.let { readings ->
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text(stringResource(R.string.family_delete_title, ui.person.nickname)) },
            text = {
                Text(
                    if (readings > 0) {
                        pluralStringResource(R.plurals.person_delete_readings, readings, readings)
                    } else {
                        stringResource(R.string.person_delete_no_readings)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = vm::delete) { Text(stringResource(R.string.delete), color = colors.attention) }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDelete) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
fun lengthUnitLabel(unit: LengthUnit): String =
    stringResource(if (unit == LengthUnit.CM) R.string.unit_cm else R.string.unit_ft_in)

/**
 * Name, sex, birthday and height. [onLengthUnit] adds a unit switch beside the height, for
 * onboarding, which asks for a height before it reaches the units page.
 */
@Composable
fun PersonFields(
    form: PersonForm,
    onChange: (PersonForm) -> Unit,
    lengthUnit: LengthUnit,
    showErrors: Boolean,
    modifier: Modifier = Modifier,
    onLengthUnit: ((LengthUnit) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    var pickingBirthday by rememberSaveable { mutableStateOf(false) }
    val birthdayWrong = showErrors && !form.birthdayIsPast()

    Column(
        modifier.fillMaxWidth().padding(horizontal = Gutter),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            OutlinedTextField(
                value = form.name,
                onValueChange = { onChange(form.copy(name = it.take(NAME_MAX_LENGTH))) },
                label = { Text(stringResource(R.string.person_name)) },
                isError = showErrors && form.name.isBlank(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            if (showErrors && form.name.isBlank()) ErrorLine(stringResource(R.string.person_name_missing))
        }
        Column {
            FieldLabel(stringResource(R.string.role_sex))
            OptionRow<Sex?>(
                options = listOf(Sex.MALE, Sex.FEMALE),
                selected = form.sex,
                label = { stringResource(if (it == Sex.MALE) R.string.male else R.string.female) },
                onSelect = { onChange(form.copy(sex = it)) },
            )
            if (showErrors && form.sex == null) ErrorLine(stringResource(R.string.person_sex_missing))
        }
        Column {
            ListRow(
                title = stringResource(R.string.role_birthday),
                value = form.birthdayEpochDay?.let(::formatEpochDay)
                    ?: stringResource(R.string.onboarding_birthday_select),
                valueColor = if (birthdayWrong) colors.attention else Color.Unspecified,
                onClick = { pickingBirthday = true },
                padding = PaddingValues(vertical = 10.dp),
            )
            if (birthdayWrong) ErrorLine(stringResource(R.string.invalid_birthday))
        }
        HeightFields(
            height = form.height,
            onChange = { onChange(form.copy(height = it)) },
            unit = lengthUnit,
            onUnit = onLengthUnit,
            showError = showErrors,
        )
    }

    if (pickingBirthday) {
        BirthdayDialog(
            selected = form.birthdayEpochDay,
            onPick = {
                onChange(form.copy(birthdayEpochDay = it))
                pickingBirthday = false
            },
            onDismiss = { pickingBirthday = false },
        )
    }
}

@Composable
private fun HeightFields(
    height: HeightEntry,
    onChange: (HeightEntry) -> Unit,
    unit: LengthUnit,
    onUnit: ((LengthUnit) -> Unit)?,
    showError: Boolean,
) {
    val wrong = showError && height.cm == null
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (unit) {
                LengthUnit.CM -> NumberField(
                    value = height.cmText,
                    label = stringResource(R.string.role_height),
                    maxDigits = 3,
                    isError = wrong,
                    suffix = { Text(stringResource(R.string.unit_cm)) },
                    onValue = { onChange(height.typed(unit, cmText = it)) },
                    modifier = Modifier.weight(1f),
                )
                LengthUnit.IMPERIAL -> {
                    NumberField(
                        value = height.feetText,
                        label = stringResource(R.string.onboarding_height_ft_hint),
                        maxDigits = 1,
                        isError = wrong,
                        onValue = { onChange(height.typed(unit, feetText = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        value = height.inchesText,
                        label = stringResource(R.string.onboarding_height_in_hint),
                        maxDigits = 2,
                        isError = wrong,
                        onValue = { onChange(height.typed(unit, inchesText = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (onUnit != null) {
                OptionRow(
                    options = LengthUnit.entries,
                    selected = unit,
                    label = { lengthUnitLabel(it) },
                    onSelect = onUnit,
                )
            }
        }
        if (wrong) ErrorLine(stringResource(R.string.invalid_height))
    }
}

@Composable
private fun NumberField(
    value: String,
    label: String,
    maxDigits: Int,
    isError: Boolean,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.filter(Char::isDigit).take(maxDigits)) },
        label = { Text(label) },
        suffix = suffix,
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayDialog(selected: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val today = remember { LocalDate.now() }
    val pastOnly = remember(today) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                utcTimeMillis.floorDiv(MILLIS_PER_DAY) < today.toEpochDay()

            override fun isSelectableYear(year: Int) = year in EARLIEST_BIRTH_YEAR..today.year
        }
    }
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = selected?.let { it * MILLIS_PER_DAY },
        initialDisplayedMonthMillis = (selected ?: DEFAULT_BIRTHDAY_MONTH.toEpochDay()) * MILLIS_PER_DAY,
        yearRange = EARLIEST_BIRTH_YEAR..today.year,
        selectableDates = pastOnly,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = picker.selectedDateMillis != null,
                onClick = { picker.selectedDateMillis?.let { onPick(it.floorDiv(MILLIS_PER_DAY)) } },
            ) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) { DatePicker(picker) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatches(selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalAppColors.current
    val dark = colors.ground.luminance() < 0.5f
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(PersonColorCount) { index ->
            val isSelected = index == selected.mod(PersonColorCount)
            val description = stringResource(R.string.person_color_option, index + 1, PersonColorCount)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(personColor(index, dark)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(Icons.Outlined.Check, null, tint = colors.onAccent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalAppColors.current.muted,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalAppColors.current.attention,
        modifier = modifier.padding(top = 4.dp),
    )
}
