package com.bivouac.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Une photo associée à une sortie du Journal (RIC-43). Copie locale uniquement (voir
// LoggedTrackPhotoStore) : latitude/longitude/takenAtMillis viennent de l'EXIF au moment de
// l'ajout et sont dénormalisés ici pour ne jamais avoir à rouvrir le fichier ensuite, même
// raisonnement que les colonnes dénormalisées de LoggedTrackDayEntity.
//
// positionPointIndex indexe le HikeTrack concaténé (jours dans l'ordre, voir
// LoggedTrackRepository.open) — même convention que bivouacTrackPointIndices sur
// BankedTrackEntity/SavedTrackEntity pour "un marqueur accroché à un point de la trace". Null
// quand aucune corrélation (GPS ni horodatage) n'a été possible : la photo reste alors accessible
// seulement depuis la galerie plate, jamais un blocage.
//
// positionApproximate distingue une position déduite par horodatage (bandeau "positionnement
// approximatif" côté UI) d'une position certaine — GPS d'origine ou repositionnement manuel par
// l'utilisateur, qui vaut confirmation explicite au même titre qu'un GPS exact.
@Entity(
    tableName = "logged_track_photo",
    foreignKeys = [
        ForeignKey(
            entity = LoggedTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackId"])],
)
data class LoggedTrackPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    // Relatif à filesDir, jamais absolu — voir LoggedTrackPhotoStore pour la même raison que
    // LoggedTrackGpxStore.rawGpxFilePath.
    val filePath: String,
    val addedAtMillis: Long,
    val takenAtMillis: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val positionPointIndex: Int? = null,
    val positionApproximate: Boolean = false,
    // SHA-256 des octets de la photo, même principe que LoggedTrackEntity.contentHash — sert à
    // refuser un doublon (même photo sélectionnée deux fois, dans le même lot ou plus tard) sans
    // se fier à l'Uri du Photo Picker, qui n'est pas garantie stable d'une sélection à l'autre.
    // Vide sur les lignes d'avant cette colonne (migration 15->16), jamais recalculé après coup.
    val contentHash: String = "",
)
