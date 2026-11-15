package com.traceguard.antitheft.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(report: LocationEntity)

    @Query("SELECT * FROM location_reports WHERE ownerUid = :ownerUid ORDER BY recordedAtMillis ASC LIMIT :limit")
    suspend fun pending(ownerUid: String, limit: Int = 200): List<LocationEntity>

    @Query("DELETE FROM location_reports WHERE locationId = :locationId")
    suspend fun deleteSynced(locationId: String)

    @Query("DELETE FROM location_reports WHERE deviceId = :deviceId")
    suspend fun deleteDeviceReports(deviceId: String)

    @Query("SELECT COUNT(*) FROM location_reports")
    suspend fun count(): Int

    @Query("DELETE FROM location_reports WHERE locationId IN (SELECT locationId FROM location_reports ORDER BY recordedAtMillis ASC LIMIT :count)")
    suspend fun trimOldest(count: Int)
}
