package com.bivouac.app.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-157 : la construction des requêtes de la recherche profonde, c'est-à-dire la garantie
 * « jamais de scan complet de la galerie ».
 *
 * Robolectric et non JVM pure, uniquement pour que les constantes `MediaStore` aient une valeur :
 * rien ici n'ouvre de base, de fichier ni de fournisseur.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoOriginalLookupTest {

    /**
     * LE test de ce fichier : sans métadonnée exploitable, aucune requête n'est produite, donc la
     * résolution s'arrête sans rien parcourir. Parcourir et hacher une pellicule entière pour une
     * seule photo n'est pas une résolution plus lente, c'est une opération d'une autre nature.
     */
    @Test
    fun withNoUsableMetadata_producesNoQueryAtAll() {
        assertEquals(emptyList<MediaStoreLookup>(), PhotoOriginalResolver.lookupsFor(null, null))
        assertEquals(emptyList<MediaStoreLookup>(), PhotoOriginalResolver.lookupsFor("", null))
        assertEquals(emptyList<MediaStoreLookup>(), PhotoOriginalResolver.lookupsFor("   ", null))
    }

    /**
     * DATE_TAKEN à zéro compte comme absente : plusieurs fournisseurs remplissent la colonne avec
     * zéro plutôt que de la laisser vide, et cette « date » désignerait toute la pellicule d'un
     * coup, ce qui est exactement le scan complet qu'on interdit.
     */
    @Test
    fun aZeroDateTakenIsTreatedAsMissing() {
        assertEquals(emptyList<MediaStoreLookup>(), PhotoOriginalResolver.lookupsFor(null, 0L))
        assertEquals(emptyList<MediaStoreLookup>(), PhotoOriginalResolver.lookupsFor(null, -1L))
    }

    // Deux requêtes successives, jamais une conjonction : une photo renommée serait ratée par
    // « nom ET date », alors que le critère de date seul la retrouve, et réciproquement.
    @Test
    fun bothMetadataAvailable_producesTwoSeparateQueriesNameFirst() {
        val lookups = PhotoOriginalResolver.lookupsFor("IMG_0001.jpg", 1780300850000L)

        assertEquals(2, lookups.size)
        assertTrue("le nom doit être tenté en premier", lookups[0].selection.contains("_display_name"))
        assertEquals(listOf("IMG_0001.jpg"), lookups[0].args)
        assertTrue(lookups[1].selection.contains("datetaken"))
        assertEquals(listOf("1780300850000"), lookups[1].args)
        // Aucune requête ne combine les deux : ce serait une conjonction déguisée.
        lookups.forEach { assertEquals(1, it.args.size) }
    }

    @Test
    fun onlyOneMetadataAvailable_producesThatSingleQuery() {
        val byName = PhotoOriginalResolver.lookupsFor("IMG_0001.jpg", null)
        assertEquals(1, byName.size)
        assertEquals(listOf("IMG_0001.jpg"), byName.single().args)

        val byDate = PhotoOriginalResolver.lookupsFor(null, 1780300850000L)
        assertEquals(1, byDate.size)
        assertEquals(listOf("1780300850000"), byDate.single().args)
    }

    // Le chemin relatif n'est jamais un filtre : une photo rangée ailleurs depuis l'import est
    // précisément ce que la recherche profonde doit rattraper.
    @Test
    fun theRelativePathIsNeverPartOfTheSelection() {
        val lookups = PhotoOriginalResolver.lookupsFor("IMG_0001.jpg", 1780300850000L)
        lookups.forEach { assertTrue(!it.selection.contains("relative_path")) }
    }
}
