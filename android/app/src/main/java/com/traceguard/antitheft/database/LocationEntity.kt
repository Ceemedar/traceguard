package com.traceguard.antitheft.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_reports")
data class LocationEntity(
    @PrimaryKey val locationId: String,
    val deviceId: String,
    val ownerUid: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val recordedAtMillis: Long,
    val batteryPercentage: Int?,
    val charging: Boolean?,
    val connectionType: String?,
    val networkOperator: String?,
    val wifiSsid: String?,
    val internetValidated: Boolean?,
)
