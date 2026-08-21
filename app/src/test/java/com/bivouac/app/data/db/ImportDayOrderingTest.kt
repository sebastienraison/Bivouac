package com.bivouac.app.data.db

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportDayOrderingTest {

    private fun at(iso: String): Instant = Instant.parse(iso)

    @Test
    fun `orders days by first GPX timestamp, not by selection order`() {
        val order = ImportDayOrdering.orderIndices(
            listOf(
                at("2025-07-14T06:12:00Z"), // sélectionné en 1er, mais c'est le jour 2
                at("2025-07-13T05:58:00Z"),
                at("2025-07-15T07:03:00Z"),
            ),
        )
        assertEquals(listOf(1, 0, 2), order)
    }

    @Test
    fun `keeps a single file as day 1`() {
        assertEquals(listOf(0), ImportDayOrdering.orderIndices(listOf(at("2025-07-13T05:58:00Z"))))
    }

    @Test
    fun `already chronological selection is left untouched`() {
        val order = ImportDayOrdering.orderIndices(
            listOf(at("2025-07-13T05:58:00Z"), at("2025-07-14T06:12:00Z"), at("2025-07-15T07:03:00Z")),
        )
        assertEquals(listOf(0, 1, 2), order)
    }

    @Test
    fun `identical timestamps keep their selection order`() {
        val same = at("2025-07-13T05:58:00Z")
        assertEquals(listOf(0, 1, 2), ImportDayOrdering.orderIndices(listOf(same, same, same)))
    }

    @Test
    fun `a single file without timestamp falls the whole batch back to selection order`() {
        val order = ImportDayOrdering.orderIndices(
            listOf(at("2025-07-15T07:03:00Z"), null, at("2025-07-13T05:58:00Z")),
        )
        assertEquals(listOf(0, 1, 2), order)
    }

    @Test
    fun `a batch without any timestamp keeps selection order`() {
        assertEquals(listOf(0, 1), ImportDayOrdering.orderIndices(listOf(null, null)))
    }

    @Test
    fun `an empty selection orders nothing`() {
        assertEquals(emptyList<Int>(), ImportDayOrdering.orderIndices(emptyList()))
    }
}
