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
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.scenaristo.camera.domain.whitebalance.DEFAULT_KELVIN
import com.scenaristo.camera.capture.ManualSession
import com.scenaristo.camera.capture.PreviewJpegSource
import com.scenaristo.camera.capture.PreviewTapProcessor
import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.protocol.CaptureSettings
import com.scenaristo.camera.domain.protocol.DeviceStatus
import com.scenaristo.camera.domain.protocol.RecordingState
import com.scenaristo.camera.domain.protocol.Session
import com.scenaristo.camera.domain.protocol.State as ProtocolState
import com.scenaristo.camera.domain.protocol.ThermalState
import com.scenaristo.camera.server.ControlServer
import com.scenaristo.camera.server.LocalAddress
import com.scenaristo.camera.server.PreviewFrames
import com.scenaristo.camera.capture.SessionSupportProbe
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

/**
 * The shutters worth pointing at a lamp, and what each one proves.
 *
 * A mains-driven light ripples at twice the grid frequency, so on a 50 Hz grid
 * the light pulses every 10 ms. An exposure covering a whole number of pulses
 * integrates them away; a fractional one leaves the residue that becomes a
 * rolling band.
 *
 * That makes [ONE_SIXTIETH] the positive control the flicker test has been
 * missing (#20): if 1/50 and 1/100 are clean *and* 1/60 bands, the shutter is
 * doing the work. A lamp that simply does not ripple — many LED drivers are DC —
 * cannot produce that pattern, which is why one clean run at 1/50 proved
 * nothing on its own.
 */
private enum class Shutter(val label: String, val exposureNs: Long, val expectation: String) {
    ONE_FIFTIETH("1/50", 20_000_000L, "2.0 pulses at 50 Hz — expect clean"),
    ONE_SIXTIETH("1/60", 16_666_667L, "1.67 pulses at 50 Hz — expect BANDS"),
    ONE_HUNDREDTH("1/100", 10_000_000L, "1.0 pulse at 50 Hz — expect clean"),
}

private fun requestFor(shutter: Shutter) = ManualControls.Request(
    exposureTimeNs = shutter.exposureNs,
    sensitivity = 100,
    frameDurationNs = 33_333_333L,
    // PRD 6.4's default, as the locked preset nearest 5600 K.
    awbMode = ManualControls.awbModeFor(DEFAULT_KELVIN),
)

@Composable
fun InteropEchoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    // Changing the shutter re-creates the session: the keys are applied to the
    // use-case builder at bind time, so a new value means a new bind. `key`
    // disposes the old tap and its EGL thread with it.
    var shutter by remember { mutableStateOf(Shutter.ONE_FIFTIETH) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (granted) {
            key(shutter) { EchoRunner(shutter) { shutter = it } }
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
private fun EchoRunner(shutter: Shutter, onShutterChange: (Shutter) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Frames come from the GL tap rather than ImageAnalysis (ADR-0018). The
    // counter is the measurement that matters here: binding proved the session is
    // accepted, and only a frame rate proves a real shader keeps up with a 4K
    // encode running beside it.
    var tapFrames by remember { mutableIntStateOf(0) }
    var tapFps by remember { mutableStateOf("—") }
    // The tapped frames now go somewhere: JPEG for the browser preview (ADR-0008).
    val jpeg = remember { PreviewJpegSource() }
    val tap = remember {
        var count = 0
        // A window, not a cumulative average: #23 asks whether the rate *drops*
        // as the phone heats, and an average over ten minutes hides exactly that.
        var windowStart = 0L
        var windowCount = 0
        PreviewTapProcessor { image ->
            // accept() closes the image; the reader stalls if anything holds on.
            jpeg.accept(image)
            count++
            tapFrames = count
            val now = System.nanoTime()
            if (windowStart == 0L) windowStart = now
            windowCount++
            val seconds = (now - windowStart) / 1_000_000_000.0
            if (seconds >= 5.0) {
                tapFps = "%.1f".format(windowCount / seconds)
                windowStart = now
                windowCount = 0
            }
        }
    }

    // PRD 8-Q4 and #23: the transitions are the measurement, not the final value.
    val thermal = remember { mutableStateListOf<String>() }
    var thermalNow by remember { mutableStateOf("?") }

    // The phone records its own soak. A 10-minute run cannot be watched over USB
    // while the cable is also charging the battery -- charge heat is exactly the
    // confound #23 does not want -- so every 30 s the rate and thermal state are
    // appended here and the whole run is readable from one screenshot afterwards.
    val samples = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val started = System.currentTimeMillis()
        var last = -1
        var nextSample = 30_000L
        while (true) {
            val status = power.currentThermalStatus
            val elapsedMs = System.currentTimeMillis() - started
            val elapsed = elapsedMs / 1000
            if (status != last) {
                thermal += "%d:%02d %s".format(elapsed / 60, elapsed % 60, thermalName(status))
                last = status
            }
            thermalNow = thermalName(status)
            if (elapsedMs >= nextSample) {
                samples += "%d:%02d %s fps %s".format(elapsed / 60, elapsed % 60, tapFps, thermalName(status))
                nextSample += 30_000L
            }
            kotlinx.coroutines.delay(1_000)
        }
    }
    val session = remember { ManualSession(requestFor(shutter), tap = tap) }

    // Both previous soaks ended at the device's 120 s screen timeout, because the
    // camera is bound to this activity's lifecycle. Shipping capture runs in a
    // foreground service and does not have this problem (ADR-0003); until then a
    // wake lock on the window is what lets a timed run finish.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { tap.release(); jpeg.release() } }

    // The server is what makes this a remote at all (ADR-0006, ADR-0007). It is
    // started here rather than in a foreground service because the service is
    // ADR-0003's work and not yet written; a take still dies with the activity.
    var serverUrl by remember { mutableStateOf<String?>(null) }
    val server = remember {
        ControlServer(
            session = Session(startingState()),
            frames = PreviewFrames { jpeg.latest() },
        )
    }
    DisposableEffect(Unit) {
        server.start()
        serverUrl = LocalAddress.url()
        onDispose { server.stop() }
    }
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var status by remember { mutableStateOf("Binding…") }
    var cameraId by remember { mutableStateOf("?") }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var report by remember { mutableStateOf<LensEchoReport?>(null) }
    var recordingState by remember { mutableStateOf("idle") }
    var support by remember { mutableStateOf("") }

    val latest: State<List<KeyEcho>> = session.latestEchoes.collectAsState()

    LaunchedEffect(Unit) {
        session.preview.setSurfaceProvider { surfaceRequest = it }
        val provider = ProcessCameraProvider.awaitInstance(context)
        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        // Ask before binding. "Feature group is not supported" from bindToLifecycle
        // is true but unattributable; the probe says which variation the device
        // refuses, which is the answer #20 actually wants.
        val info = provider.getCameraInfo(selector)
        val caps = session.capabilities(info)
        cameraId = caps.cameraId
        val results = SessionSupportProbe.run(info)

        // isSessionConfigSupported is CameraX's own answer and may not model
        // effects at all, so the effect shapes are bound for real. A stub
        // processor renders nothing; what is being tested is whether the session
        // is accepted, not whether pixels arrive. Printed first because it is the
        // load-bearing result and the table below is long.
        // The plain question: three use cases, no feature group, no fps demand.
        // What does the device say is possible, and what does it hand back?
        val binds = StringBuilder("Plain SessionConfig(preview, video, analysis), no preference:\n")
        run {
            val plainUseCases = listOf(
                Preview.Builder().build(),
                VideoCapture.withOutput(Recorder.Builder().build()),
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build(),
            )
            val plain = SessionConfig.Builder(plainUseCases).build()
            // getResolution is @RestrictTo library-internal; Quality's own toString
            // already carries typicalSizes, which is the number wanted here.
            val qualities = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
            binds.append("- Recorder says qualities available on this lens: ")
                .append(qualities.joinToString())
                .append("\n")
            binds.append("- supported fps ranges for this session: ")
                .append(info.getSupportedFrameRateRanges(plain).joinToString())
                .append("\n")
            binds.append("- isSessionConfigSupported: ${info.isSessionConfigSupported(plain)}\n")
            val outcome = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, plain)
            }
            binds.append("- bound: ")
                .append(
                    if (outcome.isSuccess) {
                        SessionSupportProbe.resolutionsOf(plainUseCases)
                    } else {
                        "REFUSED: ${shortReason(outcome.exceptionOrNull())}"
                    },
                )
                .append("\n")
        }

        binds.append("\nPreferred feature group (CameraX drops what it cannot do):\n")
        for ((label, config, useCases) in SessionSupportProbe.preferredCandidates()) {
            var selected = "?"
            config.setFeatureSelectionListener(ContextCompat.getMainExecutor(context)) { features ->
                selected = if (features.isEmpty()) "none" else features.joinToString { "$it" }
            }
            val outcome = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, config)
            }
            binds.append("- $label: ")
                .append(
                    if (outcome.isSuccess) {
                        "BOUND ${SessionSupportProbe.resolutionsOf(useCases)} selected=[$selected]"
                    } else {
                        "REFUSED: ${shortReason(outcome.exceptionOrNull())}"
                    },
                )
                .append("\n")
        }

        binds.append("\nVararg binds (CameraX resolves the combination itself):\n")
        for ((label, useCases, varies) in SessionSupportProbe.varargCandidates()) {
            val outcome = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
            }
            binds.append("- $label ($varies): ")
                .append(
                    if (outcome.isSuccess) {
                        "BOUND ${SessionSupportProbe.resolutionsOf(useCases)}"
                    } else {
                        "REFUSED: ${shortReason(outcome.exceptionOrNull())}"
                    },
                )
                .append("\n")
        }
        binds.insert(0, "SessionConfig binds, with the resolutions CameraX assigned:\nPLACEHOLDER\n\n")
        val sessionConfigSection = StringBuilder()
        for (r in results) {
            val outcome = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, r.candidate.config)
            }
            sessionConfigSection.append("- ${r.candidate.label}: ")
                .append(
                    if (outcome.isSuccess) {
                        "BOUND ${SessionSupportProbe.resolutions(r.candidate.config)}"
                    } else {
                        "REFUSED: ${shortReason(outcome.exceptionOrNull())}"
                    },
                )
                .append("\n")
        }
        provider.unbindAll()
        val assembled = binds.toString().replace("PLACEHOLDER", sessionConfigSection.toString().trimEnd())
        support = assembled

        status = try {
            provider.bindToLifecycle(lifecycleOwner, selector, session.sessionConfig)
            "Bound with the preview tap. manualSensor=${caps.hasManualSensor} " +
                "manualPostProcessing=${caps.hasManualPostProcessing} uhd30=${caps.supportsUhd30}"
        } catch (e: IllegalArgumentException) {
            // Record verbatim: a refusal here is a Phase 0 result, not a bug.
            "BIND FAILED — ${e.message}"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Fixed height rather than a weight: the readout column below scrolls and
        // therefore claims every pixel it is offered, which left the viewfinder
        // with none. Without a visible viewfinder the flicker check of PRD 6.2
        // cannot be run at all -- you cannot aim at a light panel you cannot see.
        Box(modifier = Modifier.fillMaxWidth().height(260.dp)) {
            surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
        }

        Text(status, style = MaterialTheme.typography.bodySmall)
        Text(
            "tap: $tapFrames frames, $tapFps fps (5s window)",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "remote: ${serverUrl ?: "no network"}  ·  ${jpeg.encoded} jpeg",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "rec: $recordingState",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "thermal: $thermalNow — ${thermal.joinToString(" → ")}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        if (samples.isNotEmpty()) {
            Text(
                // Newest first: a soak that ends badly ends at the top.
                samples.takeLast(24).reversed().joinToString("   "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in Shutter.entries) {
                if (option == shutter) {
                    Button(onClick = {}) { Text(option.label) }
                } else {
                    OutlinedButton(onClick = { onShutterChange(option) }) { Text(option.label) }
                }
            }
        }
        Text(
            "${shutter.label}: ${shutter.expectation}",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (recording == null) {
                        report = null
                        recordingState = "starting…"
                        recording = startRecording(context, session) { event ->
                            when (event) {
                                is VideoRecordEvent.Status -> {
                                    val s = event.recordingStats.recordedDurationNanos / 1_000_000_000
                                    val mib = event.recordingStats.numBytesRecorded / (1024 * 1024)
                                    recordingState = "recording %d:%02d, %d MiB".format(s / 60, s % 60, mib)
                                }
                                is VideoRecordEvent.Finalize -> {
                                    // The recorder can end a take on its own. Reflect
                                    // that, rather than leaving a button claiming to
                                    // be recording something that stopped minutes ago.
                                    recordingState = "ENDED: ${finalizeReason(event)}"
                                    recording = null
                                    report = session.report(cameraId, "Rear main (wide)")
                                }
                                else -> Unit
                            }
                        }
                    } else {
                        recording?.stop()
                        recording = null
                    }
                },
            ) {
                Text(if (recording == null) "Record" else "Stop and report")
            }

            OutlinedButton(
                onClick = {
                    copyToClipboard(context, listOfNotNull(support, report?.markdown()).joinToString("\n"))
                },
            ) {
                Text("Copy paste for #20")
            }
        }

        // Live, so a key that flips mid-take is visible while it happens rather
        // than only in the summary.
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
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
            if (support.isNotEmpty()) {
                Text(
                    support,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
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

/**
 * The first sentence of a bind failure plus the surface configs it names. The
 * full message runs to a dozen lines of FeatureSettings and pushes the later
 * candidates off screen, and the configs are the part that says why.
 */
private fun shortReason(error: Throwable?): String {
    val message = error?.message ?: return "unknown"
    val configs = Regex("New configs: \\[(.*?)\\]").find(message)?.groupValues?.get(1)
        ?.split(",")
        ?.joinToString(" + ") { it.trim().substringAfterLast('.').substringBefore('@') }
    val head = message.substringAfter("IllegalArgumentException: ").substringBefore(".")
    return listOfNotNull(head, configs).joinToString(" — ")
}

/** PowerManager's THERMAL_STATUS_* constants, named for a report a human reads. */
private fun thermalName(status: Int): String = when (status) {
    android.os.PowerManager.THERMAL_STATUS_NONE -> "none"
    android.os.PowerManager.THERMAL_STATUS_LIGHT -> "light"
    android.os.PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
    android.os.PowerManager.THERMAL_STATUS_SEVERE -> "severe"
    android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
    android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
    android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
    else -> "unknown($status)"
}

/**
 * What the browser sees before anything has happened.
 *
 * The spike does not yet feed real battery, thermal or storage into the state
 * document -- those come from the phone's own sources when the foreground
 * service exists (ADR-0003). The values here are placeholders and are the reason
 * the browser's status line is not yet trustworthy.
 */
private fun startingState() = ProtocolState(
    settings = CaptureSettings(
        grid = GridFrequency.HZ_50,
        shutterHz = 50,
        iso = 100,
        whiteBalanceKelvin = 5600,
        lensId = "0",
    ),
    recording = RecordingState(recording = false),
    device = DeviceStatus(
        batteryPercent = 0,
        charging = false,
        thermal = ThermalState.NOMINAL,
        storageMinutesRemaining = 0,
    ),
    serverTimeMs = System.currentTimeMillis(),
)

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Writes to app-specific external storage, which needs no storage permission.
 * The file is evidence for the issue: its metadata is what PRD 6.1's "3840x2160
 * at 30.00 fps constant" is checked against.
 */
private fun startRecording(
    context: Context,
    session: ManualSession,
    onEvent: (VideoRecordEvent) -> Unit,
): Recording {
    val file = File(context.getExternalFilesDir(null), "interop-echo-${System.currentTimeMillis()}.mp4")
    return session.recorder
        .prepareRecording(context, FileOutputOptions.Builder(file).build())
        .start(ContextCompat.getMainExecutor(context), onEvent)
}

/**
 * Why a recording ended, in words.
 *
 * Two timed runs were mis-reported before this existed. One stopped at the
 * device's 120 s screen timeout and the UI claimed it was still recording for
 * another nine minutes; another ended 3.4 MB short of 2 GiB and left no evidence
 * of whether that was a file-size cap or a human pressing stop. A measurement
 * harness that cannot say why it stopped measuring is worse than none, because
 * its numbers look fine.
 */
private fun finalizeReason(event: VideoRecordEvent.Finalize): String {
    val error = when (event.error) {
        VideoRecordEvent.Finalize.ERROR_NONE -> "stopped normally"
        VideoRecordEvent.Finalize.ERROR_UNKNOWN -> "UNKNOWN"
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "FILE_SIZE_LIMIT_REACHED"
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "INSUFFICIENT_STORAGE"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "SOURCE_INACTIVE (camera unbound — screen off?)"
        VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "INVALID_OUTPUT_OPTIONS"
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "ENCODING_FAILED"
        VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "RECORDER_ERROR"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "NO_VALID_DATA"
        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "DURATION_LIMIT_REACHED"
        VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "RECORDING_GARBAGE_COLLECTED"
        else -> "error ${event.error}"
    }
    val stats = event.recordingStats
    val seconds = stats.recordedDurationNanos / 1_000_000_000.0
    val mib = stats.numBytesRecorded / (1024.0 * 1024.0)
    val cause = event.cause?.message?.let { " — $it" } ?: ""
    return "%s after %d:%02d, %.0f MiB%s".format(
        error,
        (seconds / 60).toInt(),
        (seconds % 60).toInt(),
        mib,
        cause,
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("interop echo report", text))
}
