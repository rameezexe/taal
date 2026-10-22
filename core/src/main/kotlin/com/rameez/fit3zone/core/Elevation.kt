package com.rameez.fit3zone.core

import kotlin.math.max
import kotlin.math.min

/**
 * Smoothed GPS elevation for an activity. Phone GPS altitude is noisy (±5–15 m), so samples are
 * median-filtered then averaged over [windowMs], and gain/loss only count climbs or descents of at
 * least [hysteresisM] (small wiggles never add up to fake metres of climbing).
 */
data class ElevationProfile(
    /** (offset ms, metres) after smoothing. */
    val samples: List<Pair<Long, Double>>,
    val gainM: Double,
    val lossM: Double,
    val minM: Double,
    val maxM: Double,
) {
    companion object {
        fun of(points: List<GeoPoint>, windowMs: Long = 20_000, hysteresisM: Double = 3.0): ElevationProfile? {
            val raw = points.mapNotNull { p -> p.altitudeM?.let { p.offsetMs to it } }
            if (raw.size < 10) return null
            // Median of 5 removes single-fix spikes.
            val median = raw.indices.map { i ->
                val w = raw.subList(max(0, i - 2), min(raw.size, i + 3)).map { it.second }.sorted()
                raw[i].first to w[w.size / 2]
            }
            // Centered moving average over the time window.
            var lo = 0
            var hi = 0
            var sum = 0.0
            val smooth = median.map { (t, _) ->
                while (hi < median.size && median[hi].first <= t + windowMs / 2) { sum += median[hi].second; hi++ }
                while (median[lo].first < t - windowMs / 2) { sum -= median[lo].second; lo++ }
                t to sum / (hi - lo)
            }
            var gain = 0.0
            var loss = 0.0
            var ref = smooth.first().second
            for ((_, h) in smooth) {
                if (h - ref >= hysteresisM) { gain += h - ref; ref = h }
                else if (ref - h >= hysteresisM) { loss += ref - h; ref = h }
            }
            return ElevationProfile(smooth, gain, loss, smooth.minOf { it.second }, smooth.maxOf { it.second })
        }
    }
}
