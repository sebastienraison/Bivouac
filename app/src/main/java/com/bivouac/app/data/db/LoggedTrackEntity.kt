package com.bivouac.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.gpx.TrackStatsParameters

// A completed hike logged into the Journal: immutable once imported, unlike banked_track's
// planned/editable entries. Stats are denormalized for the same reason as BankedTrackEntity (list
// rendering without re-parsing GPX). startedAt comes from the GPX's own first timestamp when
// present (real GPS traces normally have one), falling back to the import instant otherwise:
// it drives the chronological/by-year grouping, so it should reflect when the hike happened, not
// when it was imported.
@Entity(tableName = "logged_track")
data class LoggedTrackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val startedAt: Long,
    // SHA-256 of the concatenated raw day files, in order: catches re-importing the exact same
    // file(s) again. Deliberately not used alone for near-duplicate detection (a re-export of the
    // same hike from a different tool won't hash the same); see LoggedTrackRepository.findDuplicate.
    val contentHash: String,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val pointCount: Int,
    val estimatedDurationMinutes: Int,
    // Plain text, not Markdown: line breaks preserved, lines starting with "-" rendered as
    // bullets purely as a display transform (see BulletVisualTransformation). Lives on the
    // "Détails" sub-screen, not the main map/stats view: same reasoning as tags.
    val note: String = "",
    // RIC-114 lot 2 : version de l'algorithme (TrackStatsParameters.ALGORITHM_VERSION) qui a
    // calculé les quatre colonnes de statistiques ci-dessus. defaultValue = "0" côté colonne (une
    // ligne d'avant ce ticket, ancien algorithme mm5) ; défaut Kotlin à ALGORITHM_VERSION (une
    // ligne que le code construit maintenant vient d'être calculée par l'algorithme courant, voir
    // LoggedTrackRepository.prepareImport). Voir LoggedTrackBackfill.runStats pour le rattrapage
    // des lignes à 0.
    @ColumnInfo(defaultValue = "0")
    val statsVersion: Int = TrackStatsParameters.ALGORITHM_VERSION,
) {
    fun toTrackStats(): TrackStats = TrackStats(
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        estimatedDurationMinutes = estimatedDurationMinutes,
    )
}
