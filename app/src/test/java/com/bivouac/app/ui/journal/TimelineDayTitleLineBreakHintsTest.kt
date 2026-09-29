package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-212 (troisième passe) : [timelineDayFiguresLine] protège la seconde ligne du titre de jour
 * ("15,7 km · 7h04") de toute coupure, en posant des espaces insécables (U+00A0) à la place des
 * espaces ordinaires. Fonction pure : ne connaît ni la locale ni les valeurs, ne change aucun mot.
 * Les espaces insécables sont écrits en échappement Unicode, jamais tapés tels quels.
 */
class TimelineDayTitleLineBreakHintsTest {

    @Test
    fun laLigneFrancaiseNeContientAucunEspaceOrdinaire() {
        val result = timelineDayFiguresLine("15,7 km · 7h04")
        assertFalse(result.contains(' '))
        assertEquals("15,7\u00A0km\u00A0·\u00A07h04", result)
    }

    @Test
    fun laLigneAnglaiseNeContientAucunEspaceOrdinaireMemeAvecUneDureeAEspace() {
        val result = timelineDayFiguresLine("15.7 km · 7h 04m")
        assertFalse(result.contains(' '))
        assertEquals("15.7\u00A0km\u00A0·\u00A07h\u00A004m", result)
    }

    @Test
    fun leTexteVisibleResteInchangeAuxEspacesPres() {
        val formatted = "15,7 km · 7h04"
        val result = timelineDayFiguresLine(formatted)
        assertEquals(formatted, result.replace('\u00A0', ' '))
    }

    @Test
    fun uneLigneDejaInsecableResteIdentique() {
        val formatted = "15.7\u00A0km · 7h\u00A004m"
        val result = timelineDayFiguresLine(formatted)
        assertEquals("15.7\u00A0km\u00A0·\u00A07h\u00A004m", result)
        assertTrue(result.count { it == '\u00A0' } == 4)
    }
}
