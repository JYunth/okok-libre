package app.okoklibre.ui.measure

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.okoklibre.R
import app.okoklibre.algo.BodyCompositionEngine
import app.okoklibre.algo.Sex
import app.okoklibre.algo.WeightUnit
import app.okoklibre.ble.BlePermissions
import app.okoklibre.data.RoleEntity
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.formatWeightKg
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.Avatar
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.KeyRow
import app.okoklibre.ui.design.ListRow
import app.okoklibre.ui.design.PrimaryButton
import app.okoklibre.ui.design.SecondaryButton
import app.okoklibre.ui.theme.LocalAppColors

/** The weigh-in: one number on an empty screen, from first frame to lock. */
@Composable
fun MeasureScreen(
    onBack: () -> Unit,
    onAddDevice: () -> Unit,
    onSaved: (Long) -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val vm: MeasureViewModel = appViewModel { MeasureViewModel(it) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val attribution by vm.attribution.collectAsStateWithLifecycle()
    val scaleMatch by vm.scaleMatch.collectAsStateWithLifecycle()
    val guestResult by vm.guestResult.collectAsStateWithLifecycle()
    val heartRatePhase by vm.heartRatePhase.collectAsStateWithLifecycle()
    val countdownSec by vm.countdownSec.collectAsStateWithLifecycle()
    val unit by vm.weightUnit.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionDenied = result.values.any { !it }
        vm.retry()
    }

    LaunchedEffect(Unit) {
        vm.savedRecordId.collect { onSaved(it) }
    }
    LaunchedEffect(Unit) {
        vm.playSuccessSound.collect {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            runCatching {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).startTone(ToneGenerator.TONE_PROP_ACK, 160)
            }
        }
    }

    // Leaving mid-weigh just stops listening; nothing is lost, so it needs no confirmation.
    fun leave() {
        vm.abandon()
        onBack()
    }
    BackHandler { leave() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(
                targetState = uiState::class,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "measure",
                modifier = Modifier.fillMaxSize(),
            ) { _ ->
                when (val state = uiState) {
                    MeasureUiState.Loading -> Box(Modifier.fillMaxSize())
                    MeasureUiState.NoDevices -> EmptyState(
                        stringResource(R.string.measure_no_scale_title),
                        stringResource(R.string.measure_no_scale_body),
                        Modifier.padding(top = 64.dp),
                        action = stringResource(R.string.add_scale),
                        onAction = onAddDevice,
                    )
                    is MeasureUiState.PickDevice -> Column(Modifier.padding(top = 64.dp)) {
                        Text(
                            stringResource(R.string.measure_pick_scale),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(horizontal = Gutter).padding(bottom = 12.dp),
                        )
                        state.devices.forEach { device ->
                            ListRow(title = device.name, onClick = { vm.startSession(device) })
                        }
                    }
                    is MeasureUiState.Failed -> Failure(
                        reason = state.reason,
                        onRetry = vm::retry,
                        permissionDenied = permissionDenied,
                        onGrant = {
                            if (permissionDenied) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            } else {
                                permissions.launch(BlePermissions.requiredRuntimePermissions())
                            }
                        },
                        onEnableBluetooth = {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                        onDiagnostics = onOpenDiagnostics,
                    )
                    MeasureUiState.Timeout -> Failure(
                        reason = null,
                        permissionDenied = false,
                        onRetry = vm::retry,
                        onGrant = {},
                        onEnableBluetooth = {},
                        onDiagnostics = onOpenDiagnostics,
                    )
                    else -> Weighing(state, heartRatePhase, countdownSec, unit)
                }
            }
            IconButton(onClick = ::leave, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                Icon(Icons.Outlined.Close, stringResource(R.string.cancel), tint = LocalAppColors.current.muted)
            }
        }
    }

    when (val a = attribution) {
        is AttributionState.PickMemberOrGuest -> WhoSheet(
            title = stringResource(R.string.measure_who),
            body = stringResource(
                R.string.measure_who_body,
                "${formatWeightKg(a.measurement.weightKg, unit, 1)} ${weightUnitLabel(unit)}",
            ),
            roles = a.roles,
            onPick = vm::pickRole,
            onGuest = vm::pickGuest,
        )
        is AttributionState.ConfirmCandidate -> WhoSheet(
            title = stringResource(R.string.measure_who),
            body = stringResource(
                R.string.measure_confirm_body,
                "${formatWeightKg(a.measurement.weightKg, unit, 1)} ${weightUnitLabel(unit)}",
                a.candidates.first().nickname,
                a.secondsLeft,
            ),
            roles = a.candidates,
            onPick = vm::pickRole,
            onGuest = null,
        )
        AttributionState.None -> {}
    }

    scaleMatch?.let { match ->
        WhoSheet(
            title = stringResource(R.string.measure_who),
            body = stringResource(R.string.measure_match_scale_title),
            roles = match.candidates,
            onPick = vm::answerScaleMatch,
            onGuest = null,
        )
    }

    guestResult?.let { guest ->
        GuestResultDialog(guest, unit) {
            vm.dismissGuestResult()
            leave()
        }
    }
}

@Composable
private fun Weighing(state: MeasureUiState, heartRatePhase: Boolean, countdownSec: Int?, unit: WeightUnit) {
    val colors = LocalAppColors.current
    val live = state as? MeasureUiState.Live
    val hint = when {
        heartRatePhase -> stringResource(R.string.measure_heart_rate)
        state is MeasureUiState.Connecting -> stringResource(R.string.measure_connecting)
        state is MeasureUiState.Stabilizing -> stringResource(R.string.measure_settling)
        state is MeasureUiState.Done -> stringResource(R.string.measure_saved)
        live != null -> stringResource(R.string.measure_hold_still)
        else -> stringResource(R.string.measure_step_on)
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (live != null) {
                // Live frames outrun any digit animation, so the number just updates in place.
                Text(formatWeightKg(live.weightKg, unit, live.digits), style = MaterialTheme.typography.displayLarge)
                Text(
                    weightUnitLabel(unit),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.muted,
                    modifier = Modifier.padding(start = 6.dp, bottom = 14.dp),
                )
            } else {
                Text(stringResource(R.string.measure_placeholder), style = MaterialTheme.typography.displayLarge, color = colors.faint)
            }
        }
        Spacer(Modifier.height(20.dp))
        Listening(active = state !is MeasureUiState.Done)
        Spacer(Modifier.height(20.dp))
        Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.muted)
        if (countdownSec != null && countdownSec <= 10 && live == null) {
            Text(
                stringResource(R.string.measure_seconds_left, countdownSec),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A short line that breathes while the app is listening to the scale. */
@Composable
private fun Listening(active: Boolean) {
    val colors = LocalAppColors.current
    val transition = rememberInfiniteTransition(label = "listening")
    val phase by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "breath",
    )
    Canvas(Modifier.size(width = 56.dp, height = 3.dp)) {
        val width = size.width * (if (active) phase else 1f)
        val start = (size.width - width) / 2
        drawLine(
            colors.accent,
            Offset(start, size.height / 2),
            Offset(start + width, size.height / 2),
            size.height,
            StrokeCap.Round,
        )
    }
}

/** Names the problem and the way out; the raw reason stays in Diagnostics. */
@Composable
private fun Failure(
    reason: String?,
    permissionDenied: Boolean,
    onRetry: () -> Unit,
    onGrant: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val needsPermission = reason?.contains("permission", ignoreCase = true) == true
    val bluetoothOff = reason?.contains("Bluetooth is off", ignoreCase = true) == true
    val title = stringResource(
        when {
            needsPermission -> R.string.measure_fail_permission_title
            bluetoothOff -> R.string.measure_fail_bluetooth_title
            reason == null -> R.string.measure_timeout
            else -> R.string.measure_failed
        },
    )
    val body = stringResource(
        when {
            needsPermission && Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> R.string.measure_fail_permission_location
            needsPermission -> R.string.measure_fail_permission_body
            bluetoothOff -> R.string.measure_fail_bluetooth_body
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> R.string.measure_fail_generic_location
            else -> R.string.measure_fail_generic_body
        },
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = Gutter).padding(top = 72.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = LocalAppColors.current.muted)
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            stringResource(
                when {
                    needsPermission && permissionDenied -> R.string.app_settings_open
                    needsPermission -> R.string.measure_grant
                    bluetoothOff -> R.string.measure_open_bluetooth
                    else -> R.string.measure_try_again
                },
            ),
            onClick = when {
                needsPermission -> onGrant
                bluetoothOff -> onEnableBluetooth
                else -> onRetry
            },
        )
        if (bluetoothOff || permissionDenied) SecondaryButton(stringResource(R.string.measure_try_again), onRetry)
        TextButton(onClick = onDiagnostics, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.measure_diagnostics))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhoSheet(
    title: String,
    body: String,
    roles: List<RoleEntity>,
    onPick: (RoleEntity) -> Unit,
    onGuest: ((Sex, Int, Int) -> Unit)?,
) {
    val colors = LocalAppColors.current
    var guest by rememberSaveable { mutableStateOf(false) }
    // A reading must have an owner, so this sheet can't be swiped away by accident.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    ModalBottomSheet(onDismissRequest = {}, sheetState = sheetState, containerColor = colors.ground) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            if (guest && onGuest != null) {
                GuestForm(onGuest)
            } else {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = Gutter))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    modifier = Modifier.padding(horizontal = Gutter).padding(top = 4.dp, bottom = 8.dp),
                )
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    roles.forEach { role ->
                        ListRow(title = role.nickname, leading = { Avatar(role, 32.dp) }, onClick = { onPick(role) })
                    }
                    if (onGuest != null) {
                        ListRow(title = stringResource(R.string.measure_guest), onClick = { guest = true })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuestForm(onSubmit: (Sex, Int, Int) -> Unit) {
    var sex by rememberSaveable { mutableStateOf(Sex.MALE) }
    var age by rememberSaveable { mutableStateOf("30") }
    var height by rememberSaveable { mutableStateOf("170") }
    val ageValue = age.toIntOrNull()?.takeIf { it in 6..120 }
    val heightValue = height.toIntOrNull()?.takeIf { it in 80..250 }
    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.measure_guest), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.measure_guest_body),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalAppColors.current.muted,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Sex.entries.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = sex == value,
                    onClick = { sex = value },
                    shape = SegmentedButtonDefaults.itemShape(index, Sex.entries.size),
                    label = { Text(stringResource(if (value == Sex.MALE) R.string.sex_male else R.string.sex_female)) },
                )
            }
        }
        OutlinedTextField(
            value = age,
            onValueChange = { age = it },
            label = { Text(stringResource(R.string.measure_guest_age)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = height,
            onValueChange = { height = it },
            label = { Text(stringResource(R.string.measure_guest_height)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(
            stringResource(R.string.measure_guest_show),
            enabled = ageValue != null && heightValue != null,
            onClick = { onSubmit(sex, ageValue!!, heightValue!!) },
        )
    }
}

@Composable
private fun GuestResultDialog(guest: GuestResult, unit: WeightUnit, onDismiss: () -> Unit) {
    val result = remember(guest) { BodyCompositionEngine.compute(guest.input) }
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${formatWeightKg(guest.measurement.weightKg, unit, 1)} ${weightUnitLabel(unit)}") },
        text = {
            Column {
                Text(stringResource(R.string.measure_guest_not_saved), color = colors.muted)
                Spacer(Modifier.height(8.dp))
                KeyRow(colors.faint, stringResource(R.string.metric_bmi), "%.1f".format(result.bmi))
                if (result.hasBodyComposition) {
                    KeyRow(colors.faint, stringResource(R.string.metric_body_fat), "%.1f %%".format(result.bodyFatPct))
                    KeyRow(colors.faint, stringResource(R.string.metric_water), "%.1f %%".format(result.waterPct))
                    KeyRow(
                        colors.faint,
                        stringResource(R.string.metric_muscle),
                        "${formatWeightKg(result.muscleMassKg, unit, 1)} ${weightUnitLabel(unit)}",
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
