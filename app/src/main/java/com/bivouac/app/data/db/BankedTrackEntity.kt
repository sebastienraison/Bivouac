package com.bivouac.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bivouac.app.data.gpx.TrackStats

// The deliberate, named collection of traces the user has explicitly saved — distinct from
// SavedTrackEntity's singleton row, which is just an invisible crash/restart safety net for
// whatever is currently open. Stats are denormalized (computed once at save time via
// TrackStatsCalculator) so the home screen list doesn't need to re-parse every trace's full GPX
// content just to render distance/duration/D+/D-.
@Entity(tableName = "banked_track")
data class BankedTrackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val gpxContent: String,
    val bivouacTrackPointIndices: String,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val pointCount: Int,
    val estimatedDurationMinutes: Int,
    val savedAt: Long,
) {
    // Reconstructs the same TrackStats the open-trace toolbar shows, from the denormalized
    // columns — lets the home screen list reuse the StatsRows composable as-is for full parity.
    fun toTrackStats(): TrackStats = TrackStats(
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        pointCount = pointCount,
        estimatedDurationMinutes = estimatedDurationMinutes,
    )
}
