package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.DayJunctions
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.gpximport.planificationSegments
import com.bivouac.app.gpximport.planificationTotalStats
import com.bivouac.app.ui.map.TrackDistanceCache
import java.time.Instant
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-114 : les invariants de cohérence entre écrans (conception, section 2.D), chacun sur une
 * trace synthétique vallonnée, bruitée, avec une pause sur place et un tronçon lent où le seuil de
 * 3 m ignore des points. Graines fixes ; aucune trace réelle n'entre dans le dépôt.
 */
class TrackStatsInvariantsTest {

    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)
    private val calibration = SpeedCalibration.DEFAULT

    /**
     * Une journée horodatée (un point toutes les 2 s), qui part de [startDistanceMeters] vers le
     * nord : marche au pas de 4 m, pause de 80 points sur place (index 300 à 379), tronçon lent au
     * pas de 1,2 m (index 380 à 419, deux points sur trois ignorés par le seuil), puis marche. Le
     * dernier point est à 2244 m du départ ([finalWalkPoints] = 250). Altitude vallonnée plus un
     * bruit gaussien d'1 m.
     */
    private fun day(
        seed: Long,
        startDistanceMeters: Double = 0.0,
        startTime: Instant = Instant.parse("2026-07-01T07:00:00Z"),
        finalWalkPoints: Int = 250,
    ): List<TrackPoint> {
        val random = Random(seed)
        fun elevation(d: Double) = 1000.0 + 60.0 * sin(d / 180.0) + 25.0 * sin(d / 47.0) + random.nextGaussian()
        val points = mutableListOf<TrackPoint>()
        var time = startTime
        fun add(northMeters: Double, eastMeters: Double, d: Double) {
            points += TrackPoint(
                latitude = 45.0 + northMeters * degPerMeter,
                longitude = 6.0 + eastMeters * degPerMeter / cos(Math.toRadians(45.0)),
                elevationMeters = elevation(d),
                time = time,
            )
            time = time.plusSeconds(2)
        }
        var d = startDistanceMeters
        repeat(300) { add(d, 0.0, d); d += 4.0 }
        repeat(80) {
            val r = 1.4 * sqrt(random.nextDouble())
            val theta = 2 * Math.PI * random.nextDouble()
            add(d + r * sin(theta), r * cos(theta), d)
        }
        repeat(40) { add(d, 0.0, d); d += 1.2 }
        repeat(finalWalkPoints) { add(d, 0.0, d); d += 4.0 }
        return points
    }

    // --- I1 : Planification, somme des segments = total ---

    @Test
    fun planificationSegmentsSumToTheTotalWithBivouacsInAPauseAndOnAnIgnoredPoint() {
        val track = day(seed = 1L)
        val distances = TrackStatsCalculator.series(track).cumulativeDistanceMeters
        // Un point du tronçon lent que le seuil ignore : même distance que son prédécesseur.
        val ignored = (390 until 420).first { distances[it] == distances[it - 1] }
        // 150 : marche ; 340 : au milieu de la pause ; ignored : tronçon lent ; 600 : marche.
        val bivouacs = listOf(150, 340, ignored, 600).mapIndexed { i, index -> BivouacPoint("b$i", index) }

        assertSegmentsSumToTotal(track, bivouacs)
    }

    @Test
    fun planificationSegmentsSumToTheTotalAcrossARecordingGap() {
        // Trek dupliqué depuis le Journal : le lendemain repart à 500 m de l'arrivée de la veille.
        val first = day(seed = 2L)
        val second = day(seed = 3L, startDistanceMeters = 2744.0, startTime = Instant.parse("2026-07-02T07:00:00Z"))
        val track = first + second
        val bivouacs = listOf(BivouacPoint("pause", 200), BivouacPoint("nuit", first.lastIndex))
        assertEquals(setOf(first.lastIndex), DayJunctions.planificationSeriesBreaks(track, bivouacs.map { it.trackPointIndex }))

        assertSegmentsSumToTotal(track, bivouacs)
        val total = planificationTotalStats(track, bivouacs, calibration)
        val withoutBreak = TrackStatsCalculator.compute(track, calibration)
        assertTrue("le saut non parcouru est exclu du total", total.distanceMeters < withoutBreak.distanceMeters - 400.0)
    }

    @Test
    fun planificationSegmentsStillSumToTheTotalWhileABivouacIsBeingDragged() {
        // Pendant un glissement, les bornes suivent l'aperçu mais les coupures restent celles des
        // bivouacs validés, comme le total : la somme tombe toujours juste.
        val first = day(seed = 4L)
        val second = day(seed = 5L, startDistanceMeters = 2000.0)
        val track = first + second
        val committed = listOf(BivouacPoint("nuit", first.lastIndex))
        val dragged = listOf(BivouacPoint("nuit", first.lastIndex + 37))

        val segments = planificationSegments(track, committed, dragged, calibration)
        val total = planificationTotalStats(track, committed, calibration)

        assertEquals(total.distanceMeters, segments.sumOf { it.stats.distanceMeters }, 1e-6)
        assertEquals(total.elevationGainMeters, segments.sumOf { it.stats.elevationGainMeters }, 1e-6)
        assertEquals(total.elevationLossMeters, segments.sumOf { it.stats.elevationLossMeters }, 1e-6)
    }

    private fun assertSegmentsSumToTotal(track: List<TrackPoint>, bivouacs: List<BivouacPoint>) {
        val segments = planificationSegments(track, bivouacs, bivouacs, calibration)
        val total = planificationTotalStats(track, bivouacs, calibration)

        assertEquals(bivouacs.size + 1, segments.size)
        assertEquals(total.distanceMeters, segments.sumOf { it.stats.distanceMeters }, 1e-6)
        assertEquals(total.elevationGainMeters, segments.sumOf { it.stats.elevationGainMeters }, 1e-6)
        assertEquals(total.elevationLossMeters, segments.sumOf { it.stats.elevationLossMeters }, 1e-6)
    }

    // --- I2 : Journal, la série affichée est la concaténation des séries par jour ---

    @Test
    fun journalSeriesIsTheConcatenationOfTheDaySeries() {
        // Le lendemain repart à 20 m : jonction sous le seuil des coupures d'enregistrement, mais
        // coupure de série quand même, pour que le profil tombe sur les chiffres stockés par jour.
        val first = day(seed = 6L)
        val second = day(seed = 7L, startDistanceMeters = 2264.0, startTime = Instant.parse("2026-07-02T07:00:00Z"))
        val track = first + second
        val junctions = DayJunctions.bivouacTrackPointIndices(listOf(first.size, second.size))
        val breaks = DayJunctions.journalSeriesBreaks(junctions)

        val series = TrackStatsCalculator.series(track, breaks)
        val firstStats = TrackStatsCalculator.compute(first)
        val secondStats = TrackStatsCalculator.compute(second)

        assertEquals(firstStats.distanceMeters + secondStats.distanceMeters, series.cumulativeDistanceMeters.last(), 1e-6)
        val journalTotal = series.statsBetween(0, track.lastIndex)
        assertEquals(firstStats.elevationGainMeters + secondStats.elevationGainMeters, journalTotal.elevationGainMeters, 1e-9)
        assertEquals(firstStats.elevationLossMeters + secondStats.elevationLossMeters, journalTotal.elevationLossMeters, 1e-9)
        val concatenated = TrackStatsCalculator.series(first).smoothedElevationMeters!! +
            TrackStatsCalculator.series(second).smoothedElevationMeters!!
        assertArrayEquals(concatenated, series.smoothedElevationMeters!!, 0.0)
    }

    // --- I3 : segmenteur, somme des D+ de tous les segments = D+ du jour ---

    @Test
    fun sumOfAllSegmenterGainsEqualsTheDayGain() {
        val track = day(seed = 8L, finalWalkPoints = segmenterFinalWalkPoints)
        assertSegmenterCoversTheDay(track)
    }

    @Test
    fun sumOfAllSegmenterGainsEqualsTheDayGainWithPointsMissingElevationOrTime() {
        val track = day(seed = 9L, finalWalkPoints = segmenterFinalWalkPoints).mapIndexed { i, p ->
            when (i) {
                100, 101, 102 -> p.copy(elevationMeters = null)
                250 -> p.copy(time = null)
                else -> p
            }
        }
        assertSegmenterCoversTheDay(track)
    }

    // Avec 250 points de marche finale, le dernier segment de 200 m se ferme 8 m avant la fin et
    // ce reliquat est abandonné (sous 100 m) : la somme des segments ne peut alors pas égaler le
    // jour. 36 points de plus (144 m) portent le reliquat à 152 m, conservé : tous les sauts du
    // jour tombent dans un segment.
    private val segmenterFinalWalkPoints = 286

    private fun assertSegmenterCoversTheDay(track: List<TrackPoint>) {
        val dayStats = TrackStatsCalculator.compute(track)
        val segments = TrackSegmenter.segment(track)

        // Distance couverte = distance du jour : aucun reliquat abandonné, rien hors segment.
        assertEquals(dayStats.distanceMeters, segments.sumOf { it.distanceMeters }, 1e-6)
        assertEquals(dayStats.elevationGainMeters, segments.sumOf { it.elevationGainMeters }, 1e-9)
    }

    // --- I4 et I5 : profil, bulle et statistiques lisent la même série ---

    @Test
    fun profileCurveAndAxisAreTheSeriesThatProducedTheStats() {
        val first = day(seed = 10L)
        val second = day(seed = 11L, startDistanceMeters = 2000.0)
        val track = first + second
        val breaks = DayJunctions.planificationSeriesBreaks(track, listOf(first.lastIndex))

        // Ce que le profil trace (ElevationProfile lit TrackStatsCalculator.series avec les mêmes
        // coupures), et ce que les statistiques affichent.
        val profile = TrackStatsCalculator.series(track, breaks)
        val stats = TrackStatsCalculator.compute(track, calibration, breaks)

        assertEquals(profile.smoothedElevationMeters!!.toList(), TrackStatsCalculator.smoothedElevationSeries(track, breaks))
        var gain = 0.0
        var loss = 0.0
        val curve = profile.smoothedElevationMeters!!
        for (i in 0 until curve.lastIndex) {
            if (i in breaks) continue
            val delta = curve[i + 1] - curve[i]
            if (delta > 0) gain += delta else loss -= delta
        }
        assertEquals(stats.elevationGainMeters, gain, 0.0)
        assertEquals(stats.elevationLossMeters, loss, 0.0)
        assertEquals("l'axe du profil finit sur la distance affichée", stats.distanceMeters, profile.cumulativeDistanceMeters.last(), 0.0)
    }

    @Test
    fun cursorBubbleDistanceIsTheStatsDistanceAtTheSameIndex() {
        val first = day(seed = 12L)
        val second = day(seed = 13L, startDistanceMeters = 2000.0)
        val track = first + second
        val bivouacs = listOf(BivouacPoint("midi", 340), BivouacPoint("nuit", first.lastIndex))
        val breaks = DayJunctions.planificationSeriesBreaks(track, bivouacs.map { it.trackPointIndex })

        val cache = TrackDistanceCache().apply { bind(breaks, TrackStatsParameters.DEFAULT) }
        val bubble = cache.distancesFor(track)
        val segments = planificationSegments(track, bivouacs, bivouacs, calibration)

        assertArrayEquals(TrackStatsCalculator.series(track, breaks).cumulativeDistanceMeters, bubble, 0.0)
        // Au bivouac k, la bulle annonce la somme des segments qui le précèdent ; au dernier point,
        // le total.
        assertEquals(segments[0].stats.distanceMeters, bubble[340], 1e-6)
        assertEquals(segments[0].stats.distanceMeters + segments[1].stats.distanceMeters, bubble[first.lastIndex], 1e-6)
        assertEquals(planificationTotalStats(track, bivouacs, calibration).distanceMeters, bubble.last(), 1e-6)
    }

    @Test
    fun cursorBubbleDistanceFollowsANewBindingOfTheSameTrack() {
        val track = day(seed = 14L) + day(seed = 15L, startDistanceMeters = 1600.0)
        val cache = TrackDistanceCache()
        val unbroken = cache.distancesFor(track).last()

        cache.bind(setOf(669), TrackStatsParameters.DEFAULT)

        assertTrue("la coupure retire le pas de jonction", cache.distancesFor(track).last() < unbroken)
    }

    // --- I6 : les index de points ne changent jamais ---

    @Test
    fun seriesHasOneEntryPerPointWhateverThePausesAndBreaks() {
        val first = day(seed = 16L)
        val track = first + day(seed = 17L, startDistanceMeters = 2000.0)
        val series = TrackStatsCalculator.series(track, setOf(first.lastIndex))

        assertEquals(track.size, series.size)
        assertEquals(track.size, series.smoothedElevationMeters!!.size)
    }
}
