package com.scenaristo.camera.ui

import androidx.camera.core.SurfaceRequest
import androidx.camera.compose.CameraXViewfinder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scenaristo.camera.domain.exposure.shutterLadder
import com.scenaristo.camera.domain.protocol.State as ProtocolState
import com.scenaristo.camera.domain.protocol.Warning
import com.scenaristo.camera.theme.Tokens

/**
 * The phone's camera screen (PRD 6.9, spec UI-1 to UI-6).
 *
 * The rule the layout follows is UI-5's two grammars: what the camera *settled
 * on* is reported, dimmed and untouchable along the top; what the user *decides*
 * is framed, amber and touchable along the bottom. A pro camera app draws every
 * number as a button because in those apps every number is settable. Here
 * shutter, ISO and white balance are outputs of the exposure loop (ADR-0005),
 * so drawing them as buttons would be a lie the user finds by pressing one.
 *
 * What is deliberately absent: battery, charging and thermal state. Those are
 * Android status-bar items one swipe away (UI-2, UI-3), so they belong to the
 * remote control, where the phone's own screen is out of sight.
 */
@Composable
fun CameraScreen(
    surfaceRequest: SurfaceRequest?,
    state: ProtocolState,
    codec: String,
    onToggleRecording: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = state.recording.recording

    Box(modifier = modifier.fillMaxSize().background(Tokens.Ground)) {
        surfaceRequest?.let {
            CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize())
        }

        // UI-6: a red inset border frames the whole preview while recording, and
        // red appears nowhere else in the interface.
        if (recording) {
            Box(Modifier.fillMaxSize().padding(6.dp).border(3.dp, Tokens.Red, RoundedCornerShape(4.dp)))
        }

        // UI-1 asks the two chrome classes to be distinguishable at 2 m, and a
        // 58 %-opacity readout over a sunlit window is not readable at any
        // distance -- measured on the reference device, where the top-right
        // values disappeared against a bright window behind the subject. These
        // scrims are the least chrome that fixes it: they darken only the strips,
        // leave the centre of the frame untouched (UI-3), and are not a panel, so
        // nothing reads as a frame that is not a control.
        Box(
            Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter).background(
                Brush.verticalGradient(listOf(Tokens.Ground.copy(alpha = 0.55f), Color.Transparent)),
            ),
        )
        Box(
            Modifier.fillMaxWidth().height(160.dp).align(Alignment.BottomCenter).background(
                Brush.verticalGradient(listOf(Color.Transparent, Tokens.Ground.copy(alpha = 0.55f))),
            ),
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            TopStrip(state = state, codec = codec)
            // UI-5: warnings sit below the top strip and never over the subject.
            Warnings(state.warnings)
            Spacer(Modifier.weight(1f))
            BottomStrip(
                recording = recording,
                onToggleRecording = onToggleRecording,
                onConnect = onConnect,
            )
        }
    }
}

@Composable
private fun TopStrip(state: ProtocolState, codec: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Reported(label = "Format", value = "4K · 30")
            Shutter(state)
            Reported(label = "ISO", value = state.settings.iso.toString())
            Reported(label = "Codec", value = codec)
        }
        Spacer(Modifier.weight(1f))
        Timecode(state)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Reported(label = "Space left", value = "${state.device.storageMinutesRemaining} min")
            // UI-12 fixes the word: a connected browser is a remote, never a
            // viewer and never a client -- a viewer cannot start a recording.
            Reported(label = "Remotes", value = state.clients.toString())
        }
    }
}

/**
 * UI-4: the shutter carries a padlock and a dimmed mains-frequency caption, and
 * is marked `Stepped` when the loop is on ADR-0005's flicker-safe rung.
 *
 * The pair is what makes the step legible rather than alarming: 1/100 on its own
 * looks like a setting drifted, and "1/100, 50 Hz, stepped" reads as the app
 * doing its job. PRD 6.3 is explicit that a successful step raises no warning.
 */
@Composable
private fun Shutter(state: ProtocolState) {
    val stepped = state.settings.shutterHz != shutterLadder(state.settings.grid).first()
    Column {
        Label("Shutter")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Value("1/${state.settings.shutterHz}")
            // The padlock means "held still by the app" and nothing else (UI-6
            // reserves dimming plus a caption for "locked while recording").
            Text(" 🔒", color = Tokens.Dimmer, fontSize = 10.sp)
        }
        Text(
            text = if (stepped) "${state.settings.grid.hz} Hz · stepped" else "${state.settings.grid.hz} Hz",
            color = Tokens.Dimmer,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun Timecode(state: ProtocolState) {
    val elapsedMs = state.recording.startedAtMs?.let { (state.serverTimeMs - it).coerceAtLeast(0L) } ?: 0L
    val recording = state.recording.recording
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatTimecode(elapsedMs),
            color = if (recording) Tokens.Text else Tokens.Dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recording) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Tokens.Red))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = if (recording) "Recording" else "Ready",
                color = if (recording) Tokens.Text else Tokens.Dim,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * UI-5: one line per warning, beginning with the action rather than the fault,
 * always with an icon, and never restating a value the strip above already shows.
 */
@Composable
private fun Warnings(warnings: List<Warning>) {
    if (warnings.isEmpty()) return
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        warnings.forEach { warning ->
            Row(
                modifier = Modifier
                    .background(Tokens.Panel, RoundedCornerShape(6.dp))
                    .border(1.dp, Tokens.Orange, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠", color = Tokens.Orange, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(copyFor(warning), color = Tokens.Text, fontSize = 12.sp)
            }
        }
    }
}

/** UI-5 and UI-12: name the fix, in sentence case, without repeating the readout. */
private fun copyFor(warning: Warning): String = when (warning) {
    Warning.TOO_DARK -> "Add light — this ISO will look noisy"
    Warning.OVEREXPOSED_AT_BASE_ISO -> "Too much light — close the blinds or move the key light back"
    Warning.TOO_CLOSE_TO_LENS -> "Sit further back — 1.5–2 m for the wide lens"
    Warning.TOO_BRIGHT -> "Too much light — close the blinds or move the key light back"
}

@Composable
private fun BottomStrip(recording: Boolean, onToggleRecording: () -> Unit, onConnect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        // UI-6: every setting is locked for the take, and a locked control says
        // so rather than being discovered by refusal.
        Control(
            label = if (recording) "Locked while recording" else "Connect",
            enabled = !recording,
            onClick = onConnect,
        )
        Spacer(Modifier.width(20.dp))
        RecordButton(recording = recording, onClick = onToggleRecording)
    }
}

/**
 * UI-4: at least 64 dp, and no closer than 28 dp to the bottom edge — which
 * UI-3 makes load-bearing rather than cosmetic. With the navigation bar hidden
 * the bottom edge is the home gesture zone, and record is the one control the
 * system must never intercept.
 *
 * UI-6: it becomes a stop control in the same position at the same size, a
 * rounded square rather than a circle.
 */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(50))
            .border(3.dp, Tokens.Text.copy(alpha = 0.85f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (recording) "Stop recording" else "Start recording" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (recording) 30.dp else 56.dp)
                .clip(if (recording) RoundedCornerShape(6.dp) else RoundedCornerShape(50))
                .background(Tokens.Red),
        )
    }
}

/** UI-1: framed, amber label, full contrast, always touchable. */
@Composable
private fun Control(label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .background(Tokens.Panel.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .border(1.dp, if (enabled) Tokens.Amber else Tokens.Dimmer, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = if (enabled) Tokens.Amber else Tokens.Dimmer,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** UI-1: reported values are dimmed, unframed, tabular, and never a touch target. */
@Composable
private fun Reported(label: String, value: String) {
    Column {
        Label(label)
        Value(value)
    }
}

@Composable
private fun Label(text: String) = Text(
    text = text.uppercase(),
    color = Tokens.Dimmer,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Start,
)

@Composable
private fun Value(text: String) = Text(
    text = text,
    color = Tokens.Dim,
    fontFamily = FontFamily.Monospace,
    fontSize = 15.sp,
    fontWeight = FontWeight.Medium,
)

internal fun formatTimecode(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return buildString {
        append(hours.toString().padStart(2, '0'))
        append(':')
        append(minutes.toString().padStart(2, '0'))
        append(':')
        append(seconds.toString().padStart(2, '0'))
    }
}
