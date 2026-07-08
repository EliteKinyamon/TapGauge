package com.tapgauge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.tapgauge.TapGaugeApplication
import com.tapgauge.ui.components.RequireMicPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val vm: DiagnosticsViewModel = viewModel(factory = viewModelFactory {
        initializer { DiagnosticsViewModel(this[APPLICATION_KEY] as TapGaugeApplication) }
    })
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = { vm.stop(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        RequireMicPermission {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Live spectrogram (0\u20133.5 kHz). Brighter = more energy.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Detected peak: " + (state.peakHz?.let { "${it.toInt()} Hz" } ?: "\u2014"),
                    style = MaterialTheme.typography.titleMedium)

                Canvas(Modifier.fillMaxWidth().height(280.dp)) {
                    val cols = state.columns
                    if (cols.isEmpty()) return@Canvas
                    val colW = size.width / cols.size
                    val bins = cols.first().size
                    val rowH = size.height / bins
                    cols.forEachIndexed { ci, col ->
                        for (b in col.indices) {
                            val v = col[b].coerceIn(0f, 1f)
                            // simple viridis-ish ramp: dark blue -> green -> yellow
                            val color = Color(
                                red = v,
                                green = (v * 0.9f + 0.1f).coerceIn(0f, 1f),
                                blue = (1f - v) * 0.6f,
                            )
                            // y inverted so low freq at bottom
                            val y = size.height - (b + 1) * rowH
                            drawRect(
                                color = color,
                                topLeft = Offset(ci * colW, y),
                                size = Size(colW + 1f, rowH + 1f),
                            )
                        }
                    }
                }

                Button(
                    onClick = { if (state.running) vm.stop() else vm.start() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.running) "Stop" else "Start listening") }
            }
        }
    }
}
