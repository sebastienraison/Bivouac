package com.bivouac.app.data.model

import com.bivouac.app.data.gpx.GeoMath

/**
 * RIC-40 : où tomber les points de bivouac quand une trace multi-jours du Journal est dupliquée
 * vers la Planification. Aucune heuristique n'est nécessaire : la coupure entre deux fichiers
 * importés *est* la nuit passée dehors, donc chaque jonction devient un bivouac.
 *
 * L'index retenu est le dernier point du jour qui se termine, et non le premier du jour suivant :
 * c'est la convention que la Planification applique déjà (un point de bivouac est la borne
 * partagée entre deux segments, cf. GpxImportViewModel.computeSegments), donc les segments
 * obtenus correspondent un pour un aux fichiers d'origine.
 */
object DayJunctions {

    /**
     * Les index, dans la trace concaténée, des points de bivouac déduits de [dayPointCounts]
     * (nombre de points de chaque jour, dans l'ordre des jours). Vide pour une trace d'un seul
     * jour. Un jour vide (ne devrait pas arriver, le parseur refuse un GPX sans point) ne produit
     * pas de jonction en double ni d'index négatif.
     */
    fun bivouacTrackPointIndices(dayPointCounts: List<Int>): List<Int> {
        if (dayPointCounts.size < 2) return emptyList()
        return dayPointCounts.runningFold(0) { total, dayPoints -> total + dayPoints }
            .drop(1)
            .dropLast(1)
            .map { it - 1 }
            .filter { it > 0 }
            .distinct()
    }

    /**
     * Au-delà de cet écart entre l'arrivée d'un jour et le départ du lendemain, la jonction est
     * traitée comme un trajet non enregistré : distance exclue des cumuls, tracé en pointillé.
     *
     * En deçà, c'est de la dérive GPS ou un repli de quelques pas au camp, qui ne mérite ni d'être
     * signalé ni d'être retranché.
     */
    const val GAP_THRESHOLD_METERS = 50.0

    /**
     * Les index de [dayBoundaryIndices] (dernier point de chaque jour qui s'achève, voir
     * [bivouacTrackPointIndices]) où l'enregistrement a réellement été interrompu, c'est-à-dire où
     * le lendemain ne repart pas d'où la veille s'est arrêtée.
     *
     * Ces jonctions-là relient deux points sans qu'aucun trajet ait été enregistré entre eux : les
     * compter comme de la distance parcourue ferait mentir le profil altimétrique, dont l'axe se
     * mettrait à annoncer plus de kilomètres que les statistiques de la même vue, celles-ci étant
     * sommées jour par jour.
     */
    fun recordingGaps(points: List<TrackPoint>, dayBoundaryIndices: List<Int>): Set<Int> =
        dayBoundaryIndices.filterTo(mutableSetOf()) { index ->
            val end = points.getOrNull(index)
            val next = points.getOrNull(index + 1)
            end != null && next != null &&
                GeoMath.haversineMeters(end.latitude, end.longitude, next.latitude, next.longitude) >
                GAP_THRESHOLD_METERS
        }
}
