package com.rameez.fit3zone.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

data class DailyHealth(
    val date: LocalDate,
    val steps: Long?,
    val caloriesKcal: Int?,
    val sleep: Duration?,
    val sleepEnd: Instant?,
    val restingHr: Int?,
    val hrMin: Int?,
    val hrMax: Int?,
    val hrAvg: Int?,
    /** Today's heart-rate samples (time, bpm), downsampled for a chart. */
    val hrSeries: List<Pair<Instant, Int>>,
    /** Steps for the last 7 days, oldest first. */
    val weekSteps: List<Pair<LocalDate, Long>>,
    /** Newest data point found, to show how fresh Samsung Health's sync is. */
    val lastDataAt: Instant?,
)

/**
 * Read-only access to data Samsung Health writes into Health Connect (the band syncs to Samsung
 * Health; Samsung Health shares to Health Connect). Not real-time: it lags the band by minutes.
 */
class HealthData(private val context: Context) {

    enum class Availability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED }

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE_REQUIRED
        else -> Availability.NOT_INSTALLED
    }

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(PERMISSIONS)

    suspend fun readToday(zone: ZoneId = ZoneId.systemDefault()): DailyHealth {
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val granted = grantedPermissions()
        fun has(p: String) = p in granted

        val agg = client.aggregate(
            AggregateRequest(
                metrics = buildSet {
                    if (has(P_STEPS)) add(StepsRecord.COUNT_TOTAL)
                    if (has(P_CALORIES)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                    if (has(P_HR)) { add(HeartRateRecord.BPM_MIN); add(HeartRateRecord.BPM_MAX); add(HeartRateRecord.BPM_AVG) }
                },
                timeRangeFilter = TimeRangeFilter.between(dayStart, now),
            ),
        )

        // Last night's sleep: sessions ending between 18:00 yesterday and now.
        val sleeps = if (has(P_SLEEP)) client.readRecords(
            ReadRecordsRequest(
                SleepSessionRecord::class,
                TimeRangeFilter.between(today.minusDays(1).atTime(18, 0).atZone(zone).toInstant(), now),
            ),
        ).records else emptyList()
        val sleep = sleeps.takeIf { it.isNotEmpty() }
            ?.fold(Duration.ZERO) { acc, s -> acc + Duration.between(s.startTime, s.endTime) }

        val resting = if (has(P_RESTING)) client.readRecords(
            ReadRecordsRequest(RestingHeartRateRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(7)), now)),
        ).records.maxByOrNull { it.time } else null

        val hrRecords = if (has(P_HR)) client.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(dayStart, now)),
        ).records else emptyList()
        val series = hrRecords.flatMap { r -> r.samples.map { it.time to it.beatsPerMinute.toInt() } }
            .sortedBy { it.first }
            .let { all -> if (all.size <= 300) all else all.filterIndexed { i, _ -> i % (all.size / 300 + 1) == 0 } }

        val weekSteps = if (has(P_STEPS)) client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(today.minusDays(6).atStartOfDay(), LocalDateTime.now(zone)),
                timeRangeSlicer = Period.ofDays(1),
            ),
        ).map { it.startTime.toLocalDate() to (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) } else emptyList()

        // Steps arrive as many small records; the newest one shows how recent Samsung Health's last sync was.
        val lastSteps = if (has(P_STEPS)) client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(dayStart, now)),
        ).records.maxOfOrNull { it.endTime } else null

        val newest = listOfNotNull(
            series.lastOrNull()?.first, sleeps.maxOfOrNull { it.endTime }, resting?.time, lastSteps,
        ).maxOrNull()

        return DailyHealth(
            date = today,
            steps = agg[StepsRecord.COUNT_TOTAL],
            caloriesKcal = agg[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toInt(),
            sleep = sleep,
            sleepEnd = sleeps.maxOfOrNull { it.endTime },
            restingHr = resting?.beatsPerMinute?.toInt(),
            hrMin = agg[HeartRateRecord.BPM_MIN]?.toInt(),
            hrMax = agg[HeartRateRecord.BPM_MAX]?.toInt(),
            hrAvg = agg[HeartRateRecord.BPM_AVG]?.toInt(),
            hrSeries = series,
            weekSteps = weekSteps,
            lastDataAt = newest,
        )
    }

    companion object {
        val P_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
        val P_SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
        val P_HR = HealthPermission.getReadPermission(HeartRateRecord::class)
        val P_RESTING = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
        val P_CALORIES = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
        val PERMISSIONS = setOf(P_STEPS, P_SLEEP, P_HR, P_RESTING, P_CALORIES)
    }
}
