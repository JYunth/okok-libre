package app.okoklibre.ble.protocol

/**
 * Reassembles multi-packet payloads.
 *
 * Packet field byte: high nibble = packet index (1-based), low nibble = total
 * packet count. [add] returns the concatenated payload (in index order) once
 * every index 1..total has been received, and null otherwise.
 *
 * Unlike the original app (which drops the whole sequence if
 * the last-indexed packet arrives before earlier ones), this assembler accepts
 * out-of-order arrival and completes whenever all indices are present.
 */
class MultiPacketAssembler {
    private val packets = HashMap<Int, ByteArray>()

    /**
     * @param packageField raw packet-field byte (index << 4 | total), 0..255
     * @param payload packet payload bytes (everything after the packet field)
     * @return assembled payload when complete, null while waiting for packets
     */
    fun add(packageField: Int, payload: ByteArray): ByteArray? {
        val index = (packageField shr 4) and 0xF
        val total = packageField and 0xF
        if (index == 0 || total == 0 || index > total) {
            packets.clear()
            return null
        }
        if (index == 1 && packets.containsKey(1)) {
            // A repeated first packet means a new sequence started while an
            // earlier partial one was still buffered.
            packets.clear()
        }
        packets[index] = payload
        if (packets.size == total) {
            val out = ByteArray(packets.values.sumOf { it.size })
            var offset = 0
            for (i in 1..total) {
                val part = packets.getValue(i)
                part.copyInto(out, offset)
                offset += part.size
            }
            packets.clear()
            return out
        }
        return null
    }

    fun reset() = packets.clear()
}
