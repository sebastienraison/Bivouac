package com.bivouac.app.journal

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
import com.bivouac.app.data.operations.ExclusiveOperations
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
 * RIC-183 : bouton « Annuler » du mode placement (JournalViewModel.cancelPhotoPlacement). Même
 * patron que JournalPhotoPlacementEditsTest (RIC-166), dont ce fichier reprend la mise en place :
 * le brouillon (_pendingPhotoPositions) est la même chose observée ici, mais côté annulation plutôt
 * que validation.
 *
 * La règle testée : Annuler ne restaure QUE l'entrée telle qu'elle était à l'entrée DU MODE
 * COURANT (requestPhotoPlacement), jamais plus loin. Un déplacement déjà validé par un Terminé
 * précédent, dans la même édition, est acquis et n'est pas défait par l'Annuler qui suit.
 */
@RunWith(RobolectricTestRunner::class)
class JournalPhotoPlacementCancelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var viewModel: JournalViewModel

    private val trackId = "ric183-annuler-placement"

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        ExclusiveOperations.resetForTests()
        repository = LoggedTrackRepository(application)
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

    /** Entrée sans brouillon de position (photo jamais déplacée) : Annuler retire l'entrée posée. */
    @Test
    fun annulerSansEntreePreexistanteRetireLeBrouillon() {
        val entry = openTrack()
        val photoId = importAndSave(entry)
        val photo = viewModel.currentPhotos.value.single()
        assertNull(photo.positionPointIndex)

        viewModel.requestPhotoPlacement(photo)
        viewModel.updatePhotoPlacementPosition(photoId, 1)
        settle()
        assertEquals(1, viewModel.currentPhotos.value.single().positionPointIndex)

        viewModel.cancelPhotoPlacement()
        settle()

        assertNull(viewModel.photoPlacementTarget.value)
        assertNull(
            "le brouillon retombe à l'absence d'entrée, comme avant le mode",
            viewModel.currentPhotos.value.single().positionPointIndex,
        )
        assertFalse(viewModel.photosDirty.value)
    }

    /** Entrée avec brouillon déjà posé (glissement précédent, pas encore enregistré) : Annuler le restaure. */
    @Test
    fun annulerAvecEntreePreexistanteLaRestaure() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        // Un premier glissement, jamais validé par Terminé ni par la disquette : c'est l'entrée
        // que le mode suivant doit retrouver après Annuler.
        viewModel.updatePhotoPlacementPosition(photoId, 0)
        settle()
        val photo = viewModel.currentPhotos.value.single()
        assertEquals(0, photo.positionPointIndex)

        viewModel.requestPhotoPlacement(photo)
        viewModel.updatePhotoPlacementPosition(photoId, 1)
        settle()
        assertEquals(1, viewModel.currentPhotos.value.single().positionPointIndex)

        viewModel.cancelPhotoPlacement()
        settle()

        assertNull(viewModel.photoPlacementTarget.value)
        assertEquals(
            "Annuler ne revient qu'à l'entrée DE CE mode, pas à la base",
            0,
            viewModel.currentPhotos.value.single().positionPointIndex,
        )
        assertTrue("l'entrée restaurée diffère de la base : toujours en attente", viewModel.photosDirty.value)
    }

    /** Terminé conserve le déplacement ; entrer à nouveau et glisser puis Annuler ne défait que le second geste. */
    @Test
    fun termineePuisNouvelleEntreeAnnulerNeDefaitQueLeSecondDeplacement() {
        val entry = openTrack()
        val photoId = importAndSave(entry)
        val photo = viewModel.currentPhotos.value.single()

        viewModel.requestPhotoPlacement(photo)
        viewModel.updatePhotoPlacementPosition(photoId, 0)
        viewModel.exitPhotoPlacement()
        settle()
        assertEquals(0, viewModel.currentPhotos.value.single().positionPointIndex)

        viewModel.requestPhotoPlacement(viewModel.currentPhotos.value.single())
        viewModel.updatePhotoPlacementPosition(photoId, 1)
        settle()
        assertEquals(1, viewModel.currentPhotos.value.single().positionPointIndex)

        viewModel.cancelPhotoPlacement()
        settle()

        assertEquals(
            "seul le second glissement est défait, le premier (validé par Terminé) reste acquis",
            0,
            viewModel.currentPhotos.value.single().positionPointIndex,
        )
    }

    /** Abandon de l'édition pendant le mode placement : tout est vide, y compris hors du mode. */
    @Test
    fun abandonPendantLeModePlacementVideTout() {
        val entry = openTrack()
        val photoId = importAndSave(entry)
        val positionAvant = storedPositionIndex()
        val photo = viewModel.currentPhotos.value.single()

        viewModel.requestPhotoPlacement(photo)
        viewModel.updatePhotoPlacementPosition(photoId, 1)
        settle()
        assertEquals(1, viewModel.currentPhotos.value.single().positionPointIndex)

        viewModel.discardPhotoEdits()
        settle()

        assertNull(viewModel.photoPlacementTarget.value)
        assertFalse(viewModel.photosDirty.value)
        assertEquals(positionAvant, viewModel.currentPhotos.value.single().positionPointIndex)
        assertEquals(positionAvant, storedPositionIndex())
    }

    // --- Mise en place -------------------------------------------------------------------------

    private fun openTrack(): LoggedTrackEntity {
        val entry = runBlocking { createTrack() }
        viewModel = JournalViewModel(application)
        viewModel.openTrack(entry)
        waitUntil("la trace ne s'est pas ouverte") { viewModel.uiState.value is JournalUiState.Detail }
        return entry
    }

    private fun importAndSave(entry: LoggedTrackEntity): Long {
        viewModel.addPhotos(listOf(photoUri(1)))
        waitUntil("le lot n'est pas arrivé en transit") {
            viewModel.photoOperationProgress.value == null && viewModel.photosDirty.value
        }
        saveAndWait { runBlocking { repository.listPhotos(entry.id) }.size == 1 }
        return runBlocking { repository.listPhotos(entry.id) }.single().id
    }

    private fun saveAndWait(done: () -> Boolean) {
        viewModel.saveDetails(tags = emptySet(), note = "")
        waitUntil("l'enregistrement ne s'est jamais terminé", condition = done)
        waitUntil("le verrou d'exclusion n'a jamais été rendu") {
            ExclusiveOperations.current.value == null
        }
        waitUntil("le brouillon photo n'a pas été vidé") { !viewModel.photosDirty.value }
    }

    private fun storedPositionIndex(): Int? =
        runBlocking { repository.listPhotos(trackId) }.single().positionPointIndex

    private suspend fun createTrack(): LoggedTrackEntity {
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test RIC-183",
        )
        val entry = LoggedTrackEntity(
            id = trackId,
            name = "Trace test RIC-183",
            startedAt = 0L,
            contentHash = "hash-trace",
            distanceMeters = 1.0,
            elevationGainMeters = 2.0,
            elevationLossMeters = 3.0,
            pointCount = 2,
            estimatedDurationMinutes = 4,
        )
        repository.commitImport(
            PreparedImport(
                entry,
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
        return entry
    }

    private fun photoUri(index: Int): Uri {
        val uri = Uri.parse("content://test/photo-$index")
        val bytes = ByteArray(2_048) { (index * 13 + it).toByte() }
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun settle() {
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitUntil(message: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        if (!condition()) org.junit.Assert.fail(message)
    }
}
