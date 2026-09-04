package com.scenaristo.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.scenaristo.camera.theme.ScenaristoCameraTheme

@Composable
fun MainScreen(onOpenInteropEchoSpike: () -> Unit = {}) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Scenaristo Camera", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Capture engine lands in Phase 1 (PRD 6.1-6.7, 6.9).",
                style = MaterialTheme.typography.bodyMedium,
            )
            // Phase 0 only. Removed when the real phone UI lands, along with the
            // screen it opens.
            Button(onClick = onOpenInteropEchoSpike) { Text("Phase 0 spike: interop key echo (#20)") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    ScenaristoCameraTheme { MainScreen() }
}
