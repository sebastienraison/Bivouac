package com.bivouac.app.ui.journal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-146 lot 7 (conception 2 section 5.1, brief Partie B) : le choix Carnet/Analyse est gardé
 * d'une rando à l'autre tant que l'app reste ouverte, et revient à Carnet en entrant en édition ou
 * en placement photo. La persistance d'une rando à l'autre tient au `rememberSaveable` SANS clé de
 * `analysisModeActive` dans JournalScreen (rien à tester ici, c'est l'absence d'appel qui la
 * garantit) ; ce test couvre la partie qui EST une fonction pure, [analysisModeAfter], qui porte
 * la logique des deux `LaunchedEffect` (isEditingDetail, photoPlacementTarget) de JournalScreen.
 */
class AnalysisModeMemoryTest {

    @Test
    fun rienNeChangeHorsEditionEtPlacementPhoto() {
        // Simule le passage d'une rando à l'autre : ni édition ni placement, le mode Analyse
        // choisi sur la rando précédente reste actif sur la nouvelle.
        assertTrue(analysisModeAfter(current = true, isEditing = false, photoPlacementActive = false))
        assertFalse(analysisModeAfter(current = false, isEditing = false, photoPlacementActive = false))
    }

    @Test
    fun entrerEnEditionRameneAuCarnet() {
        assertFalse(analysisModeAfter(current = true, isEditing = true, photoPlacementActive = false))
    }

    @Test
    fun entrerEnPlacementPhotoRameneAuCarnet() {
        assertFalse(analysisModeAfter(current = true, isEditing = false, photoPlacementActive = true))
    }
}
