package com.rameez.fit3zone.data

import android.content.Context
import androidx.core.content.edit
import com.rameez.fit3zone.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Developer tools (simulated sessions, Diagnostics) are hidden from testers. On by default in debug builds;
 * toggled by tapping the version line in the Device tab 7 times. Prefs "dev", key "on".
 */
object DevMode {
    private val _on = MutableStateFlow(BuildConfig.DEBUG)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    fun load(context: Context) {
        _on.value = prefs(context).getBoolean(KEY, BuildConfig.DEBUG)
    }

    fun set(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY, on) }
        _on.value = on
    }

    private fun prefs(context: Context) = context.getSharedPreferences("dev", Context.MODE_PRIVATE)
    private const val KEY = "on"
}
