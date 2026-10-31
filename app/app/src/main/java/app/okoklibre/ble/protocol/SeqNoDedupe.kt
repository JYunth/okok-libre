package app.okoklibre.ble.protocol

/**
 * Bounded dedupe for broadcast measurement sequence numbers: one weighing
 * must not be saved twice when both the broadcast lock frame and the GATT
 * lock frame arrive. Sequence numbers are a
 * single wire byte and wrap, so the memory is bounded and per-session.
 */
class SeqNoDedupe(private val capacity: Int = 32) {

    private val seen = LinkedHashSet<Int>()

    /** True when [seqNo] is new (and now remembered); false on a repeat. */
    fun claim(seqNo: Int): Boolean = synchronized(seen) {
        if (!seen.add(seqNo)) return false
        if (seen.size > capacity) seen.remove(seen.first())
        true
    }
}
