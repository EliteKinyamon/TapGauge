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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.tapgauge.TapGaugeApplication
import com.tapgauge.data.TankShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTankScreen(onCreated: (Long) -> Unit, onBack: () -> Unit) {
    val vm: AddTankViewModel = viewModel(factory = viewModelFactory {
        initializer { AddTankViewModel(this[APPLICATION_KEY] as TapGaugeApplication) }
    })

    var name by remember { mutableStateOf("") }
    var shape by remember { mutableStateOf(TankShape.UNKNOWN) }
    var capacity by remember { mutableStateOf("") }
    var tare by remember { mutableStateOf("") }
    var bandLow by remember { mutableStateOf("60") }
    var bandHigh by remember { mutableStateOf("3000") }

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
            Text("No sensor to buy or install \u2014 just your phone.",
                style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name (e.g. Grill propane tank)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Text("Shape / orientation", style = MaterialTheme.typography.labelLarge)
            val shapes = listOf(
                TankShape.VERTICAL_CYLINDER to "Vertical cyl.",
                TankShape.HORIZONTAL_CYLINDER to "Horizontal cyl.",
                TankShape.RECTANGULAR to "Rectangular",
                TankShape.SPHERE to "Sphere",
                TankShape.UNKNOWN to "Not sure",
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shapes.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (s, label) ->
                            FilterChip(selected = shape == s, onClick = { shape = s },
                                label = { Text(label) })
                        }
                    }
                }
            }

            OutlinedTextField(
                value = capacity, onValueChange = { capacity = it },
                label = { Text("Capacity (optional, gal/lbs)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tare, onValueChange = { tare = it },
                label = { Text("Tare / empty weight (optional, propane \u201cTW\u201d)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Text("Resonance search band (Hz)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = bandLow, onValueChange = { bandLow = it },
                    label = { Text("Low") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = bandHigh, onValueChange = { bandHigh = it },
                    label = { Text("High") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    vm.create(
                        name = name,
                        shape = shape,
                        capacity = capacity.toDoubleOrNull(),
                        tareWeight = tare.toDoubleOrNull(),
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
