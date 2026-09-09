package com.bivouac.app.settings

import android.Manifest
import android.app.Application
import android.os.Looper
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
import com.bivouac.app.data.operations.ExclusiveOperation
import com.bivouac.app.data.operations.ExclusiveOperations
import com.bivouac.app.data.photo.PhotoContentHash
import com.bivouac.app.data.photo.PhotoStorageMode
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-157 : la recompression entre au registre d'exclusion mutuelle, au même titre que la purge
 * (RIC-158) : elle réécrit des fichiers de photos/, exactement ce qu'une sauvegarde zippe et ce
 * qu'une restauration remplace en bloc.
 *
 * Même infrastructure et mêmes exigences que SettingsPhotoPurgeExclusionTest : verrou posé par le
 * clic lui-même, dialogue bloquant publié avant tout aller-retour de coroutine, verrou relâché et
 * rapport de fin publié à la sortie.
 */
@RunWith(RobolectricTestRunner::class)
class StorageUsageRecompressionExclusionTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var viewModel: StorageUsageViewModel

    private val trackId = "ric157-exclusion"
    private val photoBytes = ByteArray(2_048) { (it * 13).toByte() }

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        ExclusiveOperations.resetForTests()
        shadowOf(application).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        repository = LoggedTrackRepository(application)
        runBlocking { seedOneArchivePhoto() }
        viewModel = StorageUsageViewModel(application)
    }

    @After
    fun tearDown() {
        ExclusiveOperations.resetForTests()
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
    }

    @Test
    fun theRecompressionIsRefusedCleanlyWhileAnotherOperationIsInFlight() {
        assertTrue(ExclusiveOperations.tryStart(ExclusiveOperation.BACKUP))

        viewModel.recompressPhotos()
        idle()

        assertTrue(
            "le refus doit être annoncé à l'écran",
            viewModel.recompressionError.value?.contains("une sauvegarde") ?: false,
        )
        assertNull("aucun dialogue bloquant ne doit s'ouvrir sur un refus", viewModel.recompressionProgress.value)
        assertEquals(
            "le verrou de l'opération déjà en vol ne doit pas bouger",
            ExclusiveOperation.BACKUP,
            ExclusiveOperations.current.value,
        )
        assertEquals(
            "la photo ne doit pas avoir été touchée",
            PhotoStorageMode.FULL,
            runBlocking { repository.listPhotos(trackId) }.single().storageMode,
        )
    }

    @Test
    fun theRecompressionPublishesItsDialogAtTheClickThenReleasesTheLockAndReports() {
        viewModel.recompressPhotos()

        // Publié par le clic lui-même, avant tout aller-retour de coroutine : même exigence que
        // backup()/restore() (RIC-156), sans quoi le verrou ne fermerait pas la fenêtre qu'il est
        // censé fermer.
        assertNotNull(viewModel.recompressionProgress.value)
        assertEquals(ExclusiveOperation.PHOTO_RECOMPRESS, ExclusiveOperations.current.value)

        idle()

        assertNull(viewModel.recompressionError.value)
        assertNull("le dialogue bloquant doit être retiré", viewModel.recompressionProgress.value)
        assertNull("le verrou doit être relâché", ExclusiveOperations.current.value)
        assertNotNull("jamais de fin silencieuse", viewModel.recompressionReport.value)
    }

    // --- Mise en place -------------------------------------------------------------------------

    private suspend fun seedOneArchivePhoto() {
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test exclusion",
        )
        repository.commitImport(
            PreparedImport(
                LoggedTrackEntity(
                    id = trackId,
                    name = "Trace test exclusion",
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
        val relativePath = "${LoggedTrackPhotoStore.DIR_NAME}/$trackId-copie.jpg"
        val file = LoggedTrackPhotoStore.resolve(application, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(photoBytes)
        BivouacDatabase.getInstance(application).loggedTrackDao().insertPhoto(
            LoggedTrackPhotoEntity(
                trackId = trackId,
                filePath = relativePath,
                addedAtMillis = 1780300900000L,
                contentHash = PhotoContentHash.of(ByteArrayInputStream(photoBytes)),
                sourceDisplayName = "IMG_0001.jpg",
                sourceDateTakenMillis = 1780300850000L,
                storageMode = PhotoStorageMode.FULL,
                lastResolvedUri = "content://test/introuvable",
            ),
        )
    }

    private fun idle(timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ExclusiveOperations.current.value != ExclusiveOperation.PHOTO_RECOMPRESS) {
                shadowOf(Looper.getMainLooper()).idle()
                return
            }
            Thread.sleep(5)
        }
        fail("la recompression ne s'est jamais terminée")
    }
}
