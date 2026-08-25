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
 * longueur des segments, gestion du reliquat, filtrage des points inexploitables, et surtout la
 * propriété centrale du design (CR section 3.2) : la somme du D+ des segments doit égaler
 * exactement le D+ affiché de la trace, calculé par TrackStatsCalculator sur le même lissage.
 */
class TrackSegmenterTest {

    // Pas de 30 m par point. Volontairement pas un diviseur de 200 (200 / 30 n'est pas entier) :
    // avec ce pas, la fermeture d'un segment (accumulatedDistance >= 200) tombe toujours à 7 sauts
    // (210 m), avec 10 m de marge au-dessus du seuil — bien au-delà de l'imprécision flottante du
    // haversine (< 1e-9 m sur ce pas). Un pas de 20 m (multiple exact de 200) mettrait la fermeture
    // pile sur le seuil, où la moindre imprécision ferait basculer le compte de segments d'un côté
    // ou de l'autre selon l'exécution.
    private val stepMeters = 30.0

    // Degrés de latitude par mètre, dérivé exactement de la formule de GeoMath.haversineMeters :
    // pour deux points à même longitude, elle se réduit à R * radians(deltaLat), sans aucune
    // approximation d'angle. Utiliser l'inverse exact ici (plutôt qu'une conversion approchée du
    // type 111 320 m/degré) fait tomber chaque saut consécutif pile sur stepMeters, à moins de
    // 1e-9 m près — vérifié séparément en Python avant d'écrire ce test.
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
    fun ignoresPointsMissingElevationOrTime() {
        // Deux points sans altitude, un sans horodatage, glissés au milieu d'une trace exploitable.
        val track = points(75, elevation = { i ->
            when (i) {
                20, 21 -> null
                else -> 1000.0
            }
        }).mapIndexed { i, p -> if (i == 50) p.copy(time = null) else p }

        val segments = TrackSegmenter.segment(track)

        // La trace ne plante pas et reste exploitable, juste amputée des points manquants : moins
        // de distance couverte que la version sans trous ci-dessus (75 points intacts -> 120 m de
        // reliquat sur 2220 m), ici trois sauts de moins.
        assertTrue(segments.isNotEmpty())
        segments.forEach { assertTrue(it.hours > 0) }
        assertTrue(segments.sumOf { it.distanceMeters } < 2220.0)
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
    // lissage fenêtre 5 ait vraiment un effet à absorber).
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

        assertEquals(1, aggregate.flatCount) // flatStopped exclu : sous PAUSE_SPEED_KMH
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
        val a = DaySegmentAggregate(flatCount = 2, flatDistanceMeters = 400.0, flatHours = 0.1, steepCount = 1, steepDistanceMeters = 200.0, steepGainMeters = 15.0, steepHours = 0.06)
        val b = DaySegmentAggregate(flatCount = 3, flatDistanceMeters = 600.0, flatHours = 0.15, steepCount = 2, steepDistanceMeters = 400.0, steepGainMeters = 30.0, steepHours = 0.1)

        val sum = a + b

        assertEquals(5, sum.flatCount)
        assertEquals(1000.0, sum.flatDistanceMeters, 1e-9)
        assertEquals(0.25, sum.flatHours, 1e-9)
        assertEquals(3, sum.steepCount)
        assertEquals(600.0, sum.steepDistanceMeters, 1e-9)
        assertEquals(45.0, sum.steepGainMeters, 1e-9)
        assertEquals(0.16, sum.steepHours, 1e-9)
        assertEquals(sum, DaySegmentAggregate.EMPTY + a + b)
    }

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-06-01T08:00:00Z")
    }
}
