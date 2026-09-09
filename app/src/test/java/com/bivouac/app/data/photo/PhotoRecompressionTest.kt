package com.bivouac.app.data.photo

import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-140/157 : la pré-sélection des photos recompressibles et le chiffre annoncé avant l'action.
 *
 * En JVM pure, sans appareil : ce sont deux décisions, pas des accès disque. Ce qu'elles décident,
 * c'est ce que l'utilisateur lit avant d'accepter une opération qui réécrit ses fichiers.
 */
class PhotoRecompressionTest {

    private fun photo(
        storageMode: PhotoStorageMode = PhotoStorageMode.FULL,
        contentHash: String = "abc",
        lastResolvedUri: String? = "content://media/external/images/media/42",
        sourceDisplayName: String? = null,
        sourceDateTakenMillis: Long? = null,
    ) = LoggedTrackPhotoEntity(
        id = 1L,
        trackId = "trace",
        filePath = "photos/trace-1.jpg",
        addedAtMillis = 0L,
        contentHash = contentHash,
        storageMode = storageMode,
        lastResolvedUri = lastResolvedUri,
        sourceDisplayName = sourceDisplayName,
        sourceDateTakenMillis = sourceDateTakenMillis,
    )

    @Test
    fun aFullCopyWithAKnownSourceUriIsRecompressible() {
        assertTrue(PhotoRecompression.isRecompressible(photo()))
    }

    // Déjà réduite : il n'y a rien à reprendre, et la réexaminer à chaque passe serait la seule
    // chose que la colonne storageMode existe pour éviter.
    @Test
    fun anAlreadyReducedCopyIsNeverRecompressible() {
        assertFalse(PhotoRecompression.isRecompressible(photo(storageMode = PhotoStorageMode.REDUCED)))
    }

    /**
     * Sans URI mémorisé, il reste la recherche profonde : un nom de fichier ou une date de prise de
     * vue suffisent à requêter MediaStore (voir PhotoOriginalResolver.lookupsFor).
     */
    @Test
    fun aFullCopyWithoutUriButWithSourceMetadataIsStillRecompressible() {
        assertTrue(PhotoRecompression.isRecompressible(photo(lastResolvedUri = null, sourceDisplayName = "IMG_0042.jpg")))
        assertTrue(PhotoRecompression.isRecompressible(photo(lastResolvedUri = null, sourceDateTakenMillis = 1_700_000_000_000L)))
    }

    // Rien pour retrouver l'original : la proposer reviendrait à promettre un gain qu'aucune
    // recherche ne pourra jamais produire.
    @Test
    fun aFullCopyWithNothingToSearchWithIsNotRecompressible() {
        assertFalse(
            PhotoRecompression.isRecompressible(
                photo(lastResolvedUri = null, sourceDisplayName = null, sourceDateTakenMillis = null),
            ),
        )
        // Une date à zéro n'est pas une date : plusieurs fournisseurs remplissent DATE_TAKEN avec
        // zéro, et ce critère-là désignerait la pellicule entière.
        assertFalse(
            PhotoRecompression.isRecompressible(photo(lastResolvedUri = null, sourceDateTakenMillis = 0L)),
        )
    }

    // L'empreinte est le seul juge de la confirmation : sans elle, aucun original ne peut être
    // reconnu, donc aucune recompression ne peut aboutir.
    @Test
    fun aRowWithoutAContentHashIsNotRecompressible() {
        assertFalse(PhotoRecompression.isRecompressible(photo(contentHash = "")))
    }

    @Test
    fun theEstimateFallsBackToTheAssumedReducedSizeWhenTheJournalHasNoSample() {
        val estimate = PhotoRecompression.estimate(listOf(4_500_000L, 4_500_000L))

        assertEquals(2, estimate?.photoCount)
        assertEquals(2 * (4_500_000L - PhotoRecompression.ASSUMED_REDUCED_PHOTO_BYTES), estimate?.freedBytes)
    }

    // À partir de MIN_REDUCED_SAMPLES copies réduites, la mesure faite sur les photos réelles de
    // l'utilisateur remplace la constante : 300 000 octets de moyenne ici, pas 700 000.
    @Test
    fun theEstimateUsesTheMeasuredAverageOnceThereAreEnoughReducedPhotos() {
        val estimate = PhotoRecompression.estimate(
            candidateFileBytes = listOf(4_000_000L),
            reducedSampleCount = 4,
            reducedSampleBytes = 1_200_000L,
        )

        assertEquals(4_000_000L - 300_000L, estimate?.freedBytes)
    }

    // Deux échantillons ne font pas une moyenne : un panorama ou une capture d'écran suffirait à
    // fausser tout le chiffre annoncé.
    @Test
    fun theEstimateIgnoresTooFewReducedSamples() {
        val estimate = PhotoRecompression.estimate(
            candidateFileBytes = listOf(4_000_000L),
            reducedSampleCount = 2,
            reducedSampleBytes = 200_000L,
        )

        assertEquals(4_000_000L - PhotoRecompression.ASSUMED_REDUCED_PHOTO_BYTES, estimate?.freedBytes)
    }

    /**
     * Une photo déjà plus légère que la cible ne rend rien, et ne retranche rien non plus au gain
     * des autres : c'est ce qui distingue une somme photo par photo d'une soustraction globale.
     */
    @Test
    fun aPhotoLighterThanTheTargetContributesNothingRatherThanANegativeGain() {
        val estimate = PhotoRecompression.estimate(listOf(4_500_000L, 50_000L))

        assertEquals(2, estimate?.photoCount)
        assertEquals(4_500_000L - PhotoRecompression.ASSUMED_REDUCED_PHOTO_BYTES, estimate?.freedBytes)
    }

    // Rien à gagner : pas d'estimation, donc pas de bouton qui promettrait de libérer 0 Mo.
    @Test
    fun nothingToGainYieldsNoEstimateAtAll() {
        assertNull(PhotoRecompression.estimate(emptyList()))
        assertNull(PhotoRecompression.estimate(listOf(100_000L, 200_000L)))
    }

    // --- shouldOfferRecompressionAfterModeChange (RIC-157, retour de recette) ------------------

    private val someEstimate = PhotoRecompression.Estimate(photoCount = 3, freedBytes = 9_000_000L)

    // Le cas nominal : c'est exactement le retour de recette à couvrir.
    @Test
    fun offersRecompressionOnASwitchToReducedWithFullPhotosAndSomethingToGain() {
        assertTrue(
            PhotoRecompression.shouldOfferRecompressionAfterModeChange(
                previousMode = PhotoStorageMode.FULL,
                newMode = PhotoStorageMode.REDUCED,
                fullPhotoCount = 3,
                estimate = someEstimate,
            ),
        )
    }

    // Choisir « Pleine résolution » ne doit jamais rien déclencher, quel que soit le reste.
    @Test
    fun neverOffersOnASwitchToFull() {
        assertFalse(
            PhotoRecompression.shouldOfferRecompressionAfterModeChange(
                previousMode = PhotoStorageMode.REDUCED,
                newMode = PhotoStorageMode.FULL,
                fullPhotoCount = 3,
                estimate = someEstimate,
            ),
        )
    }

    // Un simple affichage des Réglages n'est pas une bascule : le mode choisi est déjà celui
    // affiché (SegmentedButton rappelle onModeSelected même sur le bouton déjà sélectionné).
    @Test
    fun neverOffersWhenTheModeDoesNotActuallyChange() {
        assertFalse(
            PhotoRecompression.shouldOfferRecompressionAfterModeChange(
                previousMode = PhotoStorageMode.REDUCED,
                newMode = PhotoStorageMode.REDUCED,
                fullPhotoCount = 3,
                estimate = someEstimate,
            ),
        )
    }

    // Aucune photo FULL : rien à annoncer, même si un appelant fournissait par erreur une
    // estimation non nulle.
    @Test
    fun neverOffersWhenThereAreNoFullPhotos() {
        assertFalse(
            PhotoRecompression.shouldOfferRecompressionAfterModeChange(
                previousMode = PhotoStorageMode.FULL,
                newMode = PhotoStorageMode.REDUCED,
                fullPhotoCount = 0,
                estimate = someEstimate,
            ),
        )
    }

    // Rien à gagner (originaux introuvables, ou déjà sous la cible) : pas de bouton qui
    // promettrait de libérer 0 Mo, ici pas plus qu'à l'écran « Espace utilisé ».
    @Test
    fun neverOffersWhenTheEstimateIsNull() {
        assertFalse(
            PhotoRecompression.shouldOfferRecompressionAfterModeChange(
                previousMode = PhotoStorageMode.FULL,
                newMode = PhotoStorageMode.REDUCED,
                fullPhotoCount = 5,
                estimate = null,
            ),
        )
    }
}
