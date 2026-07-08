package com.tapgauge.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Tank geometry preset. DEMOTED to a secondary/optional field in the RV
 *  re-scope (section 1): RV holding tanks calibrate directly in volume-percent
 *  (section 3.2/4), so geometry is not needed for the calibration approach. Kept
 *  only for the "Advanced" disclosure on the Add Tank screen. */
enum class TankShape { VERTICAL_CYLINDER, HORIZONTAL_CYLINDER, RECTANGULAR, SPHERE, UNKNOWN }

/** PRIMARY classifying field (RV re-scope section 1). The holding-tank type drives
 *  default names, which calibration flow is offered (section 3), and result-screen
 *  copy (section 4). FRESH keeps the original Full/Empty anchors; GREY/BLACK use
 *  "Just Dumped" + Driveway Calibration. */
enum class TankType { FRESH, GREY, BLACK, OTHER }

/** A physical tank the user owns (spec section 3.2, RV re-scope section 1). */
@Entity(tableName = "tank_profiles")
data class TankProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: TankType = TankType.OTHER,   // primary classifier (re-scope section 1)
    val shape: TankShape = TankShape.UNKNOWN,
    val capacity: Double? = null,          // generic capacity in the user's chosen unit
    // RV data-plate capacity in gallons; frames Driveway Calibration progress
    // ("added 15 of 40 gal") and converts cumulative gallons -> % (section 1/3.2).
    val capacityGallons: Double? = null,
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
