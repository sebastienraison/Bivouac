package com.bivouac.app.journal

import org.junit.Assert.assertEquals
import org.junit.Test

// RIC-227 : points de bivouac d'une rando du Journal (carte de la vue détail, duplication vers la
// Planification) : jonctions entre jours et nuits restées à l'intérieur d'un jour.
class JournalBivouacPointsTest {

    @Test
    fun `jonctions seules, comme avant le lot 3`() {
        assertEquals(listOf(9, 19), JournalBivouacPoints.trackPointIndices(listOf(10, 10, 10), emptyList()))
    }

    @Test
    fun `une nuit interne s'ajoute aux jonctions, dans l'ordre de la trace`() {
        // Trois jours de 10 points, une nuit gardée à l'intérieur du deuxième (arrêt au point 14).
        assertEquals(listOf(9, 14, 19), JournalBivouacPoints.trackPointIndices(listOf(10, 10, 10), listOf(14)))
    }

    @Test
    fun `rando d'un seul fichier gardee entiere, ses nuits seules`() {
        assertEquals(listOf(4, 9), JournalBivouacPoints.trackPointIndices(listOf(13), listOf(9, 4)))
    }

    @Test
    fun `aucun bivouac`() {
        assertEquals(emptyList<Int>(), JournalBivouacPoints.trackPointIndices(listOf(13), emptyList()))
    }
}
