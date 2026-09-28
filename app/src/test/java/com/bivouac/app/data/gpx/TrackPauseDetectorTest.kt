package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-146 : détection fine des pauses (TrackPauseDetector), portage de la fonction `pauses` de
 * docs/pilotage/ric-146/analyse.py. Points construits le long d'un méridien, à une abscisse en
 * mètres donnée : même conversion exacte que TrackSegmenterTest, chaque distance entre deux points
 * tombe pile sur la différence de leurs abscisses.
 */
class TrackPauseDetectorTest {

    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)

    /** Un point à [meters] le long de la trace, [seconds] après le départ (null : sans heure). */
    private fun point(meters: Double, seconds: Long?) = TrackPoint(
        latitude = 45.0 + meters * degPerMeter,
        longitude = 6.0,
        elevationMeters = 1000.0,
        time = seconds?.let { BASE_TIME.plusSeconds(it) },
    )

    /** Marche à 4 km/h, un point tous les 30 m (27 s), de [fromMeters] sur [count] points. */
    private fun walk(fromMeters: Double, fromSeconds: Long, count: Int): List<TrackPoint> =
        (1..count).map { k -> point(fromMeters + 30.0 * k, fromSeconds + 27L * k) }

    @Test
    fun emptyTrackHasNoPause() {
        assertEquals(emptyList<TrackPause>(), TrackPauseDetector.detect(emptyList()))
    }

    @Test
    fun pointsWithoutTimestampsHaveNoPause() {
        // Tous sur place : une pause évidente dans l'espace, mais aucune durée mesurable.
        val track = (0 until 20).map { point(it * 0.5, null) }
        assertEquals(emptyList<TrackPause>(), TrackPauseDetector.detect(track))
    }

    @Test
    fun detectsASimplePauseInsideTheRadius() {
        // Marche jusqu'à 150 m, puis 5 minutes sur place (un point toutes les 30 s, à moins de
        // 3 m du premier), puis reprise de la marche.
        val before = listOf(point(0.0, 0)) + walk(0.0, 0, 5) // index 0 à 5, dernier à 150 m, 135 s
        val stay = (1..10).map { k -> point(150.0 + (k % 3), 135L + 30L * k) } // index 6 à 15
        val after = walk(150.0, 435, 5) // index 16 à 20
        val track = before + stay + after

        val pauses = TrackPauseDetector.detect(track)

        // La pause s'ouvre au dernier point de marche (index 5, déjà dans le rayon) et se ferme au
        // dernier point sur place : le premier point de reprise est à 30 m, hors du rayon.
        assertEquals(listOf(TrackPause(5, 15, 300.0)), pauses)
    }

    @Test
    fun shortStopUnderTheMinimumDurationIsNotAPause() {
        val before = listOf(point(0.0, 0)) + walk(0.0, 0, 5)
        val stay = (1..3).map { k -> point(150.0 + k, 135L + 30L * k) } // 90 s sur place
        val after = walk(153.0, 225, 5)
        assertEquals(emptyList<TrackPause>(), TrackPauseDetector.detect(before + stay + after))
    }

    @Test
    fun detectsARecordingGapWithoutMovement() {
        // Montre en pause automatique : rien pendant 10 minutes, et 20 m entre les deux points qui
        // bordent le trou. La première source ne le voit pas (deux points seulement, 20 m > 15 m).
        val before = listOf(point(0.0, 0)) + walk(0.0, 0, 5) // dernier : index 5, 150 m, 135 s
        val after = listOf(point(170.0, 735)) + walk(170.0, 735, 5) // index 6 à 11
        val pauses = TrackPauseDetector.detect(before + after)

        assertEquals(listOf(TrackPause(5, 6, 600.0)), pauses)
    }

    @Test
    fun recordingGapWithTooMuchMovementIsNotAPause() {
        // Même trou de 10 minutes, mais 80 m parcourus pendant : l'enregistrement a sauté un
        // tronçon de marche, ce n'est pas un arrêt.
        val before = listOf(point(0.0, 0)) + walk(0.0, 0, 5)
        val after = listOf(point(230.0, 735)) + walk(230.0, 735, 5)
        assertEquals(emptyList<TrackPause>(), TrackPauseDetector.detect(before + after))
    }

    @Test
    fun aStopFollowedByARecordingGapMergesIntoOnePause() {
        // 3 minutes sur place (index 5 à 11), puis la montre se met en pause : trou de 5 minutes
        // entre l'index 11 et l'index 12, 30 m plus loin (hors du rayon de la première source,
        // sous le seuil de la seconde). Les deux se touchent à l'index 11 et n'en font qu'une.
        val before = listOf(point(0.0, 0)) + walk(0.0, 0, 5) // index 0 à 5, 150 m, 135 s
        val stay = (1..6).map { k -> point(150.0 + (k % 2), 135L + 30L * k) } // index 6 à 11, 315 s
        val resumed = listOf(point(180.0, 615)) + walk(180.0, 615, 5) // index 12 à 17
        val pauses = TrackPauseDetector.detect(before + stay + resumed)

        assertEquals(listOf(TrackPause(5, 12, 480.0)), pauses)
    }

    @Test
    fun slowDriftIsNotAPause() {
        // 0,2 m/s (0,72 km/h), un point toutes les 10 s : deux points consécutifs ne sont qu'à
        // 2 m l'un de l'autre, mais le rayon de 15 m autour de n'importe quel point est quitté en
        // 80 s, sous les 120 s d'une pause. Un seuil de proche en proche y verrait un arrêt de
        // 10 minutes.
        val track = (0..60).map { k -> point(2.0 * k, 10L * k) }
        assertEquals(emptyList<TrackPause>(), TrackPauseDetector.detect(track))
    }

    @Test
    fun aPauseClosesOnItsLastTimestampedPoint() {
        // Le dernier point sur place n'a pas d'heure : la pause se ferme au point horodaté qui le
        // précède, le seul dont on connaisse l'instant.
        val before = listOf(point(0.0, 0)) + walk(0.0, 0, 5) // index 0 à 5
        val stay = (1..5).map { k -> point(150.0 + (k % 2), 135L + 30L * k) } + point(151.0, null) // index 6 à 11
        val after = walk(151.0, 330, 5)
        val pauses = TrackPauseDetector.detect(before + stay + after)

        assertEquals(listOf(TrackPause(5, 10, 150.0)), pauses)
    }

    @Test
    fun pausedSecondsBetweenSplitsAPauseAtTheCut() {
        val before = listOf(point(0.0, 0)) + walk(0.0, 0, 5)
        val stay = (1..10).map { k -> point(150.0 + (k % 3), 135L + 30L * k) }
        val after = walk(150.0, 435, 5)
        val track = before + stay + after
        val pauses = TrackPauseDetector.detect(track) // (5, 15), 300 s

        assertEquals(300.0, TrackPauseDetector.pausedSecondsBetween(track, pauses, 0, 20), 1e-9)
        // Coupure à l'index 9 (255 s) : 120 s avant, 180 s après, jamais comptées deux fois.
        assertEquals(120.0, TrackPauseDetector.pausedSecondsBetween(track, pauses, 0, 9), 1e-9)
        assertEquals(180.0, TrackPauseDetector.pausedSecondsBetween(track, pauses, 9, 20), 1e-9)
        // Intervalle qui ne touche la pause qu'en un point : rien.
        assertEquals(0.0, TrackPauseDetector.pausedSecondsBetween(track, pauses, 0, 5), 1e-9)
    }

    @Test
    fun slopeBandsFollowTheBoundsWithTheLowerBoundIncluded() {
        val p = AnalysisParameters.DEFAULT
        assertEquals(11, p.slopeBandCount)
        assertEquals(0, p.slopeBandOf(-30.0))
        assertEquals(1, p.slopeBandOf(-25.0))
        assertEquals(5, p.slopeBandOf(-2.0))
        assertEquals(5, p.slopeBandOf(0.0))
        assertEquals(6, p.slopeBandOf(2.0))
        assertEquals(9, p.slopeBandOf(24.9))
        assertEquals(10, p.slopeBandOf(25.0))
    }

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-06-01T08:00:00Z")
    }
}
