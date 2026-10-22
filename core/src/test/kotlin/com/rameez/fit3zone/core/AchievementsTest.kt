package com.rameez.fit3zone.core

import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AchievementsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(month: Int, day: Int) = LocalDateTime.of(2026, month, day, 18, 0).atZone(zone).toInstant().toEpochMilli()
    private fun run(id: Long, start: Long, km: Double, oneKmSec: Long? = null, fiveKmSec: Long? = null) = RunFacts(
        id, start, km * 1000,
        buildMap {
            oneKmSec?.let { put(1_000.0, it * 1000) }
            fiveKmSec?.let { put(5_000.0, it * 1000) }
        },
    )

    @Test fun `first run earns nothing`() {
        val r = run(1, at(10, 1), 5.0, 360, 1900)
        assertTrue(Achievements.of(r, listOf(r), zone).isEmpty())
    }

    @Test fun `ranks efforts against earlier runs only`() {
        val a = run(1, at(10, 1), 5.0, 360, 1900)
        val b = run(2, at(10, 5), 5.0, 340, 1950)
        val c = run(3, at(10, 9), 3.0, 350)
        val all = listOf(a, b, c)
        val bBadges = Achievements.of(b, all, zone)
        assertEquals(listOf("Fastest 1 km"), bBadges.filter { it.kind == AchievementKind.EFFORT }.map { it.label })
        val cBadges = Achievements.of(c, all, zone)
        assertEquals("2nd fastest 1 km", cBadges.single().label)
        assertEquals(340_000L, cBadges.single().bestMs)
        // A later, slower run doesn't change b's record.
        assertTrue(Achievements.of(b, all, zone).first().isRecord)
    }

    @Test fun `slowest of two gets no badge`() {
        val a = run(1, at(10, 1), 2.0, 300)
        val b = run(2, at(10, 2), 1.5, 320)
        assertTrue(Achievements.of(b, listOf(a, b), zone).isEmpty())
    }

    @Test fun `fourth fastest gets no badge`() {
        val runs = listOf(300L, 310L, 320L, 330L).mapIndexed { i, s -> run(i.toLong(), at(10, 1 + i), 2.0, s) }
        assertTrue(Achievements.of(runs.last(), runs, zone).none { it.kind == AchievementKind.EFFORT })
    }

    @Test fun `longest run ever and longest this month`() {
        val sept = run(1, at(9, 20), 8.0)
        val oct1 = run(2, at(10, 1), 5.0)
        val oct2 = run(3, at(10, 10), 6.0)
        val oct3 = run(4, at(10, 15), 9.0)
        val all = listOf(sept, oct1, oct2, oct3)
        assertEquals("Longest run this month", Achievements.of(oct2, all, zone).single().label)
        assertEquals("Longest run", Achievements.of(oct3, all, zone).single().label)
        // First run of a month has no month rival: nothing.
        assertTrue(Achievements.of(oct1, all, zone).isEmpty())
    }

    @Test fun `records sort before other ranks`() {
        val a = run(1, at(10, 1), 5.0, 330, 1800)
        val z = run(9, at(10, 1) - 1, 3.0, 400)
        val b = run(2, at(10, 2), 6.0, 340, 1750)
        val labels = Achievements.of(b, listOf(z, a, b), zone).map { it.label }
        assertEquals(listOf("Fastest 5 km", "Longest run", "2nd fastest 1 km"), labels)
    }
}
