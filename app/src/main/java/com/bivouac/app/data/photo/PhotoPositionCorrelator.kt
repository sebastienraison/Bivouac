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
    // pas garantie synchro avec le GPS.
    //
    // Valeur re-questionnée après la correction de fuseau de PhotoExifReader, et gardée telle
    // quelle : elle avait été calibrée contre un signal biaisé (sans le correctif, une photo sans
    // tag d'offset arrivait décalée d'une heure entière ou plus, donc systématiquement hors
    // tolérance — aucune observation utile n'a pu en sortir). Ce qu'elle doit absorber maintenant
    // est uniquement la dérive d'horloge du téléphone, qui se compte en secondes ou en minutes,
    // pas en heures : 10 min reste large pour ça, et surtout assez étroit pour qu'une photo prise
    // la veille ou après le retour ne s'accroche pas au premier/dernier point de la trace. Reste à
    // confirmer sur de vraies photos en session device ; ajustable sans changer la logique.
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
