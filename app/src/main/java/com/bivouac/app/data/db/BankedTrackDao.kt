package com.bivouac.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BankedTrackDao {

    @Query("SELECT * FROM banked_track ORDER BY savedAt DESC")
    suspend fun list(): List<BankedTrackEntity>

    @Query("SELECT * FROM banked_track WHERE id = :id")
    suspend fun get(id: String): BankedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BankedTrackEntity)

    @Query("DELETE FROM banked_track WHERE id = :id")
    suspend fun delete(id: String)

    // RIC-114 lot 2 : même patron que LoggedTrackDao (statsVersion < version), voir sa kdoc.
    @Query("SELECT * FROM banked_track WHERE statsVersion < :version ORDER BY id LIMIT :limit")
    suspend fun getTracksNeedingStatsBackfill(version: Int, limit: Int): List<BankedTrackEntity>

    @Query("SELECT COUNT(*) FROM banked_track WHERE statsVersion < :version")
    suspend fun countTracksNeedingStatsBackfill(version: Int): Int

    @Query("UPDATE banked_track SET statsVersion = :version WHERE id = :id")
    suspend fun markStatsVersion(id: String, version: Int)

    @Query(
        "UPDATE banked_track SET distanceMeters = :distanceMeters, " +
            "elevationGainMeters = :elevationGainMeters, elevationLossMeters = :elevationLossMeters, " +
            "estimatedDurationMinutes = :estimatedDurationMinutes, statsVersion = :statsVersion " +
            "WHERE id = :id",
    )
    suspend fun updateTrackStats(
        id: String,
        distanceMeters: Double,
        elevationGainMeters: Double,
        elevationLossMeters: Double,
        estimatedDurationMinutes: Int,
        statsVersion: Int,
    )
}
