package com.bivouac.app.ui.journal

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-162 : la logique de sélection et de navigation de la visionneuse de sélection, extraite en
 * fonctions pures pour être vérifiable sans monter ni la grille ni la visionneuse.
 *
 * Robolectric et non un test JVM pur (contrairement à AddPhotosOutcomeTest) : [toggleSelection]
 * manipule des `android.net.Uri`, et `Uri.parse` n'est pas mocké par défaut hors Robolectric, même
 * convention que le reste de la suite (PhotoOriginalResolverTest, LoggedTrackPhotoRepositoryTest…).
 */
@RunWith(RobolectricTestRunner::class)
class PhotoSelectionViewerLogicTest {

    private val photoA = Uri.parse("content://media/external/images/media/1")
    private val photoB = Uri.parse("content://media/external/images/media/2")

    // --- toggleSelection : partagée par le tap de la grille et la case de la visionneuse --------

    @Test
    fun anUnselectedPhotoGetsAddedByOneToggle() {
        val result = toggleSelection(emptySet(), photoA)

        assertEquals(setOf(photoA), result)
    }

    @Test
    fun aSelectedPhotoGetsRemovedByOneToggle() {
        val result = toggleSelection(setOf(photoA), photoA)

        assertTrue(result.isEmpty())
    }

    @Test
    fun togglingOnePhotoNeverTouchesTheOthers() {
        val result = toggleSelection(setOf(photoA, photoB), photoA)

        assertEquals(setOf(photoB), result)
        assertFalse(photoA in result)
    }

    @Test
    fun theOriginalSetIsNeverMutatedInPlace() {
        val original = setOf(photoA)

        toggleSelection(original, photoB)

        // `toggleSelection` rend un NOUvel ensemble : l'appelant (grille ou visionneuse) doit
        // pouvoir comparer avant/après sans que l'un des deux ait bougé sous ses pieds.
        assertEquals(setOf(photoA), original)
    }

    // --- selectionCounterLabel : affichage "n / N" du compteur de la visionneuse -----------------

    @Test
    fun theFirstPageIsDisplayedAsOneNotZero() {
        assertEquals("1 / 5", selectionCounterLabel(page = 0, total = 5))
    }

    @Test
    fun theLastPageMatchesTheTotal() {
        assertEquals("5 / 5", selectionCounterLabel(page = 4, total = 5))
    }

    @Test
    fun aSingleCandidateStillShowsOneOverOne() {
        assertEquals("1 / 1", selectionCounterLabel(page = 0, total = 1))
    }
}
