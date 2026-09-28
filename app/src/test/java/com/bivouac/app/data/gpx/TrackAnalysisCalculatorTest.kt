package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-146 lot 2 : TrackAnalysisCalculator, portage de docs/pilotage/ric-146/reference_lot2.py (voir
 * sa kdoc pour les définitions exactes). Le contrôle chiffré complet contre l'oracle se fait par un
 * test temporaire non commité (voir le rapport du lot) ; ce fichier couvre la mécanique propre au
 * portage Kotlin : repli et interpolation de la référence, signe de l'écart d'estimation, meilleurs
 * passages (absence, nuit non franchie), totaux sur plusieurs jours, trace sans horodatage.
 *
 * Points construits le long d'un méridien, pas de 30 m : même conversion exacte que
 * TrackSegmenterTest/TrackPauseDetectorTest (chaque saut tombe pile sur son abscisse en mètres), 7
 * sauts (210 m) ferment un tronçon pile au-dessus du seuil de 200 m. Une pente CONSTANTE sur toute
 * la marche fait que le lissage (moyenne sur une fenêtre symétrique) reproduit exactement la pente
 * voulue pour tout point dont la fenêtre ne déborde pas de la trace (à plus de 25 m des deux bouts,
 * soit tout sauf le premier et le dernier point) : les assertions de pente portent donc sur un
 * tronçon intérieur, jamais le premier ni le dernier.
 */
class TrackAnalysisCalculatorTest {

    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)
    private val stepMeters = 30.0

    /** Marche à pente et vitesse constantes, [count] points espacés de [stepMeters]. */
    private fun walk(
        count: Int,
        slopePercent: Double = 0.0,
        speedKmh: Double = 4.0,
        startElevation: Double = 1_000.0,
        startTime: Instant = BASE_TIME,
    ): List<TrackPoint> {
        val stepSeconds = stepMeters / 1_000.0 / speedKmh * 3_600.0
        return (0 until count).map { i ->
            TrackPoint(
                latitude = 45.0 + i * stepMeters * degPerMeter,
                longitude = 6.0,
                elevationMeters = startElevation + i * stepMeters * slopePercent / 100.0,
                time = startTime.plusMillis((i * stepSeconds * 1_000).toLong()),
            )
        }
    }

    private fun band(band: Int, segmentCount: Int, distanceMeters: Double, movingSeconds: Double) =
        PaceBandSum(band, segmentCount, distanceMeters, movingSeconds)

    // --- Repli de la référence (section 5.4) ----------------------------------------------------

    @Test
    fun aBandWithEnoughReferenceSegmentsUsesTheReferenceSpeed() {
        // Bande 5 (plat, -2 à 2 %) : 25 tronçons dans la référence (>= 20), vitesse 5 km/h. La
        // rando analysée est elle aussi plate (donc dans la bande 5) mais à une vitesse différente
        // (4 km/h) : si la référence l'emportait à tort sur la rando elle-même, on verrait 4, pas 5.
        val reference = listOf(band(5, 25, 5_000.0, 3_600.0)) // 5 km en 1 h = 5 km/h
        val hike = listOf(walk(count = 71, slopePercent = 0.0, speedKmh = 4.0))

        val analysis = TrackAnalysisCalculator.compute(hike, SpeedCalibration.DEFAULT, reference)

        val flatBand = analysis.bands.single { it.band == 5 }
        assertEquals(ReferenceSource.REFERENCE, flatBand.referenceSource)
        assertEquals(5.0, flatBand.referenceSpeedKmh!!, 1e-9)
        assertEquals(5.0, analysis.days[0].segments[5].referenceSpeedKmh!!, 1e-9) // tronçon intérieur
    }

    @Test
    fun aBandBelowTheMinimumSegmentCountFallsBackToTheHikeItself() {
        // Bande 5 : seulement 5 tronçons dans la référence (< 20) : repli sur la rando elle-même.
        val reference = listOf(band(5, 5, 1_000.0, 900.0)) // valeur sans importance, sous le seuil
        val hike = listOf(walk(count = 71, slopePercent = 0.0, speedKmh = 4.0))

        val analysis = TrackAnalysisCalculator.compute(hike, SpeedCalibration.DEFAULT, reference)

        val flatBand = analysis.bands.single { it.band == 5 }
        assertEquals(ReferenceSource.HIKE, flatBand.referenceSource)
        // 10 tronçons de 210 m à 4 km/h, sans pause : la rando marche exactement à 4 km/h.
        assertEquals(4.0, flatBand.referenceSpeedKmh!!, 1e-6)
        assertEquals(4.0, analysis.days[0].segments[5].referenceSpeedKmh!!, 1e-6)
    }

    @Test
    fun aBandWithNoSegmentAnywhereHasNoReferenceSpeed() {
        // Bande 0 (descente > 25 %) : ni dans la référence (vide), ni dans une rando entièrement
        // plate : aucune vitesse, aucune couleur possible pour cette bande.
        val hike = listOf(walk(count = 71, slopePercent = 0.0, speedKmh = 4.0))

        val analysis = TrackAnalysisCalculator.compute(hike, SpeedCalibration.DEFAULT, referencePaceBands = emptyList())

        val steepDescentBand = analysis.bands.single { it.band == 0 }
        assertEquals(ReferenceSource.NONE, steepDescentBand.referenceSource)
        assertNull(steepDescentBand.referenceSpeedKmh)
        assertEquals(0.0, steepDescentBand.hikeDistanceMeters, 1e-9)
        assertNull(steepDescentBand.hikeSpeedKmh)
    }

    @Test
    fun theReferenceSpeedInterpolatesLinearlyBetweenTwoBandCenters() {
        // Bandes 6 (centre 3,5 %, 4 km/h) et 7 (centre 7,5 %, 6 km/h). Le tronçon de la rando est à
        // pile 5,5 %, le milieu des deux centres : la référence interpolée doit être pile 5 km/h,
        // la moyenne des deux, pas un palier.
        val reference = listOf(band(6, 25, 4_000.0, 3_600.0), band(7, 25, 6_000.0, 3_600.0))
        val hike = listOf(walk(count = 71, slopePercent = 5.5, speedKmh = 3.0))

        val analysis = TrackAnalysisCalculator.compute(hike, SpeedCalibration.DEFAULT, reference)

        assertEquals(5.5, analysis.days[0].segments[5].netSlopePercent, 1e-6) // tronçon intérieur
        assertEquals(5.0, analysis.days[0].segments[5].referenceSpeedKmh!!, 1e-6)
    }

    @Test
    fun theReferenceSpeedExtrapolatesFlatBeyondTheOutermostKnownBand() {
        // Bandes 6 et 7 seules connues (mêmes valeurs que ci-dessus). Vitesse de marche à 20 km/h,
        // hors de [1, 8] : la rando n'est retenue pour aucune bande (isRetainedForPace faux partout),
        // donc aucun repli ne vient ajouter un point au-delà de la bande 7 ou en-deçà de la bande 6 -
        // seule l'extrapolation de la référence est mesurée, sans contamination par la rando.
        val reference = listOf(band(6, 25, 4_000.0, 3_600.0), band(7, 25, 6_000.0, 3_600.0))

        val steepHike = listOf(walk(count = 71, slopePercent = 20.0, speedKmh = 20.0))
        val steepAnalysis = TrackAnalysisCalculator.compute(steepHike, SpeedCalibration.DEFAULT, reference)
        assertEquals(6.0, steepAnalysis.days[0].segments[5].referenceSpeedKmh!!, 1e-6) // valeur de la dernière bande connue (7)
        assertNull("hors [1, 8] km/h : non retenu", steepAnalysis.days[0].segments[5].movingSpeedKmh)

        val gentleDescentHike = listOf(walk(count = 71, slopePercent = -20.0, speedKmh = 20.0))
        val descentAnalysis = TrackAnalysisCalculator.compute(gentleDescentHike, SpeedCalibration.DEFAULT, reference)
        assertEquals(4.0, descentAnalysis.days[0].segments[5].referenceSpeedKmh!!, 1e-6) // valeur de la première bande connue (6)
    }

    // --- Estimation : signe de l'écart (section 5.5) --------------------------------------------

    @Test
    fun theDeltaIsNegativeWhenTheHikeWasFasterThanPredicted() {
        // Rando à 4 km/h réels, calibration qui prédit une vitesse de 2 km/h (plus lente) : la
        // prévision est plus longue que le réel, l'écart (réel - prévision) est négatif.
        val hike = listOf(walk(count = 71, slopePercent = 0.0, speedKmh = 4.0))
        val slowPrediction = SpeedCalibration(walkingSpeedKmh = 2.0, elevationGainPenaltyMetersPerKm = 100.0, pauseFractionPercent = 0.0)

        val analysis = TrackAnalysisCalculator.compute(hike, slowPrediction, referencePaceBands = emptyList())
        val deltaSeconds = analysis.estimate!!.deltaSeconds

        assertTrue("écart négatif attendu : $deltaSeconds", deltaSeconds < 0.0)
        assertExpectedEstimate(analysis, slowPrediction)
    }

    @Test
    fun theDeltaIsPositiveWhenTheHikeWasSlowerThanPredicted() {
        // Même rando, calibration qui prédit 8 km/h (plus rapide que les 4 km/h réels) : la
        // prévision est plus courte que le réel, l'écart est positif.
        val hike = listOf(walk(count = 71, slopePercent = 0.0, speedKmh = 4.0))
        val fastPrediction = SpeedCalibration(walkingSpeedKmh = 8.0, elevationGainPenaltyMetersPerKm = 100.0, pauseFractionPercent = 0.0)

        val analysis = TrackAnalysisCalculator.compute(hike, fastPrediction, referencePaceBands = emptyList())
        val deltaSeconds = analysis.estimate!!.deltaSeconds

        assertTrue("écart positif attendu : $deltaSeconds", deltaSeconds > 0.0)
        assertExpectedEstimate(analysis, fastPrediction)
    }

    // Recalcule l'attendu avec les fonctions de production elles-mêmes (walkingMinutes/
    // applyPauseProvision, consigne du lot : ne pas les réimplémenter) : ce test vérifie le
    // branchement de TrackAnalysisCalculator dessus, pas la formule.
    private fun assertExpectedEstimate(analysis: TrackAnalysis, calibration: SpeedCalibration) {
        val totals = analysis.totals!!
        val expectedWalkingMinutes = TrackStatsCalculator.walkingMinutes(totals.distanceMeters, totals.elevationGainMeters, calibration)
        val expectedTotalMinutes = TrackStatsCalculator.applyPauseProvision(expectedWalkingMinutes, calibration.pauseFractionPercent)
        assertEquals(expectedWalkingMinutes * 60.0, analysis.estimate!!.walkingSeconds, 1e-6)
        assertEquals(expectedTotalMinutes * 60.0, analysis.estimate.totalSeconds, 1e-6)
        assertEquals(totals.elapsedSeconds - expectedTotalMinutes * 60.0, analysis.estimate.deltaSeconds, 1e-6)
    }

    // --- Meilleurs passages (section 5.6) --------------------------------------------------------

    @Test
    fun bestPassagesAreAbsentWhenNoRunOfSegmentsReachesTheMinimumMovingTime() {
        // Un seul tronçon de 210 m à 4 km/h, plat : 189 s de marche, sous les deux seuils (600 s à
        // plat, 1200 s en montée).
        val hike = listOf(walk(count = 8, slopePercent = 0.0, speedKmh = 4.0))

        val analysis = TrackAnalysisCalculator.compute(hike, SpeedCalibration.DEFAULT, referencePaceBands = emptyList())

        assertNull(analysis.bestPassages!!.climbMetersPerHour)
        assertNull(analysis.bestPassages.flatSpeedKmh)
    }

    @Test
    fun bestClimbIsFoundWhenARunOfSegmentsReachesTheMinimumMovingTime() {
        // 8 tronçons de 210 m à 10 % de pente, 3 km/h : 252 s de marche par tronçon, 2016 s cumulés
        // sur 8 : dépasse 1200 s dès le sixième. VAM attendue = D+ cumulé / heures cumulées au
        // moment où le seuil est atteint.
        val hike = listOf(walk(count = 71, slopePercent = 10.0, speedKmh = 3.0))

        val analysis = TrackAnalysisCalculator.compute(hike, SpeedCalibration.DEFAULT, referencePaceBands = emptyList())

        val segments = TrackSegmenter.segment(hike[0])
        var movingSeconds = 0.0
        var netElevation = 0.0
        for (segment in segments) {
            movingSeconds += segment.movingHours * 3_600.0
            netElevation += segment.netElevationMeters
            if (movingSeconds >= AnalysisParameters.DEFAULT.bestClimbMinMovingSeconds) break
        }
        val expected = netElevation / (movingSeconds / 3_600.0)
        assertEquals(expected, analysis.bestPassages!!.climbMetersPerHour!!, 1e-6)
    }

    @Test
    fun bestPassagesNeverCrossANightBetweenTwoDays() {
        // Deux jours identiques, chacun à 1134 s de marche cumulée (6 tronçons de 210 m à 10 % de
        // pente, 4 km/h) : sous le seuil de 1200 s pris seul, mais 2268 s en tout si les deux jours
        // étaient concaténés à tort. La meilleure montée doit rester absente : la nuit ne se
        // franchit jamais.
        val day = walk(count = 43, slopePercent = 10.0, speedKmh = 4.0)
        val hike = listOf(day, day)

        val analysis = TrackAnalysisCalculator.compute(hike, SpeedCalibration.DEFAULT, referencePaceBands = emptyList())

        assertNull("la nuit ne doit jamais etre franchie par un meilleur passage", analysis.bestPassages!!.climbMetersPerHour)
    }

    // --- Totaux sur plusieurs jours (section 5.5) -------------------------------------------------

    @Test
    fun aTwoDayHikeSumsTotalsAndExcludesTheNightFromElapsedTime() {
        val day0 = walk(count = 71, slopePercent = 0.0, speedKmh = 4.0, startTime = BASE_TIME)
        // Jour 2 démarre 20 h après la fin du jour 1 (bivouac) : cette nuit ne doit compter dans
        // aucun total.
        val day1EndOfDay0 = day0.last().time!!
        val day1 = walk(count = 71, slopePercent = 0.0, speedKmh = 4.0, startTime = day1EndOfDay0.plusSeconds(20 * 3_600))

        val analysis = TrackAnalysisCalculator.compute(listOf(day0, day1), SpeedCalibration.DEFAULT, referencePaceBands = emptyList())

        val oneDayElapsed = (day0.last().time!!.toEpochMilli() - day0.first().time!!.toEpochMilli()) / 1_000.0
        assertEquals(2 * oneDayElapsed, analysis.totals!!.elapsedSeconds, 1e-6)
        assertEquals(2 * 2_100.0, analysis.totals.distanceMeters, 1e-6) // 70 sauts de 30 m par jour
        assertEquals(2, analysis.days.size)
    }

    // --- Trace sans horodatage (section 7.5) ------------------------------------------------------

    @Test
    fun anUntimedTrackOnlyGetsSlopeClassedSegmentsNoPausesNoTotalsNoBandsNoBestPassages() {
        // Mêmes points qu'une marche normale, mais sans heure : TrackSegmenter.segment ne produit
        // aucun tronçon (aucun horodatage exploitable), donc repli sur le découpage 200 m sur la
        // seule distance.
        val untimed = walk(count = 71, slopePercent = 12.0, speedKmh = 4.0).map { it.copy(time = null) }

        val analysis = TrackAnalysisCalculator.compute(listOf(untimed), SpeedCalibration.DEFAULT, referencePaceBands = emptyList())

        assertNull(analysis.totals)
        assertNull(analysis.estimate)
        assertNull(analysis.bestPassages)
        assertTrue(analysis.bands.isEmpty())
        assertEquals(1, analysis.days.size)
        assertTrue(analysis.days[0].pauses.isEmpty())
        val segments = analysis.days[0].segments
        assertTrue("des troncons de pente doivent quand meme etre produits", segments.isNotEmpty())
        for (segment in segments) {
            assertNull(segment.movingSeconds)
            assertNull(segment.movingSpeedKmh)
            assertNull(segment.referenceSpeedKmh)
            assertNull(segment.paceClass)
            assertNull(segment.speedClass)
        }
        // Tronçon intérieur : la pente lue doit être la pente géométrique voulue (12 %, classe 2 :
        // entre 10 et 15 % en valeur absolue, loin de toute frontière pour éviter le bruit flottant
        // du lissage sur une valeur pile sur une borne).
        assertEquals(12.0, segments[5].netSlopePercent, 1e-6)
        assertEquals(2, segments[5].slopeClass)
    }

    companion object {
        val BASE_TIME: Instant = Instant.parse("2026-06-01T08:00:00Z")
    }
}
