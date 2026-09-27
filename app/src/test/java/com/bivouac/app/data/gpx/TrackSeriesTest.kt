package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-114 : la série commune ([TrackStatsCalculator.series]) : distance filtrée au seuil de 3 m,
 * altitude lissée par moyenne glissante sur 50 m de distance parcourue. Toutes les traces sont
 * synthétiques, générées ici avec des graines fixes : aucune trace réelle n'entre dans le dépôt.
 *
 * Les bornes des assertions viennent de la simulation de l'algorithme faite à la conception ; elles
 * ne sont pas à ajuster pour faire passer un test.
 */
class TrackSeriesTest {

    // Degrés de latitude par mètre, dérivé exactement de GeoMath.haversineMeters : pour deux
    // points à même longitude, la formule se réduit à R * radians(deltaLat).
    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)

    private val baseLatitude = 45.0

    /** Un point à [distanceMeters] au nord de l'origine, sur un méridien. */
    private fun at(distanceMeters: Double, elevation: Double?) =
        TrackPoint(latitude = baseLatitude + distanceMeters * degPerMeter, longitude = 6.0, elevationMeters = elevation, time = null)

    /** Un point décalé de ([northMeters], [eastMeters]) par rapport à [center]. */
    private fun offset(center: TrackPoint, northMeters: Double, eastMeters: Double, elevation: Double) =
        TrackPoint(
            latitude = center.latitude + northMeters * degPerMeter,
            longitude = center.longitude + eastMeters * degPerMeter / cos(Math.toRadians(center.latitude)),
            elevationMeters = elevation,
            time = null,
        )

    /** Ligne droite de [lengthMeters] au pas [stepMeters], altitude donnée par [elevation]. */
    private fun line(lengthMeters: Double, stepMeters: Double, elevation: (Double) -> Double?): List<TrackPoint> {
        val count = floor(lengthMeters / stepMeters + 1e-9).toInt()
        return (0..count).map { i -> (i * stepMeters).let { d -> at(d, elevation(d)) } }
    }

    /**
     * [count] points tirés uniformément dans un disque de [radiusMeters] autour de [center] :
     * deux points du disque sont à au plus 2 x [radiusMeters] l'un de l'autre.
     */
    private fun pause(center: TrackPoint, count: Int, radiusMeters: Double, random: Random, elevation: () -> Double) =
        (0 until count).map {
            val r = radiusMeters * sqrt(random.nextDouble())
            val theta = 2 * Math.PI * random.nextDouble()
            offset(center, r * sin(theta), r * cos(theta), elevation())
        }

    // --- plateau bruité : le lissage absorbe le bruit, quel que soit le pas d'enregistrement ---

    @Test
    fun noisyPlateauGainStaysSmallWhateverTheRecordingStep() {
        listOf(1.0, 3.0, 10.0).forEach { step ->
            val random = Random(114L)
            val track = line(2000.0, step) { 1000.0 + random.nextGaussian() }
            val raw = TrackStatsCalculator.compute(track, parameters = TrackStatsParameters(0.0, 0.0)).elevationGainMeters
            val smoothed = TrackStatsCalculator.compute(track).elevationGainMeters
            assertTrue("pas $step m : D+ brut attendu nettement plus élevé (obtenu $raw)", raw > 60.0)
            assertTrue("pas $step m : D+ lissé < 30 m (obtenu $smoothed)", smoothed < 30.0)
        }
    }

    @Test
    fun smoothingAloneIsIndependentOfTheRecordingStep() {
        // La moyenne sur 50 m de distance, seule (seuil de déplacement à 0), donne le même D+ de
        // bruit quel que soit le pas : 20 à 23 m en simulation. Le seuil de 3 m, lui, groupe les
        // points serrés (par 4 au pas de 1 m) et fait encore baisser ce D+ de bruit aux pas denses
        // (simulé : 11 m au pas de 1 m, 15 m au pas de 3 m, 23 m au pas de 10 m), toujours sous
        // la borne de 30 m du test ci-dessus.
        val smoothingOnly = TrackStatsParameters(TrackStatsParameters.ELEVATION_WINDOW_METERS, 0.0)
        val gains = listOf(1.0, 3.0, 10.0).map { step ->
            val random = Random(114L)
            val track = line(2000.0, step) { 1000.0 + random.nextGaussian() }
            TrackStatsCalculator.compute(track, parameters = smoothingOnly).elevationGainMeters
        }
        val spread = (gains.max() - gains.min()) / gains.max()
        assertTrue("les trois pas à moins de 15 % l'un de l'autre (obtenu $gains)", spread < 0.15)
    }

    // --- pauses : ni distance ni D+ fantômes ---

    @Test
    fun noisyStandstillHasExactlyZeroDistanceAndZeroElevationChange() {
        val random = Random(2026L)
        // Rayon 1,4 m : tous les points à moins de 2,8 m les uns des autres, donc sous le seuil.
        val track = pause(at(0.0, 1000.0), 600, 1.4, random) { 1000.0 + random.nextGaussian() }

        val stats = TrackStatsCalculator.compute(track)

        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(0.0, stats.elevationGainMeters, 0.0)
        assertEquals(0.0, stats.elevationLossMeters, 0.0)
    }

    @Test
    fun noisyPauseInTheMiddleOfAFlatWalkAddsNoPhantomGain() {
        val random = Random(27L)
        val before = line(300.0, 5.0) { 1000.0 }
        val stop = pause(before.last(), 600, 1.4, random) { 1000.0 + random.nextGaussian() }
        val after = line(300.0, 5.0) { 1000.0 }.drop(1).map { p ->
            p.copy(latitude = p.latitude + 300.0 * degPerMeter)
        }

        val stats = TrackStatsCalculator.compute(before + stop + after)

        assertTrue("D+ < 0,5 m (obtenu ${stats.elevationGainMeters})", stats.elevationGainMeters < 0.5)
        assertEquals(600.0, stats.distanceMeters, 3.0)
    }

    // --- formes exactes ---

    // Marches de +10 m tous les 100 m, de 100 à 1000 m ; paliers de 100 m au départ et de 200 m à
    // l'arrivée, plus larges qu'une demi-fenêtre.
    private fun staircase(d: Double) = 1000.0 + 10.0 * min(10.0, floor(d / 100.0))

    @Test
    fun regularStaircaseGainIsExact() {
        val stats = TrackStatsCalculator.compute(line(1200.0, 3.0, ::staircase))

        assertEquals(100.0, stats.elevationGainMeters, 1e-9)
        assertEquals(0.0, stats.elevationLossMeters, 1e-9)
    }

    @Test
    fun outAndBackWithAFlatSummitGivesEqualExactGainAndLoss() {
        // Sommet plat de 200 m (de 1000 à 1200 m), bien plus large que la fenêtre de 50 m.
        val track = line(2400.0, 3.0) { d -> if (d <= 1200.0) staircase(d) else staircase(2400.0 - d) }

        val stats = TrackStatsCalculator.compute(track)

        assertEquals(100.0, stats.elevationGainMeters, 1e-9)
        assertEquals(100.0, stats.elevationLossMeters, 1e-9)
    }

    @Test
    fun sharpSummitIsClippedByAboutSlopeTimesAQuarterWindow() {
        // Comportement assumé de toute moyenne glissante : un sommet pointu (pente de 10 %, aucun
        // palier) perd environ pente x 12,5 m de chaque côté. Paliers plats de 100 m aux deux
        // bouts pour isoler l'effet du sommet de celui des bords.
        val slope = 0.10
        val track = line(1200.0, 5.0) { d ->
            when {
                d <= 100.0 -> 1000.0
                d <= 600.0 -> 1000.0 + slope * (d - 100.0)
                d <= 1100.0 -> 1050.0 - slope * (d - 600.0)
                else -> 1000.0
            }
        }

        val stats = TrackStatsCalculator.compute(track)

        val expected = 50.0 - slope * 12.5
        assertTrue("D+ sous le dénivelé brut (obtenu ${stats.elevationGainMeters})", stats.elevationGainMeters < 50.0)
        assertEquals(expected, stats.elevationGainMeters, 0.25)
        assertEquals(stats.elevationGainMeters, stats.elevationLossMeters, 1e-9)
    }

    // --- seuil de distance ---

    @Test
    fun pointsWithinTheThresholdOfTheLastRetainedPointAreNotAccumulated() {
        // Pas de 2 m : chaque point est à 2 m de son prédécesseur, mais à 4 m du dernier RETENU un
        // point sur deux, et c'est cette distance-là qui est cumulée d'un bloc.
        val track = line(8.0, 2.0) { 1000.0 }

        val distances = TrackStatsCalculator.series(track).cumulativeDistanceMeters

        assertArrayEquals(doubleArrayOf(0.0, 0.0, 4.0, 4.0, 8.0), distances, 1e-9)
    }

    @Test
    fun slowDriftIsEventuallyAccumulatedInOneBlock() {
        // Dérive de 1 m par point : rien n'est cumulé tant qu'on reste à 3 m de l'ancre, puis les
        // 4 m d'un coup.
        val track = line(8.0, 1.0) { 1000.0 }

        val distances = TrackStatsCalculator.series(track).cumulativeDistanceMeters

        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 4.0, 4.0, 4.0, 4.0, 8.0), distances, 1e-9)
    }

    @Test
    fun aLastPointWithinTheThresholdIsNotAccumulated() {
        val track = listOf(at(0.0, 1000.0), at(5.0, 1000.0), at(7.0, 1000.0))

        assertEquals(5.0, TrackStatsCalculator.compute(track).distanceMeters, 1e-9)
    }

    @Test
    fun seriesKeepsEveryIndexAndGivesIgnoredPointsTheirAnchorDistance() {
        val random = Random(6L)
        val walk = line(100.0, 5.0) { 1000.0 }
        val stop = pause(walk.last(), 50, 1.0, random) { 1000.0 + random.nextGaussian() }
        val track = walk + stop + line(100.0, 5.0) { 1000.0 }.drop(1).map { p ->
            p.copy(latitude = p.latitude + 100.0 * degPerMeter)
        }

        val series = TrackStatsCalculator.series(track)

        assertEquals(track.size, series.size)
        assertEquals(track.size, series.smoothedElevationMeters!!.size)
        val anchor = series.cumulativeDistanceMeters[walk.lastIndex]
        for (i in walk.size until walk.size + stop.size) {
            assertEquals("point ignoré $i : distance de son ancre", anchor, series.cumulativeDistanceMeters[i], 0.0)
        }
        for (i in 1 until series.size) {
            assertTrue("distance cumulée croissante au sens large ($i)", series.cumulativeDistanceMeters[i] >= series.cumulativeDistanceMeters[i - 1])
        }
    }

    // --- bords et cas limites ---

    @Test
    fun emptyTrackHasNothing() {
        val stats = TrackStatsCalculator.compute(emptyList())
        val series = TrackStatsCalculator.series(emptyList())

        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(0.0, stats.elevationGainMeters, 0.0)
        assertEquals(0, series.size)
    }

    @Test
    fun singlePointHasNoDistanceNoGainAndItsOwnElevation() {
        val track = listOf(at(0.0, 1234.0))

        val stats = TrackStatsCalculator.compute(track)

        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(0.0, stats.elevationGainMeters, 0.0)
        assertEquals(listOf(1234.0), TrackStatsCalculator.smoothedElevationSeries(track))
    }

    @Test
    fun twoPointsFurtherApartThanHalfTheWindowAreNotSmoothed() {
        val track = listOf(at(0.0, 1000.0), at(100.0, 1080.0))

        val stats = TrackStatsCalculator.compute(track)

        assertEquals(80.0, stats.elevationGainMeters, 1e-9)
        assertEquals(100.0, stats.distanceMeters, 1e-6)
    }

    @Test
    fun trackWithoutAnyElevationHasZeroGainAndNoSeries() {
        val track = line(500.0, 10.0) { null }

        val stats = TrackStatsCalculator.compute(track)

        assertEquals(0.0, stats.elevationGainMeters, 0.0)
        assertEquals(0.0, stats.elevationLossMeters, 0.0)
        assertEquals(500.0, stats.distanceMeters, 1e-6)
        assertNull(TrackStatsCalculator.series(track).smoothedElevationMeters)
    }

    @Test
    fun trackShorterThanTheWindowIsPartiallySmoothedWithoutError() {
        val track = line(30.0, 5.0) { d -> 1000.0 + d }

        val stats = TrackStatsCalculator.compute(track)

        assertTrue(stats.elevationGainMeters > 0.0)
        assertTrue(stats.elevationGainMeters < 30.0)
    }

    // --- unification : compute ne retire plus les points sans altitude ---

    @Test
    fun computeInterpolatesMissingElevationsInsteadOfDroppingThePoints() {
        // Rampe régulière : l'altitude interpolée d'un point manquant est exactement celle qu'il
        // aurait eue, donc le résultat est celui de la rampe complète.
        val complete = line(600.0, 5.0) { d -> 1000.0 + 0.1 * d }
        val withGaps = complete.mapIndexed { i, p -> if (i % 17 == 5 || i == 60) p.copy(elevationMeters = null) else p }

        val expected = TrackStatsCalculator.compute(complete)
        val actual = TrackStatsCalculator.compute(withGaps)

        assertEquals(expected.elevationGainMeters, actual.elevationGainMeters, 1e-9)
        assertEquals(expected.distanceMeters, actual.distanceMeters, 0.0)
    }

    // --- coupures ---

    @Test
    fun aBreakAddsNeitherDistanceNorElevationAndStopsTheSmoothing() {
        // Deux tronçons plats à 1000 m et 1200 m, reliés par un saut de 500 m non parcouru.
        val first = line(200.0, 5.0) { 1000.0 }
        val second = line(200.0, 5.0) { 1200.0 }.map { p -> p.copy(latitude = p.latitude + 700.0 * degPerMeter) }
        val track = first + second
        val breaks = setOf(first.lastIndex)

        val stats = TrackStatsCalculator.compute(track, breaks = breaks)
        val series = TrackStatsCalculator.series(track, breaks)

        assertEquals(400.0, stats.distanceMeters, 1e-6)
        assertEquals(0.0, stats.elevationGainMeters, 0.0)
        assertEquals(1000.0, series.smoothedElevationMeters!![first.lastIndex], 0.0)
        assertEquals(1200.0, series.smoothedElevationMeters!![first.size], 0.0)
    }

    @Test
    fun theWindowIsAParameter() {
        // Le point d'extension de la conception : le calculateur ne connaît que des paramètres.
        val random = Random(40L)
        val track = line(2000.0, 3.0) { 1000.0 + random.nextGaussian() }

        val narrow = TrackStatsCalculator.compute(track, parameters = TrackStatsParameters(10.0, 3.0))
        val default = TrackStatsCalculator.compute(track)

        assertTrue(narrow.elevationGainMeters > default.elevationGainMeters)
        assertEquals(50.0, TrackStatsParameters.DEFAULT.elevationWindowMeters, 0.0)
        assertEquals(3.0, TrackStatsParameters.DEFAULT.minMoveMeters, 0.0)
        assertTrue(abs(narrow.distanceMeters - default.distanceMeters) < 1e-9)
    }
}
