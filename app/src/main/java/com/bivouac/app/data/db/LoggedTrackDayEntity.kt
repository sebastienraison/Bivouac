package com.bivouac.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// One raw, untouched GPX file belonging to a logged_track: a separate row per day rather than a
// single blob, so a multi-day hike (several device exports, one per day) can be represented
// without ever concatenating or otherwise altering the original files. Unlike banked_track's
// gpxContent (re-serialized, extensions stripped), the raw content is stored byte-for-byte as
// imported: the Journal's whole point is to keep everything, even data the app doesn't use yet.
// RIC-62 : le contenu lui-même vit dans un fichier sous le stockage interne (voir
// LoggedTrackGpxStore), la ligne ne porte que son chemin relatif : une trace volumineuse ne
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
    // RIC-109 : les sept sommes dont la calibration vitesse/pénalité D+ par segments a besoin (voir
    // TrackSegmenter et DaySegmentAggregate), calculées une fois à l'import à partir des segments
    // de 200 m de ce jour, jamais recalculées à la volée. Comme pour contentHash ci-dessus,
    // flatCount vaut null si et seulement si ce jour n'a pas encore été rattrapé pour ces colonnes
    // (voir LoggedTrackBackfill) ; une fois rattrapé, il vaut 0 (pas null) si la trace n'a aucun
    // segment plat exploitable, ce qui est un cas réel distinct du "pas encore calculé". Les six
    // autres colonnes suivent le même sort que flatCount : soit toutes nulles (pas rattrapé), soit
    // toutes renseignées (y compris à 0.0 si le jour n'a aucun segment de la catégorie).
    val flatCount: Int? = null,
    val flatDistanceMeters: Double? = null,
    val flatHours: Double? = null,
    val steepCount: Int? = null,
    val steepDistanceMeters: Double? = null,
    val steepGainMeters: Double? = null,
    val steepHours: Double? = null,
    // RIC-115 : heures cumulées des segments à l'arrêt de ce jour (voir DaySegmentAggregate.
    // stoppedHours), rattrapées dans la même passe que les sept colonnes ci-dessus, même
    // convention que flatCount : null tant que ce jour n'est pas rattrapé, 0.0 (pas null) si le
    // jour n'a aucun segment à l'arrêt une fois rattrapé.
    val stoppedHours: Double? = null,
    // RIC-19 : altitude du jour, reparsée depuis rawGpxFilePath au même titre que les colonnes
    // ci-dessus. Contrairement à flatCount, ni l'une ni l'autre ne peut servir de marqueur "pas
    // encore rattrapé" : une altitude de 0 m est une valeur réelle possible (rando en bord de mer),
    // et l'absence d'altitude exploitable dans le GPX est elle aussi un cas réel et définitif : les
    // deux collisionneraient avec "pas encore traité" si l'une d'elles servait de marqueur. D'où
    // elevationBackfilled, un marqueur dédié qui ne porte aucune autre information.
    //
    // maxElevationMeters : altitude max atteinte ce jour-là (record "altitude max atteinte").
    val maxElevationMeters: Double? = null,
    // lastPointElevationMeters : altitude du DERNIER point du jour, convention retenue pour le
    // record "bivouac le plus haut" (RIC-19 §3). Ce n'est un bivouac que si ce jour n'est pas le
    // dernier de sa trace (bivouacCount = dayCount - 1, voir JournalDayInfo) : c'est à l'appelant
    // de l'exclure pour le dernier jour de chaque trace, cette colonne ne fait aucune distinction.
    val lastPointElevationMeters: Double? = null,
    // Marqueur "rattrapage RIC-19 effectué" pour ce jour, indépendant de flatCount ci-dessus (arrivé
    // par une migration antérieure, RIC-109) : une ligne déjà entièrement rattrapée avant RIC-19 a
    // flatCount non nul mais elevationBackfilled à false, et doit repasser une fois par ce nouveau
    // rattrapage. Voir LoggedTrackBackfill.runElevation.
    val elevationBackfilled: Boolean = false,
)
