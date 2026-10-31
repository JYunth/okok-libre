package app.okoklibre.ble

import app.okoklibre.ble.protocol.Measurement
import app.okoklibre.ble.protocol.ScaleAdvertisement
import app.okoklibre.ble.protocol.SeqNoDedupe
import app.okoklibre.ble.protocol.WeightUnit
import app.okoklibre.ble.protocol.decimals
import app.okoklibre.diagnostics.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Events emitted by [MeasurementCoordinator.startWeighing]. */
sealed interface WeighEvent {
    /** Scanning for the scale's advertisements. */
    data object Scanning : WeighEvent

    /** Device found; GATT connection and protocol setup in progress. */
    data object Connecting : WeighEvent

    /** Unlocked live weight in kg (broadcast pre-connect, fff1 after connect). */
    data class Live(val weightKg: Double, val digits: Int = 2) : WeighEvent

    /** Weight returned to zero after being weighed — waiting for the lock. */
    data object Stabilizing : WeighEvent

    /** Terminal success: locked measurement with full body-composition data. */
    data class Locked(val measurement: Measurement) : WeighEvent

    /**
     * Classic 0x14 user match: the scale matched candidates; the UI must pick
     * one and call [MeasurementCoordinator.selectUser].
     */
    data class UserMatchNeeded(val candidates: List<Int>) : WeighEvent

    /** Terminal failure. */
    data class Failed(val reason: String) : WeighEvent

    /** Terminal: no locked measurement within 30 s of connecting. */
    data object Timeout : WeighEvent
}

/** Where weighing events come from. Production is the scale; debug builds can script one. */
interface WeighingSource {
    fun startWeighing(device: ScaleAdvertisement, roles: List<ScaleUserProfile>, unit: WeightUnit): Flow<WeighEvent>

    fun selectUser(roleId: Int)
}

/**
 * Thin orchestration the UI/ViewModel drives for a weighing session:
 * scan (broadcast live weight pre-connect) → connect → driver → stream events
 * → stop cleanly. Owns the scan and the GATT session for the flow's lifetime;
 * both are torn down when the flow completes or is cancelled.
 *
 * Lock sources, first one wins:
 *  1. the GATT locked measurement (fff1/2a9c via [ScaleManager.lockedMeasurement]);
 *  2. a connectionless locked broadcast frame (0xCA v0x20 / 0xC0 with
 *     measureSeqNo) — accepted after [BROADCAST_LOCK_GRACE_MS] so the richer
 *     GATT frame is preferred when both arrive; deduped by sequence number
 *     ([SeqNoDedupe]) so one weighing can never be reported twice.
 */
class MeasurementCoordinator(private val scaleManager: ScaleManager) : WeighingSource {

    companion object {
        private const val TAG = "Weighing"

        /** Max time to spot the scale advertising before failing. */
        const val DEVICE_DISCOVERY_TIMEOUT_MS = 10_000L

        /** Max time to establish GATT + protocol setup before failing. */
        const val CONNECT_TIMEOUT_MS = 20_000L

        /** Grace period after a lock so classic v11 history sync can drain (2 s silence). */
        const val HISTORY_DRAIN_MS = 2_500L

        /** How long a broadcast lock waits for a GATT lock before being accepted. */
        const val BROADCAST_LOCK_GRACE_MS = 800L

        /** A scale keeps advertising its last lock for a while after someone steps off. */
        const val RESIDUAL_LOCK_MS = 60_000L
    }

    private var lastBroadcastLock: Pair<Int, Long>? = null

    /** True when this frame is the previous person's lock still on the air, not a new weighing. */
    private fun isResidualLock(seq: Int): Boolean {
        val (lastSeq, at) = lastBroadcastLock ?: return false
        return seq == lastSeq && System.currentTimeMillis() - at < RESIDUAL_LOCK_MS
    }

    /** Pass-through for [WeighEvent.UserMatchNeeded] answers. */
    override fun selectUser(roleId: Int) = scaleManager.selectUser(roleId)

    override fun startWeighing(
        device: ScaleAdvertisement,
        roles: List<ScaleUserProfile>,
        unit: WeightUnit,
    ): Flow<WeighEvent> = channelFlow {
        try {
            send(WeighEvent.Scanning)
            when {
                !scaleManager.hasPermissions -> {
                    send(WeighEvent.Failed("Bluetooth permissions not granted"))
                    return@channelFlow
                }
                !scaleManager.isBluetoothEnabled -> {
                    send(WeighEvent.Failed("Bluetooth is off"))
                    return@channelFlow
                }
            }
            scaleManager.startScan()

            val advFlow = scaleManager.advertisements
                .filter { it.mac.equals(device.mac, ignoreCase = true) }
            val found = withTimeoutOrNull(DEVICE_DISCOVERY_TIMEOUT_MS) { advFlow.first() }
            if (found == null) {
                send(WeighEvent.Failed("scale not found while scanning"))
                return@channelFlow
            }

            // Keep listening to the broadcast while GATT is being attempted.
            // Some classic scales expose a perfectly valid locked frame but
            // reject service discovery. If the lock listener starts only after
            // connect(), that frame is lost and the UI can never open a report.
            val broadcastJob = launch {
                advFlow.collect { adv ->
                    adv.weightKg?.let { if (it > 0.0) send(WeighEvent.Live(it, adv.decimals())) }
                }
            }

            val lockedDeferred = CompletableDeferred<Measurement>()
            val seqDedupe = SeqNoDedupe()
            val broadcastLockJob = launch {
                advFlow.collect { adv ->
                    val seq = adv.measureSeqNo ?: return@collect
                    val weight = adv.weightKg ?: return@collect
                    if (weight <= 0.0 || isResidualLock(seq) || !seqDedupe.claim(seq)) return@collect
                    lastBroadcastLock = seq to System.currentTimeMillis()
                    DiagnosticLog.log(
                        TAG,
                        "locked broadcast frame seq=$seq weight=${weight}kg — " +
                            "waiting ${BROADCAST_LOCK_GRACE_MS}ms for a GATT lock",
                    )
                    delay(BROADCAST_LOCK_GRACE_MS)
                    if (lockedDeferred.complete(
                            Measurement(
                                weightKg = weight,
                                lockFlag = true,
                                impedanceOhm = adv.impedanceOhm ?: 0.0,
                                scaleProperty = adv.scaleProperty,
                                decimals = adv.decimals(),
                            ),
                        )
                    ) {
                        DiagnosticLog.log(TAG, "accepted broadcast lock seq=$seq (no GATT lock)")
                    }
                }
            }

            send(WeighEvent.Connecting)
            val connectJob = async {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    scaleManager.connect(found, roles, unit)
                } ?: false
            }
            // Keep both attempts moving. A Deferred select clause is not
            // available on every coroutines runtime used by the Android app,
            // so poll the two completion flags at a short UI-invisible cadence.
            // The lock is checked first: a valid broadcast reading wins even
            // if GATT finishes in the same scheduling slice.
            var connected: Boolean? = null
            var locked: Measurement? = null
            while (connected == null && locked == null) {
                if (lockedDeferred.isCompleted) {
                    locked = runCatching { lockedDeferred.await() }.getOrNull()
                } else if (connectJob.isCompleted) {
                    connected = runCatching { connectJob.await() }.getOrDefault(false)
                } else {
                    delay(25L)
                }
            }
            if (locked != null) {
                connectJob.cancel()
                broadcastJob.cancel()
                broadcastLockJob.cancel()
                send(WeighEvent.Locked(locked!!))
                delay(HISTORY_DRAIN_MS)
                return@channelFlow
            }

            if (connected != true) {
                // Give a lock that arrives just after a failed GATT attempt a
                // short final window before reporting a hard failure.
                locked = withTimeoutOrNull(BROADCAST_LOCK_GRACE_MS * 4) {
                    lockedDeferred.await()
                }
                if (locked == null) {
                    broadcastJob.cancel()
                    broadcastLockJob.cancel()
                    send(WeighEvent.Failed("could not connect to scale"))
                    return@channelFlow
                }
                broadcastJob.cancel()
                broadcastLockJob.cancel()
                send(WeighEvent.Locked(locked!!))
                delay(HISTORY_DRAIN_MS)
                return@channelFlow
            }

            // GATT now owns live frames; keep the connectionless lock listener
            // alive as the fallback, but stop duplicating live UI updates.
            broadcastJob.cancel()

            var sawWeight = false
            val liveJob = launch {
                scaleManager.liveMeasurement.filterNotNull().collect { m ->
                    if (m.weightKg > 0.0) {
                        sawWeight = true
                        send(WeighEvent.Live(m.weightKg, m.decimals))
                    } else if (sawWeight) {
                        sawWeight = false
                        send(WeighEvent.Stabilizing)
                    }
                }
            }
            val matchJob = launch {
                scaleManager.userMatchRequest.collect {
                    send(WeighEvent.UserMatchNeeded(it.candidates))
                }
            }

            // Primary lock source: the GATT locked measurement.
            val gattLockJob = launch {
                scaleManager.awaitLockedMeasurement()?.let { lockedDeferred.complete(it) }
            }
            locked = withTimeoutOrNull(
                ScaleManager.MEASUREMENT_TIMEOUT_MS + BROADCAST_LOCK_GRACE_MS,
            ) { lockedDeferred.await() }
            liveJob.cancel()
            matchJob.cancel()
            gattLockJob.cancel()
            broadcastLockJob.cancel()
            if (locked == null) {
                DiagnosticLog.log(TAG, "no locked measurement within timeout")
                send(WeighEvent.Timeout)
                return@channelFlow
            }
            send(WeighEvent.Locked(locked!!))
            delay(HISTORY_DRAIN_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.log(TAG, "session error: ${e.stackTraceToString().lineSequence().first()}")
            runCatching { send(WeighEvent.Failed("internal error: ${e.message ?: e.javaClass.simpleName}")) }
        } finally {
            scaleManager.stopScan()
            scaleManager.disconnect()
        }
    }
}
