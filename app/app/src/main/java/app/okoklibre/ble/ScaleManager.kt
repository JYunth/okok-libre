package app.okoklibre.ble

import android.content.Context
import app.okoklibre.ble.protocol.DeviceType
import app.okoklibre.ble.protocol.Measurement
import app.okoklibre.ble.protocol.ParseResult
import app.okoklibre.ble.protocol.PropertyByte
import app.okoklibre.ble.protocol.ScaleAdvertisement
import app.okoklibre.ble.protocol.ScaleProtocolType
import app.okoklibre.ble.protocol.WeightUnit
import app.okoklibre.ble.protocol.decimals
import app.okoklibre.diagnostics.DiagnosticLog
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Central BLE scale coordinator — connection state machine.
 *
 * Singleton (see [getInstance]); owns the [BleScanner] and at most one GATT
 * connection. All parsing decisions stay in `ble.protocol`; this class only
 * routes frames, drives the state machine, auto-reconnects on unexpected
 * disconnects while a measurement session is active (1500 ms retry interval,
 * "always connect" mode), and reports device-type detection results.
 */
class ScaleManager private constructor(private val appContext: Context) {

    /** State machine (mapped names; GPS_CHANGE and WAIT_CONNECT not used). */
    enum class ConnectionState {
        BLE_OFF,
        IDLE,
        SCANNING,
        CONNECTING,
        CONNECTED,
        CALCULATING,
        WAIT_SCALE,
    }

    companion object {
        private const val TAG = "ScaleManager"
        const val RECONNECT_INTERVAL_MS = 1_500L
        const val MEASUREMENT_TIMEOUT_MS = 30_000L
        const val SELECT_USER_MAX_ATTEMPTS = 10
        const val SELECT_USER_INTERVAL_MS = 800L

        @Volatile
        private var instance: ScaleManager? = null

        fun getInstance(context: Context): ScaleManager =
            instance ?: synchronized(this) {
                instance ?: ScaleManager(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val scanner = BleScanner(appContext)

    // ------------------------------------------------------------------ state

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Unlocked live weight — from broadcast frames or fff1 live notifications. */
    private val _liveMeasurement = MutableStateFlow<Measurement?>(null)
    val liveMeasurement: StateFlow<Measurement?> = _liveMeasurement.asStateFlow()

    /** Locked/stable final measurement (reset to null on each connect). */
    private val _lockedMeasurement = MutableStateFlow<Measurement?>(null)
    val lockedMeasurement: StateFlow<Measurement?> = _lockedMeasurement.asStateFlow()

    private val _historyRecords = MutableSharedFlow<Measurement>(extraBufferCapacity = 64)
    val historyRecords: SharedFlow<Measurement> = _historyRecords.asSharedFlow()

    private val _userMatchRequest = MutableSharedFlow<ParseResult.UserMatch>(extraBufferCapacity = 8)
    val userMatchRequest: SharedFlow<ParseResult.UserMatch> = _userMatchRequest.asSharedFlow()

    /** CloudV4 heart-rate measurement in progress (C5 property bit7). */
    private val _heartRatePhase = MutableStateFlow(false)
    val heartRatePhase: StateFlow<Boolean> = _heartRatePhase.asStateFlow()

    /** Latest parsed advertisement per device MAC. */
    val latestAdvertisements: StateFlow<Map<String, ScaleAdvertisement>> = scanner.latestByMac

    /** Every parsed scale advertisement frame. */
    val advertisements: SharedFlow<ScaleAdvertisement> = scanner.advertisements

    /**
     * Persistence hook: fired once per MAC on the first locked frame of a
     * classic scale — typeId 2 if oriVisceral == 25600 (weight+impedance
     * only), 1 if the frame carries scale-computed composition, else 0.
     */
    var onDeviceTypeDetected: ((mac: String, typeId: Int) -> Unit)? = null

    val hasPermissions: Boolean get() = BlePermissions.hasBlePermissions(appContext)
    val isBluetoothEnabled: Boolean get() = BlePermissions.isBluetoothEnabled(appContext)

    // ---------------------------------------------------------- session state

    @Volatile
    private var sessionActive = false

    @Volatile
    private var connectLoopActive = false

    private var sessionDevice: ScaleAdvertisement? = null
    private var sessionRoles: List<ScaleUserProfile> = emptyList()
    private var sessionUnit: WeightUnit = WeightUnit.KG

    private var gattClient: ScaleGattClient? = null
    private var protocolDriver: ScaleProtocolDriver? = null
    private var collectJob: Job? = null
    private var reconnectJob: Job? = null
    private var selectUserJob: Job? = null

    private val connectMutex = Mutex()
    private val detectedDeviceTypes = CopyOnWriteArraySet<String>()

    init {
        // Keep connectionless live-weight broadcast frames flowing into
        // liveMeasurement for the device a session is bound to.
        scope.launch {
            scanner.advertisements.collect { adv ->
                val mac = sessionDevice?.mac ?: return@collect
                val weight = adv.weightKg ?: return@collect
                if (!adv.mac.equals(mac, ignoreCase = true) || weight <= 0.0) return@collect
                _liveMeasurement.value = Measurement(
                    weightKg = weight,
                    lockFlag = PropertyByte.lockFlag(adv.scaleProperty),
                    impedanceOhm = adv.impedanceOhm ?: 0.0,
                    scaleProperty = adv.scaleProperty,
                    decimals = adv.decimals(),
                )
            }
        }
        scope.launch {
            var last: ConnectionState? = null
            connectionState.collect { state ->
                if (state != last) {
                    last = state
                    DiagnosticLog.log(TAG, "state → $state")
                }
            }
        }
    }

    // ------------------------------------------------------------------- scan

    /** Starts (idempotent) the scan/restart loop. False if permissions or radio missing. */
    fun startScan(): Boolean {
        if (!hasPermissions) return false
        if (!isBluetoothEnabled) {
            _connectionState.value = ConnectionState.BLE_OFF
            return false
        }
        scanner.startScan(scope)
        if (_connectionState.value == ConnectionState.IDLE ||
            _connectionState.value == ConnectionState.BLE_OFF
        ) {
            _connectionState.value = ConnectionState.SCANNING
        }
        return true
    }

    fun stopScan() {
        scanner.stopScan()
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.IDLE
        }
    }

    // ---------------------------------------------------------------- connect

    /**
     * Connects to [device] and runs the protocol connect sequence. Retries
     * every [RECONNECT_INTERVAL_MS] (also after GATT status 133) until the
     * link is up, [disconnect] is called, or the calling coroutine is
     * cancelled. Returns true once connected and set up.
     */
    suspend fun connect(
        device: ScaleAdvertisement,
        roles: List<ScaleUserProfile>,
        preferredUnit: WeightUnit,
    ): Boolean {
        if (!hasPermissions) return false
        if (!isBluetoothEnabled) {
            _connectionState.value = ConnectionState.BLE_OFF
            return false
        }
        reconnectJob?.cancel()
        reconnectJob = null
        sessionActive = true
        sessionDevice = device
        sessionRoles = roles
        sessionUnit = preferredUnit
        _liveMeasurement.value = null
        _lockedMeasurement.value = null
        _heartRatePhase.value = false
        connectLoopActive = true
        return try {
            connectLoop()
        } finally {
            connectLoopActive = false
        }
    }

    /** Ends the session: no auto-reconnect after this. */
    fun disconnect() {
        sessionActive = false
        sessionDevice = null
        reconnectJob?.cancel()
        reconnectJob = null
        cleanupGatt()
        _heartRatePhase.value = false
        _connectionState.value =
            if (scanner.isScanning.value) ConnectionState.SCANNING else ConnectionState.IDLE
    }

    /**
     * Answer a classic 0x14 user-match request: Select-User resent up to 10×
     * every 800 ms, cancelled as soon as a locked measurement arrives.
     */
    fun selectUser(roleId: Int) {
        val gatt = gattClient ?: return
        val driver = protocolDriver ?: return
        selectUserJob?.cancel()
        selectUserJob = scope.launch {
            repeat(SELECT_USER_MAX_ATTEMPTS) {
                driver.selectUser(gatt, roleId)
                delay(SELECT_USER_INTERVAL_MS)
            }
        }
    }

    /** 30 s measurement timeout helper: next locked measurement or null. */
    suspend fun awaitLockedMeasurement(timeoutMs: Long = MEASUREMENT_TIMEOUT_MS): Measurement? =
        withTimeoutOrNull(timeoutMs) { lockedMeasurement.filterNotNull().first() }

    // --------------------------------------------------------------- internal

    private suspend fun connectLoop(): Boolean {
        while (sessionActive) {
            _connectionState.value = ConnectionState.CONNECTING
            if (doConnect()) return true
            cleanupGatt()
            if (!sessionActive) return false
            delay(RECONNECT_INTERVAL_MS)
        }
        return false
    }

    private suspend fun doConnect(): Boolean = connectMutex.withLock {
        val adv = sessionDevice ?: return@withLock false
        gattClient?.let { if (it.isConnected) return@withLock true }
        cleanupGatt()
        val gatt = ScaleGattClient(appContext)
        val driver = ScaleProtocolDriver.create(adv)
        gattClient = gatt
        protocolDriver = driver
        collectJob = scope.launch { collectGattEvents(gatt, driver, adv) }
        DiagnosticLog.log(TAG, "connecting to ${adv.mac} (${driver.protocolType} v${adv.version})")
        if (!gatt.connect(adv.mac)) {
            DiagnosticLog.log(TAG, "GATT connect/service-discovery failed for ${adv.mac}")
            return@withLock false
        }
        val config = ConnectConfig(sessionRoles, sessionUnit, adv.productId)
        if (!driver.runConnectSequence(gatt, config)) {
            DiagnosticLog.log(TAG, "connect sequence failed for ${adv.mac}")
            return@withLock false
        }
        _connectionState.value = ConnectionState.CONNECTED
        true
    }

    private suspend fun collectGattEvents(
        gatt: ScaleGattClient,
        driver: ScaleProtocolDriver,
        adv: ScaleAdvertisement,
    ) {
        gatt.events.collect { event ->
            when (event) {
                is ScaleGattClient.GattEvent.CharacteristicChanged ->
                    handleFrame(gatt, driver, adv, event.uuid, event.value)
                is ScaleGattClient.GattEvent.Disconnected ->
                    handleGattDisconnected(gatt)
            }
        }
    }

    private fun handleFrame(
        gatt: ScaleGattClient,
        driver: ScaleProtocolDriver,
        adv: ScaleAdvertisement,
        uuid: String,
        data: ByteArray,
    ) {
        if (gattClient !== gatt) return
        when (val result = driver.parse(uuid, data)) {
            is ParseResult.LiveMeasurement -> {
                val m = result.measurement
                _liveMeasurement.value = m
                _heartRatePhase.value = m.heartRateMeasuring
                if (m.weightKg > 0.0 && _connectionState.value == ConnectionState.CONNECTED) {
                    _connectionState.value = ConnectionState.CALCULATING
                } else if (m.weightKg <= 0.0 && _connectionState.value == ConnectionState.CALCULATING) {
                    _connectionState.value = ConnectionState.WAIT_SCALE
                }
            }
            is ParseResult.LockedMeasurement -> {
                val m = result.measurement
                DiagnosticLog.log(
                    TAG,
                    "locked via GATT: ${m.weightKg}kg prop=${m.scaleProperty} " +
                        "fat=${m.fatPct} imp=${m.impedanceOhm} oriVisceral=${m.oriVisceralFat}",
                )
                _lockedMeasurement.value = m
                _heartRatePhase.value = m.heartRateMeasuring
                selectUserJob?.cancel()
                selectUserJob = null
                _connectionState.value = ConnectionState.CONNECTED
                detectDeviceType(adv.mac, driver.protocolType, m)
                scope.launch { driver.onLockedMeasurement(gatt) }
            }
            is ParseResult.HistoryRecord -> {
                DiagnosticLog.log(TAG, "history record: ${result.measurement.weightKg}kg ts=${result.measurement.timestampEpochSec}")
                _historyRecords.tryEmit(result.measurement)
            }
            is ParseResult.UserMatch -> {
                DiagnosticLog.log(TAG, "user-match request: ${result.candidates}")
                _userMatchRequest.tryEmit(result)
            }
            else -> {}
        }
    }

    /**
     * Device-type detection, once per MAC, on the first locked frame.
     * Classic protocol only: the oriVisceral sentinel does not exist on
     * Cloud/CloudV4 frames (they always send impedance and no composition
     * block, which would otherwise misdetect as type 0). Runs on the raw
     * frame values — a `fatPct > 0` gate would make type 2 unreachable
     * because the sentinel clamps fat/visceral to 0.
     */
    private fun detectDeviceType(mac: String, protocolType: ScaleProtocolType, m: Measurement) {
        if (protocolType != ScaleProtocolType.OKOK_CLASSIC) return
        if (!detectedDeviceTypes.add(mac)) return
        val typeId = DeviceType.detectFromLocked(m)
        DiagnosticLog.log(TAG, "device type detected: $mac → $typeId")
        onDeviceTypeDetected?.invoke(mac, typeId)
    }

    private fun handleGattDisconnected(gatt: ScaleGattClient) {
        if (gattClient !== gatt) return
        DiagnosticLog.log(TAG, "GATT link dropped (sessionActive=$sessionActive)")
        cleanupGatt()
        if (!sessionActive) {
            _connectionState.value =
                if (scanner.isScanning.value) ConnectionState.SCANNING else ConnectionState.IDLE
            return
        }
        // Unexpected drop mid-session: auto-reconnect. An in-flight
        // connect loop (initial connect) retries on its own.
        if (connectLoopActive) return
        _connectionState.value = ConnectionState.CONNECTING
        reconnectJob = scope.launch {
            delay(RECONNECT_INTERVAL_MS)
            connectLoopActive = true
            try {
                connectLoop()
            } finally {
                connectLoopActive = false
            }
        }
    }

    private fun cleanupGatt() {
        collectJob?.cancel()
        collectJob = null
        selectUserJob?.cancel()
        selectUserJob = null
        protocolDriver = null
        gattClient?.close()
        gattClient = null
    }
}
