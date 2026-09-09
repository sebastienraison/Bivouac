package com.bivouac.app.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-157 : la règle de défaut du mode de stockage, la seule partie de ce réglage qui puisse être
 * fausse sans que rien ne le signale : elle décide du sort de photos que l'utilisateur n'a pas
 * encore importées, à partir d'un choix qu'il n'a pas encore fait.
 */
class PhotoStoragePolicyTest {

    @Test
    fun undecidedOnAnEmptyJournal_defaultsToReduced() {
        assertEquals(
            PhotoStorageMode.REDUCED,
            PhotoStoragePolicy.resolve(decision = null, hasExistingPhotos = false),
        )
    }

    @Test
    fun undecidedWithExistingPhotos_keepsTheHistoricalFullCopy() {
        assertEquals(
            PhotoStorageMode.FULL,
            PhotoStoragePolicy.resolve(decision = null, hasExistingPhotos = true),
        )
    }

    /**
     * Le défaut d'un Journal vide se verrouille : sans ça, un utilisateur tout neuf importerait son
     * premier lot en copie réduite puis tous les suivants en copie intégrale, la présence de photos
     * ayant changé entre-temps. Deux régimes dans un même Journal.
     */
    @Test
    fun theImplicitReducedDefaultIsPinnedSoItCannotFlipOnTheNextBatch() {
        assertTrue(
            PhotoStoragePolicy.shouldPersistAsDecision(decision = null, resolved = PhotoStorageMode.REDUCED),
        )
    }

    /**
     * Le défaut d'un Journal déjà pourvu ne se verrouille PAS : c'est le profil de quelqu'un qui met
     * l'app à jour, et fermer sa question dans son dos empêcherait la proposition post-mise à jour
     * de se poser, sa condition étant « aucune décision enregistrée ».
     */
    @Test
    fun theImplicitFullDefaultIsNeverPinnedSoTheQuestionStaysOpen() {
        assertFalse(
            PhotoStoragePolicy.shouldPersistAsDecision(decision = null, resolved = PhotoStorageMode.FULL),
        )
    }

    // Une décision existante ne se réécrit jamais : ça n'ajouterait rien et masquerait un appelant
    // qui aurait oublié de la lire.
    @Test
    fun anExistingDecisionIsNeverRewritten() {
        assertFalse(
            PhotoStoragePolicy.shouldPersistAsDecision(PhotoStorageMode.REDUCED, PhotoStorageMode.REDUCED),
        )
        assertFalse(
            PhotoStoragePolicy.shouldPersistAsDecision(PhotoStorageMode.FULL, PhotoStorageMode.FULL),
        )
    }

    // Une fois le choix fait, il vaut dans les deux situations : le nombre de photos déjà présentes
    // ne sert qu'à départager l'absence de choix, jamais à corriger un choix explicite.
    @Test
    fun anExplicitDecisionAlwaysWinsOverBothDefaults() {
        assertEquals(
            PhotoStorageMode.FULL,
            PhotoStoragePolicy.resolve(PhotoStorageMode.FULL, hasExistingPhotos = false),
        )
        assertEquals(
            PhotoStorageMode.REDUCED,
            PhotoStoragePolicy.resolve(PhotoStorageMode.REDUCED, hasExistingPhotos = true),
        )
    }
}
