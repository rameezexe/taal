package com.rameez.fit3zone.data

import android.content.Context
import com.rameez.fit3zone.core.Achievement
import com.rameez.fit3zone.core.Achievements
import com.rameez.fit3zone.core.ActivityType
import com.rameez.fit3zone.core.RunFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Strava-style badges ("Fastest 5 km", "Longest run") for every real run, kept up to date as history changes.
 * Best efforts need a replay of each run's GPS track; those are cached per activity and distance.
 */
class AchievementStore private constructor(context: Context) {
    private val history = SessionHistory.get(context)
    private val _byId = MutableStateFlow<Map<Long, List<Achievement>>>(emptyMap())
    val byId: StateFlow<Map<Long, List<Achievement>>> = _byId.asStateFlow()

    /** (session id, distance) → fastest efforts; a changed distance (re-measured track) replays again. */
    private val effortCache = HashMap<Pair<Long, Double>, Map<Double, Long>>()

    fun watch(scope: CoroutineScope) {
        scope.launch {
            history.sessions.collect { list ->
                val runs = list.filter {
                    !it.simulated && it.status != SessionStatus.IN_PROGRESS && it.type == ActivityType.RUN && it.distanceM > 0
                }
                val facts = withContext(SessionHistory.dispatcher) {
                    runs.map { s ->
                        val efforts = if (!s.hasGps || s.distanceM < Achievements.EFFORTS.first().first) emptyMap()
                        else effortCache.getOrPut(s.id to s.distanceM) {
                            val track = replayTrack(s, history.points(s.id), steps = history.stepTimeline(s.id))
                            Achievements.EFFORTS.mapNotNull { (m, _) -> track.bestEffortMs(m)?.let { m to it } }.toMap()
                        }
                        RunFacts(s.id, s.startedAt, s.distanceM, efforts)
                    }
                }
                _byId.value = Achievements.forAll(facts, ZoneId.systemDefault())
            }
        }
    }

    companion object {
        @Volatile private var instance: AchievementStore? = null
        fun get(context: Context): AchievementStore =
            instance ?: synchronized(this) { instance ?: AchievementStore(context.applicationContext).also { instance = it } }
    }
}
