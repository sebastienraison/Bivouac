package com.bivouac.app.data.model

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
}
