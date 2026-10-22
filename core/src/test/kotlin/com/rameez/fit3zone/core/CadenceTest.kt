package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CadenceTest {
    @Test fun `steady 170 spm`() {
        val c = CadenceTracker()
        var t = 0L
        repeat(60) { c.onStep(t); t += 353 } // 60 000 / 170 ≈ 353 ms per step
        assertEquals(169, c.current(t - 353)!! , "within 1 spm") // last step just happened
        assertEquals(60, c.steps)
    }

    @Test fun `null until enough steps, zero after stopping`() {
        val c = CadenceTracker()
        assertNull(c.current(0))
        c.onStep(0); c.onStep(400)
        assertNull(c.current(500))
        c.onStep(800); c.onStep(1_200)
        assertEquals(150, c.current(1_300))
        assertEquals(0, c.current(5_000))
    }

    @Test fun `window follows a change of pace`() {
        val c = CadenceTracker(windowMs = 10_000)
        var t = 0L
        repeat(40) { c.onStep(t); t += 500 } // 120 spm for 20 s
        repeat(40) { c.onStep(t); t += 333 } // then ~180 spm for 13 s
        assertEquals(180, c.current(t - 333)!!.let { if (it in 178..181) 180 else it })
    }

    @Test fun `average over active time`() {
        val c = CadenceTracker()
        repeat(330) { c.onStep(it * 360L) }
        assertNull(c.average(30_000))
        assertEquals(165, c.average(120_000))
    }
}
