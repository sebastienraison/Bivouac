package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.DayJunctions
import com.bivouac.app.data.model.TrackPoint
import java.time.Duration
import java.time.ZoneId

/**
 * RIC-226 (lot 2 de la 2.7.0, conception RIC-222 §3.4) : ce que les bivouacs détectés dans un jour
 * changent à ses durées et à ses cumuls, quand ce jour contient une ou plusieurs nuits (l'utilisateur
 * a répondu Non au découpage, ou la rando n'est pas encore passée au crible). Calculs purs, aucune
 * I/O : un seul endroit, utilisé par l'import, le rattrapage, le découpage et l'Analyse.
 *
 * « Bivouacs du jour » = [BivouacDetector.detect] sur les points du jour, rien de plus : le crible
 * (qui a répondu quoi) est le lot 3.
 *
 * Règle d'ensemble : un jour gardé entier se calcule comme s'il était découpé à ses bivouacs, en
 * morceaux ([pieces]) qui se suivent. La nuit n'est ni de la marche ni une pause (« un bivouac n'est
 * pas une pause ») : elle sort de la durée écoulée, et les pauses comme les tronçons de 200 m se
 * détectent morceau par morceau, si bien qu'aucun ne la traverse. Seule exception, la distance du
 * trou : elle suit la règle des jonctions ([DayJunctions.recordingGaps]), exclue au-delà de 50 m,
 * comptée en deçà ([seriesBreaks]).
 */
object DayBivouacs {

    /** Bivouacs de [points] (un jour), au sens de [BivouacDetector]. */
    fun detect(points: List<TrackPoint>, zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint): List<DetectedBivouac> =
        BivouacDetector.detect(points, zoneFor)

    /** Durée cumulée des trous de [bivouacs], de l'arrêt à la reprise. */
    fun gapDuration(bivouacs: List<DetectedBivouac>): Duration =
        bivouacs.fold(Duration.ZERO) { total, bivouac -> total.plus(Duration.between(bivouac.stopAt, bivouac.resumeAt)) }

    /** Même chose que [gapDuration], en secondes flottantes (convention de l'Analyse). */
    fun gapSeconds(bivouacs: List<DetectedBivouac>): Double = gapDuration(bivouacs).toMillis() / 1_000.0

    /**
     * Durée écoulée du jour, en secondes : du premier au dernier point, moins la somme des trous de
     * [bivouacs]. `null` quand le premier ou le dernier point n'a pas d'heure, ou que le résultat
     * n'est pas positif : même convention que la colonne `logged_track_day.elapsedSeconds` avant ce
     * ticket (un jour sans horodatage exploitable n'a pas de durée réelle, jamais une durée inventée).
     */
    fun elapsedSeconds(points: List<TrackPoint>, bivouacs: List<DetectedBivouac>): Long? {
        val first = points.firstOrNull()?.time ?: return null
        val last = points.lastOrNull()?.time ?: return null
        return Duration.between(first, last).minus(gapDuration(bivouacs)).seconds.takeIf { it > 0 }
    }

    /**
     * Les morceaux du jour entre ses bivouacs, en index de points (bornes incluses) : `[0, arrêt 1]`,
     * `[reprise 1, arrêt 2]`, ..., `[reprise N, dernier]`. Un seul morceau, le jour entier, sans
     * bivouac. [bivouacs] dans n'importe quel ordre ; ils sont supposés valides (reprise juste après
     * l'arrêt, sans chevauchement), comme [BivouacDetector] les rend.
     */
    fun pieces(pointCount: Int, bivouacs: List<DetectedBivouac>): List<IntRange> {
        if (pointCount <= 0) return emptyList()
        val result = mutableListOf<IntRange>()
        var start = 0
        for (bivouac in bivouacs.sortedBy { it.stopIndex }) {
            result += start..bivouac.stopIndex
            start = bivouac.resumeIndex
        }
        result += start until pointCount
        return result
    }

    /**
     * Coupures de la série commune du jour ([TrackStatsCalculator.series]) dues à ses bivouacs : les
     * arrêts dont la reprise se fait à plus de [DayJunctions.GAP_THRESHOLD_METERS]. Le trou n'y
     * ajoute alors ni distance ni dénivelé ; en deçà, il est compté, comme une jonction de jours en
     * Planification.
     */
    fun seriesBreaks(points: List<TrackPoint>, bivouacs: List<DetectedBivouac>): Set<Int> =
        DayJunctions.recordingGaps(points, bivouacs.map { it.stopIndex })

    /** Pauses et tronçons de 200 m d'un jour, en index du jour. */
    data class PausesAndSegments(val pauses: List<TrackPause>, val segments: List<TrackSegment>)

    /**
     * Pauses ([TrackPauseDetector]) et tronçons ([TrackSegmenter]) de [points], détectés morceau par
     * morceau ([pieces]) puis ramenés aux index du jour : exactement ce que donneraient les jours
     * découpés, et sans bivouac exactement ce que donne la détection sur le jour entier. Aucune
     * pause ni aucun tronçon ne contient la nuit. La définition de la pause ne change pas.
     */
    fun pausesAndSegments(
        points: List<TrackPoint>,
        bivouacs: List<DetectedBivouac>,
        parameters: AnalysisParameters = AnalysisParameters.DEFAULT,
    ): PausesAndSegments {
        if (bivouacs.isEmpty()) {
            val pauses = TrackPauseDetector.detect(points, parameters)
            return PausesAndSegments(pauses, TrackSegmenter.segment(points, pauses = pauses))
        }
        val pauses = mutableListOf<TrackPause>()
        val segments = mutableListOf<TrackSegment>()
        for (piece in pieces(points.size, bivouacs)) {
            val offset = piece.first
            val sub = points.subList(piece.first, piece.last + 1)
            val subPauses = TrackPauseDetector.detect(sub, parameters)
            pauses += subPauses.map { it.copy(startIndex = it.startIndex + offset, endIndex = it.endIndex + offset) }
            segments += TrackSegmenter.segment(sub, pauses = subPauses)
                .map { it.copy(startIndex = it.startIndex + offset, endIndex = it.endIndex + offset) }
        }
        return PausesAndSegments(pauses, segments)
    }

    /**
     * [DaySegmentSums] d'un jour qui contient [bivouacs] : sommes de calibration, pauses et rythme
     * par pente lus sur [pausesAndSegments]. Sans bivouac, identique à [DaySegmentSums.of].
     */
    fun segmentSums(
        points: List<TrackPoint>,
        bivouacs: List<DetectedBivouac>,
        parameters: AnalysisParameters = AnalysisParameters.DEFAULT,
    ): DaySegmentSums {
        if (bivouacs.isEmpty()) return DaySegmentSums.of(points, parameters)
        val found = pausesAndSegments(points, bivouacs, parameters)
        return DaySegmentSums(
            aggregate = DaySegmentAggregate.of(found.segments, parameters),
            pausedSeconds = found.pauses.sumOf { it.seconds },
            paceBands = PaceBandSum.of(found.segments, parameters),
        )
    }
}

/**
 * RIC-226 : tout ce que le Journal range sur une ligne `logged_track_day`, calculé depuis les seuls
 * points du jour. Un seul calcul pour l'import (LoggedTrackRepository.prepareImport), le rattrapage
 * (LoggedTrackBackfill) et le découpage (LoggedTrackRepository.splitDay) : un jour découpé et un
 * jour importé ne peuvent pas diverger.
 *
 * [stats] : distance, D+ et D- du jour sur sa série commune, coupée aux bivouacs dont la reprise est
 * à plus de 50 m ([DayBivouacs.seriesBreaks]) ; durée estimée sous la calibration passée.
 */
data class JournalDayStats(
    val startedAtMillis: Long?,
    val elapsedSeconds: Long?,
    val sums: DaySegmentSums,
    val stats: TrackStats,
    val maxElevationMeters: Double?,
    val lastPointElevationMeters: Double?,
    val bivouacs: List<DetectedBivouac>,
) {
    companion object {
        fun of(
            points: List<TrackPoint>,
            calibration: SpeedCalibration = SpeedCalibration.DEFAULT,
            zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint,
        ): JournalDayStats {
            val bivouacs = DayBivouacs.detect(points, zoneFor)
            return JournalDayStats(
                startedAtMillis = points.firstOrNull()?.time?.toEpochMilli(),
                elapsedSeconds = DayBivouacs.elapsedSeconds(points, bivouacs),
                sums = DayBivouacs.segmentSums(points, bivouacs),
                stats = TrackStatsCalculator.compute(points, calibration, DayBivouacs.seriesBreaks(points, bivouacs)),
                maxElevationMeters = points.mapNotNull { it.elevationMeters }.maxOrNull(),
                lastPointElevationMeters = points.lastOrNull()?.elevationMeters,
                bivouacs = bivouacs,
            )
        }
    }
}
