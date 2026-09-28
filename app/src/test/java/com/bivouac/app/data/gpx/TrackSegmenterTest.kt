package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-109 : découpage d'une trace en segments de 200 m (voir TrackSegmenter.kt), portage de
 * docs/pilotage/prototype-calibration-segments/segments.py. Ce fichier n'a pas d'équivalent direct
 * dans test_prototype.py (qui teste la calibration sur des `SegmentInput` déjà construits, pas la
 * découpe elle-même) ; les tests ci-dessous couvrent la mécanique propre au portage Kotlin :
 * longueur des segments, gestion du reliquat, points sans altitude ou sans heure, et surtout la
 * propriété centrale du design (CR section 3.2) : la somme du D+ des segments doit égaler
 * exactement le D+ affiché de la trace, calculé par TrackStatsCalculator sur le même lissage.
 */
class TrackSegmenterTest {

    // Pas de 30 m par point. Volontairement pas un diviseur de 200 (200 / 30 n'est pas entier) :
    // avec ce pas, la fermeture d'un segment (accumulatedDistance >= 200) tombe toujours à 7 sauts
    // (210 m), avec 10 m de marge au-dessus du seuil, bien au-delà de l'imprécision flottante du
    // haversine (< 1e-9 m sur ce pas). Un pas de 20 m (multiple exact de 200) mettrait la fermeture
    // pile sur le seuil, où la moindre imprécision ferait basculer le compte de segments d'un côté
    // ou de l'autre selon l'exécution.
    private val stepMeters = 30.0

    // Degrés de latitude par mètre, dérivé exactement de la formule de GeoMath.haversineMeters :
    // pour deux points à même longitude, elle se réduit à R * radians(deltaLat), sans aucune
    // approximation d'angle. Utiliser l'inverse exact ici (plutôt qu'une conversion approchée du
    // type 111 320 m/degré) fait tomber chaque saut consécutif pile sur stepMeters, à moins de
    // 1e-9 m près, vérifié séparément en Python avant d'écrire ce test.
    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)

    private fun points(
        count: Int,
        speedKmh: Double = 4.0,
        elevation: (Int) -> Double? = { 1000.0 },
        time: (Int) -> Instant? = { i -> BASE_TIME.plusSeconds((i * stepMeters / 1000.0 / speedKmh * 3600.0).toLong()) },
    ): List<TrackPoint> = (0 until count).map { i ->
        TrackPoint(
            latitude = 45.0 + i * stepMeters * degPerMeter,
            longitude = 6.0,
            elevationMeters = elevation(i),
            time = time(i),
        )
    }

    @Test
    fun cutsAFlatTrackIntoSegmentsOfAboutTheTargetLength() {
        // 71 points, 70 sauts de 30 m : chaque segment se ferme à 7 sauts (210 m), 70 / 7 = 10
        // segments pile, aucun reliquat.
        val track = points(71)
        val segments = TrackSegmenter.segment(track)

        assertEquals(10, segments.size)
        segments.forEach {
            assertTrue("segment >= 200 m (${it.distanceMeters})", it.distanceMeters >= 200.0)
            assertEquals(210.0, it.distanceMeters, 1e-6)
        }
        assertEquals(2100.0, segments.sumOf { it.distanceMeters }, 1e-6)
    }

    @Test
    fun keepsATailReachingHalfTheTargetLength() {
        // 10 segments pleins (70 sauts) + 4 sauts de 30 m = 120 m de reliquat, au-dessus de la
        // moitié de 200 m : conservé comme onzième segment.
        val track = points(75)
        val segments = TrackSegmenter.segment(track)

        assertEquals(11, segments.size)
        assertEquals(120.0, segments.last().distanceMeters, 1e-6)
    }

    @Test
    fun dropsATailShorterThanHalfTheTargetLength() {
        // 10 segments pleins (70 sauts) + 3 sauts de 30 m = 90 m de reliquat, sous la moitié de
        // 200 m : abandonné.
        val track = points(74)
        val segments = TrackSegmenter.segment(track)

        assertEquals(10, segments.size)
        assertEquals(2100.0, segments.sumOf { it.distanceMeters }, 1e-6)
    }

    @Test
    fun keepsPointsMissingElevationOrTimeInsteadOfDroppingThem() {
        // Deux points sans altitude, un sans horodatage, glissés au milieu d'une trace exploitable.
        val track = points(75, elevation = { i ->
            when (i) {
                20, 21 -> null
                else -> 1000.0
            }
        }).mapIndexed { i, p -> if (i == 50) p.copy(time = null) else p }

        val segments = TrackSegmenter.segment(track)

        // RIC-114 : les points sans altitude portent l'altitude interpolée de la série commune, et
        // le point sans heure reste du parcours (il ne peut simplement pas borner un segment, et
        // l'index 50 n'est de toute façon pas une borne : 49 et 56 le sont). Plus aucun saut n'est
        // perdu : même découpage que la trace intacte, 10 segments de 210 m et 120 m de reliquat.
        // Avant RIC-114 ces trois points étaient écartés, et la distance couverte tombait sous
        // 2220 m.
        segments.forEach { assertTrue(it.hours > 0) }
        assertEquals(11, segments.size)
        assertEquals(2220.0, segments.sumOf { it.distanceMeters }, 1e-6)
    }

    @Test
    fun aSegmentOnlyClosesOnATimestampedPoint() {
        // RIC-114 : le seuil de 200 m est atteint à l'index 7 (210 m), mais ce point n'a pas
        // d'heure : le segment se ferme au premier point horodaté qui suit, l'index 8 (240 m).
        val track = points(71).mapIndexed { i, p -> if (i == 7) p.copy(time = null) else p }

        val segments = TrackSegmenter.segment(track)

        assertEquals(240.0, segments.first().distanceMeters, 1e-6)
        assertEquals(2100.0, segments.sumOf { it.distanceMeters }, 1e-6)
    }

    @Test
    fun tooFewUsablePointsYieldsNoSegments() {
        assertEquals(emptyList<TrackSegment>(), TrackSegmenter.segment(points(1)))
        assertEquals(emptyList<TrackSegment>(), TrackSegmenter.segment(emptyList()))
    }

    // Propriété centrale du design (CR_CALIBRATION_SEGMENTS.md section 3.2) : le découpage doit
    // réutiliser exactement le même lissage que TrackStatsCalculator, sinon la pénalité calibrée
    // n'est pas à l'échelle du D+ que la prédiction affichera. Vérifié ici en reconstituant le
    // même D+ par les deux chemins sur un profil vallonné (pas un simple plan incliné, pour que le
    // lissage ait vraiment un effet à absorber).
    @Test
    fun sumOfSegmentGainMatchesTrackStatsCalculatorExactly() {
        // 197 points, 196 sauts = 28 segments de 7 sauts pile (aucun reliquat) : tous les sauts de
        // la trace tombent dans un segment, donc rien n'est perdu dans une comparaison au flottant
        // près avec TrackStatsCalculator, qui les couvre tous lui aussi.
        val track = points(197, elevation = { i -> 1000.0 + 30.0 * sin(i * 0.2) })

        val segments = TrackSegmenter.segment(track)
        val expectedGain = TrackStatsCalculator.compute(track).elevationGainMeters

        assertEquals(expectedGain, segments.sumOf { it.elevationGainMeters }, 1e-9)
    }

    @Test
    fun netSlopeAndSpeedDerivedPropertiesAreConsistent() {
        val flat = TrackSegment(distanceMeters = 200.0, elevationGainMeters = 5.0, netElevationMeters = 2.0, hours = 0.05)
        assertEquals(1.0, flat.netSlopePercent, 1e-9) // 2 m sur 200 m = 1 %
        assertEquals(4.0, flat.speedKmh, 1e-9) // 0.2 km / 0.05 h

        val degenerate = TrackSegment(distanceMeters = 0.0, elevationGainMeters = 0.0, netElevationMeters = 0.0, hours = 0.0)
        assertEquals(0.0, degenerate.netSlopePercent, 1e-9)
        assertEquals(Double.MAX_VALUE, degenerate.speedKmh, 0.0)
    }

    @Test
    fun daySegmentAggregateClassifiesFlatVersusSteepAndExcludesStoppedSegments() {
        val flatMoving = TrackSegment(distanceMeters = 200.0, elevationGainMeters = 1.0, netElevationMeters = 1.0, hours = 0.05) // 4 km/h, 0.5 %
        val flatStopped = TrackSegment(distanceMeters = 200.0, elevationGainMeters = 0.5, netElevationMeters = 0.5, hours = 0.5) // 0.4 km/h : à l'arrêt
        val steepUp = TrackSegment(distanceMeters = 200.0, elevationGainMeters = 20.0, netElevationMeters = 20.0, hours = 0.08) // 10 %
        val steepDown = TrackSegment(distanceMeters = 200.0, elevationGainMeters = 0.0, netElevationMeters = -20.0, hours = 0.06) // -10 %, sans D+

        val aggregate = DaySegmentAggregate.of(listOf(flatMoving, flatStopped, steepUp, steepDown))

        assertEquals(1, aggregate.flatCount) // flatStopped exclu : sous AnalysisParameters.MIN_MOVING_SPEED_KMH
        assertEquals(200.0, aggregate.flatDistanceMeters, 1e-9)
        assertEquals(0.05, aggregate.flatHours, 1e-9)
        assertEquals(2, aggregate.steepCount) // montée ET descente comptent
        assertEquals(400.0, aggregate.steepDistanceMeters, 1e-9)
        assertEquals(20.0, aggregate.steepGainMeters, 1e-9) // la descente n'apporte aucun D+
        assertEquals(0.14, aggregate.steepHours, 1e-9)
    }

    @Test
    fun daySegmentAggregateExcludesStoppedSegmentsFromSteepToo() {
        // RIC-129 : un arrêt pris en pleine montée ne doit pas gonfler steepHours/steepGainMeters.
        val steepMoving = TrackSegment(distanceMeters = 200.0, elevationGainMeters = 20.0, netElevationMeters = 20.0, hours = 0.08) // 2.5 km/h
        val steepStopped = TrackSegment(distanceMeters = 200.0, elevationGainMeters = 1.0, netElevationMeters = 20.0, hours = 0.5) // 0.4 km/h : à l'arrêt

        val aggregate = DaySegmentAggregate.of(listOf(steepMoving, steepStopped))

        assertEquals(1, aggregate.steepCount)
        assertEquals(200.0, aggregate.steepDistanceMeters, 1e-9)
        assertEquals(20.0, aggregate.steepGainMeters, 1e-9)
        assertEquals(0.08, aggregate.steepHours, 1e-9)
    }

    @Test
    fun daySegmentAggregatePlusIsAdditive() {
        val a = DaySegmentAggregate(flatCount = 2, flatDistanceMeters = 400.0, flatHours = 0.1, steepCount = 1, steepDistanceMeters = 200.0, steepGainMeters = 15.0, steepHours = 0.06, stoppedHours = 0.02)
        val b = DaySegmentAggregate(flatCount = 3, flatDistanceMeters = 600.0, flatHours = 0.15, steepCount = 2, steepDistanceMeters = 400.0, steepGainMeters = 30.0, steepHours = 0.1, stoppedHours = 0.03)

        val sum = a + b

        assertEquals(5, sum.flatCount)
        assertEquals(1000.0, sum.flatDistanceMeters, 1e-9)
        assertEquals(0.25, sum.flatHours, 1e-9)
        assertEquals(3, sum.steepCount)
        assertEquals(600.0, sum.steepDistanceMeters, 1e-9)
        assertEquals(45.0, sum.steepGainMeters, 1e-9)
        assertEquals(0.16, sum.steepHours, 1e-9)
        assertEquals(0.05, sum.stoppedHours, 1e-9)
        assertEquals(sum, DaySegmentAggregate.EMPTY + a + b)
    }

    // RIC-115 : les segments écartés du plat ET du pentu (peu importe leur pente) doivent finir
    // dans stoppedHours, pas être simplement jetés.
    @Test
    fun daySegmentAggregateSumsStoppedHoursAcrossFlatAndSteep() {
        val flatMoving = TrackSegment(distanceMeters = 200.0, elevationGainMeters = 1.0, netElevationMeters = 0.5, hours = 0.05) // 4 km/h, plat
        val flatStopped = TrackSegment(distanceMeters = 200.0, elevationGainMeters = 0.0, netElevationMeters = 0.0, hours = 0.4) // 0.5 km/h, plat mais à l'arrêt
        val steepMoving = TrackSegment(distanceMeters = 200.0, elevationGainMeters = 20.0, netElevationMeters = 20.0, hours = 0.08) // 2.5 km/h, pentu
        val steepStopped = TrackSegment(distanceMeters = 200.0, elevationGainMeters = 1.0, netElevationMeters = 20.0, hours = 0.5) // 0.4 km/h, pentu mais à l'arrêt

        val aggregate = DaySegmentAggregate.of(listOf(flatMoving, flatStopped, steepMoving, steepStopped))

        assertEquals(1, aggregate.flatCount)
        assertEquals(1, aggregate.steepCount)
        assertEquals(0.9, aggregate.stoppedHours, 1e-9)
    }

    // --- RIC-146 : temps de marche des segments et sommes sur la définition fine des pauses ---

    /** Un point à [meters] le long du méridien, [seconds] après le départ, altitude plate. */
    private fun pointAt(meters: Double, seconds: Long, elevation: Double = 1000.0) = TrackPoint(
        latitude = 45.0 + meters * degPerMeter,
        longitude = 6.0,
        elevationMeters = elevation,
        time = BASE_TIME.plusSeconds(seconds),
    )

    @Test
    fun aSegmentContainingAPauseWalksForItsElapsedTimeMinusThePause() {
        // Marche jusqu'à 150 m (index 0 à 5), 5 minutes sur place (index 6 à 15, à moins de 3 m :
        // aucune distance cumulée), puis reprise : le seuil de 200 m tombe à l'index 17, bien après
        // la pause, qui est donc entière dans le premier segment.
        val walkBefore = (0..5).map { k -> pointAt(30.0 * k, 27L * k) }
        val stay = (1..10).map { k -> pointAt(150.0 + (k % 3), 135L + 30L * k) }
        val walkAfter = (1..8).map { k -> pointAt(150.0 + 30.0 * k, 435L + 27L * k) }
        val track = walkBefore + stay + walkAfter

        val segment = TrackSegmenter.segment(track).first()

        assertEquals(0, segment.startIndex)
        assertEquals(17, segment.endIndex)
        assertEquals(489.0 / 3600.0, segment.hours, 1e-9)
        assertEquals(300.0 / 3600.0, segment.pausedHours, 1e-9)
        assertEquals(189.0 / 3600.0, segment.movingHours, 1e-9)
        assertEquals(0.21 / (189.0 / 3600.0), segment.movingSpeedKmh!!, 1e-9)
    }

    @Test
    fun aPauseStraddlingTwoSegmentsIsSharedAtTheCut() {
        // Arrêt qui commence à 190 m (index 5) : on piétine en avançant de 6 m (comptés, au-dessus
        // du seuil de 3 m) jusqu'à 202 m, où le premier segment se ferme (index 7), puis on reste
        // sur place jusqu'à l'index 10. La pause (index 5 à 10, 300 s) est coupée à l'index 7 :
        // 120 s pour le premier segment, 180 s pour le second, et rien de compté deux fois.
        val walkBefore = (0..5).map { k -> pointAt(38.0 * k, 34L * k) } // 190 m, 170 s
        val stay = listOf(
            pointAt(196.0, 230),
            pointAt(202.0, 290), // index 7 : 202 m cumulés, le premier segment se ferme ici
            pointAt(203.0, 350),
            pointAt(204.0, 410),
            pointAt(204.5, 470), // index 10 : fin de la pause
        )
        val walkAfter = (0..6).map { k -> pointAt(234.5 + 30.0 * k, 497L + 27L * k) } // index 11 à 17
        val track = walkBefore + stay + walkAfter

        val pauses = TrackPauseDetector.detect(track)
        assertEquals(listOf(TrackPause(5, 10, 300.0)), pauses)

        val segments = TrackSegmenter.segment(track)
        assertEquals(2, segments.size)
        assertEquals(7, segments[0].endIndex)
        assertEquals(7, segments[1].startIndex)
        assertEquals(17, segments[1].endIndex)
        assertEquals(120.0 / 3600.0, segments[0].pausedHours, 1e-9)
        assertEquals(170.0 / 3600.0, segments[0].movingHours, 1e-9)
        assertEquals(180.0 / 3600.0, segments[1].pausedHours, 1e-9)
        assertEquals((369.0 - 180.0) / 3600.0, segments[1].movingHours, 1e-9)
        assertEquals(300.0 / 3600.0, segments.sumOf { it.pausedHours }, 1e-9)
    }

    @Test
    fun daySegmentAggregateAppliesTheThreeBranchesOfTheFinePauseDefinition() {
        // Branche 1 : aucun temps de marche, tout le temps écoulé est à l'arrêt.
        val noMoving = TrackSegment(200.0, 0.0, 0.0, hours = 0.1, pausedHours = 0.1)
        // Branche 2 : vitesse en marche hors de [1, 8] km/h, tout le temps écoulé est à l'arrêt.
        val tooSlow = TrackSegment(200.0, 0.0, 0.0, hours = 0.5, pausedHours = 0.2) // 0,67 km/h en marche
        val tooFast = TrackSegment(200.0, 0.0, 0.0, hours = 0.1, pausedHours = 0.09) // 20 km/h en marche
        // Branche 3 : retenu, les pauses vont à l'arrêt et le temps de marche au plat ou au pentu.
        val flat = TrackSegment(200.0, 1.0, 1.0, hours = 0.1, pausedHours = 0.05) // 4 km/h, 0,5 %
        val steep = TrackSegment(200.0, 20.0, 20.0, hours = 0.12, pausedHours = 0.02) // 2 km/h, 10 %

        val aggregate = DaySegmentAggregate.of(listOf(noMoving, tooSlow, tooFast, flat, steep))

        assertEquals(1, aggregate.flatCount)
        assertEquals(200.0, aggregate.flatDistanceMeters, 1e-9)
        assertEquals(0.05, aggregate.flatHours, 1e-9)
        assertEquals(1, aggregate.steepCount)
        assertEquals(200.0, aggregate.steepDistanceMeters, 1e-9)
        assertEquals(20.0, aggregate.steepGainMeters, 1e-9)
        assertEquals(0.1, aggregate.steepHours, 1e-9)
        assertEquals(0.1 + 0.5 + 0.1 + 0.05 + 0.02, aggregate.stoppedHours, 1e-9)
    }

    @Test
    fun movingSpeedBoundsAreInclusive() {
        val atOne = TrackSegment(200.0, 0.0, 0.0, hours = 0.2) // 1 km/h pile
        val atEight = TrackSegment(200.0, 0.0, 0.0, hours = 0.025) // 8 km/h pile
        assertTrue(atOne.isRetainedForPace())
        assertTrue(atEight.isRetainedForPace())
        assertEquals(2, DaySegmentAggregate.of(listOf(atOne, atEight)).flatCount)
    }

    // Journée vallonnée avec des arrêts réguliers, construite point par point : l'invariant par
    // jour de la conception (section 10) doit tenir quelle que soit la découpe.
    private fun hillyDayWithStops(): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        var meters = 0.0
        var seconds = 0L
        for (i in 0 until 400) {
            val elevation = 1000.0 + 120.0 * sin(i * 0.03)
            points += pointAt(meters, seconds, elevation)
            if (i % 60 == 59) {
                // 4 minutes sur place, un point toutes les 20 s.
                repeat(12) {
                    seconds += 20
                    points += pointAt(meters + (it % 2), seconds, elevation)
                }
            }
            meters += 30.0
            seconds += 27L + (i % 7) * 4L // allure variable, de 4 à 3 km/h environ
        }
        return points
    }

    @Test
    fun walkingPlusStoppedTimeEqualsElapsedTimeForTheDay() {
        val track = hillyDayWithStops()
        val segments = TrackSegmenter.segment(track)
        val aggregate = DaySegmentAggregate.of(segments)

        assertTrue("des pauses détectées", segments.sumOf { it.pausedHours } > 0.0)
        assertTrue("du plat et du pentu", aggregate.flatCount > 0 && aggregate.steepCount > 0)
        assertEquals(
            segments.sumOf { it.hours },
            aggregate.flatHours + aggregate.steepHours + aggregate.stoppedHours,
            1e-9,
        )
    }

    @Test
    fun paceBandsCountOnlyRetainedSegmentsAndMatchTheCalibrationSums() {
        val track = hillyDayWithStops()
        val sums = DaySegmentSums.of(track)
        val segments = TrackSegmenter.segment(track)

        // Mêmes segments retenus des deux côtés : le rythme par pente et la calibration ne peuvent
        // pas diverger sur ce qu'ils considèrent comme de la marche.
        assertEquals(sums.aggregate.flatCount + sums.aggregate.steepCount, sums.paceBands.sumOf { it.segmentCount })
        assertEquals(
            sums.aggregate.flatDistanceMeters + sums.aggregate.steepDistanceMeters,
            sums.paceBands.sumOf { it.distanceMeters },
            1e-6,
        )
        assertEquals(
            (sums.aggregate.flatHours + sums.aggregate.steepHours) * 3600.0,
            sums.paceBands.sumOf { it.movingSeconds },
            1e-6,
        )
        assertEquals(sums.paceBands.sortedBy { it.band }, sums.paceBands)
        assertTrue(sums.paceBands.all { it.segmentCount > 0 && it.band in 0..10 })
        assertEquals(TrackPauseDetector.detect(track).sumOf { it.seconds }, sums.pausedSeconds, 1e-9)
        assertEquals(DaySegmentAggregate.of(segments), sums.aggregate)
    }

    @Test
    fun paceBandsGroupRetainedSegmentsBySlopeBand() {
        val down = TrackSegment(200.0, 0.0, -30.0, hours = 0.06) // -15 %, bande 2
        val flatA = TrackSegment(200.0, 1.0, 1.0, hours = 0.05) // 0,5 %, bande 5
        val flatB = TrackSegment(200.0, 1.0, -1.0, hours = 0.06, pausedHours = 0.01) // -0,5 %, bande 5
        val stopped = TrackSegment(200.0, 1.0, 1.0, hours = 0.5) // 0,4 km/h : écarté
        val up = TrackSegment(200.0, 60.0, 60.0, hours = 0.1) // 30 %, bande 10

        val bands = PaceBandSum.of(listOf(down, flatA, flatB, stopped, up))

        assertEquals(
            listOf(
                PaceBandSum(band = 2, segmentCount = 1, distanceMeters = 200.0, movingSeconds = 216.0),
                PaceBandSum(band = 5, segmentCount = 2, distanceMeters = 400.0, movingSeconds = 360.0),
                PaceBandSum(band = 10, segmentCount = 1, distanceMeters = 200.0, movingSeconds = 360.0),
            ).map { it.copy(movingSeconds = Math.round(it.movingSeconds * 1e6) / 1e6) },
            bands.map { it.copy(movingSeconds = Math.round(it.movingSeconds * 1e6) / 1e6) },
        )
    }

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-06-01T08:00:00Z")
    }
}
