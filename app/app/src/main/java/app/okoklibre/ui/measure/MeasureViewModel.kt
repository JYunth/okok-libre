package app.okoklibre.ui.measure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.okoklibre.algo.Sex
import app.okoklibre.algo.WeightUnit
import app.okoklibre.ble.ScaleUserProfile
import app.okoklibre.ble.WeighEvent
import app.okoklibre.ble.protocol.Measurement
import app.okoklibre.ble.protocol.ScaleAdvertisement
import app.okoklibre.data.Attribution
import app.okoklibre.data.RoleEntity
import app.okoklibre.data.ScaleDeviceEntity
import app.okoklibre.data.ageYearsAt
import app.okoklibre.diagnostics.DiagnosticLog
import app.okoklibre.ui.AppState
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state machine for the weighing flow. */
sealed interface MeasureUiState {
    /** Bound devices not loaded yet. */
    data object Loading : MeasureUiState

    /** No scale bound — empty state with add-device CTA. */
    data object NoDevices : MeasureUiState

    /** More than one bound scale — pick one before weighing. */
    data class PickDevice(val devices: List<ScaleDeviceEntity>) : MeasureUiState

    /** Waiting for the scale's advertisement while scanning. */
    data object Scanning : MeasureUiState

    /** Scale found, GATT/protocol setup in progress. */
    data object Connecting : MeasureUiState

    /** Live unlocked weight streaming in, at the scale's digit precision. */
    data class Live(val weightKg: Double, val digits: Int = 2) : MeasureUiState

    /** Weight returned to zero — waiting for the lock. */
    data object Stabilizing : MeasureUiState

    /** Terminal failure (permissions off, BT off, not found, connect failed). */
    data class Failed(val reason: String) : MeasureUiState

    /** Terminal: no locked measurement within 30 s. */
    data object Timeout : MeasureUiState

    /** Terminal: record saved, report pending navigation. */
    data object Done : MeasureUiState
}

/** Pending role attribution after a lock (±3 kg match). */
sealed interface AttributionState {
    data object None : AttributionState

    /** Nobody fits: pick a person or weigh as guest. */
    data class PickMemberOrGuest(val measurement: Measurement, val roles: List<RoleEntity>) : AttributionState

    /** Several people fit: a 10 s countdown settles on the first, the best guess. */
    data class ConfirmCandidate(
        val measurement: Measurement,
        val candidates: List<RoleEntity>,
        val secondsLeft: Int,
    ) : AttributionState
}

/** Scale-side 0x14 user match request. */
data class ScaleMatchRequest(val candidates: List<RoleEntity>)

class MeasureViewModel(private val appState: AppState) : ViewModel() {

    private val _uiState = MutableStateFlow<MeasureUiState>(MeasureUiState.Loading)
    val uiState: StateFlow<MeasureUiState> = _uiState.asStateFlow()

    private val _attribution = MutableStateFlow<AttributionState>(AttributionState.None)
    val attribution: StateFlow<AttributionState> = _attribution.asStateFlow()

    private val _scaleMatch = MutableStateFlow<ScaleMatchRequest?>(null)
    val scaleMatch: StateFlow<ScaleMatchRequest?> = _scaleMatch.asStateFlow()

    /** Guest weighing result (not persisted): computed composition for the dialog. */
    private val _guestResult = MutableStateFlow<GuestResult?>(null)
    val guestResult: StateFlow<GuestResult?> = _guestResult.asStateFlow()

    /** One-shot: the new record id to open. Buffered so a lock during rotation isn't lost. */
    private val _savedRecordId = Channel<Long>(Channel.BUFFERED)
    val savedRecordId: Flow<Long> = _savedRecordId.receiveAsFlow()

    /** One-shot: play the completion sound. */
    private val _playSuccessSound = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playSuccessSound: SharedFlow<Unit> = _playSuccessSound.asSharedFlow()

    /** Heart-rate phase reported by the scale (CloudV4 C5 property bit7). */
    val heartRatePhase: StateFlow<Boolean> = appState.scaleManager.heartRatePhase

    val weightUnit: StateFlow<WeightUnit> = appState.settingsRepository.weightUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeightUnit.KG)

    /** Seconds left of the overall 30 s measurement window (null = no session). */
    private val _countdownSec = MutableStateFlow<Int?>(null)
    val countdownSec: StateFlow<Int?> = _countdownSec.asStateFlow()

    private var devices: List<ScaleDeviceEntity> = emptyList()
    private var selectedDevice: ScaleDeviceEntity? = null
    private var sessionJob: Job? = null
    private var historyJob: Job? = null
    private var countdownJob: Job? = null
    private var attributionCountdownJob: Job? = null

    /** Set once a reading has locked: the session then runs on to drain the scale's history. */
    private var locked = false

    /** Who the scale-side match settled on; that choice shouldn't be asked twice. */
    private var scaleChosenRole: RoleEntity? = null

    private val sessionScope = appState.backgroundScope

    init {
        // Observed, so a scale added from this very screen takes effect on return.
        viewModelScope.launch {
            appState.deviceRepository.devices.collect { list ->
                devices = list
                val waiting = _uiState.value
                if (waiting is MeasureUiState.Loading || waiting is MeasureUiState.NoDevices ||
                    waiting is MeasureUiState.PickDevice
                ) {
                    _uiState.value = when {
                        list.isEmpty() -> MeasureUiState.NoDevices
                        list.size == 1 -> {
                            startSession(list.first())
                            MeasureUiState.Scanning
                        }
                        else -> MeasureUiState.PickDevice(list)
                    }
                }
            }
        }
    }

    fun deviceName(mac: String): String = devices.firstOrNull { it.mac == mac }?.name ?: mac

    /** Device picked (or auto-picked) → begin the weighing session. */
    fun startSession(device: ScaleDeviceEntity) {
        if (sessionJob?.isActive == true) return
        selectedDevice = device
        locked = false
        scaleChosenRole = null
        _attribution.value = AttributionState.None
        _scaleMatch.value = null
        _guestResult.value = null

        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            _countdownSec.value = COUNTDOWN_TOTAL_SEC
            while (_countdownSec.value?.let { it > 0 } == true) {
                delay(1000)
                _countdownSec.value = _countdownSec.value?.minus(1)
            }
        }

        // Offline records stored on the scale drain in after a lock.
        historyJob = sessionScope.launch {
            try {
                appState.scaleManager.historyRecords.collect { m ->
                    if (m.weightKg > 0.0) {
                        // Known-role records are claimed right away;
                        // the rest wait in the tmp table for manual claiming.
                        appState.tmpWeightRepository.addOrAutoClaim(
                            mac = device.mac,
                            measurement = m,
                            productId = device.productId,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.log(TAG, "history drain error: ${e.firstStackLine()}")
            }
        }

        sessionJob = sessionScope.launch {
            try {
                val roles = buildScaleProfiles()
                val adv = appState.scaleManager.latestAdvertisements.value[device.mac]
                    ?: ScaleAdvertisement(
                        mac = device.mac,
                        protocolType = device.protocolType,
                        version = device.protocolVersion,
                        deviceType = device.typeId,
                        scaleProperty = 0,
                        productId = device.productId?.toInt() ?: 0,
                    )
                DiagnosticLog.log(TAG, "session start: ${device.mac} (${device.protocolType} v${device.protocolVersion})")
                val unit = appState.settingsRepository.weightUnit.first()
                appState.weighingSource.startWeighing(adv, roles, unit.toProtocolUnit())
                    .collect { event -> handleEvent(event, device) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never die silently — the UI must leave the frozen Live state.
                DiagnosticLog.log(TAG, "session collector died: ${e.firstStackLine()}")
                _uiState.value = MeasureUiState.Failed("internal error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                sessionEnded()
            }
        }
        sessionJob?.invokeOnCompletion { historyJob?.cancel() }
    }

    private suspend fun handleEvent(event: WeighEvent, device: ScaleDeviceEntity) {
        try {
            when (event) {
                is WeighEvent.Scanning -> _uiState.value = MeasureUiState.Scanning
                is WeighEvent.Connecting -> _uiState.value = MeasureUiState.Connecting
                is WeighEvent.Live ->
                    _uiState.value = MeasureUiState.Live(event.weightKg, event.digits)
                is WeighEvent.Stabilizing -> _uiState.value = MeasureUiState.Stabilizing
                is WeighEvent.Failed -> _uiState.value = MeasureUiState.Failed(event.reason)
                is WeighEvent.Timeout -> _uiState.value = MeasureUiState.Timeout
                is WeighEvent.UserMatchNeeded -> {
                    val roles = appState.roleRepository.allRoles()
                    val candidates = event.candidates.mapNotNull { id -> roles.firstOrNull { it.id.toInt() == id } }
                    val active = appState.profileSession.activeRoleNow()
                    val pick = candidates.singleOrNull() ?: candidates.firstOrNull { it.id == active?.id }
                    if (pick != null) {
                        selectOnScale(pick)
                    } else if (candidates.isNotEmpty()) {
                        _scaleMatch.value = ScaleMatchRequest(candidates)
                    }
                }
                is WeighEvent.Locked -> onLocked(event.measurement, device)
            }
            if (event !is WeighEvent.Live) DiagnosticLog.log(TAG, "event: $event")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.log(TAG, "handleEvent($event) failed: ${e.firstStackLine()}")
            _uiState.value = MeasureUiState.Failed("internal error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun onLocked(measurement: Measurement, device: ScaleDeviceEntity) {
        _playSuccessSound.tryEmit(Unit)
        locked = true
        scaleChosenRole?.let {
            persistAndReport(it, measurement, device)
            return
        }
        val active = appState.profileSession.activeRoleNow()
        when (val decision = appState.roleRepository.attribute(measurement.weightKg, active?.id)) {
            is Attribution.Save -> persistAndReport(decision.role, measurement, device)
            Attribution.Ask -> _attribution.value = AttributionState.PickMemberOrGuest(
                measurement,
                appState.roleRepository.allRoles(),
            )
            is Attribution.Confirm -> {
                _attribution.value = AttributionState.ConfirmCandidate(measurement, decision.candidates, AUTO_PICK_SEC)
                attributionCountdownJob?.cancel()
                attributionCountdownJob = viewModelScope.launch {
                    var left = AUTO_PICK_SEC
                    while (left > 0) {
                        delay(1000)
                        left--
                        val current = _attribution.value
                        if (current !is AttributionState.ConfirmCandidate) return@launch
                        _attribution.value = current.copy(secondsLeft = left)
                    }
                    val current = _attribution.value as? AttributionState.ConfirmCandidate ?: return@launch
                    _attribution.value = AttributionState.None
                    persistAndReport(current.candidates.first(), current.measurement, device)
                }
            }
        }
    }

    /** Attribution dialog answer. */
    fun pickRole(role: RoleEntity) {
        val measurement = when (val current = _attribution.value) {
            is AttributionState.PickMemberOrGuest -> current.measurement
            is AttributionState.ConfirmCandidate -> current.measurement
            AttributionState.None -> return
        }
        attributionCountdownJob?.cancel()
        _attribution.value = AttributionState.None
        val device = selectedDevice ?: return
        viewModelScope.launch { persistAndReport(role, measurement, device) }
    }
    /** Attribution dialog: weigh as guest — compute metrics without persisting. */
    fun pickGuest(sex: Sex, ageYears: Int, heightCm: Int) {
        val current = _attribution.value as? AttributionState.PickMemberOrGuest ?: return
        _attribution.value = AttributionState.None
        val m = current.measurement
        _guestResult.value = GuestResult(
            measurement = m,
            input = app.okoklibre.algo.CompositionInput(
                heightCm = heightCm.toDouble(),
                weightKg = m.weightKg,
                sex = sex,
                ageYears = ageYears,
                impedanceOhm = m.impedanceOhm,
            ),
        )
        endSessionToDone()
    }

    fun dismissGuestResult() {
        _guestResult.value = null
    }

    /** Scale-side 0x14 match answer. */
    fun answerScaleMatch(role: RoleEntity) {
        _scaleMatch.value = null
        selectOnScale(role)
    }

    private fun selectOnScale(role: RoleEntity) {
        scaleChosenRole = role
        appState.weighingSource.selectUser(role.id.toInt())
    }

    fun retry() {
        val device = selectedDevice ?: devices.firstOrNull() ?: return
        sessionJob?.cancel()
        _uiState.value = MeasureUiState.Scanning
        startSession(device)
    }

    /** Abandons an active session (back press / navigation away). */
    fun abandon() {
        sessionJob?.cancel()
        sessionEnded()
    }

    private fun sessionEnded() {
        countdownJob?.cancel()
        _countdownSec.value = null
    }

    private fun endSessionToDone() {
        _uiState.value = MeasureUiState.Done
    }

    private suspend fun persistAndReport(role: RoleEntity, measurement: Measurement, device: ScaleDeviceEntity) {
        try {
            val id = appState.weightRepository.insertScaleComputed(
                role = role,
                measurement = measurement,
                productId = device.productId,
            )
            // Whoever just weighed in is who the app should now be showing.
            appState.profileSession.switchTo(role.id)
            appState.deviceRepository.seen(device.mac)
            DiagnosticLog.log(TAG, "saved record id=$id ${measurement.weightKg}kg → report")
            _uiState.value = MeasureUiState.Done
            _savedRecordId.send(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.log(TAG, "persist failed: ${e.firstStackLine()}")
            _uiState.value = MeasureUiState.Failed("could not save record: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun buildScaleProfiles(): List<ScaleUserProfile> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis() / 1000
        // Some scales only take one profile and compute with it, so the active person goes first.
        val activeId = appState.profileSession.activeRoleNow()?.id
        return appState.roleRepository.allRoles().sortedByDescending { it.id == activeId }.map { role ->
            ScaleUserProfile(
                roleId = role.id.toInt(),
                male = role.sex == Sex.MALE,
                age = ageYearsAt(role.birthdayEpochDay, now, zone),
                heightCm = role.heightCm,
                weightKg = appState.weightRepository.latest(role.id)?.weightKg ?: 0.0,
            )
        }
    }

    override fun onCleared() {
        // After a lock the session finishes on its own, once the scale's history has drained.
        if (!locked) sessionJob?.cancel()
        countdownJob?.cancel()
        attributionCountdownJob?.cancel()
    }

    companion object {
        private const val TAG = "MeasureVM"
        const val AUTO_PICK_SEC = 10
        const val COUNTDOWN_TOTAL_SEC = 30
    }
}

private fun Exception.firstStackLine(): String =
    "${javaClass.simpleName}: $message @ ${stackTrace.firstOrNull()?.toString() ?: "?"}"

/** App display unit → protocol wire unit. */
private fun WeightUnit.toProtocolUnit(): app.okoklibre.ble.protocol.WeightUnit = when (this) {
    WeightUnit.KG -> app.okoklibre.ble.protocol.WeightUnit.KG
    WeightUnit.LB -> app.okoklibre.ble.protocol.WeightUnit.LB
    WeightUnit.JIN -> app.okoklibre.ble.protocol.WeightUnit.JIN
    WeightUnit.ST_LB -> app.okoklibre.ble.protocol.WeightUnit.ST
}

/** Guest (visitor) weighing — shown in a dialog, never persisted. */
data class GuestResult(
    val measurement: Measurement,
    val input: app.okoklibre.algo.CompositionInput,
)
