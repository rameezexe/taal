package com.rameez.fit3zone.data

import java.time.ZoneId
import java.time.Instant
import com.rameez.fit3zone.core.RestingEstimate
import com.rameez.fit3zone.core.BandRestingHr
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.edit
import androidx.core.database.sqlite.transaction
import com.rameez.fit3zone.core.BandDecoder
import com.rameez.fit3zone.core.BandDecoderStats
import com.rameez.fit3zone.core.BandExerciseRecord
import com.rameez.fit3zone.core.BandHrRecord
import com.rameez.fit3zone.core.BandRecord
import com.rameez.fit3zone.core.BandSleepRecord
import com.rameez.fit3zone.core.BandSleepStage
import com.rameez.fit3zone.core.BandSpo2Record
import com.rameez.fit3zone.core.BandStepsRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

data class BandDataState(
    val enabled: Boolean = false,
    val stats: BandDecoderStats = BandDecoderStats(),
    val unknownTypes: Set<Int> = emptySet(),
    /** Wall clock of the last notification that produced records. */
    val lastRecordsAt: Long? = null,
    /** Bumped after every DB write so screens can reload. */
    val revision: Int = 0,
)

/** One heart-rate point for a chart: a minute bin (min–max) or a spot reading (min == max). */
data class BandHrPoint(val tMs: Long, val min: Int, val max: Int, val spot: Boolean)

data class BandSpo2Point(val tMs: Long, val spo2: Int, val heartRate: Int?)

/** The night that ended on this day (combined record preferred), with its stage intervals if synced. */
data class BandNight(
    val startMs: Long,
    val endMs: Long,
    val score: Int?,
    val efficiency: Float?,
    val durationMin: Int?,
    val stages: List<BandSleepStage>,
)

data class BandWorkout(
    val startMs: Long,
    val endMs: Long,
    val exerciseType: Int,
    val durationMs: Long,
    val distanceM: Float?,
    val kcal: Float?,
    val meanHr: Float?,
    val maxHr: Float?,
)

data class BandDay(
    val hr: List<BandHrPoint>,
    val steps: Int,
    val distanceM: Double,
    /** Steps per hour of the day, 24 entries. */
    val stepsByHour: List<Int>,
    val lastHrAt: Long?,
    val lastStepsAt: Long?,
    val spo2: List<BandSpo2Point> = emptyList(),
    val night: BandNight? = null,
    val workouts: List<BandWorkout> = emptyList(),
) {
    val hrMin: Int? get() = hr.minOfOrNull { it.min }
    val hrMax: Int? get() = hr.maxOfOrNull { it.max }
    val isEmpty: Boolean get() = hr.isEmpty() && steps == 0 && spo2.isEmpty() && night == null && workouts.isEmpty()
}

/**
 * Health data decoded from the band's own SAP traffic (passive, read-only).
 * The BLE manager forwards SAP notifications here; records are decoded and stored in `band.db`
 * on a single thread. Nothing is ever written to the band.
 */
object BandData {
    private val _state = MutableStateFlow(BandDataState())
    val state: StateFlow<BandDataState> = _state.asStateFlow()

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "band-data") }
    private val decoder = BandDecoder() // touched on [io] only
    private var db: BandDb? = null
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        db = BandDb(context.applicationContext)
        val prefs = prefs(context)
        // Opt-in for new installs. Installs from before the switch existed relied on "on" and have a Fit3 saved.
        if (!prefs.contains(KEY_ENABLED)) prefs.edit { putBoolean(KEY_ENABLED, DevicePrefs(context).device?.kind == DeviceKind.FIT3) }
        _state.update { it.copy(enabled = prefs.getBoolean(KEY_ENABLED, false)) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        _state.update { it.copy(enabled = enabled) }
    }

    val isEnabled: Boolean get() = _state.value.enabled

    /** Copies band.db for a backup (on the band-data thread, so no write is half done); false without one. */
    fun snapshotTo(context: Context, dest: File): Boolean {
        load(context)
        return io.submit<Boolean> {
            val d = db ?: return@submit false
            dest.delete()
            runCatching { d.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(dest.absolutePath)) }.isSuccess
        }.get()
    }

    /** Before an import replaces band.db; the app restarts right after. */
    fun close() {
        io.submit { db?.close() }.get()
    }

    /** A notification from the SAP characteristic (any thread; callbacks arrive in order). */
    fun onSapNotification(value: ByteArray) {
        if (!isEnabled) return
        val copy = value.copyOf()
        io.execute {
            val records = runCatching { decoder.onNotification(copy) }.getOrDefault(emptyList())
            if (records.isNotEmpty()) runCatching { db?.store(records) }
            _state.update {
                it.copy(
                    stats = decoder.stats,
                    unknownTypes = decoder.unknownHealthTypes.toSet(),
                    lastRecordsAt = if (records.isNotEmpty()) System.currentTimeMillis() else it.lastRecordsAt,
                    revision = if (records.isNotEmpty()) it.revision + 1 else it.revision,
                )
            }
        }
    }

    /**
     * Backfill from passive capture files (the .log files in files/sap): replays the SAP notification lines through a
     * fresh decoder per file. Returns the number of records stored. Blocking; call off the main thread.
     */
    fun importCaptures(context: Context, files: List<File>): Int {
        load(context)
        var stored = 0
        for (file in files) {
            val decoder = BandDecoder()
            val records = mutableListOf<BandRecord>()
            file.forEachLine { line ->
                if (line.startsWith("#")) return@forEachLine
                val parts = line.split(' ')
                if (parts.size == 5 && parts[2] == SAP_SOURCE) runCatching { records += decoder.onNotification(hex(parts[4])) }
            }
            db!!.store(records)
            stored += records.size
        }
        _state.update { it.copy(revision = it.revision + 1) }
        return stored
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Blocking; call off the main thread. [startMs, endMs) is usually one local day. */
    fun day(context: Context, startMs: Long, endMs: Long): BandDay {
        load(context)
        return db!!.day(startMs, endMs)
    }

    /** Resting HR from the last 14 days of band minute data; null without a well-covered day. Blocking. */
    fun restingEstimate(context: Context, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): RestingEstimate? {
        load(context)
        val bins = db!!.hrBins(nowMs - 15 * 86_400_000L, nowMs)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return BandRestingHr.estimate(BandRestingHr.daily(bins, zone), today)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("band_data", Context.MODE_PRIVATE)
    private const val KEY_ENABLED = "enabled"
    /** How SapCapture labels the SAP notify characteristic. */
    private const val SAP_SOURCE = "1a1a/797ae4e9"
}

private class BandDb(context: Context) : SQLiteOpenHelper(context, "band.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        // A continuous HR record is resent with more bins as its hour fills, so bins are keyed by
        // (record start, index) and replaced; bins are append-only in the captures.
        db.execSQL(
            "CREATE TABLE hr_bins (record_start INTEGER NOT NULL, idx INTEGER NOT NULL, t_ms INTEGER NOT NULL, " +
                "avg INTEGER NOT NULL, min INTEGER NOT NULL, max INTEGER NOT NULL, received_ms INTEGER NOT NULL, " +
                "PRIMARY KEY (record_start, idx))",
        )
        db.execSQL("CREATE INDEX hr_bins_t ON hr_bins (t_ms)")
        db.execSQL("CREATE TABLE hr_spots (t_ms INTEGER PRIMARY KEY, bpm INTEGER NOT NULL, received_ms INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE steps (start_ms INTEGER PRIMARY KEY, end_ms INTEGER NOT NULL, steps INTEGER NOT NULL, " +
                "run_steps INTEGER NOT NULL, walk_steps INTEGER NOT NULL, distance_m REAL NOT NULL, kcal REAL NOT NULL, " +
                "active_ms INTEGER NOT NULL, received_ms INTEGER NOT NULL)",
        )
        createV2(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
    }

    /** v2: SpO2, band workouts (summary + live samples), sleep sessions and stages. */
    private fun createV2(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE spo2 (t_ms INTEGER PRIMARY KEY, end_ms INTEGER NOT NULL, spo2 INTEGER NOT NULL, hr INTEGER, " +
                "min INTEGER, max INTEGER, received_ms INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE exercises (start_ms INTEGER PRIMARY KEY, end_ms INTEGER NOT NULL, type INTEGER NOT NULL, " +
                "duration_ms INTEGER NOT NULL, distance_m REAL, kcal REAL, mean_hr REAL, max_hr REAL, min_hr REAL, " +
                "mean_speed REAL, max_speed REAL, mean_cadence REAL, track_points INTEGER NOT NULL, received_ms INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE exercise_samples (exercise_start INTEGER NOT NULL, t_ms INTEGER NOT NULL, hr REAL, cadence REAL, " +
                "speed REAL, distance_m REAL, PRIMARY KEY (exercise_start, t_ms))",
        )
        db.execSQL(
            "CREATE TABLE sleep (start_ms INTEGER NOT NULL, combined INTEGER NOT NULL, end_ms INTEGER NOT NULL, " +
                "efficiency REAL, score INTEGER, duration_min INTEGER, cycles INTEGER, rem_min INTEGER, " +
                "received_ms INTEGER NOT NULL, PRIMARY KEY (start_ms, combined))",
        )
        db.execSQL("CREATE TABLE sleep_stages (start_ms INTEGER PRIMARY KEY, end_ms INTEGER NOT NULL, stage INTEGER NOT NULL)")
    }

    fun store(records: List<BandRecord>) {
        val now = System.currentTimeMillis()
        writableDatabase.transaction {
            for (r in records) when (r) {
                is BandHrRecord -> if (r.isSpot) {
                    insertWithOnConflict("hr_spots", null, ContentValues().apply {
                        put("t_ms", r.startMs); put("bpm", r.avg); put("received_ms", now)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                } else r.bins.forEachIndexed { i, b ->
                    insertWithOnConflict("hr_bins", null, ContentValues().apply {
                        put("record_start", r.startMs); put("idx", i); put("t_ms", r.startMs + b.offsetMin * 60_000L)
                        put("avg", b.avg); put("min", b.min); put("max", b.max); put("received_ms", now)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                is BandStepsRecord -> insertWithOnConflict("steps", null, ContentValues().apply {
                    put("start_ms", r.startMs); put("end_ms", r.endMs); put("steps", r.steps)
                    put("run_steps", r.runSteps); put("walk_steps", r.walkSteps); put("distance_m", r.distanceM.toDouble())
                    put("kcal", r.kcal.toDouble()); put("active_ms", r.activeMs); put("received_ms", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                is BandSpo2Record -> insertWithOnConflict("spo2", null, ContentValues().apply {
                    put("t_ms", r.startMs); put("end_ms", r.endMs); put("spo2", r.spo2); put("hr", r.heartRate)
                    put("min", r.min); put("max", r.max); put("received_ms", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                is BandExerciseRecord -> {
                    insertWithOnConflict("exercises", null, ContentValues().apply {
                        put("start_ms", r.startMs); put("end_ms", r.endMs); put("type", r.exerciseType)
                        put("duration_ms", r.durationMs); put("distance_m", r.distanceM); put("kcal", r.kcal)
                        put("mean_hr", r.meanHr); put("max_hr", r.maxHr); put("min_hr", r.minHr)
                        put("mean_speed", r.meanSpeedMps); put("max_speed", r.maxSpeedMps); put("mean_cadence", r.meanCadence)
                        put("track_points", r.track.size); put("received_ms", now)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                    r.samples.forEach { s ->
                        insertWithOnConflict("exercise_samples", null, ContentValues().apply {
                            put("exercise_start", r.startMs); put("t_ms", s.timeMs); put("hr", s.heartRate)
                            put("cadence", s.cadence); put("speed", s.speedMps); put("distance_m", s.distanceM)
                        }, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                }
                is BandSleepRecord -> insertWithOnConflict("sleep", null, ContentValues().apply {
                    put("start_ms", r.startMs); put("combined", if (r.combined) 1 else 0); put("end_ms", r.endMs)
                    put("efficiency", r.efficiency); put("score", r.score); put("duration_min", r.durationMin)
                    put("cycles", r.cycles); put("rem_min", r.remMin); put("received_ms", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                is BandSleepStage -> insertWithOnConflict("sleep_stages", null, ContentValues().apply {
                    put("start_ms", r.startMs); put("end_ms", r.endMs); put("stage", r.stage)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    /** Per-minute (start ms, average bpm) in [fromMs, toMs). */
    fun hrBins(fromMs: Long, toMs: Long): List<Pair<Long, Int>> =
        readableDatabase.rawQuery(
            "SELECT t_ms, avg FROM hr_bins WHERE t_ms >= ? AND t_ms < ? ORDER BY t_ms", arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getInt(1)) } }

    fun day(startMs: Long, endMs: Long): BandDay {
        val db = readableDatabase
        val args = arrayOf(startMs.toString(), endMs.toString())
        val hr = mutableListOf<BandHrPoint>()
        db.rawQuery("SELECT t_ms, min, max FROM hr_bins WHERE t_ms >= ? AND t_ms < ?", args).use { c ->
            while (c.moveToNext()) hr += BandHrPoint(c.getLong(0), c.getInt(1), c.getInt(2), spot = false)
        }
        db.rawQuery("SELECT t_ms, bpm FROM hr_spots WHERE t_ms >= ? AND t_ms < ?", args).use { c ->
            while (c.moveToNext()) hr += BandHrPoint(c.getLong(0), c.getInt(1), c.getInt(1), spot = true)
        }
        hr.sortBy { it.tMs }
        val byHour = IntArray(24)
        var steps = 0
        var dist = 0.0
        var lastSteps: Long? = null
        db.rawQuery("SELECT start_ms, end_ms, steps, distance_m FROM steps WHERE start_ms >= ? AND start_ms < ? ORDER BY start_ms", args).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(2)
                steps += n
                dist += c.getDouble(3)
                byHour[((c.getLong(0) - startMs) / 3_600_000L).toInt().coerceIn(0, 23)] += n
                lastSteps = c.getLong(1)
            }
        }
        val spo2 = mutableListOf<BandSpo2Point>()
        db.rawQuery("SELECT t_ms, spo2, hr FROM spo2 WHERE t_ms >= ? AND t_ms < ? ORDER BY t_ms", args).use { c ->
            while (c.moveToNext()) spo2 += BandSpo2Point(c.getLong(0), c.getInt(1), if (c.isNull(2)) null else c.getInt(2))
        }
        val workouts = mutableListOf<BandWorkout>()
        db.rawQuery(
            "SELECT start_ms, end_ms, type, duration_ms, distance_m, kcal, mean_hr, max_hr FROM exercises " +
                "WHERE start_ms >= ? AND start_ms < ? ORDER BY start_ms", args,
        ).use { c ->
            fun f(i: Int) = if (c.isNull(i)) null else c.getFloat(i)
            while (c.moveToNext()) workouts += BandWorkout(c.getLong(0), c.getLong(1), c.getInt(2), c.getLong(3), f(4), f(5), f(6), f(7))
        }
        return BandDay(hr, steps, dist, byHour.toList(), hr.lastOrNull()?.tMs, lastSteps, spo2, night(db, args), workouts)
    }

    /** The night whose wake-up falls in [args]: the combined record if present, else the longest session. */
    private fun night(db: SQLiteDatabase, args: Array<String>): BandNight? {
        val night = db.rawQuery(
            "SELECT start_ms, end_ms, score, efficiency, duration_min FROM sleep WHERE end_ms >= ? AND end_ms < ? " +
                "ORDER BY combined DESC, (end_ms - start_ms) DESC LIMIT 1", args,
        ).use { c ->
            if (!c.moveToFirst()) return null
            BandNight(
                c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getInt(2),
                if (c.isNull(3)) null else c.getFloat(3), if (c.isNull(4)) null else c.getInt(4), emptyList(),
            )
        }
        val stages = mutableListOf<BandSleepStage>()
        db.rawQuery(
            "SELECT start_ms, end_ms, stage FROM sleep_stages WHERE start_ms >= ? AND start_ms < ? ORDER BY start_ms",
            arrayOf(night.startMs.toString(), night.endMs.toString()),
        ).use { c -> while (c.moveToNext()) stages += BandSleepStage(c.getLong(0), c.getLong(1), c.getInt(2)) }
        return night.copy(stages = stages)
    }
}
