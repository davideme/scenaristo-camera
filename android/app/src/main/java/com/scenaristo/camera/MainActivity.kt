package com.scenaristo.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        enableEdgeToEdge()
        setContent {
            // A thin client of the foreground service (ADR-0003): it draws the
            // viewfinder and shows the address, and owns neither the camera nor
            // the server. PRD 6.9's real phone UI is Phase 1.
            ScenaristoCameraTheme { RemoteScreen() }
        }
    }
}
