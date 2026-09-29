package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-212 (brief Partie B) : [timelineDayTitleWithLineBreakHints] pose des espaces insécables pour
 * qu'une coupure de ligne à 360 points ne tombe jamais qu'entre la date et la distance, jamais à
 * l'intérieur de "12,4 km · 6h10". Fonction pure : ne fait que retoucher les séparateurs " · " du
 * texte déjà formaté, ne connaît ni la locale ni les valeurs elles-mêmes.
 */
class TimelineDayTitleLineBreakHintsTest {

    @Test
    fun leSeparateurEntreDistanceEtDureeDevientInsecable() {
        val formatted = "Jour 1 · mercredi 1 juillet · 12,4 km · 6h10"
        val result = timelineDayTitleWithLineBreakHints(formatted)
        assertTrue(result.contains("12,4 km · 6h10"))
    }

    @Test
    fun leSeparateurEntreLaDateEtLaDistanceResteUnEspaceOrdinaireCoupable() {
        // Seul point de coupure accepté par le brief : entre la date et la distance.
        val formatted = "Jour 1 · mercredi 1 juillet · 12,4 km · 6h10"
        val result = timelineDayTitleWithLineBreakHints(formatted)
        assertTrue(result.contains("mercredi 1 juillet · 12,4 km"))
    }

    @Test
    fun fonctionneAvecUneDureeAnglaisePorteuseDUnEspaceInterne() {
        // "5h 32m" (anglais) : l'appelant l'a déjà rendue insécable en interne avant de la passer
        // en paramètre (voir AnalysisTimelineSection) ; cette fonction n'a qu'à protéger le
        // séparateur ENTRE distance et durée, pas l'intérieur de la durée elle-même.
        val formatted = "Day 1 · Wednesday, July 1 · 12.4 km · 5h 32m"
        val result = timelineDayTitleWithLineBreakHints(formatted)
        assertEquals("Day 1 · Wednesday, July 1 · 12.4 km · 5h 32m", result)
    }

    @Test
    fun replisIdentitaireSiLeTexteNaPasExactementQuatreSegments() {
        val formatted = "texte inattendu sans le bon nombre de separateurs"
        assertEquals(formatted, timelineDayTitleWithLineBreakHints(formatted))
    }
}
