package app.okoklibre.ble

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import app.okoklibre.ble.protocol.AdvertisementAssembler
import app.okoklibre.ble.protocol.BroadcastParser
import app.okoklibre.ble.protocol.ScaleAdvertisement
import app.okoklibre.diagnostics.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Unfiltered LOW_LATENCY BLE scan wrapper.
 *
 * The scan runs in cycles (12 s on, brief gap, restart) because the original
 * app restarts the scanner periodically to keep receiving advertisements on
 * stacks that throttle long-running scans. Scanning continues while a device
 * is connected so connectionless live-weight broadcast frames keep flowing.
 *
 * Every advertisement whose manufacturer data parses via [BroadcastParser]
 * (and whose name is not blacklisted) is emitted on [advertisements];
 * [latestByMac] keeps the newest frame per device.
 */
@SuppressLint("MissingPermission") // guarded by BlePermissions in startScan loop
class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        const val SCAN_CYCLE_MS = 12_000L
        const val RESTART_GAP_MS = 400L
        const val RETRY_GAP_MS = 1_000L

        /** Name blacklist (other products, not weight scales). */
        private val BLACKLISTED_NAME_PARTS = listOf("SWAN", "AIMA-", "BYECH", "EeEeE", "CS-Rope-")
    }

    private val _advertisements = MutableSharedFlow<ScaleAdvertisement>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every parsed scale advertisement frame (multiple per device over time). */
    val advertisements: SharedFlow<ScaleAdvertisement> = _advertisements.asSharedFlow()

    private val _latestByMac = MutableStateFlow<Map<String, ScaleAdvertisement>>(emptyMap())

    /** Latest parsed frame per device MAC. */
    val latestByMac: StateFlow<Map<String, ScaleAdvertisement>> = _latestByMac.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)

        override fun onBatchScanResults(results: List<ScanResult>) = results.forEach(::handleResult)

        override fun onScanFailed(errorCode: Int) {
            DiagnosticLog.log(TAG, "scan failed, errorCode=$errorCode")
            _isScanning.value = false
        }
    }

    // Consecutive-identical log suppression (scales advertise many times/s).
    @Volatile
    private var lastLoggedAdv: String? = null

    /**
     * Starts the scan/restart loop on [scope]. Idempotent; no-op if already
     * running. Cycles self-heal: if Bluetooth is off or permissions are
     * missing the loop idles and retries on the next cycle.
     */
    fun startScan(scope: CoroutineScope) {
        if (scanJob?.isActive == true) return
        // A scale heard in an earlier session may be gone; only what's advertising now counts.
        _latestByMac.value = emptyMap()
        scanJob = scope.launch {
            while (isActive) {
                val scanner = leScanner()
                if (scanner == null) {
                    _isScanning.value = false
                    delay(RETRY_GAP_MS)
                    continue
                }
                val started = try {
                    scanner.startScan(null, lowLatencySettings(), scanCallback)
                    true
                } catch (e: SecurityException) {
                    false
                } catch (e: IllegalStateException) {
                    false
                }
                if (!started) {
                    _isScanning.value = false
                    delay(RETRY_GAP_MS)
                    continue
                }
                _isScanning.value = true
                delay(SCAN_CYCLE_MS)
                try {
                    scanner.stopScan(scanCallback)
                } catch (e: SecurityException) {
                    // permissions revoked mid-scan; next cycle re-checks
                }
                _isScanning.value = false
                delay(RESTART_GAP_MS)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        try {
            leScanner()?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // ignore — we are stopping anyway
        }
        _isScanning.value = false
    }

    private fun leScanner(): BluetoothLeScanner? {
        if (!BlePermissions.hasBlePermissions(context)) return null
        val adapter = BlePermissions.bluetoothAdapter(context) ?: return null
        return try {
            if (adapter.isEnabled) adapter.bluetoothLeScanner else null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun lowLatencySettings(): ScanSettings =
        ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

    private fun handleResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val name = record.deviceName
        if (name != null && BLACKLISTED_NAME_PARTS.any { name.contains(it) }) return
        val mac = result.device?.address ?: return
        val msd = record.manufacturerSpecificData ?: return
        for (i in 0 until msd.size()) {
            // Android strips the 2-byte company ID (the key) from the value, but
            // OKOK encodes its frame header there — prepend it back.
            val frame = AdvertisementAssembler.fromAndroidManufacturerData(msd.keyAt(i), msd.valueAt(i))
            val adv = BroadcastParser.parse(mac, frame)
            if (adv == null) {
                logOnce("unparsed frame mac=$mac ${frame.toHex()}")
                continue
            }
            logOnce(
                "adv mac=$mac proto=${adv.protocolType} v=${adv.version} type=${adv.deviceType} " +
                    "prop=${adv.scaleProperty} w=${adv.weightKg} imp=${adv.impedanceOhm} " +
                    "seq=${adv.measureSeqNo} raw=${frame.toHex()}",
            )
            _latestByMac.value = _latestByMac.value + (mac to adv)
            _advertisements.tryEmit(adv)
            return
        }
    }

    /** Logs [message] unless identical to the previously logged one. */
    private fun logOnce(message: String) {
        if (message == lastLoggedAdv) return
        lastLoggedAdv = message
        DiagnosticLog.log(TAG, message)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
