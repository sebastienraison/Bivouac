package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import kotlin.math.abs

// RIC-109 : portage direct de docs/pilotage/prototype-calibration-segments/segments.py, validé sur
// données réelles (voir CR_CALIBRATION_SEGMENTS.md section 5.1). Choix de conception, repris tels
// quels du prototype :
//   - la fenêtre est fermée sur la DISTANCE parcourue, pas sur un nombre de points ni sur une
//     durée : à nombre de points fixe la longueur varierait avec la vitesse (donc avec la pente,
//     ce qui corrélerait la variable explicative avec l'effet à mesurer) ; à durée fixe c'est pire
//     encore.
//   - le D+ d'un segment est sommé sur les mêmes altitudes lissées que TrackStatsCalculator.compute
//     (RIC-114 : la série commune du jour, TrackStatsCalculator.series), de sorte que
//     somme(D+ segments) == D+ de la trace : la pénalité calibrée reste à l'échelle du D+ que la
//     prédiction utilisera. La distance d'un segment est la distance filtrée de cette même série.
//   - le dénivelé NET du segment (altitude lissée fin - début) sert à la classification plat/pentu,
//     bien plus robuste au bruit capteur que le D+ intégré (CR section 3.3 : le D+ "fantôme" d'un
//     segment réellement plat vaut encore 13 m/km en médiane).
//   - aucun segment n'est écarté au découpage, y compris ceux contenant un long arrêt : ce temps
//     fait partie de la durée que l'estimation doit reproduire. C'est à la classification
//     (DaySegmentAggregate.of) de décider quoi en faire (voir AnalysisParameters).
//
// RIC-146 : le découpage ne change pas, mais chaque segment connaît désormais ses bornes (index des
// points) et la part des pauses fines (TrackPauseDetector) qui tombe entre elles : son temps de
// marche est son temps écoulé moins ces pauses.

/** Un segment de trace : distance parcourue à peu près constante, dénivelé et durée réels. */
data class TrackSegment(
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    /** Dénivelé net (altitude lissée fin - début) : bien plus robuste au bruit capteur que le D+. */
    val netElevationMeters: Double,
    /** Temps écoulé du premier au dernier point du segment, pauses comprises. */
    val hours: Double,
    // RIC-146 : valeurs par défaut pour les segments construits à la main (tests de calibration),
    // qui n'ont ni points ni pauses : un segment sans pause marche pendant tout son temps écoulé.
    /** Index du point qui ouvre le segment dans la liste passée à [TrackSegmenter.segment]. */
    val startIndex: Int = 0,
    /** Index du point qui ferme le segment ; le point suivant ouvre le segment d'après. */
    val endIndex: Int = 0,
    /** Part des pauses du jour comprise entre [startIndex] et [endIndex], en heures. */
    val pausedHours: Double = 0.0,
) {
    val speedKmh: Double get() = if (hours > 0) (distanceMeters / 1000.0) / hours else Double.MAX_VALUE
    val netSlopePercent: Double get() = if (distanceMeters > 0) 100.0 * netElevationMeters / distanceMeters else 0.0

    /** Temps de marche : temps écoulé moins les pauses, jamais négatif. */
    val movingHours: Double get() = (hours - pausedHours).coerceAtLeast(0.0)

    /** Vitesse en marche, ou null si le segment n'a aucun temps de marche. */
    val movingSpeedKmh: Double? get() = if (movingHours > 0) (distanceMeters / 1000.0) / movingHours else null
}

object TrackSegmenter {

    const val SEGMENT_LENGTH_METERS = 200.0

    // |pente nette| en deçà de laquelle un segment compte comme plat (CR section 5.2 : le seuil
    // n'est pas critique, 1 à 5 % donnent des résultats équivalents, mais 2 % est très supérieur à
    // l'incertitude de pente sur 200 m (0,3 % en Garmin, 1,3 % en Geo Tracker) et laisse le D+
    // résiduel des segments retenus rester du bruit).
    const val FLAT_SLOPE_PERCENT = 2.0

    private const val KEEP_TAIL_RATIO = 0.5

    /**
     * Découpe [points] (un jour) en segments d'environ [segmentLengthMeters] de distance parcourue.
     * Le reliquat de fin de trace est conservé s'il atteint la moitié de cette longueur, sinon
     * abandonné (trop court pour que sa pente ait un sens).
     *
     * RIC-114 : tout est lu sur la série commune du jour ([TrackStatsCalculator.series], sans
     * coupure), exactement celle qui donne le D+ du jour : distance filtrée, altitude lissée en
     * distance. Les points sans altitude y portent une altitude interpolée, déjà mêlée à la moyenne
     * de leurs voisins : ils ne sont plus écartés, ce qui revient sur le choix de RIC-138 au profit
     * de l'invariant somme(D+ de tous les segments, reliquat compris) == D+ du jour.
     *
     * Un segment ne s'ouvre et ne se ferme que sur un point horodaté, puisque sa durée en dépend :
     * le seuil de distance atteint, il se ferme au premier point horodaté qui suit. Les points
     * avant le premier ou après le dernier point horodaté ne sont dans aucun segment.
     *
     * RIC-146 : [pauses] sont les pauses du jour ([TrackPauseDetector.detect] sur ces mêmes
     * points) ; l'appelant qui en a aussi besoin ailleurs les passe pour ne pas les détecter deux
     * fois.
     */
    fun segment(
        points: List<TrackPoint>,
        segmentLengthMeters: Double = SEGMENT_LENGTH_METERS,
        parameters: TrackStatsParameters = TrackStatsParameters.DEFAULT,
        pauses: List<TrackPause> = TrackPauseDetector.detect(points),
    ): List<TrackSegment> {
        val firstTimed = points.indexOfFirst { it.time != null }
        val lastTimed = points.indexOfLast { it.time != null }
        if (firstTimed < 0 || lastTimed <= firstTimed) return emptyList()
        val series = TrackStatsCalculator.series(points, parameters = parameters)
        val smoothed = series.smoothedElevationMeters ?: return emptyList()
        val distances = series.cumulativeDistanceMeters

        val segments = mutableListOf<TrackSegment>()
        var startIndex = firstTimed
        var accumulatedDistance = 0.0
        var accumulatedGain = 0.0

        fun close(endIndex: Int) {
            val hours = (points[endIndex].time!!.toEpochMilli() - points[startIndex].time!!.toEpochMilli()) / 3_600_000.0
            if (hours > 0) {
                segments += TrackSegment(
                    distanceMeters = accumulatedDistance,
                    elevationGainMeters = accumulatedGain,
                    netElevationMeters = smoothed[endIndex] - smoothed[startIndex],
                    hours = hours,
                    startIndex = startIndex,
                    endIndex = endIndex,
                    pausedHours = TrackPauseDetector.pausedSecondsBetween(points, pauses, startIndex, endIndex) / 3_600.0,
                )
            }
        }

        for (i in firstTimed until lastTimed) {
            accumulatedDistance += distances[i + 1] - distances[i]
            val delta = smoothed[i + 1] - smoothed[i]
            if (delta > 0) accumulatedGain += delta
            if (accumulatedDistance >= segmentLengthMeters && points[i + 1].time != null) {
                close(i + 1)
                startIndex = i + 1
                accumulatedDistance = 0.0
                accumulatedGain = 0.0
            }
        }
        if (accumulatedDistance >= KEEP_TAIL_RATIO * segmentLengthMeters) close(lastTimed)
        return segments
    }
}

/**
 * Les seules sommes dont [SpeedCalibrationCalculator] a besoin, par jour de rando (RIC-109 : voir
 * CR_CALIBRATION_SEGMENTS.md section 9). Calculées une fois à l'import et rangées à côté
 * d'`elapsedSeconds` sur `logged_track_day`, elles évitent de re-parser le moindre GPX au moment de
 * calibrer, ce qui préserve le gain de performance obtenu en dénormalisant (RIC-62/98/99). Vérifié
 * sur le Journal réel (script `18_aggregates.py` du prototype) : la calibration reconstruite depuis
 * ces sommes est identique au calcul complet sur tous les segments, écart maximal 1,1e-15.
 */
data class DaySegmentAggregate(
    val flatCount: Int,
    val flatDistanceMeters: Double,
    val flatHours: Double,
    val steepCount: Int,
    val steepDistanceMeters: Double,
    val steepGainMeters: Double,
    val steepHours: Double,
    // RIC-115 : heures cumulées à l'arrêt, qui servent à mesurer automatiquement la provision de
    // pause (SpeedCalibrationCalculator). RIC-146 : c'est désormais le temps de pause fin de chaque
    // segment retenu, plus le temps écoulé entier des segments écartés (voir `of`).
    val stoppedHours: Double,
) {
    operator fun plus(other: DaySegmentAggregate) = DaySegmentAggregate(
        flatCount = flatCount + other.flatCount,
        flatDistanceMeters = flatDistanceMeters + other.flatDistanceMeters,
        flatHours = flatHours + other.flatHours,
        steepCount = steepCount + other.steepCount,
        steepDistanceMeters = steepDistanceMeters + other.steepDistanceMeters,
        steepGainMeters = steepGainMeters + other.steepGainMeters,
        steepHours = steepHours + other.steepHours,
        stoppedHours = stoppedHours + other.stoppedHours,
    )

    companion object {
        val EMPTY = DaySegmentAggregate(0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0.0)

        /**
         * Classe [segments] en plat/pentu selon [TrackSegmenter.FLAT_SLOPE_PERCENT], sur leur temps
         * de marche. La classification a lieu une seule fois, ici, à l'import ou au rattrapage ;
         * [SpeedCalibrationCalculator] ne voit plus jamais un [TrackSegment] individuel.
         *
         * RIC-146 : une seule définition de la pause dans toute l'app (voir [AnalysisParameters]),
         * portage de `day_sums` (docs/pilotage/ric-146/reference_lot1.py). Pour chaque segment :
         *   - si son temps de marche est nul, ou sa vitesse en marche hors de
         *     [AnalysisParameters.minMovingSpeedKmh, AnalysisParameters.maxMovingSpeedKmh], il ne
         *     renseigne pas sur l'allure : tout son temps écoulé va dans [stoppedHours] ;
         *   - sinon [stoppedHours] reçoit ses pauses, et [flatHours] ou [steepHours] son temps de
         *     marche.
         * Invariant : flatHours + steepHours + stoppedHours égale la somme des temps écoulés des
         * segments. Avant RIC-146, un segment était à l'arrêt ou en marche tout entier (vitesse
         * écoulée sous 1 km/h) : un arrêt de 3 minutes dans une montée comptait comme de la marche,
         * et gonflait la pénalité D+ calibrée.
         *
         * RIC-129 (inchangé) : l'exclusion porte sur le plat ET le pentu ; un arrêt pris en pleine
         * montée ne doit pas gonfler `steepHours` sans y ajouter de D+.
         */
        fun of(
            segments: List<TrackSegment>,
            parameters: AnalysisParameters = AnalysisParameters.DEFAULT,
        ): DaySegmentAggregate {
            var flatCount = 0
            var flatDistance = 0.0
            var flatHours = 0.0
            var steepCount = 0
            var steepDistance = 0.0
            var steepGain = 0.0
            var steepHours = 0.0
            var stoppedHours = 0.0
            for (segment in segments) {
                if (!segment.isRetainedForPace(parameters)) {
                    stoppedHours += segment.hours
                    continue
                }
                val moving = segment.movingHours
                stoppedHours += segment.hours - moving
                if (abs(segment.netSlopePercent) < TrackSegmenter.FLAT_SLOPE_PERCENT) {
                    flatCount++
                    flatDistance += segment.distanceMeters
                    flatHours += moving
                } else {
                    steepCount++
                    steepDistance += segment.distanceMeters
                    steepGain += segment.elevationGainMeters
                    steepHours += moving
                }
            }
            return DaySegmentAggregate(
                flatCount = flatCount,
                flatDistanceMeters = flatDistance,
                flatHours = flatHours,
                steepCount = steepCount,
                steepDistanceMeters = steepDistance,
                steepGainMeters = steepGain,
                steepHours = steepHours,
                stoppedHours = stoppedHours,
            )
        }
    }
}

/**
 * RIC-146 : un segment ne renseigne sur l'allure que s'il a un temps de marche et une vitesse en
 * marche plausible (voir [AnalysisParameters]). Critère commun aux sommes de calibration et au
 * rythme par bande de pente : les deux doivent écarter exactement les mêmes segments.
 */
fun TrackSegment.isRetainedForPace(parameters: AnalysisParameters = AnalysisParameters.DEFAULT): Boolean {
    val speed = movingSpeedKmh ?: return false
    return speed >= parameters.minMovingSpeedKmh && speed <= parameters.maxMovingSpeedKmh
}

/**
 * RIC-146 : sommes d'un jour (ou d'un ensemble de randos, une fois additionnées) pour une bande de
 * pente nette : de quoi calculer le rythme habituel d'un marcheur sur cette pente (distance totale
 * divisée par temps de marche total, conception section 5.4). Seuls les segments retenus pour
 * l'allure y sont comptés ([isRetainedForPace]).
 *
 * Rangées une fois par jour et par bande dans `logged_track_day_pace`, à l'import et au rattrapage,
 * pour la même raison que [DaySegmentAggregate] : ne jamais re-parser un GPX pour calculer une
 * référence sur tout le Journal.
 */
data class PaceBandSum(
    /** Numéro de bande, de 0 à [AnalysisParameters.slopeBandCount] - 1 (voir [AnalysisParameters.slopeBandOf]). */
    val band: Int,
    val segmentCount: Int,
    val distanceMeters: Double,
    val movingSeconds: Double,
) {
    companion object {
        /** Une ligne par bande non vide, triées par bande : une bande sans segment n'a pas de ligne. */
        fun of(segments: List<TrackSegment>, parameters: AnalysisParameters = AnalysisParameters.DEFAULT): List<PaceBandSum> =
            segments
                .filter { it.isRetainedForPace(parameters) }
                .groupBy { parameters.slopeBandOf(it.netSlopePercent) }
                .map { (band, inBand) ->
                    PaceBandSum(
                        band = band,
                        segmentCount = inBand.size,
                        distanceMeters = inBand.sumOf { it.distanceMeters },
                        movingSeconds = inBand.sumOf { it.movingHours * 3_600.0 },
                    )
                }
                .sortedBy { it.band }
    }
}

/**
 * RIC-146 : tout ce qu'un jour range à côté de ses points, à l'import comme au rattrapage, calculé
 * en une passe : les pauses sont détectées une seule fois et servent à la fois au total du jour et
 * au temps de marche de chaque segment.
 */
data class DaySegmentSums(
    val aggregate: DaySegmentAggregate,
    /** Somme des durées des pauses du jour, en secondes (voir [TrackPauseDetector]). */
    val pausedSeconds: Double,
    val paceBands: List<PaceBandSum>,
) {
    companion object {
        /** Jour sans donnée exploitable (fichier illisible) : des zéros, pas des nuls. */
        val EMPTY = DaySegmentSums(DaySegmentAggregate.EMPTY, 0.0, emptyList())

        fun of(points: List<TrackPoint>, parameters: AnalysisParameters = AnalysisParameters.DEFAULT): DaySegmentSums {
            val pauses = TrackPauseDetector.detect(points, parameters)
            val segments = TrackSegmenter.segment(points, pauses = pauses)
            return DaySegmentSums(
                aggregate = DaySegmentAggregate.of(segments, parameters),
                pausedSeconds = pauses.sumOf { it.seconds },
                paceBands = PaceBandSum.of(segments, parameters),
            )
        }
    }
}
