package com.thinktough.tutticam.protocol

import java.io.ByteArrayInputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockAndFramingTest {
    @Test
    fun picksShortestRoundTrip() {
        var time = 0.0
        val clock = ClockSync({ time })
        // 模拟主机时钟快 100 秒：t1 = t0 + 去程 + 100，t3 = t2 − 100 + 回程。
        fun probe(t0: Double, out: Double, back: Double) {
            val t1 = t0 + out + 100
            val t2 = t1 + 0.0002
            clock.addPong(t0, t1, t2, t2 - 100 + back)
        }
        probe(1.0, 0.020, 0.002) // 往返长且不对称
        probe(2.0, 0.001, 0.001) // 往返最短
        probe(3.0, 0.005, 0.030)
        val result = assertNotNull(clock.finishBurst())
        assertEquals(0.002, result.rtt, 1e-9)
        assertEquals(100.0, result.offset, 1e-9)
        assertEquals(10.0, clock.hostToLocal(110.0)!!, 1e-9)
        assertTrue(clock.isSynced)
        time = 5.0
        assertEquals(1, clock.nextPing().seq)
    }

    @Test
    fun emptyBurstKeepsPreviousOffset() {
        val clock = ClockSync({ 0.0 })
        assertNull(clock.finishBurst())
        assertFalse(clock.isSynced)
        clock.addPong(0.0, 50.0, 50.0, 0.004)
        assertNotNull(clock.provisional())
        assertTrue(clock.isSynced)
        clock.reset()
        assertFalse(clock.isSynced)
    }

    @Test
    fun negativeRoundTripIsIgnored() {
        val clock = ClockSync({ 0.0 })
        clock.addPong(1.0, 5.0, 6.0, 1.5)
        assertNull(clock.finishBurst())
    }

    @Test
    fun framesRoundTrip() {
        val a = Framing.frame("""{"stop":{}}""")
        val b = Framing.frame("中文")
        assertEquals(0, a[0].toInt())
        assertEquals(11, a[3].toInt())
        val input = ByteArrayInputStream(a + b)
        assertContentEquals("""{"stop":{}}""".toByteArray(), Framing.read(input))
        assertEquals("中文", String(Framing.read(input), Charsets.UTF_8))
        assertFailsWith<EOFException> { Framing.read(input) }
        assertFailsWith<IllegalStateException> { Framing.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0))) }
    }
}
