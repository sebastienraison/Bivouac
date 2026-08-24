package com.bivouac.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bivouac.app.data.gpx.TrackStats

// The deliberate, named collection of traces the user has explicitly saved — distinct from
// SavedTrackEntity's singleton row, which is just an invisible crash/restart safety net for
// whatever is currently open. Stats are denormalized (computed once at save time via
// TrackStatsCalculator) so the home screen list doesn't need to re-parse every trace's full GPX
// content just to render distance/duration/D+/D-.
//
// RIC-97 : gpxContent (TEXT) a laissé place à gpxFilePath, chemin relatif à filesDir vers un
// fichier sous PlanificationGpxStore — même raison que rawGpxFilePath sur LoggedTrackDayEntity
// (RIC-62) : une colonne TEXT peut heurter la limite CursorWindow (~2 Mo/ligne). pointCount part
// avec elle : colonne écrite mais jamais lue (vérifié par grep), et la table était de toute façon
// recréée pour sortir gpxContent.
@Entity(tableName = "banked_track")
data class BankedTrackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val gpxFilePath: String,
    val bivouacTrackPointIndices: String,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val estimatedDurationMinutes: Int,
    val savedAt: Long,
) {
    // Reconstructs the same TrackStats the open-trace toolbar shows, from the denormalized
    // columns — lets the home screen list reuse the StatsRows composable as-is for full parity.
    fun toTrackStats(): TrackStats = TrackStats(
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        estimatedDurationMinutes = estimatedDurationMinutes,
    )
}
