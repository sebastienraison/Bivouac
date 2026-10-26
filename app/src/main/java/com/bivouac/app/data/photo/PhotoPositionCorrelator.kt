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
//
// RIC-214 : un GPS trop loin de la trace n'écarte plus l'horodatage à coup sûr, il le resserre
// (GPS_CONTRADICTED_TIME_TOLERANCE_MILLIS), voir correlate.
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
     * RIC-214 : la tolérance temporelle quand le GPS de la photo existe mais tombe trop loin de la
     * trace (au-delà de [GPS_MAX_DISTANCE_METERS]). Distincte des 10 minutes ci-dessus, et plus
     * étroite : sans GPS, l'heure est le seul indice et 10 minutes absorbent la dérive d'horloge ;
     * avec un GPS qui contredit la trace, l'heure doit en plus tomber PENDANT l'enregistrement, pas
     * à côté. 2 minutes laissent la dérive d'horloge d'un téléphone et refusent la photo prise juste
     * avant le départ ou juste après l'arrivée, qui n'a plus rien pour la rattacher à cette trace.
     *
     * Les deux cas qui cadrent la valeur : une photo dont le GPS est faux de 16 km alors que la trace
     * enregistrait à la même minute (placée), et une photo prise à 2 km de la trace, 27 minutes hors
     * de ses heures (qui reste sans position). Ajustable sans changer la logique.
     */
    private const val GPS_CONTRADICTED_TIME_TOLERANCE_MILLIS = 2 * 60 * 1000L

    /**
     * Pendant spatial de [TIME_TOLERANCE_MILLIS] : au-delà, le GPS de la photo n'est pas retenu, plutôt
     * que d'accrocher la photo au point de la trace le moins lointain d'une trace dont elle n'a
     * peut-être rien à voir. Elle reste sans position, sauf si son heure tombe à
     * [GPS_CONTRADICTED_TIME_TOLERANCE_MILLIS] ou moins d'un point de la trace (RIC-214).
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
            // RIC-214 : un GPS valide qui tombe à des kilomètres de la trace dit que la photo a pu
            // être prise ailleurs (chez soi, sur la route, pendant une autre sortie), d'où l'absence
            // de repli à l'origine. Mais un GPS peut aussi être faux (fix aberrant, métadonnée
            // corrompue) alors que la trace enregistrait à la même minute. Le repli par l'heure est
            // donc rendu, sous une tolérance resserrée (GPS_CONTRADICTED_TIME_TOLERANCE_MILLIS) :
            // l'heure doit tomber pendant l'enregistrement. Le résultat reste approximatif, comme
            // toute position tirée de l'heure. Sans heure, ou hors de cette tolérance : aucune position.
            if (takenAtMillis == null) return Position.NONE
            return nearestIndexByTime(points, takenAtMillis, GPS_CONTRADICTED_TIME_TOLERANCE_MILLIS)
                ?.let { Position(it, approximate = true) }
                ?: Position.NONE
        }
        if (takenAtMillis != null) {
            nearestIndexByTime(points, takenAtMillis, TIME_TOLERANCE_MILLIS)?.let { return Position(it, approximate = true) }
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
    private fun nearestIndexByTime(points: List<TrackPoint>, takenAtMillis: Long, toleranceMillis: Long): Int? {
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
        return bestIndex?.takeIf { bestDiffMillis <= toleranceMillis }
    }
}
