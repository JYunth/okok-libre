package app.okoklibre.ui.devices

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.okoklibre.R
import app.okoklibre.ble.BlePermissions
import app.okoklibre.data.ScaleDeviceEntity
import app.okoklibre.ui.LocalAppState
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.EmptyState
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.theme.LocalAppColors
import kotlinx.coroutines.delay

private const val BIND_TIMEOUT_MS = 30_000L
private const val SUCCESS_NAVIGATE_DELAY_MS = 1_400L

private enum class BindPhase { Scanning, Success, Failed }

/** What the screen shows: the two gates come before anything [BindPhase] has to say. */
private enum class BindStep { NeedsPermission, BluetoothOff, Scanning, Added, NotFound }

/**
 * Bind flow 1:1 with the original app: the FIRST qualifying advertisement from an
 * unbound MAC auto-binds immediately (no list, no tap, no weight requirement).
 * On timeout a failure state offers a retry.
 */
@Composable
fun DeviceBindScreen(onBound: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val appState = LocalAppState.current
    val defaultName = stringResource(R.string.device_default_name)

    var hasPermissions by remember { mutableStateOf(BlePermissions.hasBlePermissions(context)) }
    var bluetoothOn by remember { mutableStateOf(BlePermissions.isBluetoothEnabled(context)) }
    var permissionDenied by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(BindPhase.Scanning) }
    var scanRound by remember { mutableIntStateOf(0) }
    var registered by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermissions = BlePermissions.hasBlePermissions(context)
        permissionDenied = !hasPermissions
    }

    val prerequisitesOk = hasPermissions && bluetoothOn

    // Scan while the screen is in the foreground and still looking; re-check
    // the radio on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, phase, prerequisitesOk) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasPermissions = BlePermissions.hasBlePermissions(context)
                    bluetoothOn = BlePermissions.isBluetoothEnabled(context)
                    if (hasPermissions && bluetoothOn && phase == BindPhase.Scanning) {
                        appState.scaleManager.startScan()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> appState.scaleManager.stopScan()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            appState.scaleManager.stopScan()
        }
    }

    // Timeout only fails a still-scanning round.
    LaunchedEffect(scanRound, phase, prerequisitesOk) {
        if (phase != BindPhase.Scanning || !prerequisitesOk) return@LaunchedEffect
        delay(BIND_TIMEOUT_MS)
        if (phase == BindPhase.Scanning) {
            appState.scaleManager.stopScan()
            phase = BindPhase.Failed
        }
    }

    val advertisements by appState.scaleManager.latestAdvertisements
        .collectAsStateWithLifecycle(emptyMap())
    // Null until read: the advertisement map outlives a scan, so treating "not loaded" as
    // "nothing bound" could re-register a scale that's already paired.
    val boundDevices by appState.deviceRepository.devices
        .collectAsStateWithLifecycle<List<ScaleDeviceEntity>?>(null)

    // Auto-bind the first advertisement whose MAC is not already bound.
    LaunchedEffect(phase, advertisements, boundDevices) {
        if (phase != BindPhase.Scanning || registered) return@LaunchedEffect
        val boundMacs = boundDevices?.map { it.mac }?.toSet() ?: return@LaunchedEffect
        val adv = advertisements.values.firstOrNull { it.mac !in boundMacs } ?: return@LaunchedEffect
        registered = true
        appState.scaleManager.stopScan()
        appState.deviceRepository.register(
            mac = adv.mac,
            name = defaultName,
            typeId = adv.deviceType,
            productId = adv.productId.takeIf { it != 0 }?.toLong(),
            protocolVersion = adv.version,
            protocolType = adv.protocolType,
        )
        phase = BindPhase.Success
    }

    // Show the confirmation briefly, then leave.
    LaunchedEffect(phase) {
        if (phase == BindPhase.Success) {
            delay(SUCCESS_NAVIGATE_DELAY_MS)
            onBound()
        }
    }

    val step = when {
        !hasPermissions -> BindStep.NeedsPermission
        !bluetoothOn -> BindStep.BluetoothOff
        phase == BindPhase.Success -> BindStep.Added
        phase == BindPhase.Failed -> BindStep.NotFound
        else -> BindStep.Scanning
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppBar(title = null, onBack = onBack) },
    ) { padding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "bind",
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) { shown ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (shown) {
                    // After a refusal Android may stop showing its prompt, so the way out is settings.
                    BindStep.NeedsPermission -> EmptyState(
                        stringResource(R.string.device_add_permission_title),
                        stringResource(
                            if (permissionDenied) {
                                R.string.device_add_permission_denied_body
                            } else {
                                R.string.device_add_permission_body
                            },
                        ),
                        action = stringResource(
                            if (permissionDenied) R.string.app_settings_open else R.string.device_add_permission_grant,
                        ),
                        onAction = {
                            val perms = BlePermissions.requiredRuntimePermissions()
                            if (perms.isEmpty()) {
                                hasPermissions = true
                            } else if (permissionDenied) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            } else {
                                permissionLauncher.launch(perms)
                            }
                        },
                    )
                    BindStep.BluetoothOff -> EmptyState(
                        stringResource(R.string.device_add_bt_off_title),
                        stringResource(R.string.device_add_bt_off_body),
                        action = stringResource(R.string.device_add_bt_open_settings),
                        onAction = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    )
                    BindStep.Scanning -> {
                        EmptyState(
                            stringResource(R.string.measure_step_on),
                            stringResource(R.string.device_bind_wake_body),
                        )
                        Listening(Modifier.padding(horizontal = Gutter))
                    }
                    BindStep.Added -> EmptyState(stringResource(R.string.device_bind_added), null)
                    BindStep.NotFound -> EmptyState(
                        stringResource(R.string.device_bind_failed_title),
                        stringResource(R.string.device_bind_failed_body),
                        action = stringResource(R.string.device_bind_retry),
                        onAction = {
                            appState.scaleManager.startScan()
                            phase = BindPhase.Scanning
                            scanRound += 1
                        },
                    )
                }
            }
        }
    }
}

/** A short line that breathes while the phone listens, as on the weigh-in screen. */
@Composable
private fun Listening(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val transition = rememberInfiniteTransition(label = "listening")
    val breath by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "breath",
    )
    Canvas(modifier.size(width = 56.dp, height = 3.dp)) {
        drawLine(
            colors.accent,
            Offset(0f, size.height / 2),
            Offset(size.width * breath, size.height / 2),
            size.height,
            StrokeCap.Round,
        )
    }
}
