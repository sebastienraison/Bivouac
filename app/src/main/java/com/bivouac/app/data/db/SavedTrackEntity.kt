package com.bivouac.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Single-row table: this is deliberately not a multi-trace store (that's the larger "banque de
// traces" feature, BIV-15) — just enough to survive a restart without losing the current plan.
// The GPX content is stored as text (via GpxWriter) rather than the source content:// Uri, since
// that Uri's read permission isn't guaranteed to outlive the app process.
@Entity(tableName = "saved_track")
data class SavedTrackEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val trackName: String?,
    val gpxContent: String,
    val bivouacTrackPointIndices: String,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
