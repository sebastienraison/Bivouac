package com.bivouac.app.data.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DayJunctionsTest {

    @Test
    fun `a single day has no junction, so no bivouac`() {
        assertEquals(emptyList<Int>(), DayJunctions.bivouacTrackPointIndices(listOf(120)))
    }

    @Test
    fun `no day at all has no junction`() {
        assertEquals(emptyList<Int>(), DayJunctions.bivouacTrackPointIndices(emptyList()))
    }

    @Test
    fun `two days put one bivouac on the last point of day 1`() {
        // Jour 1 = index 0..99, jour 2 = index 100..249 : la jonction est l'index 99.
        assertEquals(listOf(99), DayJunctions.bivouacTrackPointIndices(listOf(100, 150)))
    }

    @Test
    fun `each junction of a four-day trek becomes a bivouac`() {
        // Trois nuits pour quatre jours, jamais une quatrième au point d'arrivée.
        assertEquals(listOf(9, 29, 59), DayJunctions.bivouacTrackPointIndices(listOf(10, 20, 30, 40)))
    }

    @Test
    fun `the arrival point is never a bivouac`() {
        val junctions = DayJunctions.bivouacTrackPointIndices(listOf(10, 20))
        assertEquals(listOf(9), junctions)
        // 29 = dernier point de la trace concaténée (10 + 20 - 1), il ne doit pas y figurer.
        assertEquals(false, junctions.contains(29))
    }

    @Test
    fun `an empty day produces neither a duplicate junction nor a negative index`() {
        assertEquals(listOf(2), DayJunctions.bivouacTrackPointIndices(listOf(3, 0, 4)))
        assertEquals(emptyList<Int>(), DayJunctions.bivouacTrackPointIndices(listOf(0, 5)))
    }

    // Une jonction n'est une coupure d'enregistrement que si le lendemain ne repart pas d'où la
    // veille s'est arrêtée : sinon la distance ne doit pas être retranchée ni le tracé rompu.
    private fun pointAt(lat: Double, lon: Double) = TrackPoint(latitude = lat, longitude = lon, elevationMeters = 1000.0, time = null)

    @Test
    fun `une jonction sur place n'est pas une coupure`() {
        // Quelques metres d'ecart : derive GPS au camp, pas une interruption.
        val points = listOf(
            pointAt(45.1000, 5.7000),
            pointAt(45.1001, 5.7000),
            pointAt(45.1002, 5.7000),
        )
        assertEquals(emptySet<Int>(), DayJunctions.recordingGaps(points, listOf(1)))
    }

    @Test
    fun `une jonction distante est une coupure`() {
        // La jonction est l'index 1 : l'ecart qui compte est celui entre les points 1 et 2, soit
        // ici environ 1,1 km entre l'arrivee du jour 1 et le depart du jour 2.
        val points = listOf(
            pointAt(45.1000, 5.7000),
            pointAt(45.1001, 5.7000),
            pointAt(45.1100, 5.7000),
        )
        assertEquals(setOf(1), DayJunctions.recordingGaps(points, listOf(1)))
    }

    @Test
    fun `une jonction en fin de trace ne produit pas de coupure`() {
        val points = listOf(pointAt(45.1000, 5.7000), pointAt(45.1100, 5.7000))
        assertEquals(emptySet<Int>(), DayJunctions.recordingGaps(points, listOf(1)))
    }

    @Test
    fun `sans jonction il n'y a rien a couper`() {
        val points = listOf(pointAt(45.1000, 5.7000), pointAt(45.5000, 5.7000))
        assertEquals(emptySet<Int>(), DayJunctions.recordingGaps(points, emptyList()))
    }

    // --- RIC-223 : coupures de la Planification par la règle du bivouac ---
    //
    // Traces sur un méridien (la distance entre deux points vaut l'écart de leur abscisse), fuseau
    // imposé à Europe/Paris (UTC+2 en juillet) pour que "20 h 30 locale" ne dépende pas de la machine.

    private val paris: (TrackPoint) -> ZoneId = { ZoneId.of("Europe/Paris") }
    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)

    private fun meridianPoint(northMeters: Double, time: Instant?) = TrackPoint(
        latitude = 45.0 + northMeters * degPerMeter,
        longitude = 6.0,
        elevationMeters = 1500.0,
        time = time,
    )

    /**
     * 11 points (index 0 à 10) espacés de [stepMeters]. Si [times] est fourni, il donne l'heure de
     * chacun ; [gapAfter] (index) ajoute [gapMeters] (négatif pour rapprocher) entre ce point et le
     * suivant et décale les abscisses des suivants d'autant.
     */
    private fun trace(
        stepMeters: Double,
        times: List<Instant>? = null,
        gapAfter: Int? = null,
        gapMeters: Double = 0.0,
    ): List<TrackPoint> {
        var north = 0.0
        return (0..10).map { i ->
            if (i > 0) north += stepMeters + if (gapAfter == i - 1) gapMeters else 0.0
            meridianPoint(north, times?.get(i))
        }
    }

    @Test
    fun `planification - un itineraire sans heures n'a jamais de coupure meme a points espaces de 141 m`() {
        val points = trace(stepMeters = 141.0)
        assertEquals(emptySet<Int>(), DayJunctions.planificationSeriesBreaks(points, listOf(5), paris))
        assertEquals(emptySet<Int>(), DayJunctions.planificationSeriesBreaks(points, listOf(1, 5, 9), paris))
    }

    @Test
    fun `planification - une trace horodatee continue a points espaces n'a pas de coupure`() {
        // 2 min entre points, reprise à 141 m : le trou ne dure pas 4 h.
        val start = Instant.parse("2026-07-01T08:00:00Z")
        val times = (0..10).map { start.plusSeconds(120L * it) }
        val points = trace(stepMeters = 141.0, times = times)
        assertEquals(emptySet<Int>(), DayJunctions.planificationSeriesBreaks(points, listOf(5), paris))
    }

    @Test
    fun `planification - une nuit de 20 h 30 a 7 h avec reprise a 2 km est une coupure`() {
        // 20 h 30 locale = 18:30Z ; reprise à 7 h locale = 05:00Z le lendemain : 10 h 30.
        val points = trace(
            stepMeters = 141.0,
            times = nightTimes(stopAt = Instant.parse("2026-07-01T18:30:00Z"), resumeAt = Instant.parse("2026-07-02T05:00:00Z")),
            gapAfter = 5,
            gapMeters = 2_000.0,
        )
        assertEquals(setOf(5), DayJunctions.planificationSeriesBreaks(points, listOf(5), paris))
        // Un bivouac posé ailleurs sur la même trace n'est pas une coupure.
        assertEquals(setOf(5), DayJunctions.planificationSeriesBreaks(points, listOf(2, 5, 8), paris))
    }

    @Test
    fun `planification - la meme nuit avec reprise a 30 m est aussi une coupure car la distance ne decide plus`() {
        val points = trace(
            stepMeters = 141.0,
            times = nightTimes(stopAt = Instant.parse("2026-07-01T18:30:00Z"), resumeAt = Instant.parse("2026-07-02T05:00:00Z")),
            gapAfter = 5,
            gapMeters = -111.0,
        )
        assertEquals(setOf(5), DayJunctions.planificationSeriesBreaks(points, listOf(5), paris))
    }

    @Test
    fun `planification - une sieste de 11 h a 16 h n'est pas une coupure`() {
        // 11 h locale = 09:00Z, 16 h locale = 14:00Z : 5 h, mais hors de la fenêtre 19 h-5 h.
        val points = trace(
            stepMeters = 141.0,
            times = nightTimes(stopAt = Instant.parse("2026-07-01T09:00:00Z"), resumeAt = Instant.parse("2026-07-01T14:00:00Z")),
            gapAfter = 5,
            gapMeters = 2_000.0,
        )
        assertEquals(emptySet<Int>(), DayJunctions.planificationSeriesBreaks(points, listOf(5), paris))
    }

    @Test
    fun `planification - un bivouac sur le dernier point ou hors de la trace ne produit ni coupure ni exception`() {
        val points = trace(
            stepMeters = 141.0,
            times = nightTimes(stopAt = Instant.parse("2026-07-01T18:30:00Z"), resumeAt = Instant.parse("2026-07-02T05:00:00Z")),
            gapAfter = 5,
            gapMeters = 2_000.0,
        )
        assertEquals(emptySet<Int>(), DayJunctions.planificationSeriesBreaks(points, listOf(points.lastIndex), paris))
        assertEquals(emptySet<Int>(), DayJunctions.planificationSeriesBreaks(points, listOf(points.size + 3, -1), paris))
        assertEquals(emptySet<Int>(), DayJunctions.planificationSeriesBreaks(points, emptyList(), paris))
        assertEquals(emptySet<Int>(), DayJunctions.planificationSeriesBreaks(emptyList(), listOf(0), paris))
    }

    @Test
    fun `planification - sans fuseau fourni, le fuseau des coordonnees est utilise`() {
        // 45 N, 6 E : Europe/Paris. Même nuit qu'au-dessus, sans passer par zoneFor.
        val points = trace(
            stepMeters = 141.0,
            times = nightTimes(stopAt = Instant.parse("2026-07-01T18:30:00Z"), resumeAt = Instant.parse("2026-07-02T05:00:00Z")),
            gapAfter = 5,
            gapMeters = 2_000.0,
        )
        assertEquals(setOf(5), DayJunctions.planificationSeriesBreaks(points, listOf(5)))
    }

    /** Un point toutes les 2 min, arrêt à [stopAt] sur l'index 5, reprise à [resumeAt] sur le 6. */
    private fun nightTimes(stopAt: Instant, resumeAt: Instant): List<Instant> =
        (0..10).map { i ->
            if (i <= 5) stopAt.minusSeconds(120L * (5 - i)) else resumeAt.plusSeconds(120L * (i - 6))
        }
}
