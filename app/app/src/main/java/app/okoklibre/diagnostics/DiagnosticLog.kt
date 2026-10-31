package app.okoklibre.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide in-memory ring buffer of timestamped diagnostic entries
 * (tag + message), so BLE/protocol problems can be reported from a device
 * without a debugger. Cheap and allocation-light: bounded to [CAPACITY]
 * entries, no persistence, no I/O. View it from About → Diagnostics.
 */
object DiagnosticLog {

    const val CAPACITY = 500

    data class Entry(val timestampMs: Long, val tag: String, val message: String)

    private val buffer = ArrayDeque<Entry>(CAPACITY)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest-last snapshot of the buffer; replaced on every change. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun log(tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), tag, message)
        val snapshot: List<Entry>
        synchronized(buffer) {
            while (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            snapshot = buffer.toList()
        }
        _entries.value = snapshot
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _entries.value = emptyList()
    }

    /** Plain-text dump for the clipboard ("HH:mm:ss.SSS tag: message" per line). */
    fun formatAll(zone: ZoneId = ZoneId.systemDefault()): String {
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        val snapshot = synchronized(buffer) { buffer.toList() }
        return buildString(snapshot.size * 48) {
            snapshot.forEach { e ->
                append(Instant.ofEpochMilli(e.timestampMs).atZone(zone).format(fmt))
                append(' ')
                append(e.tag)
                append(": ")
                appendLine(e.message)
            }
        }
    }
}
