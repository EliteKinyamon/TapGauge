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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.tapgauge.TapGaugeApplication

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(tankId: Long, onBack: () -> Unit) {
    val vm: HistoryViewModel = viewModel(factory = viewModelFactory {
        initializer { HistoryViewModel(this[APPLICATION_KEY] as TapGaugeApplication, tankId) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val lineColor = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("${state.tankName} \u2014 trend") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.points.size < 2) {
                Text("Not enough measurements yet to draw a trend. Keep tapping over time.")
            } else {
                Text("Fill % over time", style = MaterialTheme.typography.titleMedium)
                Canvas(Modifier.fillMaxWidth().height(220.dp)) {
                    val pts = state.points
                    val minT = pts.first().timestamp.toFloat()
                    val maxT = pts.last().timestamp.toFloat()
                    val spanT = (maxT - minT).coerceAtLeast(1f)
                    val w = size.width
                    val h = size.height

                    // gridlines at 0/50/100%
                    listOf(0f, 0.5f, 1f).forEach { g ->
                        val y = h - g * h
                        drawLine(Color.LightGray, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    }
                    val path = Path()
                    pts.forEachIndexed { i, p ->
                        val x = (p.timestamp - minT) / spanT * w
                        val y = h - (p.percent.toFloat() / 100f) * h
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                }
                Text("A smooth decline is a good sign; a jumpy line can mean a bad " +
                    "calibration or the tank was moved.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
