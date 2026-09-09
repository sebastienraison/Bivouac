package com.bivouac.app.data.photo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * RIC-157 : ce qu'il faut faire des octets d'une photo à l'import, décidé AVANT d'en décoder le
 * moindre pixel (lecture des seules dimensions de l'en-tête).
 *
 * Le plan existe séparément de l'écriture parce que l'extension du fichier de transit en dépend :
 * une copie réduite est un JPEG quelle que soit l'origine (HEIC, PNG, WebP), une copie brute garde
 * l'extension de sa source. Décider après avoir déjà nommé le fichier laisserait des `.heic`
 * contenant du JPEG.
 */
enum class PhotoCopyPlan {
    /** Redimensionner et réencoder : voir [PhotoReducer.writeReduced]. */
    REDUCE,

    /**
     * Recopier les octets tels quels : la photo est déjà sous la cible, l'agrandir n'aurait aucun
     * sens et la réencoder ne ferait que perdre de la qualité pour un gain nul, voire négatif.
     *
     * La ligne est quand même marquée [PhotoStorageMode.REDUCED] : ce que la colonne enregistre,
     * c'est la POLITIQUE appliquée à l'import, pas la nature du fichier obtenu. C'est ce dont le
     * lot B a besoin des deux côtés : sa passe de recompression du stock doit sauter cette photo
     * (il n'y a rien à y gagner, ni maintenant ni jamais), et la marquer FULL la lui ferait
     * réexaminer indéfiniment. Contrepartie assumée : la montée en qualité dans la visionneuse
     * proposera peut-être de résoudre un original strictement identique à la copie locale, ce qui
     * n'affiche rien de faux, seulement rien de nouveau.
     */
    COPY_ALREADY_SMALL,

    /**
     * Recopier les octets tels quels parce que l'image n'est pas décodable ici (format que la
     * plateforme ne sait pas ouvrir, fichier tronqué).
     *
     * La ligne est marquée [PhotoStorageMode.FULL], contrairement au cas ci-dessus : le fichier
     * local EST une copie intégrale, elle pèse ce qu'elle pèse, et la présenter comme réduite
     * mettrait le lot B hors d'état de la reprendre. Cas franchement exceptionnel : la photo vient
     * d'être affichée dans le sélecteur, donc la plateforme sait la décoder.
     */
    COPY_UNDECODABLE,
}

/**
 * RIC-157 : la fabrication de la copie réduite d'une photo du Journal.
 *
 * ⚠️ L'empreinte SHA-256 de la ligne est calculée sur les octets D'ORIGINE, avant tout passage ici :
 * c'est elle qui permettra de reconnaître l'original dans la galerie (voir PhotoOriginalResolver),
 * et elle n'aurait plus aucun sens calculée sur une copie réencodée. Rien dans ce fichier ne doit
 * jamais être appelé avant l'empreinte.
 *
 * ⚠️ Le GPS n'est PAS recopié dans l'EXIF de la copie, et ce n'est pas un oubli : la copie locale
 * n'en a jamais porté. Le flux MediaStore est expurgé du GPS, et la position vit dénormalisée en
 * base (colonnes latitude/longitude de LoggedTrackPhotoEntity). Y écrire des coordonnées créerait
 * une donnée de localisation là où l'app n'en a jamais produit.
 */
object PhotoReducer {

    private const val TAG = "PhotoReducer"

    /**
     * Décide sans décoder : `inJustDecodeBounds` ne lit que l'en-tête, il n'alloue aucun bitmap.
     * C'est ce qui permet de trancher le sort d'une photo de 100 Mpx sans jamais risquer de la
     * charger entière.
     */
    fun planFor(resolver: ContentResolver, uri: Uri): PhotoCopyPlan {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
            .onFailure { Log.w(TAG, "Dimensions illisibles, copie brute", it) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return PhotoCopyPlan.COPY_UNDECODABLE
        val longSide = max(bounds.outWidth, bounds.outHeight)
        return if (longSide <= PhotoStoragePolicy.REDUCED_LONG_SIDE_PX) {
            PhotoCopyPlan.COPY_ALREADY_SMALL
        } else {
            PhotoCopyPlan.REDUCE
        }
    }

    /**
     * Écrit dans [target] la copie réduite de la photo désignée par [uri].
     *
     * Décodage en deux temps, jamais en plein format : `inSampleSize` (puissance de 2, seul pas que
     * BitmapFactory sache appliquer pendant le décodage) descend au plus près au-dessus de la
     * cible, puis un unique redimensionnement exact finit le travail. Le bitmap intermédiaire fait
     * donc au pire deux fois la cible sur chaque côté, jamais les 100 Mpx du fichier.
     *
     * @return false si le décodage ou l'écriture échouent : l'appelant doit alors se rabattre sur
     *   une copie brute, et [target] est laissé effacé. Jamais d'exception : une photo qui résiste
     *   ne doit pas emporter le reste du lot.
     */
    fun writeReduced(resolver: ContentResolver, uri: Uri, target: File): Boolean {
        val decoded = decodeSampled(resolver, uri) ?: return false
        return try {
            val (width, height) = scaledSize(decoded.width, decoded.height, PhotoStoragePolicy.REDUCED_LONG_SIDE_PX)
            // filter = true : sans lui, un facteur d'échelle non entier produit un crénelage très
            // visible sur les lignes fines (câbles, arêtes, poteaux), typiques d'une photo de rando.
            val scaled = if (width == decoded.width && height == decoded.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, width, height, true)
            }
            try {
                target.outputStream().use { out ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, PhotoStoragePolicy.REDUCED_JPEG_QUALITY, out)) {
                        return false
                    }
                }
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
            copyExifSubset(resolver, uri, target)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Réduction impossible, copie brute à la place", e)
            target.delete()
            false
        } catch (e: OutOfMemoryError) {
            // Rattrapé explicitement : c'est le seul échec que la réduction puisse provoquer par
            // elle-même, et il ne doit pas tuer le process pour une photo que la copie brute
            // sauverait très bien.
            Log.w(TAG, "Mémoire insuffisante pour réduire, copie brute à la place", e)
            target.delete()
            false
        } finally {
            decoded.recycle()
        }
    }

    private fun decodeSampled(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(
                max(bounds.outWidth, bounds.outHeight),
                PhotoStoragePolicy.REDUCED_LONG_SIDE_PX,
            )
        }
        return runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.onFailure { Log.w(TAG, "Décodage impossible", it) }.getOrNull()
    }

    /**
     * Les trois seuls tags recopiés sur la copie réduite : la date de prise de vue, son décalage
     * horaire quand l'appareil l'écrit, et l'orientation.
     *
     * L'app elle-même ne les relit jamais (tout est dénormalisé en base à l'import) : ils sont là
     * pour que le fichier reste juste vu de l'extérieur, une fois partagé ou exporté. L'orientation
     * en particulier n'est pas cosmétique : `BitmapFactory` ne redresse pas les pixels, la copie
     * sort donc exactement dans le même sens que l'original, et sans le tag toute photo prise en
     * portrait s'afficherait couchée.
     *
     * Best effort complet : un EXIF qui refuse de s'écrire ne doit pas faire échouer un import qui
     * a par ailleurs produit une image parfaitement valide.
     */
    private fun copyExifSubset(resolver: ContentResolver, uri: Uri, target: File) {
        runCatching {
            val source = resolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return
            val original = source.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            val offset = source.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            val orientation = source.getAttribute(ExifInterface.TAG_ORIENTATION)
            val destination = ExifInterface(target)
            if (original != null) destination.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, original)
            if (offset != null) destination.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            if (orientation != null) destination.setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
            destination.saveAttributes()
        }.onFailure { Log.w(TAG, "EXIF non recopié sur la copie réduite", it) }
    }

    /**
     * Le plus grand `inSampleSize` (puissance de 2) qui laisse encore le grand côté AU-DESSUS de la
     * cible, jamais en dessous : sous-échantillonner d'un cran de trop coûterait de la définition
     * qu'aucun redimensionnement ultérieur ne peut rendre.
     *
     * Corollaire : le bitmap décodé fait au plus deux fois la cible sur son grand côté (donc au
     * plus quatre fois sa surface). C'est la borne mémoire de toute cette opération, et elle ne
     * dépend pas de la taille du fichier d'entrée.
     *
     * Pure et publique pour être exercée en JVM : c'est le calcul qui décide combien de mémoire
     * l'app va demander.
     */
    fun sampleSizeFor(longSidePx: Int, targetLongSidePx: Int): Int {
        if (longSidePx <= 0 || targetLongSidePx <= 0) return 1
        var sample = 1
        while (longSidePx / (sample * 2) >= targetLongSidePx) sample *= 2
        return sample
    }

    /**
     * Les dimensions exactes visées, à rapport d'aspect conservé : le grand côté vaut
     * [targetLongSidePx], le petit suit.
     *
     * Une image déjà sous la cible est rendue inchangée : rien ici n'agrandit jamais quoi que ce
     * soit. Plancher à 1 px sur le petit côté, pour qu'un panorama extrême (rapport supérieur à
     * 2048:1) ne produise pas une hauteur nulle, que `createScaledBitmap` refuserait.
     *
     * Pure et publique pour la même raison que [sampleSizeFor].
     */
    fun scaledSize(width: Int, height: Int, targetLongSidePx: Int): Pair<Int, Int> {
        val longSide = max(width, height)
        if (longSide <= targetLongSidePx || longSide <= 0) return width to height
        val scale = targetLongSidePx.toDouble() / longSide
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
}
