package com.bivouac.app.data.photo

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RIC-143 / RIC-144 : la transformation qui porte le rendu des ajustements sur TOUTES les surfaces,
 * exercée sur un bitmap dont on connaît chaque pixel.
 *
 * En test instrumenté et non en JVM : Bitmap, Matrix et createBitmap sont du vrai code Android, et
 * c'est justement leur comportement (quel pixel atterrit où après un quart de tour) qu'il s'agit de
 * vérifier, pas une reimplémentation Robolectric.
 *
 * L'image de départ est construite ici plutôt que versée en fixture : ce qu'on mesure est une
 * géométrie de pixels, et quatre quadrants de couleurs franches la rendent lisible à l'assertion
 * près, là où une photo réelle n'apporterait que du bruit (et n'aurait rien à faire dans un dépôt
 * public, voir la même justification dans BivouacDatabaseMigrationTest).
 */
@RunWith(AndroidJUnit4::class)
class PhotoAdjustmentsTransformationInstrumentedTest {

    /**
     * 40 x 20 : volontairement pas carré, pour qu'un échange largeur/hauteur se voie. Quadrants :
     * rouge en haut à gauche, vert en haut à droite, bleu en bas à gauche, jaune en bas à droite.
     */
    private fun quadrants(): Bitmap {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        for (x in 0 until 40) {
            for (y in 0 until 20) {
                val color = when {
                    x < 20 && y < 10 -> Color.RED
                    x >= 20 && y < 10 -> Color.GREEN
                    x < 20 -> Color.BLUE
                    else -> Color.YELLOW
                }
                bitmap.setPixel(x, y, color)
            }
        }
        return bitmap
    }

    private fun transform(adjustments: PhotoAdjustments, input: Bitmap = quadrants()): Bitmap =
        runBlocking { PhotoAdjustmentsTransformation(adjustments).transform(input, Size.ORIGINAL) }

    @Test
    fun sansAjustementLeBitmapRessortTelQuel() {
        val input = quadrants()

        val output = transform(PhotoAdjustments.NONE, input)

        // Le MÊME objet, pas une copie : c'est ce qui garantit qu'une photo jamais ajustée (donc
        // l'écrasante majorité) ne paie strictement rien.
        assertSame(input, output)
    }

    @Test
    fun unQuartDeTourHoraireEchangeLesDimensionsEtDeplaceLesQuadrants() {
        val output = transform(PhotoAdjustments(rotationQuarterTurns = 1))

        assertEquals(20, output.width)
        assertEquals(40, output.height)
        // Le rouge (haut-gauche) passe en haut à droite, le bleu (bas-gauche) passe en haut à gauche.
        assertEquals(Color.BLUE, output.getPixel(5, 5))
        assertEquals(Color.RED, output.getPixel(15, 5))
        assertEquals(Color.YELLOW, output.getPixel(5, 30))
        assertEquals(Color.GREEN, output.getPixel(15, 30))
    }

    @Test
    fun deuxQuartsDeTourRetournentL_imageSansChangerSesDimensions() {
        val output = transform(PhotoAdjustments(rotationQuarterTurns = 2))

        assertEquals(40, output.width)
        assertEquals(20, output.height)
        assertEquals(Color.YELLOW, output.getPixel(5, 5))
        assertEquals(Color.RED, output.getPixel(35, 15))
    }

    @Test
    fun unRecadrageNeGardeQueLaZoneRetenue() {
        // Moitié droite, moitié basse : le quadrant jaune, et lui seul.
        val output = transform(
            PhotoAdjustments(cropRect = NormalizedCropRect(0.5f, 0.5f, 1f, 1f)),
        )

        assertEquals(20, output.width)
        assertEquals(10, output.height)
        assertEquals(Color.YELLOW, output.getPixel(0, 0))
        assertEquals(Color.YELLOW, output.getPixel(19, 9))
    }

    @Test
    fun leRecadrageS_appliqueDansLeRepereDeL_imageDejaTournee() {
        // Après un quart de tour horaire, l'image fait 20 x 40 et sa moitié HAUTE porte le bleu
        // (à gauche) et le rouge (à droite). Un cadre sur la moitié haute doit donc rendre ces
        // deux-là, ce qui ne serait pas le cas si le cadre était appliqué avant la rotation.
        val output = transform(
            PhotoAdjustments(rotationQuarterTurns = 1, cropRect = NormalizedCropRect(0f, 0f, 1f, 0.5f)),
        )

        assertEquals(20, output.width)
        assertEquals(20, output.height)
        assertEquals(Color.BLUE, output.getPixel(5, 5))
        assertEquals(Color.RED, output.getPixel(15, 5))
    }

    /**
     * Les deux fichiers de la visionneuse (copie locale réduite et original pleine résolution, voir
     * RIC-157) n'ont pas les mêmes dimensions : c'est LA raison d'être du rectangle normalisé. Le
     * même rectangle doit donner le même cadrage sur les deux, sans quoi la photo saute au fondu.
     */
    @Test
    fun leMemeRectangleDonneLeMemeCadrageSurDeuxTaillesDeFichier() {
        val small = Bitmap.createScaledBitmap(quadrants(), 40, 20, false)
        val large = Bitmap.createScaledBitmap(quadrants(), 400, 200, false)
        val crop = NormalizedCropRect(0.5f, 0f, 1f, 0.5f)

        val fromSmall = transform(PhotoAdjustments(cropRect = crop), small)
        val fromLarge = transform(PhotoAdjustments(cropRect = crop), large)

        // Rapport largeur/hauteur identique, et le même contenu (le quadrant vert) dans les deux.
        assertEquals(
            fromSmall.width.toFloat() / fromSmall.height,
            fromLarge.width.toFloat() / fromLarge.height,
            0.01f,
        )
        assertEquals(Color.GREEN, fromSmall.getPixel(fromSmall.width / 2, fromSmall.height / 2))
        assertEquals(Color.GREEN, fromLarge.getPixel(fromLarge.width / 2, fromLarge.height / 2))
    }

    /**
     * La clé de cache porte les ajustements : c'est elle qui fait qu'une photo qu'on vient de
     * tourner ne ressort pas du cache mémoire dans son état précédent.
     */
    @Test
    fun laCleDeCacheChangeAvecLesAjustements() {
        val rotated = PhotoAdjustmentsTransformation(PhotoAdjustments(rotationQuarterTurns = 1)).cacheKey
        val rotatedTwice = PhotoAdjustmentsTransformation(PhotoAdjustments(rotationQuarterTurns = 2)).cacheKey
        val cropped = PhotoAdjustmentsTransformation(
            PhotoAdjustments(rotationQuarterTurns = 1, cropRect = NormalizedCropRect(0f, 0f, 0.5f, 0.5f)),
        ).cacheKey

        assertEquals(false, rotated == rotatedTwice)
        assertEquals(false, rotated == cropped)
    }
}
