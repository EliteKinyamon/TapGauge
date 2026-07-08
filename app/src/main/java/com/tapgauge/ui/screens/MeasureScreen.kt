package com.tapgauge.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.tapgauge.TapGaugeApplication
import com.tapgauge.data.TankType
import com.tapgauge.ui.components.CalibrationConfidenceBadge
import com.tapgauge.ui.components.LevelMeter
import com.tapgauge.ui.components.ReadingConfidenceBadge
import com.tapgauge.ui.components.RequireMicPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasureScreen(
    tankId: Long,
    onCalibrate: () -> Unit,
    onHistory: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: MeasureViewModel = viewModel(factory = viewModelFactory {
        initializer { MeasureViewModel(this[APPLICATION_KEY] as TapGaugeApplication, tankId) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    // Fire a haptic buzz the instant a knock is detected (spec section 3.4 / 7).
    LaunchedEffect(state.hapticNonce) {
        if (state.hapticNonce > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.tankName) },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.stopMeasurement(); onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        RequireMicPermission {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CalibrationConfidenceBadge(state.calibrationConfidence)
                Text("${state.anchorCount} calibration point(s)",
                    style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(8.dp))

                // Hero number (spec section 3.4 / 7).
                Box(Modifier.height(120.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.measuring -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(8.dp))
                                Text("Tap ${state.tapsDone + 1} of ${state.tapsNeeded} \u2014 knock the tank")
                            }
                        }
                        state.percent != null -> {
                            Text("${state.percent!!.toInt()}%",
                                fontSize = 72.sp, fontWeight = FontWeight.Bold)
                        }
                        else -> Text("\u2014", fontSize = 72.sp, fontWeight = FontWeight.Bold)
                    }
                }

                state.readingConfidence?.let { ReadingConfidenceBadge(it) }
                if (state.extrapolated) {
                    Text("Outside calibrated range \u2014 extrapolated estimate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center)
                }
                // Per-type framing around the decision RVers actually make (re-scope section 4).
                if (state.percent != null) {
                    val advice = when (state.tankType) {
                        TankType.GREY, TankType.BLACK -> "Plan your next dump."
                        TankType.FRESH -> "Fresh water left for the trip."
                        TankType.OTHER -> null
                    }
                    advice?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center)
                    }
                }
                state.daysUntilEmpty?.let {
                    Text("~${it.toInt()} days until empty (recent rate)",
                        style = MaterialTheme.typography.bodyMedium)
                }
                state.message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center)
                }

                Spacer(Modifier.height(8.dp))
                LevelMeter(state.level, Modifier.fillMaxWidth())

                Button(
                    onClick = { if (state.measuring) vm.stopMeasurement() else vm.startMeasurement() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(if (state.measuring) "Cancel" else "Tap to measure",
                        style = MaterialTheme.typography.titleMedium)
                }

                // In-context calibration shortcuts, type-aware (re-scope section 3.4).
                // You never "fill" a waste tank, so Grey/Black only offer the 0% anchor.
                when (state.tankType) {
                    TankType.GREY, TankType.BLACK -> {
                        OutlinedButton(
                            onClick = { vm.logAnchorFromLastReading(0.0, "dumped") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Just Dumped (0%)") }
                        Text("For in-between levels, use Driveway Calibration.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center)
                    }
                    else -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { vm.logAnchorFromLastReading(100.0, "full") },
                                modifier = Modifier.weight(1f),
                            ) { Text("This is Full") }
                            OutlinedButton(
                                onClick = { vm.logAnchorFromLastReading(0.0, "empty") },
                                modifier = Modifier.weight(1f),
                            ) { Text("Just ran Empty") }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onCalibrate, modifier = Modifier.weight(1f)) {
                        Text("Calibrate")
                    }
                    TextButton(onClick = onHistory, modifier = Modifier.weight(1f)) {
                        Text("History")
                    }
                }

                Text(
                    "Estimate for convenience, not a certified gauge.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
