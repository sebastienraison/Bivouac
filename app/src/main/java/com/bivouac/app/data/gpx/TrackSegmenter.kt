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
//     (via smoothedElevationSeries, fenêtre 5), de sorte que somme(D+ segments) == D+ de la trace :
//     la pénalité calibrée reste à l'échelle du D+ que la prédiction utilisera.
//   - le dénivelé NET du segment (altitude lissée fin - début) sert à la classification plat/pentu,
//     bien plus robuste au bruit capteur que le D+ intégré (CR section 3.3 : le D+ "fantôme" d'un
//     segment réellement plat vaut encore 13 m/km en médiane).
//   - aucun segment n'est écarté au découpage, y compris ceux contenant un long arrêt : ce temps
//     fait partie de la durée que l'estimation doit reproduire. C'est à la classification
//     (DaySegmentAggregate.of) de décider quoi en faire (voir PAUSE_SPEED_KMH).

/** Un segment de trace : distance parcourue à peu près constante, dénivelé et durée réels. */
data class TrackSegment(
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    /** Dénivelé net (altitude lissée fin - début) : bien plus robuste au bruit capteur que le D+. */
    val netElevationMeters: Double,
    val hours: Double,
) {
    val speedKmh: Double get() = if (hours > 0) (distanceMeters / 1000.0) / hours else Double.MAX_VALUE
    val netSlopePercent: Double get() = if (distanceMeters > 0) 100.0 * netElevationMeters / distanceMeters else 0.0
}

object TrackSegmenter {

    const val SEGMENT_LENGTH_METERS = 200.0

    // |pente nette| en deçà de laquelle un segment compte comme plat (CR section 5.2 : le seuil
    // n'est pas critique, 1 à 5 % donnent des résultats équivalents, mais 2 % est très supérieur à
    // l'incertitude de pente sur 200 m — 0,3 % en Garmin, 1,3 % en Geo Tracker — et laisse le D+
    // résiduel des segments retenus rester du bruit).
    const val FLAT_SLOPE_PERCENT = 2.0

    // En deçà, le segment est à l'arrêt (pause, photo, casse-croûte) et ne renseigne pas sur
    // l'allure de marche. Exclusion impérative et non un bonus (CR section 6.1 : sans elle
    // l'estimateur par segments est PIRE que l'ancien calcul par ligne-rando).
    const val PAUSE_SPEED_KMH = 1.0

    private const val KEEP_TAIL_RATIO = 0.5

    /**
     * Découpe [points] en segments d'environ [segmentLengthMeters] de distance parcourue. Le
     * reliquat de fin de trace est conservé s'il atteint la moitié de cette longueur, sinon
     * abandonné (trop court pour que sa pente ait un sens).
     *
     * Seuls les points porteurs à la fois d'une altitude et d'un horodatage sont pris en compte —
     * les deux sont nécessaires (altitude pour le dénivelé, horodatage pour la durée d'un segment),
     * et [TrackStatsCalculator.smoothedElevationSeries] exige justement l'absence de trou
     * d'altitude sur la série qu'on lui passe.
     */
    fun segment(points: List<TrackPoint>, segmentLengthMeters: Double = SEGMENT_LENGTH_METERS): List<TrackSegment> {
        val usable = points.filter { it.elevationMeters != null && it.time != null }
        if (usable.size < 2) return emptyList()
        val smoothed = TrackStatsCalculator.smoothedElevationSeries(usable) ?: return emptyList()

        val segments = mutableListOf<TrackSegment>()
        var startIndex = 0
        var accumulatedDistance = 0.0
        var accumulatedGain = 0.0

        fun close(endIndex: Int) {
            val hours = (usable[endIndex].time!!.toEpochMilli() - usable[startIndex].time!!.toEpochMilli()) / 3_600_000.0
            if (hours > 0) {
                segments += TrackSegment(
                    distanceMeters = accumulatedDistance,
                    elevationGainMeters = accumulatedGain,
                    netElevationMeters = smoothed[endIndex] - smoothed[startIndex],
                    hours = hours,
                )
            }
        }

        for (i in 0 until usable.size - 1) {
            val a = usable[i]
            val b = usable[i + 1]
            accumulatedDistance += GeoMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val delta = smoothed[i + 1] - smoothed[i]
            if (delta > 0) accumulatedGain += delta
            if (accumulatedDistance >= segmentLengthMeters) {
                close(i + 1)
                startIndex = i + 1
                accumulatedDistance = 0.0
                accumulatedGain = 0.0
            }
        }
        if (accumulatedDistance >= KEEP_TAIL_RATIO * segmentLengthMeters) close(usable.lastIndex)
        return segments
    }
}

/**
 * Les seules sommes dont [SpeedCalibrationCalculator] a besoin, par jour de rando (RIC-109 : voir
 * CR_CALIBRATION_SEGMENTS.md section 9). Calculées une fois à l'import et rangées à côté
 * d'`elapsedSeconds` sur `logged_track_day`, elles évitent de re-parser le moindre GPX au moment de
 * calibrer — ce qui préserve le gain de performance obtenu en dénormalisant (RIC-62/98/99). Vérifié
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
) {
    operator fun plus(other: DaySegmentAggregate) = DaySegmentAggregate(
        flatCount = flatCount + other.flatCount,
        flatDistanceMeters = flatDistanceMeters + other.flatDistanceMeters,
        flatHours = flatHours + other.flatHours,
        steepCount = steepCount + other.steepCount,
        steepDistanceMeters = steepDistanceMeters + other.steepDistanceMeters,
        steepGainMeters = steepGainMeters + other.steepGainMeters,
        steepHours = steepHours + other.steepHours,
    )

    companion object {
        val EMPTY = DaySegmentAggregate(0, 0.0, 0.0, 0, 0.0, 0.0, 0.0)

        /**
         * Classe [segments] en plat/pentu selon [TrackSegmenter.FLAT_SLOPE_PERCENT], en écartant
         * des deux catégories les segments à l'arrêt ([TrackSegmenter.PAUSE_SPEED_KMH]) — voir la
         * kdoc de ces constantes pour pourquoi. RIC-129 : l'exclusion portait initialement sur le
         * plat seul ; un arrêt pris en pleine montée gonflait `steepHours` sans y ajouter de D+,
         * ce qui biaisait la pénalité calibrée à la hausse (mesuré sur le Journal réel : jusqu'à
         * 18 % du temps « pentu » cumulé était en fait du temps à l'arrêt). La classification a
         * lieu une seule fois, ici, à l'import ou au rattrapage ; [SpeedCalibrationCalculator] ne
         * voit plus jamais un [TrackSegment] individuel.
         */
        fun of(segments: List<TrackSegment>): DaySegmentAggregate {
            val flat = segments.filter {
                abs(it.netSlopePercent) < TrackSegmenter.FLAT_SLOPE_PERCENT && it.speedKmh >= TrackSegmenter.PAUSE_SPEED_KMH
            }
            val steep = segments.filter {
                abs(it.netSlopePercent) >= TrackSegmenter.FLAT_SLOPE_PERCENT && it.speedKmh >= TrackSegmenter.PAUSE_SPEED_KMH
            }
            return DaySegmentAggregate(
                flatCount = flat.size,
                flatDistanceMeters = flat.sumOf { it.distanceMeters },
                flatHours = flat.sumOf { it.hours },
                steepCount = steep.size,
                steepDistanceMeters = steep.sumOf { it.distanceMeters },
                steepGainMeters = steep.sumOf { it.elevationGainMeters },
                steepHours = steep.sumOf { it.hours },
            )
        }
    }
}
