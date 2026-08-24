package com.bivouac.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Single-row table: this is deliberately not a multi-trace store (that's the larger "banque de
// traces" feature, BIV-15) — just enough to survive a restart without losing the current plan.
// The GPX content is generated as text (via GpxWriter) rather than the source content:// Uri,
// since that Uri's read permission isn't guaranteed to outlive the app process.
//
// RIC-97 : ce texte vit maintenant dans un fichier sous PlanificationGpxStore (gpxFilePath, chemin
// relatif à filesDir) plutôt que dans la colonne gpxContent — même raison que côté banked_track :
// une colonne TEXT peut heurter la limite CursorWindow (~2 Mo/ligne), et ce singleton est lu à
// chaque démarrage à froid si une planification était ouverte.
@Entity(tableName = "saved_track")
data class SavedTrackEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val trackName: String?,
    val gpxFilePath: String,
    val bivouacTrackPointIndices: String,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
