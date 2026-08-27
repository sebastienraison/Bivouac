package com.bivouac.app.data.photo

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * RIC-43 : ce que MediaStore sait de la photo d'origine au moment de l'ajout, relevé une fois puis
 * dénormalisé dans LoggedTrackPhotoEntity — voir ses colonnes source* pour l'usage prévu
 * (re-acquisition depuis la galerie, RIC-151).
 *
 * Tout est nullable et ne l'est pas par prudence de façade : une Uri du Photo Picker est
 * volontairement caviardée par le système, elle ne répond pas forcément à ces colonnes, et
 * RELATIVE_PATH n'existe qu'à partir de l'API 29.
 */
data class PhotoSourceMetadata(
    val displayName: String? = null,
    val relativePath: String? = null,
    val dateTakenMillis: Long? = null,
) {
    companion object {
        val EMPTY = PhotoSourceMetadata()
    }
}

// RIC-43 : requete filtree par plage de dates (extension optionnelle, voir PhotoLibraryPermission
// pour la permission qui la debloque). Ne s'execute jamais sans permission accordee au prealable -
// pas de repli automatique ici, c'est a l'appelant de decider quoi faire d'un refus.
object MediaStorePhotoQuery {

    // Marge avant/apres la trace elle-meme : une photo prise au parking avant de partir, ou juste
    // apres etre arrive, ne doit pas manquer parce qu'elle precede/suit de peu le premier/dernier
    // point GPS enregistre.
    private const val MARGIN_MILLIS = 2 * 60 * 60 * 1000L

    fun findInRange(resolver: ContentResolver, startMillis: Long, endMillis: Long): List<Uri> {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?"
        val args = arrayOf(
            (startMillis - MARGIN_MILLIS).toString(),
            (endMillis + MARGIN_MILLIS).toString(),
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} ASC"
        val uris = mutableListOf<Uri>()
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                uris += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn))
            }
        }
        return uris
    }

    /**
     * Métadonnées d'origine d'une photo sélectionnée, quelle que soit la provenance de son [uri]
     * (Photo Picker générique ou sélecteur filtré par date).
     *
     * Aucune permission n'est requise ni supposée : l'Uri porte sa propre autorisation de lecture.
     * Tout échec (colonne absente, Uri caviardée, permission révoquée entre-temps) rend
     * [PhotoSourceMetadata.EMPTY] plutôt qu'une exception — ces colonnes sont un bonus pour plus
     * tard, jamais une condition de l'ajout de la photo.
     */
    fun readSource(resolver: ContentResolver, uri: Uri): PhotoSourceMetadata = runCatching {
        val projection = buildList {
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.Images.Media.DATE_TAKEN)
        }.toTypedArray()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use PhotoSourceMetadata.EMPTY
            PhotoSourceMetadata(
                displayName = cursor.stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME),
                relativePath = cursor.stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH),
                dateTakenMillis = cursor.longOrNull(MediaStore.Images.Media.DATE_TAKEN),
            )
        } ?: PhotoSourceMetadata.EMPTY
    }.getOrDefault(PhotoSourceMetadata.EMPTY)

    // getColumnIndex (et non getColumnIndexOrThrow) : une colonne absente de la projection réelle
    // renvoyée par le fournisseur est un cas normal ici, pas une erreur.
    private fun android.database.Cursor.stringOrNull(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }

    private fun android.database.Cursor.longOrNull(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getLong(it) }
}
