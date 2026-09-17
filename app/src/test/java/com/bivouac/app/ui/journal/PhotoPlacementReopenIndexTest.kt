package com.bivouac.app.ui.journal

import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RIC-184 : à la sortie du mode placement (Terminé, Annuler), l'index où rouvrir la visionneuse
 * (voir JournalScreen.photoIndexToReopenAfterPlacement). Fonction pure, même patron que
 * PhotoViewerRemovalIndexTest (RIC-161) : pas de Compose ni de Robolectric nécessaires.
 */
class PhotoPlacementReopenIndexTest {

    private fun photo(id: Long) = LoggedTrackPhotoEntity(
        id = id,
        trackId = "trace-test",
        filePath = "photo-$id.jpg",
        addedAtMillis = 0L,
    )

    /** Cas simple : la photo est restée à sa place, son index ne change pas. */
    @Test
    fun laPhotoEncorePresenteAuMemeRangRendCeRang() {
        val photos = listOf(photo(1), photo(2), photo(3))
        assertEquals(1, photoIndexToReopenAfterPlacement(photoId = 2, photos = photos))
    }

    /**
     * Cas du glissement : currentPhotos trie par position sur la trace, un déplacement peut donc
     * faire changer le rang de la photo repositionnée. La recherche se fait par identifiant, pas
     * par l'index d'avant le mode.
     */
    @Test
    fun laPhotoReordonneeParLeGlissementRendSonNouveauRang() {
        // La photo 3 occupait le rang 2 avant le mode ; le glissement l'a fait remonter en tête.
        val photosApresGlissement = listOf(photo(3), photo(1), photo(2))
        assertEquals(0, photoIndexToReopenAfterPlacement(photoId = 3, photos = photosApresGlissement))
    }

    /** La photo a été supprimée pendant le mode (cas limite) : rien à rouvrir. */
    @Test
    fun laPhotoAbsenteNeRouvreRien() {
        val photos = listOf(photo(1), photo(2))
        assertNull(photoIndexToReopenAfterPlacement(photoId = 99, photos = photos))
    }

    /** Liste vide (dernière photo supprimée) : rien à rouvrir non plus. */
    @Test
    fun uneListeVideNeRouvreRien() {
        assertNull(photoIndexToReopenAfterPlacement(photoId = 1, photos = emptyList()))
    }
}
