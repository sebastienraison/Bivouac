package com.bivouac.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// One raw, untouched GPX file belonging to a logged_track — a separate row per day rather than a
// single blob, so a multi-day hike (several device exports, one per day) can be represented
// without ever concatenating or otherwise altering the original files. Unlike banked_track's
// gpxContent (re-serialized, extensions stripped), the raw content is stored byte-for-byte as
// imported: the Journal's whole point is to keep everything, even data the app doesn't use yet.
// RIC-62 : le contenu lui-même vit dans un fichier sous le stockage interne (voir
// LoggedTrackGpxStore), la ligne ne porte que son chemin relatif — une trace volumineuse ne
// passe plus par un Cursor Room, donc plus de plafond CursorWindow possible à la lecture.
@Entity(
    tableName = "logged_track_day",
    foreignKeys = [
        ForeignKey(
            entity = LoggedTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId")],
)
data class LoggedTrackDayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val dayIndex: Int,
    val rawGpxFilePath: String,
    // Les trois colonnes ci-dessous dénormalisent ce qu'on lisait jusqu'ici en reparsant le
    // fichier. Motif : le coût d'un import ne dépendait pas de ce qu'on importait mais de la
    // taille de la banque (voir LoggedTrackRepository.calibrationSamples), donc il se dégradait
    // tout seul à mesure que l'archive grossit.
    //
    // contentHash est le pivot : il est calculable pour n'importe quel fichier, horodaté ou non,
    // donc il vaut null si et seulement si la ligne n'a pas encore été rattrapée (voir
    // LoggedTrackBackfill). Les deux autres restent null après rattrapage quand le GPX n'a
    // aucun horodatage exploitable, ce qui est un cas réel et pas une anomalie.
    val contentHash: String? = null,
    // Horodatage du premier point du jour. Sert à afficher la plage de dates réelle d'un trek
    // sans ouvrir le moindre fichier ; la déduire de logged_track.startedAt plus dayIndex
    // supposerait des jours strictement consécutifs, faux dès qu'un trek comporte une journée
    // sans trace enregistrée.
    val startedAtMillis: Long? = null,
    // Temps écoulé du premier au dernier point du jour. Sommé par jour et jamais de bout en bout,
    // pour qu'une nuit passée dehors ne compte pas comme du temps de marche.
    val elapsedSeconds: Long? = null,
)
