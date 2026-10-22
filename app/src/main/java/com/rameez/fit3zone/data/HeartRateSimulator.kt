package com.rameez.fit3zone.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Feeds a BPM sequence into the repository, tagged SIMULATED. Each value is repeated
 * [samplesPerStep] times at [intervalMs] so a step is long enough to exercise confirmation timers.
 */
class HeartRateSimulator(private val scope: CoroutineScope, private val repo: HeartRateRepository = HeartRateRepository) {
    private var job: Job? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start(sequence: List<Int>, intervalMs: Long = 1_000, samplesPerStep: Int = 5) {
        stop()
        repo.log.add("sim", "start $sequence, ${samplesPerStep}x every ${intervalMs}ms")
        _running.value = true
        job = scope.launch {
            try {
                for (bpm in sequence) repeat(samplesPerStep) {
                    repo.onSimulatedBpm(bpm)
                    delay(intervalMs)
                }
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
    }
}
