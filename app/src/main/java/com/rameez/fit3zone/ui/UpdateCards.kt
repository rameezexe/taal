package com.rameez.fit3zone.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.APP_NAME
import com.rameez.fit3zone.data.Updates

/** My Day: "Update available" (download link from version.json) and, after an update, "What's new". */
@Composable
fun UpdateCards() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Updates.check(context) } // at most every 12 h
    val update by Updates.available.collectAsStateWithLifecycle()
    val whatsNew by Updates.whatsNew.collectAsStateWithLifecycle()

    update?.let { u ->
        GlowCard(accent = Palette.accent) {
            CardLabel("Update available · ${u.versionName}", Palette.accent)
            if (u.notes.isNotBlank()) Text(u.notes, fontSize = 14.sp)
            Text("Download it, open the file and tap Update. Your data stays.", color = ZoneColors.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction("Download", {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
                TextAction("Later", { Updates.dismissUpdate(context) }, color = ZoneColors.muted)
            }
        }
    }
    whatsNew?.let { notes ->
        GlowCard(accent = Palette.accent2) {
            CardLabel("What's new in $APP_NAME", Palette.accent2)
            Text(notes, fontSize = 14.sp)
            TextAction("Got it", { Updates.dismissWhatsNew(context) }, color = Palette.accent2)
        }
    }
}
