package com.bivouac.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bivouac.app.data.gpx.TrackStats

// A completed hike logged into the Journal — immutable once imported, unlike banked_track's
// planned/editable entries. Stats are denormalized for the same reason as BankedTrackEntity (list
// rendering without re-parsing GPX). startedAt comes from the GPX's own first timestamp when
// present (real GPS traces normally have one), falling back to the import instant otherwise —
// it drives the chronological/by-year grouping, so it should reflect when the hike happened, not
// when it was imported.
@Entity(tableName = "logged_track")
data class LoggedTrackEntity(
    @PrimaryKey val id: String,
    val name: String,
    // The picked file's own display name, kept purely for traceability/provenance and as a
    // rename suggestion later — real GPX exports are often better-named than the in-file <name>
    // tag (which can be a generic device/location label repeated across several different hikes).
    val sourceFileName: String?,
    val startedAt: Long,
    // SHA-256 of the concatenated raw day files, in order — catches re-importing the exact same
    // file(s) again. Deliberately not used alone for near-duplicate detection (a re-export of the
    // same hike from a different tool won't hash the same); see LoggedTrackRepository.findDuplicate.
    val contentHash: String,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val pointCount: Int,
    val estimatedDurationMinutes: Int,
    // Plain text, not Markdown — line breaks preserved, lines starting with "-" rendered as
    // bullets purely as a display transform (see BulletVisualTransformation). Lives on the
    // "Détails" sub-screen, not the main map/stats view — same reasoning as tags.
    val note: String = "",
) {
    fun toTrackStats(): TrackStats = TrackStats(
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        pointCount = pointCount,
        estimatedDurationMinutes = estimatedDurationMinutes,
    )
}
