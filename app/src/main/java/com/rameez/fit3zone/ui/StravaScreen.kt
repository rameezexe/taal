package com.rameez.fit3zone.ui

import com.rameez.fit3zone.APP_NAME
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.painterResource
import com.rameez.fit3zone.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.Fit3ZoneApp
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.SessionSummary
import com.rameez.fit3zone.data.Strava
import com.rameez.fit3zone.data.UploadStatus
import kotlinx.coroutines.launch

/** Strava's brand orange (their API brand guidelines: links in #FC5200), for the Strava parts of the UI only. */
val StravaOrange = Color(0xFFFC5200)

/** Device → Strava: connect, auto-upload, upload past activities. */
@Composable
fun StravaScreen(modifier: Modifier) {
    val context = LocalContext.current
    val strava = remember { Strava.get(context) }
    val state by strava.state.collectAsStateWithLifecycle()
    val sessions by SessionHistory.get(context).sessions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDisconnect by remember { mutableStateOf(false) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Strava", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (!state.configured) {
            Text("Add STRAVA_CLIENT_ID and STRAVA_CLIENT_SECRET to local.properties and rebuild.", color = ZoneColors.above)
            return@Column
        }

        GlowCard(accent = StravaOrange) {
            CardLabel(if (state.connected) "Connected" else "Not connected", StravaOrange)
            Text(
                if (state.connected) state.athleteName ?: "Strava account" else "Upload your activities to Strava",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (state.connected) "Activities you upload use your Strava privacy defaults."
                else "Log in through the Strava app or browser. $APP_NAME uploads your activities and can rename them there; it doesn't download anything from Strava.",
                color = ZoneColors.muted, fontSize = 13.sp,
            )
        }

        if (!state.connected) {
            // Strava's official button artwork, unmodified, 48 dp high (brand guidelines 1.1).
            Image(
                painterResource(R.drawable.btn_strava_connect_with_orange), contentDescription = "Connect with Strava",
                modifier = Modifier.align(Alignment.CenterHorizontally).height(48.dp).clickable { openUrl(context, strava.authorizeUri()) },
            )
        } else {
            GlowCard(accent = StravaOrange) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-upload")
                        Text("Each activity of 5 min or more you finish from now on goes to Strava (tried again later when offline). Shorter ones: upload them from the activity.", color = ZoneColors.muted, fontSize = 13.sp)
                    }
                    Switch(checked = state.autoUpload, onCheckedChange = strava::setAutoUpload)
                }
            }

            if (!state.canEdit) {
                GlowCard(accent = StravaOrange) {
                    CardLabel("Renaming", StravaOrange)
                    Text("To rename activities on Strava when you rename them here, Strava needs one more permission (read access to your activities). Log in once more to allow it.", color = ZoneColors.muted, fontSize = 13.sp)
                    GhostButton("Allow renaming", { openUrl(context, strava.authorizeUri()) }, Modifier.fillMaxWidth(), height = 52.dp)
                }
            }

            val pending = strava.notUploaded(sessions)
            GlowCard(accent = Palette.accent) {
                CardLabel("History", Palette.accent)
                val done = sessions.count { state.uploads[it.id]?.status == UploadStatus.DONE }
                Text("$done on Strava · ${pending.size} not uploaded", style = MaterialTheme.typography.titleMedium)
                Text("Real activities over a minute. Simulated sessions are never uploaded.", color = ZoneColors.muted, fontSize = 13.sp)
                state.progress?.let { Text(it, color = StravaOrange, fontWeight = FontWeight.SemiBold) }
            }
            GhostButton(
                if (pending.isEmpty()) "Everything is uploaded" else "Upload ${pending.size} past ${if (pending.size == 1) "activity" else "activities"}",
                { (context.applicationContext as Fit3ZoneApp).appScope.launch { strava.uploadHistory(sessions) } },
                Modifier.fillMaxWidth(), enabled = pending.isNotEmpty() && state.progress == null, height = 56.dp,
            )
            GhostButton(
                if (confirmDisconnect) "Tap again to disconnect" else "Disconnect",
                { if (confirmDisconnect) scope.launch { strava.disconnect(); confirmDisconnect = false } else confirmDisconnect = true },
                Modifier.fillMaxWidth(), height = 56.dp,
            )
        }
        state.message?.let { Text(it, color = if (it.startsWith("Uploaded")) ZoneColors.inZone else ZoneColors.pending) }
        Image(
            painterResource(R.drawable.api_logo_pwrdby_strava_horiz_white), contentDescription = "Powered by Strava",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).height(16.dp),
        )
    }
}

/** Upload state of one activity, for the activity detail screen. Nothing when Strava isn't connected. */
@Composable
fun StravaUploadRow(s: SessionSummary, deleting: Boolean = false) {
    val context = LocalContext.current
    val strava = remember { Strava.get(context) }
    val state by strava.state.collectAsStateWithLifecycle()
    val upload = state.uploads[s.id]
    var reupload by remember { mutableStateOf(false) }
    if (!state.connected && upload?.status != UploadStatus.DONE) return
    if (!strava.uploadable(s)) return

    GlowCard(accent = StravaOrange) {
        CardLabel("Strava", StravaOrange)
        when (upload?.status) {
            UploadStatus.DONE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("On Strava", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextAction("View on Strava ›", { upload.url?.let { openUrl(context, it.toUri()) } }, color = StravaOrange)
                }
                // Strava's API can't delete activities, so the copy there has to go by hand.
                if (deleting) Text("Deleting here doesn't remove it from Strava. Open it there and use ⋯ → Delete.", color = ZoneColors.pending, fontSize = 13.sp)
                else if (reupload) {
                    Text(
                        "Strava can't change an uploaded activity's distance. Delete it on Strava first (⋯ → Delete), then upload it again.",
                        color = ZoneColors.muted, fontSize = 13.sp,
                    )
                    TextAction("Upload again", {
                        reupload = false
                        (context.applicationContext as Fit3ZoneApp).appScope.launch { strava.uploadAgain(s) }
                    }, color = StravaOrange)
                } else TextAction("Distance changed? Upload again…", { reupload = true }, color = ZoneColors.muted)
            }
            UploadStatus.UPLOADING -> Text("Uploading…", style = MaterialTheme.typography.titleMedium)
            else -> {
                upload?.error?.let { Text(it, color = ZoneColors.pending, fontSize = 13.sp) }
                GhostButton(
                    if (upload == null) "Upload to Strava" else "Try again",
                    { (context.applicationContext as Fit3ZoneApp).appScope.launch { strava.upload(s) } },
                    Modifier.fillMaxWidth(), height = 52.dp,
                )
            }
        }
    }
}

private fun openUrl(context: Context, uri: Uri) {
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
