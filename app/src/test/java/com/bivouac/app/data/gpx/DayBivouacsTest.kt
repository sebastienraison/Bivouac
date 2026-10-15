package com.bivouac.app.data.gpx

import com.bivouac.app.data.db.LoggedTrackDayEntity
import com.bivouac.app.data.model.DayJunctions
import com.bivouac.app.data.model.TrackPoint
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-226 (lot 2 de la 2.7.0) : ce qu'une nuit au bivouac à l'intérieur d'un jour change aux durées
 * et aux cumuls ([DayBivouacs], [JournalDayStats]), et la cohérence entre le Journal, l'Analyse et la
 * frise. Règle verrouillée ici (conception RIC-222 §3.4, cadrage du lot 2 §3.3) : un jour gardé
 * entier se calcule comme s'il était découpé à ses bivouacs ; la nuit sort de la durée écoulée, n'est
 * pas une pause, et sa distance n'est comptée qu'en deçà de 50 m.
 *
 * Trace : celle de NightInSingleFileReproductionTest (7 h de marche à 4 km/h, nuit de 17 h, 7 h de
 * marche), reprise à 141 m ou à 40 m du dernier point. Fuseau imposé (Europe/Paris).
 */
class DayBivouacsTest {

    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)
    private val paris: (TrackPoint) -> ZoneId = { ZoneId.of("Europe/Paris") }

    private class Built(val points: List<TrackPoint>, val stopIndices: List<Int>)

    /** [halfDays] demi-journées de marche séparées par des nuits de [NIGHT_HOURS] h reprises à [resumeGapMeters]. */
    private fun build(resumeGapMeters: Double, halfDays: Int = 2): Built {
        val stepsPerHalfDay = (HALF_DAY_HOURS * 3_600 / STEP_SECONDS).toInt()
        val points = mutableListOf<TrackPoint>()
        val stops = mutableListOf<Int>()
        var north = 0.0
        var time = START
        fun add() {
            points += TrackPoint(
                latitude = 45.0 + north * degPerMeter,
                longitude = 6.0,
                elevationMeters = 1_000.0 + 40.0 * sin(north / 500.0),
                time = time,
            )
        }
        add()
        repeat(halfDays) { half ->
            if (half > 0) {
                stops += points.lastIndex
                north += resumeGapMeters
                time = time.plus(Duration.ofHours(NIGHT_HOURS))
                add()
            }
            repeat(stepsPerHalfDay) {
                north += STEP_METERS
                time = time.plusSeconds(STEP_SECONDS)
                add()
            }
        }
        return Built(points, stops)
    }

    // --- Durée écoulée ---------------------------------------------------------------------------

    @Test
    fun elapsedWithoutBivouacIsLastMinusFirst() {
        val points = build(141.0, halfDays = 1).points
        assertTrue(DayBivouacs.detect(points, paris).isEmpty())
        assertEquals(HALF_DAY_SECONDS, DayBivouacs.elapsedSeconds(points, emptyList()))
    }

    @Test
    fun elapsedSubtractsEveryBivouacGap() {
        val built = build(141.0, halfDays = 3)
        val bivouacs = DayBivouacs.detect(built.points, paris)
        assertEquals(built.stopIndices, bivouacs.map { it.stopIndex })
        assertEquals(Duration.ofHours(2 * NIGHT_HOURS), DayBivouacs.gapDuration(bivouacs))
        assertEquals(2.0 * NIGHT_HOURS * 3_600.0, DayBivouacs.gapSeconds(bivouacs), 0.0)
        assertEquals(3 * HALF_DAY_SECONDS, DayBivouacs.elapsedSeconds(built.points, bivouacs))
    }

    @Test
    fun elapsedIsNullWithoutTimestampsAtBothEnds() {
        val points = build(141.0).points
        val bivouacs = DayBivouacs.detect(points, paris)
        val untimedFirst = listOf(points.first().copy(time = null)) + points.drop(1)
        assertNull(DayBivouacs.elapsedSeconds(untimedFirst, bivouacs))
        assertNull(DayBivouacs.elapsedSeconds(emptyList(), emptyList()))
    }

    @Test
    fun piecesCoverTheDayBetweenBivouacs() {
        val built = build(141.0, halfDays = 3)
        val bivouacs = DayBivouacs.detect(built.points, paris)
        val (s1, s2) = built.stopIndices
        assertEquals(listOf(0..s1, (s1 + 1)..s2, (s2 + 1)..built.points.lastIndex), DayBivouacs.pieces(built.points.size, bivouacs))
        assertEquals(listOf(0..9), DayBivouacs.pieces(10, emptyList()))
        assertTrue(DayBivouacs.pieces(0, emptyList()).isEmpty())
    }

    // --- Pauses, distance : la nuit n'est pas une pause, le trou suit la règle des 50 m -----------

    @Test
    fun theNightIsNeitherElapsedNorPausedWhateverTheResumeDistance() {
        val far = JournalDayStats.of(build(141.0).points, zoneFor = paris)
        val near = JournalDayStats.of(build(40.0).points, zoneFor = paris)
        for (figures in listOf(far, near)) {
            assertEquals(1, figures.bivouacs.size)
            assertEquals(2 * HALF_DAY_SECONDS, figures.elapsedSeconds)
            // Marche continue de part et d'autre : aucune pause, ni à 141 m ni à 40 m, où la
            // détection sur le jour entier voyait une pause de 17 h.
            assertEquals(0.0, figures.sums.pausedSeconds, 0.0)
            val real = RealDurationCalculator.forDays(listOf(dayRow(figures)))!!
            assertEquals(real.elapsedSeconds, real.walkingSeconds)
        }
        assertTrue("à 40 m, l'ancienne détection voyait la nuit en pause", TrackPauseDetector.detect(build(40.0).points).any { it.seconds >= NIGHT_HOURS * 3_600.0 })
    }

    @Test
    fun theResumeJumpIsExcludedBeyond50MetersAndCountedBelow() {
        val far = build(141.0)
        val near = build(40.0)
        val farBivouacs = DayBivouacs.detect(far.points, paris)
        val nearBivouacs = DayBivouacs.detect(near.points, paris)
        assertEquals(far.stopIndices.toSet(), DayBivouacs.seriesBreaks(far.points, farBivouacs))
        assertTrue(DayBivouacs.seriesBreaks(near.points, nearBivouacs).isEmpty())

        val farDistance = JournalDayStats.of(far.points, zoneFor = paris).stats.distanceMeters
        val farWithJump = TrackStatsCalculator.compute(far.points).distanceMeters
        assertEquals("le saut de 141 m est exclu", 141.0, farWithJump - farDistance, 0.5)
        val nearDistance = JournalDayStats.of(near.points, zoneFor = paris).stats.distanceMeters
        assertEquals("le saut de 40 m est compté", TrackStatsCalculator.compute(near.points).distanceMeters, nearDistance, 0.0)
    }

    // --- Un jour gardé entier = ce même jour découpé ----------------------------------------------

    @Test
    fun aDayKeptWholeHasTheFiguresOfTheSameDaySplitInTwo() {
        val built = build(141.0)
        val stop = built.stopIndices.single()
        val whole = JournalDayStats.of(built.points, zoneFor = paris)
        val first = JournalDayStats.of(built.points.subList(0, stop + 1), zoneFor = paris)
        val second = JournalDayStats.of(built.points.subList(stop + 1, built.points.size), zoneFor = paris)

        assertEquals(first.elapsedSeconds!! + second.elapsedSeconds!!, whole.elapsedSeconds)
        assertEquals(first.sums.pausedSeconds + second.sums.pausedSeconds, whole.sums.pausedSeconds, 1e-9)
        val parts = first.sums.aggregate + second.sums.aggregate
        assertEquals(parts.flatCount, whole.sums.aggregate.flatCount)
        assertEquals(parts.steepCount, whole.sums.aggregate.steepCount)
        assertEquals(parts.flatHours, whole.sums.aggregate.flatHours, 1e-9)
        assertEquals(parts.steepHours, whole.sums.aggregate.steepHours, 1e-9)
        assertEquals("la nuit n'est pas dans les heures à l'arrêt", parts.stoppedHours, whole.sums.aggregate.stoppedHours, 1e-9)
        assertTrue(whole.sums.aggregate.stoppedHours < 1.0)
        assertEquals(
            first.sums.paceBands.sumOf { it.segmentCount } + second.sums.paceBands.sumOf { it.segmentCount },
            whole.sums.paceBands.sumOf { it.segmentCount },
        )
        // Distance : au-delà de 50 m le trou n'est compté nulle part, comme entre deux fichiers.
        assertEquals(first.stats.distanceMeters + second.stats.distanceMeters, whole.stats.distanceMeters, 1e-6)
        assertEquals(first.stats.elevationGainMeters + second.stats.elevationGainMeters, whole.stats.elevationGainMeters, 1e-6)
    }

    @Test
    fun withoutBivouacTheSumsAreExactlyTheWholeDaySums() {
        val points = build(141.0, halfDays = 1).points
        assertEquals(DaySegmentSums.of(points), DayBivouacs.segmentSums(points, emptyList()))
        assertEquals(DaySegmentSums.of(points), JournalDayStats.of(points, zoneFor = paris).sums)
    }

    // --- Analyse et frise : mêmes chiffres que le Journal ----------------------------------------

    @Test
    fun analysisAndTimelineAgreeWithTheJournal() {
        for (gap in listOf(141.0, 40.0)) {
            val points = build(gap).points
            val figures = JournalDayStats.of(points, zoneFor = paris)
            val real = RealDurationCalculator.forDays(listOf(dayRow(figures)))!!
            val analysis = TrackAnalysisCalculator.compute(listOf(points), SpeedCalibration.DEFAULT, emptyList(), zoneFor = paris)
            val totals = analysis.totals!!
            assertEquals(real.elapsedSeconds.toDouble(), totals.elapsedSeconds, 1.0)
            assertEquals(figures.sums.pausedSeconds, totals.pausedSeconds, 1e-9)
            assertEquals(real.walkingSeconds!!.toDouble(), totals.movingSeconds, 1.0)
            assertEquals(totals.elapsedSeconds - analysis.estimate!!.totalSeconds, analysis.estimate!!.deltaSeconds, 1e-9)
            val timeline = TrackTimelineCalculator.compute(listOf(points), analysis)
            assertEquals(real.walkingSharePercent, timeline.walkingSharePercent)
            assertEquals(figures.stats.distanceMeters, totals.distanceMeters, 1e-6)
        }
    }

    @Test
    fun noAnalyzedSegmentOrPauseCrossesTheNight() {
        val built = build(40.0, halfDays = 3)
        val analysis = TrackAnalysisCalculator.compute(listOf(built.points), SpeedCalibration.DEFAULT, emptyList(), zoneFor = paris)
        val day = analysis.days.single()
        assertEquals(built.stopIndices, day.bivouacs.map { it.stopIndex })
        for (stop in built.stopIndices) {
            assertTrue(day.segments.none { it.startIndex <= stop && it.endIndex >= stop + 1 })
            assertTrue(day.pauses.none { it.startIndex <= stop && it.endIndex >= stop + 1 })
        }
    }

    @Test
    fun theTimelineShowsTheNightAsABivouacBlockAndNoPhaseContainsIt() {
        val built = build(141.0, halfDays = 3)
        val analysis = TrackAnalysisCalculator.compute(listOf(built.points), SpeedCalibration.DEFAULT, emptyList(), zoneFor = paris)
        val day = TrackTimelineCalculator.compute(listOf(built.points), analysis).days!!.single()

        val blocks = day.elements.filterIsInstance<TimelinePause>().filter { it.isBivouac }
        assertEquals(built.stopIndices, blocks.map { it.startIndex })
        assertEquals(built.stopIndices.map { it + 1 }, blocks.map { it.endIndex })
        blocks.forEach { assertEquals(NIGHT_HOURS * 3_600.0, it.pausedSeconds, 0.0) }
        assertEquals(2.0 * NIGHT_HOURS * 3_600.0, day.bivouacSeconds, 0.0)
        // Aucune phase de marche ne dure la nuit : la plus longue tient dans une demi-journée.
        val phases = day.elements.filterIsInstance<TimelinePhase>()
        assertTrue(phases.isNotEmpty())
        assertTrue(phases.all { it.endSeconds - it.startSeconds <= HALF_DAY_SECONDS.toDouble() })
        // Le déroulé reste continu, du départ à l'arrivée (heures d'horloge, nuit comprise).
        var cursor = 0.0
        for (element in day.elements) {
            assertEquals(cursor, element.startSeconds, 1e-6)
            cursor = element.endSeconds
        }
        assertEquals(day.elapsedSeconds, cursor, 1e-6)
        assertEquals((3 * HALF_DAY_SECONDS + 2 * NIGHT_HOURS * 3_600).toDouble(), day.elapsedSeconds, 0.0)
    }

    @Test
    fun journalSeriesBreaksAddOnlyTheFarIntraDayBivouacs() {
        val far = build(141.0)
        val near = build(40.0)
        assertEquals(setOf(3) + far.stopIndices, DayJunctions.journalSeriesBreaks(listOf(3), far.points, far.stopIndices))
        assertEquals(setOf(3), DayJunctions.journalSeriesBreaks(listOf(3), near.points, near.stopIndices))
        assertEquals(setOf(3), DayJunctions.journalSeriesBreaks(listOf(3)))
    }

    // --- RIC-229 : une nuit refusée (« Pause ») est une pause, pas un bivouac -----------------------

    @Test
    fun effectiveBivouacsLeaveOutTheRefusedStopsAndIgnoreUnknownOnes() {
        val built = build(141.0, halfDays = 3)
        val (s1, s2) = built.stopIndices
        assertEquals(built.stopIndices, DayBivouacs.effective(built.points, emptySet(), paris).map { it.stopIndex })
        assertEquals(listOf(s2), DayBivouacs.effective(built.points, setOf(s1), paris).map { it.stopIndex })
        assertTrue(DayBivouacs.effective(built.points, setOf(s1, s2), paris).isEmpty())
        // Un refus qui ne tombe sur aucune nuit détectée est ignoré.
        assertEquals(built.stopIndices, DayBivouacs.effective(built.points, setOf(3, built.points.size + 5), paris).map { it.stopIndex })

        val nights = DayBivouacs.nights(built.points, setOf(s1, 3), paris)
        assertEquals(listOf(s1), nights.refused.map { it.stopIndex })
        assertEquals(listOf(s2), nights.bivouacs.map { it.stopIndex })
        assertEquals(listOf(s1 to s1 + 1), DayBivouacs.forcedPauses(nights.refused))
    }

    @Test
    fun theTesterCaseRefusedIs31HoursElapsed14HoursWalkingAndTheNightInPause() {
        // Cas du testeur F-Droid : 7 h de marche, nuit de 17 h reprise à 141 m, 7 h de marche.
        val built = build(141.0)
        val stop = built.stopIndices.single()
        val refused = JournalDayStats.of(built.points, zoneFor = paris, refusedStops = setOf(stop))
        val split = JournalDayStats.of(built.points, zoneFor = paris)

        assertTrue(refused.bivouacs.isEmpty())
        assertEquals(listOf(stop), refused.refusedNights.map { it.stopIndex })
        // Écoulé : du premier au dernier point, nuit comprise (31 h).
        assertEquals(2 * HALF_DAY_SECONDS + NIGHT_HOURS * 3_600, refused.elapsedSeconds)
        // Pause : la nuit seule (la marche est continue de part et d'autre).
        assertEquals(NIGHT_HOURS * 3_600.0, refused.sums.pausedSeconds, 0.0)
        val real = RealDurationCalculator.forDays(listOf(dayRow(refused)))!!
        assertEquals(2 * HALF_DAY_SECONDS, real.walkingSeconds)
        assertEquals(45, real.walkingSharePercent)
        // Aucune coupure de série : la distance du trou (141 m) est comptée.
        assertTrue(DayBivouacs.seriesBreaks(built.points, refused.bivouacs).isEmpty())
        assertEquals(TrackStatsCalculator.compute(built.points).distanceMeters, refused.stats.distanceMeters, 0.0)
        assertEquals(141.0, refused.stats.distanceMeters - split.stats.distanceMeters, 0.5)

        // Le même accepté (bivouac, réponse « Bivouac » ou pas encore de réponse) : lot 2 inchangé.
        assertEquals(listOf(stop), split.bivouacs.map { it.stopIndex })
        assertTrue(split.refusedNights.isEmpty())
        assertEquals(2 * HALF_DAY_SECONDS, split.elapsedSeconds)
        assertEquals(0.0, split.sums.pausedSeconds, 0.0)
    }

    @Test
    fun aRefusedNightResumedAt30MetersGivesTheSameFiguresAsADetectedPause() {
        val built = build(30.0)
        val stop = built.stopIndices.single()
        val refused = JournalDayStats.of(built.points, zoneFor = paris, refusedStops = setOf(stop))
        // Référence : le jour calculé sans aucune notion de bivouac, où la règle des 50 m voit déjà
        // la nuit comme une pause.
        val asPause = DaySegmentSums.of(built.points)
        assertEquals(asPause, refused.sums)
        assertEquals(TrackStatsCalculator.compute(built.points).distanceMeters, refused.stats.distanceMeters, 0.0)
        assertEquals(2 * HALF_DAY_SECONDS + NIGHT_HOURS * 3_600, refused.elapsedSeconds)
    }

    @Test
    fun theRefusedNightStaysOutOfWalkingSegmentsAndPaceBands() {
        val built = build(141.0)
        val stop = built.stopIndices.single()
        val refused = JournalDayStats.of(built.points, zoneFor = paris, refusedStops = setOf(stop))
        val split = JournalDayStats.of(built.points, zoneFor = paris)
        val found = DayBivouacs.pausesAndSegments(built.points, emptyList(), forcedPauses = listOf(stop to stop + 1))

        // Le tronçon qui traverse la nuit la compte en pause : son temps de marche est de quelques
        // minutes, jamais 17 h.
        val crossing = found.segments.single { it.startIndex <= stop && it.endIndex >= stop + 1 }
        assertEquals(NIGHT_HOURS.toDouble(), crossing.pausedHours, 1e-9)
        assertTrue(crossing.movingHours < 0.1)
        // Temps de marche de la calibration : celui des deux demi-journées, comme découpées (à un
        // tronçon de jonction près).
        val aggregate = refused.sums.aggregate
        assertTrue(aggregate.flatHours + aggregate.steepHours <= 2 * HALF_DAY_SECONDS / 3_600.0 + 1e-9)
        assertEquals(
            split.sums.aggregate.flatHours + split.sums.aggregate.steepHours,
            aggregate.flatHours + aggregate.steepHours,
            0.1,
        )
        // Rythme par pente : aucune bande ne voit la nuit (temps de marche de même ordre qu'en découpant).
        assertEquals(
            split.sums.paceBands.sumOf { it.movingSeconds },
            refused.sums.paceBands.sumOf { it.movingSeconds },
            360.0,
        )
        // Les heures à l'arrêt, elles, comptent la nuit, comme toute pause (voir le rapport RIC-229).
        assertTrue(aggregate.stoppedHours >= NIGHT_HOURS)
    }

    @Test
    fun analysisAndTimelineAgreeWithTheJournalForARefusedNight() {
        val built = build(141.0)
        val stop = built.stopIndices.single()
        val figures = JournalDayStats.of(built.points, zoneFor = paris, refusedStops = setOf(stop))
        val real = RealDurationCalculator.forDays(listOf(dayRow(figures)))!!
        val analysis = TrackAnalysisCalculator.compute(
            listOf(built.points), SpeedCalibration.DEFAULT, emptyList(), zoneFor = paris, refusedStopsByDay = listOf(setOf(stop)),
        )
        val totals = analysis.totals!!
        assertTrue(analysis.days.single().bivouacs.isEmpty())
        assertEquals(real.elapsedSeconds.toDouble(), totals.elapsedSeconds, 1.0)
        assertEquals(figures.sums.pausedSeconds, totals.pausedSeconds, 1e-9)
        assertEquals(real.walkingSeconds!!.toDouble(), totals.movingSeconds, 1.0)
        assertEquals(figures.stats.distanceMeters, totals.distanceMeters, 1e-6)
        // Forme du jour : l'écart à l'estimation se mesure sur l'écoulé nuit comprise.
        assertEquals(totals.elapsedSeconds - analysis.estimate!!.totalSeconds, analysis.estimate!!.deltaSeconds, 1e-9)

        val timeline = TrackTimelineCalculator.compute(listOf(built.points), analysis)
        assertEquals(real.walkingSharePercent, timeline.walkingSharePercent)
        val day = timeline.days!!.single()
        // Un bloc pause ordinaire couvre la nuit ; aucun bloc « au bivouac ».
        val pauses = day.elements.filterIsInstance<TimelinePause>()
        assertTrue(pauses.none { it.isBivouac })
        val night = pauses.single { it.startIndex <= stop && it.endIndex >= stop + 1 }
        assertEquals(NIGHT_HOURS * 3_600.0, night.pausedSeconds, 0.0)
        assertEquals(0.0, day.bivouacSeconds, 0.0)
        // Durée du jour (titre de la frise) : l'écart d'horloge, nuit comprise.
        assertEquals((2 * HALF_DAY_SECONDS + NIGHT_HOURS * 3_600).toDouble(), day.elapsedSeconds, 0.0)
        assertEquals(day.elapsedSeconds, com.bivouac.app.ui.journal.timelineDayDurationSeconds(day), 0.0)
        // Le déroulé reste continu.
        var cursor = 0.0
        for (element in day.elements) {
            assertEquals(cursor, element.startSeconds, 1e-6)
            cursor = element.endSeconds
        }
        assertEquals(day.elapsedSeconds, cursor, 1e-6)
    }

    private fun dayRow(figures: JournalDayStats) = LoggedTrackDayEntity(
        trackId = "t",
        dayIndex = 0,
        rawGpxFilePath = "irrelevant",
        elapsedSeconds = figures.elapsedSeconds,
        pausedSeconds = figures.sums.pausedSeconds,
    )

    private companion object {
        val START: Instant = Instant.parse("2026-06-01T07:00:00Z")
        const val STEP_METERS = 30.0
        const val STEP_SECONDS = 27L
        const val HALF_DAY_HOURS = 7L
        const val NIGHT_HOURS = 17L

        /** Durée réelle d'une demi-journée : un nombre entier de pas de 27 s (933), soit 6 h 59 min 51 s. */
        const val HALF_DAY_SECONDS = HALF_DAY_HOURS * 3_600 / STEP_SECONDS * STEP_SECONDS
    }
}
