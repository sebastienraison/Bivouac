package com.bivouac.app.data.gpx

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-146 lot 2 : bornes des trois classes de couleur (AnalysisParameters.paceClassOf/slopeClassOf/
 * speedClassOf) et centres de bande (slopeBandCenter), portage exact de reference_lot2.py (voir sa
 * kdoc pour les définitions) et de analyse.py (`band_center`). Chaque frontière est testée pile
 * dessus et de part et d'autre : les classes d'allure ont deux bornes strictes puis deux inclusives,
 * pente et vitesse sont toutes strictes (voir AnalysisParameters, section 5.4/5.7 de la conception).
 */
class AnalysisParametersClassTest {

    private val parameters = AnalysisParameters.DEFAULT

    // --- paceClassOf : e < -25, e < -10, e <= 10, e <= 25, sinon --------------------------------

    @Test
    fun paceClassBoundariesMatchTheTwoStrictThenTwoInclusiveCascade() {
        assertEquals(0, parameters.paceClassOf(-25.0001))
        assertEquals(1, parameters.paceClassOf(-25.0)) // pas strictement < -25
        assertEquals(1, parameters.paceClassOf(-10.0001))
        assertEquals(2, parameters.paceClassOf(-10.0)) // pas strictement < -10, mais <= 10
        assertEquals(2, parameters.paceClassOf(10.0)) // <= 10 inclus
        assertEquals(3, parameters.paceClassOf(10.0001))
        assertEquals(3, parameters.paceClassOf(25.0)) // <= 25 inclus
        assertEquals(4, parameters.paceClassOf(25.0001))
    }

    // --- slopeClassOf : |pente| < 5, < 10, < 15, < 25, sinon, sans distinguer montée/descente -----

    @Test
    fun slopeClassBoundariesAreAllStrict() {
        assertEquals(0, parameters.slopeClassOf(4.9999))
        assertEquals(1, parameters.slopeClassOf(5.0)) // pas strictement < 5
        assertEquals(1, parameters.slopeClassOf(9.9999))
        assertEquals(2, parameters.slopeClassOf(10.0))
        assertEquals(2, parameters.slopeClassOf(14.9999))
        assertEquals(3, parameters.slopeClassOf(15.0))
        assertEquals(3, parameters.slopeClassOf(24.9999))
        assertEquals(4, parameters.slopeClassOf(25.0))
        assertEquals(4, parameters.slopeClassOf(25.0001))
    }

    @Test
    fun slopeClassIgnoresTheSignOfTheSlope() {
        assertEquals(parameters.slopeClassOf(12.0), parameters.slopeClassOf(-12.0))
        assertEquals(0, parameters.slopeClassOf(-4.9999))
        assertEquals(1, parameters.slopeClassOf(-5.0))
    }

    // --- speedClassOf : vitesse < 2, < 3, < 4, < 5, sinon ----------------------------------------

    @Test
    fun speedClassBoundariesAreAllStrict() {
        assertEquals(0, parameters.speedClassOf(1.9999))
        assertEquals(1, parameters.speedClassOf(2.0))
        assertEquals(1, parameters.speedClassOf(2.9999))
        assertEquals(2, parameters.speedClassOf(3.0))
        assertEquals(2, parameters.speedClassOf(3.9999))
        assertEquals(3, parameters.speedClassOf(4.0))
        assertEquals(3, parameters.speedClassOf(4.9999))
        assertEquals(4, parameters.speedClassOf(5.0))
        assertEquals(4, parameters.speedClassOf(5.0001))
    }

    // --- slopeBandCenter : moyenne des deux bornes, extrémités ouvertes étendues de 7,5 -----------

    @Test
    fun slopeBandCenterAveragesTheTwoSurroundingBoundsForInnerBands() {
        // Bornes DEFAULT : -25, -15, -10, -5, -2, 2, 5, 10, 15, 25 (10 bornes, 11 bandes).
        assertEquals(-20.0, parameters.slopeBandCenter(1), 1e-9) // (-25 + -15) / 2
        assertEquals(0.0, parameters.slopeBandCenter(5), 1e-9) // (-2 + 2) / 2, la bande "plat"
        assertEquals(3.5, parameters.slopeBandCenter(6), 1e-9) // (2 + 5) / 2
        assertEquals(7.5, parameters.slopeBandCenter(7), 1e-9) // (5 + 10) / 2
    }

    @Test
    fun slopeBandCenterExtendsTheTwoOpenEndedBandsBy7Point5() {
        assertEquals(-32.5, parameters.slopeBandCenter(0), 1e-9) // -25 - 7.5
        assertEquals(32.5, parameters.slopeBandCenter(10), 1e-9) // 25 + 7.5
    }
}
