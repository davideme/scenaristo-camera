package com.scenaristo.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.scenaristo.camera.theme.ScenaristoCameraTheme
import com.scenaristo.camera.ui.MainScreen

/**
 * Phase 0 scaffold. The real phone UI (preview, record button, QR panel,
 * settings sheet) is PRD 6.9 and lands in Phase 1; capture is bound to a
 * foreground service, not to this activity (ADR-0003).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScenaristoCameraTheme { MainScreen() }
        }
    }
}
