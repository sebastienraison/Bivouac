package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.DayJunctions
import com.bivouac.app.data.model.TrackPoint
import java.time.Duration
import java.time.ZoneId

/**
 * RIC-226 (lot 2 de la 2.7.0, conception RIC-222 §3.4) : ce que les bivouacs détectés dans un jour
 * changent à ses durées et à ses cumuls, quand ce jour contient une ou plusieurs nuits (la rando
 * n'est pas encore passée au crible ; une nuit refusée n'en est plus une, voir RIC-229). Calculs
 * purs, aucune I/O : un seul endroit, utilisé par l'import, le rattrapage, le découpage et l'Analyse.
 *
 * « Bivouacs du jour » = [BivouacDetector.detect] sur les points du jour, moins les arrêts refusés
 * depuis RIC-229 ([effective]).
 *
 * Règle d'ensemble : un jour gardé entier se calcule comme s'il était découpé à ses bivouacs, en
 * morceaux ([pieces]) qui se suivent. La nuit n'est ni de la marche ni une pause (« un bivouac n'est
 * pas une pause ») : elle sort de la durée écoulée, et les pauses comme les tronçons de 200 m se
 * détectent morceau par morceau, si bien qu'aucun ne la traverse. Seule exception, la distance du
 * trou : elle suit la règle des jonctions ([DayJunctions.recordingGaps]), exclue au-delà de 50 m,
 * comptée en deçà ([seriesBreaks]).
 *
 * RIC-229 (lot 7, conception RIC-222 §10, qui remplace le §3.4 pour un fichier gardé en une journée) :
 * une nuit détectée à laquelle l'utilisateur a répondu « Pause » n'est PAS un bivouac. Les bivouacs
 * d'un jour sont désormais ses bivouacs EFFECTIFS ([effective], [nights]) : les nuits détectées moins
 * les arrêts refusés (`logged_track_day.refusedBivouacStops`). Une nuit refusée devient une pause
 * forcée ([pausesAndSegments], paramètre `forcedPauses`) : elle reste dans la durée écoulée, compte
 * dans le temps de pause, jamais dans le temps de marche, et ne coupe ni les morceaux ni la série
 * (la distance de l'arrêt à la reprise est comptée, comme pour toute pause). Tant qu'aucune réponse
 * n'a été donnée, il n'y a aucun refus : la nuit reste un bivouac, comme au lot 2.
 */
object DayBivouacs {

    /**
     * Toutes les nuits détectées de [points] (un jour), au sens de [BivouacDetector], refusées
     * comprises. Seuls l'action de menu « Détecter les bivouacs » (qui repropose les nuits
     * refusées) et les calculs ci-dessous l'appellent directement ; tout le reste passe par
     * [effective] ou [nights].
     */
    fun detect(points: List<TrackPoint>, zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint): List<DetectedBivouac> =
        BivouacDetector.detect(points, zoneFor)

    /**
     * RIC-229 : les nuits détectées d'un jour, partagées en [bivouacs] (effectifs, ce qui coupe le
     * jour) et [refused] (réponse « Pause », comptées comme pauses forcées). Un arrêt refusé qui ne
     * correspond à aucune nuit détectée est ignoré.
     */
    data class DayNights(val bivouacs: List<DetectedBivouac>, val refused: List<DetectedBivouac>)

    /** RIC-229 : [DayNights] de [points], les arrêts de [refusedStops] (index du jour) étant refusés. */
    fun nights(
        points: List<TrackPoint>,
        refusedStops: Set<Int>,
        zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint,
    ): DayNights {
        val detected = detect(points, zoneFor)
        if (refusedStops.isEmpty()) return DayNights(detected, emptyList())
        val (refused, bivouacs) = detected.partition { it.stopIndex in refusedStops }
        return DayNights(bivouacs, refused)
    }

    /**
     * RIC-229 : bivouacs effectifs de [points] : les nuits détectées moins les arrêts refusés
     * ([refusedStops], index du jour). Sans refus, exactement [detect].
     */
    fun effective(
        points: List<TrackPoint>,
        refusedStops: Set<Int>,
        zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint,
    ): List<DetectedBivouac> = nights(points, refusedStops, zoneFor).bivouacs

    /** RIC-229 : les nuits refusées [refused] comme intervalles de pause forcée (arrêt, reprise). */
    fun forcedPauses(refused: List<DetectedBivouac>): List<Pair<Int, Int>> = refused.map { it.stopIndex to it.resumeIndex }

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
     *
     * RIC-229 : [forcedPauses], les nuits refusées ([forcedPauses] de [DayNights.refused]), en index
     * du jour : des pauses quelle que soit la distance, fusionnées avec les pauses détectées. Les
     * tronçons de 200 m qui les traversent les comptent en pause, comme toute pause : la nuit sort
     * de leur temps de marche. Sans pause forcée, rien ne change.
     */
    fun pausesAndSegments(
        points: List<TrackPoint>,
        bivouacs: List<DetectedBivouac>,
        parameters: AnalysisParameters = AnalysisParameters.DEFAULT,
        forcedPauses: List<Pair<Int, Int>> = emptyList(),
    ): PausesAndSegments {
        if (bivouacs.isEmpty()) {
            val pauses = TrackPauseDetector.detect(points, parameters, forcedPauses)
            return PausesAndSegments(pauses, TrackSegmenter.segment(points, pauses = pauses))
        }
        val pauses = mutableListOf<TrackPause>()
        val segments = mutableListOf<TrackSegment>()
        for (piece in pieces(points.size, bivouacs)) {
            val offset = piece.first
            val sub = points.subList(piece.first, piece.last + 1)
            // Une pause forcée est un trou entre deux points consécutifs, jamais un bivouac effectif
            // (les deux listes viennent de la même détection, partagée par DayNights) : elle tombe
            // tout entière dans un seul morceau.
            val subForced = forcedPauses
                .filter { (a, b) -> a >= piece.first && b <= piece.last }
                .map { (a, b) -> (a - offset) to (b - offset) }
            val subPauses = TrackPauseDetector.detect(sub, parameters, subForced)
            pauses += subPauses.map { it.copy(startIndex = it.startIndex + offset, endIndex = it.endIndex + offset) }
            segments += TrackSegmenter.segment(sub, pauses = subPauses)
                .map { it.copy(startIndex = it.startIndex + offset, endIndex = it.endIndex + offset) }
        }
        return PausesAndSegments(pauses, segments)
    }

    /**
     * [DaySegmentSums] d'un jour qui contient [bivouacs] : sommes de calibration, pauses et rythme
     * par pente lus sur [pausesAndSegments]. Sans bivouac ni pause forcée, identique à
     * [DaySegmentSums.of].
     */
    fun segmentSums(
        points: List<TrackPoint>,
        bivouacs: List<DetectedBivouac>,
        parameters: AnalysisParameters = AnalysisParameters.DEFAULT,
        forcedPauses: List<Pair<Int, Int>> = emptyList(),
    ): DaySegmentSums {
        if (bivouacs.isEmpty() && forcedPauses.isEmpty()) return DaySegmentSums.of(points, parameters)
        val found = pausesAndSegments(points, bivouacs, parameters, forcedPauses)
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
 *
 * RIC-229 : [bivouacs] sont les bivouacs EFFECTIFS du jour, ses arrêts refusés ([refusedStops] de
 * [of], index du jour) en étant retirés ; ces nuits refusées ([refusedNights]) sont des pauses
 * forcées : dans la durée écoulée et le temps de pause, hors du temps de marche, sans coupure de
 * série. Sans refus (import, jour pas encore examiné), exactement le calcul du lot 2.
 */
data class JournalDayStats(
    val startedAtMillis: Long?,
    val elapsedSeconds: Long?,
    val sums: DaySegmentSums,
    val stats: TrackStats,
    val maxElevationMeters: Double?,
    val lastPointElevationMeters: Double?,
    val bivouacs: List<DetectedBivouac>,
    val refusedNights: List<DetectedBivouac> = emptyList(),
) {
    companion object {
        fun of(
            points: List<TrackPoint>,
            calibration: SpeedCalibration = SpeedCalibration.DEFAULT,
            zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint,
            refusedStops: Set<Int> = emptySet(),
        ): JournalDayStats {
            val nights = DayBivouacs.nights(points, refusedStops, zoneFor)
            val bivouacs = nights.bivouacs
            return JournalDayStats(
                startedAtMillis = points.firstOrNull()?.time?.toEpochMilli(),
                elapsedSeconds = DayBivouacs.elapsedSeconds(points, bivouacs),
                sums = DayBivouacs.segmentSums(points, bivouacs, forcedPauses = DayBivouacs.forcedPauses(nights.refused)),
                stats = TrackStatsCalculator.compute(points, calibration, DayBivouacs.seriesBreaks(points, bivouacs)),
                maxElevationMeters = points.mapNotNull { it.elevationMeters }.maxOrNull(),
                lastPointElevationMeters = points.lastOrNull()?.elevationMeters,
                bivouacs = bivouacs,
                refusedNights = nights.refused,
            )
        }
    }
}
