package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import kotlin.math.abs

/**
 * RIC-146 lot 2 : origine de la vitesse de référence d'une bande de pente (conception section 5.4).
 * [REFERENCE] : mesurée sur les randos de référence (au moins [AnalysisParameters.
 * minReferenceSegmentsPerBand] tronçons). [HIKE] : repli sur la rando analysée elle-même, faute
 * d'assez de tronçons dans la référence. [NONE] : ni l'une ni l'autre n'a de tronçon dans cette
 * bande, aucune vitesse de référence.
 */
enum class ReferenceSource { REFERENCE, HIKE, NONE }

/**
 * Un tronçon analysé (conception section 5.2, 5.4, 5.7). [movingSeconds], [movingSpeedKmh],
 * [referenceSpeedKmh], [paceClass] et [speedClass] sont tous `null` pour une [DayAnalysis] issue
 * d'une trace sans horodatage (section 7.5) : il n'y a alors ni temps de marche ni référence à
 * calculer, seule la coloration par pente reste possible.
 *
 * [movingSpeedKmh] (et donc [paceClass]/[speedClass]) reste aussi `null` sur une trace horodatée
 * quand le tronçon n'est PAS retenu pour l'allure ([TrackSegment.isRetainedForPace]) : vitesse en
 * marche hors de [AnalysisParameters.minMovingSpeedKmh]..[AnalysisParameters.maxMovingSpeedKmh], ou
 * temps de marche nul. [referenceSpeedKmh] reste renseigné dans ce cas (l'interpolation ne dépend
 * que de la pente, pas de la retenue du tronçon), sauf si aucune bande n'a la moindre vitesse.
 */
data class AnalyzedSegment(
    val startIndex: Int,
    val endIndex: Int,
    val distanceMeters: Double,
    val netSlopePercent: Double,
    /** Classe de pente (0 à 4) : toujours renseignée, même sans horodatage. */
    val slopeClass: Int,
    val movingSeconds: Double?,
    val movingSpeedKmh: Double?,
    val referenceSpeedKmh: Double?,
    /** Classe d'allure (0 à 4), ou `null` si le tronçon n'est pas retenu ou sans référence. */
    val paceClass: Int?,
    /** Classe de vitesse en marche (0 à 4), ou `null` si le tronçon n'est pas retenu. */
    val speedClass: Int?,
)

/**
 * Une pause analysée (conception section 5.1, 7.3 "liste des pauses") : les mêmes bornes qu'un
 * [TrackPause], plus sa position sur la trace pour la situer sans reparcourir les points.
 */
data class AnalyzedPause(
    val startIndex: Int,
    val endIndex: Int,
    val seconds: Double,
    /** Distance cumulée du jour au début de la pause. */
    val distanceMeters: Double,
    /** Altitude lissée du jour au début de la pause. */
    val elevationMeters: Double,
)

/** Tronçons et pauses d'un jour de la rando analysée. */
data class DayAnalysis(
    val segments: List<AnalyzedSegment>,
    val pauses: List<AnalyzedPause>,
)

/** Totaux de la rando (conception section 5.5, "chiffre de tête"). `null` sans horodatage. */
data class AnalysisTotals(
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elapsedSeconds: Double,
    val pausedSeconds: Double,
    val movingSeconds: Double,
    val pauseCount: Int,
    val movingSpeedKmh: Double,
    /** Vitesse ascensionnelle moyenne sur les tronçons retenus de pente nette >= 10 %, ou `null`
     * si aucun. */
    val averageClimbingSpeedMetersPerHour: Double?,
)

/**
 * Prévision et écart (conception section 5.5). [deltaSeconds] : temps écoulé réel moins
 * [totalSeconds] (positif = plus long que prévu). `null` sans horodatage.
 */
data class AnalysisEstimate(
    val walkingSeconds: Double,
    val pausedSeconds: Double,
    val totalSeconds: Double,
    val deltaSeconds: Double,
)

/**
 * Une des onze bandes de pente nette (conception section 5.4). [referenceSpeedKmh] est `null` ssi
 * [referenceSource] vaut [ReferenceSource.NONE]. [hikeSpeedKmh] est `null` si la rando n'a aucun
 * tronçon retenu dans cette bande (même si [hikeDistanceMeters] vaut alors 0.0).
 */
data class AnalysisBand(
    val band: Int,
    val referenceSpeedKmh: Double?,
    val referenceSource: ReferenceSource,
    val hikeDistanceMeters: Double,
    val hikeSpeedKmh: Double?,
)

/**
 * Meilleurs passages (conception section 5.6). Chaque champ est `null` si la rando n'a aucune
 * suite de tronçons retenus atteignant le temps de marche minimal correspondant.
 */
data class BestPassages(
    val climbMetersPerHour: Double?,
    val flatSpeedKmh: Double?,
)

/**
 * Résultat complet de l'Analyse d'une rando (conception section 5). [totals], [estimate] et
 * [bestPassages] sont `null`, et [bands] est vide, pour une trace sans horodatage (section 7.5) :
 * voir [TrackAnalysisCalculator.compute].
 */
data class TrackAnalysis(
    val days: List<DayAnalysis>,
    val totals: AnalysisTotals?,
    val estimate: AnalysisEstimate?,
    val bands: List<AnalysisBand>,
    val bestPassages: BestPassages?,
)

/**
 * RIC-146 lot 2 : calcul de l'Analyse d'une rando (conception section 5), portage fidèle de
 * `docs/pilotage/ric-146/reference_lot2.py`. Calculateur pur (aucune I/O), sur le modèle de
 * [com.bivouac.app.bilan.BilanStatsCalculator] : reçoit les points de chaque jour, la calibration
 * active et les sommes par bande des randos de référence (déjà exclue de sa propre référence, voir
 * [com.bivouac.app.data.db.LoggedTrackRepository.paceBandSums]), et rend le résultat immuable.
 */
object TrackAnalysisCalculator {

    // Pente nette (valeur absolue) en deçà de laquelle un tronçon compte pour le meilleur passage à
    // plat : même seuil que la classification plat/pentu de la calibration (TrackSegmenter.
    // FLAT_SLOPE_PERCENT), pas un nouveau réglage.
    private val FLAT_SLOPE_PERCENT = TrackSegmenter.FLAT_SLOPE_PERCENT

    // Pente nette à partir de laquelle un tronçon compte pour la vitesse ascensionnelle moyenne des
    // totaux (conception section 5.5) : seuil de la conception, distinct de FLAT_SLOPE_PERCENT (qui
    // sépare plat/pentu) et des bornes de bande (qui séparent les classes de couleur).
    private const val CLIMBING_MIN_SLOPE_PERCENT = 10.0

    private class DayRaw(
        val points: List<TrackPoint>,
        val series: TrackSeries,
        val pauses: List<TrackPause>,
        val segments: List<TrackSegment>,
    )

    /**
     * [pointsByDay] : les points de chaque jour de la rando, dans l'ordre. [referencePaceBands] :
     * sommes par bande des randos de référence, rando analysée déjà exclue (conception section 5.4
     * ; voir [com.bivouac.app.data.db.LoggedTrackRepository.paceBandSums]).
     *
     * RIC-146 section 7.5 "Trace sans horodatage" : si aucun jour ne produit de tronçon via
     * [TrackSegmenter.segment] (aucun horodatage exploitable dans toute la rando : un cas mixte
     * horodaté/non horodaté n'existe pas en pratique, un GPX porte `<time>` partout ou nulle part),
     * chaque jour est redécoupé en tronçons de 200 m sur la seule distance (la série commune du
     * jour, sans toucher à [TrackSegmenter.segment]) pour la seule classe de pente : pas de pause,
     * pas de chiffre de tête, pas de bandes, pas de meilleurs passages.
     */
    fun compute(
        pointsByDay: List<List<TrackPoint>>,
        calibration: SpeedCalibration,
        referencePaceBands: List<PaceBandSum>,
        parameters: AnalysisParameters = AnalysisParameters.DEFAULT,
    ): TrackAnalysis {
        val daysRaw = pointsByDay.map { points ->
            val pauses = TrackPauseDetector.detect(points, parameters)
            DayRaw(
                points = points,
                series = TrackStatsCalculator.series(points),
                pauses = pauses,
                segments = TrackSegmenter.segment(points, pauses = pauses),
            )
        }

        if (daysRaw.all { it.segments.isEmpty() }) {
            return TrackAnalysis(
                days = pointsByDay.map { points -> DayAnalysis(segments = slopeOnlySegments(points, parameters), pauses = emptyList()) },
                totals = null,
                estimate = null,
                bands = emptyList(),
                bestPassages = null,
            )
        }

        val allSegments = daysRaw.flatMap { it.segments }
        val hikePaceBands = PaceBandSum.of(allSegments, parameters)
        val bands = buildBands(referencePaceBands, hikePaceBands, parameters)

        val days = daysRaw.map { day ->
            DayAnalysis(
                segments = day.segments.map { segment -> analyzeSegment(segment, bands, parameters) },
                pauses = day.pauses.map { pause -> analyzePause(pause, day.series) },
            )
        }

        val totals = computeTotals(allSegments, daysRaw, parameters)
        val estimate = computeEstimate(totals, calibration)
        val bestPassages = computeBestPassages(daysRaw.map { it.segments }, parameters)

        return TrackAnalysis(days, totals, estimate, bands, bestPassages)
    }

    // --- Trace sans horodatage (section 7.5) -------------------------------------------------

    private fun slopeOnlySegments(points: List<TrackPoint>, parameters: AnalysisParameters): List<AnalyzedSegment> {
        if (points.size < 2) return emptyList()
        val series = TrackStatsCalculator.series(points)
        val smoothed = series.smoothedElevationMeters ?: return emptyList()
        val distances = series.cumulativeDistanceMeters

        val segments = mutableListOf<AnalyzedSegment>()
        var startIndex = 0
        var accumulatedDistance = 0.0

        fun close(endIndex: Int) {
            val netElevation = smoothed[endIndex] - smoothed[startIndex]
            val slope = if (accumulatedDistance > 0) 100.0 * netElevation / accumulatedDistance else 0.0
            segments += AnalyzedSegment(
                startIndex = startIndex,
                endIndex = endIndex,
                distanceMeters = accumulatedDistance,
                netSlopePercent = slope,
                slopeClass = parameters.slopeClassOf(slope),
                movingSeconds = null,
                movingSpeedKmh = null,
                referenceSpeedKmh = null,
                paceClass = null,
                speedClass = null,
            )
        }

        for (i in 0 until points.lastIndex) {
            accumulatedDistance += distances[i + 1] - distances[i]
            if (accumulatedDistance >= TrackSegmenter.SEGMENT_LENGTH_METERS) {
                close(i + 1)
                startIndex = i + 1
                accumulatedDistance = 0.0
            }
        }
        if (accumulatedDistance >= TAIL_KEEP_RATIO * TrackSegmenter.SEGMENT_LENGTH_METERS) close(points.lastIndex)
        return segments
    }

    // Même convention que TrackSegmenter (KEEP_TAIL_RATIO, privée là-bas) : un reliquat de fin de
    // trace n'est gardé que s'il atteint la moitié de la longueur cible.
    private const val TAIL_KEEP_RATIO = 0.5

    // --- Bandes de référence (section 5.4) ----------------------------------------------------

    private fun buildBands(
        referencePaceBands: List<PaceBandSum>,
        hikePaceBands: List<PaceBandSum>,
        parameters: AnalysisParameters,
    ): List<AnalysisBand> {
        val referenceByBand = referencePaceBands.associateBy { it.band }
        val hikeByBand = hikePaceBands.associateBy { it.band }
        return (0 until parameters.slopeBandCount).map { band ->
            val reference = referenceByBand[band]
            val hike = hikeByBand[band]
            val (speed, source) = when {
                reference != null && reference.segmentCount >= parameters.minReferenceSegmentsPerBand ->
                    speedOf(reference) to ReferenceSource.REFERENCE
                hike != null && hike.segmentCount > 0 ->
                    speedOf(hike) to ReferenceSource.HIKE
                else -> null to ReferenceSource.NONE
            }
            AnalysisBand(
                band = band,
                referenceSpeedKmh = speed,
                referenceSource = source,
                hikeDistanceMeters = hike?.distanceMeters ?: 0.0,
                hikeSpeedKmh = hike?.let { if (it.movingSeconds > 0) speedOf(it) else null },
            )
        }
    }

    private fun speedOf(sum: PaceBandSum): Double = (sum.distanceMeters / 1000.0) / (sum.movingSeconds / 3600.0)

    /**
     * Interpolation linéaire entre les centres des bandes qui ont une vitesse de référence
     * (conception section 5.4) ; hors des extrémités, valeur de l'extrémité. `null` si aucune bande
     * n'a de vitesse.
     */
    private fun interpolatedReferenceSpeedKmh(bands: List<AnalysisBand>, netSlopePercent: Double, parameters: AnalysisParameters): Double? {
        val points = bands.filter { it.referenceSpeedKmh != null }
            .map { parameters.slopeBandCenter(it.band) to it.referenceSpeedKmh!! }
        if (points.isEmpty()) return null
        val first = points.first()
        val last = points.last()
        if (netSlopePercent <= first.first) return first.second
        if (netSlopePercent >= last.first) return last.second
        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            if (netSlopePercent in x0..x1) return y0 + (y1 - y0) * (netSlopePercent - x0) / (x1 - x0)
        }
        return null // inatteignable : first/last couvrent déjà les bords, la boucle le reste.
    }

    // --- Tronçons et pauses --------------------------------------------------------------------

    private fun analyzeSegment(segment: TrackSegment, bands: List<AnalysisBand>, parameters: AnalysisParameters): AnalyzedSegment {
        val retained = segment.isRetainedForPace(parameters)
        val movingSpeed = if (retained) segment.movingSpeedKmh else null
        val referenceSpeed = interpolatedReferenceSpeedKmh(bands, segment.netSlopePercent, parameters)
        val paceClass = if (movingSpeed != null && referenceSpeed != null) {
            parameters.paceClassOf((movingSpeed / referenceSpeed - 1.0) * 100.0)
        } else {
            null
        }
        return AnalyzedSegment(
            startIndex = segment.startIndex,
            endIndex = segment.endIndex,
            distanceMeters = segment.distanceMeters,
            netSlopePercent = segment.netSlopePercent,
            slopeClass = parameters.slopeClassOf(segment.netSlopePercent),
            movingSeconds = segment.movingHours * 3_600.0,
            movingSpeedKmh = movingSpeed,
            referenceSpeedKmh = referenceSpeed,
            paceClass = paceClass,
            speedClass = movingSpeed?.let { parameters.speedClassOf(it) },
        )
    }

    // Sans altitude du tout (aucun point de la trace n'a d'<ele>), il n'y a rien à rapporter comme
    // altitude de pause : 0.0, cas marginal qu'aucune trace réelle du jeu de démo ne rencontre.
    private fun analyzePause(pause: TrackPause, series: TrackSeries): AnalyzedPause = AnalyzedPause(
        startIndex = pause.startIndex,
        endIndex = pause.endIndex,
        seconds = pause.seconds,
        distanceMeters = series.cumulativeDistanceMeters[pause.startIndex],
        elevationMeters = series.smoothedElevationMeters?.get(pause.startIndex) ?: 0.0,
    )

    // --- Totaux et estimation (section 5.5) -----------------------------------------------------

    private fun computeTotals(allSegments: List<TrackSegment>, daysRaw: List<DayRaw>, parameters: AnalysisParameters): AnalysisTotals {
        var distance = 0.0
        var gain = 0.0
        var elapsedSeconds = 0.0
        var pausedSeconds = 0.0
        var pauseCount = 0
        for (day in daysRaw) {
            distance += day.series.cumulativeDistanceMeters.lastOrNull() ?: 0.0
            val smoothed = day.series.smoothedElevationMeters
            if (smoothed != null) {
                for (i in 0 until smoothed.size - 1) {
                    val delta = smoothed[i + 1] - smoothed[i]
                    if (delta > 0) gain += delta
                }
            }
            // Écoulé du jour = premier au dernier point horodaté : une nuit entre deux jours n'est
            // jamais comptée, chaque jour porte son propre écoulé (voir aussi paused/pauseCount).
            val timed = day.points.mapNotNull { it.time }
            if (timed.size >= 2) elapsedSeconds += (timed.last().toEpochMilli() - timed.first().toEpochMilli()) / 1_000.0
            pausedSeconds += day.pauses.sumOf { it.seconds }
            pauseCount += day.pauses.size
        }
        val movingSeconds = (elapsedSeconds - pausedSeconds).coerceAtLeast(0.0)
        val movingSpeedKmh = if (movingSeconds > 0) (distance / 1000.0) / (movingSeconds / 3_600.0) else 0.0

        val climbing = allSegments.filter { it.isRetainedForPace(parameters) && it.netSlopePercent >= CLIMBING_MIN_SLOPE_PERCENT }
        val climbingMovingHours = climbing.sumOf { it.movingHours }
        val averageClimbingSpeed = if (climbing.isNotEmpty() && climbingMovingHours > 0) {
            climbing.sumOf { it.netElevationMeters } / climbingMovingHours
        } else {
            null
        }

        return AnalysisTotals(
            distanceMeters = distance,
            elevationGainMeters = gain,
            elapsedSeconds = elapsedSeconds,
            pausedSeconds = pausedSeconds,
            movingSeconds = movingSeconds,
            pauseCount = pauseCount,
            movingSpeedKmh = movingSpeedKmh,
            averageClimbingSpeedMetersPerHour = averageClimbingSpeed,
        )
    }

    // Réutilise TrackStatsCalculator.walkingMinutes/applyPauseProvision tels quels (consigne du
    // lot) : le prédicteur reste la formule existante, l'Analyse ne fait que l'appliquer à la
    // distance/D+ réels de la rando pour situer l'écart (conception section 4 et 5.5).
    private fun computeEstimate(totals: AnalysisTotals, calibration: SpeedCalibration): AnalysisEstimate {
        val walkingMinutes = TrackStatsCalculator.walkingMinutes(totals.distanceMeters, totals.elevationGainMeters, calibration)
        val walkingSeconds = walkingMinutes * 60.0
        val totalSeconds = TrackStatsCalculator.applyPauseProvision(walkingMinutes, calibration.pauseFractionPercent) * 60.0
        return AnalysisEstimate(
            walkingSeconds = walkingSeconds,
            pausedSeconds = totalSeconds - walkingSeconds,
            totalSeconds = totalSeconds,
            deltaSeconds = totals.elapsedSeconds - totalSeconds,
        )
    }

    // --- Meilleurs passages (section 5.6) -------------------------------------------------------

    private fun computeBestPassages(segmentsByDay: List<List<TrackSegment>>, parameters: AnalysisParameters): BestPassages {
        var bestClimb: Double? = null
        var bestFlat: Double? = null
        for (segments in segmentsByDay) { // un passage ne franchit jamais une nuit
            for (start in segments.indices) {
                var climbMovingSeconds = 0.0
                var climbNetElevation = 0.0
                for (segment in segments.subList(start, segments.size)) {
                    if (!segment.isRetainedForPace(parameters)) break
                    climbMovingSeconds += segment.movingHours * 3_600.0
                    climbNetElevation += segment.netElevationMeters
                    if (climbMovingSeconds >= parameters.bestClimbMinMovingSeconds) {
                        val speed = climbNetElevation / (climbMovingSeconds / 3_600.0)
                        if (bestClimb == null || speed > bestClimb!!) bestClimb = speed
                        break
                    }
                }
                var flatMovingSeconds = 0.0
                var flatDistance = 0.0
                for (segment in segments.subList(start, segments.size)) {
                    if (!segment.isRetainedForPace(parameters) || abs(segment.netSlopePercent) >= FLAT_SLOPE_PERCENT) break
                    flatMovingSeconds += segment.movingHours * 3_600.0
                    flatDistance += segment.distanceMeters
                    if (flatMovingSeconds >= parameters.bestFlatMinMovingSeconds) {
                        val speed = (flatDistance / 1000.0) / (flatMovingSeconds / 3_600.0)
                        if (bestFlat == null || speed > bestFlat!!) bestFlat = speed
                        break
                    }
                }
            }
        }
        return BestPassages(climbMetersPerHour = bestClimb, flatSpeedKmh = bestFlat)
    }
}
