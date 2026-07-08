package com.tapgauge.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun shapeToString(s: TankShape): String = s.name
    @TypeConverter fun stringToShape(s: String): TankShape =
        runCatching { TankShape.valueOf(s) }.getOrDefault(TankShape.UNKNOWN)

    @TypeConverter fun typeToString(t: TankType): String = t.name
    @TypeConverter fun stringToType(s: String): TankType =
        runCatching { TankType.valueOf(s) }.getOrDefault(TankType.OTHER)
}

@Database(
    entities = [TankProfileEntity::class, CalibrationPointEntity::class],
    // v2: RV re-scope added TankType + capacityGallons columns (section 1).
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TapGaugeDatabase : RoomDatabase() {
    abstract fun tankProfileDao(): TankProfileDao
    abstract fun calibrationPointDao(): CalibrationPointDao
}
