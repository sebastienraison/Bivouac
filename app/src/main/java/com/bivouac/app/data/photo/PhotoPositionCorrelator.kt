package com.bivouac.app.data.photo

import com.bivouac.app.data.gpx.TrackGeometry
import com.bivouac.app.data.model.TrackPoint
import kotlin.math.abs

// RIC-43 : où accrocher une photo sur la trace, voir la spec ("Présentation : positionnement sur
// la trace") pour le raisonnement complet. GPS d'abord (position certaine, snap spatial via le
// même TrackGeometry.nearestPointIndex que les marqueurs bivouac) ; à défaut, corrélation par
// horodatage (position approximative) ; à défaut, aucune position — la photo reste accessible
// depuis la galerie plate uniquement, jamais un blocage.
object PhotoPositionCorrelator {

    // "Quelques minutes" (décidé en séance de conception) : l'horloge du téléphone-photo n'est
    // pas garantie synchro avec le GPS. Valeur de départ, ajustable sans changer la logique
    // ci-dessous si l'usage réel montre qu'elle est trop large ou trop stricte.
    private const val TIME_TOLERANCE_MILLIS = 10 * 60 * 1000L

    data class Position(val pointIndex: Int?, val approximate: Boolean) {
        companion object {
            val NONE = Position(pointIndex = null, approximate = false)
        }
    }

    fun correlate(points: List<TrackPoint>, latitude: Double?, longitude: Double?, takenAtMillis: Long?): Position {
        if (points.isEmpty()) return Position.NONE
        if (latitude != null && longitude != null) {
            return Position(TrackGeometry.nearestPointIndex(points, latitude, longitude), approximate = false)
        }
        if (takenAtMillis != null) {
            nearestIndexByTime(points, takenAtMillis)?.let { return Position(it, approximate = true) }
        }
        return Position.NONE
    }

    // Hors tolérance, aucune position n'est retenue plutôt que la plus proche quand même : une
    // photo prise la veille du départ ne doit pas atterrir sur le premier point de la trace.
    private fun nearestIndexByTime(points: List<TrackPoint>, takenAtMillis: Long): Int? {
        var bestIndex: Int? = null
        var bestDiffMillis = Long.MAX_VALUE
        for (i in points.indices) {
            val time = points[i].time ?: continue
            val diffMillis = abs(time.toEpochMilli() - takenAtMillis)
            if (diffMillis < bestDiffMillis) {
                bestDiffMillis = diffMillis
                bestIndex = i
            }
        }
        return bestIndex?.takeIf { bestDiffMillis <= TIME_TOLERANCE_MILLIS }
    }
}
