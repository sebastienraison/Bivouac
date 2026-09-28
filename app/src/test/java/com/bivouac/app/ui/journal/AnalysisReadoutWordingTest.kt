package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-146 lot 4 (brief §7) : la formule de la ligne de lecture choisie selon la classe d'allure du
 * tronçon sous le doigt (conception section 5.4, classes 0 à 4, classe 2 = milieu = rythme
 * habituel). Fonction pure, extraite de AnalysisReadoutLine pour ce test.
 */
class AnalysisReadoutWordingTest {

    @Test
    fun uneClasseNulleEstUnTronconAlArret() {
        assertEquals(ReadoutPaceWording.STOPPED, readoutPaceWordingFor(null))
    }

    @Test
    fun lesClassesZeroEtUnSontPlusLentes() {
        assertEquals(ReadoutPaceWording.SLOWER, readoutPaceWordingFor(0))
        assertEquals(ReadoutPaceWording.SLOWER, readoutPaceWordingFor(1))
    }

    @Test
    fun laClasseDuMilieuEstLeRythmeHabituel() {
        assertEquals(ReadoutPaceWording.USUAL, readoutPaceWordingFor(2))
    }

    @Test
    fun lesClassesTroisEtQuatreSontPlusRapides() {
        assertEquals(ReadoutPaceWording.FASTER, readoutPaceWordingFor(3))
        assertEquals(ReadoutPaceWording.FASTER, readoutPaceWordingFor(4))
    }
}
