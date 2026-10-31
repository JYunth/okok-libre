package app.okoklibre.ble.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiPacketAssemblerTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `single packet completes immediately`() {
        val assembler = MultiPacketAssembler()
        val out = assembler.add(0x11, bytes(1, 2, 3)) // index 1 of 1
        assertArrayEquals(bytes(1, 2, 3), out)
    }

    @Test
    fun `in-order multi-packet assembly`() {
        val assembler = MultiPacketAssembler()
        assertNull(assembler.add(0x13, bytes(1, 2))) // 1 of 3
        assertNull(assembler.add(0x23, bytes(3, 4))) // 2 of 3
        val out = assembler.add(0x33, bytes(5, 6)) // 3 of 3
        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6), out)
    }

    @Test
    fun `out-of-order packets complete when all indices present`() {
        val assembler = MultiPacketAssembler()
        assertNull(assembler.add(0x33, bytes(5, 6))) // last first
        assertNull(assembler.add(0x13, bytes(1, 2)))
        val out = assembler.add(0x23, bytes(3, 4))
        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6), out)
    }

    @Test
    fun `new sequence restarts assembly`() {
        val assembler = MultiPacketAssembler()
        assertNull(assembler.add(0x12, bytes(9, 9))) // stale packet 1 of 2
        assertNull(assembler.add(0x12, bytes(1, 2))) // new sequence packet 1 of 2
        val out = assembler.add(0x22, bytes(3, 4))
        assertArrayEquals(bytes(1, 2, 3, 4), out)
    }

    @Test
    fun `duplicate index overwrites`() {
        val assembler = MultiPacketAssembler()
        assertNull(assembler.add(0x12, bytes(9, 9)))
        assertNull(assembler.add(0x12, bytes(1, 2))) // index 1 restarts, stale copy dropped
        val out = assembler.add(0x22, bytes(3, 4))
        assertArrayEquals(bytes(1, 2, 3, 4), out)
    }

    @Test
    fun `invalid packet field resets and waits`() {
        val assembler = MultiPacketAssembler()
        assertNull(assembler.add(0x12, bytes(1, 2)))
        assertNull(assembler.add(0x31, bytes(7))) // index 3 > total 1
        // assembly was cleared; a fresh valid sequence works
        assertNull(assembler.add(0x12, bytes(1, 2)))
        assertArrayEquals(bytes(1, 2, 3, 4), assembler.add(0x22, bytes(3, 4)))
    }
}
