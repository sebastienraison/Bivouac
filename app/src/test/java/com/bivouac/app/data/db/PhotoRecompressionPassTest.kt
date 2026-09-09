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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-157 : la passe de recompression du stock, sur une vraie base, de vrais fichiers et le
 * MediaStore de Robolectric.
 *
 * Ce qui se vérifie ici est la MÉCANIQUE : qui est candidate, ce qui arrive à une photo dont
 * l'original n'est plus reconnaissable (rien), et le fait qu'aucune ligne ne se retrouve jamais
 * sans fichier. Le réencodage réel (définition, poids repris, échange du fichier) ne peut pas
 * l'être ici : Robolectric ne décode aucune image et rend des dimensions de complaisance, sous la
 * cible, ce qui envoie toute photo sur la branche « déjà au format le plus léger ». C'est
 * PhotoRecompressionInstrumentedTest, en GMD, qui exerce la branche du réencodage.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoRecompressionPassTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository

    private val trackId = "ric157-recompression"
    private val originalBytes = ByteArray(4_096) { (it * 7).toByte() }
    private val otherBytes = ByteArray(4_096) { (it * 11 + 3).toByte() }

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
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
    }

    /**
     * L'original est retrouvé et confirmé, mais il est déjà sous la cible : rien à gagner. La ligne
     * passe REDUCED sans que le fichier bouge, pour ne plus jamais être réexaminée, et l'issue est
     * comptée à part de « conservée », qui, elle, veut dire « on n'a pas pu ».
     */
    @Test
    fun anOriginalAlreadyUnderTheTargetMarksTheRowReducedWithoutTouchingTheFile() = runBlocking {
        val photo = insertPhoto(lastResolvedUri = "content://test/original")
        registerBytes("content://test/original", originalBytes)
        val fileBefore = LoggedTrackPhotoStore.resolve(application, photo.filePath)
        val bytesBefore = fileBefore.readBytes()

        val report = repository.recompressFullPhotos()

        assertEquals(1, report.alreadyReduced)
        assertEquals(0, report.recompressed)
        assertEquals(0, report.kept)
        val after = repository.listPhotos(trackId).single()
        assertEquals(PhotoStorageMode.REDUCED, after.storageMode)
        assertEquals("le fichier ne doit pas bouger", photo.filePath, after.filePath)
        assertTrue(fileBefore.exists())
        assertTrue(bytesBefore.contentEquals(fileBefore.readBytes()))
    }

    /**
     * L'original n'existe plus, ou plus sous la même forme : la copie intégrale est INTACTE, sa
     * ligne aussi, et la photo est comptée « conservée ». C'est la garantie qui rend l'opération
     * acceptable : elle ne dégrade jamais une photo dont l'archive locale est le dernier exemplaire.
     */
    @Test
    fun aPhotoWhoseOriginalCannotBeConfirmedIsLeftStrictlyUntouched() = runBlocking {
        val photo = insertPhoto(lastResolvedUri = "content://test/original")
        // L'URI mémorisé désigne désormais une autre photo, et le seul candidat de la galerie
        // porte le bon nom mais pas le bon contenu : exactement le cas « Google Photos a
        // recompressé mon original ».
        registerBytes("content://test/original", otherBytes)
        insertGalleryPhoto("IMG_0001.jpg", 1780300850000L, otherBytes)
        val file = LoggedTrackPhotoStore.resolve(application, photo.filePath)

        val report = repository.recompressFullPhotos()

        assertEquals(1, report.kept)
        assertEquals(0, report.recompressed)
        assertEquals(0L, report.freedBytes)
        val after = repository.listPhotos(trackId).single()
        assertEquals(PhotoStorageMode.FULL, after.storageMode)
        assertEquals(photo.filePath, after.filePath)
        assertTrue("la copie intégrale doit rester exactement là où elle est", file.exists())
        assertTrue(originalBytes.contentEquals(file.readBytes()))
    }

    /**
     * Une photo déjà réduite n'est pas candidate, et une photo dont rien ne permet de retrouver
     * l'original non plus : la passe ne les compte même pas dans son total, sans quoi le compteur
     * du dialogue annoncerait du travail qui n'existe pas.
     */
    @Test
    fun onlyFullCopiesWithSomethingToSearchWithAreEvenConsidered() = runBlocking {
        insertPhoto(lastResolvedUri = "content://test/original", storageMode = PhotoStorageMode.REDUCED)
        insertPhoto(lastResolvedUri = null, displayName = null, dateTakenMillis = null, fileName = "sans-source.jpg")
        val totals = mutableListOf<Int>()

        val report = repository.recompressFullPhotos { _, total -> totals += total }

        assertEquals("aucune candidate", listOf(0), totals.distinct())
        assertEquals(0, report.recompressed)
        assertEquals(0, report.kept)
        assertEquals(0, report.alreadyReduced)
    }

    /** Le compteur du dialogue bloquant : un pas par photo examinée, quel qu'en soit le sort. */
    @Test
    fun progressAdvancesOncePerCandidateWhateverTheOutcome() = runBlocking {
        insertPhoto(lastResolvedUri = "content://test/a", fileName = "a.jpg").also {
            registerBytes("content://test/a", originalBytes)
        }
        insertPhoto(lastResolvedUri = "content://test/b", fileName = "b.jpg").also {
            registerBytes("content://test/b", otherBytes)
        }
        val steps = mutableListOf<Pair<Int, Int>>()

        repository.recompressFullPhotos { done, total -> steps += done to total }

        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), steps)
    }

    /**
     * Sans permission galerie, la résolution répond « indisponible » sans rien demander : la passe
     * conserve alors tout. Le chemin est fermé plus haut (l'écran déclenche le flux de permission
     * avant de lancer), ce test vérifie que la couche basse ne dégrade rien si on l'atteint quand
     * même.
     */
    @Test
    fun withoutTheGalleryPermissionNothingIsTouched() = runBlocking {
        shadowOf(application).denyPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        val photo = insertPhoto(lastResolvedUri = "content://test/original")
        registerBytes("content://test/original", originalBytes)

        val report = repository.recompressFullPhotos()

        assertEquals(1, report.kept)
        assertEquals(PhotoStorageMode.FULL, repository.listPhotos(trackId).single().storageMode)
        assertTrue(LoggedTrackPhotoStore.resolve(application, photo.filePath).exists())
    }

    // --- Mise en place -------------------------------------------------------------------------

    private suspend fun insertPhoto(
        lastResolvedUri: String?,
        displayName: String? = "IMG_0001.jpg",
        dateTakenMillis: Long? = 1780300850000L,
        storageMode: PhotoStorageMode = PhotoStorageMode.FULL,
        fileName: String = "copie.jpg",
    ): LoggedTrackPhotoEntity {
        createTrack()
        val relativePath = "${LoggedTrackPhotoStore.DIR_NAME}/$trackId-$fileName"
        val file = LoggedTrackPhotoStore.resolve(application, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(originalBytes)
        val dao = BivouacDatabase.getInstance(application).loggedTrackDao()
        val id = dao.insertPhoto(
            LoggedTrackPhotoEntity(
                trackId = trackId,
                filePath = relativePath,
                addedAtMillis = 1780300900000L,
                contentHash = PhotoContentHash.of(ByteArrayInputStream(originalBytes)),
                sourceDisplayName = displayName,
                sourceDateTakenMillis = dateTakenMillis,
                storageMode = storageMode,
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
            "Trace test recompression",
        )
        repository.commitImport(
            PreparedImport(
                LoggedTrackEntity(
                    id = trackId,
                    name = "Trace test recompression",
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
