package com.bivouac.app.bilan

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressionAxisWidthTest {
    @Test
    fun sousLeMinimumLaColonneGardeSes34dp() {
        // Plus large libellé 20 dp + marge 6 dp = 26 dp, sous le minimum.
        assertEquals(34f, axisColumnWidth(listOf(10f, 20f, 5f), 6f), 0f)
    }

    @Test
    fun auDessusDuMinimumLaColonneSuitLePlusLargeDesTroisLibelles() {
        // « 3 853 » mesure environ 29 dp en labelSmall : 29 + 6 = 35 dp, au-dessus de 34.
        assertEquals(35f, axisColumnWidth(listOf(29f, 15f, 5f), 6f), 0f)
        assertEquals(46f, axisColumnWidth(listOf(10f, 40f, 20f), 6f), 0f)
    }

    @Test
    fun sansLibelleOnRendLeMinimum() {
        assertEquals(34f, axisColumnWidth(emptyList(), 6f), 0f)
    }
}
