package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint

/**
 * Une pause d'un jour : les points de [startIndex] à [endIndex] inclus, tous deux horodatés.
 * [seconds] est le temps écoulé entre ces deux points.
 */
data class TrackPause(val startIndex: Int, val endIndex: Int, val seconds: Double)

/**
 * RIC-146 : détection fine des pauses sur les points d'un jour, portage fidèle de la fonction
 * `pauses` de docs/pilotage/ric-146/analyse.py. Voir [AnalysisParameters] pour la définition et les
 * mesures qui la justifient.
 *
 * Deux sources, fusionnées :
 *   - une suite de points qui restent à moins de [AnalysisParameters.pauseRadiusMeters] du premier,
 *     sur au moins [AnalysisParameters.pauseMinSeconds] ;
 *   - un trou d'enregistrement d'au moins [AnalysisParameters.pauseMinSeconds] entre deux points
 *     consécutifs distants de moins de [AnalysisParameters.gapMaxMeters] : une montre en pause
 *     automatique n'enregistre rien pendant l'arrêt, la première source ne le verrait pas.
 *
 * Le rayon est mesuré depuis le PREMIER point de la suite, pas de proche en proche : une marche très
 * lente finit toujours par sortir du rayon, alors qu'un seuil entre points consécutifs la prendrait
 * pour un arrêt.
 */
object TrackPauseDetector {

    /** Intervalles triés et fusionnés : deux pauses qui se chevauchent ou se touchent n'en font qu'une. */
    fun detect(points: List<TrackPoint>, parameters: AnalysisParameters = AnalysisParameters.DEFAULT): List<TrackPause> {
        val n = points.size
        val runs = mutableListOf<Pair<Int, Int>>()

        var i = 0
        while (i < n) {
            val start = points[i]
            if (start.time == null) {
                i++
                continue
            }
            var j = i
            while (j + 1 < n && distance(start, points[j + 1]) < parameters.pauseRadiusMeters) j++
            // La suite peut finir sur des points sans heure : la pause se ferme au dernier point
            // horodaté, le seul dont on connaisse l'instant.
            while (j > i && points[j].time == null) j--
            if (j > i && seconds(points[i], points[j]) >= parameters.pauseMinSeconds) {
                runs += i to j
                // j et non j + 1, comme le portage de référence : une pause suivante peut repartir
                // de ce point, et la fusion ci-dessous recolle alors les deux.
                i = j
            } else {
                i++
            }
        }

        for (k in 0 until n - 1) {
            val a = points[k]
            val b = points[k + 1]
            if (a.time != null && b.time != null &&
                seconds(a, b) >= parameters.pauseMinSeconds &&
                distance(a, b) < parameters.gapMaxMeters
            ) {
                runs += k to k + 1
            }
        }

        runs.sortWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        val merged = mutableListOf<Pair<Int, Int>>()
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && run.first <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, run.second)
            } else {
                merged += run
            }
        }
        return merged.map { (a, b) -> TrackPause(a, b, seconds(points[a], points[b])) }
    }

    /**
     * Temps de pause compris entre les points [fromIndex] et [toIndex] (tous deux horodatés), en
     * secondes : la part de chaque pause qui tombe dans l'intervalle. Portage de
     * `paused_seconds_between` : une pause à cheval sur deux tronçons est partagée entre eux au
     * point de coupure, sans jamais être comptée deux fois.
     */
    fun pausedSecondsBetween(points: List<TrackPoint>, pauses: List<TrackPause>, fromIndex: Int, toIndex: Int): Double {
        var total = 0.0
        for (pause in pauses) {
            val lo = maxOf(pause.startIndex, fromIndex)
            val hi = minOf(pause.endIndex, toIndex)
            if (hi > lo) total += seconds(points[lo], points[hi])
        }
        return total
    }

    private fun distance(a: TrackPoint, b: TrackPoint): Double =
        GeoMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    // Millisecondes et non Duration.seconds : un GPX peut porter des fractions de seconde, et le
    // portage de référence travaille en secondes flottantes.
    private fun seconds(a: TrackPoint, b: TrackPoint): Double =
        (b.time!!.toEpochMilli() - a.time!!.toEpochMilli()) / 1000.0
}
