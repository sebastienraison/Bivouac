package com.bivouac.app.data.db

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.photo.PhotoContentHash
import com.bivouac.app.data.photo.PhotoStorageMode
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-151 : retrouver les photos dont le fichier local a disparu, typiquement après la
 * restauration d'une sauvegarde antérieure à leur ajout.
 *
 * Toute la re-corrélation part des colonnes de la base (empreinte, nom d'origine, date de prise de
 * vue) : il n'y a précisément plus de fichier local à interroger, et c'est un point acquis du
 * projet que l'EXIF local ne sert jamais à ça.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoRecoveryPassTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository

    private val trackId = "ric151-recuperation"
    private val originalBytes = ByteArray(2_048) { (it * 17).toByte() }
    private val recompressedBytes = ByteArray(1_024) { (it * 19 + 5).toByte() }

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
        repository = LoggedTrackRepository(application)
        shadowOf(application).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
    }

    @After
    fun tearDown() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
    }

    /**
     * Le cas nominal : l'original est encore dans la galerie et son empreinte correspond. La copie
     * locale est refabriquée, la ligne pointe à nouveau vers un fichier existant, et le mode de
     * stockage est celui d'AUJOURD'HUI, pas celui que la ligne portait.
     */
    @Test
    fun aConfirmedOriginalIsCopiedBackUnderTheCurrentStorageMode() = runBlocking {
        val photo = insertMissingPhoto(lastResolvedUri = "content://test/original")
        registerBytes("content://test/original", originalBytes)

        val report = repository.recoverMissingPhotos(PhotoStorageMode.REDUCED)

        assertEquals(1, report.recovered)
        assertEquals(0, report.modifiedNotAdopted)
        assertEquals(0, report.notFound)
        val after = repository.listPhotos(trackId).single()
        assertEquals(PhotoStorageMode.REDUCED, after.storageMode)
        assertTrue(
            "la ligne doit à nouveau désigner un fichier existant",
            LoggedTrackPhotoStore.resolve(application, after.filePath).isFile,
        )
        assertEquals("plus aucune photo manquante", 0, repository.countMissingPhotoFiles())
        assertEquals(
            "l'empreinte porte toujours les octets d'origine",
            photo.contentHash,
            after.contentHash,
        )
    }

    /**
     * Le cas Google Photos : la galerie contient bien une photo du même nom et de la même date,
     * mais son contenu a changé. Rien n'est adopté, et c'est compté à part : « rien trouvé » serait
     * faux, et reprendre cette image-là remplacerait une photo du carnet par une autre.
     */
    @Test
    fun anApproximateMatchIsCountedApartAndNeverAdopted() = runBlocking {
        insertMissingPhoto(lastResolvedUri = null)
        insertGalleryPhoto("IMG_0001.jpg", 1780300850000L, recompressedBytes)

        val report = repository.recoverMissingPhotos(PhotoStorageMode.REDUCED)

        assertEquals(0, report.recovered)
        assertEquals(1, report.modifiedNotAdopted)
        assertEquals(0, report.notFound)
        val after = repository.listPhotos(trackId).single()
        assertFalse(
            "aucun fichier ne doit avoir été écrit pour une correspondance approximative",
            LoggedTrackPhotoStore.resolve(application, after.filePath).exists(),
        )
        assertEquals("la fiche doit survivre intacte", 1, repository.countMissingPhotoFiles())
    }

    /** Ni original, ni ressemblance : introuvable, et la fiche reste, au cas où l'original revienne. */
    @Test
    fun nothingInTheGalleryMeansNotFoundAndTheRowSurvives() = runBlocking {
        insertMissingPhoto(lastResolvedUri = null)

        val report = repository.recoverMissingPhotos(PhotoStorageMode.REDUCED)

        assertEquals(0, report.recovered)
        assertEquals(0, report.modifiedNotAdopted)
        assertEquals(1, report.notFound)
        assertEquals(1, repository.listPhotos(trackId).size)
    }

    /**
     * Une photo dont le fichier est bien là n'est pas candidate : la passe ne la compte pas, ne la
     * réécrit pas, et ne va surtout pas la rechercher dans la galerie.
     */
    @Test
    fun aPhotoWhoseFileIsStillThereIsNeverEvenConsidered() = runBlocking {
        val photo = insertMissingPhoto(lastResolvedUri = "content://test/original")
        val file = LoggedTrackPhotoStore.resolve(application, photo.filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(originalBytes)
        val totals = mutableListOf<Int>()

        val report = repository.recoverMissingPhotos(PhotoStorageMode.REDUCED) { _, total -> totals += total }

        assertEquals(listOf(0), totals.distinct())
        assertEquals(0, report.recovered + report.modifiedNotAdopted + report.notFound)
        assertEquals(photo.filePath, repository.listPhotos(trackId).single().filePath)
    }

    /** Le compteur du dialogue bloquant : un pas par photo manquante, quel qu'en soit le sort. */
    @Test
    fun progressAdvancesOncePerMissingPhoto() = runBlocking {
        insertMissingPhoto(lastResolvedUri = "content://test/a", fileName = "a.jpg")
        registerBytes("content://test/a", originalBytes)
        insertMissingPhoto(lastResolvedUri = null, fileName = "b.jpg", displayName = null, dateTakenMillis = null)
        val steps = mutableListOf<Pair<Int, Int>>()

        repository.recoverMissingPhotos(PhotoStorageMode.REDUCED) { done, total -> steps += done to total }

        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), steps)
    }

    // --- Mise en place -------------------------------------------------------------------------

    /** Une ligne bien vivante dont le fichier n'existe pas : l'état laissé par une restauration. */
    private suspend fun insertMissingPhoto(
        lastResolvedUri: String?,
        displayName: String? = "IMG_0001.jpg",
        dateTakenMillis: Long? = 1780300850000L,
        fileName: String = "disparue.jpg",
    ): LoggedTrackPhotoEntity {
        createTrack()
        val dao = BivouacDatabase.getInstance(application).loggedTrackDao()
        val id = dao.insertPhoto(
            LoggedTrackPhotoEntity(
                trackId = trackId,
                filePath = "${LoggedTrackPhotoStore.DIR_NAME}/$trackId-$fileName",
                addedAtMillis = 1780300900000L,
                contentHash = PhotoContentHash.of(ByteArrayInputStream(originalBytes)),
                sourceDisplayName = displayName,
                sourceDateTakenMillis = dateTakenMillis,
                storageMode = PhotoStorageMode.FULL,
                lastResolvedUri = lastResolvedUri,
            ),
        )
        return repository.listPhotos(trackId).single { it.id == id }
    }

    private fun registerBytes(uri: String, bytes: ByteArray) {
        shadowOf(application.contentResolver)
            .registerInputStreamSupplier(Uri.parse(uri)) { ByteArrayInputStream(bytes) }
    }

    private fun insertGalleryPhoto(displayName: String, dateTakenMillis: Long, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMillis)
        }
        val uri = application.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore n'a pas accepté l'insertion")
        registerBytes(uri.toString(), bytes)
        return uri
    }

    private suspend fun createTrack() {
        if (repository.list().any { it.id == trackId }) return
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test récupération",
        )
        repository.commitImport(
            PreparedImport(
                LoggedTrackEntity(
                    id = trackId,
                    name = "Trace test récupération",
                    startedAt = 0L,
                    contentHash = "hash-trace",
                    distanceMeters = 1.0,
                    elevationGainMeters = 2.0,
                    elevationLossMeters = 3.0,
                    pointCount = 2,
                    estimatedDurationMinutes = 4,
                ),
                listOf(
                    PreparedDay(
                        rawGpx = gpx,
                        contentHash = "hash-jour",
                        startedAtMillis = 0L,
                        elapsedSeconds = null,
                        segmentAggregate = DaySegmentAggregate.EMPTY,
                    ),
                ),
            ),
        )
    }
}
