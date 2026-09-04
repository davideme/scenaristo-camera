package com.scenaristo.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.scenaristo.camera.spike.InteropEchoScreen
import com.scenaristo.camera.theme.ScenaristoCameraTheme

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
            // Phase 0 builds launch straight into the measurement screen: it is
            // the only thing this app does yet, and the spike is driven from a
            // laptop with no way to tap a menu. MainScreen and this line both go
            // when the real phone UI lands (PRD 6.9, Phase 1).
            ScenaristoCameraTheme { InteropEchoScreen() }
        }
    }
}
