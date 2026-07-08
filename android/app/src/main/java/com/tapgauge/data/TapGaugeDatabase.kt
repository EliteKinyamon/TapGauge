package com.tapgauge.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun shapeToString(s: TankShape): String = s.name
    @TypeConverter fun stringToShape(s: String): TankShape =
        runCatching { TankShape.valueOf(s) }.getOrDefault(TankShape.UNKNOWN)
}

@Database(
    entities = [TankProfileEntity::class, CalibrationPointEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TapGaugeDatabase : RoomDatabase() {
    abstract fun tankProfileDao(): TankProfileDao
    abstract fun calibrationPointDao(): CalibrationPointDao
}
