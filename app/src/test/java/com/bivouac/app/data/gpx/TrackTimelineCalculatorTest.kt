package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-146 lot 6 : TrackTimelineCalculator, portage de docs/pilotage/ric-146/banc_frise.py (fonction
 * `phases`) et de reference_lot6.py (résumé par nature, chiffres). Le contrôle chiffré complet
 * contre l'oracle se fait par un test temporaire non commité (voir le rapport du lot) ; ce fichier
 * couvre la mécanique propre au portage Kotlin sur des points construits.
 *
 * Points construits le long d'un méridien, pas de 30 m (même convention que
 * TrackAnalysisCalculatorTest) : 7 sauts (210 m) ferment un tronçon pile au-dessus du seuil de
 * 200 m, ce qui permet d'aligner les bornes de tronçon sur les bornes de "jambe" (leg) voulues.
 * [DayBuilder] chaîne des jambes à pente et vitesse constantes, avec des pauses ponctuelles
 * (un point immobile inséré : distance nulle, donc détecté comme "trou d'enregistrement" par
 * TrackPauseDetector, portage fidèle de la seconde source de `analyse.py.pauses`).
 */
class TrackTimelineCalculatorTest {

    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)
    private val stepMeters = 30.0

    private inner class DayBuilder(startTime: Instant, startElevation: Double = 1_000.0) {
        private val points = mutableListOf<TrackPoint>()
        private var lat = 45.0
        private var elevation = startElevation
        private var time = startTime

        init {
            points += TrackPoint(lat, LONGITUDE, elevation, time)
        }

        /** [count] pas de [stepMeters], à [slopePercent] et [speedKmh] constants. */
        fun walk(count: Int, slopePercent: Double, speedKmh: Double): DayBuilder {
            val stepSeconds = stepMeters / 1_000.0 / speedKmh * 3_600.0
            repeat(count) {
                lat += stepMeters * degPerMeter
                elevation += stepMeters * slopePercent / 100.0
                time = time.plusMillis((stepSeconds * 1_000).toLong())
                points += TrackPoint(lat, LONGITUDE, elevation, time)
            }
            return this
        }

        /** Un point immobile [seconds] plus tard : trou d'enregistrement, donc une pause. */
        fun pause(seconds: Double): DayBuilder {
            time = time.plusMillis((seconds * 1_000).toLong())
            points += TrackPoint(lat, LONGITUDE, elevation, time)
            return this
        }

        fun build(): List<TrackPoint> = points.toList()
    }

    private fun timeline(days: List<List<TrackPoint>>, referencePaceBands: List<PaceBandSum> = emptyList()): TrackTimeline {
        val analysis = TrackAnalysisCalculator.compute(days, SpeedCalibration.DEFAULT, referencePaceBands)
        return TrackTimelineCalculator.compute(days, analysis)
    }

    /** L'invariant de la conception section 3.2 : aucun trou, aucun chevauchement. */
    private fun assertContiguous(day: DayTimeline) {
        assertTrue("au moins un élément attendu", day.elements.isNotEmpty())
        var expectedStart = 0.0
        for (element in day.elements) {
            assertEquals("trou ou chevauchement avant $element", expectedStart, element.startSeconds, 1e-6)
            assertTrue("élément de durée négative $element", element.endSeconds >= element.startSeconds)
            expectedStart = element.endSeconds
        }
        assertEquals("la fin du dernier élément doit être l'arrivée", day.elapsedSeconds, expectedStart, 1e-6)
    }

    // --- Invariant de partage du temps (section 3.2) --------------------------------------------

    @Test
    fun invariantHoldsWithoutAnyPause() {
        val day = DayBuilder(BASE_TIME).walk(20, slopePercent = 5.0, speedKmh = 4.0).build()
        val result = timeline(listOf(day))
        assertContiguous(result.days!![0])
        assertTrue(result.days[0].elements.none { it is TimelinePause })
    }

    @Test
    fun invariantHoldsWithAPauseInTheMiddle() {
        val day = DayBuilder(BASE_TIME)
            .walk(14, slopePercent = 5.0, speedKmh = 4.0)
            .pause(400.0)
            .walk(14, slopePercent = 5.0, speedKmh = 4.0)
            .build()
        val result = timeline(listOf(day))
        assertContiguous(result.days!![0])
        assertEquals(1, result.days[0].elements.count { it is TimelinePause })
    }

    @Test
    fun invariantHoldsWithAPauseAtTheSummitBetweenAClimbAndADescent() {
        val day = DayBuilder(BASE_TIME)
            .walk(21, slopePercent = 15.0, speedKmh = 3.0)
            .pause(400.0)
            .walk(21, slopePercent = -15.0, speedKmh = 3.0)
            .build()
        val result = timeline(listOf(day))
        assertContiguous(result.days!![0])
        val phases = result.days[0].elements.filterIsInstance<TimelinePhase>()
        assertEquals(TimelinePhaseKind.CLIMB, phases.first().kind)
        assertEquals(TimelinePhaseKind.DESCENT, phases.last().kind)
    }

    @Test
    fun invariantHoldsWithTwoCloseEventPauses() {
        val day = DayBuilder(BASE_TIME)
            .walk(14, slopePercent = 5.0, speedKmh = 4.0)
            .pause(400.0)
            .walk(3, slopePercent = 5.0, speedKmh = 4.0) // 90 m : réuni (règle 2)
            .pause(400.0)
            .walk(14, slopePercent = 5.0, speedKmh = 4.0)
            .build()
        val result = timeline(listOf(day))
        assertContiguous(result.days!![0])
    }

    @Test
    fun invariantHoldsWithAPauseAtTheVeryStartOfTheDay() {
        val day = DayBuilder(BASE_TIME)
            .pause(400.0)
            .walk(20, slopePercent = 5.0, speedKmh = 4.0)
            .build()
        val result = timeline(listOf(day))
        assertContiguous(result.days!![0])
        val first = result.days[0].elements.first()
        assertTrue("le premier élément doit être la pause de tête", first is TimelinePause)
        assertEquals(0.0, first.startSeconds, 1e-6)
    }

    @Test
    fun invariantHoldsWithAPauseAtTheVeryEndOfTheDay() {
        val day = DayBuilder(BASE_TIME)
            .walk(20, slopePercent = 5.0, speedKmh = 4.0)
            .pause(400.0)
            .build()
        val result = timeline(listOf(day))
        assertContiguous(result.days!![0])
        val last = result.days[0].elements.last()
        assertTrue("le dernier élément doit être la pause de queue", last is TimelinePause)
        assertEquals(result.days[0].elapsedSeconds, last.endSeconds, 1e-6)
    }

    // --- Réunion de deux pauses au seuil de 100 m (règle 2) ---------------------------------------

    @Test
    fun twoPausesSeparatedByLessThan100MetersAreMerged() {
        val day = DayBuilder(BASE_TIME)
            .walk(8, slopePercent = 5.0, speedKmh = 4.0) // 240 m : au moins un tronçon plein
            .pause(400.0)
            .walk(3, slopePercent = 5.0, speedKmh = 4.0) // 90 m < 100 m
            .pause(400.0)
            .walk(8, slopePercent = 5.0, speedKmh = 4.0)
            .build()
        val result = timeline(listOf(day))
        assertContiguous(result.days!![0])
        val pauses = result.days[0].elements.filterIsInstance<TimelinePause>()
        assertEquals("les deux pauses doivent être réunies en une seule", 1, pauses.size)
        assertEquals(2, pauses[0].mergedCount)
        assertEquals(800.0, pauses[0].pausedSeconds, 1e-6)
    }

    @Test
    fun twoPausesSeparatedByAtLeast100MetersStayDistinct() {
        val day = DayBuilder(BASE_TIME)
            .walk(8, slopePercent = 5.0, speedKmh = 4.0)
            .pause(400.0)
            .walk(4, slopePercent = 5.0, speedKmh = 4.0) // 120 m >= 100 m
            .pause(400.0)
            .walk(8, slopePercent = 5.0, speedKmh = 4.0)
            .build()
        val result = timeline(listOf(day))
        assertContiguous(result.days!![0])
        val pauses = result.days[0].elements.filterIsInstance<TimelinePause>()
        assertEquals("les deux pauses doivent rester distinctes", 2, pauses.size)
        assertTrue(pauses.all { it.mergedCount == 1 })
    }

    // --- Fusion d'une phase de moins de 1000 m (règle 3), trois cas -------------------------------

    @Test
    fun aShortPhaseWithOnlyOneNeighbourIsFusedIntoIt() {
        // Un seul tronçon de 210 m en montée (< 1000 m), suivi d'un long plat : pas de second
        // voisin possible, la montée courte doit se fondre dans le plat.
        val day = DayBuilder(BASE_TIME)
            .walk(7, slopePercent = 15.0, speedKmh = 3.0) // 210 m, seul groupe court
            .walk(42, slopePercent = 0.0, speedKmh = 4.0) // 1260 m de plat
            .build()
        val result = timeline(listOf(day))
        assertContiguous(result.days!![0])
        val phases = result.days[0].elements.filterIsInstance<TimelinePhase>()
        assertEquals("la phase courte doit avoir fusionné avec son unique voisine", 1, phases.size)
        assertEquals(1_470.0, phases[0].distanceMeters, 5.0)
    }

    @Test
    fun aShortPhaseBetweenTwoNeighboursOfTheSameKindIsFusedIntoBoth() {
        // Plat long, montée courte (< 1000 m), plat long : les deux plats doivent absorber la
        // montée courte ensemble puisqu'ils partagent la même nature.
        val day = DayBuilder(BASE_TIME)
            .walk(42, slopePercent = 0.0, speedKmh = 4.0) // 1260 m plat
            .walk(7, slopePercent = 15.0, speedKmh = 3.0) // 210 m montée courte
            .walk(42, slopePercent = 0.0, speedKmh = 4.0) // 1260 m plat
            .build()
        val result = timeline(listOf(day))
        assertContiguous(result.days!![0])
        val phases = result.days[0].elements.filterIsInstance<TimelinePhase>()
        assertEquals("les trois groupes doivent avoir fusionné en une seule phase", 1, phases.size)
        assertEquals(2_730.0, phases[0].distanceMeters, 5.0)
    }

    @Test
    fun aShortPhaseIsFusedIntoItsLongestNeighbourWhenNeighboursDiffer() {
        // Montée longue (1260 m), plat court (210 m < 1000 m), descente longue (1050 m) : le plat
        // court doit rejoindre la montée (plus longue), pas la descente.
        val day = DayBuilder(BASE_TIME)
            .walk(42, slopePercent = 10.0, speedKmh = 3.0) // 1260 m montée
            .walk(7, slopePercent = 0.0, speedKmh = 4.0) // 210 m plat court
            .walk(35, slopePercent = -10.0, speedKmh = 3.0) // 1050 m descente
            .build()
        val result = timeline(listOf(day))
        assertContiguous(result.days!![0])
        val phases = result.days[0].elements.filterIsInstance<TimelinePhase>()
        assertEquals("la fusion doit laisser deux phases (montée+plat, puis descente)", 2, phases.size)
        assertEquals(1_470.0, phases[0].distanceMeters, 5.0) // 1260 + 210
        assertEquals(TimelinePhaseKind.CLIMB, phases[0].kind)
        assertEquals(1_050.0, phases[1].distanceMeters, 5.0)
        assertEquals(TimelinePhaseKind.DESCENT, phases[1].kind)
    }

    // --- Chaque frontière de nature (règles 3/4/5) ------------------------------------------------

    @Test
    fun aSlopeOfExactlyThePositiveThresholdIsAClimb() {
        val day = DayBuilder(BASE_TIME).walk(70, slopePercent = 2.0, speedKmh = 4.0).build()
        val result = timeline(listOf(day))
        val phases = result.days!![0].elements.filterIsInstance<TimelinePhase>()
        assertEquals(1, phases.size)
        assertEquals(TimelinePhaseKind.CLIMB, phases[0].kind)
    }

    @Test
    fun aSlopeOfExactlyTheNegativeThresholdIsADescent() {
        val day = DayBuilder(BASE_TIME).walk(70, slopePercent = -2.0, speedKmh = 4.0).build()
        val result = timeline(listOf(day))
        val phases = result.days!![0].elements.filterIsInstance<TimelinePhase>()
        assertEquals(1, phases.size)
        assertEquals(TimelinePhaseKind.DESCENT, phases[0].kind)
    }

    @Test
    fun aFlatSlopeWithLowCumulativeElevationChangeStaysFlat() {
        val day = DayBuilder(BASE_TIME).walk(70, slopePercent = 0.0, speedKmh = 4.0).build()
        val result = timeline(listOf(day))
        val phases = result.days!![0].elements.filterIsInstance<TimelinePhase>()
        assertEquals(1, phases.size)
        assertEquals(TimelinePhaseKind.FLAT, phases[0].kind)
    }

    @Test
    fun aZigzagWithNoNetSlopeButHighCumulativeElevationChangeIsRolling() {
        // 5 cycles montée 8 % / descente 8 %, 210 m chacune : pente d'ensemble nulle (retour à
        // l'altitude de départ), mais D+ + D- élevé (règle 5). Les groupes alternés, tous < 1000 m,
        // fusionnent progressivement (règle 3) jusqu'à une seule phase de 2100 m.
        val builder = DayBuilder(BASE_TIME)
        repeat(5) {
            builder.walk(7, slopePercent = 8.0, speedKmh = 3.0)
            builder.walk(7, slopePercent = -8.0, speedKmh = 3.0)
        }
        val result = timeline(listOf(builder.build()))
        val phases = result.days!![0].elements.filterIsInstance<TimelinePhase>()
        assertEquals(1, phases.size)
        assertEquals(TimelinePhaseKind.ROLLING, phases[0].kind)
        val meterPerKm = (phases[0].elevationGainMeters + phases[0].elevationLossMeters) / (phases[0].distanceMeters / 1_000.0)
        assertTrue("dénivelé cumulé attendu très supérieur à 30 m/km : $meterPerKm", meterPerKm > 30.0)
    }

    // --- Temps de marche d'une phase avec des pauses courtes ---------------------------------------

    @Test
    fun aPhaseSubtractsShortPausesFromItsMovingTime() {
        val day = DayBuilder(BASE_TIME)
            .walk(14, slopePercent = 5.0, speedKmh = 4.0)
            .pause(150.0) // < 300 s : pause courte, ne coupe pas la phase
            .walk(14, slopePercent = 5.0, speedKmh = 4.0)
            .build()
        val result = timeline(listOf(day))
        val dayTimeline = result.days!![0]
        assertContiguous(dayTimeline)
        val phases = dayTimeline.elements.filterIsInstance<TimelinePhase>()
        assertEquals("aucune pause d'événement : une seule phase", 1, phases.size)
        assertEquals(1, dayTimeline.shortPauses.size)
        assertEquals(150.0, dayTimeline.shortPauses[0].pausedSeconds, 1e-6)
        assertEquals(dayTimeline.elapsedSeconds - 150.0, phases[0].movingSeconds, 1e-6)
    }

    // --- Rando de deux jours : un déroulé par jour, instants relatifs -----------------------------

    @Test
    fun aTwoDayHikeProducesOneTimelinePerDayWithSecondsRelativeToEachDayStart() {
        val day0 = DayBuilder(BASE_TIME).walk(20, slopePercent = 5.0, speedKmh = 4.0).build()
        val day1 = DayBuilder(BASE_TIME.plusSeconds(20 * 3_600)).walk(20, slopePercent = -5.0, speedKmh = 4.0).build()

        val result = timeline(listOf(day0, day1))

        assertEquals(2, result.days!!.size)
        assertContiguous(result.days[0])
        assertContiguous(result.days[1])
        // Le jour 2 démarre 20 h après la fin du jour 1, mais sa propre frise repart de 0 s.
        assertEquals(0.0, result.days[1].elements.first().startSeconds, 1e-6)
        assertEquals(TimelinePhaseKind.CLIMB, (result.days[0].elements.first() as TimelinePhase).kind)
        assertEquals(TimelinePhaseKind.DESCENT, (result.days[1].elements.first() as TimelinePhase).kind)
    }

    // --- Trace sans horodatage (section 7.5) --------------------------------------------------------

    @Test
    fun anUntimedTrackHasNoTimelineNoSummaryNoWalkingShareButKeepsFigures() {
        val timed = DayBuilder(BASE_TIME).walk(70, slopePercent = 12.0, speedKmh = 4.0).build()
        val untimed = timed.map { it.copy(time = null) }

        val result = timeline(listOf(untimed))

        assertNull(result.days)
        assertNull(result.walkingSharePercent)
        assertTrue(result.terrainSummary.isEmpty())
        assertNotNull(result.figures.highestElevationMeters)
        assertNotNull(result.figures.lowestElevationMeters)
        assertNotNull(result.figures.steepestClimbPercent)
        // Tronçon intérieur (voir TrackAnalysisCalculatorTest) : la pente lue reste fidèle à 12 %.
        assertEquals(12.0, result.figures.steepestClimbPercent!!, 0.5)
    }

    // --- Résumé par nature et chiffres (conception section 4) --------------------------------------

    @Test
    fun terrainSummaryAndFiguresReflectTheThreeCategoriesAndTheSteepestSegments() {
        val day = DayBuilder(BASE_TIME)
            .walk(42, slopePercent = 15.0, speedKmh = 3.0) // montée : 1260 m
            .walk(21, slopePercent = 0.0, speedKmh = 4.0) // plat : 630 m
            .walk(35, slopePercent = -10.0, speedKmh = 3.0) // descente : 1050 m
            .build()

        val result = timeline(listOf(day))

        val climb = result.terrainSummary.single { it.kind == TimelinePhaseKind.CLIMB }
        val flat = result.terrainSummary.single { it.kind == TimelinePhaseKind.FLAT }
        val descent = result.terrainSummary.single { it.kind == TimelinePhaseKind.DESCENT }
        assertEquals(1_260.0, climb.distanceMeters, 30.0)
        assertEquals(630.0, flat.distanceMeters, 30.0)
        assertEquals(1_050.0, descent.distanceMeters, 30.0)
        assertNotNull(climb.movingSpeedKmh)
        assertNotNull(flat.movingSpeedKmh)
        assertNotNull(descent.movingSpeedKmh)

        // Chiffres : sans référence externe, la vitesse de référence de chaque bande replie sur la
        // rando elle-même (voir TrackAnalysisCalculator.buildBands), donc chaque écart reste modéré.
        assertEquals(1_000.0, result.figures.lowestElevationMeters!!, 1.0) // altitude de départ
        assertTrue("la plus forte montée doit être proche de 15 %", result.figures.steepestClimbPercent!! > 10.0)
        assertTrue("la plus forte descente doit être proche de 10 %", result.figures.steepestDescentPercent!! > 5.0)
        assertEquals(100, result.walkingSharePercent) // aucune pause : 100 % de marche
    }

    companion object {
        val BASE_TIME: Instant = Instant.parse("2026-06-01T08:00:00Z")
        const val LONGITUDE = 6.0
    }
}
