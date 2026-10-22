package com.rameez.fit3zone.data

import com.rameez.fit3zone.APP_NAME
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.sqlite.transaction
import com.rameez.fit3zone.core.AlertTiming
import com.rameez.fit3zone.core.GeoPoint
import com.rameez.fit3zone.core.HrSample
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.Lap
import com.rameez.fit3zone.core.RouteTracker
import com.rameez.fit3zone.core.stepTimelineFromCadence
import com.rameez.fit3zone.core.ZoneSnapshot
import com.rameez.fit3zone.core.ZoneTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.rameez.fit3zone.core.ActivityType
import java.text.SimpleDateFormat
import java.util.Date
import java.io.File
import java.util.Locale

enum class SessionStatus { IN_PROGRESS, COMPLETED, INTERRUPTED }

/** Cadence every few seconds while moving, with the steps counted so far (null in rows saved before v9). */
data class CadenceSample(val offsetMs: Long, val spm: Int, val steps: Long?)

/** Activities recorded from v10 measure distance on a smoothed track (see RouteTracker). */
const val TRACK_VERSION = 2

/**
 * Rebuilds an activity's GPS track measured the same way as when it was recorded, so older activities keep their
 * splits and laps. [steps] (the step timeline) bridges GPS gaps.
 */
fun replayTrack(
    s: SessionSummary, points: List<GeoPoint>, samples: List<HrSample> = emptyList(), steps: List<Pair<Long, Long>> = emptyList(),
): RouteTracker = RouteTracker.replay(
    points, samples, s.segmentBreaksMs, steps,
    smoothFixes = if (s.trackVersion >= TRACK_VERSION) RouteTracker.SMOOTH_FIXES else RouteTracker.LEGACY,
)

/** Shorter activities (quick tests, false starts) go to Strava / Health Connect only when sent by hand. */
const val AUTO_SYNC_MIN_MS = 5 * 60_000L

const val SYNC_STRAVA = "strava"
const val SYNC_HEALTH = "health"

/** Words for the save screen's 1–10 effort scale. */
fun effortWord(effort: Int): String = when (effort) {
    in 1..2 -> "Easy"; in 3..4 -> "Moderate"; in 5..6 -> "Hard"; in 7..8 -> "Very hard"; else -> "Max effort"
}

data class SessionSummary(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val status: SessionStatus,
    val simulated: Boolean,
    /** Target range; 0/0 for a free run. */
    val lowerBpm: Int,
    val upperBpm: Int,
    /** "Zone 2", "Zones 2–3", "Custom"; null for a free run. Sessions before v3 were all Zone 2. */
    val targetLabel: String?,
    val activeMs: Long,
    val belowMs: Long,
    val inMs: Long,
    val aboveMs: Long,
    val staleMs: Long,
    /** Above + below alerts. */
    val alerts: Int,
    val belowAlerts: Int = 0,
    val interruptions: Int,
    val avgBpm: Int?,
    val minBpm: Int?,
    val maxBpm: Int?,
    val sampleIntervalMs: Long?,
    val distanceM: Double = 0.0,
    val hasGps: Boolean = false,
    /** Offsets where the session was resumed after a pause; GPS distance restarts there. */
    val segmentBreaksMs: List<Long> = emptyList(),
    /** Time in heart-rate zones 0..5; empty before v3. */
    val zoneMs: List<Long> = emptyList(),
    /** Zones in force during the session; null before v3 or without a profile. */
    val zones: HrZones? = null,
    val type: ActivityType = ActivityType.RUN,
    /** Name of the structured workout run in this session; null for target / free runs. */
    val workoutName: String? = null,
    /** From the phone's step detector (v5); null when not recorded. */
    val steps: Int? = null,
    val avgCadenceSpm: Int? = null,
    /** Name the user gave the activity (v7); null = [defaultTitle]. */
    val name: String? = null,
    /** Free-text note the user wrote about the activity (v8). */
    val note: String? = null,
    /** How GPS distance was measured: 1 = raw fixes (before v10), [TRACK_VERSION] = smoothed track. */
    val trackVersion: Int = 1,
    /** Metres of [distanceM] estimated from steps across GPS gaps (v10); null for older activities. */
    val bridgedM: Double? = null,
    /** Planned route followed in this activity (v11); the id may point to a route deleted since. */
    val routeId: Long? = null,
    val routeName: String? = null,
    /** Share of the route covered while on it, 0..1 (saved at the end). */
    val routeFollowed: Double? = null,
    /**
     * False from the start until the user saves it on the post-run screen (v12); Strava / Health Connect wait
     * for it. Activities from before v12 count as saved.
     */
    val reviewed: Boolean = true,
    /** How hard it felt, 1–10, from the save screen (v12). */
    val effort: Int? = null,
    /** Services the user turned off for this activity on the save screen: [SYNC_STRAVA], [SYNC_HEALTH]. */
    val skipSync: Set<String> = emptySet(),
) {
    /** Share of the distance estimated from steps, when it's enough to mention (≥ 5 %); else null. */
    val estimatedPercent: Int?
        get() {
            val b = bridgedM ?: return null
            if (distanceM < 100) return null
            return (b * 100 / distanceM).toInt().takeIf { it >= 5 }
        }

    val target: ZoneTarget? get() = targetLabel?.let { runCatching { ZoneTarget(lowerBpm, upperBpm, it) }.getOrNull() }

    val hasZoneTimes: Boolean get() = zoneMs.size == HrZones.COUNT + 1 && zoneMs.sum() > 0

    /** "Run · Zone 2", "Run · Free", "Run · 4 × 4 min Zone 4". */
    val defaultTitle: String get() = "${type.label} · ${workoutName ?: targetLabel ?: "Free"}"

    /** Shown everywhere and sent to Strava / Health Connect: the user's name, else [defaultTitle]. */
    val title: String get() = name ?: defaultTitle

    /** Average pace in s/km over the whole session, when GPS recorded a meaningful distance. */
    val avgPaceSecPerKm: Double?
        get() = if (distanceM >= 100) activeMs / 1000.0 / (distanceM / 1000.0) else null

    val inZonePercent: Int
        get() = (belowMs + inMs + aboveMs).let { if (it > 0) (inMs * 100 / it).toInt() else 0 }
}

/** Short text for the activity's description on Strava / notes in Health Connect; [device] = the HR sensor's name. */
fun SessionSummary.notes(device: SavedDevice? = null): String = buildList {
    note?.let { add(it); add("") }
    effort?.let { add("Effort $it/10 · ${effortWord(it)}") }
    val t = target
    when {
        workoutName != null -> add("Workout: $workoutName")
        t != null -> add("${t.label} ${t.lowerBpm}–${t.upperBpm} bpm · $inZonePercent% in target")
    }
    if (hasZoneTimes) {
        val total = zoneMs.drop(1).sum().coerceAtLeast(1)
        add((1..HrZones.COUNT).filter { zoneMs[it] > 0 }.joinToString(" · ") { "Z$it ${zoneMs[it] * 100 / total}%" })
    }
    estimatedPercent?.let { add("Distance partly estimated from steps ($it %, GPS was off for a while)") }
    add("Recorded with $APP_NAME" + (device?.displayName?.let { " + $it" } ?: ""))
}.joinToString("\n")

data class BpmStats(val avg: Int?, val min: Int?, val max: Int?)

/**
 * Local session history in a plain SQLite database (no cloud, excluded from backups).
 * All methods block; call them off the main thread.
 */
class SessionHistory private constructor(context: Context) :
    SQLiteOpenHelper(context, "sessions.db", null, DB_VERSION) {

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    /** All sessions, newest first. Refresh with [refresh]; writes refresh automatically. */
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL, ended_at INTEGER, status TEXT NOT NULL, simulated INTEGER NOT NULL,
                lower_bpm INTEGER NOT NULL, upper_bpm INTEGER NOT NULL, confirm_ms INTEGER NOT NULL, repeat_ms INTEGER NOT NULL,
                active_ms INTEGER NOT NULL DEFAULT 0, below_ms INTEGER NOT NULL DEFAULT 0, in_ms INTEGER NOT NULL DEFAULT 0,
                above_ms INTEGER NOT NULL DEFAULT 0, stale_ms INTEGER NOT NULL DEFAULT 0,
                alerts INTEGER NOT NULL DEFAULT 0, interruptions INTEGER NOT NULL DEFAULT 0,
                avg_bpm INTEGER, min_bpm INTEGER, max_bpm INTEGER, sample_interval_ms INTEGER)""",
        )
        db.execSQL(
            """CREATE TABLE samples (
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                offset_ms INTEGER NOT NULL, bpm INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX samples_session ON samples(session_id, offset_ms)")
        migrateToV2(db)
        migrateToV3(db)
        migrateToV4(db)
        migrateToV5(db)
        migrateToV6(db)
        migrateToV7(db)
        migrateToV8(db)
        migrateToV9(db)
        migrateToV10(db)
        migrateToV11(db)
        migrateToV12(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateToV2(db)
        if (oldVersion < 3) migrateToV3(db)
        if (oldVersion < 4) migrateToV4(db)
        if (oldVersion < 5) migrateToV5(db)
        if (oldVersion < 6) migrateToV6(db)
        if (oldVersion < 7) migrateToV7(db)
        if (oldVersion < 8) migrateToV8(db)
        if (oldVersion < 9) migrateToV9(db)
        if (oldVersion < 10) migrateToV10(db)
        if (oldVersion < 11) migrateToV11(db)
        if (oldVersion < 12) migrateToV12(db)
    }

    /** v12: the post-run save screen (saved yet, effort, services skipped for this activity). */
    private fun migrateToV12(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN reviewed INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE sessions ADD COLUMN effort INTEGER")
        db.execSQL("ALTER TABLE sessions ADD COLUMN skip_sync TEXT")
    }

    /** v11: planned routes (imported GPX, generated loops) and which one an activity followed. */
    private fun migrateToV11(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE routes (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL, source TEXT NOT NULL,
                distance_m REAL NOT NULL, ascent_m REAL, points TEXT NOT NULL)""",
        )
        db.execSQL("ALTER TABLE sessions ADD COLUMN route_id INTEGER")
        db.execSQL("ALTER TABLE sessions ADD COLUMN route_name TEXT")
        db.execSQL("ALTER TABLE sessions ADD COLUMN route_followed REAL")
    }

    /** v10: how the GPS distance was measured (null = before smoothing) and how much of it came from steps. */
    private fun migrateToV10(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN track_version INTEGER")
        db.execSQL("ALTER TABLE sessions ADD COLUMN bridged_m REAL")
    }

    /** v9: cumulative steps with each cadence sample (exact step timeline for bridging GPS gaps). */
    private fun migrateToV9(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE cadence_samples ADD COLUMN steps INTEGER")
    }

    /** v8: the user's notes on an activity. */
    private fun migrateToV8(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN note TEXT")
    }

    /** v7: activity names given by the user. */
    private fun migrateToV7(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN name TEXT")
    }

    /** v6: GPS altitude (elevation chart) and cadence over time (cadence chart). */
    private fun migrateToV6(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE gps_points ADD COLUMN altitude_m REAL")
        db.execSQL(
            """CREATE TABLE cadence_samples (
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                offset_ms INTEGER NOT NULL, spm INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX cadence_session ON cadence_samples(session_id, offset_ms)")
    }

    /** v5: steps and average cadence from the phone's step detector. */
    private fun migrateToV5(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN steps INTEGER")
        db.execSQL("ALTER TABLE sessions ADD COLUMN avg_cadence INTEGER")
    }

    /** v4: structured workouts and laps. */
    private fun migrateToV4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN workout_name TEXT")
        db.execSQL(
            """CREATE TABLE laps (
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                number INTEGER NOT NULL, start_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL,
                distance_m REAL NOT NULL, avg_bpm INTEGER, max_bpm INTEGER, label TEXT)""",
        )
        db.execSQL("CREATE INDEX laps_session ON laps(session_id, number)")
    }

    /** v3: multiple zones. Existing sessions were Zone 2 runs; free runs store 0/0 limits and no label. */
    private fun migrateToV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN target_label TEXT")
        db.execSQL("UPDATE sessions SET target_label = 'Zone 2'")
        db.execSQL("ALTER TABLE sessions ADD COLUMN zone_ms TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sessions ADD COLUMN zones TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sessions ADD COLUMN below_alerts INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN activity_type TEXT NOT NULL DEFAULT 'RUN'")
    }

    /** v2: GPS runs. */
    private fun migrateToV2(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN distance_m REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN has_gps INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN segment_breaks TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """CREATE TABLE gps_points (
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                offset_ms INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, accuracy_m REAL NOT NULL)""",
        )
        db.execSQL("CREATE INDEX gps_session ON gps_points(session_id, offset_ms)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun start(
        startedAt: Long, simulated: Boolean, target: ZoneTarget?, zones: HrZones?, timing: AlertTiming,
        sampleIntervalMs: Long?, hasGps: Boolean = false, type: ActivityType = ActivityType.RUN, workoutName: String? = null,
        routeId: Long? = null, routeName: String? = null,
    ): Long {
        val id = writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
            put("started_at", startedAt)
            put("status", SessionStatus.IN_PROGRESS.name)
            put("simulated", if (simulated) 1 else 0)
            putTarget(target)
            put("zones", zones?.encode() ?: "")
            put("activity_type", type.name)
            put("confirm_ms", timing.confirmMs)
            put("repeat_ms", timing.repeatMs)
            put("sample_interval_ms", sampleIntervalMs)
            put("has_gps", if (hasGps) 1 else 0)
            if (hasGps) put("track_version", TRACK_VERSION)
            put("workout_name", workoutName)
            put("route_id", routeId)
            put("route_name", routeName)
            put("reviewed", 0)
        })
        refresh()
        return id
    }

    /** Saves running totals and any new samples in one transaction. */
    fun update(
        id: Long, snapshot: ZoneSnapshot, target: ZoneTarget?, bpm: BpmStats, newSamples: List<HrSample>,
        status: SessionStatus = SessionStatus.IN_PROGRESS, endedAt: Long? = null,
        distanceM: Double = 0.0, newPoints: List<GeoPoint> = emptyList(), segmentBreaksMs: List<Long> = emptyList(),
        laps: List<Lap>? = null, steps: Int? = null, avgCadence: Int? = null,
        newCadence: List<CadenceSample> = emptyList(), bridgedM: Double? = null, routeFollowed: Double? = null,
    ) {
        writableDatabase.transaction {
            val db = this
            db.update("sessions", ContentValues().apply {
                put("status", status.name)
                put("ended_at", endedAt)
                putTarget(target)
                put("zone_ms", snapshot.zoneMs.joinToString(","))
                put("below_alerts", snapshot.belowAlerts)
                put("active_ms", snapshot.activeDurationMs)
                put("below_ms", snapshot.timeBelowMs)
                put("in_ms", snapshot.timeInMs)
                put("above_ms", snapshot.timeAboveMs)
                put("stale_ms", snapshot.timeStaleMs)
                put("alerts", snapshot.alertCount)
                put("interruptions", snapshot.dataInterruptions)
                put("avg_bpm", bpm.avg)
                put("min_bpm", bpm.min)
                put("max_bpm", bpm.max)
                put("distance_m", distanceM)
                put("segment_breaks", segmentBreaksMs.joinToString(","))
                put("steps", steps)
                put("avg_cadence", avgCadence)
                bridgedM?.let { put("bridged_m", it) }
                routeFollowed?.let { put("route_followed", it) }
            }, "id = ?", arrayOf(id.toString()))
            val insert = db.compileStatement("INSERT INTO samples(session_id, offset_ms, bpm) VALUES (?, ?, ?)")
            for (s in newSamples) {
                insert.bindLong(1, id)
                insert.bindLong(2, s.offsetMs)
                insert.bindLong(3, s.bpm.toLong())
                insert.executeInsert()
                insert.clearBindings()
            }
            val gps = db.compileStatement("INSERT INTO gps_points(session_id, offset_ms, lat, lon, accuracy_m, altitude_m) VALUES (?, ?, ?, ?, ?, ?)")
            for (p in newPoints) {
                gps.bindLong(1, id)
                gps.bindLong(2, p.offsetMs)
                gps.bindDouble(3, p.lat)
                gps.bindDouble(4, p.lon)
                gps.bindDouble(5, p.accuracyM.toDouble())
                p.altitudeM?.let { gps.bindDouble(6, it) } ?: gps.bindNull(6)
                gps.executeInsert()
                gps.clearBindings()
            }
            val cad = db.compileStatement("INSERT INTO cadence_samples(session_id, offset_ms, spm, steps) VALUES (?, ?, ?, ?)")
            for ((t, spm, steps) in newCadence) {
                cad.bindLong(1, id)
                cad.bindLong(2, t)
                cad.bindLong(3, spm.toLong())
                steps?.let { cad.bindLong(4, it) } ?: cad.bindNull(4)
                cad.executeInsert()
                cad.clearBindings()
            }
            // Laps are few: the full list replaces what was stored.
            if (laps != null) {
                db.delete("laps", "session_id = ?", arrayOf(id.toString()))
                for (l in laps) db.insert("laps", null, ContentValues().apply {
                    put("session_id", id); put("number", l.number); put("start_ms", l.startMs); put("duration_ms", l.durationMs)
                    put("distance_m", l.distanceM); put("avg_bpm", l.avgBpm); put("max_bpm", l.maxBpm); put("label", l.label)
                })
            }
        }
        if (status != SessionStatus.IN_PROGRESS) refresh()
    }

    private fun ContentValues.putTarget(target: ZoneTarget?) {
        put("lower_bpm", target?.lowerBpm ?: 0)
        put("upper_bpm", target?.upperBpm ?: 0)
        put("target_label", target?.label)
    }

    /** A session still IN_PROGRESS at process start belonged to a process that died. */
    fun markAbandonedAsInterrupted() {
        writableDatabase.update(
            "sessions",
            // Nobody saw a save screen for these; they sync like before (only finished ones go anywhere).
            ContentValues().apply { put("status", SessionStatus.INTERRUPTED.name); put("reviewed", 1) },
            "status = ?", arrayOf(SessionStatus.IN_PROGRESS.name),
        )
        refresh()
    }

    /** Corrects the activity type afterwards (e.g. a walk recorded as a run). */
    fun setType(id: Long, type: ActivityType) {
        writableDatabase.update("sessions", ContentValues().apply { put("activity_type", type.name) }, "id = ?", arrayOf(id.toString()))
        refresh()
    }

    /** Renames an activity; a blank name goes back to the default title. */
    fun setName(id: Long, name: String?) {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        writableDatabase.update("sessions", ContentValues().apply { put("name", clean) }, "id = ?", arrayOf(id.toString()))
        refresh()
    }

    /** Sets the activity's note; blank removes it. */
    fun setNote(id: Long, note: String?) {
        val clean = note?.trim()?.takeIf { it.isNotEmpty() }
        writableDatabase.update("sessions", ContentValues().apply { put("note", clean) }, "id = ?", arrayOf(id.toString()))
        refresh()
    }

    /** The save screen's result: name, note and effort, which services to skip, and the activity counts as saved. */
    fun review(id: Long, name: String?, note: String?, effort: Int?, skipSync: Set<String>) {
        writableDatabase.update("sessions", ContentValues().apply {
            put("name", name?.trim()?.takeIf { it.isNotEmpty() })
            put("note", note?.trim()?.takeIf { it.isNotEmpty() })
            put("effort", effort)
            put("skip_sync", skipSync.sorted().joinToString(",").ifEmpty { null })
            put("reviewed", 1)
        }, "id = ?", arrayOf(id.toString()))
        refresh()
    }

    fun delete(id: Long) {
        writableDatabase.delete("sessions", "id = ?", arrayOf(id.toString()))
        refresh()
    }

    /** A consistent copy of the whole database (including what's still in the WAL) for a backup. */
    fun snapshotTo(dest: File) {
        dest.delete()
        writableDatabase.execSQL("VACUUM INTO ?", arrayOf(dest.absolutePath))
    }

    fun points(id: Long): List<GeoPoint> = points(readableDatabase, id)

    /** (time, cumulative steps) for bridging GPS gaps; estimated from cadence for activities saved before v9. */
    fun stepTimeline(id: Long): List<Pair<Long, Long>> = stepTimeline(readableDatabase, id)

    private fun stepTimeline(db: SQLiteDatabase, id: Long): List<Pair<Long, Long>> {
        val rows = db.rawQuery("SELECT offset_ms, spm, steps FROM cadence_samples WHERE session_id = ? ORDER BY offset_ms", arrayOf(id.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getInt(1), c.getLongOrNull(2))) } }
        if (rows.isEmpty()) return emptyList()
        if (rows.all { it.third != null }) return listOf(0L to 0L) + rows.map { it.first to it.third!! }.filter { it.first > 0 }
        return stepTimelineFromCadence(rows.map { it.first to it.second })
    }

    private fun points(db: SQLiteDatabase, id: Long): List<GeoPoint> =
        db.rawQuery(
            "SELECT offset_ms, lat, lon, accuracy_m, altitude_m FROM gps_points WHERE session_id = ? ORDER BY offset_ms", arrayOf(id.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(GeoPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getFloat(3), c.getDoubleOrNull(4))) } }

    /** Cadence every 5 s (steps per minute), oldest first. */
    fun cadence(id: Long): List<Pair<Long, Int>> =
        readableDatabase.rawQuery(
            "SELECT offset_ms, spm FROM cadence_samples WHERE session_id = ? ORDER BY offset_ms", arrayOf(id.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getInt(1)) } }

    fun laps(id: Long): List<Lap> =
        readableDatabase.rawQuery(
            "SELECT number, start_ms, duration_ms, distance_m, avg_bpm, max_bpm, label FROM laps WHERE session_id = ? ORDER BY number",
            arrayOf(id.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Lap(c.getInt(0), c.getLong(1), c.getLong(2), c.getDouble(3), c.getIntOrNull(4), c.getIntOrNull(5), c.getString(6)),
                )
            }
        }

    fun samples(id: Long): List<HrSample> =
        readableDatabase.rawQuery(
            "SELECT offset_ms, bpm FROM samples WHERE session_id = ? ORDER BY offset_ms", arrayOf(id.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(HrSample(c.getLong(0), c.getInt(1))) } }

    fun refresh() {
        _sessions.value = readableDatabase.rawQuery("SELECT * FROM sessions ORDER BY started_at DESC", null).use { c ->
            fun col(name: String) = c.getColumnIndexOrThrow(name)
            buildList {
                while (c.moveToNext()) add(
                    SessionSummary(
                        id = c.getLong(col("id")),
                        startedAt = c.getLong(col("started_at")),
                        endedAt = c.getLongOrNull(col("ended_at")),
                        status = runCatching { SessionStatus.valueOf(c.getString(col("status"))) }
                            .getOrDefault(SessionStatus.INTERRUPTED),
                        simulated = c.getInt(col("simulated")) == 1,
                        lowerBpm = c.getInt(col("lower_bpm")),
                        upperBpm = c.getInt(col("upper_bpm")),
                        targetLabel = c.getString(col("target_label")),
                        activeMs = c.getLong(col("active_ms")),
                        belowMs = c.getLong(col("below_ms")),
                        inMs = c.getLong(col("in_ms")),
                        aboveMs = c.getLong(col("above_ms")),
                        staleMs = c.getLong(col("stale_ms")),
                        alerts = c.getInt(col("alerts")),
                        belowAlerts = c.getInt(col("below_alerts")),
                        interruptions = c.getInt(col("interruptions")),
                        avgBpm = c.getIntOrNull(col("avg_bpm")),
                        minBpm = c.getIntOrNull(col("min_bpm")),
                        maxBpm = c.getIntOrNull(col("max_bpm")),
                        sampleIntervalMs = c.getLongOrNull(col("sample_interval_ms")),
                        distanceM = c.getDouble(col("distance_m")),
                        hasGps = c.getInt(col("has_gps")) == 1,
                        segmentBreaksMs = c.getString(col("segment_breaks")).split(",").mapNotNull { it.toLongOrNull() },
                        zoneMs = c.getString(col("zone_ms")).split(",").mapNotNull { it.toLongOrNull() },
                        zones = HrZones.decode(c.getString(col("zones"))),
                        type = ActivityType.parse(c.getString(col("activity_type"))),
                        workoutName = c.getString(col("workout_name")),
                        steps = c.getIntOrNull(col("steps")),
                        avgCadenceSpm = c.getIntOrNull(col("avg_cadence")),
                        name = c.getString(col("name")),
                        note = c.getString(col("note")),
                        trackVersion = c.getIntOrNull(col("track_version")) ?: 1,
                        bridgedM = c.getDoubleOrNull(col("bridged_m")),
                        routeId = c.getLongOrNull(col("route_id")),
                        routeName = c.getString(col("route_name")),
                        routeFollowed = c.getDoubleOrNull(col("route_followed")),
                        reviewed = c.getInt(col("reviewed")) == 1,
                        effort = c.getIntOrNull(col("effort")),
                        skipSync = c.getString(col("skip_sync"))?.split(",")?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
                    ),
                )
            }
        }
    }

    /** CSV for user-initiated export: a commented summary header, then offset/bpm rows. */
    fun toCsv(s: SessionSummary): String = buildString {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        appendLine("# $APP_NAME session ${s.id}${if (s.simulated) " (SIMULATED)" else ""}")
        appendLine("# started ${iso.format(Date(s.startedAt))}, status ${s.status}")
        val target = s.workoutName?.let { "workout \"$it\"" } ?: s.target?.let { "${it.label} ${it.lowerBpm}-${it.upperBpm} bpm" } ?: "free run"
        appendLine("# target $target, active ${s.activeMs / 1000}s, below ${s.belowMs / 1000}s, in ${s.inMs / 1000}s, above ${s.aboveMs / 1000}s, no-data ${s.staleMs / 1000}s")
        if (s.hasZoneTimes) appendLine("# time in zones 0-5 (s): ${s.zoneMs.joinToString(" ") { (it / 1000).toString() }}")
        s.zones?.let { z -> appendLine("# zone floors Z1-Z5 ${z.floors.joinToString("/")} bpm, max ${z.maxHr}") }
        if (s.hasGps) appendLine("# distance ${"%.2f".format(Locale.US, s.distanceM / 1000)} km, avg pace ${com.rameez.fit3zone.core.formatPace(s.avgPaceSecPerKm)} /km")
        appendLine("# type ${s.type.label}")
        s.name?.let { appendLine("# name $it") }
        s.note?.let { appendLine("# note ${it.replace("\n", " / ")}") }
        s.steps?.let { appendLine("# steps $it, avg cadence ${s.avgCadenceSpm ?: "-"} spm") }
        com.rameez.fit3zone.core.ElevationProfile.of(points(s.id))?.let { e ->
            appendLine("# elevation gain ${e.gainM.toInt()} m, loss ${e.lossM.toInt()} m (GPS, smoothed)")
        }
        appendLine("# alerts ${s.alerts} (below ${s.belowAlerts}), data gaps ${s.interruptions}, avg ${s.avgBpm ?: "-"} min ${s.minBpm ?: "-"} max ${s.maxBpm ?: "-"} bpm")
        laps(s.id).forEach { l ->
            appendLine("# lap ${l.number}${l.label?.let { " ($it)" } ?: ""}: ${l.durationMs / 1000}s, ${"%.0f".format(Locale.US, l.distanceM)} m, avg ${l.avgBpm ?: "-"} max ${l.maxBpm ?: "-"} bpm")
        }
        appendLine("seconds_from_start,bpm")
        samples(s.id).forEach { appendLine("${it.offsetMs / 1000.0},${it.bpm}") }
    }

    companion object {
        /** All history access goes through this one-at-a-time dispatcher, so writes stay in order. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val dispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

        const val DB_VERSION = 12
        @Volatile private var instance: SessionHistory? = null
        fun get(context: Context): SessionHistory =
            instance ?: synchronized(this) { instance ?: SessionHistory(context.applicationContext).also { instance = it } }
    }
}
