package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import kotlin.math.roundToInt

/**
 * Nature d'une phase de la frise (RIC-146, conception section 3.1, règles 3 à 5). [ROLLING]
 * ("vallonné") n'apparaît jamais au groupement des tronçons de 200 m : seule la relecture de la
 * pente d'ensemble d'une phase (règle 5) peut y transformer un [FLAT].
 */
enum class TimelinePhaseKind { CLIMB, DESCENT, ROLLING, FLAT }

/** Élément ordonné de la frise d'un jour : une phase de marche ou une pause d'événement. */
sealed interface TimelineElement {
    val startSeconds: Double
    val endSeconds: Double
}

/**
 * Une phase de marche (conception section 3.3). [movingSpeedKmh] est `null` si la phase n'a aucun
 * temps de marche. [paceDeltaPercent] est `null` si aucun de ses tronçons de 200 m retenus n'a de
 * vitesse de référence (voir [TrackTimelineCalculator], section "écart").
 */
data class TimelinePhase(
    val kind: TimelinePhaseKind,
    val startIndex: Int,
    val endIndex: Int,
    override val startSeconds: Double,
    override val endSeconds: Double,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    /** Durée de la phase moins les pauses courtes (< [AnalysisParameters.pauseEventSeconds]) qu'elle contient. */
    val movingSeconds: Double,
    val movingSpeedKmh: Double?,
    /** Écart en % au rythme habituel, arrondi ; `null` sans référence (voir la kdoc de la classe). */
    val paceDeltaPercent: Int?,
) : TimelineElement

/**
 * Une pause d'événement (conception section 3.1 règle 1, 3.3), éventuellement la réunion de
 * plusieurs pauses fines proches (règle 2, [mergedCount] > 1).
 */
data class TimelinePause(
    val startIndex: Int,
    val endIndex: Int,
    override val startSeconds: Double,
    override val endSeconds: Double,
    val pausedSeconds: Double,
    val startElevationMeters: Double,
    /** Nombre de pauses fines réunies dans ce bloc (1 si aucune réunion). */
    val mergedCount: Int,
) : TimelineElement

/** Une pause trop courte pour couper le trait de la frise (conception section 3.3, 5.4). */
data class ShortTimelinePause(
    val startSeconds: Double,
    val pausedSeconds: Double,
    val distanceMeters: Double,
)

/**
 * Déroulé d'un jour (conception section 3.2) : [elements] se suivent sans trou ni chevauchement,
 * du départ (temps 0) à l'arrivée ([elapsedSeconds]) : c'est l'invariant que
 * [TrackTimelineCalculatorTest] vérifie explicitement.
 */
data class DayTimeline(
    val elements: List<TimelineElement>,
    val shortPauses: List<ShortTimelinePause>,
    val elapsedSeconds: Double,
    val distanceMeters: Double,
    val startElevationMeters: Double,
    val endElevationMeters: Double,
)

/** Résumé par nature de terrain (conception section 4, "Forme du jour") : montées, plat, descentes. */
data class TerrainSummary(
    val kind: TimelinePhaseKind,
    val distanceMeters: Double,
    val movingSeconds: Double,
    val movingSpeedKmh: Double?,
    val referenceSpeedKmh: Double?,
)

/** Les chiffres de la conception section 4, toujours calculables (même sans horodatage). */
data class TrackFigures(
    val highestElevationMeters: Double?,
    val lowestElevationMeters: Double?,
    /** Plus forte pente nette d'un tronçon de 200 m ; jamais celle d'un seul point. */
    val steepestClimbPercent: Double?,
    val steepestDescentPercent: Double?,
)

/**
 * Résultat complet du lot 6 (conception section 3 et 4). [days] et [walkingSharePercent] sont
 * `null`, et [terrainSummary] est vide, pour une trace sans horodatage (section 7.5 de
 * [TrackAnalysisCalculator]) : [figures] reste renseigné, altitude et pentes ne demandant aucune
 * horloge.
 */
data class TrackTimeline(
    val days: List<DayTimeline>?,
    val terrainSummary: List<TerrainSummary>,
    val walkingSharePercent: Int?,
    val figures: TrackFigures,
)

/**
 * RIC-146 lot 6 : découpage d'une rando en phases pour la frise chronologique de la vue Analyse
 * (conception section 3), portage fidèle de `docs/pilotage/ric-146/banc_frise.py` (fonction
 * `phases`) et de `docs/pilotage/ric-146/reference_lot6.py` (résumé par nature, chiffres).
 *
 * Calculateur pur : ne détecte ni pauses ni tronçons de 200 m, et n'interpole aucune vitesse de
 * référence lui-même : tout ça vient de [TrackAnalysisCalculator.compute] (même [points], mêmes
 * [AnalysisParameters]), passé en [analysis]. Un tronçon est "retenu" pour le rythme exactement
 * quand [AnalyzedSegment.movingSpeedKmh] n'est pas `null` (voir [TrackSegment.isRetainedForPace]) :
 * ce calculateur ne relit jamais la vitesse en marche brute d'un tronçon pour recalculer cette
 * retenue lui-même.
 *
 * Seule donnée que ce lot lit en dehors de [analysis] : la série commune du jour ([TrackSeries],
 * [TrackStatsCalculator.series]) pour la distance cumulée et l'altitude lissée à des index
 * arbitraires (bornes de pause, de tronçon retenu à moitié...) : la même série que
 * [TrackSegmenter] et [TrackAnalysisCalculator] utilisent déjà pour découper et analyser, jamais
 * une nouvelle distance ou un nouveau lissage.
 */
object TrackTimelineCalculator {

    /** Pauses fines déjà réunies en un seul événement (règle 2), pendant la construction. */
    private class MergingPause(var startIndex: Int, var endIndex: Int, var pausedSeconds: Double, var mergedCount: Int)

    /** Un groupe de tronçons de 200 m consécutifs de même nature (règle 3), avant fusion des courts. */
    private class SlopeGroup(val kind: TimelinePhaseKind, val segments: MutableList<AnalyzedSegment>) {
        fun lengthMeters(): Double = segments.sumOf { it.distanceMeters }
    }

    /**
     * [pointsByDay] : les points de chaque jour, dans le même ordre que ceux passés à
     * [TrackAnalysisCalculator.compute] pour produire [analysis]. [analysis] doit avoir été
     * calculé avec les mêmes [parameters] (mêmes bandes de référence, même seuil de retenue).
     */
    fun compute(
        pointsByDay: List<List<TrackPoint>>,
        analysis: TrackAnalysis,
        parameters: AnalysisParameters = AnalysisParameters.DEFAULT,
    ): TrackTimeline {
        val allSmoothedElevations = mutableListOf<Double>()
        val allSlopePercents = mutableListOf<Double>()
        for (points in pointsByDay) {
            val series = TrackStatsCalculator.series(points)
            series.smoothedElevationMeters?.let { allSmoothedElevations += it.toList() }
        }
        for (day in analysis.days) {
            for (segment in day.segments) {
                if (segment.distanceMeters > 0) allSlopePercents += segment.netSlopePercent
            }
        }
        val figures = TrackFigures(
            highestElevationMeters = allSmoothedElevations.maxOrNull(),
            lowestElevationMeters = allSmoothedElevations.minOrNull(),
            steepestClimbPercent = allSlopePercents.maxOrNull(),
            steepestDescentPercent = allSlopePercents.minOrNull()?.let { -it },
        )

        val totals = analysis.totals
        if (totals == null) {
            // Section 7.5 : trace sans horodatage, pas de déroulé ni de résumé possibles.
            return TrackTimeline(days = null, terrainSummary = emptyList(), walkingSharePercent = null, figures = figures)
        }

        val days = pointsByDay.mapIndexed { index, points -> computeDayTimeline(points, analysis.days[index], parameters) }
        val walkingSharePercent = if (totals.elapsedSeconds > 0) {
            (100.0 * (totals.elapsedSeconds - totals.pausedSeconds) / totals.elapsedSeconds).roundToInt()
        } else {
            null
        }
        val terrainSummary = computeTerrainSummary(analysis, parameters)
        return TrackTimeline(days, terrainSummary, walkingSharePercent, figures)
    }

    // --- Résumé par nature (conception section 4) -----------------------------------------------

    private fun computeTerrainSummary(analysis: TrackAnalysis, parameters: AnalysisParameters): List<TerrainSummary> {
        // Même seuil, même partition que slopeKindOf (jamais de nature vallonnée ici : le résumé
        // par nature reste tronçon par tronçon, la relecture de phase entière ne s'y applique pas).
        val kept = analysis.days.flatMap { it.segments }
            .filter { it.movingSpeedKmh != null }
            .groupBy { slopeKindOf(it.netSlopePercent, parameters) }
        return listOf(TimelinePhaseKind.CLIMB, TimelinePhaseKind.FLAT, TimelinePhaseKind.DESCENT).map { kind ->
            val segments = kept[kind].orEmpty()
            val distance = segments.sumOf { it.distanceMeters }
            val moving = segments.sumOf { it.movingSeconds ?: 0.0 }
            // Temps de référence : uniquement les tronçons dont l'interpolation a une vitesse ;
            // le numérateur reste la distance de TOUTE la catégorie, fidèle à reference_lot6.py.
            val referenceSeconds = segments.sumOf { s ->
                s.referenceSpeedKmh?.let { 3_600.0 * (s.distanceMeters / 1000.0) / it } ?: 0.0
            }
            TerrainSummary(
                kind = kind,
                distanceMeters = distance,
                movingSeconds = moving,
                movingSpeedKmh = if (moving > 0) (distance / 1000.0) / (moving / 3_600.0) else null,
                referenceSpeedKmh = if (referenceSeconds > 0) (distance / 1000.0) / (referenceSeconds / 3_600.0) else null,
            )
        }
    }

    // --- Déroulé d'un jour (conception section 3) ------------------------------------------------

    private fun computeDayTimeline(points: List<TrackPoint>, day: DayAnalysis, parameters: AnalysisParameters): DayTimeline {
        val timedIndices = points.indices.filter { points[it].time != null }
        if (timedIndices.size < 2 || day.segments.isEmpty()) {
            // Un jour sans au moins deux points horodatés, ou sans le moindre tronçon de 200 m :
            // aucune frise possible pour ce jour (portage de `phases()` qui rend None dans ce cas).
            // N'arrive pas en pratique une fois analysis.totals non nul (voir kdoc de compute) :
            // traité ici pour ne jamais planter sur un GPX inattendu plutôt que pour un cas réel.
            return DayTimeline(emptyList(), emptyList(), 0.0, 0.0, 0.0, 0.0)
        }
        val startIndex = timedIndices.first()
        val endIndex = timedIndices.last()

        val series = TrackStatsCalculator.series(points)
        val cumulativeDistance = series.cumulativeDistanceMeters
        val smoothedElevation = series.smoothedElevationMeters!! // non-null : des points horodatés ont forcément une heure, pas forcément une altitude, mais TrackSegmenter n'aurait alors produit aucun segment (day.segments non vide écarte ce cas)

        // tt[i] : secondes écoulées depuis le premier point horodaté du jour, en reportant la
        // dernière heure connue sur les points sans heure propre (portage exact de `phases()`).
        val elapsedSinceStart = DoubleArray(points.size)
        var lastKnownMillis = points[startIndex].time!!.toEpochMilli()
        for (i in points.indices) {
            points[i].time?.let { lastKnownMillis = it.toEpochMilli() }
            elapsedSinceStart[i] = (lastKnownMillis - points[startIndex].time!!.toEpochMilli()) / 1_000.0
        }

        // Règle 1 et 2 : pauses d'événement (>= pauseEventSeconds), réunies si moins de
        // minWalkBetweenPausesMeters de marche les sépare, puis rattachées au bord si le premier
        // ou dernier tronçon de marche du jour est lui-même trop court.
        val merged = mutableListOf<MergingPause>()
        for (pause in day.pauses) {
            if (pause.seconds < parameters.pauseEventSeconds) continue
            val last = merged.lastOrNull()
            if (last != null && cumulativeDistance[pause.startIndex] - cumulativeDistance[last.endIndex] < parameters.minWalkBetweenPausesMeters) {
                last.endIndex = pause.endIndex
                last.pausedSeconds += pause.seconds
                last.mergedCount += 1
            } else {
                merged += MergingPause(pause.startIndex, pause.endIndex, pause.seconds, 1)
            }
        }
        if (merged.isNotEmpty() && cumulativeDistance[merged.first().startIndex] - cumulativeDistance[startIndex] < parameters.minWalkBetweenPausesMeters) {
            merged.first().startIndex = startIndex
        }
        if (merged.isNotEmpty() && cumulativeDistance[endIndex] - cumulativeDistance[merged.last().endIndex] < parameters.minWalkBetweenPausesMeters) {
            merged.last().endIndex = endIndex
        }

        // Tronçons de marche entre les pauses d'événement (ce qui reste de [startIndex, endIndex]
        // une fois les blocs de pause retirés).
        val stretches = mutableListOf<Pair<Int, Int>>()
        var cursor = startIndex
        for (pause in merged) {
            if (pause.startIndex > cursor) stretches += cursor to pause.startIndex
            cursor = pause.endIndex
        }
        if (cursor < endIndex) stretches += cursor to endIndex

        val phaseItems = mutableListOf<TimelinePhase>()
        for ((a, b) in stretches) {
            phaseItems += phasesOfStretch(a, b, day.segments, cumulativeDistance, smoothedElevation, elapsedSinceStart, day.pauses, parameters)
        }
        val pauseItems = merged.map { m ->
            TimelinePause(
                startIndex = m.startIndex,
                endIndex = m.endIndex,
                startSeconds = elapsedSinceStart[m.startIndex],
                endSeconds = elapsedSinceStart[m.endIndex],
                pausedSeconds = m.pausedSeconds,
                startElevationMeters = smoothedElevation[m.startIndex],
                mergedCount = m.mergedCount,
            )
        }
        val elements: List<TimelineElement> = buildList<TimelineElement> {
            addAll(phaseItems)
            addAll(pauseItems)
        }.sortedWith(compareBy({ it.startSeconds }, { it.endSeconds }))

        val shortPauses = day.pauses
            .filter { it.seconds < parameters.pauseEventSeconds }
            .filterNot { short -> merged.any { it.startIndex <= short.startIndex && short.endIndex <= it.endIndex } }
            .map { ShortTimelinePause(elapsedSinceStart[it.startIndex], it.seconds, cumulativeDistance[it.startIndex]) }

        return DayTimeline(
            elements = elements,
            shortPauses = shortPauses,
            elapsedSeconds = elapsedSinceStart[endIndex],
            distanceMeters = cumulativeDistance[endIndex],
            startElevationMeters = smoothedElevation[startIndex],
            endElevationMeters = smoothedElevation[endIndex],
        )
    }

    /** Nature d'un tronçon ou d'une phase à la pente [slopePercent] (règles 3 et 4, jamais [TimelinePhaseKind.ROLLING] ici). */
    private fun slopeKindOf(slopePercent: Double, parameters: AnalysisParameters): TimelinePhaseKind = when {
        slopePercent >= parameters.phaseSlopePercent -> TimelinePhaseKind.CLIMB
        slopePercent <= -parameters.phaseSlopePercent -> TimelinePhaseKind.DESCENT
        else -> TimelinePhaseKind.FLAT
    }

    /** Les phases d'un seul tronçon de marche [a, b] (entre deux pauses d'événement, ou un bord du jour). */
    private fun phasesOfStretch(
        a: Int,
        b: Int,
        segments: List<AnalyzedSegment>,
        cumulativeDistance: DoubleArray,
        smoothedElevation: DoubleArray,
        elapsedSinceStart: DoubleArray,
        pauses: List<AnalyzedPause>,
        parameters: AnalysisParameters,
    ): List<TimelinePhase> {
        fun overlapMeters(segment: AnalyzedSegment): Double {
            val lo = maxOf(cumulativeDistance[segment.startIndex], cumulativeDistance[a])
            val hi = minOf(cumulativeDistance[segment.endIndex], cumulativeDistance[b])
            return maxOf(0.0, hi - lo)
        }
        // Règle 3 : un tronçon de 200 m appartient au tronçon de marche qui en contient au moins
        // la moitié de la distance.
        val mine = segments.filter { it.distanceMeters > 0 && overlapMeters(it) >= 0.5 * it.distanceMeters }

        // Groupe les tronçons retenus par nature consécutive, puis fond les groupes trop courts
        // dans leur voisin le plus long (ou les deux voisins s'ils partagent la même nature).
        val groups = mutableListOf<SlopeGroup>()
        for (segment in mine) {
            val kind = slopeKindOf(segment.netSlopePercent, parameters)
            val last = groups.lastOrNull()
            if (last != null && last.kind == kind) last.segments += segment else groups += SlopeGroup(kind, mutableListOf(segment))
        }
        while (groups.size > 1) {
            var shortestIndex = 0
            var shortestLength = groups[0].lengthMeters()
            for (i in 1 until groups.size) {
                val length = groups[i].lengthMeters()
                if (length < shortestLength) {
                    shortestIndex = i
                    shortestLength = length
                }
            }
            if (shortestLength >= parameters.minPhaseMeters) break
            val left = groups.getOrNull(shortestIndex - 1)
            val right = groups.getOrNull(shortestIndex + 1)
            when {
                left != null && right != null && left.kind == right.kind -> {
                    left.segments += groups[shortestIndex].segments
                    left.segments += right.segments
                    groups.removeAt(shortestIndex + 1)
                    groups.removeAt(shortestIndex)
                }
                right == null || (left != null && left.lengthMeters() >= right.lengthMeters()) -> {
                    left!!.segments += groups[shortestIndex].segments
                    groups.removeAt(shortestIndex)
                }
                else -> {
                    right.segments.addAll(0, groups[shortestIndex].segments)
                    groups.removeAt(shortestIndex)
                }
            }
        }

        // Bornes des groupes : la fin de chaque groupe est la fin de son dernier tronçon ; sans
        // aucun tronçon retenu pour ce tronçon de marche, il reste une seule phase [a, b] vide.
        data class Bound(val start: Int, val end: Int, val segments: List<AnalyzedSegment>)
        val bounds = if (groups.isEmpty()) {
            listOf(Bound(a, b, emptyList()))
        } else {
            val cuts = mutableListOf(a)
            for (group in groups.dropLast(1)) cuts += group.segments.last().endIndex.coerceIn(a, b)
            cuts += b
            val cutBounds = (groups.indices).mapNotNull { k -> if (cuts[k + 1] > cuts[k]) Bound(cuts[k], cuts[k + 1], groups[k].segments) else null }
            // Dégénère si toutes les bornes se recouvrent (n'arrive pas en pratique) : repli sur un
            // bloc unique [a, b], comme le portage de référence ("if not bounds: bounds = [[a,b,mine]]").
            cutBounds.ifEmpty { listOf(Bound(a, b, mine)) }
        }

        // Règle 4 : nature relue sur la pente d'ensemble de chaque borne, puis fusion des voisines
        // de même nature (deux groupes peuvent finir de même nature une fois relus sur la pente
        // nette d'ensemble, même s'ils différaient tronçon par tronçon).
        data class Phase(var start: Int, var end: Int, val segments: MutableList<AnalyzedSegment>, val kind: TimelinePhaseKind)
        val rereadPhases = mutableListOf<Phase>()
        for (bound in bounds) {
            val distance = cumulativeDistance[bound.end] - cumulativeDistance[bound.start]
            val kind = if (distance > 0) {
                slopeKindOf(100.0 * (smoothedElevation[bound.end] - smoothedElevation[bound.start]) / distance, parameters)
            } else {
                TimelinePhaseKind.FLAT
            }
            val last = rereadPhases.lastOrNull()
            if (last != null && last.kind == kind) {
                last.end = bound.end
                last.segments += bound.segments
            } else {
                rereadPhases += Phase(bound.start, bound.end, bound.segments.toMutableList(), kind)
            }
        }

        return rereadPhases.map { phase -> buildPhase(phase.start, phase.end, phase.kind, phase.segments, cumulativeDistance, smoothedElevation, elapsedSinceStart, pauses, parameters) }
    }

    private fun buildPhase(
        startIndex: Int,
        endIndex: Int,
        preliminaryKind: TimelinePhaseKind,
        segments: List<AnalyzedSegment>,
        cumulativeDistance: DoubleArray,
        smoothedElevation: DoubleArray,
        elapsedSinceStart: DoubleArray,
        pauses: List<AnalyzedPause>,
        parameters: AnalysisParameters,
    ): TimelinePhase {
        val distance = cumulativeDistance[endIndex] - cumulativeDistance[startIndex]
        val elapsed = elapsedSinceStart[endIndex] - elapsedSinceStart[startIndex]
        // Temps de marche affiché : durée de la phase moins les pauses COURTES (< pauseEventSeconds)
        // qu'elle contient ; les pauses d'événement ne peuvent pas tomber dans une phase (règle 1).
        val pausedShort = pauses.filter { it.seconds < parameters.pauseEventSeconds }.sumOf { pause ->
            val lo = maxOf(elapsedSinceStart[pause.startIndex], elapsedSinceStart[startIndex])
            val hi = minOf(elapsedSinceStart[pause.endIndex], elapsedSinceStart[endIndex])
            maxOf(0.0, hi - lo)
        }
        val movingSeconds = maxOf(elapsed - pausedShort, 0.0)

        // Écart au rythme habituel : uniquement sur les tronçons de 200 m RETENUS de la phase
        // (mouvingSpeedKmh non nul) qui ont une vitesse de référence, temps de référence contre
        // leur propre temps de marche (portage exact de banc_frise.py, pas le mv ci-dessus).
        var referenceSeconds = 0.0
        var retainedMovingSeconds = 0.0
        for (segment in segments) {
            val movingSpeed = segment.movingSpeedKmh ?: continue
            val referenceSpeed = segment.referenceSpeedKmh ?: continue
            referenceSeconds += 3_600.0 * (segment.distanceMeters / 1000.0) / referenceSpeed
            retainedMovingSeconds += segment.movingSeconds ?: (segment.distanceMeters / 1000.0 / movingSpeed * 3_600.0)
        }
        val paceDeltaPercent = if (retainedMovingSeconds > 0 && referenceSeconds > 0) {
            (100.0 * (referenceSeconds / retainedMovingSeconds - 1.0)).roundToInt()
        } else {
            null
        }

        var gain = 0.0
        var loss = 0.0
        for (i in startIndex until endIndex) {
            val delta = smoothedElevation[i + 1] - smoothedElevation[i]
            if (delta > 0) gain += delta else loss += -delta
        }

        // Règle 5 : une phase sans pente nette (plate) au dénivelé cumulé élevé est vallonnée.
        val kind = if (preliminaryKind == TimelinePhaseKind.FLAT && distance > 0 &&
            (gain + loss) / (distance / 1000.0) >= parameters.rollingThresholdMetersPerKm
        ) {
            TimelinePhaseKind.ROLLING
        } else {
            preliminaryKind
        }

        return TimelinePhase(
            kind = kind,
            startIndex = startIndex,
            endIndex = endIndex,
            startSeconds = elapsedSinceStart[startIndex],
            endSeconds = elapsedSinceStart[endIndex],
            distanceMeters = distance,
            elevationGainMeters = gain,
            elevationLossMeters = loss,
            movingSeconds = movingSeconds,
            movingSpeedKmh = if (movingSeconds > 0) (distance / 1000.0) / (movingSeconds / 3_600.0) else null,
            paceDeltaPercent = paceDeltaPercent,
        )
    }
}
