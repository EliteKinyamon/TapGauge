package com.tapgauge

import android.app.Application
import androidx.room.Room
import com.tapgauge.data.TapGaugeDatabase
import com.tapgauge.data.TankProfileRepository

/**
 * Manual dependency container (no DI framework needed for a single-module,
 * offline app). Holds the Room database and the repository as app singletons.
 */
class TapGaugeApplication : Application() {
    val database: TapGaugeDatabase by lazy {
        Room.databaseBuilder(this, TapGaugeDatabase::class.java, "tapgauge.db").build()
    }
    val repository: TankProfileRepository by lazy {
        TankProfileRepository(database.tankProfileDao(), database.calibrationPointDao())
    }
    val settings: SettingsStore by lazy { SettingsStore(this) }
}
