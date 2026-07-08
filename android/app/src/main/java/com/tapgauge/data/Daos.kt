package com.tapgauge.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TankProfileDao {
    @Query("SELECT * FROM tank_profiles ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TankProfileEntity>>

    @Query("SELECT * FROM tank_profiles WHERE id = :id")
    fun observe(id: Long): Flow<TankProfileEntity?>

    @Query("SELECT * FROM tank_profiles WHERE id = :id")
    suspend fun get(id: Long): TankProfileEntity?

    @Insert
    suspend fun insert(profile: TankProfileEntity): Long

    @Update
    suspend fun update(profile: TankProfileEntity)

    @Delete
    suspend fun delete(profile: TankProfileEntity)
}

@Dao
interface CalibrationPointDao {
    @Query("SELECT * FROM calibration_points WHERE tankId = :tankId ORDER BY timestamp ASC")
    fun observeForTank(tankId: Long): Flow<List<CalibrationPointEntity>>

    @Query("SELECT * FROM calibration_points WHERE tankId = :tankId ORDER BY timestamp ASC")
    suspend fun getForTank(tankId: Long): List<CalibrationPointEntity>

    @Query("SELECT * FROM calibration_points WHERE tankId = :tankId AND knownLevelPercent IS NOT NULL ORDER BY frequencyHz ASC")
    suspend fun getAnchorsForTank(tankId: Long): List<CalibrationPointEntity>

    @Insert
    suspend fun insert(point: CalibrationPointEntity): Long

    @Query("DELETE FROM calibration_points WHERE tankId = :tankId")
    suspend fun clearForTank(tankId: Long)

    @Query("DELETE FROM calibration_points WHERE tankId = :tankId AND knownLevelPercent IS NOT NULL")
    suspend fun clearAnchorsForTank(tankId: Long)
}
