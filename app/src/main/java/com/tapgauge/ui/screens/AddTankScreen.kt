package com.tapgauge.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wash
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.tapgauge.TapGaugeApplication
import com.tapgauge.data.TankShape
import com.tapgauge.data.TankType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTankScreen(onCreated: (Long) -> Unit, onBack: () -> Unit) {
    val vm: AddTankViewModel = viewModel(factory = viewModelFactory {
        initializer { AddTankViewModel(this[APPLICATION_KEY] as TapGaugeApplication) }
    })

    // Type is the FIRST and primary choice (re-scope section 2). Nothing else on the
    // screen matters until it's picked, because it decides the calibration flow.
    var type by remember { mutableStateOf<TankType?>(null) }
    // Name auto-fills from the type but stays user-editable.
    var name by remember { mutableStateOf("") }
    var nameEditedByUser by remember { mutableStateOf(false) }
    var capacity by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var shape by remember { mutableStateOf(TankShape.UNKNOWN) }
    var bandLow by remember { mutableStateOf("60") }
    var bandHigh by remember { mutableStateOf("3000") }

    fun pickType(t: TankType) {
        type = t
        if (!nameEditedByUser) name = AddTankViewModel.defaultName(t)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add tank") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Which tank is this?", style = MaterialTheme.typography.titleMedium)
            Text("For the tank sensor RVers already know is lying to them.",
                style = MaterialTheme.typography.bodySmall)

            // Big icon chips, not a text field (re-scope section 2).
            val types = listOf(
                TankType.FRESH to ("Fresh Water" to Icons.Filled.WaterDrop),
                TankType.GREY to ("Grey Water" to Icons.Filled.Wash),
                TankType.BLACK to ("Black Water" to Icons.Filled.Opacity),
                TankType.OTHER to ("Other" to Icons.Outlined.Dashboard),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                types.chunked(2).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        rowItems.forEach { (t, meta) ->
                            val (label, icon: ImageVector) = meta
                            FilterChip(
                                selected = type == t,
                                onClick = { pickType(t) },
                                leadingIcon = { Icon(icon, contentDescription = null) },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // Everything below only matters once a type is chosen.
            if (type != null) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameEditedByUser = true },
                    label = { Text("Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = capacity, onValueChange = { capacity = it },
                    label = { Text("Capacity in gallons (recommended)") },
                    supportingText = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, contentDescription = null,
                                modifier = Modifier.height(16.dp))
                            Spacer(Modifier.height(0.dp))
                            Text("  On your RV\u2019s data plate or in the owner\u2019s manual. " +
                                "Used to frame Driveway Calibration (\u201cadded 15 of 40 gal\u201d).")
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                // Advanced disclosure, OFF by default (re-scope section 2): built-in RV tanks
                // aren't clean geometric shapes and this flow doesn't need geometry.
                TextButton(onClick = { advanced = !advanced }) {
                    Icon(if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null)
                    Text("  Advanced (shape & resonance band)")
                }
                AnimatedVisibility(visible = advanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Shape / orientation (optional)",
                            style = MaterialTheme.typography.labelLarge)
                        val shapes = listOf(
                            TankShape.VERTICAL_CYLINDER to "Vertical cyl.",
                            TankShape.HORIZONTAL_CYLINDER to "Horizontal cyl.",
                            TankShape.RECTANGULAR to "Rectangular",
                            TankShape.SPHERE to "Sphere",
                            TankShape.UNKNOWN to "Not sure",
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            shapes.chunked(2).forEach { r ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    r.forEach { (s, label) ->
                                        FilterChip(selected = shape == s, onClick = { shape = s },
                                            label = { Text(label) })
                                    }
                                }
                            }
                        }
                        Text("Resonance search band (Hz)",
                            style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = bandLow, onValueChange = { bandLow = it },
                                label = { Text("Low") }, singleLine = true,
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedTextField(
                                value = bandHigh, onValueChange = { bandHigh = it },
                                label = { Text("High") }, singleLine = true,
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.create(
                            name = name,
                            type = type!!,
                            capacityGallons = capacity.toDoubleOrNull(),
                            shape = shape,
                            bandLowHz = bandLow.toDoubleOrNull() ?: 60.0,
                            bandHighHz = bandHigh.toDoubleOrNull() ?: 3000.0,
                            onCreated = onCreated,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create & calibrate") }
            }
        }
    }
}
