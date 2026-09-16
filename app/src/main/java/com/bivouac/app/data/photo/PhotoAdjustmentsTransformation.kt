package com.bivouac.app.data.photo

import android.graphics.Bitmap
import android.graphics.Matrix
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation

/**
 * RIC-143 / RIC-144 : le MÉCANISME UNIQUE de rendu des ajustements, pour toutes les surfaces qui
 * affichent un fichier photo.
 *
 * Une transformation Coil et pas cinq rendus parallèles : le bandeau, la grille « tout voir », la
 * bulle de la carte et les deux couches de la visionneuse passent toutes par le même chargeur
 * d'images. Poser la règle une fois là où elles se rejoignent est ce qui garantit qu'aucune ne
 * raconte une autre histoire, et c'est exactement le piège que ce ticket devait éviter : une photo
 * recadrée dans le bandeau mais entière dans la bulle.
 *
 * Coil applique l'orientation EXIF au décodage, AVANT les transformations : la rotation posée ici
 * est donc bien un delta par-dessus, ce que les colonnes décrivent (voir [PhotoAdjustments]).
 *
 * Mémoire : le bitmap reçu est celui que Coil a décodé POUR LA CIBLE, donc déjà sous-échantillonné
 * à la taille du composable. Le découpage se fait dessus, jamais sur un décodage plein format : une
 * photo de 12 Mpx recadrée pour une vignette de 72 dp ne fait jamais entrer 12 Mpx en mémoire.
 * Contrepartie assumée et inchangée par rapport à l'existant : un recadrage serré affiché en plein
 * écran est agrandi depuis un bitmap de la taille de l'écran, exactement comme l'est déjà un zoom
 * pincé sur une photo non recadrée.
 */
data class PhotoAdjustmentsTransformation(
    private val adjustments: PhotoAdjustments,
) : Transformation {

    /**
     * ⚠️ C'est cette clé qui invalide l'image quand les ajustements changent : Coil la fait entrer
     * dans la clé du cache mémoire. Sans les valeurs dedans, une photo qu'on vient de tourner
     * ressortirait du cache dans son ancien état, et le seul moyen de la voir bouger serait de
     * quitter l'écran. Le fichier, lui, n'a pas changé : le cache disque reste donc légitimement
     * indexé sur le seul chemin.
     */
    override val cacheKey: String = buildString {
        append("photo-adjustments:r")
        append(adjustments.normalizedRotationQuarterTurns)
        val crop = adjustments.cropRect?.sanitized()
        if (crop != null) {
            append(":c")
            append(crop.left)
            append(',')
            append(crop.top)
            append(',')
            append(crop.right)
            append(',')
            append(crop.bottom)
        }
    }

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (adjustments.isIdentity) return input
        val rotated = rotate(input)
        val crop = adjustments.cropRect?.sanitized()
        if (crop == null || crop.isFullFrame) return rotated
        val pixels = cropPixelRect(rotated.width, rotated.height, crop)
        val cropped = Bitmap.createBitmap(rotated, pixels.left, pixels.top, pixels.width, pixels.height)
        // L'intermédiaire de rotation est à nous, et à nous seuls : le recycler tout de suite évite
        // de garder une image entière en mémoire le temps que le ramasse-miettes s'en aperçoive.
        // Jamais [input], que Coil possède, ni un bitmap que createBitmap aurait rendu tel quel
        // (ce qu'il fait quand la région demandée est l'image entière).
        if (rotated !== input && rotated !== cropped) rotated.recycle()
        return cropped
    }

    private fun rotate(input: Bitmap): Bitmap {
        val quarterTurns = adjustments.normalizedRotationQuarterTurns
        if (quarterTurns == 0) return input
        val matrix = Matrix().apply { postRotate(quarterTurns * 90f) }
        return Bitmap.createBitmap(input, 0, 0, input.width, input.height, matrix, true)
    }
}

/**
 * RIC-143 / RIC-144 : le branchement des ajustements sur une requête d'image, quelle que soit la
 * surface. Toutes passent par ici, y compris la bulle de la carte, qui est une View et non un
 * composable (`ImageView.load { ... }` construit le même ImageRequest.Builder).
 *
 * Aucune transformation posée quand il n'y a rien à ajuster : c'est le cas courant, et une
 * transformation neutre coûterait une recopie de bitmap par image affichée, plus une clé de cache
 * différente de celle des images non ajustées, donc un doublon en cache mémoire.
 */
fun ImageRequest.Builder.adjustedBy(adjustments: PhotoAdjustments): ImageRequest.Builder =
    if (adjustments.isIdentity) this else transformations(PhotoAdjustmentsTransformation(adjustments))
