package com.bivouac.app.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-143 / RIC-144 : la géométrie des ajustements, en pur calcul.
 *
 * Tout ce qui est vérifiable sans écran l'est ici : la rotation d'un rectangle normalisé, son
 * passage en pixels, les bornes, et surtout l'IDENTITÉ, c'est-à-dire le fait qu'une photo sans
 * ajustement traverse la chaîne sans être touchée. C'est le cas de l'écrasante majorité des photos
 * d'un Journal, et celui qu'aucune régression ne doit atteindre.
 */
class PhotoAdjustmentsTest {

    // --- Identité ------------------------------------------------------------------------------

    @Test
    fun `aucun ajustement est l'identite`() {
        assertTrue(PhotoAdjustments.NONE.isIdentity)
        assertEquals(1000 to 800, PhotoAdjustments.NONE.displayedSize(1000, 800))
    }

    @Test
    fun `une rotation de quatre quarts de tour retombe sur l'identite`() {
        val adjustments = PhotoAdjustments(rotationQuarterTurns = 4)
        assertEquals(0, adjustments.normalizedRotationQuarterTurns)
        assertTrue(adjustments.isIdentity)
    }

    @Test
    fun `un compte negatif de quarts de tour se ramene dans 0 a 3`() {
        assertEquals(3, PhotoAdjustments(rotationQuarterTurns = -1).normalizedRotationQuarterTurns)
        assertEquals(2, PhotoAdjustments(rotationQuarterTurns = -6).normalizedRotationQuarterTurns)
    }

    @Test
    fun `un recadrage plein cadre n'est pas l'identite mais ne rogne rien`() {
        // Distinction voulue : « pas de recadrage » (null) et « cadre posé sur l'image entière »
        // sont deux états différents en base. Aucun des deux ne rogne quoi que ce soit.
        val adjustments = PhotoAdjustments(cropRect = NormalizedCropRect.FULL)
        assertFalse(adjustments.isIdentity)
        assertTrue(NormalizedCropRect.FULL.isFullFrame)
        assertEquals(1000 to 800, adjustments.displayedSize(1000, 800))
    }

    // --- Rotation ------------------------------------------------------------------------------

    @Test
    fun `un quart de tour horaire echange largeur et hauteur`() {
        assertEquals(800 to 1000, PhotoAdjustments(rotationQuarterTurns = 1).displayedSize(1000, 800))
        assertEquals(1000 to 800, PhotoAdjustments(rotationQuarterTurns = 2).displayedSize(1000, 800))
        assertEquals(800 to 1000, PhotoAdjustments(rotationQuarterTurns = 3).displayedSize(1000, 800))
    }

    @Test
    fun `tourner a droite envoie le coin haut-gauche en haut a droite`() {
        // Un petit cadre collé au coin haut-gauche de l'image : après un quart de tour horaire, la
        // même portion de paysage est en haut à DROITE. C'est la propriété qui fait que le cadre
        // « suit » l'image au lieu de désigner une autre zone.
        //
        // Valeurs choisies exactes en binaire (quarts et huitièmes) : ces tests comparent des
        // rectangles au bit près, et 1f - 0.9f ne vaut pas 0.1f en Float.
        val corner = NormalizedCropRect(0f, 0f, 0.25f, 0.125f)

        val rotated = corner.rotatedByQuarterTurns(1)

        assertEquals(NormalizedCropRect(0.875f, 0f, 1f, 0.25f), rotated)
    }

    @Test
    fun `tourner a gauche est l'inverse exact de tourner a droite`() {
        val crop = NormalizedCropRect(0.125f, 0.25f, 0.625f, 0.875f)
        val adjustments = PhotoAdjustments(rotationQuarterTurns = 1, cropRect = crop)

        val roundTrip = adjustments.rotatedRight().rotatedLeft()

        assertEquals(adjustments, roundTrip)
    }

    @Test
    fun `quatre rotations dans le meme sens ramenent le cadre a sa place`() {
        val crop = NormalizedCropRect(0.125f, 0.25f, 0.625f, 0.875f)

        val rotated = crop.rotatedByQuarterTurns(4)

        assertEquals(crop, rotated)
    }

    @Test
    fun `le cadre suit l'image quand on la tourne, il n'est jamais reinitialise`() {
        // Décidé en conception : recadrer puis redresser est un enchaînement naturel, perdre le
        // cadre au passage serait une punition.
        val adjustments = PhotoAdjustments(cropRect = NormalizedCropRect(0.125f, 0.25f, 0.625f, 0.875f))

        val turned = adjustments.rotatedRight()

        assertEquals(1, turned.normalizedRotationQuarterTurns)
        assertEquals(NormalizedCropRect(0.125f, 0.125f, 0.75f, 0.625f), turned.cropRect)
    }

    // --- Passage en pixels ---------------------------------------------------------------------

    @Test
    fun `le rectangle normalise se traduit en pixels de l'image tournee`() {
        val adjustments = PhotoAdjustments(cropRect = NormalizedCropRect(0.25f, 0.5f, 0.75f, 1f))

        assertEquals(500 to 400, adjustments.displayedSize(1000, 800))
        assertEquals(
            CropPixelRect(250, 400, 750, 800),
            cropPixelRect(1000, 800, adjustments.cropRect!!),
        )
    }

    @Test
    fun `rotation et recadrage se composent dans le bon ordre`() {
        // Le cadre est exprimé dans le repère de l'image DÉJÀ tournée : sur une image 1000x800
        // tournée d'un quart de tour (donc 800x1000), une moitié gauche vaut 400 px de large.
        val adjustments = PhotoAdjustments(
            rotationQuarterTurns = 1,
            cropRect = NormalizedCropRect(0f, 0f, 0.5f, 1f),
        )

        assertEquals(400 to 1000, adjustments.displayedSize(1000, 800))
    }

    @Test
    fun `un cadre plus petit qu'un pixel garde quand meme un pixel`() {
        // Sur une vignette de 72 dp, un recadrage serré peut valoir moins d'un pixel : une largeur
        // nulle ferait lever une exception à Bitmap.createBitmap, pas afficher une image vide.
        val crop = NormalizedCropRect(0.5f, 0.5f, 0.5001f, 0.5001f)

        val pixels = cropPixelRect(40, 40, crop)

        assertTrue(pixels.width >= 1)
        assertTrue(pixels.height >= 1)
        assertTrue(pixels.right <= 40)
        assertTrue(pixels.bottom <= 40)
    }

    // --- Bornes et hygiène ---------------------------------------------------------------------

    @Test
    fun `un rectangle hors bornes est ramene dans l'image`() {
        val crop = NormalizedCropRect(-0.5f, -2f, 1.5f, 3f).sanitized()

        assertEquals(NormalizedCropRect(0f, 0f, 1f, 1f), crop)
    }

    @Test
    fun `un rectangle aux cotes inverses est remis a l'endroit`() {
        val crop = NormalizedCropRect(0.8f, 0.9f, 0.2f, 0.3f).sanitized()

        assertEquals(NormalizedCropRect(0.2f, 0.3f, 0.8f, 0.9f), crop)
    }

    @Test
    fun `un rectangle degenere recoit un cote minimal`() {
        val crop = NormalizedCropRect(0.4f, 0.4f, 0.4f, 0.4f).sanitized()

        // À la précision du Float près : 0.41f - 0.4f ne vaut pas exactement 0.01f, et ce qui
        // compte ici est qu'un rectangle vide ne puisse jamais sortir d'ici.
        assertEquals(NormalizedCropRect.MIN_SIDE, crop.width, 1e-6f)
        assertEquals(NormalizedCropRect.MIN_SIDE, crop.height, 1e-6f)
    }

    // --- Aller-retour avec la base -------------------------------------------------------------

    @Test
    fun `une ligne sans ajustement se lit comme l'identite`() {
        val photo = photoEntity()

        assertTrue(photo.adjustments.isIdentity)
        assertNull(photo.adjustments.cropRect)
    }

    @Test
    fun `les ajustements font l'aller-retour avec les colonnes`() {
        val adjustments = PhotoAdjustments(
            rotationQuarterTurns = 3,
            cropRect = NormalizedCropRect(0.1f, 0.2f, 0.6f, 0.9f),
        )

        val stored = photoEntity().withAdjustments(adjustments)

        assertEquals(3, stored.rotationQuarterTurns)
        assertEquals(0.1f, stored.cropLeft!!, 1e-6f)
        assertEquals(0.9f, stored.cropBottom!!, 1e-6f)
        assertEquals(adjustments, stored.adjustments)
    }

    @Test
    fun `retirer les ajustements vide bien les quatre colonnes`() {
        val stored = photoEntity()
            .withAdjustments(PhotoAdjustments(2, NormalizedCropRect(0.1f, 0.2f, 0.6f, 0.9f)))
            .withAdjustments(PhotoAdjustments.NONE)

        assertEquals(0, stored.rotationQuarterTurns)
        assertNull(stored.cropLeft)
        assertNull(stored.cropTop)
        assertNull(stored.cropRight)
        assertNull(stored.cropBottom)
        assertTrue(stored.adjustments.isIdentity)
    }

    @Test
    fun `une ligne au recadrage incomplet est traitee comme non recadree`() {
        // Cas d'une restauration de sauvegarde bricolée ou d'un futur bug : mieux vaut « pas de
        // recadrage » qu'un rectangle composé à partir de trous.
        val photo = photoEntity().copy(cropLeft = 0.1f, cropTop = 0.2f, cropRight = null, cropBottom = 0.9f)

        assertNull(photo.adjustments.cropRect)
    }

    private fun photoEntity() = com.bivouac.app.data.db.LoggedTrackPhotoEntity(
        id = 1L,
        trackId = "track-1",
        filePath = "photos/track-1-abc.jpg",
        addedAtMillis = 1_780_300_900_000L,
        contentHash = "abc123",
    )
}
