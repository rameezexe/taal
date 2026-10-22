package com.rameez.fit3zone

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import com.rameez.fit3zone.data.SettingsStore
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.BandData
import com.rameez.fit3zone.core.RestingHrSource
import android.app.Application
import com.rameez.fit3zone.BuildConfig
import com.rameez.fit3zone.data.HeartRateSimulator
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.Strava
import com.rameez.fit3zone.data.HealthExport
import com.rameez.fit3zone.data.AchievementStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Shown in texts, notifications and exports. Keep in sync with app_name in app/build.gradle.kts. Internal ids still
 * say "fit3zone" (code package, log file, Strava external_id, Health Connect client ids) so existing exports match.
 */
const val APP_NAME = "Taal"

class Fit3ZoneApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val simulator by lazy { HeartRateSimulator(appScope) }

    override fun onCreate() {
        super.onCreate()
        HeartRateRepository.log.attachFile(java.io.File(filesDir, "logs"))
        // No crash reporting service (sideloaded, no analytics): crashes go to the local log for Feedback.
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { HeartRateRepository.log.crash(thread.name, error) }
            systemHandler?.uncaughtException(thread, error)
        }
        HeartRateRepository.log.add("app", "$APP_NAME ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        // Sessions are never resumed after the process dies; close out any left IN_PROGRESS.
        // Queued on the history dispatcher, so it runs before any new session is inserted.
        appScope.launch(SessionHistory.dispatcher) {
            val history = SessionHistory.get(this@Fit3ZoneApp)
            history.markAbandonedAsInterrupted()
            com.rameez.fit3zone.data.RouteStore.get(this@Fit3ZoneApp).refresh()
        }
        com.rameez.fit3zone.data.DevMode.load(this)
        com.rameez.fit3zone.data.Updates.load(this)
        appScope.launch { com.rameez.fit3zone.data.Updates.check(this@Fit3ZoneApp) }
        keepRestingHrFromBand()
        Strava.get(this).watch(appScope, SessionHistory.get(this))
        HealthExport.get(this).watch(appScope)
        AchievementStore.get(this).watch(appScope)
    }

    /**
     * When resting HR comes from the band, re-estimate it whenever new band data is stored and update the
     * profile (and so the zones) if it changed. A running session keeps the zones it started with.
     */
    private fun keepRestingHrFromBand() {
        val settings = SettingsStore.get(this)
        BandData.load(this)
        appScope.launch(Dispatchers.IO) {
            combine(BandData.state.map { it.revision }.distinctUntilChanged(), settings.profile, settings.bandRestingRange) { _, p, range -> p to range }
                .collect { (p, range) ->
                    if (p == null || p.restingSource != RestingHrSource.BAND) return@collect
                    val e = runCatching { BandData.restingEstimate(this@Fit3ZoneApp) }.getOrNull() ?: return@collect
                    val bpm = e.within(range)
                    if (bpm == p.restingHr) return@collect
                    runCatching { p.copy(restingHr = bpm) }.onSuccess { updated ->
                        settings.saveProfile(updated)
                        HeartRateRepository.log.add(
                            "zones", "resting HR from band ${p.restingHr} -> $bpm (measured ${e.bpm}, limits $range, ${e.daysUsed} days, latest ${e.latestDay})",
                        )
                    }
                }
        }
    }
}
