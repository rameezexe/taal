package com.rameez.fit3zone.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.rameez.fit3zone.Fit3ZoneApp
import com.rameez.fit3zone.data.Strava
import kotlinx.coroutines.launch

/**
 * Receives taal://localhost/strava (debug: fit3zone://) after the Strava login, finishes the login in the background and
 * returns to the app (which shows the Strava screen the user started from). No UI of its own.
 */
class StravaCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { uri ->
            (application as Fit3ZoneApp).appScope.launch { Strava.get(this@StravaCallbackActivity).completeAuthorization(uri) }
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
