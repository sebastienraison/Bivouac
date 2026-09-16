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
 * RIC-170 (légende) / RIC-171 (retirer de la carte) / RIC-166 (repositionnement) : les trois
 * nouveautés du lot 2, transactionnelles comme les ajustements du lot 1 (voir
 * JournalPhotoAdjustmentsTest, dont ce fichier reprend le patron).
 *
 * ⚠️ Même leçon RIC-149 pour chacune des trois : si [JournalViewModel.photosDirty] ne les inclut
 * pas, la croix de l'écran sort par « rien à enregistrer » et le geste est perdu sans un mot.
 */
@RunWith(RobolectricTestRunner::class)
class JournalPhotoPlacementEditsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var viewModel: JournalViewModel

    private val trackId = "ric170-171-166-lot2"

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

    // --- RIC-170 : légende ---------------------------------------------------------------------

    @Test
    fun poserUneLegendeMarqueLEditionCommeModifiee() {
        val entry = openTrack()
        val photoId = importAndSave(entry)
        assertFalse(viewModel.photosDirty.value)

        viewModel.applyPhotoCaption(photoId, "Vue sur le lac")
        settle()

        assertTrue(
            "sans ça, la croix sortirait par « rien à enregistrer » et la légende serait perdue",
            viewModel.photosDirty.value,
        )
        assertEquals("Vue sur le lac", viewModel.currentPhotos.value.single().caption)
    }

    /** Revenir exactement à la légende déjà enregistrée n'est pas une modification. */
    @Test
    fun revenirALaLegendeEnregistreeNeLaisseRienEnAttente() {
        val entry = openTrack()
        val photoId = importAndSave(entry)
        viewModel.applyPhotoCaption(photoId, "Vue sur le lac")
        saveAndWait { storedCaption() == "Vue sur le lac" }

        viewModel.applyPhotoCaption(photoId, "Vue sur le lac")
        settle()

        assertFalse(viewModel.photosDirty.value)
    }

    /** Une chaîne blanche vaut absence de légende, comme si le champ n'avait jamais été rempli. */
    @Test
    fun uneChaineBlancheEffaceLaLegende() {
        val entry = openTrack()
        val photoId = importAndSave(entry)
        viewModel.applyPhotoCaption(photoId, "Vue sur le lac")
        saveAndWait { storedCaption() == "Vue sur le lac" }

        viewModel.applyPhotoCaption(photoId, "   ")
        saveAndWait { storedCaption() == null }

        assertNull(storedCaption())
    }

    @Test
    fun laDisquetteEcritLaLegendeEnBase() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        viewModel.applyPhotoCaption(photoId, "Bivouac au sommet")
        saveAndWait { storedCaption() == "Bivouac au sommet" }

        assertEquals("Bivouac au sommet", storedCaption())
        assertFalse(viewModel.photosDirty.value)
    }

    @Test
    fun abandonnerLEditionOublieLaLegende() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        viewModel.applyPhotoCaption(photoId, "Bivouac au sommet")
        viewModel.discardPhotoEdits()
        settle()

        assertFalse(viewModel.photosDirty.value)
        assertNull(viewModel.currentPhotos.value.single().caption)
        assertNull(storedCaption())
    }

    // --- RIC-171 : retirer / replacer sur la carte ----------------------------------------------

    @Test
    fun retirerDeLaCarteMarqueLEditionCommeModifiee() {
        val entry = openTrack()
        importAndSave(entry)
        assertFalse(viewModel.photosDirty.value)

        viewModel.togglePhotoShownOnMap(viewModel.currentPhotos.value.single())
        settle()

        assertTrue(viewModel.photosDirty.value)
        assertFalse(viewModel.currentPhotos.value.single().shownOnMap)
    }

    /** Retirer puis replacer dans la même édition ne laisse rien en attente. */
    @Test
    fun retirerPuisReplacerNeLaisseRienEnAttente() {
        val entry = openTrack()
        importAndSave(entry)

        val photo = viewModel.currentPhotos.value.single()
        viewModel.togglePhotoShownOnMap(photo)
        viewModel.togglePhotoShownOnMap(viewModel.currentPhotos.value.single())
        settle()

        assertFalse(viewModel.photosDirty.value)
        assertTrue(viewModel.currentPhotos.value.single().shownOnMap)
    }

    @Test
    fun laDisquetteEcritLeRetraitDeLaCarteEnBaseSansToucherLaPosition() {
        val entry = openTrack()
        importAndSave(entry)
        val positionAvant = runBlocking { repository.listPhotos(entry.id) }.single().positionPointIndex

        viewModel.togglePhotoShownOnMap(viewModel.currentPhotos.value.single())
        saveAndWait { storedShownOnMap() == false }

        assertFalse(storedShownOnMap())
        assertEquals(
            "la position reste en base, seule la visibilité change",
            positionAvant,
            runBlocking { repository.listPhotos(entry.id) }.single().positionPointIndex,
        )
    }

    @Test
    fun abandonnerLEditionOublieLeRetraitDeLaCarte() {
        val entry = openTrack()
        importAndSave(entry)

        viewModel.togglePhotoShownOnMap(viewModel.currentPhotos.value.single())
        viewModel.discardPhotoEdits()
        settle()

        assertFalse(viewModel.photosDirty.value)
        assertTrue(viewModel.currentPhotos.value.single().shownOnMap)
        assertTrue(storedShownOnMap())
    }

    // --- RIC-166 : repositionnement --------------------------------------------------------------

    @Test
    fun glisserLaPhotoMarqueLEditionCommeModifiee() {
        val entry = openTrack()
        val photoId = importAndSave(entry)
        assertFalse(viewModel.photosDirty.value)

        viewModel.updatePhotoPlacementPosition(photoId, 1)
        settle()

        assertTrue(viewModel.photosDirty.value)
        assertEquals(1, viewModel.currentPhotos.value.single().positionPointIndex)
    }

    /** Chaque position intermédiaire écrase la précédente : seule la dernière compte à la sauvegarde. */
    @Test
    fun seuleLaDernierePositionDuGlissementEstEnregistree() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        viewModel.updatePhotoPlacementPosition(photoId, 1)
        viewModel.updatePhotoPlacementPosition(photoId, 0)
        saveAndWait { storedPositionIndex() == 0 }

        assertEquals(0, storedPositionIndex())
    }

    @Test
    fun laDisquetteEcritLaNouvellePositionEnBase() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        viewModel.updatePhotoPlacementPosition(photoId, 1)
        saveAndWait { storedPositionIndex() == 1 }

        assertEquals(1, storedPositionIndex())
        assertFalse(viewModel.photosDirty.value)
    }

    @Test
    fun abandonnerLEditionOublieLeRepositionnement() {
        val entry = openTrack()
        val photoId = importAndSave(entry)
        val positionAvant = storedPositionIndex()

        viewModel.updatePhotoPlacementPosition(photoId, 1)
        viewModel.discardPhotoEdits()
        settle()

        assertFalse(viewModel.photosDirty.value)
        assertEquals(positionAvant, viewModel.currentPhotos.value.single().positionPointIndex)
        assertEquals(positionAvant, storedPositionIndex())
    }

    /** Terminer le mode placement ne perd pas la position déjà posée par le glissement. */
    @Test
    fun terminerLePlacementNePerdPasLaPositionPosee() {
        val entry = openTrack()
        val photoId = importAndSave(entry)
        val photo = viewModel.currentPhotos.value.single()

        viewModel.requestPhotoPlacement(photo)
        viewModel.updatePhotoPlacementPosition(photoId, 1)
        viewModel.exitPhotoPlacement()
        settle()

        assertNull(viewModel.photoPlacementTarget.value)
        assertTrue(viewModel.photosDirty.value)
        saveAndWait { storedPositionIndex() == 1 }
        assertEquals(1, storedPositionIndex())
    }

    // --- Une photo encore en transit se comporte comme les autres --------------------------------

    @Test
    fun unePhotoEncoreEnTransitPortSaLegendeSaVisibiliteEtSaPositionALInsert() {
        val entry = openTrack()
        viewModel.addPhotos(listOf(photoUri(9)))
        waitUntil("le lot n'est pas arrivé en transit") {
            viewModel.photoOperationProgress.value == null && viewModel.photosDirty.value
        }
        val transitId = viewModel.currentPhotos.value.single().id
        assertTrue("un ajout en transit porte un id d'affichage négatif", transitId < 0)

        viewModel.applyPhotoCaption(transitId, "Photo du col")
        viewModel.togglePhotoShownOnMap(viewModel.currentPhotos.value.single())
        viewModel.updatePhotoPlacementPosition(transitId, 1)
        settle()

        saveAndWait { runBlocking { repository.listPhotos(entry.id) }.size == 1 }

        val saved = runBlocking { repository.listPhotos(entry.id) }.single()
        assertEquals("Photo du col", saved.caption)
        assertFalse(saved.shownOnMap)
        assertEquals(1, saved.positionPointIndex)
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

    private fun storedCaption(): String? =
        runBlocking { repository.listPhotos(trackId) }.single().caption

    private fun storedShownOnMap(): Boolean =
        runBlocking { repository.listPhotos(trackId) }.single().shownOnMap

    private fun storedPositionIndex(): Int? =
        runBlocking { repository.listPhotos(trackId) }.single().positionPointIndex

    private suspend fun createTrack(): LoggedTrackEntity {
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test lot 2",
        )
        val entry = LoggedTrackEntity(
            id = trackId,
            name = "Trace test lot 2",
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
