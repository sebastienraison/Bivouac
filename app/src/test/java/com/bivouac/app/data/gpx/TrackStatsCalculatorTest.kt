package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-115 : provision de pause réglable, appliquée à la toute fin de l'estimation de durée. Le
 * modèle vitesse/pénalité D+ (compute/recomputeDuration) est inchangé ; ce fichier couvre
 * spécifiquement la conversion "minutes de marche pure" -> "minutes totales" (walkingMinutes /
 * applyPauseProvision) et sa non-régression stricte quand pauseFractionPercent = 0 (le comportement
 * d'avant ce ticket, pour tout utilisateur qui n'a jamais touché ce réglage).
 */
class TrackStatsCalculatorTest {

    // Degrés de latitude par mètre, même dérivation exacte que TrackSegmenterTest : deux points à
    // même longitude, la formule haversine se réduit à R * radians(deltaLat).
    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)

    private fun straightLine(distanceMeters: Double, elevationGainMeters: Double): List<TrackPoint> = listOf(
        TrackPoint(latitude = 45.0, longitude = 6.0, elevationMeters = 1000.0, time = null),
        TrackPoint(latitude = 45.0 + distanceMeters * degPerMeter, longitude = 6.0, elevationMeters = 1000.0 + elevationGainMeters, time = null),
    )

    // --- non-régression : pauseFractionPercent = 0 reproduit exactement le calcul d'avant RIC-115 ---

    @Test
    fun zeroPauseFractionLeavesComputeUnchanged() {
        val calibration = SpeedCalibration(walkingSpeedKmh = 4.0, elevationGainPenaltyMetersPerKm = 100.0, pauseFractionPercent = 0.0)
        val points = straightLine(distanceMeters = 4000.0, elevationGainMeters = 400.0)

        val stats = TrackStatsCalculator.compute(points, calibration)

        // distance 4 km + 400 m de D+ (pénalité 100 m/km -> 4 km équivalents) = 8 km équivalents à 4 km/h = 2h00 = 120 min.
        assertEquals(120, stats.estimatedDurationMinutes)
    }

    @Test
    fun zeroPauseFractionLeavesRecomputeDurationUnchanged() {
        val stats = TrackStats(distanceMeters = 10_000.0, elevationGainMeters = 500.0, elevationLossMeters = 300.0, estimatedDurationMinutes = 0)
        val calibration = SpeedCalibration(walkingSpeedKmh = 5.0, elevationGainPenaltyMetersPerKm = 100.0, pauseFractionPercent = 0.0)

        val recomputed = TrackStatsCalculator.recomputeDuration(stats, calibration)

        // 10 km + 5 km équivalents (500/100) = 15 km à 5 km/h = 3h00 = 180 min.
        assertEquals(180, recomputed.estimatedDurationMinutes)
    }

    @Test
    fun defaultCalibrationHasNeutralPauseFraction() {
        assertEquals(0.0, SpeedCalibration.DEFAULT.pauseFractionPercent, 0.0)
    }

    // --- la provision de pause majore bien le temps de marche pur ---

    @Test
    fun computeAppliesPauseProvisionOnTopOfWalkingTime() {
        // 15 % de pause : temps total = marche / (1 - 0.15) = marche / 0.85.
        val calibration = SpeedCalibration(walkingSpeedKmh = 4.0, elevationGainPenaltyMetersPerKm = 100.0, pauseFractionPercent = 15.0)
        val points = straightLine(distanceMeters = 4000.0, elevationGainMeters = 400.0)

        val stats = TrackStatsCalculator.compute(points, calibration)

        // Marche pure : 120 min (voir zeroPauseFractionLeavesComputeUnchanged). Avec 15 % : 120 / 0.85 = 141.18 -> 141.
        assertEquals(141, stats.estimatedDurationMinutes)
    }

    @Test
    fun recomputeDurationAppliesPauseProvision() {
        val stats = TrackStats(distanceMeters = 10_000.0, elevationGainMeters = 500.0, elevationLossMeters = 300.0, estimatedDurationMinutes = 0)
        val calibration = SpeedCalibration(walkingSpeedKmh = 5.0, elevationGainPenaltyMetersPerKm = 100.0, pauseFractionPercent = 15.0)

        val recomputed = TrackStatsCalculator.recomputeDuration(stats, calibration)

        // Marche pure : 180 min. Avec 15 % : 180 / 0.85 = 211.76 -> 212.
        assertEquals(212, recomputed.estimatedDurationMinutes)
    }

    // --- walkingMinutes / applyPauseProvision, exposées pour les aperçus IHM de Réglages ---

    @Test
    fun walkingMinutesMatchesTheSpeedPenaltyFormula() {
        val calibration = SpeedCalibration(walkingSpeedKmh = 4.1, elevationGainPenaltyMetersPerKm = 92.0, pauseFractionPercent = 0.0)

        // Rando type de la maquette Réglages : 15 km, 600 m de D+ -> 5h15 (315 min), dont 1h35 (95 min) dus au D+.
        val totalWalking = TrackStatsCalculator.walkingMinutes(15_000.0, 600.0, calibration)
        val dPlusOnly = TrackStatsCalculator.walkingMinutes(0.0, 600.0, calibration)

        assertTrue("total marche ~315 min (obtenu $totalWalking)", abs(totalWalking - 315.0) < 1.0)
        assertTrue("part D+ ~95 min (obtenu $dPlusOnly)", abs(dPlusOnly - 95.0) < 1.0)
    }

    @Test
    fun applyPauseProvisionIsIdentityAtZeroPercent() {
        assertEquals(360.0, TrackStatsCalculator.applyPauseProvision(360.0, 0.0), 1e-9)
    }

    @Test
    fun applyPauseProvisionMatchesReglagesPreviewExample() {
        // Aperçu de la maquette : 6h de marche pure (360 min), 13 % de pause -> 6h54 (414 min).
        val total = TrackStatsCalculator.applyPauseProvision(360.0, 13.0)
        assertTrue("total ~414 min (obtenu $total)", abs(total - 414.0) < 1.0)
    }

    @Test
    fun applyPauseProvisionCoercesAnUnsafeFractionInsteadOfDividingByZeroOrNegative() {
        // Défense en profondeur : une valeur DataStore corrompue (>= 100 %) ne doit jamais produire
        // une division par zéro ou négative : coercée à 90 % avant division (voir la kdoc de la
        // fonction), donc un résultat fini, positif, et strictement supérieur à l'entrée.
        val result = TrackStatsCalculator.applyPauseProvision(100.0, 150.0)

        assertTrue("résultat fini", result.isFinite())
        assertTrue("résultat positif (obtenu $result)", result > 0.0)
        assertEquals(100.0 / (1.0 - 0.90), result, 1e-9)
    }
}
