package app.okoklibre.ble.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeqNoDedupeTest {

    @Test
    fun `first claim of a sequence number wins, repeats are rejected`() {
        val d = SeqNoDedupe()
        assertTrue(d.claim(7))
        assertFalse(d.claim(7))
        assertFalse(d.claim(7))
        assertTrue(d.claim(8))
    }

    @Test
    fun `evicts the oldest entry beyond capacity`() {
        val d = SeqNoDedupe(capacity = 4)
        repeat(4) { assertTrue(d.claim(it)) }
        assertFalse(d.claim(0)) // still remembered
        assertTrue(d.claim(4)) // evicts 0
        assertFalse(d.claim(1)) // 1 still remembered
        assertTrue(d.claim(0)) // 0 can be claimed again (evicts 1)
    }
}
