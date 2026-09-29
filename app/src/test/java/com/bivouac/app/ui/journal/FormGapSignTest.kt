package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-146 lot 7 (brief Partie D.5) : signe de l'écart du tableau "Forme du jour" (réel moins
 * estimé, en secondes). Fonction pure, extraite de FormTable pour ce test.
 */
class FormGapSignTest {

    @Test
    fun unEcartNegatifEstMoins() {
        // Réel plus court que l'estimation (ex. Durée 7h40 réel contre 7h55 estimé, -15 min).
        assertEquals(FormGapSign.MINUS, formGapSignFor(-900.0))
    }

    @Test
    fun unEcartPositifEstPlus() {
        // Réel plus long que l'estimation (ex. Marche 6h09 réel contre 6h04 estimé, +5 min).
        assertEquals(FormGapSign.PLUS, formGapSignFor(300.0))
    }

    @Test
    fun unEcartNulEstPlusParConvention() {
        // Aucun effet visible (la magnitude affichée vaut alors "0 min") : choix arbitraire, mais
        // stable, pour éviter un signe qui change entre deux calculs identiques.
        assertEquals(FormGapSign.PLUS, formGapSignFor(0.0))
    }
}
