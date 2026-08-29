package com.bivouac.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-156 : la logique anti-flash du dialogue bloquant, éprouvée sans Compose ni horloge.
 *
 * Deux symptômes à couvrir, et un piège :
 *  - une opération éclair (l'enregistrement des détails d'une trace sans photo, ~43 ms mesurées en
 *    recette) ne doit RIEN afficher ;
 *  - une opération qui franchit tout juste le seuil d'apparition ne doit pas disparaître aussitôt ;
 *  - et rien de tout cela ne doit toucher à l'état « opération en vol » lui-même, qui reste posé au
 *    geste et levé à la fin réelle. C'est pour ça que cette fonction ne rend qu'un verdict visuel :
 *    elle ne peut structurellement pas retarder une garde.
 */
class AntiFlashDecisionTest {

    private val appearance = 200L
    private val minimum = 500L

    private fun decide(startedAt: Long?, shownAt: Long?, now: Long) = antiFlashDecision(
        operationStartedAtMillis = startedAt,
        shownAtMillis = shownAt,
        nowMillis = now,
        appearanceDelayMillis = appearance,
        minimumVisibleMillis = minimum,
    )

    @Test
    fun rienNEstAfficheAuDemarrageDeLOperation() {
        val decision = decide(startedAt = 1_000L, shownAt = null, now = 1_000L)
        assertFalse(decision.visible)
        assertNull(decision.shownAtMillis)
        assertEquals(200L, decision.recheckInMillis)
    }

    @Test
    fun sousLeSeuilDApparitionRienNEstAffiche() {
        val decision = decide(startedAt = 1_000L, shownAt = null, now = 1_199L)
        assertFalse(decision.visible)
        assertEquals(1L, decision.recheckInMillis)
    }

    @Test
    fun leSeuilAtteintFaitApparaitreLeDialogue() {
        val decision = decide(startedAt = 1_000L, shownAt = null, now = 1_200L)
        assertTrue(decision.visible)
        assertEquals(1_200L, decision.shownAtMillis)
        assertNull("une fois visible, plus rien à réévaluer avant la fin", decision.recheckInMillis)
    }

    /** Le cas du ticket : 43 ms d'opération, l'écran ne doit jamais rien voir. */
    @Test
    fun uneOperationEclairNAffichejamaisRien() {
        // Pendant : sous le seuil.
        assertFalse(decide(startedAt = 1_000L, shownAt = null, now = 1_043L).visible)
        // Après : plus rien en vol, et rien n'a jamais été affiché.
        val afterEnd = decide(startedAt = null, shownAt = null, now = 1_043L)
        assertFalse(afterEnd.visible)
        assertNull(afterEnd.shownAtMillis)
        assertNull(afterEnd.recheckInMillis)
    }

    /**
     * Le piège que le seul seuil d'apparition ne couvre pas : 210 ms d'opération, le dialogue
     * apparaît puis devrait disparaître 10 ms plus tard — soit exactement le clignotement visé.
     */
    @Test
    fun leDialogueApparuResteAuMoinsLaDureeMinimale() {
        val shownAt = 1_200L
        val justAfterEnd = decide(startedAt = null, shownAt = shownAt, now = 1_210L)
        assertTrue(justAfterEnd.visible)
        assertEquals(shownAt, justAfterEnd.shownAtMillis)
        assertEquals(490L, justAfterEnd.recheckInMillis)
    }

    @Test
    fun leDialogueDisparaitUneFoisLaDureeMinimaleEcoulee() {
        val decision = decide(startedAt = null, shownAt = 1_200L, now = 1_700L)
        assertFalse(decision.visible)
        assertNull(decision.shownAtMillis)
        assertNull(decision.recheckInMillis)
    }

    @Test
    fun tantQueLOperationDureLeDialogueReste() {
        val decision = decide(startedAt = 1_000L, shownAt = 1_200L, now = 60_000L)
        assertTrue(decision.visible)
        assertEquals(1_200L, decision.shownAtMillis)
        assertNull(decision.recheckInMillis)
    }

    /**
     * Une opération qui démarre pendant le maintien minimal de la précédente enchaîne sans que le
     * dialogue se referme entre les deux : le refermer une fraction de seconde serait, là encore,
     * un clignotement.
     */
    @Test
    fun uneNouvelleOperationPendantLeMaintienGardeLeDialogueOuvert() {
        val decision = decide(startedAt = 1_300L, shownAt = 1_200L, now = 1_300L)
        assertTrue(decision.visible)
        assertEquals(1_200L, decision.shownAtMillis)
        assertNull(decision.recheckInMillis)
    }

    /** Le compteur n'est affiché que quand le travail est réellement dénombrable. */
    @Test
    fun leLibelleDeProgressionDitCeQuIlSait() {
        assertEquals("3 sur 12…", blockingProgressLabel(3, 12))
        assertEquals("Patiente un instant…", blockingProgressLabel(4, null))
        assertEquals("Patiente un instant…", blockingProgressLabel(null, 12))
        assertEquals("Patiente un instant…", blockingProgressLabel(null, null))
        assertEquals("un total à zéro n'est pas un dénominateur", "Patiente un instant…", blockingProgressLabel(0, 0))
    }
}
