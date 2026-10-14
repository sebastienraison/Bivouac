package com.bivouac.app.ui.journal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-227 (décision de Seb du 15/10) : l'entrée « Détecter les bivouacs » n'apparaît pas tant que
 * la vue détail est en édition, en placement d'une photo ou pendant une opération photo.
 */
class DetectBivouacsMenuEntryTest {

    @Test
    fun visibleEnConsultation() {
        assertTrue(detectBivouacsMenuEntryVisible(isEditing = false, photoPlacementActive = false, photoOperationInFlight = false))
    }

    @Test
    fun absenteEnEdition() {
        assertFalse(detectBivouacsMenuEntryVisible(isEditing = true, photoPlacementActive = false, photoOperationInFlight = false))
    }

    @Test
    fun absenteEnPlacementPhoto() {
        assertFalse(detectBivouacsMenuEntryVisible(isEditing = false, photoPlacementActive = true, photoOperationInFlight = false))
    }

    @Test
    fun absentePendantUneOperationPhoto() {
        assertFalse(detectBivouacsMenuEntryVisible(isEditing = false, photoPlacementActive = false, photoOperationInFlight = true))
    }
}
