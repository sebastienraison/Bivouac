package com.bivouac.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.model.TrackPoint
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-226 : [DayPoints], la seule lecture des points d'un jour du Journal. Fichier entier quand la
 * plage est nulle, plage du fichier sinon, liste vide (jamais d'exception) pour une plage
 * incohérente, et la concaténation des plages contiguës d'un même fichier redonne exactement ses
 * points. Robolectric pour le journal d'erreur (android.util.Log) et le stockage des fichiers.
 */
@RunWith(RobolectricTestRunner::class)
class DayPointsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        LoggedTrackGpxStore.dir(context).deleteRecursively()
    }

    private fun gpx(pointCount: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"><trk><name>t</name><trkseg>\n")
        val base = Instant.parse("2026-06-01T08:00:00Z")
        repeat(pointCount) { i ->
            append("<trkpt lat=\"${45.0 + i * 0.0003}\" lon=\"6.0\"><ele>${1000 + i}</ele><time>${base.plusSeconds(i * 30L)}</time></trkpt>\n")
        }
        append("</trkseg></trk></gpx>")
    }

    private fun allPoints(raw: String): List<TrackPoint> =
        raw.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }.points

    private fun day(first: Int?, last: Int?, path: String = "gpx/t-day0.gpx") = LoggedTrackDayEntity(
        trackId = "t",
        dayIndex = 0,
        rawGpxFilePath = path,
        firstPointIndex = first,
        lastPointIndex = last,
    )

    private fun writeFile(raw: String, path: String = "gpx/t-day0.gpx") {
        LoggedTrackGpxStore.dir(context).mkdirs()
        LoggedTrackGpxStore.resolve(context, path).writeText(raw, StandardCharsets.UTF_8)
    }

    @Test
    fun aNullRangeIsTheWholeFile() {
        val raw = gpx(10)
        writeFile(raw)
        assertEquals(allPoints(raw), DayPoints.parse(raw, day(null, null)))
        assertEquals(allPoints(raw), DayPoints.read(context, day(null, null)))
    }

    @Test
    fun aRangeIsThePointsBetweenBothBoundsIncluded() {
        val raw = gpx(10)
        writeFile(raw)
        val expected = allPoints(raw).subList(2, 6)
        assertEquals(expected, DayPoints.parse(raw, day(2, 5)))
        assertEquals(expected, DayPoints.read(context, day(2, 5)))
        // Bornes extrêmes : un seul point, et le fichier entier exprimé en plage.
        assertEquals(listOf(allPoints(raw)[9]), DayPoints.parse(raw, day(9, 9)))
        assertEquals(allPoints(raw), DayPoints.parse(raw, day(0, 9)))
    }

    @Test
    fun anInvalidRangeGivesNoPointAndNeverThrows() {
        val raw = gpx(10)
        assertTrue("inversée", DayPoints.parse(raw, day(6, 2)).isEmpty())
        assertTrue("au-delà du dernier point", DayPoints.parse(raw, day(5, 10)).isEmpty())
        assertTrue("borne négative", DayPoints.parse(raw, day(-1, 4)).isEmpty())
        assertTrue("seule la première borne", DayPoints.parse(raw, day(3, null)).isEmpty())
        assertTrue("seule la dernière borne", DayPoints.parse(raw, day(null, 3)).isEmpty())
    }

    @Test
    fun contiguousRangesOfOneFileConcatenateToTheWholeFile() {
        val raw = gpx(12)
        writeFile(raw)
        val days = listOf(day(0, 4), day(5, 8), day(9, 11))
        val read = DayPoints.readAll(context, days)
        assertEquals(listOf(5, 4, 3), read.map { it.size })
        assertEquals(allPoints(raw), read.flatten())
    }

    @Test
    fun readAllMixesWholeFileDaysAndRanges() {
        val first = gpx(6)
        val second = gpx(8)
        writeFile(first, "gpx/t-day0.gpx")
        writeFile(second, "gpx/t-day1.gpx")
        val read = DayPoints.readAll(
            context,
            listOf(day(null, null, "gpx/t-day0.gpx"), day(0, 2, "gpx/t-day1.gpx"), day(3, 7, "gpx/t-day1.gpx")),
        )
        assertEquals(allPoints(first), read[0])
        assertEquals(allPoints(second), read[1] + read[2])
    }
}
