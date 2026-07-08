package com.tapgauge.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Tank geometry preset (spec section 3.2). Affects height<->volume conversion only,
 *  not the DSP. For v1 most anchors are given as volume/weight %, which
 *  sidesteps the geometry math entirely (spec section 6.4). */
enum class TankShape { VERTICAL_CYLINDER, HORIZONTAL_CYLINDER, RECTANGULAR, SPHERE, UNKNOWN }

/** A physical tank the user owns (spec section 3.2). */
@Entity(tableName = "tank_profiles")
data class TankProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val shape: TankShape = TankShape.UNKNOWN,
    val capacity: Double? = null,          // in the user's chosen unit
    val tareWeight: Double? = null,        // propane "TW" stamp, optional
    val photoUri: String? = null,          // local content URI, user's list view only
    // Per-tank resonance search band (spec section 5.4 is per-profile-configurable).
    val bandLowHz: Double = 60.0,
    val bandHighHz: Double = 3000.0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One stored sample for a tank (spec section 6.1). Anchors (calibration ground
 * truth) have a non-null [knownLevelPercent]; ordinary "silent" measurement
 * taps have null (spec section 6.3). We store the extracted [frequencyHz] and a
 * [prominence] confidence signal -- never raw audio (spec section 4 privacy stance).
 */
@Entity(
    tableName = "calibration_points",
    foreignKeys = [ForeignKey(
        entity = TankProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["tankId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tankId")],
)
data class CalibrationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tankId: Long,
    val timestamp: Long,
    val frequencyHz: Double,
    val knownLevelPercent: Double?,   // null = ordinary measurement tap
    val prominence: Double = 0.0,
    val anchorKind: String? = null,   // "full" | "empty" | "weigh" | "gauge" | "volume"
)
