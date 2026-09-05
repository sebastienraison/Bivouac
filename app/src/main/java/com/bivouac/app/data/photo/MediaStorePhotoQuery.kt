package com.bivouac.app.data.photo

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * RIC-43 : ce que MediaStore sait de la photo d'origine au moment de l'ajout, relevé une fois puis
 * dénormalisé dans LoggedTrackPhotoEntity : voir ses colonnes source* pour l'usage prévu
 * (re-acquisition depuis la galerie, RIC-151).
 *
 * Tout est nullable et ne l'est pas par prudence de façade : le fournisseur ne garantit aucune de
 * ces colonnes (une image indexée sans nom d'affichage, un fournisseur tiers qui ne les expose
 * pas), et RELATIVE_PATH n'existe qu'à partir de l'API 29.
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

/**
 * RIC-43 : les deux périmètres que le sélecteur interne sait présenter.
 *
 * [TRACK_DATES] est le mode par défaut et la raison d'être du sélecteur : les photos d'une sortie
 * ont été prises pendant cette sortie, les chercher à la main dans toute une pellicule n'a pas de
 * sens. [WHOLE_GALLERY] existe parce que ce raccourci a de vraies exceptions, toutes rencontrées :
 * une capture d'écran de la météo ou de l'itinéraire, une photo reçue d'un compagnon de rando et
 * enregistrée le lendemain, une photo dont l'appareil n'a pas horodaté l'EXIF (DATE_TAKEN vide,
 * donc invisible d'une recherche par date, par construction).
 */
enum class PhotoPickerScope { TRACK_DATES, WHOLE_GALLERY }

// RIC-43 : ce que le sélecteur interne interroge. Ne s'exécute jamais sans permission galerie
// accordée au préalable : pas de repli automatique ici, c'est à l'appelant de décider quoi faire
// d'un refus (voir JournalScreen, qui l'explique plutôt que de contourner).
object MediaStorePhotoQuery {

    // Marge avant/après la trace elle-même : une photo prise au parking avant de partir, ou juste
    // après être arrivé, ne doit pas manquer parce qu'elle précède ou suit de peu le premier ou le
    // dernier point GPS enregistré.
    private const val MARGIN_MILLIS = 2 * 60 * 60 * 1000L

    fun findInRange(resolver: ContentResolver, startMillis: Long, endMillis: Long): List<Uri> {
        val selection = "${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?"
        val args = arrayOf(
            (startMillis - MARGIN_MILLIS).toString(),
            (endMillis + MARGIN_MILLIS).toString(),
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} ASC"
        return queryIds(resolver, selection, args, sortOrder)
    }

    /**
     * Toute la pellicule visible par l'app, la plus récente d'abord.
     *
     * Tri sur DATE_ADDED et non DATE_TAKEN, contrairement à [findInRange] : DATE_TAKEN est vide
     * pour une bonne partie de ce que ce mode sert justement à retrouver (captures d'écran, images
     * reçues), et trier sur une colonne vide regrouperait tout ce lot en bloc au même endroit.
     * DATE_ADDED est toujours renseigné et rend l'ordre auquel une pellicule ressemble.
     *
     * Volontairement sans plafond : un plafond ne ferait pas disparaître une grosse pellicule, il
     * ferait disparaître des photos, en silence. Le coût reste une liste d'Uri (la grille, elle,
     * est paresseuse) et le temps de la requête est couvert par le spinner du sélecteur.
     */
    fun findAll(resolver: ContentResolver): List<Uri> =
        queryIds(resolver, selection = null, args = null, sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC")

    private fun queryIds(
        resolver: ContentResolver,
        selection: String?,
        args: Array<String>?,
        sortOrder: String,
    ): List<Uri> {
        val projection = arrayOf(MediaStore.Images.Media._ID)
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
     * Métadonnées d'origine d'une photo sélectionnée, quel que soit le périmètre dans lequel elle
     * a été choisie (voir [PhotoPickerScope]).
     *
     * Tout échec (colonne absente, fournisseur qui ne répond pas, permission révoquée entre-temps) rend
     * [PhotoSourceMetadata.EMPTY] plutôt qu'une exception : ces colonnes sont un bonus pour plus
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
