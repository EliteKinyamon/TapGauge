package com.tapgauge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tapgauge.BuildConfig
import com.tapgauge.SettingsStore
import com.tapgauge.TapGaugeApplication

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onDiagnostics: () -> Unit) {
    val app = LocalContext.current.applicationContext as TapGaugeApplication
    val settings = app.settings

    var units by remember { mutableStateOf(settings.units) }
    var sampleRate by remember { mutableStateOf(settings.sampleRate) }
    var threshold by remember { mutableFloatStateOf(settings.tapThresholdRatio) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Units", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsStore.Units.entries.forEach { u ->
                    FilterChip(selected = units == u, onClick = {
                        units = u; settings.units = u
                    }, label = { Text(u.name.lowercase()) })
                }
            }

            Divider()
            Text("Microphone sample rate", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(44100, 48000).forEach { sr ->
                    FilterChip(selected = sampleRate == sr, onClick = {
                        sampleRate = sr; settings.sampleRate = sr
                    }, label = { Text("$sr Hz") })
                }
            }

            Divider()
            Text("Tap sensitivity: harder knock required \u2192",
                style = MaterialTheme.typography.titleSmall)
            Slider(
                value = threshold, onValueChange = { threshold = it },
                onValueChangeFinished = { settings.tapThresholdRatio = threshold },
                valueRange = 3f..12f,
            )
            Text("Threshold ratio: ${"%.1f".format(threshold)}\u00d7 above noise floor",
                style = MaterialTheme.typography.bodySmall)

            Divider()
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                TextButton(onClick = onDiagnostics) { Text("Open diagnostics (spectrogram)") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "All data stays on this device. TapGauge has no internet permission, " +
                    "no account, and never records raw audio to a file.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Estimate for convenience, not a certified safety gauge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
