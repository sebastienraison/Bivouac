package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RIC-161 : après une suppression décidée depuis la barre d'actions de la visionneuse, quelle page
 * afficher. Calculateur pur (voir PhotoViewerDialog.pagerIndexAfterRemoval), testé comme
 * BilanStatsCalculatorTest : pas de Compose ni de Robolectric nécessaire.
 */
class PhotoViewerRemovalIndexTest {

    /** La photo supprimée n'était pas la dernière : la SUIVANTE occupe déjà son index. */
    @Test
    fun laSuivanteOccupeLIndexDeLaPhotoRetiree() {
        // 5 photos, on retire la 3e (index 2) : les 4 suivantes se resserrent, la nouvelle 3e
        // (ex-4e) occupe désormais l'index 2.
        assertEquals(2, pagerIndexAfterRemoval(sizeBeforeRemoval = 5, removedIndex = 2))
    }

    /** La photo retirée était la première d'une liste qui en comptait plusieurs : reste en tête. */
    @Test
    fun laPremierePhotoRetireeLaisseLaSuivanteEnTete() {
        assertEquals(0, pagerIndexAfterRemoval(sizeBeforeRemoval = 3, removedIndex = 0))
    }

    /** La photo retirée était la DERNIÈRE : bascule sur la PRÉCÉDENTE, pas d'index suivant. */
    @Test
    fun laDerniereRetireeBasculeSurLaPrecedente() {
        assertEquals(1, pagerIndexAfterRemoval(sizeBeforeRemoval = 3, removedIndex = 2))
    }

    /** Il ne reste plus rien : ferme la visionneuse (représenté par null). */
    @Test
    fun laSeulePhotoRetireeNeLaisseRien() {
        assertNull(pagerIndexAfterRemoval(sizeBeforeRemoval = 1, removedIndex = 0))
    }

    /** Deux photos, on retire la première : la seconde devient la seule, à l'index 0. */
    @Test
    fun deuxPhotosOnRetireLaPremiere() {
        assertEquals(0, pagerIndexAfterRemoval(sizeBeforeRemoval = 2, removedIndex = 0))
    }

    /** Deux photos, on retire la dernière (la seconde) : bascule sur la première, index 0. */
    @Test
    fun deuxPhotosOnRetireLaDerniere() {
        assertEquals(0, pagerIndexAfterRemoval(sizeBeforeRemoval = 2, removedIndex = 1))
    }
}
