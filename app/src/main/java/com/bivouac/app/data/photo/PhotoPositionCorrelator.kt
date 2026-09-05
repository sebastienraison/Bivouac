package com.bivouac.app.data.photo

import com.bivouac.app.data.gpx.GeoMath
import com.bivouac.app.data.gpx.TrackGeometry
import com.bivouac.app.data.model.TrackPoint
import kotlin.math.abs

// RIC-43 : où accrocher une photo sur la trace, voir la spec ("Présentation : positionnement sur
// la trace") pour le raisonnement complet. GPS d'abord (position certaine, snap spatial via le
// même TrackGeometry.nearestPointIndex que les marqueurs bivouac) ; à défaut, corrélation par
// horodatage (position approximative) ; à défaut, aucune position : la photo reste accessible
// depuis la galerie plate uniquement, jamais un blocage.
object PhotoPositionCorrelator {

    // "Quelques minutes" (décidé en séance de conception) : l'horloge du téléphone-photo n'est
    // pas garantie synchro avec le GPS.
    //
    // Valeur re-questionnée après la correction de fuseau de PhotoExifReader, et gardée telle
    // quelle : elle avait été calibrée contre un signal biaisé (sans le correctif, une photo sans
    // tag d'offset arrivait décalée d'une heure entière ou plus, donc systématiquement hors
    // tolérance, aucune observation utile n'a pu en sortir). Ce qu'elle doit absorber maintenant
    // est uniquement la dérive d'horloge du téléphone, qui se compte en secondes ou en minutes,
    // pas en heures : 10 min reste large pour ça, et surtout assez étroit pour qu'une photo prise
    // la veille ou après le retour ne s'accroche pas au premier/dernier point de la trace. Reste à
    // confirmer sur de vraies photos en session device ; ajustable sans changer la logique.
    private const val TIME_TOLERANCE_MILLIS = 10 * 60 * 1000L

    /**
     * Pendant spatial de [TIME_TOLERANCE_MILLIS] : au-delà, le GPS de la photo n'est pas retenu et
     * la photo reste sans position, plutôt que d'être accrochée au point de la trace le moins
     * lointain d'une trace dont elle n'a rien à voir.
     *
     * Le cas visé est réel et n'était pas couvert : une photo prise chez soi, sur le trajet en
     * voiture, ou dans une autre sortie du même appareil, porte un GPS parfaitement valide qui
     * n'est simplement pas sur cette trace-là : [TrackGeometry.nearestPointIndex] rend malgré tout
     * un index, et la photo atterrissait sur le premier ou le dernier point sans le moindre signal.
     *
     * 500 m, parce que la borne doit passer largement au-dessus de tout ce qui peut légitimement
     * séparer une photo de la trace : erreur d'un fix GPS dégradé sous couvert forestier ou en
     * fond de vallon (quelques dizaines de mètres, exceptionnellement une centaine), écart entre
     * le sentier et le belvédère d'où la photo est prise, et espacement entre deux points
     * enregistrés (de l'ordre de quelques dizaines de mètres à pied). Et elle doit rester très en
     * dessous de l'échelle à laquelle « ailleurs » commence : un demi-kilomètre, c'est déjà un
     * autre versant. Ajustable sans changer la logique, comme la tolérance temporelle.
     */
    private const val GPS_MAX_DISTANCE_METERS = 500.0

    data class Position(val pointIndex: Int?, val approximate: Boolean) {
        companion object {
            val NONE = Position(pointIndex = null, approximate = false)
        }
    }

    fun correlate(points: List<TrackPoint>, latitude: Double?, longitude: Double?, takenAtMillis: Long?): Position {
        if (points.isEmpty()) return Position.NONE
        if (latitude != null && longitude != null) {
            nearestIndexByDistance(points, latitude, longitude)?.let { return Position(it, approximate = false) }
            // Volontairement pas de repli sur la corrélation temporelle quand le GPS est là mais
            // trop loin : un GPS valide qui tombe à des kilomètres de la trace dit que la photo
            // n'a pas été prise dessus, et l'horodatage ne saurait le contredire.
            return Position.NONE
        }
        if (takenAtMillis != null) {
            nearestIndexByTime(points, takenAtMillis)?.let { return Position(it, approximate = true) }
        }
        return Position.NONE
    }

    // Hors tolérance, aucune position : même règle que [nearestIndexByTime], appliquée à l'espace.
    private fun nearestIndexByDistance(points: List<TrackPoint>, latitude: Double, longitude: Double): Int? {
        val index = TrackGeometry.nearestPointIndex(points, latitude, longitude)
        val point = points[index]
        val distanceMeters = GeoMath.haversineMeters(latitude, longitude, point.latitude, point.longitude)
        return index.takeIf { distanceMeters <= GPS_MAX_DISTANCE_METERS }
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
