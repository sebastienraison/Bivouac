package com.bivouac.app.data.photo

import org.junit.Assert.assertEquals
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
