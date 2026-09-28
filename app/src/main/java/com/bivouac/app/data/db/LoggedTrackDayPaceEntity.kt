package com.bivouac.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import com.bivouac.app.data.gpx.PaceBandSum

// RIC-146 : rythme d'un jour par bande de pente nette (voir PaceBandSum et AnalysisParameters),
// une ligne par jour et par bande non vide. Sert de référence à la vue Analyse : le rythme habituel
// d'une bande est la distance totale divisée par le temps de marche total, sommés sur les randos
// de référence. Même logique que les sommes de calibration de logged_track_day : calculé une fois
// à l'import ou au rattrapage, jamais en re-parsant les GPX du Journal au moment d'analyser.
//
// Une table plutôt que onze fois trois colonnes sur logged_track_day : les bornes des bandes sont
// un paramètre (AnalysisParameters), pas une donnée du schéma. Seuls les segments retenus pour
// l'allure (vitesse en marche de 1 à 8 km/h) y sont comptés ; une bande sans segment n'a pas de
// ligne. La clé primaire commence par dayId, elle sert aussi d'index à la clé étrangère.
@Entity(
    tableName = "logged_track_day_pace",
    primaryKeys = ["dayId", "band"],
    foreignKeys = [
        ForeignKey(
            entity = LoggedTrackDayEntity::class,
            parentColumns = ["id"],
            childColumns = ["dayId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LoggedTrackDayPaceEntity(
    val dayId: Long,
    val band: Int,
    val segmentCount: Int,
    val distanceMeters: Double,
    val movingSeconds: Double,
)

fun PaceBandSum.toEntity(dayId: Long) = LoggedTrackDayPaceEntity(
    dayId = dayId,
    band = band,
    segmentCount = segmentCount,
    distanceMeters = distanceMeters,
    movingSeconds = movingSeconds,
)

// RIC-146 : somme des lignes de logged_track_day_pace par bande, sur un ensemble de randos. Les
// noms de colonnes correspondent aux alias des requêtes de LoggedTrackDao.
data class PaceBandTotal(
    @ColumnInfo(name = "band") val band: Int,
    @ColumnInfo(name = "segmentCount") val segmentCount: Int,
    @ColumnInfo(name = "distanceMeters") val distanceMeters: Double,
    @ColumnInfo(name = "movingSeconds") val movingSeconds: Double,
) {
    fun toPaceBandSum() = PaceBandSum(band, segmentCount, distanceMeters, movingSeconds)
}
