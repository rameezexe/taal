package com.rameez.fit3zone.ui

import com.rameez.fit3zone.APP_NAME
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shown by Health Connect when the user asks how this app uses their health data. */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("How $APP_NAME uses health data", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "$APP_NAME reads steps, sleep, heart rate, resting heart rate and calories from " +
                                "Health Connect to show them on the My Day screen. If you turn on \"Send to Health Connect\", it also " +
                                "writes your finished activities (workout, route, heart rate, distance, elevation gain) so Samsung Health " +
                                "and other apps can show them. It never writes steps.",
                        )
                        Text(
                            "This data is processed on your phone only. It is not uploaded, sold or shared. " +
                                "Activities recorded by $APP_NAME are stored locally and leave the phone only when you export them " +
                                "or upload them to Strava.",
                        )
                        Text("You can revoke access at any time in Health Connect settings.")
                    }
                }
            }
        }
    }
}
