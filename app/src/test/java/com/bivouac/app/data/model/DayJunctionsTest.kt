package com.bivouac.app.data.model

import com.bivouac.app.data.model.TrackPoint
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
}
