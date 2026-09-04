package com.scenaristo.camera.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.scenaristo.camera.service.CaptureService

/**
 * The phone screen, as a thin client of [CaptureService] (ADR-0003).
 *
 * It renders the viewfinder and shows the address to type into a laptop. It owns
 * no camera and no server, which is the point: destroying this activity — a
 * rotation, a call, the screen locking — must not touch a recording or a
 * connected browser.
 *
 * PRD 6.9's real phone UI is Phase 1. This is what the service needs to be
 * usable, and no more.
 */
@Composable
fun RemoteScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCamera(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    // Android 13+ will not show the foreground-service notification without this,
    // and that notification is the only recording indicator once the screen is off.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!granted) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Scenaristo Camera needs the camera.")
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow") }
            }
            return@Surface
        }

        val service = rememberCaptureService()
        DisposableEffect(Unit) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            onDispose {}
        }

        // The screen stays awake only while this activity is visible (ADR-0003).
        // Once a browser is connected the user may lock the phone and the take
        // continues, which is the whole reason capture moved into the service.
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }

        val surfaceRequest by (service?.surfaceRequest?.collectAsState() ?: return@Surface)
        val url by (service.url.collectAsState())
        val codecs by (service.codecs.collectAsState())

        Column(
            // safeDrawing, not just the status bar: in landscape this phone puts
            // its camera cutout down the left edge and its gesture bar down the
            // right, and `enableEdgeToEdge` means the window extends under both.
            // Without this the address is printed through the cutout and the
            // first character of every line is unreadable — which is the shape
            // of the problem UI-3 flags for the record button.
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                surfaceRequest?.let {
                    CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize())
                }
            }
            Text("Open on your laptop:", style = MaterialTheme.typography.bodyMedium)
            Text(
                url ?: "no network",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            // #21's readout. Temporary: it belongs in the browser's capability
            // report (PRD 6.10) once that exists, not on the phone screen.
            codecs?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * Binds to the service and starts it if it is not running.
 *
 * Started from the activity while it is visible, which Android 14 requires for a
 * `camera` foreground service — a service started from the background cannot use
 * the camera at all (ADR-0003).
 */
@Composable
private fun rememberCaptureService(): CaptureService? {
    val context = LocalContext.current
    var service by remember { mutableStateOf<CaptureService?>(null) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? CaptureService.LocalBinder)?.service
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        CaptureService.start(context)
        context.bindService(
            Intent(context, CaptureService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        onDispose {
            context.unbindService(connection)
            // Deliberately not stopped: unbinding is the activity going away, and
            // a recording must outlive it.
        }
    }
    return service
}

private fun hasCamera(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
