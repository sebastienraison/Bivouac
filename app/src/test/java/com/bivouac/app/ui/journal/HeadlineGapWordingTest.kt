package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-146 lot 7 (brief Partie D.1) : choix de la chaîne d'écart du chiffre de tête (journal_analysis_gap_less/
 * _more/_same) selon le signe de l'écart réel moins estimé. Fonction pure, extraite de
 * AnalysisHeadlineHeader pour ce test.
 */
class HeadlineGapWordingTest {

    @Test
    fun uneRandoPlusCourteQueLEstimationEstMoins() {
        // 7h40 réel contre 7h55 estimé : -15 min.
        assertEquals(HeadlineGapWording.LESS, headlineGapWordingFor(-15))
    }

    @Test
    fun uneRandoPlusLongueQueLEstimationEstPlus() {
        assertEquals(HeadlineGapWording.MORE, headlineGapWordingFor(15))
    }

    @Test
    fun unEcartArrondiAZeroMinuteEstCommeLEstimation() {
        assertEquals(HeadlineGapWording.SAME, headlineGapWordingFor(0))
    }
}
