package com.tapgauge.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapgauge.TapGaugeApplication
import com.tapgauge.data.TankProfileEntity
import com.tapgauge.data.TankShape
import kotlinx.coroutines.launch

class AddTankViewModel(private val app: TapGaugeApplication) : ViewModel() {
    fun create(
        name: String,
        shape: TankShape,
        capacity: Double?,
        tareWeight: Double?,
        bandLowHz: Double,
        bandHighHz: Double,
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            val id = app.repository.createTank(
                TankProfileEntity(
                    name = name.ifBlank { "Tank" },
                    shape = shape,
                    capacity = capacity,
                    tareWeight = tareWeight,
                    bandLowHz = bandLowHz,
                    bandHighHz = bandHighHz,
                ),
            )
            onCreated(id)
        }
    }
}
