package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RIC-146 lot 8 : classe d'allure d'une phase ([timelinePaceClassFor], enveloppe de
 * [com.bivouac.app.data.gpx.AnalysisParameters.paceClassOf] sur `paceDeltaPercent`) et pastille
 * d'écart de la frise ([timelinePaceChipWordingFor]), à chaque frontière des bornes
 * (-25, -10, 10, 25, voir AnalysisParameters.PACE_CLASS_BOUNDS_PERCENT) et sans écart (`null`).
 */
class TimelinePaceChipWordingTest {

    @Test
    fun sansEcartLaClasseEstInconnue() {
        assertNull(timelinePaceClassFor(null))
    }

    @Test
    fun classeAChaqueFrontiere() {
        assertEquals(0, timelinePaceClassFor(-30))
        assertEquals(1, timelinePaceClassFor(-25)) // borne stricte : -25 n'est pas < -25
        assertEquals(2, timelinePaceClassFor(-10)) // borne stricte : -10 n'est pas < -10
        assertEquals(2, timelinePaceClassFor(0))
        assertEquals(2, timelinePaceClassFor(10)) // borne inclusive : 10 <= 10
        assertEquals(3, timelinePaceClassFor(11))
        assertEquals(3, timelinePaceClassFor(25)) // borne inclusive : 25 <= 25
        assertEquals(4, timelinePaceClassFor(26))
    }

    @Test
    fun pastilleSansReferenceSansEcart() {
        assertEquals(TimelinePaceChipWording.NO_REFERENCE, timelinePaceChipWordingFor(null))
    }

    @Test
    fun pastilleClasses0Et1PlusLent() {
        assertEquals(TimelinePaceChipWording.SLOWER, timelinePaceChipWordingFor(0))
        assertEquals(TimelinePaceChipWording.SLOWER, timelinePaceChipWordingFor(1))
    }

    @Test
    fun pastilleClasse2RythmeHabituel() {
        assertEquals(TimelinePaceChipWording.USUAL, timelinePaceChipWordingFor(2))
    }

    @Test
    fun pastilleClasses3Et4PlusRapide() {
        assertEquals(TimelinePaceChipWording.FASTER, timelinePaceChipWordingFor(3))
        assertEquals(TimelinePaceChipWording.FASTER, timelinePaceChipWordingFor(4))
    }
}
