package com.bivouac.app.ui.journal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-239 : sous l'en-tête du mode Analyse, rien n'est composé quand la trace n'a ni heures ni
 * altitude. Les trois autres combinaisons gardent leur contenu. Fonction pure, sans Compose.
 */
class AnalysisBodyAvailableTest {

    @Test
    fun neitherTimesNorElevationHidesTheBody() {
        assertFalse(analysisBodyAvailable(hasTimestamps = false, elevationAvailable = false))
    }

    @Test
    fun timesWithoutElevationKeepsTheBody() {
        assertTrue(analysisBodyAvailable(hasTimestamps = true, elevationAvailable = false))
    }

    @Test
    fun elevationWithoutTimesKeepsTheBody() {
        assertTrue(analysisBodyAvailable(hasTimestamps = false, elevationAvailable = true))
    }

    @Test
    fun timesAndElevationKeepTheBody() {
        assertTrue(analysisBodyAvailable(hasTimestamps = true, elevationAvailable = true))
    }
}
