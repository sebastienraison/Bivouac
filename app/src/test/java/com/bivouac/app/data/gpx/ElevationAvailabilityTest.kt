package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-231 : le critère unique « la trace a-t-elle une altitude exploitable ? » et ce qu'il change
 * dans [TrackStatsCalculator] : série lissée nulle, [TrackStats.elevationAvailable] faux, D+ et D-
 * à 0.0, durée estimée calculée à plat (donc non nulle).
 */
class ElevationAvailabilityTest {

    // 0,001 degré de latitude ~ 111 m : assez pour franchir le seuil de déplacement minimal.
    private fun points(vararg elevations: Double?): List<TrackPoint> =
        elevations.mapIndexed { index, elevation ->
            TrackPoint(latitude = 45.0 + index * 0.001, longitude = 6.0, elevationMeters = elevation, time = null)
        }

    // --- ElevationAvailability.of ---

    @Test
    fun noEleAtAllIsUnavailable() {
        assertFalse(ElevationAvailability.of(points(null, null, null, null)))
    }

    @Test
    fun emptyTrackIsUnavailable() {
        assertFalse(ElevationAvailability.of(emptyList()))
    }

    @Test
    fun allIdenticalAltitudesAreUnavailable() {
        assertFalse(ElevationAvailability.of(points(0.0, 0.0, 0.0, 0.0)))
        assertFalse(ElevationAvailability.of(points(1250.0, 1250.0, 1250.0)))
    }

    @Test
    fun identicalAltitudesWithGapsAreUnavailable() {
        assertFalse(ElevationAvailability.of(points(0.0, null, 0.0, null, 0.0)))
    }

    @Test
    fun aSingleDifferingPointIsAvailable() {
        assertTrue(ElevationAvailability.of(points(100.0, 100.0, 100.0, 101.0, 100.0)))
    }

    @Test
    fun partialTrackWithRealReliefIsAvailable() {
        assertTrue(ElevationAvailability.of(points(100.0, null, 150.0, null, null, 220.0)))
    }

    // --- TrackStatsCalculator : le critère se propage à la série et aux stats ---

    @Test
    fun interpolateReturnsNullForTheTwoUnavailableCases() {
        assertNull(TrackStatsCalculator.interpolateElevations(points(null, null, null)))
        assertNull(TrackStatsCalculator.interpolateElevations(points(0.0, 0.0, 0.0)))
    }

    @Test
    fun interpolateIsUnchangedForThePartialCase() {
        assertEquals(listOf(100.0, 150.0, 200.0), TrackStatsCalculator.interpolateElevations(points(100.0, null, 200.0)))
    }

    @Test
    fun noEleTrackHasNoSmoothedSeriesAndStatsMarkedUnavailable() {
        val track = points(null, null, null, null, null)

        val series = TrackStatsCalculator.series(track)
        val stats = TrackStatsCalculator.compute(track)

        assertNull(series.smoothedElevationMeters)
        assertFalse(stats.elevationAvailable)
        assertEquals(0.0, stats.elevationGainMeters, 0.0)
        assertEquals(0.0, stats.elevationLossMeters, 0.0)
        assertTrue("la distance reste mesurée", stats.distanceMeters > 400.0)
        assertTrue("la durée reste estimée, à plat", stats.estimatedDurationMinutes > 0)
    }

    @Test
    fun constantAltitudeTrackHasNoSmoothedSeriesAndStatsMarkedUnavailable() {
        val track = points(0.0, 0.0, 0.0, 0.0, 0.0)

        val stats = TrackStatsCalculator.compute(track)

        assertNull(TrackStatsCalculator.series(track).smoothedElevationMeters)
        assertFalse(stats.elevationAvailable)
        assertEquals(0.0, stats.elevationGainMeters, 0.0)
        assertEquals(0.0, stats.elevationLossMeters, 0.0)
    }

    @Test
    fun estimatedDurationOfAnUnavailableTrackIsTheFlatOne() {
        val flat = TrackStatsCalculator.walkingMinutes(
            distanceMeters = TrackStatsCalculator.compute(points(null, null, null, null)).distanceMeters,
            elevationGainMeters = 0.0,
            calibration = SpeedCalibration.DEFAULT,
        )

        val stats = TrackStatsCalculator.compute(points(null, null, null, null))

        assertEquals(Math.round(flat).toInt(), stats.estimatedDurationMinutes)
    }

    @Test
    fun partialTrackStaysAvailableAndKeepsItsGain() {
        val track = points(100.0, null, 300.0, null, 500.0)

        val series = TrackStatsCalculator.series(track)
        val stats = TrackStatsCalculator.compute(track)

        assertNotNull(series.smoothedElevationMeters)
        assertTrue(stats.elevationAvailable)
        assertTrue(stats.elevationGainMeters > 0.0)
    }

    @Test
    fun realReliefTrackIsAvailable() {
        val stats = TrackStatsCalculator.compute(points(100.0, 250.0, 400.0, 350.0, 500.0))

        assertTrue(stats.elevationAvailable)
        assertTrue(stats.elevationGainMeters > 0.0)
    }

    @Test
    fun segmentsOfAnUnavailableSeriesAreUnavailableToo() {
        val series = TrackStatsCalculator.series(points(null, null, null, null))

        assertFalse(series.statsBetween(0, 3).elevationAvailable)
        assertFalse(series.statsBetween(1, 2).elevationAvailable)
    }

    @Test
    fun recomputeDurationKeepsTheAvailabilityFlag() {
        val stats = TrackStats(1_000.0, 0.0, 0.0, 10, elevationAvailable = false)

        assertFalse(TrackStatsCalculator.recomputeDuration(stats, SpeedCalibration.DEFAULT).elevationAvailable)
    }

    @Test
    fun trackStatsAreAvailableByDefault() {
        assertTrue(TrackStats(1_000.0, 10.0, 5.0, 10).elevationAvailable)
    }
}
