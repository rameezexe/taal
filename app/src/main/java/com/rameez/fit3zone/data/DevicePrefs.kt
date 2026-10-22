package com.rameez.fit3zone.data

import android.content.Context
import androidx.core.content.edit

/** What kind of heart-rate device the user paired; picks the words and the setup help, nothing else. */
enum class DeviceKind(val word: String, val title: String) {
    STRAP("Strap", "Chest strap or armband"),
    WATCH("Watch", "Watch broadcasting heart rate"),
    FIT3("Band", "Samsung Galaxy Fit3"),
    ;

    companion object {
        fun guess(name: String?): DeviceKind = if (name?.contains("Fit3", ignoreCase = true) == true) FIT3 else STRAP
    }
}

data class SavedDevice(val address: String, val name: String?, val kind: DeviceKind = DeviceKind.guess(name)) {
    /** Name without the per-unit suffix, e.g. "Galaxy Fit3 (0FA2)" → "Galaxy Fit3". */
    val displayName: String? get() = name?.substringBefore(" (")?.trim()?.takeIf { it.isNotEmpty() }
}

class DevicePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    var device: SavedDevice?
        get() = prefs.getString(KEY_ADDRESS, null)?.let { address ->
            val name = prefs.getString(KEY_NAME, null)
            val kind = prefs.getString(KEY_KIND, null)?.let { k -> DeviceKind.entries.firstOrNull { it.name == k } } ?: DeviceKind.guess(name)
            SavedDevice(address, name, kind)
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_ADDRESS); remove(KEY_NAME); remove(KEY_KIND)
            } else {
                putString(KEY_ADDRESS, value.address); putString(KEY_NAME, value.name); putString(KEY_KIND, value.kind.name)
            }
        }

    /** Word for the saved device in status texts ("Band connected", "Strap connected"); "Sensor" when none. */
    val word: String get() = device?.kind?.word ?: "Sensor"

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val KEY_KIND = "kind"
    }
}
