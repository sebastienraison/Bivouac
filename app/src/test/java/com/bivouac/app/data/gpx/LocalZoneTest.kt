package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// RIC-225 : fuseau horaire déduit des coordonnées (bibliothèque LatLongToTimezone embarquée).

class LocalZoneTest {

    private fun point(lat: Double, lon: Double, time: Instant? = null) =
        TrackPoint(latitude = lat, longitude = lon, elevationMeters = null, time = time)

    private val canigou = point(42.519, 2.457)
    private val chamonix = point(45.924, 6.869)
    private val barcelone = point(41.3874, 2.1686)
    private val quebec = point(46.8139, -71.2080)

    @Test
    fun `le Canigou et Chamonix sont en Europe Paris`() {
        assertEquals(ZoneId.of("Europe/Paris"), LocalZone.forPoint(canigou))
        assertEquals(ZoneId.of("Europe/Paris"), LocalZone.forPoint(chamonix))
    }

    @Test
    fun `Barcelone est en Europe Madrid`() {
        assertEquals(ZoneId.of("Europe/Madrid"), LocalZone.forPoint(barcelone))
    }

    @Test
    fun `Quebec est en America Toronto ou America Montreal`() {
        val zone = LocalZone.forPoint(quebec)
        assertTrue("fuseau rendu : $zone", zone == ZoneId.of("America/Toronto") || zone == ZoneId.of("America/Montreal"))
    }

    @Test
    fun `le plein ocean et les poles rendent un fuseau sans exception`() {
        listOf(0.0 to -30.0, -40.0 to -120.0, 90.0 to 0.0, -90.0 to 0.0, 0.0 to 180.0, 0.0 to -180.0).forEach { (lat, lon) ->
            assertNotNull("($lat, $lon)", LocalZone.forPoint(point(lat, lon)))
        }
    }

    @Test
    fun `des coordonnees hors bornes ou non finies ne levent pas d'exception`() {
        listOf(
            200.0 to 400.0,
            Double.NaN to Double.NaN,
            Double.POSITIVE_INFINITY to Double.NEGATIVE_INFINITY,
        ).forEach { (lat, lon) ->
            assertNotNull("($lat, $lon)", LocalZone.forPoint(point(lat, lon)))
        }
    }

    // --- BivouacDetector sans zoneFor imposé utilise ce fuseau ---------------------------------

    // 17 h 30 à 21 h 30 UTC un jour d'été : 19 h 30 à 23 h 30 à Paris (+2) : bivouac ;
    // 13 h 30 à 17 h 30 à Québec (-4) : pas de bivouac.
    private fun gapAt(base: TrackPoint) = listOf(
        base.copy(time = Instant.parse("2026-07-14T17:30:00Z")),
        base.copy(time = Instant.parse("2026-07-14T21:30:00Z")),
    )

    @Test
    fun `le detecteur par defaut trouve le bivouac au Canigou`() {
        val found = BivouacDetector.detect(gapAt(canigou)).single()
        assertEquals(ZoneId.of("Europe/Paris"), found.zone)
    }

    @Test
    fun `le detecteur par defaut ne trouve pas le meme trou a Quebec`() {
        assertTrue(BivouacDetector.detect(gapAt(quebec)).isEmpty())
    }
}
