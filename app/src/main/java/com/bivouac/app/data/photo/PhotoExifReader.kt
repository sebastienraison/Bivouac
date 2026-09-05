package com.bivouac.app.data.photo

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// Ce qu'on retient d'une photo à l'ajout (RIC-43) : takenAtMillis/latitude/longitude alimentent
// LoggedTrackPhotoEntity et le placement sur la trace (voir PhotoPositionCorrelator). Tout est
// nullable : beaucoup de photos (capture d'écran, image envoyée par un tiers, appareil sans GPS)
// n'ont ni l'un ni l'autre, cas déjà prévu par la spec ("rien n'est perdu, juste moins bien
// situé").
//
// takenAtZoneCertain dit si [takenAtMillis] repose sur un fuseau connu du fichier lui-même, ou
// sur la supposition « la photo a été prise dans le fuseau où tourne le téléphone aujourd'hui »
// (voir originalDateTimeMillis). C'est ce qui distingue une corrélation par horodatage fiable
// d'une corrélation seulement plausible, et c'est à ce titre que la donnée est persistée
// (LoggedTrackPhotoEntity.takenAtZoneCertain) puis affichée par la pastille de positionnement
// approximatif. Deux sources la rendent vraie : l'horodatage GPS, en UTC par construction, et le
// tag d'offset EXIF quand l'appareil l'écrit. Faux par défaut : en l'absence d'information, on
// n'affirme rien.
data class PhotoExifData(
    val takenAtMillis: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val takenAtZoneCertain: Boolean = false,
)

object PhotoExifReader {

    /**
     * RIC-43 : l'EXIF de la photo désignée par [uri].
     *
     * [requireOriginal] demande à MediaStore l'EXIF non expurgé, ce qu'il ne rend que si
     * ACCESS_MEDIA_LOCATION est accordée (voir PhotoLibraryPermission.isMediaLocationGranted, dont
     * c'est la valeur attendue ici). Sans ça, depuis Android 10, MediaStore retire les coordonnées
     * GPS de toute photo qu'il rend : une photo géolocalisée était lue comme si elle ne l'était
     * pas, et sa position exacte, présente dans le fichier, était remplacée par une corrélation
     * temporelle approximative.
     *
     * Repli explicite sur l'Uri ordinaire à la moindre difficulté : setRequireOriginal ne
     * s'applique qu'aux Uri MediaStore, et l'ouverture peut lever une SecurityException si la
     * permission a été retirée entre la vérification et la lecture. On lit alors sans GPS, ce qui
     * est exactement le comportement d'avant, dégradé, jamais bloqué.
     */
    fun read(resolver: ContentResolver, uri: Uri, requireOriginal: Boolean = false): PhotoExifData {
        if (requireOriginal && Build.VERSION.SDK_INT >= 29) {
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrNull()
                ?.let { original -> readOrNull(resolver, original)?.let { return it } }
        }
        return readOrNull(resolver, uri) ?: EMPTY
    }

    // Null (et non EMPTY) quand la lecture elle-même échoue : c'est ce qui permet à read() de
    // distinguer « cette Uri n'a rien donné, essayons l'autre » de « lu, mais sans métadonnée ».
    private fun readOrNull(resolver: ContentResolver, uri: Uri): PhotoExifData? =
        runCatching { resolver.openInputStream(uri)?.use { read(it) } }.getOrNull()

    /**
     * Surcharge par flux, séparée de la lecture d'Uri ci-dessus pour que la logique EXIF soit
     * testable sans ContentResolver ni appareil : voir PhotoExifReaderTest, qui lui passe une
     * fixture JPEG minimale écrite par ExifInterface lui-même.
     */
    fun read(input: InputStream): PhotoExifData {
        val exif = runCatching { ExifInterface(input) }.getOrNull() ?: return EMPTY
        val latLong = runCatching { exif.latLong }.getOrNull()
        // getGpsDateTime() d'abord : dérivé de TAG_GPS_DATESTAMP/TAG_GPS_TIMESTAMP, en UTC et donc
        // non ambigu (takenAtZoneCertain = true sans autre condition). Le repli DateTimeOriginal,
        // lui, demande une correction et n'est certain que s'il porte son tag d'offset, voir
        // originalDateTimeMillis.
        val gpsDateTime = runCatching { exif.gpsDateTime }.getOrNull()
        val original = if (gpsDateTime == null) originalDateTimeMillis(exif) else null
        return PhotoExifData(
            takenAtMillis = gpsDateTime ?: original?.millis,
            latitude = latLong?.get(0),
            longitude = latLong?.get(1),
            takenAtZoneCertain = if (gpsDateTime != null) true else original?.zoneCertain == true,
        )
    }

    // L'horodatage de prise de vue reconstitué, et la façon dont son fuseau a été obtenu : les
    // deux sont indissociables, d'où un porteur plutôt qu'un simple Long? rendu.
    private data class OriginalDateTime(val millis: Long, val zoneCertain: Boolean)

    /**
     * TAG_DATETIME_ORIGINAL, interprété dans le fuseau porté par TAG_OFFSET_TIME_ORIGINAL quand il
     * existe, et à défaut dans le fuseau du téléphone.
     *
     * Pourquoi ne pas simplement appeler `ExifInterface.getDateTimeOriginal()` : exifinterface
     * 1.3.7 parse cette date avec un SimpleDateFormat dont le TimeZone est forcé à UTC (vérifié
     * dans le bytecode de la lib) et n'applique un décalage que si le tag d'offset est présent.
     * Sans ce tag (le cas de la grande majorité des appareils), l'heure murale de la prise de vue
     * est donc rendue comme de l'UTC, alors que les points d'une trace GPX portent du vrai UTC. En
     * France l'été, l'écart systématique est de 2 h, très au-delà de la tolérance de
     * [PhotoPositionCorrelator] : la corrélation par horodatage renvoyait « aucune position » sans
     * le moindre signal, exactement dans le cas qu'elle était censée servir (photo sans GPS).
     * Accessoirement, la lib lit les chiffres du tag d'offset avant d'en vérifier le format, donc
     * un offset illisible fait carrément lever `getDateTimeOriginal()`.
     *
     * D'où le parsing en propre ici, qui ne dépend plus du comportement interne de la lib. Les
     * sous-secondes (TAG_SUBSEC_TIME_ORIGINAL) sont délibérément ignorées : elles ne changent rien
     * face à une tolérance de corrélation qui se compte en minutes.
     *
     * Limite résiduelle assumée : une photo prise dans un autre fuseau que celui du téléphone au
     * moment de l'import (voyage lointain, téléphone remis à l'heure locale depuis) reste décalée du
     * différentiel entre les deux fuseaux. Rare, et rien dans le fichier ne permet de le détecter,
     * puisque c'est précisément l'absence du tag d'offset qui pose problème. Le repositionnement
     * manuel reste la sortie de secours dans ce cas.
     */
    private fun originalDateTimeMillis(exif: ExifInterface): OriginalDateTime? {
        val raw = runCatching { exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) }.getOrNull() ?: return null
        // Même garde que la lib : un horodatage tout à zéro ("0000:00:00 00:00:00") est un
        // remplissage d'appareil, pas une date.
        if (raw.none { it in '1'..'9' }) return null
        val wallClock = PRIMARY_FORMATS.firstNotNullOfOrNull { format ->
            runCatching { LocalDateTime.parse(raw.trim(), format) }.getOrNull()
        } ?: return null
        val offset = runCatching { exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) }.getOrNull()
        val declaredZone = offset?.takeIf { OFFSET_PATTERN.matches(it) }
            ?.let { runCatching { ZoneOffset.of(it) as ZoneId }.getOrNull() }
        // Un offset illisible compte comme un offset absent, ici aussi : le fuseau du téléphone
        // sert de repli, et le résultat n'est donc pas certain pour autant.
        val zone = declaredZone ?: ZoneId.systemDefault()
        return OriginalDateTime(
            millis = wallClock.atZone(zone).toInstant().toEpochMilli(),
            zoneCertain = declaredZone != null,
        )
    }

    // Le format EXIF canonique, plus la variante à tirets que la lib tolère aussi : quelques
    // appareils l'écrivent, et l'accepter ne coûte qu'une tentative de parsing de plus.
    private val PRIMARY_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
    )

    // Signe, deux chiffres d'heures, deux-points, deux chiffres de minutes, heures plafonnées à 14
    // (la borne des fuseaux réels).
    private val OFFSET_PATTERN = Regex("^[+-](0\\d|1[0-4]):\\d{2}$")

    private val EMPTY = PhotoExifData(null, null, null, takenAtZoneCertain = false)
}
