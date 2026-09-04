package com.scenaristo.camera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scenaristo.camera.theme.Tokens

/**
 * How a laptop finds the phone, and what that costs (spec UI-7, PRD 6.8).
 *
 * The orange box is deliberate and is the one sanctioned use of orange outside
 * `State.warnings` (UI-7, decided 2026-09-04): v1 ships open LAN access, and
 * anyone on the network can start a recording. That is a real consequence, so it
 * is stated where the user opts into it rather than in documentation they will
 * not read. When PRD 6.11's pairing check lands, this box is what it replaces.
 *
 * The QR code PRD 6.8 also asks for is **not here yet** — it needs an encoder,
 * and the address is typable in the meantime.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectSheet(url: String?, remotes: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.Panel) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Open this on your laptop",
                color = Tokens.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )

            // UI-7: monospace, at least 17 px, and copyable.
            Text(
                text = url ?: "No network",
                color = Tokens.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                modifier = Modifier
                    .background(Tokens.Ground, RoundedCornerShape(8.dp))
                    .clickable { url?.let { copy(context, it) } }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Text("Tap the address to copy it.", color = Tokens.Dimmer, fontSize = 11.sp)

            // UI-12 fixes this wording: remotes, never viewers and never clients.
            Text(
                text = when (remotes) {
                    0 -> "No remotes connected"
                    1 -> "1 remote connected"
                    else -> "$remotes remotes connected"
                },
                color = Tokens.Dim,
                fontSize = 13.sp,
            )

            Row(
                modifier = Modifier
                    .background(Tokens.Ground, RoundedCornerShape(8.dp))
                    .border(1.dp, Tokens.Orange, RoundedCornerShape(8.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("⚠", color = Tokens.Orange, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Anyone on this network can monitor and control the camera. " +
                        "Turn the server off when you are done.",
                    color = Tokens.Text,
                    fontSize = 12.sp,
                )
            }

            Text(
                "Recording keeps running if the laptop drops off Wi-Fi.",
                color = Tokens.Dim,
                fontSize = 12.sp,
            )

            // ADR-0019: the sentence above promises an off switch, so it says
            // where it is. The switch itself is in the notification, because
            // that is the one place reachable once the user has left the app --
            // which is also when they are most likely to want it.
            Text(
                "The server stops on its own when you leave the app, unless a " +
                    "recording or a remote is still using it. Stop it now from " +
                    "the Scenaristo Camera notification.",
                color = Tokens.Dim,
                fontSize = 12.sp,
            )
        }
    }
}

private fun copy(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Scenaristo Camera", url))
}
