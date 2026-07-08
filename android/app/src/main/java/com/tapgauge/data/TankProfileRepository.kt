package com.tapgauge.data

import com.tapgauge.calibration.CalibrationEngine
import com.tapgauge.calibration.CalibrationPoint
import kotlinx.coroutines.flow.Flow

/**
 * Room-backed CRUD for tank profiles, calibration points, and measurement
 * history (spec section 4). Also the single place that maps stored rows into the
 * pure-Kotlin [CalibrationPoint] model consumed by [CalibrationEngine], so the
 * DSP/calibration layer stays free of Android/Room types and remains unit-testable.
 */
class TankProfileRepository(
    private val tankDao: TankProfileDao,
    private val pointDao: CalibrationPointDao,
) {
    fun observeTanks(): Flow<List<TankProfileEntity>> = tankDao.observeAll()
    fun observeTank(id: Long): Flow<TankProfileEntity?> = tankDao.observe(id)
    suspend fun getTank(id: Long): TankProfileEntity? = tankDao.get(id)

    suspend fun createTank(profile: TankProfileEntity): Long = tankDao.insert(profile)
    suspend fun updateTank(profile: TankProfileEntity) = tankDao.update(profile)
    suspend fun deleteTank(profile: TankProfileEntity) = tankDao.delete(profile)

    fun observePoints(tankId: Long): Flow<List<CalibrationPointEntity>> =
        pointDao.observeForTank(tankId)

    /** Store any tap. Anchors carry a non-null percent; silent measurement taps
     *  carry null (spec section 6.3). Raw audio is never stored -- only the extracted
     *  frequency + prominence. */
    suspend fun addPoint(
        tankId: Long,
        timestamp: Long,
        frequencyHz: Double,
        knownLevelPercent: Double?,
        prominence: Double,
        anchorKind: String? = null,
    ): Long = pointDao.insert(
        CalibrationPointEntity(
            tankId = tankId,
            timestamp = timestamp,
            frequencyHz = frequencyHz,
            knownLevelPercent = knownLevelPercent,
            prominence = prominence,
            anchorKind = anchorKind,
        ),
    )

    suspend fun resetCalibration(tankId: Long) = pointDao.clearAnchorsForTank(tankId)
    suspend fun resetAll(tankId: Long) = pointDao.clearForTank(tankId)

    /** Build a [CalibrationEngine] loaded with this tank's anchor points. */
    suspend fun loadEngine(tankId: Long): CalibrationEngine {
        val engine = CalibrationEngine()
        val anchors = pointDao.getAnchorsForTank(tankId).map { it.toDomain() }
        engine.setAnchors(anchors)
        return engine
    }

    suspend fun history(tankId: Long): List<CalibrationPoint> =
        pointDao.getForTank(tankId).map { it.toDomain() }
}

fun CalibrationPointEntity.toDomain(): CalibrationPoint =
    CalibrationPoint(timestamp, frequencyHz, knownLevelPercent)
