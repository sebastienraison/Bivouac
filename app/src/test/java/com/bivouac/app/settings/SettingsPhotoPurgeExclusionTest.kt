package com.bivouac.app.settings

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.db.PreparedDay
import com.bivouac.app.data.db.PreparedImport
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.operations.ExclusiveOperation
import com.bivouac.app.data.operations.ExclusiveOperations
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-158 : la purge des photos (Réglages) entre au registre d'exclusion mutuelle : elle supprime
 * en masse des fichiers de photos/, exactement ce qu'une sauvegarde zippe et qu'une restauration
 * remplace en bloc.
 *
 * Même infrastructure Robolectric que JournalGpxImportExclusionTest.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsPhotoPurgeExclusionTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var viewModel: SettingsViewModel

    private val trackId = "ric158-purge"

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        ExclusiveOperations.resetForTests()
        repository = LoggedTrackRepository(application)
        viewModel = SettingsViewModel(application)
    }

    @After
    fun tearDown() {
        ExclusiveOperations.resetForTests()
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
    }

    @Test
    fun confirmPhotoPurgeIsRefusedCleanlyWhileAnotherOperationIsInFlight() {
        runBlocking { seedOnePhoto() }
        assertTrue(ExclusiveOperations.tryStart(ExclusiveOperation.RESTORE))

        viewModel.confirmPhotoPurge()
        idle()

        assertEquals(
            "le refus doit être annoncé à l'écran",
            true,
            viewModel.photoPurgeError.value?.contains("une restauration") ?: false,
        )
        assertEquals(
            "rien n'a dû être purgé",
            1,
            runBlocking { repository.photoStorageSummary() }.count,
        )
        assertEquals(
            "le verrou de l'opération déjà en vol ne doit pas bouger",
            ExclusiveOperation.RESTORE,
            ExclusiveOperations.current.value,
        )
        assertNull(
            "aucun dialogue bloquant ne doit s'ouvrir sur un refus",
            viewModel.dataOperationProgress.value,
        )
    }

    @Test
    fun confirmPhotoPurgeSucceedsReportsProgressAndReleasesTheLock() {
        runBlocking { seedOnePhoto() }

        viewModel.confirmPhotoPurge()

        // Publié par le clic lui-même, avant tout aller-retour de coroutine : même exigence que
        // backup()/restore() (RIC-156).
        assertEquals(DataOperationPhase.PHOTO_PURGE, viewModel.dataOperationProgress.value?.phase)

        idle()

        assertNull(viewModel.photoPurgeError.value)
        assertEquals(0, runBlocking { repository.photoStorageSummary() }.count)
        assertNull(
            "le dialogue bloquant doit être retiré une fois la purge terminée",
            viewModel.dataOperationProgress.value,
        )
        assertNull(
            "le verrou doit être relâché une fois la purge terminée",
            ExclusiveOperations.current.value,
        )
    }

    // --- Mise en place -------------------------------------------------------------------------

    private suspend fun seedOnePhoto() {
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test purge",
        )
        repository.commitImport(
            PreparedImport(
                LoggedTrackEntity(
                    id = trackId,
                    name = "Trace test purge",
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
        val uri = Uri.parse("content://test/photo-purge")
        val bytes = ByteArray(1_000)
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        val staged = repository.stagePhotosFromPicker(
            trackId = trackId,
            resolver = application.contentResolver,
            uris = listOf(uri),
        )
        repository.commitPendingPhotos(trackId, staged.staged)
    }

    private fun idle(timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ExclusiveOperations.current.value != ExclusiveOperation.PHOTO_PURGE) {
                shadowOf(Looper.getMainLooper()).idle()
                return
            }
            Thread.sleep(5)
        }
        fail("la purge ne s'est jamais terminée")
    }
}
