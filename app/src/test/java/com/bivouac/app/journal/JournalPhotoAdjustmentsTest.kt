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
import com.bivouac.app.data.photo.NormalizedCropRect
import com.bivouac.app.data.photo.PhotoAdjustments
import com.bivouac.app.data.photo.adjustments
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-143 / RIC-144 : les ajustements sont des modifications du mode édition, transactionnelles
 * comme la note, les tags et les photos.
 *
 * ⚠️ Le premier test de cette classe est celui qui compte le plus, et il existe à cause de la leçon
 * RIC-149 : si le drapeau « il y a quelque chose à enregistrer » n'inclut pas les ajustements, la
 * croix de l'écran sort par « rien à enregistrer » et tout le travail de recadrage disparaît sans
 * un mot. C'est exactement le genre de perte silencieuse que ce projet a déjà payée une fois.
 *
 * Le reste tient l'aller-retour complet par le VRAI chemin de l'écran : ajuster, enregistrer,
 * relire les colonnes ; et ajuster, abandonner, vérifier que rien n'a bougé en base.
 */
@RunWith(RobolectricTestRunner::class)
class JournalPhotoAdjustmentsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var viewModel: JournalViewModel

    private val trackId = "ric143-ajustements"

    private val someAdjustments = PhotoAdjustments(
        rotationQuarterTurns = 1,
        cropRect = NormalizedCropRect(0.25f, 0.25f, 0.75f, 0.75f),
    )

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

    /**
     * LE test de ce lot. Une édition qui n'a fait qu'ajuster une photo DOIT se déclarer modifiée :
     * c'est ce drapeau que l'écran agrège pour allumer la disquette et pour proposer d'enregistrer
     * au lieu de sortir tout droit (voir ThreeStopJournalDetail.isDirty).
     */
    @Test
    fun ajusterUnePhotoMarqueLEditionCommeModifiee() {
        val entry = openTrack()
        val photoId = importAndSave(entry)
        assertFalse("rien ne doit être en attente après un enregistrement", viewModel.photosDirty.value)

        viewModel.applyPhotoAdjustments(photoId, someAdjustments)
        settle()

        assertTrue(
            "sans ça, la croix sortirait par « rien à enregistrer » et le recadrage serait perdu",
            viewModel.photosDirty.value,
        )
    }

    /**
     * Le pendant du précédent : revenir exactement à ce que la photo portait déjà n'est PAS une
     * modification. Ouvrir l'éditeur et valider sans rien toucher ne doit pas proposer d'enregistrer
     * un geste qui n'a pas eu lieu.
     */
    @Test
    fun revenirALEtatEnregistreNeLaisseRienEnAttente() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        viewModel.applyPhotoAdjustments(photoId, someAdjustments)
        viewModel.applyPhotoAdjustments(photoId, PhotoAdjustments.NONE)
        settle()

        assertFalse(viewModel.photosDirty.value)
    }

    /** L'ajustement se voit tout de suite dans ce que l'écran affiche, avant tout enregistrement. */
    @Test
    fun lAjustementEnAttenteSeVoitAvantMemeDEtreEnregistre() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        viewModel.applyPhotoAdjustments(photoId, someAdjustments)
        settle()

        assertEquals(someAdjustments, viewModel.currentPhotos.value.single().adjustments)
        // Mais la base, elle, n'a pas bougé : c'est ça, « en attente ».
        assertEquals(PhotoAdjustments.NONE, storedAdjustments())
    }

    /** La disquette écrit les colonnes, et le fichier n'est pas touché. */
    @Test
    fun laDisquetteEcritLesAjustementsEnBase() {
        val entry = openTrack()
        val photoId = importAndSave(entry)
        val photoBytesBefore = photoFileBytes()

        viewModel.applyPhotoAdjustments(photoId, someAdjustments)
        saveAndWait { storedAdjustments() == someAdjustments }

        assertEquals(someAdjustments, storedAdjustments())
        assertFalse(viewModel.photosDirty.value)
        // Non destructif : les octets de la copie locale sont exactement les mêmes qu'avant.
        assertTrue("le fichier ne doit pas avoir été réécrit", photoBytesBefore.contentEquals(photoFileBytes()))
    }

    /** Abandonner l'édition rend la photo telle qu'elle était, sans rien écrire. */
    @Test
    fun abandonnerLEditionOublieLesAjustements() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        viewModel.applyPhotoAdjustments(photoId, someAdjustments)
        viewModel.discardPhotoEdits()
        settle()

        assertFalse(viewModel.photosDirty.value)
        assertEquals(PhotoAdjustments.NONE, viewModel.currentPhotos.value.single().adjustments)
        assertEquals(PhotoAdjustments.NONE, storedAdjustments())
    }

    /**
     * Une photo encore en transit (ajoutée dans la même édition, jamais enregistrée) s'ajuste comme
     * les autres, et ses ajustements partent avec l'insert de sa ligne : elle n'a jamais existé
     * sans eux.
     */
    @Test
    fun unePhotoEncoreEnTransitSAjusteEtGardeSesAjustementsALInsert() {
        val entry = openTrack()
        viewModel.addPhotos(listOf(photoUri(9)))
        waitUntil("le lot n'est pas arrivé en transit") {
            viewModel.photoOperationProgress.value == null && viewModel.photosDirty.value
        }
        val transitId = viewModel.currentPhotos.value.single().id
        assertTrue("un ajout en transit porte un id d'affichage négatif", transitId < 0)

        viewModel.applyPhotoAdjustments(transitId, someAdjustments)
        settle()
        assertEquals(someAdjustments, viewModel.currentPhotos.value.single().adjustments)

        saveAndWait { runBlocking { repository.listPhotos(entry.id) }.size == 1 }

        assertEquals(someAdjustments, storedAdjustments())
    }

    /**
     * Supprimer une photo qu'on venait d'ajuster n'écrit rien sur la ligne qui part : l'ajustement
     * en attente s'en va avec elle.
     */
    @Test
    fun supprimerUnePhotoAjusteeNEcritRienSurElle() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        viewModel.applyPhotoAdjustments(photoId, someAdjustments)
        viewModel.requestDeletePhoto(viewModel.currentPhotos.value.single())
        viewModel.confirmDeletePhoto()
        settle()

        assertTrue("la suppression, elle, reste en attente", viewModel.photosDirty.value)
        saveAndWait { runBlocking { repository.listPhotos(entry.id) }.isEmpty() }
        assertTrue(runBlocking { repository.listPhotos(entry.id) }.isEmpty())
    }

    // --- Mise en place -------------------------------------------------------------------------

    private fun openTrack(): LoggedTrackEntity {
        val entry = runBlocking { createTrack() }
        viewModel = JournalViewModel(application)
        viewModel.openTrack(entry)
        waitUntil("la trace ne s'est pas ouverte") { viewModel.uiState.value is JournalUiState.Detail }
        return entry
    }

    // Le vrai geste de l'écran : le sélecteur remplit le transit, la disquette enregistre. Rend
    // l'identifiant de la photo une fois en base.
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
        // Les écritures se terminent un cran AVANT que le verrou d'exclusion ne soit rendu (il l'est
        // dans le finally de saveDetails) : sans cette attente, une opération enchaînée se ferait
        // refuser par le registre.
        waitUntil("le verrou d'exclusion n'a jamais été rendu") {
            ExclusiveOperations.current.value == null
        }
        waitUntil("le brouillon photo n'a pas été vidé") { !viewModel.photosDirty.value }
    }

    private fun storedAdjustments(): PhotoAdjustments =
        runBlocking { repository.listPhotos(trackId) }.single().adjustments

    private fun photoFileBytes(): ByteArray {
        val photo = runBlocking { repository.listPhotos(trackId) }.single()
        return LoggedTrackPhotoStore.resolve(application, photo.filePath).readBytes()
    }

    private suspend fun createTrack(): LoggedTrackEntity {
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test ajustements",
        )
        val entry = LoggedTrackEntity(
            id = trackId,
            name = "Trace test ajustements",
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

    // Contenus tous différents : la déduplication par empreinte n'en garderait qu'un sinon.
    private fun photoUri(index: Int): Uri {
        val uri = Uri.parse("content://test/photo-$index")
        val bytes = ByteArray(2_048) { (index * 13 + it).toByte() }
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    /**
     * Laisse les flux du ViewModel se propager.
     *
     * [JournalViewModel.photosDirty] est un `combine(...).stateIn(...)` : deux changements enchaînés
     * sans reprise de la boucle principale ne produisent qu'une seule valeur lue, celle du premier.
     * Poser puis retirer un ajustement dans la même respiration est exactement ce cas, et c'est du
     * harnais, pas du code testé : en vrai, il y a un doigt entre les deux gestes.
     */
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
        if (!condition()) fail(message)
    }
}
