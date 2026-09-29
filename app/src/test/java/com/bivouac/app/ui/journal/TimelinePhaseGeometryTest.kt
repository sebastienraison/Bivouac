package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-146 lot 8 : hauteur d'une phase ([phaseHeightDp]) et d'une pause ([pauseHeightDp]) de la
 * frise, de part et d'autre de leur plancher respectif (conception 2 section 5.4, constantes
 * regroupées dans [TimelineLayout]).
 */
class TimelinePhaseGeometryTest {

    @Test
    fun phaseSousLePlancherResteAuPlancher() {
        // 10 min à 1,56 dp/min = 15,6 dp, sous le plancher de 48 dp.
        assertEquals(48f, phaseHeightDp(10 * 60.0), 0.001f)
    }

    @Test
    fun phaseSansAucuneDureeResteAuPlancher() {
        assertEquals(48f, phaseHeightDp(0.0), 0.001f)
    }

    @Test
    fun phaseAuDelaDuPlancherSuitLaDuree() {
        // 40 min à 1,56 dp/min = 62,4 dp, au-dessus du plancher.
        assertEquals(62.4f, phaseHeightDp(40 * 60.0), 0.01f)
    }

    @Test
    fun pauseSousLePlancherResteAuPlancher() {
        // 5 min à 1,56 dp/min = 7,8 dp, sous le plancher de 30 dp.
        assertEquals(30f, pauseHeightDp(5 * 60.0), 0.001f)
    }

    @Test
    fun pauseAuDelaDuPlancherSuitLaDuree() {
        // 25 min à 1,56 dp/min = 39 dp, au-dessus du plancher de 30 dp.
        assertEquals(39f, pauseHeightDp(25 * 60.0), 0.01f)
    }
}
