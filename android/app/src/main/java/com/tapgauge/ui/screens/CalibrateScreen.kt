package com.tapgauge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.tapgauge.TapGaugeApplication
import com.tapgauge.ui.components.CalibrationConfidenceBadge
import com.tapgauge.ui.components.LevelMeter
import com.tapgauge.ui.components.RequireMicPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrateScreen(tankId: Long, onBack: () -> Unit) {
    val vm: CalibrateViewModel = viewModel(factory = viewModelFactory {
        initializer { CalibrateViewModel(this[APPLICATION_KEY] as TapGaugeApplication, tankId) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.hapticNonce) {
        if (state.hapticNonce > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    var gaugePct by remember { mutableStateOf("") }
    var curWeight by remember { mutableStateOf("") }
    var tare by remember { mutableStateOf("") }
    var netFull by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibrate ${state.tankName}") },
                navigationIcon = {
                    IconButton(onClick = { vm.cancel(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        RequireMicPermission {
            Column(
                Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CalibrationConfidenceBadge(state.confidence)
                Text("Calibration anchors the estimate to real events. The two easiest: " +
                    "tap right after a refill (Full) and right when it runs out (Empty).",
                    style = MaterialTheme.typography.bodySmall)

                LevelMeter(state.level, Modifier.fillMaxWidth())
                Button(
                    onClick = { if (state.measuring) vm.cancel() else vm.tapToCapture() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        when {
                            state.measuring -> "Cancel (tap ${state.tapsDone}/3)"
                            state.lastReadingHz != null -> "Re-tap (last: ${state.lastReadingHz!!.toInt()} Hz)"
                            else -> "Tap the tank"
                        },
                    )
                }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                Divider()
                Text("Primary anchors", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.logAnchor(100.0, "full") },
                        modifier = Modifier.weight(1f)) { Text("Log as Full (100%)") }
                    OutlinedButton(onClick = { vm.logAnchor(0.0, "empty") },
                        modifier = Modifier.weight(1f)) { Text("Log as Empty (0%)") }
                }

                Divider()
                Text("Existing gauge cross-check", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = gaugePct, onValueChange = { gaugePct = it },
                        label = { Text("Gauge %") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        gaugePct.toDoubleOrNull()?.let { vm.logAnchor(it.coerceIn(0.0, 100.0), "gauge") }
                    }) { Text("Log") }
                }

                Divider()
                Text("Weigh-in (best for propane)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = curWeight, onValueChange = { curWeight = it },
                    label = { Text("Current total weight") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = tare, onValueChange = { tare = it },
                        label = { Text("Tare (TW)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(value = netFull, onValueChange = { netFull = it },
                        label = { Text("Net full wt") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f))
                }
                TextButton(onClick = {
                    val c = curWeight.toDoubleOrNull(); val t = tare.toDoubleOrNull()
                    val nf = netFull.toDoubleOrNull()
                    if (c != null && t != null && nf != null) {
                        vm.percentFromWeight(c, t, nf)?.let { vm.logAnchor(it, "weigh") }
                    }
                }) { Text("Compute % and log") }

                Divider()
                Text("Anchor points (${state.anchors.size})",
                    style = MaterialTheme.typography.titleSmall)
                state.anchors.forEach { a ->
                    Text("\u2022 ${a.frequencyHz.toInt()} Hz  \u2192  ${a.percent.toInt()}%",
                        style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { vm.resetCalibration() }) { Text("Reset calibration") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
