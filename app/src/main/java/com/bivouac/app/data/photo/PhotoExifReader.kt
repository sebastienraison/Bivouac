package com.bivouac.app.data.photo

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

// Ce qu'on retient d'une photo à l'ajout (RIC-43) : takenAtMillis/latitude/longitude alimentent
// LoggedTrackPhotoEntity et le placement sur la trace (voir PhotoPositionCorrelator). Tout est
// nullable — beaucoup de photos (capture d'écran, image envoyée par un tiers, appareil sans GPS)
// n'ont ni l'un ni l'autre, cas déjà prévu par la spec ("rien n'est perdu, juste moins bien
// situé").
data class PhotoExifData(val takenAtMillis: Long?, val latitude: Double?, val longitude: Double?)

object PhotoExifReader {

    fun read(resolver: ContentResolver, uri: Uri): PhotoExifData {
        val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return EMPTY
        val latLong = exif.latLong
        return PhotoExifData(
            // getGpsDateTime() d'abord : dérivé de TAG_GPS_DATESTAMP/TAG_GPS_TIMESTAMP, en UTC et
            // donc non ambigu. getDateTimeOriginal() (TAG_DATETIME_ORIGINAL) n'a pas de fuseau
            // fiable en repli — horloge du téléphone au moment de la prise, exactement le cas que
            // la tolérance de PhotoPositionCorrelator est censée absorber.
            takenAtMillis = exif.gpsDateTime ?: exif.dateTimeOriginal,
            latitude = latLong?.get(0),
            longitude = latLong?.get(1),
        )
    }

    private val EMPTY = PhotoExifData(null, null, null)
}
