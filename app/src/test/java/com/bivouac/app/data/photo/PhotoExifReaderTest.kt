package com.bivouac.app.data.photo

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-43 : couvre le vrai chemin EXIF (lecture par ExifInterface), pas seulement la corrélation en
 * aval — c'est précisément ce que PhotoPositionCorrelatorTest ne voit pas, puisqu'il fabrique des
 * Instant directement.
 *
 * Les fixtures sont des JPEG minimaux (SOI + EOI, quatre octets) auxquels ExifInterface écrit
 * lui-même les tags voulus : aucune donnée d'image, aucune fausse « photo de rando » — juste la
 * structure de conteneur qu'il faut pour que la lib accepte d'écrire puis de relire un bloc EXIF.
 * Robolectric parce qu'ExifInterface s'appuie sur le runtime Android (android.util.Log,
 * android.system.Os), comme RepositoryBackupCycleTest le fait déjà pour Room.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoExifReaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var originalTimeZone: TimeZone

    // Fuseau fixé explicitement : tout l'objet du correctif est de dépendre du fuseau du téléphone,
    // donc un test qui hériterait de celui de la machine de build ne prouverait rien de stable.
    // Europe/Paris en juin = UTC+2, un décalage franc et bien au-delà de la tolérance de 10 min.
    @Before
    fun fixTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun dateTimeOriginalWithoutOffsetTag_isReadInThePhoneTimeZone() {
        val file = jpegWithExif { exif ->
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:06:12 10:30:00")
        }

        val data = PhotoExifReader.read(file.inputStream())

        // 10h30 murales à Paris en juin = 08h30 UTC, l'heure que porteraient les points GPX d'une
        // trace enregistrée au même moment.
        val expected = LocalDateTime.of(2026, 6, 12, 10, 30, 0)
            .atZone(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        assertEquals(expected, data.takenAtMillis)
        assertNull(data.latitude)
        assertNull(data.longitude)
    }

    // Le défaut que corrige RIC-43 : sans le correctif, l'heure murale était rendue telle quelle
    // comme de l'UTC, soit 2 h d'écart avec la trace — donc systématiquement hors de la tolérance
    // de PhotoPositionCorrelator, et aucune position retenue, en silence.
    @Test
    fun dateTimeOriginalWithoutOffsetTag_isNotTheNaiveUtcReading() {
        val file = jpegWithExif { exif ->
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:06:12 10:30:00")
        }

        val data = PhotoExifReader.read(file.inputStream())

        val naiveUtc = LocalDateTime.of(2026, 6, 12, 10, 30, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertNotNull(data.takenAtMillis)
        assertEquals(-2 * 3_600_000L, data.takenAtMillis!! - naiveUtc)
    }

    // Photo qui porte son propre décalage : c'est elle qui fait foi, le fuseau du téléphone n'a
    // plus rien à dire. Vérifié avec un décalage volontairement différent de celui du téléphone,
    // sans quoi les deux chemins donneraient le même résultat et le test ne distinguerait rien.
    @Test
    fun dateTimeOriginalWithOffsetTag_usesThatOffsetRatherThanThePhoneTimeZone() {
        val file = jpegWithExif { exif ->
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:06:12 10:30:00")
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+05:30")
        }

        val data = PhotoExifReader.read(file.inputStream())

        val expected = LocalDateTime.of(2026, 6, 12, 10, 30, 0)
            .toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()
        assertEquals(expected, data.takenAtMillis)
    }

    // Un offset illisible doit se comporter comme un offset absent (la lib l'ignore aussi), et pas
    // laisser passer l'heure murale pour de l'UTC.
    @Test
    fun malformedOffsetTag_fallsBackToThePhoneTimeZone() {
        val file = jpegWithExif { exif ->
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:06:12 10:30:00")
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "n'importe quoi")
        }

        val data = PhotoExifReader.read(file.inputStream())

        val expected = LocalDateTime.of(2026, 6, 12, 10, 30, 0)
            .atZone(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        assertEquals(expected, data.takenAtMillis)
    }

    // GPS actif : l'horodatage GPS est en UTC par construction et prime sur DateTimeOriginal,
    // qu'aucune correction de fuseau ne doit venir décaler.
    @Test
    fun gpsDateTime_takesPrecedenceAndIsNeverShiftedByTheTimeZone() {
        val file = jpegWithExif { exif ->
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:06:12 10:30:00")
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2026:06:12")
            exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "08:30:00")
        }

        val data = PhotoExifReader.read(file.inputStream())

        val expected = LocalDateTime.of(2026, 6, 12, 8, 30, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expected, data.takenAtMillis)
    }

    @Test
    fun gpsCoordinates_areReadWhenPresent() {
        val file = jpegWithExif { exif ->
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:06:12 10:30:00")
            exif.setLatLong(45.1885, 5.7245)
        }

        val data = PhotoExifReader.read(file.inputStream())

        assertNotNull(data.latitude)
        assertNotNull(data.longitude)
        assertTrue(kotlin.math.abs(data.latitude!! - 45.1885) < 1e-5)
        assertTrue(kotlin.math.abs(data.longitude!! - 5.7245) < 1e-5)
    }

    // Le cas le plus courant après une capture d'écran ou une image reçue d'un tiers : conteneur
    // valide, aucun tag. Ni crash, ni valeur inventée.
    @Test
    fun jpegWithoutAnyExifTag_yieldsEmptyData() {
        val file = temporaryFolder.newFile("sans-exif.jpg").apply { writeBytes(MINIMAL_JPEG) }

        val data = PhotoExifReader.read(file.inputStream())

        assertNull(data.takenAtMillis)
        assertNull(data.latitude)
        assertNull(data.longitude)
    }

    // Contenu qui n'est pas une image du tout : la lecture doit dégrader en « aucune métadonnée »,
    // jamais remonter d'exception jusqu'au ViewModel (voir la gestion d'erreur de JournalViewModel).
    @Test
    fun contentThatIsNotAnImage_yieldsEmptyDataWithoutThrowing() {
        val data = PhotoExifReader.read("ceci n'est pas une image".byteInputStream())

        assertNull(data.takenAtMillis)
        assertNull(data.latitude)
        assertNull(data.longitude)
    }

    // SOI + EOI : le plus petit conteneur JPEG qu'ExifInterface accepte de parser et de réécrire.
    // Aucune donnée d'image — c'est le bloc EXIF qui est sous test, pas le décodage.
    private val MINIMAL_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    private var fixtureCount = 0

    private fun jpegWithExif(configure: (ExifInterface) -> Unit): File {
        val file = temporaryFolder.newFile("fixture-${fixtureCount++}.jpg")
        file.writeBytes(MINIMAL_JPEG)
        val exif = ExifInterface(file)
        configure(exif)
        exif.saveAttributes()
        return file
    }
}
