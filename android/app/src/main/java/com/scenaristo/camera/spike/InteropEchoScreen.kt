package com.scenaristo.camera.spike

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scenaristo.camera.capture.EchoVerdict
import com.scenaristo.camera.capture.KeyEcho
import com.scenaristo.camera.capture.LensEchoReport
import com.scenaristo.camera.capture.ManualControls
import com.scenaristo.camera.capture.ManualSession
import com.scenaristo.camera.capture.markdown
import java.io.File

/**
 * The Phase 0 measurement screen for #20: bind the real UHD30 session, record,
 * and read back what the sensor did with the manual keys.
 *
 * This is a spike instrument, not the phone UI. PRD 6.9's real screen lands in
 * Phase 1 bound to a foreground service (ADR-0003); this one is deliberately an
 * activity-scoped throwaway whose only job is to produce a number that can be
 * pasted into the issue and into ADR-0002.
 *
 * Recording is video-only. Audio is PRD 6.6 and needs its own permission; it
 * plays no part in whether the sensor honoured a shutter request, and leaving it
 * out keeps the thing being measured to one variable.
 */

/** 1/50 s at ISO 100, 30 fps: the 50 Hz default from PRD 6.2's ladder. */
private val DEFAULT_REQUEST = ManualControls.Request(
    exposureTimeNs = 20_000_000L,
    sensitivity = 100,
    frameDurationNs = 33_333_333L,
)

@Composable
fun InteropEchoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (granted) {
            EchoRunner()
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("This spike needs the camera.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { request.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera access")
                }
            }
        }
    }
}

@Composable
private fun EchoRunner() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val session = remember { ManualSession(DEFAULT_REQUEST) }
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var status by remember { mutableStateOf("Binding…") }
    var cameraId by remember { mutableStateOf("?") }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var report by remember { mutableStateOf<LensEchoReport?>(null) }

    val latest: State<List<KeyEcho>> = session.latestEchoes.collectAsState()

    LaunchedEffect(Unit) {
        session.preview.setSurfaceProvider { surfaceRequest = it }
        val provider = ProcessCameraProvider.awaitInstance(context)
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        status = try {
            // Required, not preferred: a device that cannot do UHD30 has to fail
            // here rather than quietly measure a 1080p session instead.
            val camera = provider.bindToLifecycle(lifecycleOwner, selector, session.sessionConfig)
            val caps = session.capabilities(camera.cameraInfo)
            cameraId = caps.cameraId
            "Bound. manualSensor=${caps.hasManualSensor} " +
                "manualPostProcessing=${caps.hasManualPostProcessing} uhd30=${caps.supportsUhd30}"
        } catch (e: IllegalArgumentException) {
            // The informative failure: this device cannot bind the session the
            // product needs. Record it in the issue exactly as it reads here.
            "BIND FAILED — ${e.message}"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
        }

        Text(status, style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (recording == null) {
                        recording = startRecording(context, session)
                        report = null
                    } else {
                        recording?.stop()
                        recording = null
                        report = session.report(cameraId, "Rear main (wide)")
                    }
                },
            ) {
                Text(if (recording == null) "Record" else "Stop and report")
            }

            report?.let { finished ->
                OutlinedButton(onClick = { copyToClipboard(context, finished.markdown()) }) {
                    Text("Copy paste for #20")
                }
            }
        }

        // Live, so a key that flips mid-take is visible while it happens rather
        // than only in the summary.
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (echo in latest.value) {
                Text(
                    "${echo.key.name}: ${echo.observed ?: "absent"} (${echo.verdict})",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (echo.verdict == EchoVerdict.EXACT || echo.verdict == EchoVerdict.QUANTISED) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            report?.let {
                Text(
                    it.markdown(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Writes to app-specific external storage, which needs no storage permission.
 * The file is evidence for the issue: its metadata is what PRD 6.1's "3840x2160
 * at 30.00 fps constant" is checked against.
 */
private fun startRecording(context: Context, session: ManualSession): Recording {
    val file = File(context.getExternalFilesDir(null), "interop-echo-${System.currentTimeMillis()}.mp4")
    return session.recorder
        .prepareRecording(context, FileOutputOptions.Builder(file).build())
        .start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize && event.hasError()) {
                android.util.Log.e("InteropEcho", "recording failed: ${event.error}", event.cause)
            }
        }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("interop echo report", text))
}
