package com.bivouac.app.data.gpx

import com.bivouac.app.data.gpx.SpeedCalibrationCalculator.Sample
import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-109 : portage fidèle de
 * docs/pilotage/prototype-calibration-segments/test_prototype.py, qui teste
 * docs/pilotage/prototype-calibration-segments/prototype_calibration.py : la référence exécutable
 * et validée de l'algorithme (voir CR_CALIBRATION_SEGMENTS.md).
 *
 * Chaque test ci-dessous a un équivalent nommé dans test_prototype.py, sauf le dernier bloc
 * ("repli par échantillons seuls"), qui documente le pont vers [SpeedCalibrationCalculator.Sample]
 * (nécessaire en production quand l'agrégat de segments d'une sélection est vide, Journal pas
 * encore rattrapé, voir LoggedTrackRepository.calibrationSamples), et qui n'a pas d'équivalent côté
 * prototype puisque celui-ci n'a jamais eu de dénormalisation ligne-rando à préserver.
 *
 * Non porté : "5. Non-régression sur les VRAIES traces du Journal" du prototype, qui a besoin de
 * l'export de sauvegarde réel de l'utilisateur (103 randos), absent de ce dépôt et jamais copié
 * dedans (voir CR section 2). Point ouvert documenté dans CR_RIC109_IMPLEMENTATION.md.
 */
class SpeedCalibrationCalculatorTest {

    /** Segment cohérent avec le modèle : net et D+ dérivés de la pente, durée dérivée du modèle. */
    private fun synth(distM: Double, slopePct: Double, speedKmh: Double, penalty: Double, pauseHours: Double = 0.0): TrackSegment {
        val net = distM * slopePct / 100.0
        val gain = net.coerceAtLeast(0.0)
        val hours = (distM / 1000.0 + gain / penalty) / speedKmh + pauseHours
        return TrackSegment(distanceMeters = distM, elevationGainMeters = gain, netElevationMeters = net, hours = hours)
    }

    private fun sampleOf(segments: List<TrackSegment>) = Sample(
        distanceMeters = segments.sumOf { it.distanceMeters },
        elevationGainMeters = segments.sumOf { it.elevationGainMeters },
        elapsedHours = segments.sumOf { it.hours },
    )

    // 1. Récupère une vitesse et une pénalité connues à partir de segments exacts.
    @Test
    fun recoversKnownSpeedAndPenaltyFromExactSegments() {
        val segments = List(30) { synth(200.0, 0.0, 4.0, 80.0) } +
            List(30) { synth(200.0, 12.0, 4.0, 80.0) } +
            List(30) { synth(200.0, -12.0, 4.0, 80.0) }

        val result = SpeedCalibrationCalculator.compute(DaySegmentAggregate.of(segments), emptyList())

        assertTrue(result != null)
        assertTrue("vitesse ~4.0 (obtenu ${result!!.calibration.walkingSpeedKmh})", abs(result.calibration.walkingSpeedKmh - 4.0) < 0.05)
        assertTrue("pénalité effectivement ajustée", result.fittedPenalty)
        // La descente n'apporte pas de D+ mais consomme du temps de distance : la pénalité ajustée
        // est donc plus permissive que la vraie (80), c'est le comportement voulu du modèle sans
        // terme D-.
        assertTrue("pénalité >= 80 (obtenu ${result.calibration.elevationGainPenaltyMetersPerKm})", result.calibration.elevationGainPenaltyMetersPerKm >= 80.0)
    }

    // 2. Reste stable quand on ajoute du bruit réaliste (ce qu'un test sur données exactes ne peut
    // pas voir, voir CR section 4, "angle mort du test confirmé").
    @Test
    fun staysStableWithRealisticNoiseAcrossManyDraws() {
        val slopes = doubleArrayOf(0.0, 0.0, 3.0, 8.0, 14.0, -8.0, -14.0)

        fun noisySet(seed: Long, hikeCount: Int): List<TrackSegment> {
            val rng = Random(seed)
            val segments = mutableListOf<TrackSegment>()
            repeat(hikeCount) {
                val pace = 1.0 + rng.nextGaussian() * 0.12 // forme du jour
                repeat(60) {
                    val slope = slopes[rng.nextInt(slopes.size)]
                    val base = synth(200.0, slope, 4.0 * pace, 80.0)
                    val jitter = 1.0 + rng.nextGaussian() * 0.15 // variabilité segment à segment
                    segments += base.copy(hours = base.hours * jitter)
                }
            }
            return segments
        }

        val speeds = (0 until 12).map { draw ->
            SpeedCalibrationCalculator.compute(DaySegmentAggregate.of(noisySet(seed = 7_000L + draw, hikeCount = 9)), emptyList())!!
                .calibration.walkingSpeedKmh
        }
        val amplitude = speeds.max() - speeds.min()
        assertTrue("amplitude de vitesse sur 12 jeux bruités < 1.0 (obtenu $amplitude)", amplitude < 1.0)
    }

    // 3. Cas dégénérés.
    @Test
    fun noSegmentsAndNoFallbackReturnsNull() {
        assertNull(SpeedCalibrationCalculator.compute(DaySegmentAggregate.EMPTY, emptyList()))
    }

    @Test
    fun onlyFlatSegmentsFallsBackToDefaultPenalty() {
        val segments = List(40) { synth(200.0, 0.0, 4.0, 80.0) }

        val result = SpeedCalibrationCalculator.compute(DaySegmentAggregate.of(segments), emptyList())

        assertTrue("que du plat -> pénalité par défaut, pas une valeur inventée", result != null && !result.fittedPenalty)
        assertEquals(SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm, result!!.calibration.elevationGainPenaltyMetersPerKm, 1e-9)
    }

    @Test
    fun onlySteepSegmentsFallsBackToSampleBasedSpeed() {
        val segments = List(40) { synth(200.0, 15.0, 4.0, 80.0) }
        val aggregate = DaySegmentAggregate.of(segments) // flatCount == 0 : la vitesse à plat n'est pas mesurable

        val result = SpeedCalibrationCalculator.compute(aggregate, listOf(sampleOf(segments)))

        assertTrue("que de la pente -> repli", result != null && !result.fittedPenalty)
        assertTrue("le repli reste dans les bornes plausibles", result!!.calibration.walkingSpeedKmh in 1.0..8.0)
    }

    @Test
    fun tooFewFlatSegmentsFallsBack() {
        val segments = List(3) { synth(200.0, 0.0, 4.0, 80.0) }

        val result = SpeedCalibrationCalculator.compute(DaySegmentAggregate.of(segments), listOf(sampleOf(segments)))

        assertTrue("3 segments seulement -> repli", result != null && !result.fittedPenalty)
    }

    // 4. Les arrêts ne contaminent pas la vitesse à plat.
    @Test
    fun pausesDoNotContaminateFlatSpeed() {
        val segments = List(20) { synth(200.0, 0.0, 4.0, 80.0) } +
            List(6) { synth(200.0, 0.0, 4.0, 80.0, pauseHours = 0.5) } +
            List(30) { synth(200.0, 12.0, 4.0, 80.0) }

        val result = SpeedCalibrationCalculator.compute(DaySegmentAggregate.of(segments), emptyList())

        assertTrue(result != null)
        assertTrue("vitesse à plat préservée (obtenu ${result!!.calibration.walkingSpeedKmh})", abs(result.calibration.walkingSpeedKmh - 4.0) < 0.1)
    }

    // 5. RIC-130 : le plafond de pénalité est adaptatif au D+ cumulé des segments pentus (300 au
    // seuil MIN_TOTAL_GAIN_METERS, 450 à partir de 20 000 m, interpolation linéaire clampée entre
    // les deux, voir la kdoc de maxPenaltyFor). Le plafond est observé au travers de compute() :
    // des segments pentus parcourus PLUS VITE que le plat n'ont aucun surcoût attribuable au D+, la
    // branche "aucun surcoût de dénivelé mesurable" renvoie alors exactement le plafond.

    /** Segment pentu parcouru un peu plus vite (4,4 km/h) que la vitesse à plat de référence
     * (4,0 km/h) : surcoût strictement négatif, garanti même en arithmétique flottante. */
    private fun steepFasterThanFlat(gainM: Double) = TrackSegment(
        distanceMeters = 200.0, elevationGainMeters = gainM, netElevationMeters = gainM, hours = 0.2 / 4.4,
    )

    private fun ceilingObservedFor(steep: List<TrackSegment>): Double {
        val segments = List(30) { synth(200.0, 0.0, 4.0, 80.0) } + steep
        val result = SpeedCalibrationCalculator.compute(DaySegmentAggregate.of(segments), emptyList())!!
        assertFalse("branche 'aucun surcoût' attendue", result.fittedPenalty)
        return result.calibration.elevationGainPenaltyMetersPerKm
    }

    @Test
    fun adaptiveCeilingStaysAt300AtTheMinTotalGainAnchor() {
        // 10 segments x 30 m = 300 m pile, le seuil MIN_TOTAL_GAIN_METERS : comportement inchangé
        // au point le plus fragile.
        assertEquals(300.0, ceilingObservedFor(List(10) { steepFasterThanFlat(gainM = 30.0) }), 1e-9)
    }

    @Test
    fun adaptiveCeilingReaches450AtAndBeyondTwentyThousandMeters() {
        // 400 segments x 50 m = 20 000 m pile...
        assertEquals(450.0, ceilingObservedFor(List(400) { steepFasterThanFlat(gainM = 50.0) }), 1e-9)
        // ... et le plafond reste clampé au-delà (500 x 50 m = 25 000 m).
        assertEquals(450.0, ceilingObservedFor(List(500) { steepFasterThanFlat(gainM = 50.0) }), 1e-9)
    }

    @Test
    fun adaptiveCeilingInterpolatesLinearlyBetweenAnchors() {
        // 203 segments x 50 m = 10 150 m, le milieu exact de [300, 20 000] : le plafond doit être
        // au milieu exact de [300, 450].
        assertEquals(375.0, ceilingObservedFor(List(203) { steepFasterThanFlat(gainM = 50.0) }), 1e-9)
    }

    @Test
    fun fittedPenaltyIsCappedByTheAdaptiveCeilingToo() {
        // Surcoût minuscule (3,6 s par segment) mais non nul : la pénalité brute est énorme et le
        // fit est réel. Son écrêtage doit utiliser le même plafond adaptatif (ici 375, à 10 150 m
        // de D+ cumulé), pas l'ancien 300 fixe.
        val steep = List(203) {
            TrackSegment(distanceMeters = 200.0, elevationGainMeters = 50.0, netElevationMeters = 50.0, hours = 0.2 / 4.0 + 1e-3)
        }
        val segments = List(30) { synth(200.0, 0.0, 4.0, 80.0) } + steep
        val result = SpeedCalibrationCalculator.compute(DaySegmentAggregate.of(segments), emptyList())!!
        assertTrue("pénalité effectivement ajustée", result.fittedPenalty)
        assertEquals(375.0, result.calibration.elevationGainPenaltyMetersPerKm, 1e-9)
    }

    // Repli par échantillons seuls (aggregate vide) : le pont vers Sample utilisé quand une
    // sélection n'a pas encore de sommes de segments (banque pas rattrapée, voir
    // LoggedTrackRepository.calibrationSamples). Comportement hérité de l'ancien
    // SpeedCalibrationCalculator, verrouillé ici pour ne pas régresser silencieusement au passage.
    @Test
    fun fallbackAloneKeepsDefaultPenaltyButStillFitsSpeed() {
        val trueSpeed = 4.6
        val truePenalty = 70.0
        val distanceKm = 14.0
        val gainMeters = 650.0
        val sample = Sample(
            distanceMeters = distanceKm * 1000,
            elevationGainMeters = gainMeters,
            elapsedHours = (distanceKm + gainMeters / truePenalty) / trueSpeed,
        )

        val result = SpeedCalibrationCalculator.compute(DaySegmentAggregate.EMPTY, listOf(sample))

        assertTrue(result != null)
        assertFalse(result!!.fittedPenalty)
        assertEquals(SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm, result.calibration.elevationGainPenaltyMetersPerKm, 1e-9)
        // La vitesse reflète bien l'allure réelle de cette rando (calculée contre la pénalité par
        // défaut), pas un simple repli silencieux sur SpeedCalibration.DEFAULT.walkingSpeedKmh.
        assertTrue(abs(result.calibration.walkingSpeedKmh - SpeedCalibration.DEFAULT.walkingSpeedKmh) > 0.1)
    }

    @Test
    fun degenerateTinyFallbackSampleStaysWithinPlausibleBounds() {
        val sample = Sample(distanceMeters = 10.0, elevationGainMeters = 5000.0, elapsedHours = 0.01)

        val result = SpeedCalibrationCalculator.compute(DaySegmentAggregate.EMPTY, listOf(sample))

        assertTrue(result != null)
        assertTrue(result!!.calibration.walkingSpeedKmh in 1.0..8.0)
        assertTrue(result.calibration.elevationGainPenaltyMetersPerKm in 20.0..300.0)
    }

    @Test
    fun noUsableFallbackSampleAndEmptyAggregateReturnsNull() {
        val sample = Sample(distanceMeters = 5000.0, elevationGainMeters = 200.0, elapsedHours = 0.0)
        assertNull(SpeedCalibrationCalculator.compute(DaySegmentAggregate.EMPTY, listOf(sample)))
    }

    @Test
    fun defaultCalibrationMatchesPreviousHardcodedConstants() {
        assertEquals(3.5, SpeedCalibration.DEFAULT.walkingSpeedKmh, 0.0)
        assertEquals(100.0, SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm, 0.0)
        assertEquals(0.0, SpeedCalibration.DEFAULT.pauseFractionPercent, 0.0)
    }

    // 6. RIC-115 : pauseFractionPercent = 100 * stoppedHours / (flatHours + steepHours +
    // stoppedHours), mesurée sur TOUT le pool (voir DaySegmentAggregate.stoppedHours), et attachée
    // à la calibration renvoyée quelle que soit la branche empruntée par compute() (fit complet,
    // repli vitesse-seule, repli par échantillons).

    @Test
    fun pauseFractionPercentIsMeasuredFromStoppedHoursShareOfTotal() {
        // flatHours 3h (12 km à 4 km/h) + steepHours 1h (2 km, excédent 0,5h -> fit réel) +
        // stoppedHours 1h = 5h au total -> 20 % à l'arrêt.
        val aggregate = DaySegmentAggregate(
            flatCount = 40, flatDistanceMeters = 12_000.0, flatHours = 3.0,
            steepCount = 20, steepDistanceMeters = 2_000.0, steepGainMeters = 500.0, steepHours = 1.0,
            stoppedHours = 1.0,
        )

        val result = SpeedCalibrationCalculator.compute(aggregate, emptyList())

        assertTrue(result != null)
        assertTrue("pénalité effectivement ajustée (fit réel attendu)", result!!.fittedPenalty)
        assertEquals(20.0, result.calibration.pauseFractionPercent, 1e-9)
    }

    @Test
    fun pauseFractionPercentIsAttachedOnTheSpeedOnlyFallbackToo() {
        // flatCount = 2 < MIN_FLAT_SEGMENTS(10) -> repli vitesse-seule (samples), mais la part de
        // pause reste mesurée sur l'agrégat : flatHours 0,1h + steepHours 0h + stoppedHours 0,3h =
        // 0,4h au total -> 75 % à l'arrêt.
        val aggregate = DaySegmentAggregate(
            flatCount = 2, flatDistanceMeters = 400.0, flatHours = 0.1,
            steepCount = 0, steepDistanceMeters = 0.0, steepGainMeters = 0.0, steepHours = 0.0,
            stoppedHours = 0.3,
        )
        val sample = Sample(distanceMeters = 5000.0, elevationGainMeters = 200.0, elapsedHours = 1.5)

        val result = SpeedCalibrationCalculator.compute(aggregate, listOf(sample))

        assertTrue(result != null && !result.fittedPenalty)
        assertEquals(75.0, result!!.calibration.pauseFractionPercent, 1e-9)
    }

    @Test
    fun pauseFractionPercentIsZeroWhenAggregateHasNoUsableHours() {
        val sample = Sample(distanceMeters = 5000.0, elevationGainMeters = 200.0, elapsedHours = 1.2)

        val result = SpeedCalibrationCalculator.compute(DaySegmentAggregate.EMPTY, listOf(sample))

        assertTrue(result != null)
        assertEquals(0.0, result!!.calibration.pauseFractionPercent, 1e-9)
    }
}
