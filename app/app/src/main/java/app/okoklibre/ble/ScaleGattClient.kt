package app.okoklibre.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import app.okoklibre.ble.protocol.GattUuids
import app.okoklibre.diagnostics.DiagnosticLog
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `connectGatt(context, false, callback)` wrapper with callback → suspend/Flow
 * bridging.
 *
 * All GATT operations (CCC descriptor writes, characteristic writes) are
 * serialized through a mutex — one op at a time — with a 1 s per-op timeout
 * that advances the queue on expiry. Characteristic writes use
 * WRITE_TYPE_NO_RESPONSE on fff2 and WRITE_TYPE_DEFAULT elsewhere (2a08).
 *
 * GATT status 133 closes the client and fails the pending connect; the owner
 * (ScaleManager) decides on retry. Characteristic value updates are streamed
 * on [events].
 */
@SuppressLint("MissingPermission") // guarded by BlePermissions via ScaleManager
class ScaleGattClient(private val context: Context) {

    sealed interface GattEvent {
        /** Notify/indicate payload from the scale. [uuid] is the characteristic UUID string. */
        data class CharacteristicChanged(val uuid: String, val value: ByteArray) : GattEvent

        /** Link dropped (or connect failed); [status] is the raw GATT status (133 on the infamous error). */
        data class Disconnected(val status: Int) : GattEvent
    }

    companion object {
        private const val TAG = "GattClient"
        const val GATT_ERROR_133 = 133
        const val OP_TIMEOUT_MS = 1_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val _events = MutableSharedFlow<GattEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GattEvent> = _events.asSharedFlow()

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var gatt: BluetoothGatt? = null

    private val opMutex = Mutex()

    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private var discoverDeferred: CompletableDeferred<Boolean>? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private var descriptorDeferred: CompletableDeferred<Boolean>? = null

    @Suppress("DEPRECATION")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        DiagnosticLog.log(TAG, "connected")
                        isConnected = true
                        connectDeferred?.complete(true)
                    } else {
                        DiagnosticLog.log(TAG, "connect failed, status=$status")
                        failPending()
                        closeGatt()
                        _events.tryEmit(GattEvent.Disconnected(status))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    DiagnosticLog.log(TAG, "disconnected, status=$status")
                    isConnected = false
                    failPending()
                    if (status == GATT_ERROR_133) closeGatt()
                    _events.tryEmit(GattEvent.Disconnected(status))
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            DiagnosticLog.log(TAG, "servicesDiscovered, status=$status")
            discoverDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                DiagnosticLog.log(TAG, "char write ${ch.uuid} failed, status=$status")
            }
            writeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                DiagnosticLog.log(TAG, "descriptor write ${descriptor.uuid} failed, status=$status")
            }
            descriptorDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Pre-API-33 overload; still the only one called on older devices")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            ch.value?.let { _events.tryEmit(GattEvent.CharacteristicChanged(ch.uuid.toString(), it)) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            _events.tryEmit(GattEvent.CharacteristicChanged(ch.uuid.toString(), value))
        }
    }

    /** Connects (no autoConnect) and discovers services. False on failure (incl. status 133). */
    suspend fun connect(mac: String): Boolean {
        val adapter = BlePermissions.bluetoothAdapter(context) ?: return false
        if (!BlePermissions.isBluetoothEnabled(context)) return false
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            return false
        }
        closeGatt()
        val connected = CompletableDeferred<Boolean>()
        connectDeferred = connected
        val g = try {
            device.connectGatt(context, false, callback)
        } catch (e: SecurityException) {
            null
        }
        if (g == null) {
            connectDeferred = null
            return false
        }
        gatt = g
        val connectOk = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } ?: false
        connectDeferred = null
        if (!connectOk) {
            closeGatt()
            return false
        }
        val discovered = CompletableDeferred<Boolean>()
        discoverDeferred = discovered
        val discoverStarted = try {
            g.discoverServices()
        } catch (e: SecurityException) {
            false
        }
        if (!discoverStarted) {
            discoverDeferred = null
            closeGatt()
            return false
        }
        val discoverOk = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { discovered.await() } ?: false
        discoverDeferred = null
        if (!discoverOk) closeGatt()
        return discoverOk
    }

    /** Enable notifications on a characteristic via its CCC (0x2902) descriptor. */
    suspend fun enableNotifications(charUuid: String): Boolean =
        writeCcc(charUuid, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            .also { DiagnosticLog.log(TAG, "enableNotifications($charUuid) -> $it") }

    /** Enable indications on a characteristic via its CCC (0x2902) descriptor. */
    suspend fun enableIndications(charUuid: String): Boolean =
        writeCcc(charUuid, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            .also { DiagnosticLog.log(TAG, "enableIndications($charUuid) -> $it") }

    private suspend fun writeCcc(charUuid: String, value: ByteArray): Boolean {
        val g = gatt ?: return false
        if (!isConnected) return false
        return runOp {
            val ch = findCharacteristic(g, charUuid) ?: return@runOp false
            if (!g.setCharacteristicNotification(ch, true)) return@runOp false
            val ccc = ch.getDescriptor(CCC_UUID) ?: return@runOp false
            val done = CompletableDeferred<Boolean>()
            descriptorDeferred = done
            val initiated = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(ccc, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ccc.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(ccc)
                }
            } catch (e: SecurityException) {
                false
            }
            if (!initiated) {
                descriptorDeferred = null
                false
            } else {
                try {
                    done.await()
                } finally {
                    if (descriptorDeferred === done) descriptorDeferred = null
                }
            }
        }
    }

    /**
     * Write a characteristic. WRITE_TYPE_NO_RESPONSE on fff2,
     * WRITE_TYPE_DEFAULT elsewhere (2a08 time sync), as the protocol requires.
     */
    suspend fun writeCharacteristic(charUuid: String, data: ByteArray): Boolean {
        val g = gatt ?: return false
        if (!isConnected) return false
        val writeType =
            if (GattUuids.CHAR_WRITE_FFF2.equals(charUuid, ignoreCase = true)) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        return runOp {
            val ch = findCharacteristic(g, charUuid) ?: return@runOp false
            val done = CompletableDeferred<Boolean>()
            writeDeferred = done
            val initiated = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, data, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = data
                    @Suppress("DEPRECATION")
                    ch.writeType = writeType
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
            } catch (e: SecurityException) {
                false
            }
            if (!initiated) {
                writeDeferred = null
                false
            } else {
                try {
                    done.await()
                } finally {
                    if (writeDeferred === done) writeDeferred = null
                }
            }
        }.also { ok ->
            if (!ok) DiagnosticLog.log(TAG, "writeCharacteristic($charUuid, ${data.size}B) -> false")
        }
    }

    /** Serialized op: one at a time, 1 s budget, timeout advances the queue. */
    private suspend fun runOp(block: suspend () -> Boolean): Boolean =
        withTimeoutOrNull(OP_TIMEOUT_MS) { opMutex.withLock { block() } } ?: false

    private fun findCharacteristic(g: BluetoothGatt, uuid: String): BluetoothGattCharacteristic? {
        val target = UUID.fromString(uuid)
        val services = g.services ?: return null
        for (service in services) {
            service.characteristics?.firstOrNull { it.uuid == target }?.let { return it }
        }
        return null
    }

    /** Intentional disconnect; a [GattEvent.Disconnected] may still follow. */
    fun disconnect() {
        try {
            gatt?.disconnect()
        } catch (e: SecurityException) {
            // closing anyway
        }
    }

    /** Disconnect (if needed) and release the GATT client. Terminal. */
    fun close() = closeGatt()

    private fun closeGatt() {
        isConnected = false
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
        } catch (e: SecurityException) {
            // closing anyway
        }
        try {
            g.close()
        } catch (e: SecurityException) {
            // closing anyway
        }
    }

    private fun failPending() {
        connectDeferred?.complete(false)
        connectDeferred = null
        discoverDeferred?.complete(false)
        discoverDeferred = null
        writeDeferred?.complete(false)
        writeDeferred = null
        descriptorDeferred?.complete(false)
        descriptorDeferred = null
    }
}
