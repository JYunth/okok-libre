package app.okoklibre.ble.protocol

import java.time.LocalDateTime
import java.time.ZoneId

/** Outcome of parsing an uplink (scale → app) GATT frame. */
sealed interface ParseResult {
    /** Unlocked live frame (characteristic fff1, or classic lockFlag = 0). */
    data class LiveMeasurement(val measurement: Measurement) : ParseResult

    /** Locked/stable final measurement (characteristic 2a9c, or classic lockFlag = 1). */
    data class LockedMeasurement(val measurement: Measurement) : ParseResult

    /** Offline/history record (characteristic fa9c, or classic v11 sub-cmd 0x13). */
    data class HistoryRecord(val measurement: Measurement) : ParseResult

    /**
     * Classic v11 sub-cmd 0x14: the scale matched the weighing against on-scale
     * user profiles; the app must answer with a Select-User command.
     */
    data class UserMatch(
        val candidates: List<Int>,
        val weightKg: Double,
        val scaleProperty: Int,
    ) : ParseResult

    /** Acknowledgement frame from the scale. Reserved; no current parser emits it. */
    data class Ack(val command: Int) : ParseResult

    /** Unrecognised, malformed, incomplete or out-of-domain frame. */
    data object Unknown : ParseResult
}

/**
 * History-record timestamp acceptance window:
 * [2010-01-01 local, now + 5 min]. Records outside it are dropped.
 */
internal fun isHistoryTimestampValid(
    epochSec: Long,
    nowEpochSec: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val min = LocalDateTime.of(2010, 1, 1, 0, 0, 0).atZone(zone).toEpochSecond()
    return epochSec >= min && epochSec <= nowEpochSec + 300
}
