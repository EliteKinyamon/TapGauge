package com.tapgauge.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapgauge.TapGaugeApplication
import com.tapgauge.data.TankProfileEntity
import com.tapgauge.data.TankShape
import com.tapgauge.data.TankType
import kotlinx.coroutines.launch

class AddTankViewModel(private val app: TapGaugeApplication) : ViewModel() {
    fun create(
        name: String,
        type: TankType,
        capacityGallons: Double?,
        shape: TankShape,
        bandLowHz: Double,
        bandHighHz: Double,
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            val id = app.repository.createTank(
                TankProfileEntity(
                    name = name.ifBlank { defaultName(type) },
                    type = type,
                    capacityGallons = capacityGallons,
                    shape = shape,
                    bandLowHz = bandLowHz,
                    bandHighHz = bandHighHz,
                ),
            )
            onCreated(id)
        }
    }

    companion object {
        /** Suggested, editable default name per RV tank type (re-scope section 2). */
        fun defaultName(type: TankType): String = when (type) {
            TankType.FRESH -> "Fresh Water"
            TankType.GREY -> "Grey Water"
            TankType.BLACK -> "Black Water"
            TankType.OTHER -> "Tank"
        }
    }
}
