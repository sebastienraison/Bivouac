package com.bivouac.app.ui.journal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RIC-146 lot 8 : présence du titre de jour de la frise selon le nombre de jours ([dayTitleVisible]). */
class TimelineDayTitleVisibleTest {

    @Test
    fun uneRandoSansJourNAPasDeTitre() {
        assertFalse(dayTitleVisible(0))
    }

    @Test
    fun uneRandoDUnSeulJourNAPasDeTitre() {
        assertFalse(dayTitleVisible(1))
    }

    @Test
    fun uneRandoDeDeuxJoursALeTitre() {
        assertTrue(dayTitleVisible(2))
    }

    @Test
    fun uneRandoDePlusieursJoursALeTitre() {
        assertTrue(dayTitleVisible(5))
    }
}
