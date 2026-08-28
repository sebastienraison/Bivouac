package com.bivouac.app.data.photo

import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// RIC-43 : voir PhotoPositionCorrelator pour le raisonnement (GPS puis horodatage puis rien).
class PhotoPositionCorrelatorTest {

    private val baseTime = Instant.parse("2026-06-12T08:00:00Z")

    // Cinq points espacés de 5 minutes chacun, longitude fixe pour simplifier — seule la
    // corrélation temporelle est sous test ici, pas la distance géographique.
    private val points = (0 until 5).map { i ->
        TrackPoint(
            latitude = 45.0 + i * 0.01,
            longitude = 6.0,
            elevationMeters = 1000.0,
            time = baseTime.plusSeconds(i * 300L),
        )
    }

    @Test
    fun gpsPresent_snapsToNearestPointByDistance_andIsNeverApproximate() {
        // Coordonnées collées au point d'index 2, horodatage volontairement incohérent (proche du
        // point 0) : le GPS doit l'emporter sans même regarder takenAtMillis.
        val position = PhotoPositionCorrelator.correlate(
            points,
            latitude = points[2].latitude,
            longitude = points[2].longitude,
            takenAtMillis = baseTime.toEpochMilli(),
        )

        assertEquals(2, position.pointIndex)
        assertTrue("un placement GPS n'est jamais approximatif", !position.approximate)
    }

    // Garde de distance : un GPS parfaitement valide mais qui n'est pas sur cette trace (photo
    // prise chez soi, sur la route, ou pendant une autre sortie du même appareil) ne doit plus
    // accrocher la photo au point le moins lointain. ~0,1 degré de latitude = ~11 km, très
    // au-delà de la borne de 500 m.
    @Test
    fun gpsFarFromTheTrack_yieldsNoPosition() {
        val position = PhotoPositionCorrelator.correlate(
            points,
            latitude = points[0].latitude + 0.1,
            longitude = points[0].longitude,
            takenAtMillis = null,
        )

        assertNull(position.pointIndex)
        assertTrue(!position.approximate)
    }

    // La borne ne doit pas rejeter un écart légitime : ~0,002 degré de latitude = ~220 m, ce que
    // peut produire un fix dégradé ou une photo prise depuis un belvédère à côté du sentier.
    @Test
    fun gpsSlightlyOffTheTrack_stillSnapsToTheNearestPoint() {
        val position = PhotoPositionCorrelator.correlate(
            points,
            latitude = points[2].latitude + 0.002,
            longitude = points[2].longitude,
            takenAtMillis = null,
        )

        assertEquals(2, position.pointIndex)
        assertTrue(!position.approximate)
    }

    // Un GPS valide mais hors trace dit que la photo n'a pas été prise dessus : l'horodatage, même
    // parfaitement dans la tolérance, ne doit pas servir de session de rattrapage.
    @Test
    fun gpsFarFromTheTrack_doesNotFallBackToTimeCorrelation() {
        val position = PhotoPositionCorrelator.correlate(
            points,
            latitude = points[0].latitude + 0.1,
            longitude = points[0].longitude,
            takenAtMillis = points[2].time!!.toEpochMilli(),
        )

        assertNull(position.pointIndex)
    }

    @Test
    fun noGps_timestampWithinTolerance_correlatesToNearestPointByTime_andIsApproximate() {
        // 2 minutes après le point d'index 3 (8h15, soit 8h17) : plus proche de lui (2 min) que du
        // point d'index 4 à 8h20 (3 min), et dans la tolérance de 10 minutes.
        val takenAt = points[3].time!!.plusSeconds(2 * 60).toEpochMilli()

        val position = PhotoPositionCorrelator.correlate(points, null, null, takenAt)

        assertEquals(3, position.pointIndex)
        assertTrue("un placement par horodatage est toujours approximatif", position.approximate)
    }

    @Test
    fun noGps_timestampBeyondTolerance_yieldsNoPosition() {
        // 11 minutes après le dernier point (8h20) : au-delà de la tolérance de 10 minutes, même
        // si c'est techniquement le point le plus proche dans le temps.
        val takenAt = points.last().time!!.plusSeconds(11 * 60).toEpochMilli()

        val position = PhotoPositionCorrelator.correlate(points, null, null, takenAt)

        assertNull(position.pointIndex)
        assertTrue(!position.approximate)
    }

    @Test
    fun neitherGpsNorTimestamp_yieldsNoPosition() {
        val position = PhotoPositionCorrelator.correlate(points, null, null, null)

        assertNull(position.pointIndex)
        assertTrue(!position.approximate)
    }

    @Test
    fun pointsWithoutTimeAreSkippedDuringTimeCorrelation() {
        val mixed = listOf(
            points[0].copy(time = null),
            points[1],
            points[2].copy(time = null),
        )
        val takenAt = points[1].time!!.plusSeconds(30).toEpochMilli()

        val position = PhotoPositionCorrelator.correlate(mixed, null, null, takenAt)

        assertEquals(1, position.pointIndex)
        assertTrue(position.approximate)
    }

    @Test
    fun emptyTrack_yieldsNoPositionRegardlessOfInputs() {
        val position = PhotoPositionCorrelator.correlate(emptyList(), 45.0, 6.0, baseTime.toEpochMilli())

        assertNull(position.pointIndex)
    }
}
