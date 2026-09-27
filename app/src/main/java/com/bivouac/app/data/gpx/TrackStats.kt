package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import kotlin.math.roundToInt

data class TrackStats(
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val estimatedDurationMinutes: Int,
)

/**
 * Les trois leviers derrière l'estimation de durée (BIV-16 "Vitesse personnalisée") : vitesse à
 * plat, correction D+ façon Naismith, et provision de pause (RIC-115), tous réglables à la main en
 * mode Manuel ou calculés depuis l'historique du Journal en mode Auto/Sélection (voir
 * [com.bivouac.app.data.gpx.SpeedCalibrationCalculator]).
 */
data class SpeedCalibration(
    val walkingSpeedKmh: Double,
    val elevationGainPenaltyMetersPerKm: Double,
    // RIC-115 : part du temps passée à l'arrêt (0-100), convertie en majoration du temps de marche
    // via P / (1 - P) : voir TrackStatsCalculator.applyPauseProvision. 0.0 par défaut : valeur
    // neutre obligatoire, pour qu'un utilisateur qui n'a jamais touché ce réglage voie un
    // comportement strictement inchangé par rapport à avant ce ticket.
    val pauseFractionPercent: Double = 0.0,
) {
    companion object {
        val DEFAULT = SpeedCalibration(
            walkingSpeedKmh = 3.5,
            elevationGainPenaltyMetersPerKm = 100.0,
            pauseFractionPercent = 0.0,
        )
    }
}

/**
 * RIC-114 : la série commune d'une trace, construite une seule fois par
 * [TrackStatsCalculator.series] et lue par tout ce qui affiche une distance ou un dénivelé :
 * statistiques, segments de Planification, segmenteur de calibration, profil altimétrique, bulle
 * du curseur, rayon des photos. Deux écrans qui lisent la même série ne peuvent pas diverger.
 *
 * Même taille que la liste de points d'origine : aucun point n'est jamais retiré, les index
 * (bivouacs, photos, curseur, bornes de segment) restent valides tels quels.
 *
 * [breaks] : `i` dans [breaks] signifie que le lien entre les points `i` et `i + 1` n'est pas du
 * parcours (jonction de jours, coupure d'enregistrement) : il n'ajoute ni distance ni dénivelé, et
 * la moyenne glissante ne le traverse pas.
 */
class TrackSeries internal constructor(
    /** Distance filtrée (seuil de déplacement minimal) cumulée depuis le premier point. */
    val cumulativeDistanceMeters: DoubleArray,
    /** Altitude interpolée puis lissée sur la distance ; null si aucun point n'a d'altitude. */
    val smoothedElevationMeters: DoubleArray?,
    val breaks: Set<Int>,
) {
    val size: Int get() = cumulativeDistanceMeters.size

    /**
     * Distance, D+ et D- de la plage d'index [from, to] (bornes incluses, `from <= to`). Les
     * deltas d'altitude se partitionnent et la distance se télescope : deux plages contiguës
     * ([a, b] puis [b, c]) somment exactement, au flottant près, les chiffres de [a, c].
     */
    fun statsBetween(from: Int, to: Int, calibration: SpeedCalibration = SpeedCalibration.DEFAULT): TrackStats {
        require(from in 0..to && to < size) { "plage [$from, $to] hors de la série ($size points)" }
        val distance = cumulativeDistanceMeters[to] - cumulativeDistanceMeters[from]
        var gain = 0.0
        var loss = 0.0
        val elevations = smoothedElevationMeters
        if (elevations != null) {
            for (i in from until to) {
                if (i in breaks) continue
                val delta = elevations[i + 1] - elevations[i]
                if (delta > 0) gain += delta else loss += -delta
            }
        }
        return TrackStatsCalculator.statsWithDuration(distance, gain, loss, calibration)
    }
}

object TrackStatsCalculator {

    // RIC-115 : défense en profondeur : l'IHM borne le curseur à 35 %, mais une valeur DataStore
    // corrompue ou une migration future ne doivent jamais pouvoir amener le dénominateur de
    // applyPauseProvision à <= 0.
    private const val MAX_SAFE_PAUSE_FRACTION_PERCENT = 90.0

    /**
     * Statistiques de toute la trace, lues sur sa série commune (voir [series]) : distance filtrée,
     * D+ et D- sur l'altitude lissée en distance. [breaks] : coupures de la série, vides pour un
     * jour du Journal calculé seul ; en Planification,
     * [com.bivouac.app.data.model.DayJunctions.planificationSeriesBreaks].
     */
    fun compute(
        points: List<TrackPoint>,
        calibration: SpeedCalibration = SpeedCalibration.DEFAULT,
        breaks: Set<Int> = emptySet(),
        parameters: TrackStatsParameters = TrackStatsParameters.DEFAULT,
    ): TrackStats {
        if (points.isEmpty()) return statsWithDuration(0.0, 0.0, 0.0, calibration)
        return series(points, breaks, parameters).statsBetween(0, points.lastIndex, calibration)
    }

    internal fun statsWithDuration(distance: Double, gain: Double, loss: Double, calibration: SpeedCalibration): TrackStats {
        val durationMinutes = applyPauseProvision(walkingMinutes(distance, gain, calibration), calibration.pauseFractionPercent).roundToInt()
        return TrackStats(
            distanceMeters = distance,
            elevationGainMeters = gain,
            elevationLossMeters = loss,
            estimatedDurationMinutes = durationMinutes,
        )
    }

    /**
     * RIC-114 : construit la série commune de [points] (voir [TrackSeries]), le seul endroit de
     * l'app où une distance parcourue et une altitude lissée sont calculées. Paramètres et
     * justification des valeurs : [TrackStatsParameters].
     *
     * Distance filtrée : un point n'est cumulé que s'il est à plus de
     * [TrackStatsParameters.minMoveMeters] (comparaison stricte) du dernier point RETENU, pas du
     * point précédent : une dérive lente finit ainsi par être cumulée d'un bloc. Un point ignoré
     * reste dans la série avec la distance de son ancre ; son altitude participe à la moyenne.
     * Tous les points d'une pause partagent donc la même distance, se moyennent entre eux, et la
     * série lissée reste constante pendant la pause : aucun D+ fantôme. Aucun horodatage n'est lu,
     * ce qui vaut aussi pour les traces planifiées.
     *
     * Altitude : [interpolateElevations] (trous comblés par index, RIC-138), puis, pour chaque
     * point, moyenne arithmétique des altitudes des points du même bloc (plage sans coupure) dont
     * la distance filtrée est à au plus la demi-fenêtre de la sienne, bornes incluses. La fenêtre
     * se tronque d'elle-même aux bords d'un bloc.
     *
     * Comportements qui en découlent :
     * - liste vide : distance 0, série vide ; un seul point : série = son altitude ;
     * - deux points à plus d'une demi-fenêtre : pas de lissage, D+ = différence brute ;
     * - trace entièrement sur place (tous les points sous le seuil) : distance 0, série constante
     *   égale à la moyenne globale, D+ = D- = 0 exactement ;
     * - points espacés de plus d'une demi-fenêtre (planificateur) : la fenêtre ne contient que le
     *   point lui-même, le D+ est brut ;
     * - sommet pointu : écrêté d'environ pente x demi-fenêtre / 2 de chaque côté ; un sommet plat
     *   d'au moins une fenêtre est conservé exactement ;
     * - points sans heure : sans effet.
     */
    fun series(
        points: List<TrackPoint>,
        breaks: Set<Int> = emptySet(),
        parameters: TrackStatsParameters = TrackStatsParameters.DEFAULT,
    ): TrackSeries {
        val n = points.size
        val effectiveBreaks = breaks.filterTo(mutableSetOf()) { it in 0 until n - 1 }
        val cumulative = DoubleArray(n)
        // Distance cumulée depuis le début du bloc courant : c'est sur elle que la fenêtre est
        // mesurée, pour que la série d'un bloc soit exactement celle que donnerait le même bloc
        // calculé seul (un jour du Journal dans la trace concaténée, par exemple).
        val blockLocal = DoubleArray(n)
        var anchor = 0
        for (i in 1 until n) {
            if (i - 1 in effectiveBreaks) {
                cumulative[i] = cumulative[i - 1]
                blockLocal[i] = 0.0
                anchor = i
                continue
            }
            val a = points[anchor]
            val b = points[i]
            val step = GeoMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            if (step > parameters.minMoveMeters) {
                cumulative[i] = cumulative[i - 1] + step
                blockLocal[i] = blockLocal[i - 1] + step
                anchor = i
            } else {
                cumulative[i] = cumulative[i - 1]
                blockLocal[i] = blockLocal[i - 1]
            }
        }
        val smoothed = interpolateElevations(points)?.let { elevations ->
            smoothOverDistance(elevations, blockLocal, effectiveBreaks, parameters.elevationWindowMeters / 2.0)
        }
        return TrackSeries(cumulative, smoothed, effectiveBreaks)
    }

    // Deux pointeurs et une somme courante par bloc : O(n). La fenêtre [lo, hi) ne fait que
    // glisser vers l'avant, puisque la distance est croissante au sens large dans un bloc.
    private fun smoothOverDistance(
        elevations: List<Double>,
        distances: DoubleArray,
        breaks: Set<Int>,
        halfWindow: Double,
    ): DoubleArray {
        val n = elevations.size
        val result = DoubleArray(n)
        var blockStart = 0
        while (blockStart < n) {
            var blockEnd = blockStart
            while (blockEnd < n - 1 && blockEnd !in breaks) blockEnd++
            var lo = blockStart
            var hi = blockStart
            var sum = 0.0
            for (i in blockStart..blockEnd) {
                while (hi <= blockEnd && distances[hi] <= distances[i] + halfWindow) {
                    sum += elevations[hi]
                    hi++
                }
                while (distances[lo] < distances[i] - halfWindow) {
                    sum -= elevations[lo]
                    lo++
                }
                result[i] = sum / (hi - lo)
            }
            blockStart = blockEnd + 1
        }
        return result
    }

    /**
     * Re-derives just the duration of an already-computed [TrackStats] under a different
     * [calibration]: distance/elevation are physical facts of the track and don't change, but
     * [TrackStats.estimatedDurationMinutes] does whenever the active calibration does. Used to
     * keep list rows (banked traces, Journal) showing a duration consistent with the current
     * Réglages calibration without re-parsing every trace's GPX just to redraw a list.
     */
    fun recomputeDuration(stats: TrackStats, calibration: SpeedCalibration): TrackStats {
        val durationMinutes = applyPauseProvision(
            walkingMinutes(stats.distanceMeters, stats.elevationGainMeters, calibration),
            calibration.pauseFractionPercent,
        ).roundToInt()
        return stats.copy(estimatedDurationMinutes = durationMinutes)
    }

    /**
     * Minutes de marche pure (hors provision de pause) pour une distance et un D+ donnés, sous
     * [calibration]. Exposé (pas seulement interne à [compute]/[recomputeDuration]) pour les
     * aperçus IHM qui n'ont pas de [TrackPoint] réels : Réglages, aperçu illustratif de l'effet du
     * D+ sur une rando type (RIC-115).
     */
    fun walkingMinutes(distanceMeters: Double, elevationGainMeters: Double, calibration: SpeedCalibration): Double {
        val equivalentDistanceKm = distanceMeters / 1000.0 + elevationGainMeters / calibration.elevationGainPenaltyMetersPerKm
        return equivalentDistanceKm / calibration.walkingSpeedKmh * 60
    }

    /**
     * RIC-115 : convertit des minutes de marche pure en minutes totales (marche + pause), à partir
     * d'un pourcentage "temps passé à l'arrêt" (voir [SpeedCalibration.pauseFractionPercent]). La
     * conversion P -> majoration est P / (1 - P) : si P % du temps total est passé à l'arrêt, le
     * temps de marche restant (1 - P) doit être multiplié par 1 / (1 - P) pour reconstituer le
     * temps total. Coercée à [MAX_SAFE_PAUSE_FRACTION_PERCENT] avant division, même si l'IHM borne
     * déjà le curseur à 35 % : défense en profondeur contre une valeur DataStore corrompue.
     */
    fun applyPauseProvision(walkingMinutes: Double, pauseFractionPercent: Double): Double {
        val fraction = pauseFractionPercent.coerceIn(0.0, MAX_SAFE_PAUSE_FRACTION_PERCENT) / 100.0
        return walkingMinutes / (1.0 - fraction)
    }

    /**
     * Smoothed elevation for each point, index-aligned with [points] (for charting, where a
     * marker needs to land on the exact index a [com.bivouac.app.data.model.BivouacPoint]
     * refers to). RIC-114 : c'est la série lissée de [series], mêmes coupures, mêmes paramètres ;
     * un appelant qui a aussi besoin de la distance lit directement [series].
     *
     * RIC-138 : un point sans `<ele>` (un export GPX réel en a parfois quelques-uns épars, pas
     * forcément le fichier entier) obtient une altitude interpolée linéairement entre ses plus
     * proches voisins connus, plutôt que de faire échouer toute la série ; compacter la liste
     * casserait justement cet alignement d'index. Un trou en tout début ou toute fin de trace
     * (aucun voisin connu d'un côté) est étendu à plat depuis le point connu le plus proche : il
     * n'y a rien vers quoi interpoler de ce côté-là, et une extension plate est la supposition la
     * moins arbitraire. Ne retourne null que si AUCUN point de la trace n'a d'altitude : il n'y a
     * alors rien du tout à partir de quoi interpoler, et le profil reste vide comme avant RIC-138.
     */
    fun smoothedElevationSeries(
        points: List<TrackPoint>,
        breaks: Set<Int> = emptySet(),
        parameters: TrackStatsParameters = TrackStatsParameters.DEFAULT,
    ): List<Double>? = series(points, breaks, parameters).smoothedElevationMeters?.toList()

    // internal (pas private) pour un test unitaire direct de l'interpolation, indépendant de la
    // fenêtre de lissage (voir SmoothedElevationSeriesTest).
    internal fun interpolateElevations(points: List<TrackPoint>): List<Double>? {
        if (points.isEmpty()) return emptyList()
        if (points.none { it.elevationMeters != null }) return null

        val result = DoubleArray(points.size)
        var previousKnownIndex = -1
        var previousKnownValue = 0.0
        var i = 0
        while (i < points.size) {
            val elevation = points[i].elevationMeters
            if (elevation != null) {
                result[i] = elevation
                previousKnownIndex = i
                previousKnownValue = elevation
                i++
                continue
            }
            // Étendue du trou courant : de i (inclus) à j (exclu), j étant soit le prochain point
            // connu, soit la fin de la trace.
            var j = i
            while (j < points.size && points[j].elevationMeters == null) j++
            val nextKnownIndex = j.takeIf { it < points.size }
            val nextKnownValue = nextKnownIndex?.let { points[it].elevationMeters!! }
            for (k in i until j) {
                result[k] = when {
                    previousKnownIndex < 0 -> nextKnownValue!! // trou en tout début de trace
                    nextKnownIndex == null -> previousKnownValue // trou en toute fin de trace
                    else -> {
                        val t = (k - previousKnownIndex).toDouble() / (nextKnownIndex - previousKnownIndex)
                        previousKnownValue + t * (nextKnownValue!! - previousKnownValue)
                    }
                }
            }
            i = j
        }
        return result.toList()
    }
}
