package com.bivouac.app.data.photo

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

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
}
