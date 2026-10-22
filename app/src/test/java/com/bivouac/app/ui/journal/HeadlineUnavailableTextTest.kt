package com.bivouac.app.ui.journal

import com.bivouac.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-231 : l'en-tête de l'Analyse sans chiffre de tête dit « pas d'altitude » pour une trace
 * horodatée sans altitude, et garde « pas d'horodatage » pour une trace sans heures (le fichier
 * Decathlon, planifié, en est un). RIC-239 : sans heures ni altitude, une troisième issue dit les
 * deux manques. Fonction pure, sans Compose.
 */
class HeadlineUnavailableTextTest {

    @Test
    fun aTimedTrackWithoutElevationSaysNoElevation() {
        assertEquals(R.string.journal_analysis_no_elevation, headlineUnavailableTextRes(elevationAvailable = false, hasTimedPoint = true))
    }

    @Test
    fun aTrackWithoutTimesKeepsNoTimestamps() {
        assertEquals(R.string.journal_analysis_no_timestamps, headlineUnavailableTextRes(elevationAvailable = true, hasTimedPoint = false))
    }

    @Test
    fun aTrackWithNeitherTimesNorElevationSaysBoth() {
        // RIC-239 : un seul en-tête qui dit les deux manques.
        assertEquals(R.string.journal_analysis_no_timestamps_no_elevation, headlineUnavailableTextRes(elevationAvailable = false, hasTimedPoint = false))
    }

    @Test
    fun aTimedTrackWithElevationKeepsNoTimestampsWording() {
        // Cas inatteignable en pratique (une trace horodatée avec altitude a des tronçons), le repli reste l'ancien texte.
        assertEquals(R.string.journal_analysis_no_timestamps, headlineUnavailableTextRes(elevationAvailable = true, hasTimedPoint = true))
    }
}
