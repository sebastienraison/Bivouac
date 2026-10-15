package com.bivouac.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RIC-229 : format de la colonne `logged_track_day.refusedBivouacStops` ([RefusedBivouacStops]). */
class RefusedBivouacStopsTest {

    @Test
    fun encodesAscendingWithoutDuplicatesAndNullWhenEmpty() {
        assertEquals("14,29", RefusedBivouacStops.encode(listOf(29, 14, 29)))
        assertEquals("0", RefusedBivouacStops.encode(listOf(0)))
        assertNull(RefusedBivouacStops.encode(emptyList()))
        assertNull(RefusedBivouacStops.encode(listOf(-3)))
    }

    @Test
    fun decodesWhatItEncodesAndToleratesNoiseWithoutFailing() {
        assertEquals(setOf(14, 29), RefusedBivouacStops.decode(RefusedBivouacStops.encode(setOf(29, 14))))
        assertTrue(RefusedBivouacStops.decode(null).isEmpty())
        assertTrue(RefusedBivouacStops.decode("").isEmpty())
        assertTrue(RefusedBivouacStops.decode("  ").isEmpty())
        assertEquals(setOf(3, 7), RefusedBivouacStops.decode(" 7, x ,3,-1,"))
    }

    @Test
    fun aDayRowReadsItsRefusals() {
        val day = LoggedTrackDayEntity(trackId = "t", dayIndex = 0, rawGpxFilePath = "p", refusedBivouacStops = "5,12")
        assertEquals(setOf(5, 12), day.refusedStops())
        assertTrue(day.copy(refusedBivouacStops = null).refusedStops().isEmpty())
    }
}
