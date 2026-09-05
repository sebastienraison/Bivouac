package com.bivouac.app.data.gpx

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// RIC-163 : GpxParser.parse() n'était jusqu'ici exercé que par des tests JVM (Robolectric, voir
// LoggedTrackBackfillTest), qui tournent sur le JDK hôte et ne peuvent donc pas reproduire un
// NoSuchMethodError propre au runtime ART. Seul un test réellement instrumenté (Gradle Managed
// Device) engage jpx 3.2.1 contre la libcore d'un appareil donné : ici le point qui manquait pour
// couvrir le rapport F-Droid (Redmi Note 8T, Android 13 / API 33) : jpx appelle en interne
// Stream.toList() (Java 16), absent de la libcore avant API 34, d'où le plantage sur tout import
// en dessous de ce niveau malgré un minSdk de 26.
@RunWith(AndroidJUnit4::class)
class GpxParserInstrumentedTest {

    private val gpx = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="bivouac-instrumented-test">
  <trk><name>Trace test</name><trkseg>
    <trkpt lat="45.1885" lon="5.7245"><ele>1200.0</ele><time>2026-06-01T08:00:00Z</time></trkpt>
    <trkpt lat="45.1890" lon="5.7250"><ele>1215.0</ele><time>2026-06-01T08:05:00Z</time></trkpt>
    <trkpt lat="45.1895" lon="5.7260"><ele>1240.0</ele><time>2026-06-01T08:10:00Z</time></trkpt>
  </trkseg></trk>
</gpx>"""

    @Test
    fun parseRealGpxOnDeviceRuntime() {
        val track = GpxParser.parse(gpx.byteInputStream(Charsets.UTF_8))

        assertEquals(3, track.points.size)
        assertEquals("Trace test", track.name)
        assertEquals(45.1885, track.points.first().latitude, 1e-6)
    }
}
