package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElevationTest {
    private fun pt(t: Long, alt: Double?) = GeoPoint(t, 19.0, 72.8, 5f, alt)
    private fun near(e: Double, a: Double, tol: Double) = assertTrue(abs(e - a) <= tol, "expected $e ± $tol, was $a")

    @Test fun `steady climb counts once`() {
        // 0 → 30 m over 300 s, one fix a second
        val p = ElevationProfile.of((0..300).map { pt(it * 1000L, it * 0.1) })!!
        near(30.0, p.gainM, 4.0)
        near(0.0, p.lossM, 0.1)
    }

    @Test fun `gps noise on flat ground adds no climbing`() {
        // ±4 m jitter around 10 m (deterministic pseudo-noise) plus single 25 m spikes
        val p = ElevationProfile.of((0..600).map { i ->
            val noise = 4 * sin(i * 1.7) * sin(i * 0.31)
            pt(i * 1000L, 10 + noise + if (i % 97 == 0) 25.0 else 0.0)
        })!!
        assertTrue(p.gainM < 6, "gain ${p.gainM}")
    }

    @Test fun `needs altitude`() {
        assertNull(ElevationProfile.of((0..50).map { pt(it * 1000L, null) }))
    }
}
