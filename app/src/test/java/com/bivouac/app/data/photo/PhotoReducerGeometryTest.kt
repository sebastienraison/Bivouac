package com.bivouac.app.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-157 : les deux calculs qui décident, l'un de la mémoire que l'app va demander pour réduire
 * une photo, l'autre de la définition qu'elle en gardera. Purs, donc exercés en JVM : c'est là que
 * se joue le risque d'un décodage plein format d'une photo de 100 Mpx.
 */
class PhotoReducerGeometryTest {

    private val target = PhotoStoragePolicy.REDUCED_LONG_SIDE_PX

    // L'invariant qui protège la mémoire : après sous-échantillonnage, le grand côté reste
    // AU-DESSUS de la cible (sinon on perd de la définition irrattrapable) et STRICTEMENT SOUS son
    // double (sinon le bitmap intermédiaire n'est plus borné).
    @Test
    fun sampleSizeAlwaysLandsBetweenTheTargetAndItsDouble() {
        val longSides = listOf(2049, 2500, 3000, 4032, 4096, 4097, 8000, 12000, 20000)
        for (longSide in longSides) {
            val sample = PhotoReducer.sampleSizeFor(longSide, target)
            val decoded = longSide / sample
            assertTrue("$longSide/$sample = $decoded, sous la cible", decoded >= target)
            assertTrue("$longSide/$sample = $decoded, au-delà du double de la cible", decoded < 2 * target)
        }
    }

    // Une photo de 100 Mpx (11600 x 8700) : sans sous-échantillonnage son bitmap ARGB_8888 pèse
    // 400 Mo. C'est le cas que ce calcul existe pour empêcher.
    @Test
    fun aHundredMegapixelPhotoIsSampledDownBeforeAnyDecoding() {
        assertEquals(4, PhotoReducer.sampleSizeFor(11600, target))
    }

    @Test
    fun anImageAlreadyUnderTheTargetIsNeverSampled() {
        assertEquals(1, PhotoReducer.sampleSizeFor(2048, target))
        assertEquals(1, PhotoReducer.sampleSizeFor(800, target))
        assertEquals(1, PhotoReducer.sampleSizeFor(4095, target))
    }

    // Dimensions absentes ou aberrantes (en-tête illisible) : rendre 1 plutôt que boucler ou
    // diviser par zéro. L'appelant traite ce cas par une copie brute, pas par un plantage.
    @Test
    fun degenerateDimensionsFallBackToNoSampling() {
        assertEquals(1, PhotoReducer.sampleSizeFor(0, target))
        assertEquals(1, PhotoReducer.sampleSizeFor(-1, target))
        assertEquals(1, PhotoReducer.sampleSizeFor(4000, 0))
    }

    @Test
    fun landscapeAndPortraitBothCapTheirLongSide() {
        // 4:3, le format d'un capteur de téléphone.
        assertEquals(2048 to 1536, PhotoReducer.scaledSize(4032, 3024, target))
        assertEquals(1536 to 2048, PhotoReducer.scaledSize(3024, 4032, target))
        // 3:2, celui d'un reflex, avec un arrondi qui ne tombe pas juste.
        assertEquals(2048 to 1365, PhotoReducer.scaledSize(6000, 4000, target))
    }

    // Jamais d'agrandissement : c'est ce qui rend la copie brute légitime pour une petite photo.
    @Test
    fun anImageUnderTheTargetIsReturnedUntouched() {
        assertEquals(800 to 600, PhotoReducer.scaledSize(800, 600, target))
        assertEquals(2048 to 1536, PhotoReducer.scaledSize(2048, 1536, target))
    }

    // Panorama extrême : le petit côté arrondirait à 0, que createScaledBitmap refuse.
    @Test
    fun anExtremePanoramaKeepsAtLeastOnePixelOnItsShortSide() {
        val (width, height) = PhotoReducer.scaledSize(60000, 20, target)
        assertEquals(target, width)
        assertEquals(1, height)
    }
}
