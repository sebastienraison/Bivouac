package com.bivouac.app.journal

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.db.PreparedDay
import com.bivouac.app.data.db.PreparedImport
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.operations.ExclusiveOperations
import com.bivouac.app.data.photo.PhotoContentHash
import com.bivouac.app.data.photo.PhotoStorageMode
import com.bivouac.app.ui.journal.deservesQualityUpgrade
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-157 : la montée en qualité de la visionneuse, côté décision et côté résolution.
 *
 * Ce que le rendu lui-même fait (la copie locale affichée d'abord, l'original en fondu par-dessus,
 * le zoom conservé) relève de l'œil et de la recette : ici on vérifie les deux choses qui se
 * décident en code, la photo qui mérite qu'on cherche, et ce que la recherche rend.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoViewerQualityUpgradeTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var viewModel: JournalViewModel

    private val trackId = "ric157-visionneuse"
    private val originalBytes = ByteArray(4_096) { (it * 23).toByte() }
    private val otherBytes = ByteArray(4_096) { (it * 29 + 7).toByte() }

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        ExclusiveOperations.resetForTests()
        repository = LoggedTrackRepository(application)
        viewModel = JournalViewModel(application)
        shadowOf(application).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
    }

    @After
    fun tearDown() {
        ExclusiveOperations.resetForTests()
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
    }

    /** Seule une copie réduite a quelque chose à gagner : une copie intégrale EST déjà l'original. */
    @Test
    fun onlyAReducedCopyIsWorthResolving() = runBlocking {
        val reduced = insertPhoto(PhotoStorageMode.REDUCED, "content://test/original")
        val full = insertPhoto(PhotoStorageMode.FULL, "content://test/original", fileName = "archive.jpg")

        assertTrue(deservesQualityUpgrade(reduced))
        assertFalse(deservesQualityUpgrade(full))
    }

    /** L'original retrouvé et confirmé par son empreinte : c'est lui que la visionneuse affichera. */
    @Test
    fun aConfirmedOriginalIsHandedBackToTheViewer() = runBlocking {
        val photo = insertPhoto(PhotoStorageMode.REDUCED, "content://test/original")
        registerBytes("content://test/original", originalBytes)

        assertEquals(Uri.parse("content://test/original"), viewModel.resolveOriginalUri(photo))
    }

    /**
     * L'original a changé : rien n'est rendu, et surtout aucune erreur. La visionneuse reste sur la
     * copie locale sans rien dire, c'est la spec : l'utilisateur regarde une photo, il n'a pas
     * demandé de nouvelles de son fichier d'origine.
     */
    @Test
    fun anOriginalThatNoLongerMatchesIsASilentNonEvent() = runBlocking {
        val photo = insertPhoto(PhotoStorageMode.REDUCED, "content://test/original")
        registerBytes("content://test/original", otherBytes)

        assertNull(viewModel.resolveOriginalUri(photo))
    }

    /** Sans permission galerie, la résolution n'aboutit pas et ne demande rien : même silence. */
    @Test
    fun withoutTheGalleryPermissionItStaysSilentToo() = runBlocking {
        shadowOf(application).denyPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        val photo = insertPhoto(PhotoStorageMode.REDUCED, "content://test/original")
        registerBytes("content://test/original", originalBytes)

        assertNull(viewModel.resolveOriginalUri(photo))
    }

    // --- Mise en place -------------------------------------------------------------------------

    private suspend fun insertPhoto(
        storageMode: PhotoStorageMode,
        lastResolvedUri: String?,
        fileName: String = "copie.jpg",
    ): LoggedTrackPhotoEntity {
        createTrack()
        val relativePath = "${LoggedTrackPhotoStore.DIR_NAME}/$trackId-$fileName"
        val file = LoggedTrackPhotoStore.resolve(application, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(originalBytes)
        val id = BivouacDatabase.getInstance(application).loggedTrackDao().insertPhoto(
            LoggedTrackPhotoEntity(
                trackId = trackId,
                filePath = relativePath,
                addedAtMillis = 1780300900000L,
                contentHash = PhotoContentHash.of(ByteArrayInputStream(originalBytes)),
                sourceDisplayName = "IMG_0001.jpg",
                sourceDateTakenMillis = 1780300850000L,
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

    private suspend fun createTrack() {
        if (repository.list().any { it.id == trackId }) return
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test visionneuse",
        )
        repository.commitImport(
            PreparedImport(
                LoggedTrackEntity(
                    id = trackId,
                    name = "Trace test visionneuse",
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
