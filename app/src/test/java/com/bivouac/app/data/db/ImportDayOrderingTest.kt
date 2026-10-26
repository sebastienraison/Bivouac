package com.bivouac.app.data.db

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDayOrderingTest {

    private fun at(iso: String): Instant = Instant.parse(iso)

    // Noms sans importance pour les lots entièrement horodatés : ils ne doivent rien changer.
    private fun anyNames(count: Int): List<String> = List(count) { "fichier-${count - it}.gpx" }

    @Test
    fun `orders days by first GPX timestamp, not by selection order`() {
        val order = ImportDayOrdering.orderIndices(
            listOf(
                at("2025-07-14T06:12:00Z"), // sélectionné en 1er, mais c'est le jour 2
                at("2025-07-13T05:58:00Z"),
                at("2025-07-15T07:03:00Z"),
            ),
            anyNames(3),
        )
        assertEquals(listOf(1, 0, 2), order)
    }

    @Test
    fun `keeps a single file as day 1`() {
        assertEquals(listOf(0), ImportDayOrdering.orderIndices(listOf(at("2025-07-13T05:58:00Z")), anyNames(1)))
    }

    @Test
    fun `already chronological selection is left untouched`() {
        val order = ImportDayOrdering.orderIndices(
            listOf(at("2025-07-13T05:58:00Z"), at("2025-07-14T06:12:00Z"), at("2025-07-15T07:03:00Z")),
            anyNames(3),
        )
        assertEquals(listOf(0, 1, 2), order)
    }

    @Test
    fun `identical timestamps keep their selection order`() {
        val same = at("2025-07-13T05:58:00Z")
        assertEquals(listOf(0, 1, 2), ImportDayOrdering.orderIndices(listOf(same, same, same), anyNames(3)))
    }

    // --- RIC-101 : un lot où un fichier au moins n'a pas d'horodatage --------------------------------

    @Test
    fun `fully timestamped batch ignores file names`() {
        // Les noms diraient 0, 1, 2 ; les horodatages disent 2, 0, 1 : les horodatages l'emportent.
        val order = ImportDayOrdering.orderIndices(
            listOf(at("2025-07-14T06:12:00Z"), at("2025-07-15T07:03:00Z"), at("2025-07-13T05:58:00Z")),
            listOf("jour-1.gpx", "jour-2.gpx", "jour-3.gpx"),
        )
        assertEquals(listOf(2, 0, 1), order)
    }

    @Test
    fun `a batch with one file without timestamp is sorted by file name`() {
        val order = ImportDayOrdering.orderIndices(
            listOf(at("2025-07-15T07:03:00Z"), null, at("2025-07-13T05:58:00Z")),
            listOf("etape-c.gpx", "etape-a.gpx", "etape-b.gpx"),
        )
        assertEquals(listOf(1, 2, 0), order)
    }

    @Test
    fun `a batch without any timestamp is sorted by file name`() {
        assertEquals(
            listOf(1, 0),
            ImportDayOrdering.orderIndices(listOf(null, null), listOf("b.gpx", "a.gpx")),
        )
    }

    @Test
    fun `jour-10 comes after jour-2`() {
        val order = ImportDayOrdering.orderIndices(
            listOf(null, null, null),
            listOf("jour-10.gpx", "jour-2.gpx", "jour-1.gpx"),
        )
        assertEquals(listOf(2, 1, 0), order)
    }

    @Test
    fun `file name case is ignored`() {
        val order = ImportDayOrdering.orderIndices(
            listOf(null, null, null),
            listOf("Jour-3.gpx", "jour-1.gpx", "JOUR-2.gpx"),
        )
        assertEquals(listOf(1, 2, 0), order)
    }

    @Test
    fun `equal names keep their selection order`() {
        val order = ImportDayOrdering.orderIndices(
            listOf(null, null, null, null),
            listOf("trace.gpx", "a.gpx", "TRACE.gpx", "trace.gpx"),
        )
        // « a » d'abord, puis les trois « trace » dans l'ordre de sélection (casse ignorée).
        assertEquals(listOf(1, 0, 2, 3), order)
    }

    @Test
    fun `files without a name sort first and keep their selection order among themselves`() {
        val order = ImportDayOrdering.orderIndices(
            listOf(null, null, null),
            listOf("jour-1.gpx", "", ""),
        )
        assertEquals(listOf(1, 2, 0), order)
    }

    @Test
    fun `mismatched names and timestamps are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportDayOrdering.orderIndices(listOf(null, null), listOf("a.gpx"))
        }
    }

    // --- naturalCompare ------------------------------------------------------------------------

    @Test
    fun `natural compare orders digit runs by numeric value`() {
        assertTrue(ImportDayOrdering.naturalCompare("jour-2", "jour-10") < 0)
        assertTrue(ImportDayOrdering.naturalCompare("jour-10", "jour-2") > 0)
        assertTrue(ImportDayOrdering.naturalCompare("a9b", "a10b") < 0)
        assertTrue(ImportDayOrdering.naturalCompare("jour-2-b", "jour-2-a") > 0)
    }

    @Test
    fun `natural compare treats leading zeros as equal in value`() {
        assertEquals(0, ImportDayOrdering.naturalCompare("jour-01", "jour-1"))
        assertTrue(ImportDayOrdering.naturalCompare("jour-02", "jour-10") < 0)
    }

    @Test
    fun `natural compare handles digit runs too long for a Long`() {
        val big = "9".repeat(30)
        val bigger = "1" + "0".repeat(30)
        assertTrue(ImportDayOrdering.naturalCompare("x$big", "x$bigger") < 0)
    }

    @Test
    fun `natural compare puts a prefix before the longer name and ignores case`() {
        assertTrue(ImportDayOrdering.naturalCompare("jour", "jour-1") < 0)
        assertEquals(0, ImportDayOrdering.naturalCompare("Jour-1.GPX", "jour-1.gpx"))
        assertEquals(0, ImportDayOrdering.naturalCompare("", ""))
        assertTrue(ImportDayOrdering.naturalCompare("", "a") < 0)
    }

    @Test
    fun `an empty selection orders nothing`() {
        assertEquals(emptyList<Int>(), ImportDayOrdering.orderIndices(emptyList(), emptyList()))
    }
}
