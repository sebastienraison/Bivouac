package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-212 (seconde passe) : géométrie du trait de liaison de la nuit ([nightLinkSpanFor]) et hauteur
 * fixe du bloc bivouac. Le dessin lui-même (Canvas) se vérifie à l'écran ; ce qui se teste ici est
 * la propriété qui garantit l'absence de trou : les portions se raccordent bord à bord.
 */
class TimelineNightLinkTest {

    @Test
    fun uneLignePleineFaitTraverserLeTraitSurToutSaHauteur() {
        assertEquals(NightLinkSpan(0f, 96f), nightLinkSpanFor(NightLinkPortion.FULL, 96f))
    }

    @Test
    fun laLigneArriveeNePorteQueLaMoitieBasse() {
        // Le trait part du centre du rond de "Arrivée", pas du haut de la ligne.
        assertEquals(NightLinkSpan(20f, 40f), nightLinkSpanFor(NightLinkPortion.LOWER_HALF, 40f))
    }

    @Test
    fun laLigneDepartNePorteQueLaMoitieHaute() {
        // Le trait s'arrête au centre du rond de "Départ", pas au bas de la ligne.
        assertEquals(NightLinkSpan(0f, 20f), nightLinkSpanFor(NightLinkPortion.UPPER_HALF, 40f))
    }

    @Test
    fun uneLigneSansHauteurNeDonneAucuneLongueur() {
        for (portion in NightLinkPortion.values()) {
            val span = nightLinkSpanFor(portion, 0f)
            assertEquals(0f, span.endPx - span.startPx, 0f)
        }
    }

    @Test
    fun lesPortionsEmpilesSeRaccordentSansTrouNiRecouvrementQuellesQueSoientLesHauteurs() {
        // Arrivée (basse), note de hauteur variable, espace, bloc bivouac, espace, titre sur une,
        // deux ou trois lignes, Départ (haute) : on empile les hauteurs et on vérifie que la somme
        // des longueurs dessinées égale la distance entre les deux centres des ronds.
        for (noteHeight in listOf(0f, 33f, 61f)) {
            for (titleHeight in listOf(18f, 36f, 54f)) {
                val arrivalHeight = 17f
                val departureHeight = 17f
                val heights = listOf(
                    NightLinkPortion.LOWER_HALF to arrivalHeight,
                    NightLinkPortion.FULL to noteHeight,
                    NightLinkPortion.FULL to 20f,
                    NightLinkPortion.FULL to TimelineLayout.BIVOUAC_ROW_HEIGHT_DP,
                    NightLinkPortion.FULL to 20f,
                    NightLinkPortion.FULL to titleHeight + 8f,
                    NightLinkPortion.UPPER_HALF to departureHeight,
                )
                val drawn = heights.sumOf { (portion, h) -> nightLinkSpanFor(portion, h).let { (it.endPx - it.startPx).toDouble() } }
                val centerToCenter = heights.sumOf { it.second.toDouble() } - arrivalHeight / 2.0 - departureHeight / 2.0
                assertEquals(centerToCenter, drawn, 0.001)
            }
        }
    }

    @Test
    fun laHauteurDuBlocBivouacEstLePlancherDUnePhase() {
        assertEquals(48f, TimelineLayout.BIVOUAC_ROW_HEIGHT_DP, 0f)
        assertEquals(TimelineLayout.PHASE_MIN_HEIGHT_DP, TimelineLayout.BIVOUAC_ROW_HEIGHT_DP, 0f)
    }
}
