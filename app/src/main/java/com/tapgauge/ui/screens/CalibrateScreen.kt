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
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
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
import com.tapgauge.data.TankType
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

                // Shared capture control -- every method logs against the last tap.
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
                when (state.tankType) {
                    TankType.FRESH -> FreshSection(vm)
                    TankType.GREY, TankType.BLACK -> WasteSection(vm, state)
                    TankType.OTHER -> OtherSection(vm)
                }

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

@Composable
private fun FreshSection(vm: CalibrateViewModel) {
    Text("Fresh water", style = MaterialTheme.typography.titleSmall)
    Text("Easiest anchors: tap right after filling at the spigot (Full), and the " +
        "moment the pump sputters or the faucet runs air (Empty).",
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.logAnchor(100.0, "full") },
            modifier = Modifier.weight(1f)) { Text("Just Filled (100%)") }
        OutlinedButton(onClick = { vm.logAnchor(0.0, "empty") },
            modifier = Modifier.weight(1f)) { Text("Ran Dry (0%)") }
    }
    GaugeCrossCheck(vm)
}

@Composable
private fun WasteSection(vm: CalibrateViewModel, state: CalibrateUiState) {
    val isBlack = state.tankType == TankType.BLACK
    Text(if (isBlack) "Black tank" else "Grey tank",
        style = MaterialTheme.typography.titleSmall)

    // Primary, always-available anchor.
    OutlinedButton(onClick = { vm.logJustDumped() }, modifier = Modifier.fillMaxWidth()) {
        Text("Just Dumped + Rinsed (0%)")
    }
    Text("Do this at the dump station every time you empty \u2014 it keeps the 0% end honest.",
        style = MaterialTheme.typography.bodySmall)

    Spacer(Modifier.height(4.dp))
    // The star feature -- pre-empt the obvious question up front (re-scope section 3.2).
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Driveway Calibration", style = MaterialTheme.typography.titleSmall)
            Text(
                "Yes \u2014 you fill the tank with CLEAN water on purpose, once, at home. " +
                    "That\u2019s the only way to teach TapGauge the in-between levels without " +
                    "waiting months. Add water in known amounts, tapping after each step. " +
                    "When you\u2019re done, dump and rinse before your trip.",
                style = MaterialTheme.typography.bodySmall,
            )

            DrivewayCapacity(vm, state)
            DrivewayIncrements(vm, state)
        }
    }
}

@Composable
private fun DrivewayCapacity(vm: CalibrateViewModel, state: CalibrateUiState) {
    var capText by remember(state.capacityGallons) {
        mutableStateOf(state.capacityGallons?.let { "%.0f".format(it) } ?: "")
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = capText, onValueChange = { capText = it },
            label = { Text("Tank capacity (gal)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { vm.setCapacityGallons(capText.toDoubleOrNull()) }) { Text("Save") }
    }
}

@Composable
private fun DrivewayIncrements(vm: CalibrateViewModel, state: CalibrateUiState) {
    var mode by remember { mutableStateOf(0) } // 0 = counted jugs, 1 = timed hose
    var jugSize by remember { mutableStateOf("5") }
    var flowRate by remember { mutableStateOf("3.0") } // gal/min default estimate
    var seconds by remember { mutableStateOf("60") }

    val cap = state.capacityGallons ?: 0.0
    val added = state.drivewayCumulativeGallons
    val frac = if (cap > 0) (added / cap).coerceIn(0.0, 1.0) else 0.0

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == 0, onClick = { mode = 0 },
            label = { Text("Counted jugs") })
        FilterChip(selected = mode == 1, onClick = { mode = 1 },
            label = { Text("Timed hose") })
    }

    if (mode == 0) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = jugSize, onValueChange = { jugSize = it },
                label = { Text("Jug size (gal)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f))
            Button(onClick = { jugSize.toDoubleOrNull()?.let { vm.logDrivewayIncrement(it) } }) {
                Text("Added one + tapped")
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = flowRate, onValueChange = { flowRate = it },
                label = { Text("Flow (gal/min)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f))
            OutlinedTextField(value = seconds, onValueChange = { seconds = it },
                label = { Text("Seconds") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f))
        }
        Button(
            onClick = {
                val r = flowRate.toDoubleOrNull(); val s = seconds.toDoubleOrNull()
                if (r != null && s != null) vm.logDrivewayIncrement(r * s / 60.0)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Log this pour + tapped") }
    }

    if (cap > 0) {
        LinearProgressIndicator(progress = { frac.toFloat() }, modifier = Modifier.fillMaxWidth())
        Text("Added ${"%.1f".format(added)} of ${"%.0f".format(cap)} gal " +
            "(${(frac * 100).toInt()}%). Aim for at least 3 steps \u2014 e.g. quarter, half, " +
            "three-quarters \u2014 plus the 0% dump anchor.",
            style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { vm.resetDriveway() }) { Text("Reset driveway total") }
    Text("When finished: DUMP and RINSE before real use \u2014 the tank now holds " +
        "calibration water, not waste.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error)
}

@Composable
private fun OtherSection(vm: CalibrateViewModel) {
    var curWeight by remember { mutableStateOf("") }
    var tare by remember { mutableStateOf("") }
    var netFull by remember { mutableStateOf("") }

    Text("General calibration", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.logAnchor(100.0, "full") },
            modifier = Modifier.weight(1f)) { Text("Log Full (100%)") }
        OutlinedButton(onClick = { vm.logAnchor(0.0, "empty") },
            modifier = Modifier.weight(1f)) { Text("Log Empty (0%)") }
    }
    GaugeCrossCheck(vm)

    Divider()
    Text("Weigh-in (e.g. propane)", style = MaterialTheme.typography.titleSmall)
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
}

@Composable
private fun GaugeCrossCheck(vm: CalibrateViewModel) {
    var gaugePct by remember { mutableStateOf("") }
    Divider()
    Text("Existing gauge cross-check", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = gaugePct, onValueChange = { gaugePct = it },
            label = { Text("Gauge %") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f))
        TextButton(onClick = {
            gaugePct.toDoubleOrNull()?.let { vm.logAnchor(it.coerceIn(0.0, 100.0), "gauge") }
        }) { Text("Log") }
    }
}
