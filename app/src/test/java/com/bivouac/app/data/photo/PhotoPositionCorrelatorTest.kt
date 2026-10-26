package com.bivouac.app.data.photo

import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// RIC-43 : voir PhotoPositionCorrelator pour le raisonnement (GPS puis horodatage puis rien).
// RIC-214 : un GPS trop loin de la trace retombe sur l'horodatage, à 2 minutes près.
class PhotoPositionCorrelatorTest {

    private val baseTime = Instant.parse("2026-06-12T08:00:00Z")

    // Cinq points espacés de 5 minutes chacun, longitude fixe pour simplifier : seule la
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

    // RIC-214 : un GPS trop loin de la trace ne suffit plus à écarter la photo si son heure tombe
    // pendant l'enregistrement (GPS faux, trace qui enregistrait à la même minute). Tolérance de
    // 2 minutes, resserrée par rapport aux 10 minutes du cas sans GPS.
    @Test
    fun gpsFarFromTheTrack_timeWithinOneMinuteOfAPoint_isPlacedByTime_andIsApproximate() {
        val position = PhotoPositionCorrelator.correlate(
            points,
            latitude = points[0].latitude + 0.1,
            longitude = points[0].longitude,
            takenAtMillis = points[2].time!!.plusSeconds(60).toEpochMilli(),
        )

        assertEquals(2, position.pointIndex)
        assertTrue("un placement par horodatage est toujours approximatif", position.approximate)
    }

    @Test
    fun gpsFarFromTheTrack_timeExactlyTwoMinutesFromAPoint_isStillPlaced() {
        // Borne incluse : 2 min pile après le dernier point (les points sont à 5 min les uns des autres).
        val position = PhotoPositionCorrelator.correlate(
            points,
            latitude = points[0].latitude + 0.1,
            longitude = points[0].longitude,
            takenAtMillis = points.last().time!!.plusSeconds(2 * 60).toEpochMilli(),
        )

        assertEquals(points.lastIndex, position.pointIndex)
        assertTrue(position.approximate)
    }

    @Test
    fun gpsFarFromTheTrack_timeThreeMinutesFromAnyPoint_yieldsNoPosition() {
        // 3 minutes après le dernier point, et rien après : plus proche point à 3 min, au-delà des
        // 2 minutes, bien que dans les 10 minutes du cas sans GPS (qui ne s'applique pas ici).
        val position = PhotoPositionCorrelator.correlate(
            points,
            latitude = points[0].latitude + 0.1,
            longitude = points[0].longitude,
            takenAtMillis = points.last().time!!.plusSeconds(3 * 60).toEpochMilli(),
        )

        assertNull(position.pointIndex)
        assertTrue(!position.approximate)
    }

    @Test
    fun gpsFarFromTheTrack_withoutTimestamp_yieldsNoPosition() {
        val position = PhotoPositionCorrelator.correlate(
            points,
            latitude = points[0].latitude + 0.1,
            longitude = points[0].longitude,
            takenAtMillis = null,
        )

        assertNull(position.pointIndex)
        assertTrue(!position.approximate)
    }

    @Test
    fun gpsFarFromTheTrack_timeTwentySevenMinutesOutside_yieldsNoPosition() {
        // Le cas à préserver : photo prise loin de la trace, bien hors de ses heures.
        val position = PhotoPositionCorrelator.correlate(
            points,
            // ~0,03 degré de longitude à 45 degrés de latitude = ~2,4 km à l'écart de la trace.
            latitude = points[0].latitude,
            longitude = points[0].longitude + 0.03,
            takenAtMillis = points.last().time!!.plusSeconds(27 * 60).toEpochMilli(),
        )

        assertNull(position.pointIndex)
    }

    @Test
    fun gpsNearTheTrack_stillWinsOverTime_andIsNeverApproximate() {
        // GPS proche : l'heure, même dans la tolérance resserrée d'un autre point, ne sert à rien.
        val position = PhotoPositionCorrelator.correlate(
            points,
            latitude = points[4].latitude,
            longitude = points[4].longitude,
            takenAtMillis = points[0].time!!.plusSeconds(30).toEpochMilli(),
        )

        assertEquals(4, position.pointIndex)
        assertTrue(!position.approximate)
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
    fun noGps_timestampNineMinutesFromTheNearestPoint_isStillPlaced() {
        // Sans GPS, la tolérance reste de 10 minutes (et non les 2 minutes du GPS contredit).
        val takenAt = points.last().time!!.plusSeconds(9 * 60).toEpochMilli()

        val position = PhotoPositionCorrelator.correlate(points, null, null, takenAt)

        assertEquals(points.lastIndex, position.pointIndex)
        assertTrue(position.approximate)
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
