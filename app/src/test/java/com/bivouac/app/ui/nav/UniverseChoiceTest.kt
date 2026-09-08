package com.bivouac.app.ui.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-108 : la règle seule, sans Compose. Le comportement d'écran qui en découle (carte grisée et
 * inerte) est vérifié par UniverseChoiceDialogTest, côté instrumenté.
 */
class UniverseChoiceTest {

    @Test
    fun planificationAcceptsExactlyOneFile() {
        // Zéro fichier n'arrive jamais jusqu'au dialogue (il ne s'ouvre que sur un lot non vide),
        // mais la règle ne doit pas se mettre à refuser à la limite basse pour autant.
        assertTrue(UniverseChoice.planificationAccepts(0))
        assertTrue(UniverseChoice.planificationAccepts(1))
    }

    // Le cas du test communautaire F-Droid : trois fichiers partagés en une fois, dont deux étaient
    // silencieusement jetés par le choix Planification.
    @Test
    fun planificationRefusesABatch() {
        assertFalse(UniverseChoice.planificationAccepts(2))
        assertFalse(UniverseChoice.planificationAccepts(3))
        assertFalse(UniverseChoice.planificationAccepts(12))
    }
}
