package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionRecorderTest {

    @Test fun `averages readings into buckets`() {
        val r = SessionRecorder(5_000)
        listOf(130, 132, 134, 136, 138).forEachIndexed { i, bpm -> r.add(i * 1_000L, bpm) }
        listOf(150, 152).forEachIndexed { i, bpm -> r.add(5_000L + i * 1_000, bpm) }
        assertEquals(listOf(HrSample(0, 134)), r.drain(), "first bucket closes when the second starts")
        assertEquals(emptyList(), r.drain())
        assertEquals(listOf(HrSample(5_000, 151)), r.finish())
    }

    @Test fun `gaps leave no empty buckets`() {
        val r = SessionRecorder(5_000)
        r.add(0, 120)
        r.add(30_000, 140)
        assertEquals(listOf(HrSample(0, 120), HrSample(30_000, 140)), r.finish())
    }

    @Test fun `one second interval keeps every reading`() {
        val r = SessionRecorder(1_000)
        r.add(0, 120); r.add(1_000, 121); r.add(2_000, 122)
        assertEquals(listOf(120, 121, 122), r.finish().map { it.bpm })
    }

    @Test fun `summary stats are kept with samples off`() {
        val r = SessionRecorder(null)
        r.add(0, 120); r.add(1_000, 150); r.add(2_000, 141)
        assertEquals(emptyList(), r.finish())
        assertEquals(3, r.count)
        assertEquals(137, r.avgBpm)
        assertEquals(150, r.maxBpm)
        assertEquals(120, r.minBpm)
    }

    @Test fun `empty recorder`() {
        val r = SessionRecorder(5_000)
        assertNull(r.avgBpm)
        assertEquals(emptyList(), r.finish())
    }
}
