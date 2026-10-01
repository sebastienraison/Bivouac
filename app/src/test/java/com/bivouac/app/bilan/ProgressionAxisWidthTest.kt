package com.bivouac.app.bilan

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressionAxisWidthTest {
    @Test
    fun laColonneSuitLePlusLargeDesTroisLibelles() {
        assertEquals(36f, axisColumnWidth(listOf(10f, 30f, 20f), 6f), 0f)
    }

    @Test
    fun uneValeurAQuatreChiffresElargitLaColonneAuDelaDeLAncienne34dp() {
        // « 3 853 » mesure environ 29 dp en labelSmall : l'ancienne largeur fixe (34 dp, 28 utiles) coupait.
        assertEquals(35f, axisColumnWidth(listOf(29f, 15f, 5f), 6f), 0f)
    }

    @Test
    fun sansLibelleOnGardeLaMarge() {
        assertEquals(6f, axisColumnWidth(emptyList(), 6f), 0f)
    }
}
