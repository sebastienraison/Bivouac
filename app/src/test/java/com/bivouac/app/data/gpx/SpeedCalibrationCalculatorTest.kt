package com.bivouac.app.data.gpx

import com.bivouac.app.data.gpx.SpeedCalibrationCalculator.Sample
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCalibrationCalculatorTest {

    // Samples generated from a known (speed, penalty) pair — elapsedHours is exactly what that
    // calibration predicts, so a correct solver should recover the same pair back out.
    private fun syntheticSample(distanceKm: Double, gainMeters: Double, trueSpeedKmh: Double, truePenalty: Double): Sample {
        val equivalentKm = distanceKm + gainMeters / truePenalty
        return Sample(distanceMeters = distanceKm * 1000, elevationGainMeters = gainMeters, elapsedHours = equivalentKm / trueSpeedKmh)
    }

    @Test
    fun recoversKnownSpeedAndPenaltyFromSyntheticHikes() {
        val trueSpeed = 4.2
        val truePenalty = 85.0
        val samples = listOf(
            syntheticSample(12.0, 600.0, trueSpeed, truePenalty),
            syntheticSample(8.0, 1200.0, trueSpeed, truePenalty),
            syntheticSample(20.0, 300.0, trueSpeed, truePenalty),
            syntheticSample(5.0, 900.0, trueSpeed, truePenalty),
        )

        val result = SpeedCalibrationCalculator.compute(samples)

        assertTrue(result != null)
        assertTrue("speed ${result!!.walkingSpeedKmh} not close to $trueSpeed", abs(result.walkingSpeedKmh - trueSpeed) < 0.05)
        assertTrue(
            "penalty ${result.elevationGainPenaltyMetersPerKm} not close to $truePenalty",
            abs(result.elevationGainPenaltyMetersPerKm - truePenalty) < 5.0,
        )
    }

    @Test
    fun flatHikesOnlyStillFitsAPlausibleSpeed() {
        val samples = listOf(
            syntheticSample(10.0, 0.0, 5.0, 100.0),
            syntheticSample(15.0, 0.0, 5.0, 100.0),
        )
        val result = SpeedCalibrationCalculator.compute(samples)
        assertTrue(result != null)
        assertTrue(abs(result!!.walkingSpeedKmh - 5.0) < 0.1)
    }

    @Test
    fun noUsableSamplesReturnsNull() {
        val samples = listOf(Sample(distanceMeters = 5000.0, elevationGainMeters = 200.0, elapsedHours = 0.0))
        assertNull(SpeedCalibrationCalculator.compute(samples))
    }

    @Test
    fun emptyListReturnsNull() {
        assertNull(SpeedCalibrationCalculator.compute(emptyList()))
    }

    @Test
    fun degenerateSingleTinySampleStaysWithinPlausibleBounds() {
        val samples = listOf(Sample(distanceMeters = 10.0, elevationGainMeters = 5000.0, elapsedHours = 0.01))
        val result = SpeedCalibrationCalculator.compute(samples)
        assertTrue(result != null)
        assertTrue(result!!.walkingSpeedKmh in 1.0..8.0)
        assertTrue(result.elevationGainPenaltyMetersPerKm in 20.0..300.0)
    }

    // Documents a real user-facing question (BIV-16 recette): with a single hike, speed and D+
    // penalty can't be told apart mathematically — one data point, two unknowns — so penalty stays
    // exactly at the default rather than a value that looks computed but isn't. SettingsScreen's
    // Sélection mode explains this to the user once selectedTrackCount == 1; this test pins down
    // the calculator-level behavior behind that message.
    @Test
    fun singleSampleKeepsDefaultPenaltyButStillFitsSpeed() {
        val sample = syntheticSample(distanceKm = 14.0, gainMeters = 650.0, trueSpeedKmh = 4.6, truePenalty = 70.0)
        val result = SpeedCalibrationCalculator.compute(listOf(sample))
        assertTrue(result != null)
        assertEquals(SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm, result!!.elevationGainPenaltyMetersPerKm, 1e-9)
        // Speed still reflects that one hike's real pace, computed against the default penalty —
        // not just silently falling back to SpeedCalibration.DEFAULT.walkingSpeedKmh too.
        assertTrue(abs(result.walkingSpeedKmh - SpeedCalibration.DEFAULT.walkingSpeedKmh) > 0.1)
    }

    @Test
    fun defaultCalibrationMatchesPreviousHardcodedConstants() {
        assertEquals(3.5, SpeedCalibration.DEFAULT.walkingSpeedKmh, 0.0)
        assertEquals(100.0, SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm, 0.0)
    }
}
