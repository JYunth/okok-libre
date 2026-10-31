package app.okoklibre.ble.protocol

/** GATT UUIDs shared by all supported scales. */
object GattUuids {
    const val SERVICE_FFF0 = "0000fff0-0000-1000-8000-00805f9b34fb"

    /** Notify — scale → app live frames. */
    const val CHAR_LIVE_FFF1 = "0000fff1-0000-1000-8000-00805f9b34fb"

    /** Write (no response) — app → scale. */
    const val CHAR_WRITE_FFF2 = "0000fff2-0000-1000-8000-00805f9b34fb"

    /** Indicate — locked/final body-composition measurements (Cloud / CloudV4). */
    const val CHAR_LOCKED_2A9C = "00002a9c-0000-1000-8000-00805f9b34fb"

    /** Indicate — history/offline records (Cloud / CloudV4). */
    const val CHAR_HISTORY_FA9C = "0000fa9c-0000-1000-8000-00805f9b34fb"

    /** Write — current-time sync. */
    const val CHAR_CURRENT_TIME_2A08 = "00002a08-0000-1000-8000-00805f9b34fb"

    fun isLive(uuid: String): Boolean = uuid.equals(CHAR_LIVE_FFF1, ignoreCase = true)

    fun isHistory(uuid: String): Boolean = uuid.equals(CHAR_HISTORY_FA9C, ignoreCase = true)
}
