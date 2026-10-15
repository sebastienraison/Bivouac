package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint

/**
 * RIC-231 : le critère unique « cette trace a-t-elle une altitude exploitable ? ». Tout ce qui
 * dérive de l'altitude (D+, D-, profil, pente, segments) lit ce critère, directement ou par
 * [TrackStats.elevationAvailable] : aucun écran ne refait le sien.
 *
 * Faux dans deux cas :
 * - aucun point n'a de `<ele>` (export de planificateur, par exemple) ;
 * - toutes les altitudes présentes sont identiques (exports qui écrivent 0 partout, ou une seule
 *   valeur répétée) : une série constante ne dit rien du relief, et son D+ de 0 serait un faux.
 *
 * Vrai dès qu'au moins deux altitudes présentes diffèrent. Le cas partiel (quelques points sans
 * `<ele>`, RIC-138) reste donc vrai : ses trous sont interpolés par
 * [TrackStatsCalculator.interpolateElevations]. Une liste vide n'a pas d'altitude : faux.
 */
object ElevationAvailability {
    fun of(points: List<TrackPoint>): Boolean {
        var first: Double? = null
        for (point in points) {
            val elevation = point.elevationMeters ?: continue
            val reference = first
            if (reference == null) first = elevation else if (elevation != reference) return true
        }
        return false
    }
}
