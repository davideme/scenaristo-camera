package com.scenaristo.camera

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.scenaristo.camera.service.CaptureService
import com.scenaristo.camera.theme.ScenaristoCameraTheme
import com.scenaristo.camera.ui.RemoteScreen

/**
 * A thin client of [com.scenaristo.camera.service.CaptureService].
 *
 * Capture and the server live in the service, not here (ADR-0003), so this
 * activity can be destroyed and recreated -- a rotation, a call, the screen
 * locking -- without touching a recording or a connected browser. The real
 * phone UI (record button, QR panel, settings sheet) is PRD 6.9, Phase 1.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardSweepRequest(intent)
        enableEdgeToEdge()
        setContent {
            // A thin client of the foreground service (ADR-0003): it draws the
            // viewfinder and shows the address, and owns neither the camera nor
            // the server. PRD 6.9's real phone UI is Phase 1.
            ScenaristoCameraTheme { RemoteScreen() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardSweepRequest(intent)
    }

    /**
     * Relays #20's lens sweep to the service.
     *
     * The service is not exported, so `adb` cannot start it directly, and the
     * measurement has to be startable without unlocking the phone -- the result
     * is a table for an ADR, and reading it off the screen means standing in
     * front of the camera being measured. The launcher activity is the only
     * exported entry point, so the trigger comes through here.
     */
    private fun forwardSweepRequest(intent: Intent?) {
        if (intent?.action != CaptureService.ACTION_LENS_SWEEP) return
        startForegroundService(
            Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_LENS_SWEEP),
        )
    }
}
